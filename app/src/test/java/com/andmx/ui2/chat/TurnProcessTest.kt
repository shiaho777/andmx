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
            totalTokens = 2_000,
        )
        assertEquals(1_500L, r.ttftMs)
        assertEquals(500, r.outputTokens)
        assertEquals(2_000, r.totalTokens)
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
        assertEquals("42.4 tokens/s", TurnMetrics.formatTps(42.4))
        assertEquals("714 tokens", TurnMetrics.formatTokens(714))
        assertEquals("120 tokens/s", TurnMetrics.formatTps(120.4))
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
    fun reasoningAloneDoesNotFold() {
        val timeline = listOf(
            user(100),
            reasoning(110),
            finalAnswer(150),
        )
        assertTrue(TurnProcessFolding.folds(timeline).isEmpty())
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
    fun expandedFoldKeepsSummaryAndShowsProcessRows() {
        val timeline = listOf(
            user(100),
            reasoning(110),
            tool("t1", 130),
            finalAnswer(150),
        )
        val folds = TurnProcessFolding.folds(timeline)
        val key = folds.single().sortKey
        val collapsed = TurnProcessFolding.visibleTimeline(timeline, folds, emptySet())
        assertEquals(1, collapsed.count { it is TimelineItem.TurnProcess })
        assertTrue(collapsed.none { it is TimelineItem.Reasoning || it is TimelineItem.Tool })
        val expanded = TurnProcessFolding.visibleTimeline(timeline, folds, setOf(key))
        assertEquals(1, expanded.count { it is TimelineItem.TurnProcess })
        assertTrue(expanded.any { it is TimelineItem.Reasoning })
        assertTrue(expanded.any { it is TimelineItem.Tool })
        assertTrue(expanded.any { it is TimelineItem.Message && it.message.role == "assistant" && !it.message.isProcess })
    }

    @Test
    fun laterTurnToolsDoNotFoldAnEarlierPlainReply() {
        val messages = listOf(
            ChatMessage(id = 1, role = "user", content = "hi", sortKey = 100),
            ChatMessage(id = 2, role = "assistant", content = "你好，有什么需要我帮忙的？", sortKey = 110),
            ChatMessage(id = 3, role = "user", content = "再跑一次", sortKey = 200),
            ChatMessage(id = 4, role = "assistant", content = "先看一下", sortKey = 210, isProcess = false),
            ChatMessage(id = 5, role = "assistant", content = "跑通了", sortKey = 230),
        )
        val marked = TurnProcessFolding.markProcessMessages(messages, listOf(220L))
        assertFalse(marked[1].isProcess)
        assertTrue(marked[3].isProcess)
        assertFalse(marked[4].isProcess)
        val timeline = listOf(
            TimelineItem.Message(marked[0]),
            TimelineItem.Message(marked[1]),
            TimelineItem.Message(marked[2]),
            TimelineItem.Message(marked[3]),
            TimelineItem.Tool(
                ToolCall(id = "t1", name = "Bash", args = "{}", isRunning = false, sortKey = 220),
                220,
            ),
            TimelineItem.Message(marked[4]),
        )
        val folds = TurnProcessFolding.folds(timeline)
        assertEquals(1, folds.size)
        assertEquals(210L, folds[0].sortKey)
        assertEquals("1 步工具调用 · 1 段说明", folds[0].label())
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

class TurnNavigatorRankTest {
    @Test
    fun staysOnLatestWhilePinnedToBottom() {
        assertEquals(0, navigatorRank(listOf(4, 20, 40, 70), topVisibleIndex = 30, atBottom = true))
    }

    @Test
    fun followsTheUserMessageAtTheTopOnceScrolled() {
        assertEquals(2, navigatorRank(listOf(4, 20, 40, 70), topVisibleIndex = 45, atBottom = false))
        assertEquals(3, navigatorRank(listOf(4, 20, 40, 70), topVisibleIndex = 80, atBottom = false))
    }
}
