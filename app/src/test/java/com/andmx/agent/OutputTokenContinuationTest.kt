package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputTokenContinuationTest {
    @Test
    fun lengthWithoutToolCallsContinuesUntilThree() {
        assertEquals(
            OutputTokenContinuation.Decision.CONTINUE,
            OutputTokenContinuation.classify("length", null, toolCallCount = 0, continuationCount = 0),
        )
        assertEquals(
            OutputTokenContinuation.Decision.CONTINUE,
            OutputTokenContinuation.classify("length", null, toolCallCount = 0, continuationCount = 2),
        )
        assertEquals(
            OutputTokenContinuation.Decision.EXHAUSTED,
            OutputTokenContinuation.classify("length", null, toolCallCount = 0, continuationCount = 3),
        )
    }

    @Test
    fun toolCallsSuppressContinuation() {
        assertEquals(
            OutputTokenContinuation.Decision.NONE,
            OutputTokenContinuation.classify("length", "max_tokens", toolCallCount = 1, continuationCount = 0),
        )
    }

    @Test
    fun rawLimitReasonsContinue() {
        for (raw in listOf("max_tokens", "max_output_tokens", "model_context_window_exceeded")) {
            assertEquals(
                raw,
                OutputTokenContinuation.Decision.CONTINUE,
                OutputTokenContinuation.classify(raw, raw, toolCallCount = 0, continuationCount = 0),
            )
        }
    }

    @Test
    fun otherFinishReasonsDoNotContinue() {
        assertEquals(
            OutputTokenContinuation.Decision.NONE,
            OutputTokenContinuation.classify("stop", "end_turn", toolCallCount = 0, continuationCount = 0),
        )
    }
}
