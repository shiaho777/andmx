package com.andmx.agent

import com.andmx.llm.ApiFunctionCall
import com.andmx.llm.ApiMessage
import com.andmx.llm.ApiToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrocompactTest {

    private fun toolResult(id: String, name: String, content: String) =
        ApiMessage(role = "tool", content = content, toolCallId = id, name = name)

    private fun assistantWithCalls(vararg ids: String) = ApiMessage(
        role = "assistant",
        toolCalls = ids.map { ApiToolCall(id = it, function = ApiFunctionCall("Read", "{}")) },
    )

    @Test
    fun noTriggerWhenBelowThresholdAndNotIdle() {
        assertNull(
            Microcompact.resolveTrigger(
                estimatedTokens = 100,
                thresholdTokens = 1000,
                lastAssistantCompletedAtMs = null,
                nowMs = 0,
            ),
        )
    }

    @Test
    fun triggersOnTokenPressure() {
        assertEquals(
            Microcompact.Trigger.TOKEN_PRESSURE,
            Microcompact.resolveTrigger(1000, 1000, lastAssistantCompletedAtMs = null, nowMs = 0),
        )
    }

    @Test
    fun triggersOnIdleAfterSixtyMinutes() {
        assertEquals(
            Microcompact.Trigger.TIME_BASED,
            Microcompact.resolveTrigger(
                estimatedTokens = 10,
                thresholdTokens = 9999,
                lastAssistantCompletedAtMs = 0,
                nowMs = 61 * 60_000L,
            ),
        )
    }

    @Test
    fun keepsRecentGroupsAndClearsOlderOnes() {
        val history = mutableListOf<ApiMessage>()
        for (round in 1..7) {
            history += assistantWithCalls("c$round")
            history += toolResult("c$round", "Bash", "output $round ".repeat(120))
        }
        val before = TokenEstimate.forCompaction(history)
        val result = Microcompact.maybeMicrocompact(
            messages = history,
            estimatedTokens = before,
            thresholdTokens = 1,
            lastAssistantCompletedAtMs = null,
            nowMs = 0,
        ) ?: throw AssertionError("expected microcompact result")

        assertEquals(2, result.clearedCount)
        assertTrue(result.tokensSaved > 0)
        assertEquals(Microcompact.CLEARED_MARKER, history[1].content)
        assertEquals(Microcompact.CLEARED_MARKER, history[3].content)
        assertTrue(history[5].content!!.startsWith("output 3"))
        assertTrue(history[13].content!!.startsWith("output 7"))
    }

    @Test
    fun skipsMediaResultsNonCompactableToolsAndMarker() {
        val history = mutableListOf<ApiMessage>(
            assistantWithCalls("c1", "c2", "c3", "c4"),
            toolResult("c1", "Bash", "with image").copy(imageUrls = listOf("data:image/png;base64,x")),
            toolResult("c2", "TodoWrite", "tracked by todo"),
            toolResult("c3", "Bash", Microcompact.CLEARED_MARKER),
            toolResult("c4", "Grep", "fresh match"),
        )
        val groups = Microcompact.compactableGroups(history)
        assertEquals(1, groups.size)
        assertEquals(listOf(4), groups[0])
    }

    @Test
    fun belowMinimumSavingsAborts() {
        val history = mutableListOf<ApiMessage>(
            assistantWithCalls("c1"),
            toolResult("c1", "Bash", "ok"),
        )
        val cleared = Microcompact.maybeMicrocompact(
            messages = history,
            estimatedTokens = Microcompact.thresholdTokens(200_000),
            thresholdTokens = Microcompact.thresholdTokens(200_000),
            lastAssistantCompletedAtMs = null,
            nowMs = 0,
        )
        assertNull(cleared)
        assertEquals("ok", history[1].content)
    }

    @Test
    fun contextWindowThresholdMatchesZcodeBudget() {
        assertEquals(
            (200_000 * 0.9f).toInt().coerceAtMost(200_000 - 2_000),
            Microcompact.thresholdTokens(200_000),
        )
    }
}
