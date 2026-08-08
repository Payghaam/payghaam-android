package com.payghaam.sdk

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** Thin REST client for the `/api/sdk` endpoints. */
internal class ApiClient(private val config: PayghaamConfig) {
    var identityHash: String? = null

    /** Installed by Payghaam.initialize; parks retryable failures offline. */
    var queue: OfflineQueue? = null

    private fun headers(): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        put("Authorization", "Bearer ${config.apiKey}")
        put("X-Api-Key", config.apiKey)
        identityHash?.let { put("X-Payghaam-Identity-Hash", it) }
    }

    fun identify(
        externalId: String?,
        tags: Map<String, Any?>? = null,
        deviceContext: Map<String, Any?>? = null,
    ): JSONObject = post("/users", JSONObject().apply {
        externalId?.let { put("externalId", it) }
        tags?.let { put("tags", JSONObject(it)) }
        deviceContext?.forEach { (k, v) ->
            if (v != null) put(k, v)
        }
    })

    fun track(
        name: String,
        externalId: String?,
        properties: Map<String, Any?>? = null,
        deviceContext: Map<String, Any?>? = null,
    ): JSONObject = post("/events", JSONObject().apply {
        put("name", name)
        externalId?.let { put("externalId", it) }
        properties?.let { put("properties", JSONObject(it)) }
        deviceContext?.forEach { (k, v) ->
            if (v != null) put(k, v)
        }
    })

    fun addSubscription(
        externalId: String,
        type: SubscriptionType,
        token: String,
        deviceModel: String? = null,
        deviceOs: String? = null,
        appVersion: String? = null,
    ): JSONObject = post("/users/$externalId/subscriptions", JSONObject().apply {
        put("type", type.wire)
        put("token", token)
        deviceModel?.let { put("deviceModel", it) }
        deviceOs?.let { put("deviceOs", it) }
        appVersion?.let { put("appVersion", it) }
    })

    fun updateTags(externalId: String, tags: Map<String, Any?>): JSONObject =
        put("/users/$externalId/tags", JSONObject().apply { put("tags", JSONObject(tags)) })

    fun reportReceipt(
        messageId: String,
        event: String,
        externalId: String?,
        properties: Map<String, Any?>? = null,
    ): JSONObject = post("/receipts", JSONObject().apply {
        put("messageId", messageId)
        put("event", event)
        externalId?.let { put("externalId", it) }
        properties?.let { put("properties", JSONObject(it)) }
    })

    // Fire-and-forget path: send now, or park in the offline queue on a
    // retryable failure so offline activity isn't lost.
    private fun post(path: String, body: JSONObject): JSONObject =
        sendOrQueue("POST", path, body)

    private fun put(path: String, body: JSONObject): JSONObject =
        sendOrQueue("PUT", path, body)

    private fun sendOrQueue(method: String, path: String, body: JSONObject): JSONObject {
        return try {
            val res = request(method, path, body)
            queue?.flush()
            res
        } catch (t: Throwable) {
            val q = queue
            if (q != null && OfflineQueue.isRetryable(t)) {
                // Silently parked by design (fire-and-forget), but that also
                // means this is the ONLY place a caller ever finds out —
                // nothing else logs or surfaces this. Filter logcat by tag
                // "Payghaam" when a call seems to vanish.
                Log.w("Payghaam", "$method ${config.apiRoot}$path failed, queued for retry: $t")
                q.enqueue(method, path, body)
                JSONObject()
            } else {
                Log.e("Payghaam", "$method ${config.apiRoot}$path failed (non-retryable): $t")
                throw t
            }
        }
    }

    /** Direct request without queueing — used by OfflineQueue's drain. */
    internal fun rawRequest(method: String, path: String, body: JSONObject): JSONObject =
        request(method, path, body)

    private fun request(method: String, path: String, body: JSONObject): JSONObject {
        val url = URL("${config.apiRoot}$path")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = config.timeoutMs.toInt()
            readTimeout = config.timeoutMs.toInt()
            doOutput = true
            headers().forEach { (k, v) -> setRequestProperty(k, v) }
        }
        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
        if (code !in 200..299) throw PayghaamApiException(code, text)
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}
