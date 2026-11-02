package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadTextFormatterTest {
    @Test
    fun numbersFromOne() {
        val view = ReadTextFormatter.format("a\nb", offsetZeroBased = 0, limit = null)
        assertEquals("1\ta\n2\tb", view.output)
        assertFalse(view.partial)
    }

    @Test
    fun emptyFileIsAReminder() {
        val view = ReadTextFormatter.format("", offsetZeroBased = 0, limit = null)
        assertTrue(view.output.contains("contents are empty"))
        assertTrue(view.output.contains("<system-reminder>"))
        assertFalse(view.partial)
    }

    @Test
    fun offsetPastEndWarnsWithOneBasedLine() {
        val view = ReadTextFormatter.format("only", offsetZeroBased = 4, limit = null)
        assertTrue(view.output.contains("offset (5)"))
        assertTrue(view.output.contains("1 lines"))
        assertTrue(view.partial)
        assertTrue(view.stored.isEmpty())
    }

    @Test
    fun explicitLimitTellsHowToContinue() {
        val view = ReadTextFormatter.format("a\nb\nc", offsetZeroBased = 0, limit = 1)
        assertTrue(view.output.startsWith("1\ta"))
        assertTrue(view.output.contains("offset 2"))
        assertTrue(view.partial)
        assertEquals("a", view.stored)
    }

    @Test
    fun tokenCapUsesOneBasedContinuationOffset() {
        val text = List(800) { "x".repeat(200) }.joinToString("\n")
        val view = ReadTextFormatter.format(text, offsetZeroBased = 0, limit = null)
        assertTrue(view.partial)
        assertTrue(view.output.contains("too large"))
        assertTrue(view.output.contains("offset "))
        assertFalse(view.isError)
    }
}
