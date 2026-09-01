package com.andmx.ui2.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnMetricsTest {

    @Test
    fun computesTtftAndTps() {
        val r = TurnMetrics.reading(
            turnStartMs = 1_000L,
            firstTokenMs = 2_500L,
            turnEndMs = 7_500L,
            outputTokens = 500,
        )
        assertEquals(1_500L, r.ttftMs)
        assertEquals(500, r.outputTokens)
        assertEquals(500 * 1000.0 / 5_000, r.tokensPerSecond!!, 0.001)
    }

    @Test
    fun missingTimestampsYieldNullHonest() {
        val r = TurnMetrics.reading(0L, 2_500L, 7_500L, 500)
        assertNull(r.ttftMs)
        val r2 = TurnMetrics.reading(1_000L, 500L, 7_500L, 500)
        assertNull(r2.ttftMs)
    }

    @Test
    fun zeroOutputTokensSuppressesTps() {
        val r = TurnMetrics.reading(1_000L, 2_000L, 3_000L, 0)
        assertEquals(1_000L, r.ttftMs)
        assertNull(r.tokensPerSecond)
    }

    @Test
    fun formatting() {
        assertEquals("42.4 tok/s", TurnMetrics.formatTps(42.4))
        assertEquals("1.5 s", TurnMetrics.formatMs(1_500L))
        assertEquals("320ms", TurnMetrics.formatMs(320L))
    }
}

class TurnProcessFoldingTest {

    private fun user(key: Long) = TimelineItem.Message(
        ChatMessage(id = key, role = "user", content = "u", sortKey = key),
    )

    private fun narration(key: Long) = TimelineItem.Message(
        ChatMessage(
            id = key, role = "assistant", content = "narration",
            isProcess = true, sortKey = key, completedAt = key,
        ),
    )

    private fun finalAnswer(key: Long) = TimelineItem.Message(
        ChatMessage(
            id = key, role = "assistant", content = "final",
            isProcess = false, sortKey = key, completedAt = key,
        ),
    )

    private fun tool(id: String, key: Long, running: Boolean = false) = TimelineItem.Tool(
        ToolCall(id = id, name = "Bash", args = "{}", isRunning = running, sortKey = key),
        key,
    )

    private fun reasoning(key: Long) = TimelineItem.Reasoning(
        ReasoningItem(id = "r$key", content = "think", isStreaming = false, sortKey = key),
    )

    @Test
    fun closedTurnProducesFoldWithCounts() {
        val timeline = listOf(
            user(100),
            reasoning(110),
            narration(120),
            tool("t1", 130),
            tool("t2", 140),
            finalAnswer(150),
        )
        val folds = TurnProcessFolding.folds(timeline)
        assertEquals(1, folds.size)
        val fold = folds[0]
        assertEquals(110L, fold.sortKey)
        assertEquals(2, fold.toolCalls)
        assertEquals(1, fold.narrations)
        assertEquals("2 步工具调用 · 1 段说明", fold.label())
    }

    @Test
    fun openTurnDoesNotFold() {
        val timeline = listOf(
            user(100),
            reasoning(110),
            tool("t1", 130),
        )
        assertTrue(TurnProcessFolding.folds(timeline).isEmpty())
    }

    @Test
    fun zeroCountsReadsThoughtForAWhile() {
        val timeline = listOf(
            user(100),
            reasoning(110),
            finalAnswer(150),
        )
        val folds = TurnProcessFolding.folds(timeline)
        assertEquals(1, folds.size)
        assertEquals("思考了一会儿", folds[0].label())
        assertFalse(folds[0].hasCounts)
    }

    @Test
    fun hiddenIdsCoverProcessRowsButKeepFinalAnswer() {
        val timeline = listOf(
            user(100),
            reasoning(110),
            tool("t1", 130),
            finalAnswer(150),
        )
        val folds = TurnProcessFolding.folds(timeline)
        val hidden = TurnProcessFolding.hiddenIds(timeline, folds)
        assertTrue("r-r110" in hidden)
        assertTrue("t-t1" in hidden)
        assertFalse(hidden.contains("m-150"))
        assertFalse(hidden.contains("m-100"))
    }

    @Test
    fun runningToolsAloneProduceNoFold() {
        val timeline = listOf(
            user(100),
            tool("t1", 130, running = true),
            finalAnswer(150),
        )
        // 仅有一个仍在运行的工具、无任何已定型过程证据 → 不产出折叠。
        assertTrue(TurnProcessFolding.folds(timeline).isEmpty())
    }
}
