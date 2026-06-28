package com.engagekaro.sdk

/**
 * SDK configuration — use an SDK-type API key from the dashboard (not REST).
 */
data class EngageKaroConfig(
    val appId: String,
    val apiKey: String,
    val baseUrl: String,
    val requireConsent: Boolean = false,
    val timeoutMs: Long = 15_000,
) {
    val apiRoot: String
        get() {
            val b = baseUrl.trimEnd('/')
            return "$b/api/sdk"
        }
}

enum class SubscriptionType(val wire: String) {
    ANDROID_PUSH("ANDROID_PUSH"),
    IOS_PUSH("IOS_PUSH"),
    WEB_PUSH("WEB_PUSH"),
    EMAIL("EMAIL"),
    SMS("SMS"),
}

class EngageKaroApiException(val statusCode: Int, val body: String) :
    Exception("EngageKaroApiException($statusCode): $body")
