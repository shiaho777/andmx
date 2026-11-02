package com.andmx.diff

import org.junit.Assert.assertEquals
import org.junit.Test

class DiffEngineTest {

    @Test
    fun detectsAddedAndRemovedLines() {
        val old = "line1\nline2\nline3"
        val new = "line1\nline2-changed\nline3\nline4"
        val lines = DiffEngine.diff(old, new)
        val stats = DiffEngine.stats(lines)

        // line2 changed => 1 remove + 1 add; line4 added => +1
        assertEquals(2, stats.added)
        assertEquals(1, stats.removed)

        // context lines preserved
        assertEquals(2, lines.count { it.kind == DiffLine.Kind.CONTEXT })
    }

    @Test
    fun newFileIsAllAdds() {
        val lines = DiffEngine.diff("", "a\nb\nc")
        assertEquals(3, DiffEngine.stats(lines).added)
        assertEquals(0, DiffEngine.stats(lines).removed)
    }

    @Test
    fun identicalContentHasNoChanges() {
        val text = "same\ncontent"
        val stats = DiffEngine.stats(DiffEngine.diff(text, text))
        assertEquals(0, stats.added)
        assertEquals(0, stats.removed)
    }

    @Test
    fun lineStatsMatchesTheRenderedDiff() {
        val cases = listOf(
            "" to "a\nb\nc",
            "a\nb\nc" to "",
            "a\nb" to "a\nc\nd",
            "same\ncontent" to "same\ncontent",
            "one\ntwo\nthree\nfour" to "one\nthree\nfour\nfive",
        )
        for ((old, new) in cases) {
            val expected = DiffEngine.stats(DiffEngine.diff(old, new))
            assertEquals("old=$old new=$new", expected, DiffEngine.lineStats(old, new))
        }
    }

    @Test
    fun oneLineEditInLargeFileDoesNotReportEveryLineAsChanged() {
        val old = (1..5_000).joinToString("\n") { "line $it" }
        val new = old.replace("line 2500", "line 2500 changed")

        val stats = DiffEngine.lineStats(old, new)

        assertEquals(1, stats.added)
        assertEquals(1, stats.removed)
    }

    @Test
    fun pureInsertionAndDeletionAreCountedExactly() {
        val base = (1..100).joinToString("\n")
        val inserted = base.replace("50", "50\n50.5")

        assertEquals(DiffStats(added = 1, removed = 0), DiffEngine.lineStats(base, inserted))
        assertEquals(DiffStats(added = 0, removed = 1), DiffEngine.lineStats(inserted, base))
    }
}
