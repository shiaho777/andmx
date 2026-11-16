package com.andmx.ui2.chat

import com.andmx.diff.DiffLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffRenderTest {

    @Test
    fun parsesHunkHeader() {
        val diff = """
            @@ -12,3 +12,4 @@
             context
            -old
            +new
            +extra
        """.trimIndent()
        val hunks = DiffRender.parse(diff)
        assertEquals(1, hunks.size)
        val h = hunks[0]
        assertEquals(12, h.oldStart)
        assertEquals(3, h.oldCount)
        assertEquals(12, h.newStart)
        assertEquals(4, h.newCount)
        assertEquals("@@ -12,3 +12,4 @@", h.header)
        assertEquals(4, h.lines.size)
    }

    @Test
    fun parsesHunkHeaderWithoutCounts() {
        val hunks = DiffRender.parse("@@ -5 +7 @@\n-x\n+y")
        assertEquals(1, hunks.size)
        assertEquals(5, hunks[0].oldStart)
        assertEquals(1, hunks[0].oldCount)
        assertEquals(7, hunks[0].newStart)
        assertEquals(1, hunks[0].newCount)
    }

    @Test
    fun parsesMultipleHunks() {
        val diff = """
            @@ -1,2 +1,2 @@
             a
            -b
            +B
            @@ -10,2 +10,2 @@
             c
            -d
            +D
        """.trimIndent()
        val hunks = DiffRender.parse(diff)
        assertEquals(2, hunks.size)
        assertEquals(1, hunks[0].oldStart)
        assertEquals(10, hunks[1].oldStart)
        assertEquals(3, hunks[0].lines.size)
        assertEquals(3, hunks[1].lines.size)
    }

    @Test
    fun pairsInlineChangeWithinHunk() {
        val diff = """
            @@ -1,3 +1,3 @@
             fun main() {
            -    print("old")
            +    print("new")
             }
        """.trimIndent()
        val hunk = DiffRender.parse(diff).single()
        val removed = hunk.lines.first { it.diff.kind == DiffLine.Kind.REMOVE }
        val added = hunk.lines.first { it.diff.kind == DiffLine.Kind.ADD }
        assertEquals(removed.emphasis, added.emphasis)
        val range = removed.emphasis!!
        assertEquals("old", removed.diff.text.substring(range))
        assertEquals("new", added.diff.text.substring(added.emphasis!!))
        assertNull(hunk.lines.first { it.diff.kind == DiffLine.Kind.CONTEXT }.emphasis)
    }

    @Test
    fun inlineRangesIdenticalAndDisjoint() {
        val same = DiffRender.inlineRanges("abc", "abc")
        assertNull(same.first)
        assertNull(same.second)
        val disjoint = DiffRender.inlineRanges("abc", "xyz")
        assertNull(disjoint.first)
        assertNull(disjoint.second)
        val mid = DiffRender.inlineRanges("hello world", "hello there")
        assertEquals("world", "hello world".substring(mid.first!!))
        assertEquals("there", "hello there".substring(mid.second!!))
    }

    @Test
    fun barePlusMinusFallbackWithoutHunkHeader() {
        val diff = "+added\n-removed\n context"
        val hunks = DiffRender.parse(diff)
        assertEquals(1, hunks.size)
        val kinds = hunks[0].lines.map { it.diff.kind }
        assertEquals(
            listOf(DiffLine.Kind.ADD, DiffLine.Kind.REMOVE, DiffLine.Kind.CONTEXT),
            kinds,
        )
    }

    @Test
    fun emptyDiffYieldsNoHunks() {
        assertTrue(DiffRender.parse("").isEmpty())
        assertTrue(DiffRender.parse("\n\n").isEmpty())
    }

    @Test
    fun hunksFromDiffLinesSplitOnGap() {
        val lines = listOf(
            DiffLine(DiffLine.Kind.REMOVE, "a", oldNo = 1),
            DiffLine(DiffLine.Kind.ADD, "A", newNo = 1),
            DiffLine(DiffLine.Kind.CONTEXT, "", null, null),
            DiffLine(DiffLine.Kind.REMOVE, "b", oldNo = 9),
            DiffLine(DiffLine.Kind.ADD, "B", newNo = 9),
        )
        val hunks = DiffRender.hunks(lines)
        assertEquals(2, hunks.size)
        assertEquals(1, hunks[0].oldStart)
        assertEquals(9, hunks[1].oldStart)
    }

    @Test
    fun focusHunksRespectsLineBudget() {
        val diff = """
            @@ -1,1 +1,1 @@
            -a
            +A
            @@ -5,1 +5,1 @@
            -b
            +B
        """.trimIndent()
        val hunks = DiffRender.parse(diff)
        val focused = DiffRender.focusHunks(hunks, limit = 3)
        assertEquals(3, focused.sumOf { it.lines.size })
    }
}