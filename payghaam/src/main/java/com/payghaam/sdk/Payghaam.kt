package com.payghaam.sdk

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Payghaam Android SDK — identify users, register FCM tokens, tags, and events.
 *
 * ```kotlin
 * Payghaam.initialize(
 *     applicationContext,
 *     PayghaamConfig(appId = "...", apiKey = "ek_client_...", baseUrl = "https://api.host"),
 * )
 * Payghaam.login("user-123")
 * Payghaam.requestPushPermission(activity)
 * Payghaam.trackEvent("purchase", mapOf("sku" to "x"))
 * ```
 */
object Payghaam {
    private lateinit var appContext: Context
    private lateinit var config: PayghaamConfig
    private lateinit var api: ApiClient

    private var externalId: String? = null
    private var consentGiven = true
    private var pendingPushToken: String? = null
    private val sessions = SessionTracker()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startedActivities = 0

    /**
     * Invoked when the user taps a notification, with the full push payload —
     * your custom `data` keys plus Payghaam's own `ek_*` keys.
     *
     * Set this to route taps yourself:
     * ```kotlin
     * Payghaam.onNotificationOpened = { payload ->
     *     payload["targetId"]?.let { openOffer(it) }
     * }
     * ```
     * If you leave it null, the SDK opens `ek_url` (the campaign's deep link)
     * itself. Registering a handler suppresses that — routing becomes yours,
     * including the deep link, which you can read from `payload["ek_url"]`.
     *
     * A tap that lands before you set this is buffered and replayed on assignment,
     * so a cold start from a notification is never dropped.
     */
    var onNotificationOpened: ((Map<String, String>) -> Unit)? = null
        set(value) {
            field = value
            if (value == null) return
            val buffered = pendingOpened ?: return
            pendingOpened = null
            value(buffered)
        }

    private var pendingOpened: Map<String, String>? = null

    val isInitialized: Boolean
        get() = this::config.isInitialized

    private val canSend: Boolean
        get() = isInitialized && (!config.requireConsent || consentGiven)

    /** Call once from [Application.onCreate] or your main Activity. */
    fun initialize(context: Context, cfg: PayghaamConfig) {
        setupCore(context, cfg)
        registerForegroundTracking(appContext)
    }

    /**
     * The state [initialize] sets up, minus activity-lifecycle registration and
     * automatic tap/deep-link dispatch. Used directly by [persistBridgeConfig] and
     * [restoreIfNeeded]: wrapper SDKs (Flutter, React Native) already have their own
     * platform-idiomatic tap handling and foreground/session tracking on the Dart/JS
     * side, so registering this class's copy too would double-dispatch taps and
     * double-report device-context pings.
     */
    private fun setupCore(context: Context, cfg: PayghaamConfig) {
        appContext = context.applicationContext
        config = cfg
        api = ApiClient(cfg)
        api.queue = OfflineQueue(appContext) { method, path, body ->
            api.rawRequest(method, path, body)
        }
        consentGiven = !cfg.requireConsent
        externalId = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EXTERNAL_ID, null)
        PushDeliveryBridge.externalIdProvider = { externalId }
        PushDeliveryBridge.apiClientProvider = { if (canSend) api else null }
    }

    private fun registerForegroundTracking(context: Context) {
        val app = context.applicationContext as? Application ?: return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                startedActivities++
                if (startedActivities == 1) onForeground()
                // Covers the warm path: a singleTop activity gets the tap through
                // onNewIntent, and only sees it here if the app called setIntent().
                handleNotificationIntent(activity.intent)
                // Resume is the earliest point at which the host app's onCreate body
                // has finished, so it is the first moment we can trust that a null
                // onNotificationOpened really means "no handler" rather than "not
                // registered yet". Deciding any earlier would auto-open deep links
                // out from under apps that do their own routing.
                flushPendingOpened()
            }

            override fun onActivityPaused(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {
                // Cold start from a notification tap.
                handleNotificationIntent(a.intent)
            }
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

    /**
     * Feed an Activity intent that may have come from a notification tap.
     *
     * The SDK reads the launch intent automatically, but Android delivers taps to
     * an already-running `singleTop`/`singleTask` activity through `onNewIntent`,
     * which does not update `getIntent()`. If your launcher activity uses either
     * mode, forward it:
     * ```kotlin
     * override fun onNewIntent(intent: Intent) {
     *     super.onNewIntent(intent)
     *     Payghaam.handleNotificationIntent(intent)
     * }
     * ```
     * Safe to call with any intent — non-Payghaam ones are ignored, and a given
     * tap is only ever dispatched once.
     */
    fun handleNotificationIntent(intent: android.content.Intent?) {
        val payload = PayghaamNotifications.payloadFrom(intent) ?: return

        payload[PayghaamNotifications.KEY_MESSAGE_ID]?.let { id ->
            scope.launch { reportPushReceipt(id, "opened", payload) }
        }

        val handler = onNotificationOpened
        if (handler != null) {
            handler(payload)
        } else {
            // Hold it: the app may still be mid-onCreate. flushPendingOpened()
            // settles this on resume.
            pendingOpened = payload
        }
    }

    /** Deliver (or auto-open) a tap that arrived before the app could handle it. */
    private fun flushPendingOpened() {
        val payload = pendingOpened ?: return
        pendingOpened = null

        val handler = onNotificationOpened
        if (handler != null) {
            handler(payload)
            return
        }
        // No handler anywhere in the app — fall back to opening the campaign's
        // deep link so links work with zero integration code.
        val url = payload[PayghaamNotifications.KEY_URL]
        if (!url.isNullOrBlank() && this::appContext.isInitialized) {
            PayghaamNotifications.openUrl(appContext, url)
        }
    }

    /** Called on app foreground; also wired automatically via activity lifecycle. */
    fun onForeground() {
        scope.launch {
            pingDeviceContext(sessionStart = true)
            // Drain anything queued while the device was offline.
            runCatching { api.queue?.flush() }
        }
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

    /** Report a push delivery/engagement receipt (delivered | opened | clicked). */
    suspend fun reportPushReceipt(
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

    // ── Cross-platform wrapper support (Flutter, React Native) ─────────────────
    //
    // Those SDKs run their own JavaScript/Dart-level `initialize()` and never call
    // this class's `initialize()` directly, so `isInitialized` is normally false in
    // a process wrapper apps run. But FCM can still start that process solely to
    // deliver a push, with no Dart/JS engine alive to handle it — the messaging
    // service (running here, in plain Kotlin, regardless of wrapper) is the only
    // code that runs. `persistBridgeConfig`/`restoreIfNeeded` let a wrapper's own
    // native bridge (its "shareConfig" method channel handler) hand just enough
    // config to this class so headless delivery can still be reported, without the
    // wrapper's FCM service reimplementing the HTTP client itself. See
    // sdk-native-wrapper-design.md.

    /**
     * Called by a wrapper SDK's own native bridge (not host apps directly) whenever
     * its JS/Dart layer shares config for headless operation. Safe to call
     * repeatedly — e.g. once per `login()` to keep `externalId` current.
     */
    fun persistBridgeConfig(
        context: Context,
        apiKey: String,
        baseUrl: String,
        externalId: String?,
        identityHash: String? = null,
    ) {
        val ctx = context.applicationContext
        val trimmedKey = apiKey.trim()
        val trimmedBase = baseUrl.trim()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString(KEY_CFG_API_KEY, trimmedKey)
            putString(KEY_CFG_BASE_URL, trimmedBase)
            if (externalId != null) putString(KEY_EXTERNAL_ID, externalId)
        }.apply()

        // Rebind whenever key/baseUrl actually changed, not just on first call —
        // the process can outlive a single init (e.g. Flutter hot restart).
        val needsCore =
            !isInitialized ||
                config.apiKey != trimmedKey ||
                config.baseUrl != trimmedBase
        if (needsCore) {
            setupCore(ctx, PayghaamConfig(appId = "", apiKey = trimmedKey, baseUrl = trimmedBase))
        }
        if (externalId != null) this.externalId = externalId
        if (identityHash != null) api.identityHash = identityHash
    }

    /**
     * Rehydrates from a [persistBridgeConfig] snapshot if this process was never
     * `initialize()`d — e.g. a Flutter/RN app woken solely by FCM. Returns true if
     * already initialized or if it just restored; false if there is nothing to
     * restore (the wrapper's `shareConfig` was never called).
     */
    fun restoreIfNeeded(context: Context): Boolean {
        if (isInitialized) return true
        val ctx = context.applicationContext
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val apiKey = prefs.getString(KEY_CFG_API_KEY, null) ?: return false
        val baseUrl = prefs.getString(KEY_CFG_BASE_URL, null) ?: return false
        setupCore(ctx, PayghaamConfig(appId = "", apiKey = apiKey, baseUrl = baseUrl))
        return true
    }

    /**
     * Clears the identity [persistBridgeConfig] set — call from a wrapper's
     * `logout()`. [persistBridgeConfig] only ever *sets* `externalId` (so a
     * repeated call can't accidentally wipe it); this is the explicit clear path.
     */
    fun clearBridgeIdentity(context: Context) {
        externalId = null
        if (this::api.isInitialized) api.identityHash = null
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_EXTERNAL_ID).apply()
    }

    // ── Cross-platform wrapper support: raw API pass-through ───────────────────
    //
    // Phase 2 of the wrapper refactor (see sdk-native-wrapper-design.md §4.5):
    // sdks/flutter and sdks/react-native used to ship their own HTTP clients and
    // offline queues in Dart/JS, duplicating everything below a third and fourth
    // time. These proxy a single call through the canonical `api` (+ its offline
    // queue) with none of `login()`'s own session-ping/token-registration side
    // effects — the wrapper's Dart/JS layer already owns that orchestration (tap
    // dispatch, deep-link fallback, foreground session timing) and calls these
    // only for the network leg. Requires [persistBridgeConfig] to have run first.

    /** Raw identify call — the wrapper's own device-context snapshot goes in [deviceContext]. */
    suspend fun bridgeIdentify(deviceContext: Map<String, Any?>? = null) {
        if (!canSend) return
        withContext(Dispatchers.IO) { api.identify(externalId, deviceContext = deviceContext) }
    }

    /** Generic channel subscription (push token, email, SMS) for the current user. */
    suspend fun bridgeAddSubscription(
        type: SubscriptionType,
        token: String,
        deviceModel: String? = null,
        deviceOs: String? = null,
        appVersion: String? = null,
    ) {
        if (!canSend) return
        val id = externalId ?: return
        withContext(Dispatchers.IO) {
            api.addSubscription(id, type, token, deviceModel, deviceOs, appVersion)
        }
    }

    /** Drains anything the offline queue accumulated. Safe to call repeatedly. */
    suspend fun bridgeFlushQueue() {
        if (!this::api.isInitialized) return
        withContext(Dispatchers.IO) { runCatching { api.queue?.flush() } }
    }

    /**
     * Optional hook for wrapper SDKs to forward a push's raw data payload to their
     * own runtime (e.g. Dart's `onForeground` stream), mirroring
     * [onNotificationOpened]. The bundled native [PayghaamFirebaseMessagingService]
     * does not need this — it is only for cross-platform wrappers.
     */
    var onMessageReceivedHook: ((Map<String, String>) -> Unit)? = null

    /** Draws the tray notification for [message], with no receipt reporting. */
    fun showNotification(context: Context, message: RemoteMessage) {
        PayghaamNotifications.show(context, message)
    }

    /**
     * Full handling for an FCM [message]: draws the notification and reports a
     * `delivered` receipt, then calls [onMessageReceivedHook] if one is set. This is
     * what the bundled [PayghaamFirebaseMessagingService] does; wrapper SDKs whose
     * own FCM service subclass wants the same behavior (rather than just
     * [showNotification]) should call [restoreIfNeeded] first, then this.
     */
    fun handleRemoteMessage(context: Context, message: RemoteMessage) {
        showNotification(context, message)
        val messageId = message.data[PayghaamNotifications.KEY_MESSAGE_ID]
        if (messageId != null) {
            scope.launch { reportPushReceipt(messageId, "delivered", message.data) }
        }
        onMessageReceivedHook?.invoke(message.data)
    }

    private const val PREFS = "payghaam_sdk"
    private const val KEY_EXTERNAL_ID = "external_id"
    private const val KEY_CFG_API_KEY = "cfg_api_key"
    private const val KEY_CFG_BASE_URL = "cfg_base_url"
}

/** Hooks used by [PayghaamFirebaseMessagingService] for receipts. */
internal object PushDeliveryBridge {
    var externalIdProvider: () -> String? = { null }
    var apiClientProvider: () -> ApiClient? = { null }
}
