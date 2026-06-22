package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenEstimateTest {

    @Test
    fun cjkCountsPerChar() {
        assertEquals(2, TokenEstimate.estimate("你好"))
    }

    @Test
    fun asciiCountsRoughlyQuarter() {
        // 8 ascii chars -> ~2 tokens
        assertEquals(2, TokenEstimate.estimate("abcdefgh"))
    }

    @Test
    fun sumsAcrossTexts() {
        assertTrue(TokenEstimate.estimateAll(listOf("你好", "world")) >= 3)
    }
}
