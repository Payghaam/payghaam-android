package com.payghaam.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OfflineQueueTest {

    @Test
    fun testIsRetryableErrors() {
        // Network / IO errors must retry
        assertTrue(OfflineQueue.isRetryable(IOException("Connection reset")))

        // Server errors must retry
        assertTrue(OfflineQueue.isRetryable(PayghaamApiException(500, "Internal Server Error")))
        assertTrue(OfflineQueue.isRetryable(PayghaamApiException(502, "Bad Gateway")))
        assertTrue(OfflineQueue.isRetryable(PayghaamApiException(503, "Service Unavailable")))
        assertTrue(OfflineQueue.isRetryable(PayghaamApiException(504, "Gateway Timeout")))

        // Rate limit and request timeout must retry
        assertTrue(OfflineQueue.isRetryable(PayghaamApiException(408, "Request Timeout")))
        assertTrue(OfflineQueue.isRetryable(PayghaamApiException(429, "Too Many Requests")))

        // Permanent client errors must NOT retry (they get dropped to avoid poison queue)
        assertFalse(OfflineQueue.isRetryable(PayghaamApiException(400, "Bad Request")))
        assertFalse(OfflineQueue.isRetryable(PayghaamApiException(401, "Unauthorized")))
        assertFalse(OfflineQueue.isRetryable(PayghaamApiException(403, "Forbidden")))
        assertFalse(OfflineQueue.isRetryable(PayghaamApiException(404, "Not Found")))
        assertFalse(OfflineQueue.isRetryable(PayghaamApiException(409, "Conflict")))
        assertFalse(OfflineQueue.isRetryable(PayghaamApiException(422, "Unprocessable Entity")))
    }
}
