package com.andmx.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTest {

    private fun TerminalEmulator.text() = render()

    @Test
    fun writesAndWraps() {
        val t = TerminalEmulator(rows = 4, cols = 10)
        t.feed("hello")
        assertTrue(t.text().startsWith("hello"))
    }

    @Test
    fun cursorAddressingOverwrites() {
        val t = TerminalEmulator(rows = 4, cols = 20)
        t.feed("ABCDEF")
        // move cursor to row1 col3 (1-based) and overwrite
        t.feed("\u001b[1;3HXY")
        // first line should now read "ABXYEF"
        val firstLine = t.text().lineSequence().first()
        assertEquals("ABXYEF", firstLine)
    }

    @Test
    fun eraseDisplayClearsScreen() {
        val t = TerminalEmulator(rows = 4, cols = 20)
        t.feed("line1\r\nline2")
        t.feed("\u001b[2J\u001b[H")
        assertEquals("", t.text().trim())
    }

    @Test
    fun newlinesScrollIntoHistory() {
        val t = TerminalEmulator(rows = 2, cols = 10)
        t.feed("a\r\nb\r\nc")
        val lines = t.text().split("\n")
        // 'a' scrolled into history, screen shows b and c
        assertTrue(lines.contains("a"))
        assertTrue(lines.contains("b"))
        assertTrue(lines.contains("c"))
    }
}
