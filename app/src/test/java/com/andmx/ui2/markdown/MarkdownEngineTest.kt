package com.andmx.ui2.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownEngineTest {

    @Test
    fun headingsAtAllLevels() {
        val blocks = MarkdownEngine.parse("# H1\n## H2\n### H3\n#### H4\n##### H5\n###### H6")
        assertEquals(6, blocks.size)
        listOf(1, 2, 3, 4, 5, 6).forEachIndexed { idx, level ->
            val h = blocks[idx] as MdBlock.Heading
            assertEquals(level, h.level)
            assertEquals("H$level", h.text)
        }
    }

    @Test
    fun hashWithoutSpaceIsParagraph() {
        val blocks = MarkdownEngine.parse("#tag and #other")
        assertEquals(listOf(MdBlock.Paragraph("#tag and #other")), blocks)
    }

    @Test
    fun sevenHashesIsParagraph() {
        val blocks = MarkdownEngine.parse("####### too deep")
        assertEquals(listOf(MdBlock.Paragraph("####### too deep")), blocks)
    }

    @Test
    fun unorderedBullets() {
        val blocks = MarkdownEngine.parse("- a\n* b\n+ c")
        assertEquals(listOf(MdBlock.List(ordered = false, items = listOf("a", "b", "c"))), blocks)
    }

    @Test
    fun indentedListItems() {
        val blocks = MarkdownEngine.parse("  - a\n\t- b")
        assertEquals(listOf(MdBlock.List(ordered = false, items = listOf("a", "b"))), blocks)
    }

    @Test
    fun orderedDotAndParenMarkers() {
        val blocks = MarkdownEngine.parse("1. a\n2) b\n 3. c")
        assertEquals(listOf(MdBlock.List(ordered = true, items = listOf("a", "b", "c"))), blocks)
    }

    @Test
    fun orderedItemKeepsInnerDots() {
        val blocks = MarkdownEngine.parse("1. a. b")
        assertEquals(listOf(MdBlock.List(ordered = true, items = listOf("a. b"))), blocks)
    }

    @Test
    fun codeFencePreservesLanguageAndContent() {
        val src = "```kotlin\nval x = 1\nval y = 2\n```"
        val blocks = MarkdownEngine.parse(src)
        assertEquals(listOf(MdBlock.Code(lang = "kotlin", code = "val x = 1\nval y = 2")), blocks)
    }

    @Test
    fun unclosedCodeFenceStillYieldsCode() {
        val blocks = MarkdownEngine.parse("```\nline1\nline2")
        assertEquals(listOf(MdBlock.Code(lang = "", code = "line1\nline2")), blocks)
    }

    @Test
    fun quote() {
        val blocks = MarkdownEngine.parse("> quoted\n>q2")
        assertEquals(listOf(MdBlock.Quote("quoted"), MdBlock.Quote("q2")), blocks)
    }

    @Test
    fun paragraphJoinsConsecutiveLines() {
        val blocks = MarkdownEngine.parse("first\nsecond\n\nthird")
        assertEquals(
            listOf(MdBlock.Paragraph("first second"), MdBlock.Paragraph("third")),
            blocks,
        )
    }

    @Test
    fun paragraphIsInterruptedByHeadingAndList() {
        val blocks = MarkdownEngine.parse("text\n# Head\nmore\n- item")
        assertEquals(
            listOf(
                MdBlock.Paragraph("text"),
                MdBlock.Heading(1, "Head"),
                MdBlock.Paragraph("more"),
                MdBlock.List(ordered = false, items = listOf("item")),
            ),
            blocks,
        )
    }

    @Test
    fun blankLinesAreSkipped() {
        assertEquals(emptyList<MdBlock>(), MarkdownEngine.parse("\n\n   \n\n"))
    }

    @Test
    fun emptyInput() {
        assertEquals(emptyList<MdBlock>(), MarkdownEngine.parse(""))
    }
}
