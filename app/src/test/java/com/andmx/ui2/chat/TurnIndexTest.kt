package com.andmx.ui2.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnIndexTest {

    private fun user(id: Long, content: String) =
        TimelineItem.Message(ChatMessage(id = id, role = "user", content = content))

    private fun assistant(id: Long, content: String) =
        TimelineItem.Message(ChatMessage(id = id, role = "assistant", content = content))

    private fun tool(id: String) =
        TimelineItem.Tool(
            tool = ToolCall(id = id, name = "shell", args = "{}"),
            sortKey = id.hashCode().toLong(),
        )

    @Test
    fun emptyListYieldsNoTurns() {
        assertTrue(TurnIndex.from(emptyList()).isEmpty())
    }

    @Test
    fun listWithoutUserMessagesYieldsNoTurns() {
        val items = listOf(assistant(1, "answer"), tool("t1"), assistant(2, "more"))
        assertTrue(TurnIndex.from(items).isEmpty())
    }

    @Test
    fun singleTurnSpansWholeList() {
        val items = listOf(user(1, "question"), assistant(2, "answer"), tool("t1"))
        val turns = TurnIndex.from(items)
        assertEquals(1, turns.size)
        assertEquals(TurnEntry(1, "question", 0, 2, 1), turns[0])
    }

    @Test
    fun multipleTurnsSplitAtUserMessages() {
        val items = listOf(
            user(1, "first"),
            assistant(2, "a"),
            tool("t1"),
            user(3, "second"),
            assistant(4, "b"),
            user(5, "third"),
        )
        val turns = TurnIndex.from(items)
        assertEquals(3, turns.size)
        assertEquals(TurnEntry(1, "first", 0, 2, 1), turns[0])
        assertEquals(TurnEntry(2, "second", 3, 4, 0), turns[1])
        assertEquals(TurnEntry(3, "third", 5, 5, 0), turns[2])
    }

    @Test
    fun leadingNonUserItemsAreExcluded() {
        val items = listOf(assistant(1, "preamble"), user(2, "q"), assistant(3, "a"))
        val turns = TurnIndex.from(items)
        assertEquals(1, turns.size)
        assertEquals(1, turns[0].startIndex)
        assertEquals(2, turns[0].endIndex)
    }

    @Test
    fun toolGroupsAndFoldsCountTools() {
        val items = listOf(
            user(1, "q"),
            TimelineItem.ToolGroup(listOf(tool("t1").tool, tool("t2").tool), sortKey = 10L),
            TimelineItem.TurnProcess(TurnProcessFolding.Fold(sortKey = 20L, toolCalls = 3, subagents = 1, narrations = 2)),
            tool("t3"),
        )
        val turns = TurnIndex.from(items)
        assertEquals(1, turns.size)
        assertEquals(6, turns[0].toolCalls)
    }

    @Test
    fun summarizeCollapsesWhitespaceAndTruncates() {
        assertEquals("a b c", TurnIndex.summarize("  a\n b\t c  "))
        assertEquals("x".repeat(40), TurnIndex.summarize("x".repeat(100)))
        assertEquals("", TurnIndex.summarize("   "))
    }

    @Test
    fun turnSummaryUsesFirstUserMessage() {
        val items = listOf(user(1, "  hello\nworld  "), assistant(2, "ignored"))
        assertEquals("hello world", TurnIndex.from(items)[0].summary)
    }
}
