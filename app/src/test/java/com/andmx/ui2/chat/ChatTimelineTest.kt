package com.andmx.ui2.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTimelineTest {

    private fun msg(id: Long, sortKey: Long = id) =
        ChatMessage(id = id, role = "assistant", content = "m$id", sortKey = sortKey)

    private fun tool(id: String, sortKey: Long, name: String = "Read", running: Boolean = false, error: Boolean = false) =
        ToolCall(id = id, name = name, args = "{}", isRunning = running, isError = error, sortKey = sortKey)

    @Test
    fun sortsItemsBySortKey() {
        val timeline = buildTimeline(
            messages = listOf(msg(2, sortKey = 300)),
            tools = listOf(tool("t1", sortKey = 100, name = "Bash"), tool("t2", sortKey = 200, name = "Write")),
        )
        assertEquals(listOf(100L, 200L, 300L), timeline.map { it.sortKey })
        assertTrue(timeline[0] is TimelineItem.Tool)
        assertTrue(timeline[2] is TimelineItem.Message)
    }

    @Test
    fun groupsConsecutiveCompletedReadOnlyTools() {
        val timeline = buildTimeline(
            messages = emptyList(),
            tools = listOf(
                tool("t1", sortKey = 100, name = "Read"),
                tool("t2", sortKey = 101, name = "Grep"),
                tool("t3", sortKey = 102, name = "Bash"),
            ),
        )
        assertEquals(2, timeline.size)
        val group = timeline[0] as TimelineItem.ToolGroup
        assertEquals(listOf("t1", "t2"), group.tools.map { it.id })
        assertEquals(100L, group.sortKey)
        assertTrue(timeline[1] is TimelineItem.Tool)
    }

    @Test
    fun singleGroupableToolStaysUngrouped() {
        val timeline = buildTimeline(messages = emptyList(), tools = listOf(tool("t1", sortKey = 100, name = "Read")))
        assertTrue(timeline.single() is TimelineItem.Tool)
    }

    @Test
    fun runningAndFailedToolsAreNeverGrouped() {
        val timeline = buildTimeline(
            messages = emptyList(),
            tools = listOf(
                tool("t1", sortKey = 100, name = "Read", running = true),
                tool("t2", sortKey = 101, name = "Read", error = true),
                tool("t3", sortKey = 102, name = "Read"),
            ),
        )
        assertEquals(3, timeline.size)
        assertTrue(timeline.all { it is TimelineItem.Tool })
    }

    @Test
    fun interleavedMessageBreaksGrouping() {
        val timeline = buildTimeline(
            messages = listOf(msg(2, sortKey = 105)),
            tools = listOf(
                tool("t1", sortKey = 100, name = "Read"),
                tool("t2", sortKey = 110, name = "Read"),
            ),
        )
        assertTrue(timeline.all { it !is TimelineItem.ToolGroup })
    }

    @Test
    fun toolWithoutSortKeyFallsBackToStableHashKey() {
        val timeline = buildTimeline(messages = emptyList(), tools = listOf(tool("abc", sortKey = 0)))
        val item = timeline.single() as TimelineItem.Tool
        val expected = "abc".hashCode().toLong().and(0x7fffffffL)
        assertEquals(expected, item.sortKey)
    }

    @Test
    fun stableIdsArePrefixedPerType() {
        val timeline = buildTimeline(
            messages = listOf(msg(7)),
            tools = listOf(tool("t1", sortKey = 1, name = "Bash"), tool("t2", sortKey = 2, name = "Write")),
            approvals = listOf(ApprovalItem(id = "a1", toolName = "Bash", summary = "s", modeLabel = "m", sortKey = 3)),
            subAgents = listOf(SubAgentItem(id = "s1", task = "t", state = "running", sortKey = 4)),
            reasonings = listOf(ReasoningItem(id = "r1", content = "c", sortKey = 5)),
            showWorking = true,
        )
        assertEquals(
            listOf("t-t1", "t-t2", "a-a1", "s-s1", "r-r1", "m-7", "working"),
            timeline.map { it.stableId },
        )
    }

    @Test
    fun workingItemIsLastWhenShown() {
        val timeline = buildTimeline(messages = listOf(msg(1)), tools = emptyList(), showWorking = true)
        val last = timeline.last()
        assertTrue(last is TimelineItem.Working)
        assertEquals(Long.MAX_VALUE - 1, last.sortKey)
    }

    @Test
    fun workingItemOmittedByDefault() {
        val timeline = buildTimeline(messages = listOf(msg(1)), tools = emptyList())
        assertTrue(timeline.none { it is TimelineItem.Working })
    }
}
