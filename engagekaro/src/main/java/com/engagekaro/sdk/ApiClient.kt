package com.engagekaro.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** Thin REST client for `/api/sdk/*` endpoints. */
internal class ApiClient(private val config: EngageKaroConfig) {
    var identityHash: String? = null

    private fun headers(): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        put("Authorization", "Bearer ${config.apiKey}")
        put("X-Api-Key", config.apiKey)
        identityHash?.let { put("X-Engagekaro-Identity-Hash", it) }
    }

    fun identify(
        externalId: String?,
        tags: Map<String, Any?>? = null,
    ): JSONObject = post("/users", JSONObject().apply {
        externalId?.let { put("externalId", it) }
        tags?.let { put("tags", JSONObject(it)) }
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

    fun track(
        name: String,
        externalId: String?,
        properties: Map<String, Any?>? = null,
    ): JSONObject = post("/events", JSONObject().apply {
        put("name", name)
        externalId?.let { put("externalId", it) }
        properties?.let { put("properties", JSONObject(it)) }
    })

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

    private fun post(path: String, body: JSONObject): JSONObject =
        request("POST", path, body)

    private fun put(path: String, body: JSONObject): JSONObject =
        request("PUT", path, body)

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
        if (code !in 200..299) throw EngageKaroApiException(code, text)
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}
