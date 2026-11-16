package com.andmx.ui2.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTextChunksTest {

    @Test
    fun emptyTextYieldsNoChunks() {
        assertTrue(StreamingTextChunks.split("").isEmpty())
    }

    @Test
    fun singleLineIsOneChunk() {
        assertEquals(listOf("hello"), StreamingTextChunks.split("hello"))
    }

    @Test
    fun splitsOnBlankLineParagraphs() {
        val text = "first para\n\nsecond para\n\nthird"
        assertEquals(listOf("first para\n\n", "second para\n\n", "third"), StreamingTextChunks.split(text))
    }

    @Test
    fun singleNewlineStaysInSameChunk() {
        val text = "line one\nline two\nline three"
        assertEquals(listOf(text), StreamingTextChunks.split(text))
    }

    @Test
    fun trailingContentAfterBlankLineFormsLastChunk() {
        val text = "a\n\nb"
        assertEquals(listOf("a\n\n", "b"), StreamingTextChunks.split(text))
    }

    @Test
    fun fencedCodeBlockIsNotSplit() {
        val text = "intro\n\n```\ncode\n\nmore code\n```\n\noutro"
        val chunks = StreamingTextChunks.split(text)
        assertEquals(3, chunks.size)
        assertTrue(chunks[1].contains("code\n\nmore code"))
        assertEquals("outro", chunks[2])
    }

    @Test
    fun trailingNewlinesWithoutContentStayInLastChunk() {
        val text = "a\n\nb\n\n"
        assertEquals(listOf("a\n\n", "b\n\n"), StreamingTextChunks.split(text))
    }

    @Test
    fun chunksReassembleOriginalText() {
        val text = "para one\n\npara two\nline\n\n```\nx\n\ny\n```\ntail"
        assertEquals(text, StreamingTextChunks.split(text).joinToString(""))
    }
}