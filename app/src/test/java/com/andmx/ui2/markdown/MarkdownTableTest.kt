package com.andmx.ui2.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTableTest {

    @Test
    fun parsesBasicPipeTable() {
        val md = """
            | A | B |
            |---|---|
            | 1 | 2 |
            | 3 | 4 |
        """.trimIndent()
        val blocks = MarkdownEngine.parse(md)
        assertEquals(1, blocks.size)
        val table = blocks[0] as MdBlock.Table
        assertEquals(listOf("A", "B"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("1", "2"), table.rows[0])
        assertEquals(listOf("3", "4"), table.rows[1])
    }

    @Test
    fun toleratesMissingTrailingPipeAndShortRows() {
        val md = """
            | Name | Age | City
            |------|-----|-----
            | Tom
            | Amy | 20 | SH | extra
        """.trimIndent()
        val blocks = MarkdownEngine.parse(md)
        val table = blocks[0] as MdBlock.Table
        assertEquals(3, table.header.size)
        assertEquals(listOf("Tom", "", ""), table.rows[0])
        assertEquals(listOf("Amy", "20", "SH"), table.rows[1])
    }

    @Test
    fun escapedPipeStaysInCell() {
        val cells = MarkdownEngine.splitTableRow("| a\\|b | c |")
        assertEquals(listOf("a|b", "c"), cells)
    }

    @Test
    fun alignmentVariantsRecognized() {
        assertTrue(MarkdownEngine.parse("|h|\n|---|\n|v|").first() is MdBlock.Table)
        assertTrue(MarkdownEngine.parse("|h|\n|:--|\n|v|").first() is MdBlock.Table)
        assertTrue(MarkdownEngine.parse("|h|\n|--:|\n|v|").first() is MdBlock.Table)
        assertTrue(MarkdownEngine.parse("|h|\n|:-:|\n|v|").first() is MdBlock.Table)
    }

    @Test
    fun nonTablePipeLinesStayParagraph() {
        val blocks = MarkdownEngine.parse("a | b without pipes-table")
        assertTrue(blocks.first() is MdBlock.Paragraph)
    }

    @Test
    fun separatorWithoutDashesIsNotTable() {
        // 第二行不含 '-'，不构成表格，保持段落
        val blocks = MarkdownEngine.parse("|a|b|\n|x|y|")
        assertTrue(blocks.first() is MdBlock.Paragraph)
    }

    @Test
    fun tableDoesNotSwallowFollowingParagraph() {
        val blocks = MarkdownEngine.parse("|h|\n|---|\n|v|\n\npara after")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Table)
        assertEquals("para after", (blocks[1] as MdBlock.Paragraph).text)
    }

    @Test
    fun inlineFormattingInCellsIsPreserved() {
        val table = MarkdownEngine.parse("| **A** |\n|---|\n| `x` |").first() as MdBlock.Table
        assertEquals("**A**", table.header[0])
        assertEquals("`x`", table.rows[0][0])
    }
}
