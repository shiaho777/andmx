package com.andmx.ui2.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalViewLinesTest {

    @Test
    fun splitsScreenIntoLines() {
        assertEquals(listOf("a", "b", "c"), splitTerminalLines("a\nb\nc"))
    }

    @Test
    fun emptyScreenYieldsSingleBlankLine() {
        assertEquals(listOf(" "), splitTerminalLines(""))
    }

    @Test
    fun trailingNewlineKeepsEmptyLastLine() {
        assertEquals(listOf("a", ""), splitTerminalLines("a\n"))
    }

    @Test
    fun singleLineWithoutNewline() {
        assertEquals(listOf("hello"), splitTerminalLines("hello"))
    }
}
