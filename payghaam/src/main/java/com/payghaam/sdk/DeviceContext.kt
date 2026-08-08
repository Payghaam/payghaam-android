package com.payghaam.sdk

import android.content.Context
import android.os.Build
import java.util.TimeZone

internal object DeviceContext {
    const val SDK_VERSION = "0.1.0"

    fun collect(
        context: Context,
        sessionStart: Boolean = false,
        pushPermission: String? = null,
    ): Map<String, Any?> {
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
        return buildMap {
            put("country", locale.country)
            put("language", locale.language)
            put("timezone", TimeZone.getDefault().id)
            put("os", "Android")
            put("osVersion", Build.VERSION.RELEASE)
            appVersion?.let { put("appVersion", it) }
            put("deviceModel", Build.MODEL)
            put("sdkVersion", SDK_VERSION)
            pushPermission?.let { put("pushPermission", it) }
            if (sessionStart) put("sessionStart", true)
        }
    }
}

internal class SessionTracker {
    private var lastSessionAt: Long = 0
    private var countedThisLaunch = false

    /** Returns true when a new session should be reported (30 min gap or first launch). */
    fun noteForeground(now: Long = System.currentTimeMillis()): Boolean {
        if (countedThisLaunch && now - lastSessionAt < SESSION_GAP_MS) return false
        if (lastSessionAt > 0 && now - lastSessionAt < SESSION_GAP_MS) return false
        lastSessionAt = now
        countedThisLaunch = true
        return true
    }

    companion object {
        private const val SESSION_GAP_MS = 30 * 60 * 1000L
    }
}
