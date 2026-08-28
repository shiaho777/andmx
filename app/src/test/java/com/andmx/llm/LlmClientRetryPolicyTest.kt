package com.andmx.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmClientRetryPolicyTest {

    @Test
    fun retryableStatusesMatchZcodeAiSdkPolicy() {
        assertTrue(LlmClient.isRetryableStatus(408))
        assertTrue(LlmClient.isRetryableStatus(409))
        assertTrue(LlmClient.isRetryableStatus(429))
        assertTrue(LlmClient.isRetryableStatus(500))
        assertTrue(LlmClient.isRetryableStatus(503))
        assertTrue(LlmClient.isRetryableStatus(599))
        assertFalse(LlmClient.isRetryableStatus(400))
        assertFalse(LlmClient.isRetryableStatus(401))
        assertFalse(LlmClient.isRetryableStatus(403))
        assertFalse(LlmClient.isRetryableStatus(404))
        assertFalse(LlmClient.isRetryableStatus(422))
    }

    @Test
    fun parseRetryAfterSecondsAndMs() {
        assertEquals(12_000L, LlmClient.parseRetryAfterMs("12"))
        assertEquals(250L, LlmClient.parseRetryAfterMs("250", headerNameMs = true))
        assertNull(LlmClient.parseRetryAfterMs(null))
        assertNull(LlmClient.parseRetryAfterMs("bogus"))
        assertTrue(LlmClient.parseRetryAfterMs("1970-01-01T00:00:00Z")!! >= 0)
    }

    @Test
    fun retryableErrorCarriesStatusCode() {
        val e = RetryableHttpException(503, "overloaded", retryAfterMs = 4000)
        assertEquals(503, e.statusCode)
        assertEquals(4000L, e.retryAfterMs)
        assertTrue(e.message!!.startsWith("HTTP 503"))
    }

    @Test
    fun rateLimitCarriesOptionalHint() {
        val e = RateLimitException("slow down")
        assertNull(e.retryAfterMs)
        e.retryAfterMs = 1500
        assertEquals(1500L, e.retryAfterMs)
    }
}
