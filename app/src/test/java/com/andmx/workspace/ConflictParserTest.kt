package com.andmx.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictParserTest {

    private val standard = """
        |alpha
        |<<<<<<< HEAD
        |ours line
        |=======
        |theirs line
        |>>>>>>> feature
        |omega
        |
    """.trimMargin()

    private val diff3 = """
        |alpha
        |<<<<<<< HEAD
        |ours line
        |||||||| merged common ancestors
        |base line
        |=======
        |theirs line
        |>>>>>>> feature
        |omega
        |
    """.trimMargin()

    @Test
    fun parsesStandardConflict() {
        val doc = ConflictParser.parse(standard)
        assertFalse(doc.malformed)
        assertTrue(doc.hasConflicts)
        assertEquals(1, doc.blocks.size)
        val block = doc.blocks[0]
        assertEquals(0, block.index)
        assertEquals(2, block.startLine)
        assertEquals(6, block.endLine)
        assertEquals("HEAD", block.oursLabel)
        assertEquals("feature", block.theirsLabel)
        assertNull(block.base)
        assertNull(block.baseLabel)
        assertEquals(listOf("ours line"), block.ours.map { it.text })
        assertEquals(listOf("theirs line"), block.theirs.map { it.text })
        assertEquals(listOf(3), block.ours.map { it.number })
        assertEquals(listOf(5), block.theirs.map { it.number })
        assertTrue(doc.endsWithNewline)
    }

    @Test
    fun parsesDiff3BaseSection() {
        val doc = ConflictParser.parse(diff3)
        assertFalse(doc.malformed)
        assertEquals(1, doc.blocks.size)
        val block = doc.blocks[0]
        assertEquals(listOf("base line"), block.base?.map { it.text })
        assertEquals("merged common ancestors", block.baseLabel)
        assertEquals(listOf("ours line"), block.ours.map { it.text })
        assertEquals(listOf("theirs line"), block.theirs.map { it.text })
        assertEquals(8, block.endLine)
    }

    @Test
    fun parsesMultipleBlocksAndContext() {
        val text = "head\n<<<<<<< HEAD\na\n=======\nb\n>>>>>>> t\nmid\n" +
            "<<<<<<< HEAD\nc\nd\n=======\ne\n>>>>>>> t\ntail\n"
        val doc = ConflictParser.parse(text)
        assertFalse(doc.malformed)
        assertEquals(2, doc.blocks.size)
        assertEquals(listOf("a"), doc.blocks[0].ours.map { it.text })
        assertEquals(listOf("c", "d"), doc.blocks[1].ours.map { it.text })
        assertEquals(listOf("e"), doc.blocks[1].theirs.map { it.text })
        assertEquals(1, doc.blocks[1].index)
        val contexts = doc.segments.filterIsInstance<ConflictSegment.Context>()
        assertEquals(listOf("head"), contexts.first().lines.map { it.text })
        assertEquals(listOf("mid"), contexts[1].lines.map { it.text })
        assertEquals(listOf("tail"), contexts.last().lines.map { it.text })
    }

    @Test
    fun noConflictTextIsSingleContext() {
        val doc = ConflictParser.parse("plain\ntext\n")
        assertFalse(doc.hasConflicts)
        assertFalse(doc.malformed)
        assertEquals(1, doc.segments.size)
        assertEquals(
            listOf("plain", "text"),
            (doc.segments[0] as ConflictSegment.Context).lines.map { it.text },
        )
    }

    @Test
    fun emptyTextParsesClean() {
        val doc = ConflictParser.parse("")
        assertFalse(doc.hasConflicts)
        assertFalse(doc.malformed)
        assertEquals("", ConflictParser.resolve(doc, emptyMap()))
    }

    @Test
    fun looksLikeMarkerButNotExactIsContext() {
        val text = "x <<<<<<< HEAD\n======= extra\n>>>>>>>x\n"
        val doc = ConflictParser.parse(text)
        assertFalse(doc.hasConflicts)
        assertFalse(doc.malformed)
    }

    @Test
    fun unterminatedBlockIsMalformedAndKeptVerbatim() {
        val text = "a\n<<<<<<< HEAD\nours\n=======\ntheirs\n"
        val doc = ConflictParser.parse(text)
        assertTrue(doc.malformed)
        assertFalse(doc.hasConflicts)
        assertEquals(text, ConflictParser.resolve(doc, emptyMap()))
        assertEquals(text, ConflictParser.resolveText(text, ConflictResolution.OURS))
        assertTrue(ConflictParser.hasConflictMarkers(text))
    }

    @Test
    fun nestedStartMarkerIsMalformedNotNestedBlock() {
        val text = "<<<<<<< HEAD\nours\n<<<<<<< HEAD\ninner\n=======\ntheirs\n>>>>>>> t\n"
        val doc = ConflictParser.parse(text)
        assertTrue(doc.malformed)
        assertFalse(doc.hasConflicts)
        assertEquals(text, ConflictParser.resolveText(text, ConflictResolution.OURS))
    }

    @Test
    fun strayEndMarkerOutsideBlockIsContext() {
        val text = "a\n>>>>>>> t\n"
        val doc = ConflictParser.parse(text)
        assertFalse(doc.malformed)
        assertFalse(doc.hasConflicts)
        assertEquals(text, ConflictParser.resolve(doc, emptyMap()))
    }

    @Test
    fun markdownEqualsUnderlineIsNotConflict() {
        val text = "Title\n=======\nbody\n"
        val doc = ConflictParser.parse(text)
        assertFalse(doc.malformed)
        assertFalse(doc.hasConflicts)
        assertFalse(ConflictParser.hasConflictMarkers(text))
    }

    @Test
    fun resolveOursRemovesMarkers() {
        val expected = "alpha\nours line\nomega\n"
        assertEquals(expected, ConflictParser.resolveText(standard, ConflictResolution.OURS))
    }

    @Test
    fun resolveTheirsRemovesMarkers() {
        val expected = "alpha\ntheirs line\nomega\n"
        assertEquals(expected, ConflictParser.resolveText(standard, ConflictResolution.THEIRS))
    }

    @Test
    fun resolveBothKeepsOursThenTheirs() {
        val expected = "alpha\nours line\ntheirs line\nomega\n"
        assertEquals(expected, ConflictParser.resolveText(standard, ConflictResolution.BOTH))
    }

    @Test
    fun resolveDiff3DropsBaseSection() {
        assertEquals(
            "alpha\nours line\nomega\n",
            ConflictParser.resolveText(diff3, ConflictResolution.OURS),
        )
        assertEquals(
            "alpha\ntheirs line\nomega\n",
            ConflictParser.resolveText(diff3, ConflictResolution.THEIRS),
        )
    }

    @Test
    fun perBlockChoicesMixStrategies() {
        val text = "<<<<<<< HEAD\na\n=======\nb\n>>>>>>> t\nmid\n" +
            "<<<<<<< HEAD\nc\n=======\nd\n>>>>>>> t\n"
        val doc = ConflictParser.parse(text)
        val resolved = ConflictParser.resolve(
            doc,
            mapOf(0 to ConflictResolution.OURS, 1 to ConflictResolution.THEIRS),
        )
        assertEquals("a\nmid\nd\n", resolved)
    }

    @Test
    fun unresolvedBlockKeepsRawMarkers() {
        val doc = ConflictParser.parse(standard)
        val resolved = ConflictParser.resolve(doc, emptyMap())
        assertEquals(standard, resolved)
        assertTrue(ConflictParser.hasConflictMarkers(resolved))
        assertFalse(ConflictParser.isFullyChosen(doc, emptyMap()))
    }

    @Test
    fun isFullyChosenRequiresEveryBlock() {
        val text = "<<<<<<< HEAD\na\n=======\nb\n>>>>>>> t\n" +
            "<<<<<<< HEAD\nc\n=======\nd\n>>>>>>> t\n"
        val doc = ConflictParser.parse(text)
        assertFalse(ConflictParser.isFullyChosen(doc, mapOf(0 to ConflictResolution.OURS)))
        assertTrue(
            ConflictParser.isFullyChosen(
                doc,
                mapOf(0 to ConflictResolution.OURS, 1 to ConflictResolution.BOTH),
            ),
        )
    }

    @Test
    fun resolvedTextHasNoMarkersAndCanMarkResolved() {
        val resolved = ConflictParser.resolveText(standard, ConflictResolution.BOTH)
        assertFalse(ConflictParser.hasConflictMarkers(resolved))
        assertTrue(ConflictParser.canMarkResolved(resolved))
        assertFalse(ConflictParser.canMarkResolved(standard))
    }

    @Test
    fun preservesMissingTrailingNewline() {
        val text = "<<<<<<< HEAD\na\n=======\nb\n>>>>>>> t"
        val doc = ConflictParser.parse(text)
        assertFalse(doc.endsWithNewline)
        assertEquals("a", ConflictParser.resolveText(text, ConflictResolution.OURS))
        assertEquals("b", ConflictParser.resolveText(text, ConflictResolution.THEIRS))
    }

    @Test
    fun emptySidesResolveToEmpty() {
        val text = "x\n<<<<<<< HEAD\n=======\n>>>>>>> t\ny\n"
        assertEquals("x\ny\n", ConflictParser.resolveText(text, ConflictResolution.OURS))
        assertEquals("x\ny\n", ConflictParser.resolveText(text, ConflictResolution.BOTH))
    }

    @Test
    fun markerWithoutLabelsParses() {
        val text = "<<<<<<<\na\n=======\nb\n>>>>>>>\n"
        val doc = ConflictParser.parse(text)
        assertFalse(doc.malformed)
        assertEquals(1, doc.blocks.size)
        assertEquals("", doc.blocks[0].oursLabel)
        assertEquals("", doc.blocks[0].theirsLabel)
    }

    @Test
    fun malformedDocumentIsNotFullyChosen() {
        val doc = ConflictParser.parse("<<<<<<< HEAD\na\n=======\nb\n")
        assertFalse(ConflictParser.isFullyChosen(doc, mapOf(0 to ConflictResolution.OURS)))
    }
}