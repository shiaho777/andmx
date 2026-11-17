package com.andmx.agent.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class OoxmlStructureTest {
    private val markdown = """
        # 标题 & <demo>
        普通段落 **加粗** 收尾
        - 第一项
        - 第二项

        | 名称 | 数量 |
        | --- | --- |
        | 苹果 | 3 |
    """.trimIndent()

    private fun entries(bytes: ByteArray): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                map[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        return map
    }

    private fun assertWellFormed(xml: String) {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun docxPartsAndStructure() {
        val model = MarkdownToDocument.convert(markdown)
        val parts = entries(DocxWriter.write(model))
        assertEquals(
            setOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/_rels/document.xml.rels"),
            parts.keys,
        )
        parts.values.forEach(::assertWellFormed)
        assertTrue(parts.getValue("[Content_Types].xml").contains("/word/document.xml"))
        assertTrue(parts.getValue("_rels/.rels").contains("word/document.xml"))
        val doc = parts.getValue("word/document.xml")
        assertTrue(doc.contains("""<w:pStyle w:val="Heading1"/>"""))
        assertTrue(doc.contains("<w:b/>"))
        assertTrue(doc.contains("• "))
        assertTrue(doc.contains("第一项"))
        assertTrue(doc.contains("<w:tbl>"))
        assertTrue(doc.contains("标题 &amp; &lt;demo&gt;"))
        assertFalse(doc.contains("标题 & <demo>"))
    }

    @Test
    fun xlsxPartsAndInlineCells() {
        val model = MarkdownToDocument.convert(markdown)
        val parts = entries(XlsxWriter.write(model))
        assertEquals(
            setOf(
                "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels", "xl/worksheets/sheet1.xml",
            ),
            parts.keys,
        )
        parts.values.forEach(::assertWellFormed)
        assertTrue(parts.getValue("xl/workbook.xml").contains("""r:id="rId1""""))
        assertTrue(parts.getValue("xl/_rels/workbook.xml.rels").contains("worksheets/sheet1.xml"))
        val sheet = parts.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("""t="inlineStr""""))
        assertTrue(sheet.contains("<is><t"))
        assertTrue(sheet.contains("""<c r="B6"><v>3</v></c>"""))
        assertFalse(sheet.contains("sharedStrings"))
    }

    @Test
    fun xlsxColumnRefs() {
        assertEquals("A", XlsxWriter.columnRef(0))
        assertEquals("Z", XlsxWriter.columnRef(25))
        assertEquals("AA", XlsxWriter.columnRef(26))
        assertEquals("AB", XlsxWriter.columnRef(27))
    }

    @Test
    fun pptxPartsAndSlides() {
        val model = MarkdownToDocument.convert("# 第一页\n内容甲\n## 第二页\n内容乙")
        val parts = entries(PptxWriter.write(model))
        val required = setOf(
            "[Content_Types].xml", "_rels/.rels",
            "ppt/presentation.xml", "ppt/_rels/presentation.xml.rels",
            "ppt/slides/slide1.xml", "ppt/slides/slide2.xml",
            "ppt/slides/_rels/slide1.xml.rels", "ppt/slides/_rels/slide2.xml.rels",
            "ppt/slideLayouts/slideLayout1.xml", "ppt/slideLayouts/_rels/slideLayout1.xml.rels",
            "ppt/slideMasters/slideMaster1.xml", "ppt/slideMasters/_rels/slideMaster1.xml.rels",
            "ppt/theme/theme1.xml",
        )
        assertEquals(required, parts.keys)
        parts.values.forEach(::assertWellFormed)
        val presentation = parts.getValue("ppt/presentation.xml")
        assertTrue(presentation.contains("""<p:sldId id="256" r:id="rId2"/>"""))
        assertTrue(presentation.contains("""<p:sldId id="257" r:id="rId3"/>"""))
        assertTrue(parts.getValue("ppt/_rels/presentation.xml.rels").contains("slides/slide2.xml"))
        assertTrue(parts.getValue("ppt/slides/_rels/slide1.xml.rels").contains("../slideLayouts/slideLayout1.xml"))
        assertTrue(parts.getValue("ppt/slideLayouts/_rels/slideLayout1.xml.rels").contains("../slideMasters/slideMaster1.xml"))
        assertTrue(parts.getValue("ppt/slideMasters/_rels/slideMaster1.xml.rels").contains("../theme/theme1.xml"))
        assertTrue(parts.getValue("ppt/slides/slide1.xml").contains("第一页"))
        assertTrue(parts.getValue("ppt/slides/slide1.xml").contains("内容甲"))
        assertTrue(parts.getValue("ppt/slides/slide2.xml").contains("第二页"))
        val types = parts.getValue("[Content_Types].xml")
        assertTrue(types.contains("/ppt/slides/slide1.xml"))
        assertTrue(types.contains("/ppt/slides/slide2.xml"))
        assertTrue(types.contains("/ppt/theme/theme1.xml"))
    }

    @Test
    fun emptyModelStillProducesValidParts() {
        val empty = DocumentModel(emptyList())
        entries(DocxWriter.write(empty)).values.forEach(::assertWellFormed)
        entries(XlsxWriter.write(empty)).values.forEach(::assertWellFormed)
        val pptx = entries(PptxWriter.write(empty))
        pptx.values.forEach(::assertWellFormed)
        assertTrue(pptx.containsKey("ppt/slides/slide1.xml"))
    }
}
