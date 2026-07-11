package com.engagekaro.sdk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Persistent offline queue for fire-and-forget SDK calls (events, tags,
 * receipts, subscriptions). Calls that fail with a retryable error (network,
 * timeout, 5xx) are parked in SharedPreferences and drained FIFO on the next
 * app-foreground / successful send — offline activity is no longer lost.
 */
internal class OfflineQueue(
    context: Context,
    private val sender: (method: String, path: String, body: JSONObject) -> Unit,
) {
    private val prefs = context.getSharedPreferences("engagekaro_queue", Context.MODE_PRIVATE)

    @Volatile
    private var flushing = false

    companion object {
        private const val KEY = "queue_v1"
        private const val MAX_QUEUE = 200
        private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

        /** 4xx (except 408/429) will never be accepted; everything else retries. */
        fun isRetryable(t: Throwable): Boolean = when (t) {
            is EngageKaroApiException ->
                t.statusCode == 408 || t.statusCode == 429 || t.statusCode >= 500
            is IOException -> true
            else -> true
        }
    }

    @Synchronized
    private fun load(): MutableList<JSONObject> {
        val raw = prefs.getString(KEY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val cutoff = System.currentTimeMillis() - MAX_AGE_MS
            MutableList(arr.length()) { arr.getJSONObject(it) }
                .filter { it.optLong("queuedAt") > cutoff }
                .toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    private fun save(ops: List<JSONObject>) {
        val arr = JSONArray()
        ops.forEach { arr.put(it) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    @Synchronized
    fun enqueue(method: String, path: String, body: JSONObject) {
        val ops = load()
        ops.add(
            JSONObject()
                .put("method", method)
                .put("path", path)
                .put("body", body)
                .put("queuedAt", System.currentTimeMillis()),
        )
        while (ops.size > MAX_QUEUE) ops.removeAt(0)
        save(ops)
    }

    /**
     * Drain FIFO. Stops at the first retryable failure (still offline); drops
     * ops the server permanently rejects. Safe to call repeatedly.
     */
    fun flush() {
        if (flushing) return
        flushing = true
        try {
            while (true) {
                val ops = load()
                val op = ops.firstOrNull() ?: return
                try {
                    sender(op.getString("method"), op.getString("path"), op.getJSONObject("body"))
                } catch (t: Throwable) {
                    if (isRetryable(t)) return // still offline — retry later
                    // permanently rejected: fall through and drop
                }
                val after = load()
                if (after.isNotEmpty()) after.removeAt(0)
                save(after)
            }
        } finally {
            flushing = false
        }
    }
}
