package com.andmx.ui2.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFindTest {

    private fun msg(id: Long, role: String, content: String) =
        ChatMessage(id = id, role = role, content = content)

    @Test
    fun emptyQueryYieldsNoHits() {
        val messages = listOf(msg(1, "user", "hello"), msg(2, "assistant", "hello world"))
        assertTrue(ChatFind.find(messages, "").isEmpty())
        assertTrue(ChatFind.find(messages, "   ").isEmpty())
    }

    @Test
    fun emptyMessagesYieldNoHits() {
        assertTrue(ChatFind.find(emptyList(), "hello").isEmpty())
    }

    @Test
    fun hitsAcrossMultipleMessagesInOrder() {
        val messages = listOf(
            msg(1, "user", "foo bar"),
            msg(2, "assistant", "nothing here"),
            msg(3, "assistant", "foo and foo"),
        )
        val hits = ChatFind.find(messages, "foo")
        assertEquals(
            listOf(FindHit(0, 0, 3), FindHit(2, 0, 3), FindHit(2, 8, 11)),
            hits,
        )
    }

    @Test
    fun caseInsensitiveMatching() {
        val messages = listOf(msg(1, "assistant", "Hello hELLO hello"))
        val hits = ChatFind.find(messages, "hello")
        assertEquals(3, hits.size)
        assertEquals(listOf(0, 6, 12), hits.map { it.start })
        hits.forEach { assertEquals(it.start + 5, it.endExclusive) }
    }

    @Test
    fun nonOverlappingOccurrences() {
        val hits = ChatFind.find(listOf(msg(1, "user", "aaaa")), "aa")
        assertEquals(listOf(FindHit(0, 0, 2), FindHit(0, 2, 4)), hits)
    }

    @Test
    fun specialCharactersAreLiteral() {
        val messages = listOf(msg(1, "assistant", "use a.*b (c) [d] \\ e?"))
        val hits = ChatFind.find(messages, "a.*b")
        assertEquals(listOf(FindHit(0, 4, 8)), hits)
        assertEquals(1, ChatFind.find(messages, "(c)").size)
        assertEquals(1, ChatFind.find(messages, "[d]").size)
        assertEquals(1, ChatFind.find(messages, "\\").size)
    }

    @Test
    fun queryLongerThanContentYieldsNoHits() {
        assertTrue(ChatFind.find(listOf(msg(1, "user", "ab")), "abc").isEmpty())
    }

    @Test
    fun rangesInEmptyText() {
        assertTrue(ChatFind.rangesIn("", "a").isEmpty())
        assertTrue(ChatFind.rangesIn("abc", "").isEmpty())
    }

    @Test
    fun wrapCyclesForwardAndBackward() {
        assertEquals(0, ChatFind.wrap(0, 3))
        assertEquals(2, ChatFind.wrap(-1, 3))
        assertEquals(0, ChatFind.wrap(3, 3))
        assertEquals(1, ChatFind.wrap(4, 3))
        assertEquals(1, ChatFind.wrap(-2, 3))
        assertEquals(0, ChatFind.wrap(5, 0))
    }

    @Test
    fun highlightsForFiltersMessageAndMarksCurrent() {
        val hits = listOf(FindHit(0, 0, 2), FindHit(1, 3, 5), FindHit(1, 8, 10))
        assertEquals(
            listOf(FindHighlight(3, 5, false), FindHighlight(8, 10, true)),
            ChatFind.highlightsFor(hits, 1, 2),
        )
        assertTrue(ChatFind.highlightsFor(hits, 2, 0).isEmpty())
        assertEquals(
            listOf(FindHighlight(3, 5, true), FindHighlight(8, 10, false)),
            ChatFind.highlightsFor(hits, 1, 1),
        )
    }

    @Test
    fun rebaseMapsHitsIntoProjectedText() {
        val source = "hello world hello"
        val target = "world hello"
        val highlights = listOf(FindHighlight(12, 17, true), FindHighlight(0, 5, false))
        val rebased = ChatFind.rebase(source, target, highlights)
        assertEquals(listOf(FindHighlight(6, 11, true)), rebased)
    }

    @Test
    fun rebaseKeepsOrderWhenTargetPreservesIt() {
        val source = "alpha beta alpha"
        val target = "beta alpha"
        val highlights = listOf(FindHighlight(11, 16, true))
        assertEquals(listOf(FindHighlight(5, 10, true)), ChatFind.rebase(source, target, highlights))
    }

    @Test
    fun rebaseDropsInvalidAndMissingRanges() {
        val source = "abcdef"
        val target = "xyz"
        val highlights = listOf(
            FindHighlight(-1, 3, false),
            FindHighlight(0, 99, false),
            FindHighlight(3, 3, false),
            FindHighlight(0, 3, true),
        )
        assertTrue(ChatFind.rebase(source, target, highlights).isEmpty())
        assertTrue(ChatFind.rebase(source, target, emptyList()).isEmpty())
    }

    @Test
    fun quoteFormatsEachLineWithPrefix() {
        assertEquals("> hello\n", ChatFind.quote("hello"))
        assertEquals("> line one\n> line two\n", ChatFind.quote("line one\nline two"))
    }

    @Test
    fun quoteTrimsOuterWhitespace() {
        assertEquals("> body\n", ChatFind.quote("  \n body \n  "))
    }

    @Test
    fun quoteOfBlankTextIsEmpty() {
        assertEquals("", ChatFind.quote(""))
        assertEquals("", ChatFind.quote("   \n  "))
    }
}
