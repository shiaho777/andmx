package com.andmx.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorSgrTest {

    private fun firstSpan(sgr: String): StyledSpan {
        val t = TerminalEmulator(rows = 4, cols = 40)
        t.feed("\u001b[${sgr}mX")
        return t.renderStyled().first().first()
    }

    @Test
    fun standard16ForegroundMapping() {
        assertEquals(2, firstSpan("31").fg)
        assertEquals(8, firstSpan("37").fg)
        assertEquals(8, firstSpan("90").fg)
        assertEquals(15, firstSpan("97").fg)
    }

    @Test
    fun standardBackgroundMapping() {
        assertEquals(2, firstSpan("41").bg)
        assertEquals(8, firstSpan("47").bg)
        assertEquals(8, firstSpan("100").bg)
        assertEquals(15, firstSpan("107").bg)
        assertEquals(0, firstSpan("41").fg)
    }

    @Test
    fun indexed16MapsToStandardPalette() {
        assertEquals(1, firstSpan("38;5;1").fg)
        assertEquals(7, firstSpan("38;5;7").fg)
        assertEquals(1, firstSpan("38;5;9").fg)
        assertEquals(7, firstSpan("38;5;15").fg)
    }

    @Test
    fun indexedCubeColorPreserved() {
        val span = firstSpan("38;5;196")
        assertEquals(17 + 196, span.fg)
        assertEquals(0xFFFF0000.toInt(), TerminalEmulator.colorArgb256(196))
    }

    @Test
    fun cubePaletteConversion() {
        assertEquals(0xFF000000.toInt(), TerminalEmulator.colorArgb256(16))
        assertEquals(0xFFFFFFFF.toInt(), TerminalEmulator.colorArgb256(231))
        assertEquals(0xFF5F87AF.toInt(), TerminalEmulator.colorArgb256(67))
        assertEquals(0xFF878787.toInt(), TerminalEmulator.colorArgb256(102))
    }

    @Test
    fun grayscalePaletteConversion() {
        assertEquals(0xFF080808.toInt(), TerminalEmulator.colorArgb256(232))
        assertEquals(0xFF808080.toInt(), TerminalEmulator.colorArgb256(244))
        assertEquals(0xFFEEEEEE.toInt(), TerminalEmulator.colorArgb256(255))
    }

    @Test
    fun trueColorForeground() {
        val span = firstSpan("38;2;10;20;30")
        assertEquals(TerminalEmulator.TRUECOLOR_FLAG or 0x0A141E, span.fg)
    }

    @Test
    fun trueColorBackground() {
        val span = firstSpan("48;2;200;100;50")
        assertEquals(TerminalEmulator.TRUECOLOR_FLAG or 0xC86432, span.bg)
        assertEquals(0, span.fg)
    }

    @Test
    fun trueColorChannelsClamped() {
        val span = firstSpan("38;2;999;0;0")
        assertEquals(TerminalEmulator.TRUECOLOR_FLAG or 0xFF0000, span.fg)
    }

    @Test
    fun boldWithColorCombined() {
        val span = firstSpan("1;31")
        assertTrue(span.bold)
        assertEquals(2, span.fg)
        assertFalse(span.underline)
    }

    @Test
    fun attributeFlagsParsed() {
        val dim = firstSpan("2")
        assertTrue(dim.dim)
        val italic = firstSpan("3")
        assertTrue(italic.italic)
        val underline = firstSpan("4")
        assertTrue(underline.underline)
        val inverse = firstSpan("7")
        assertTrue(inverse.inverse)
    }

    @Test
    fun attributeResetsParsed() {
        val t = TerminalEmulator(rows = 4, cols = 40)
        t.feed("\u001b[1;2;3;4;7mX\u001b[22;23;24;27mY")
        val spans = t.renderStyled().first()
        assertTrue(spans[0].bold)
        val y = spans.last()
        assertFalse(y.bold)
        assertFalse(y.dim)
        assertFalse(y.italic)
        assertFalse(y.underline)
        assertFalse(y.inverse)
    }

    @Test
    fun resetClearsColorAndAttrs() {
        val t = TerminalEmulator(rows = 4, cols = 40)
        t.feed("\u001b[1;31;44mX\u001b[0mY")
        val spans = t.renderStyled().first()
        val y = spans.last()
        assertEquals("Y", y.text)
        assertEquals(0, y.fg)
        assertEquals(0, y.bg)
        assertFalse(y.bold)
    }

    @Test
    fun emptySgrActsAsReset() {
        val t = TerminalEmulator(rows = 4, cols = 40)
        t.feed("\u001b[1;31mX\u001b[mY")
        val y = t.renderStyled().first().last()
        assertEquals(0, y.fg)
        assertFalse(y.bold)
    }

    @Test
    fun fgBgDefaultResets() {
        val t = TerminalEmulator(rows = 4, cols = 40)
        t.feed("\u001b[31;44mX\u001b[39mY\u001b[49mZ")
        val spans = t.renderStyled().first()
        val y = spans[1]
        assertEquals(0, y.fg)
        assertEquals(5, y.bg)
        val z = spans.last()
        assertEquals(0, z.fg)
        assertEquals(0, z.bg)
    }

    @Test
    fun unknownSgrIgnoredWithoutCrash() {
        val t = TerminalEmulator(rows = 4, cols = 40)
        t.feed("\u001b[53;73;999mhello\u001b[38;5mX\u001b[38;2;1mY")
        assertTrue(t.render().contains("hello"))
        assertEquals(0, t.renderStyled().first().first().fg)
    }

    @Test
    fun incompleteEscapeBufferedAcrossChunks() {
        val t = TerminalEmulator(rows = 4, cols = 40)
        t.feed("A\u001b")
        t.feed("[3")
        t.feed("1mB")
        assertEquals("AB", t.render().trim())
        val spans = t.renderStyled().first()
        assertEquals(2, spans.last().fg)
        assertEquals("B", spans.last().text)
    }

    @Test
    fun incompleteTrueColorAcrossChunks() {
        val t = TerminalEmulator(rows = 4, cols = 40)
        t.feed("\u001b[38;2;10;2")
        t.feed("0;30mC")
        val span = t.renderStyled().first().first()
        assertEquals(TerminalEmulator.TRUECOLOR_FLAG or 0x0A141E, span.fg)
        assertEquals("C", span.text)
    }

    @Test
    fun styledLinesSplitPerRow() {
        val t = TerminalEmulator(rows = 2, cols = 10)
        t.feed("a\r\nb\r\nc\r\nd")
        val lines = t.renderStyled()
        assertEquals(4, lines.size)
        assertEquals("a", lines[0].single().text)
        assertEquals("b", lines[1].single().text)
        assertEquals("c", lines[2].single().text)
        assertEquals("d", lines[3].single().text)
    }

    @Test
    fun styleChangesSplitSpansWithinRow() {
        val t = TerminalEmulator(rows = 4, cols = 40)
        t.feed("\u001b[31mred\u001b[32mgreen")
        val spans = t.renderStyled().first()
        assertEquals(2, spans.size)
        assertEquals("red" to 2, spans[0].text to spans[0].fg)
        assertEquals("green" to 3, spans[1].text to spans[1].fg)
    }

    @Test
    fun legacyColoredOutputStillWorks() {
        val t = TerminalEmulator(rows = 4, cols = 40)
        t.feed("\u001b[31mred\u001b[0mplain")
        val spans = t.renderColored().first()
        assertEquals("red" to 2, spans[0])
        assertEquals("plain" to 0, spans[1])
    }

    @Test
    fun performance10000Lines() {
        val t = TerminalEmulator(rows = 24, cols = 80)
        val chunk = buildString {
            repeat(500) { i ->
                append("\u001b[1;3${i % 8}mline $i some terminal output text\u001b[0m\r\n")
            }
        }
        t.feed(chunk)
        val start = System.nanoTime()
        repeat(19) { t.feed(chunk) }
        t.renderStyled()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("processing 10000 lines took ${elapsedMs}ms", elapsedMs < 2000)
    }
}
