package com.andmx.agent.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownToDocumentTest {
    @Test
    fun headingsLevelsOneToThree() {
        val model = MarkdownToDocument.convert("# T1\n## T2\n### T3\n#### stays text")
        val paragraphs = model.paragraphs
        assertEquals(ParagraphStyle.HEADING1, paragraphs[0].style)
        assertEquals("T1", paragraphs[0].text)
        assertEquals(ParagraphStyle.HEADING2, paragraphs[1].style)
        assertEquals(ParagraphStyle.HEADING3, paragraphs[2].style)
        assertEquals(ParagraphStyle.NORMAL, paragraphs[3].style)
        assertEquals("#### stays text", paragraphs[3].text)
    }

    @Test
    fun bulletsWithMarkers() {
        val model = MarkdownToDocument.convert("- one\n* two\n+ three")
        assertEquals(3, model.paragraphs.size)
        assertTrue(model.paragraphs.all { it.style == ParagraphStyle.BULLET })
        assertEquals(listOf("one", "two", "three"), model.paragraphs.map { it.text })
    }

    @Test
    fun boldRunsSplit() {
        val model = MarkdownToDocument.convert("plain **bold** tail **b2**")
        val runs = model.paragraphs.single().runs
        assertEquals(listOf("plain ", "bold", " tail ", "b2"), runs.map { it.text })
        assertEquals(listOf(false, true, false, true), runs.map { it.bold })
        assertEquals("plain bold tail b2", model.paragraphs.single().text)
    }

    @Test
    fun tablesWithSeparatorRow() {
        val markdown = """
            | Name | Qty |
            | --- | ---: |
            | Apple | 3 |
            | Pear | 5 |
        """.trimIndent()
        val model = MarkdownToDocument.convert(markdown)
        val table = model.tables.single()
        assertEquals(3, table.rows.size)
        assertEquals(listOf("Name", "Qty"), table.rows[0])
        assertEquals(listOf("Apple", "3"), table.rows[1])
        assertTrue(model.paragraphs.isEmpty())
    }

    @Test
    fun mixedDocumentKeepsOrder() {
        val markdown = "# Title\nintro\n- item\n\n| a | b |\n| 1 | 2 |\n\nend"
        val model = MarkdownToDocument.convert(markdown)
        assertEquals(5, model.blocks.size)
        assertTrue(model.blocks[0] is DocBlock.Paragraph)
        assertTrue(model.blocks[3] is DocBlock.Table)
        assertEquals("end", (model.blocks[4] as DocBlock.Paragraph).paragraph.text)
    }

    @Test
    fun emptyInputProducesEmptyModel() {
        assertTrue(MarkdownToDocument.convert("").blocks.isEmpty())
        assertTrue(MarkdownToDocument.convert("\n \n").blocks.isEmpty())
    }

    @Test
    fun structuredJsonContent() {
        val content = """
            {"blocks":[
              {"type":"heading","level":2,"text":"Header"},
              {"type":"paragraph","text":"Body","bold":true},
              {"type":"bullet","text":"point"},
              {"type":"table","rows":[["h1","h2"],["a","b"]]}
            ]}
        """.trimIndent()
        val model = DocumentModel.fromContent(content)
        assertEquals(4, model.blocks.size)
        assertEquals(ParagraphStyle.HEADING2, model.paragraphs[0].style)
        assertTrue(model.paragraphs[1].runs.single().bold)
        assertEquals(ParagraphStyle.BULLET, model.paragraphs[2].style)
        assertEquals(listOf(listOf("h1", "h2"), listOf("a", "b")), model.tables.single().rows)
    }

    @Test
    fun invalidStructuredJsonFallsBackToMarkdown() {
        val model = DocumentModel.fromContent("""{"blocks":[{"type":"unknown"}]}""")
        assertEquals(1, model.paragraphs.size)
        assertFalse(model.paragraphs[0].runs.any { it.bold })
    }

    @Test
    fun slidesSplitOnHeadings() {
        val model = MarkdownToDocument.convert("# S1\nline1\n- b\n## S2\nline2")
        val slides = model.toSlides()
        assertEquals(2, slides.size)
        assertEquals("S1", slides[0].title)
        assertEquals(listOf("line1", "• b"), slides[0].body)
        assertEquals("S2", slides[1].title)
        assertEquals(listOf("line2"), slides[1].body)
    }

    @Test
    fun sheetRowsFlattenBlocks() {
        val model = MarkdownToDocument.convert("text\n\n| a | b |\n| 1 | 2 |")
        val rows = model.toSheetRows()
        assertEquals(listOf(listOf("text"), listOf("a", "b"), listOf("1", "2")), rows)
    }
}
