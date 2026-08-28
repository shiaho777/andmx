package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompactBudgetTest {

    private val compactor = ContextCompactor(client = FakeNoopLlm)

    @Test
    fun effectiveWindowSubtractsOutputReserve() {
        assertEquals(200_000 - 32_000, compactor.effectiveContextWindow(200_000))
    }

    @Test
    fun thresholdIsMinOfNinetyFivePercentAndBufferedWindow() {
        val effective = compactor.effectiveContextWindow(200_000)
        val threshold = compactor.autoCompactThresholdTokens(effective, outputReserve = 32_000)
        assertEquals(minOf((effective * 95 / 100), effective - 32_000 - 13_000), threshold)
        assertTrue(threshold > 0)
    }

    @Test
    fun estimatorIsCharsDivThree() {
        assertEquals(
            9,
            TokenEstimate.forCompaction(
                listOf(com.andmx.llm.ApiMessage(role = "user", content = "a".repeat(27))),
            ),
        )
    }

    @Test
    fun needsCompactionFiresOnlyPastThreshold() {
        val effective = compactor.effectiveContextWindow(200_000)
        val threshold = compactor.autoCompactThresholdTokens(effective, outputReserve = 32_000)
        val below = com.andmx.llm.ApiMessage(role = "user", content = "a".repeat(threshold * 3 - 30))
        val above = com.andmx.llm.ApiMessage(role = "user", content = "a".repeat(threshold * 3 + 30))
        assertFalse(compactor.needsCompaction(listOf(below), contextWindow = 200_000))
        assertTrue(compactor.needsCompaction(listOf(above), contextWindow = 200_000))
    }

    @Test
    fun summaryExtractionPrefersSummaryBlock() {
        val raw = "<analysis>thinking</analysis>\n<summary>\nthe real summary\n</summary>"
        val method = ContextCompactor::class.java.declaredMethods.firstOrNull { it.name == "extractSummary" }
            ?: throw AssertionError("extractSummary missing")
        method.isAccessible = true
        assertEquals("the real summary", method.invoke(compactor, raw))
    }
}

private object FakeNoopLlm : com.andmx.llm.LlmApi {
    override suspend fun chat(request: com.andmx.llm.ChatRequest): Result<com.andmx.llm.ApiMessage> =
        Result.success(com.andmx.llm.ApiMessage(role = "assistant", content = ""))
}
