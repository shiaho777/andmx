package com.andmx.agent

import com.andmx.llm.ApiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBreakdownTest {

    @Test
    fun classifiesSystemPromptMessagesToolsAndMcp() {
        val history = listOf(
            ApiMessage(role = "system", content = "x".repeat(600)),
            ApiMessage(role = "user", content = "y".repeat(300)),
            ApiMessage(role = "assistant", content = "z".repeat(100)),
            ApiMessage(role = "system", content = "<system-reminder>\nmeta context\n</system-reminder>"),
            ApiMessage(role = "system", content = "todo reminder body"),
        )
        val result = ContextBreakdown.compute(history, systemToolCount = 10, mcpToolCount = 3)

        assertEquals(600, result.charsFor(ContextBreakdown.Source.SYSTEM_PROMPT))
        assertEquals(400, result.charsFor(ContextBreakdown.Source.MESSAGES))
        assertTrue(result.charsFor(ContextBreakdown.Source.META_USER_CONTEXT) > 0)
        assertTrue(result.charsFor(ContextBreakdown.Source.TOOL_PROMPT) > 0)
        assertEquals(ContextBreakdown.compute(history, systemToolCount = 10, mcpToolCount = 3, toolSchemaChars = 0)
            .charsFor(ContextBreakdown.Source.SYSTEM_TOOL_SCHEMAS), 10 * 220)
        assertEquals(3 * 220, result.charsFor(ContextBreakdown.Source.MCP_TOOL_SCHEMAS))
    }

    @Test
    fun toolCallsCountTowardMessageChars() {
        val history = listOf(
            ApiMessage(
                role = "assistant",
                content = null,
                toolCalls = listOf(
                    com.andmx.llm.ApiToolCall(
                        id = "c1",
                        function = com.andmx.llm.ApiFunctionCall("Bash", "{\"command\":\"ls\"}"),
                    ),
                ),
            ),
        )
        val result = ContextBreakdown.compute(history)
        // Bash(4) + {"command":"ls"}(16) = 20 chars
        assertEquals(20, result.totalChars)
    }

    @Test
    fun withPercentIsSortedDescendingAndNormalized() {
        val history = listOf(
            ApiMessage(role = "system", content = "a".repeat(800)),
            ApiMessage(role = "user", content = "b".repeat(200)),
        )
        val result = ContextBreakdown.compute(history)
        val pairs = result.withPercent()
        assertEquals(2, pairs.size)
        assertTrue(pairs[0].second > pairs[1].second)
        assertEquals(1.0, pairs.sumOf { it.second }, 0.0001)
        assertEquals(0.8, pairs[0].second, 0.0001)
    }

    @Test
    fun emptyHistoryYieldsEmptyResult() {
        val result = ContextBreakdown.compute(emptyList())
        assertTrue(result.items.isEmpty())
        assertEquals(0, result.totalChars)
        assertTrue(result.withPercent().isEmpty())
    }

    @Test
    fun zeroCharEntriesAreDropped() {
        val history = listOf(ApiMessage(role = "user", content = ""))
        val result = ContextBreakdown.compute(history)
        assertTrue(result.items.isEmpty())
    }
}
