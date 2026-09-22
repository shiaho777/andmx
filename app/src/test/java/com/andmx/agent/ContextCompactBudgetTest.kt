package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompactBudgetTest {

    private val compactor = ContextCompactor(client = FakeNoopLlm)

    @Test
    fun effectiveWindowSubtractsCappedOutputReserve() {
        assertEquals(200_000 - 21_000, compactor.effectiveContextWindow(200_000))
        assertEquals(200_000 - 10_000, compactor.effectiveContextWindow(200_000, maxOutputTokens = 10_000))
        assertEquals(200_000 - 21_000, compactor.effectiveContextWindow(200_000, maxOutputTokens = 64_000))
    }

    @Test
    fun thresholdIsEffectiveWindowMinusBuffer() {
        val effective = compactor.effectiveContextWindow(200_000)
        val threshold = compactor.autoCompactThresholdTokens(effective)
        assertEquals(200_000 - 21_000 - 13_000, threshold)
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
        val threshold = compactor.autoCompactThresholdTokens(effective)
        val rounds = listOf(
            com.andmx.llm.ApiMessage(role = "assistant", content = "r1"),
            com.andmx.llm.ApiMessage(role = "assistant", content = "r2"),
        )
        val below = com.andmx.llm.ApiMessage(role = "user", content = "a".repeat(threshold * 3 - 30))
        val above = com.andmx.llm.ApiMessage(role = "user", content = "a".repeat(threshold * 3 + 30))
        assertFalse(compactor.needsCompaction(rounds + below, contextWindow = 200_000))
        assertTrue(compactor.needsCompaction(rounds + above, contextWindow = 200_000))
    }

    @Test
    fun notEnoughMessagesBlocksCompaction() {
        val huge = com.andmx.llm.ApiMessage(role = "user", content = "a".repeat(600_000))
        assertEquals(
            "not_enough_messages",
            compactor.autoCompactDecision(listOf(huge), contextWindow = 200_000).reason,
        )
        assertFalse(compactor.needsCompaction(listOf(huge), contextWindow = 200_000))
    }

    @Test
    fun circuitBreakerTripsAfterThreeFailures() {
        val rounds = listOf(
            com.andmx.llm.ApiMessage(role = "assistant", content = "r1"),
            com.andmx.llm.ApiMessage(role = "assistant", content = "r2"),
            com.andmx.llm.ApiMessage(role = "user", content = "a".repeat(600_000)),
        )
        repeat(3) { compactor.noteAutoCompactOutcome(success = false) }
        assertEquals(
            "circuit_breaker",
            compactor.autoCompactDecision(rounds, contextWindow = 200_000).reason,
        )
        compactor.noteAutoCompactOutcome(success = true)
        assertEquals(
            "above_threshold",
            compactor.autoCompactDecision(rounds, contextWindow = 200_000).reason,
        )
    }

    @Test
    fun providerUsageOverrideBeatsEstimate() {
        // 估算远超阈值，但 provider usage 基线小 → 不压缩
        val big = "a".repeat(400_000)
        val history = listOf(
            com.andmx.llm.ApiMessage(role = "assistant", content = big),
            com.andmx.llm.ApiMessage(
                role = "assistant",
                content = "done",
                tokenUsage = com.andmx.llm.TokenUsage(inputTokens = 1_000, outputTokens = 50),
            ),
            com.andmx.llm.ApiMessage(role = "tool", content = "ok"),
        )
        val decision = compactor.autoCompactDecision(history, contextWindow = 200_000)
        assertEquals("provider_usage", decision.tokenSource)
        assertEquals("below_threshold", decision.reason)
        // tokenCount = 1000 基线 + assistant 本身起增量估算（远小于阈值）
        assertTrue(decision.tokenCount < decision.estimatedTokenCount)
    }

    @Test
    fun estimateUsedWhenNoUsageAttached() {
        val rounds = listOf(
            com.andmx.llm.ApiMessage(role = "assistant", content = "r1"),
            com.andmx.llm.ApiMessage(role = "assistant", content = "r2"),
        )
        val decision = compactor.autoCompactDecision(rounds, contextWindow = 200_000)
        assertEquals("estimate", decision.tokenSource)
        assertEquals("below_threshold", decision.reason)
    }

    @Test
    fun summaryMessageAppendsTranscriptPath() {
        val with = ContextCompactor.buildCompactSummaryMessage(
            "s", suppressFollowup = true, transcriptPath = "/tmp/rollout.jsonl",
        )
        assertTrue(with.contains("read the full transcript at: /tmp/rollout.jsonl"))
        val without = ContextCompactor.buildCompactSummaryMessage("s", suppressFollowup = true)
        assertFalse(without.contains("transcript"))
    }

    @Test
    fun summaryExtractionStripsAnalysisAndPrefixesSummary() {
        val raw = "<analysis>thinking</analysis>\n<summary>\nthe real summary\n</summary>"
        val method = ContextCompactor::class.java.declaredMethods.firstOrNull { it.name == "extractSummary" }
            ?: throw AssertionError("extractSummary missing")
        method.isAccessible = true
        assertEquals("Summary:\nthe real summary", method.invoke(compactor, raw))
    }
}

private object FakeNoopLlm : com.andmx.llm.LlmApi {
    override suspend fun chat(request: com.andmx.llm.ChatRequest): Result<com.andmx.llm.ApiMessage> =
        Result.success(com.andmx.llm.ApiMessage(role = "assistant", content = ""))
}
