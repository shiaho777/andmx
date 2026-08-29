package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Text elision must cut on code-point boundaries: a head/tail split that lands
 * inside a surrogate pair pushes an unpaired surrogate into model context.
 */
class TextTrimmingTest {

    @Test
    fun countsCodePointsNotUtf16Units() {
        assertEquals(1, TextTrimming.length("a"))
        // U+1F600 is one code point and two UTF-16 units.
        assertEquals(1, TextTrimming.length("\uD83D\uDE00"))
        assertEquals(2, TextTrimming.length("\uD83D\uDE00\uD83D\uDE00"))
        assertEquals(3, TextTrimming.length("a\uD83D\uDE00b"))
    }

    @Test
    fun takeNeverSplitsASurrogatePair() {
        val text = "x\uD83D\uDE00y"
        assertEquals("x", TextTrimming.take(text, 1))
        assertEquals("x\uD83D\uDE00", TextTrimming.take(text, 2))
        assertEquals(text, TextTrimming.take(text, 3))
        assertEquals(text, TextTrimming.take(text, 99))
    }

    @Test
    fun takeLastNeverSplitsASurrogatePair() {
        val text = "x\uD83D\uDE00y"
        assertEquals("y", TextTrimming.takeLast(text, 1))
        assertEquals("\uD83D\uDE00y", TextTrimming.takeLast(text, 2))
        assertEquals(text, TextTrimming.takeLast(text, 99))
    }

    @Test
    fun elideKeepsHeadAndTailAndReportsWhatWasDropped() {
        val text = "0123456789"
        val out = TextTrimming.elide(text, 6) { omitted -> "[omitted:$omitted]" }
        assertTrue(out.startsWith("012"))
        assertTrue(out.endsWith("789"))
        assertTrue(out.contains("[omitted:4]"))
    }

    @Test
    fun elideLeavesTextThatAlreadyFitsAlone() {
        val text = "short"
        assertEquals(text, TextTrimming.elide(text, 100) { "[$it]" })
    }

    @Test
    fun elidedEmojiStayWellFormed() {
        val text = "a".repeat(10) + "\uD83D\uDE00" + "b".repeat(10)
        val out = TextTrimming.elide(text, 12) { "~" }
        // Reconstructing the string must not produce an unpaired surrogate.
        assertEquals(out.count { it.isHighSurrogate() }, out.count { it.isLowSurrogate() })
        assertTrue(out.contains("~"))
    }
}
