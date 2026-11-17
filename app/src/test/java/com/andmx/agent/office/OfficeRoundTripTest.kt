package com.andmx.agent.office

import android.app.Application
import com.andmx.ui2.files.OfficeTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class OfficeRoundTripTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val markdown = """
        # 报告标题
        第一段普通文字
        - 项目一
        - 项目二

        | 名称 | 数量 |
        | --- | --- |
        | 苹果 | 3 |
        | 梨 | 5 |
    """.trimIndent()

    private fun writeTemp(name: String, bytes: ByteArray) =
        temp.newFile(name).apply { writeBytes(bytes) }

    @Test
    fun docxRoundTripThroughExtractor() {
        val model = MarkdownToDocument.convert(markdown)
        val file = writeTemp("report.docx", DocxWriter.write(model))
        val text = OfficeTextExtractor.extract(file)
        assertNotNull(text)
        assertTrue(text!!.contains("报告标题"))
        assertTrue(text.contains("第一段普通文字"))
        assertTrue(text.contains("• 项目一"))
        assertTrue(text.contains("• 项目二"))
        assertTrue(text.contains("名称"))
        assertTrue(text.contains("数量"))
        assertTrue(text.contains("苹果"))
        assertTrue(text.contains("梨"))
    }

    @Test
    fun xlsxRoundTripThroughExtractor() {
        val model = MarkdownToDocument.convert(markdown)
        val file = writeTemp("report.xlsx", XlsxWriter.write(model))
        val text = OfficeTextExtractor.extract(file)
        assertNotNull(text)
        assertTrue(text!!.contains("## Sheet 1"))
        assertTrue(text.contains("报告标题"))
        assertTrue(text.contains("名称\t数量"))
        assertTrue(text.contains("苹果\t3"))
        assertTrue(text.contains("梨\t5"))
    }

    @Test
    fun pptxRoundTripThroughExtractor() {
        val model = MarkdownToDocument.convert("# 第一页\n内容甲\n- 要点\n## 第二页\n内容乙")
        val file = writeTemp("slides.pptx", PptxWriter.write(model))
        val text = OfficeTextExtractor.extract(file)
        assertNotNull(text)
        assertTrue(text!!.contains("## 第 1 页"))
        assertTrue(text.contains("第一页"))
        assertTrue(text.contains("内容甲"))
        assertTrue(text.contains("• 要点"))
        assertTrue(text.contains("## 第 2 页"))
        assertTrue(text.contains("第二页"))
        assertTrue(text.contains("内容乙"))
        assertTrue(text.indexOf("第一页") < text.indexOf("第二页"))
    }

    @Test
    fun xmlEscapingSurvivesRoundTrip() {
        val model = MarkdownToDocument.convert("a & b < c > d \"q\" 's'")
        val file = writeTemp("escape.docx", DocxWriter.write(model))
        val text = OfficeTextExtractor.extract(file)
        assertNotNull(text)
        assertEquals("a & b < c > d \"q\" 's'", text!!.trim())
    }

    @Test
    fun renderDispatchMatchesDirectWriters() {
        val model = MarkdownToDocument.convert("# hi\nbody")
        assertEquals(
            DocxWriter.write(model).size,
            OfficeWriter.render(OfficeFormat.DOCX, model).size,
        )
        assertTrue(OfficeWriter.render(OfficeFormat.XLSX, model).isNotEmpty())
        assertTrue(OfficeWriter.render(OfficeFormat.PPTX, model).isNotEmpty())
    }

    @Test
    fun formatParsing() {
        assertEquals(OfficeFormat.DOCX, OfficeFormat.from("docx"))
        assertEquals(OfficeFormat.XLSX, OfficeFormat.from("XLSX"))
        assertEquals(OfficeFormat.PPTX, OfficeFormat.from(".pptx"))
        assertEquals(OfficeFormat.PDF, OfficeFormat.from("pdf"))
        assertEquals(null, OfficeFormat.from("html"))
    }
}
