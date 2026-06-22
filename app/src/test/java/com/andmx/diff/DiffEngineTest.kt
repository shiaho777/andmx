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
}
