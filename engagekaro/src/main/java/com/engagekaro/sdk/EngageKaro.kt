package com.engagekaro.sdk

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * EngageKaro Android SDK — identify users, register FCM tokens, tags, and events.
 *
 * ```kotlin
 * EngageKaro.initialize(
 *     applicationContext,
 *     EngageKaroConfig(appId = "...", apiKey = "ek_client_...", baseUrl = "https://api.host"),
 * )
 * EngageKaro.login("user-123")
 * EngageKaro.requestPushPermission(activity)
 * EngageKaro.trackEvent("purchase", mapOf("sku" to "x"))
 * ```
 */
object EngageKaro {
    private lateinit var appContext: Context
    private lateinit var config: EngageKaroConfig
    private lateinit var api: ApiClient

    private var externalId: String? = null
    private var consentGiven = true
    private var pendingPushToken: String? = null
    private val sessions = SessionTracker()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startedActivities = 0

    val isInitialized: Boolean
        get() = this::config.isInitialized

    private val canSend: Boolean
        get() = isInitialized && (!config.requireConsent || consentGiven)

    /** Call once from [Application.onCreate] or your main Activity. */
    fun initialize(context: Context, cfg: EngageKaroConfig) {
        appContext = context.applicationContext
        config = cfg
        api = ApiClient(cfg)
        consentGiven = !cfg.requireConsent
        externalId = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EXTERNAL_ID, null)
        PushDeliveryBridge.externalIdProvider = { externalId }
        PushDeliveryBridge.apiClientProvider = { if (canSend) api else null }
        registerForegroundTracking(appContext)
    }

    private fun registerForegroundTracking(context: Context) {
        val app = context.applicationContext as? Application ?: return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                startedActivities++
                if (startedActivities == 1) onForeground()
            }

            override fun onActivityPaused(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun pushPermissionLabel(): String? = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> "granted"
        ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED -> "granted"
        else -> "denied"
    }

    private suspend fun pingDeviceContext(sessionStart: Boolean = false) {
        if (!canSend || externalId == null) return
        try {
            val countSession = sessionStart && sessions.noteForeground()
            api.identify(
                externalId,
                deviceContext = DeviceContext.collect(
                    appContext,
                    sessionStart = countSession,
                    pushPermission = pushPermissionLabel(),
                ),
            )
        } catch (_: Exception) {
            // Best-effort device profile sync.
        }
    }

    private suspend fun runPostLoginTasks() {
        val token = pendingPushToken ?: fetchFcmToken()
        if (token != null) registerPushToken(token)
        pingDeviceContext(sessionStart = true)
    }

    /** Called on app foreground; also wired automatically via activity lifecycle. */
    fun onForeground() {
        scope.launch { pingDeviceContext(sessionStart = true) }
    }

    /** Identify the current user by your backend user id. */
    suspend fun login(externalId: String, identityHash: String? = null) {
        this.externalId = externalId
        api.identityHash = identityHash
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_EXTERNAL_ID, externalId).apply()
        if (!canSend) return
        scope.launch { runPostLoginTasks() }
    }

    suspend fun logout() {
        externalId = null
        api.identityHash = null
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_EXTERNAL_ID).apply()
    }

    suspend fun trackEvent(name: String, properties: Map<String, Any?>? = null) {
        if (!canSend) return
        api.track(name, externalId, properties)
    }

    suspend fun addTag(key: String, value: Any) = addTags(mapOf(key to value))

    suspend fun addTags(tags: Map<String, Any?>) {
        if (!canSend) return
        val id = externalId ?: error("Call login() first")
        api.updateTags(id, tags)
    }

    suspend fun addEmail(email: String) {
        if (!canSend) return
        val id = externalId ?: error("Call login() first")
        api.addSubscription(id, SubscriptionType.EMAIL, email)
    }

    suspend fun addSms(phoneE164: String) {
        if (!canSend) return
        val id = externalId ?: error("Call login() first")
        api.addSubscription(id, SubscriptionType.SMS, phoneE164)
    }

    /** Returns true if notification permission is granted (or not required on API < 33). */
    fun hasPushPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Request POST_NOTIFICATIONS on Android 13+ and register the FCM token. */
    suspend fun onPushPermissionResult(granted: Boolean) {
        if (!granted) return
        val token = fetchFcmToken() ?: return
        registerPushToken(token)
    }

    suspend fun registerPushToken(token: String) {
        if (!canSend) return
        val id = externalId
        if (id == null) {
            pendingPushToken = token
            return
        }
        api.addSubscription(
            externalId = id,
            type = SubscriptionType.ANDROID_PUSH,
            token = token,
            deviceOs = "Android ${Build.VERSION.RELEASE}",
        )
        pendingPushToken = null
    }

    internal suspend fun reportPushReceipt(
        messageId: String,
        event: String,
        properties: Map<String, Any?>? = null,
    ) {
        if (!canSend) return
        api.reportReceipt(messageId, event, externalId, properties)
    }

    private suspend fun fetchFcmToken(): String? = try {
        FirebaseMessaging.getInstance().token.await()
    } catch (_: Exception) {
        null
    }

    private const val PREFS = "engagekaro_sdk"
    private const val KEY_EXTERNAL_ID = "external_id"
}

/** Hooks used by [EngagekaroFirebaseMessagingService] for receipts. */
internal object PushDeliveryBridge {
    var externalIdProvider: () -> String? = { null }
    var apiClientProvider: () -> ApiClient? = { null }
}
