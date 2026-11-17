package com.andmx.agent.office

import android.app.Application
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.ByteArrayOutputStream
import java.io.OutputStream

@Implements(PdfDocument::class)
class ShadowPdfDocument {
    private val finishedPages = mutableListOf<PdfDocument.PageInfo>()
    private var currentPage: PdfDocument.Page? = null
    private var closed = false

    @Implementation
    fun __constructor__() {
    }

    @Implementation
    fun startPage(pageInfo: PdfDocument.PageInfo): PdfDocument.Page {
        check(!closed) { "document closed" }
        check(currentPage == null) { "page not finished" }
        val page = ReflectionHelpers.callConstructor(
            PdfDocument.Page::class.java,
            ClassParameter.from(Canvas::class.java, Canvas()),
            ClassParameter.from(PdfDocument.PageInfo::class.java, pageInfo),
        )
        currentPage = page
        return page
    }

    @Implementation
    fun finishPage(page: PdfDocument.Page) {
        check(!closed) { "document closed" }
        check(page === currentPage) { "invalid page" }
        finishedPages += page.info
        currentPage = null
    }

    @Implementation
    fun getPages(): List<PdfDocument.PageInfo> = finishedPages.toList()

    @Implementation
    fun writeTo(out: OutputStream) {
        check(!closed) { "document closed" }
        check(currentPage == null) { "page not finished" }
        out.write(minimalPdf(finishedPages.toList()))
    }

    @Implementation
    fun close() {
        closed = true
    }

    companion object {
        fun minimalPdf(pages: List<PdfDocument.PageInfo>): ByteArray {
            val count = pages.size.coerceAtLeast(1)
            val objects = ArrayList<String>()
            objects += "<< /Type /Catalog /Pages 2 0 R >>"
            val kids = (0 until count).joinToString(" ") { "${3 + it} 0 R" }
            objects += "<< /Type /Pages /Kids [$kids] /Count $count >>"
            for (i in 0 until count) {
                val info = pages.getOrNull(i)
                val width = info?.pageWidth ?: 595
                val height = info?.pageHeight ?: 842
                objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height] >>"
            }
            val buffer = ByteArrayOutputStream()
            fun write(text: String) = buffer.write(text.toByteArray(Charsets.US_ASCII))
            write("%PDF-1.4\n")
            val offsets = ArrayList<Int>()
            objects.forEachIndexed { index, body ->
                offsets += buffer.size()
                write("${index + 1} 0 obj\n$body\nendobj\n")
            }
            val xrefStart = buffer.size()
            write("xref\n0 ${objects.size + 1}\n")
            write("0000000000 65535 f \n")
            offsets.forEach { write("%010d 00000 n \n".format(it)) }
            write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xrefStart\n%%EOF\n")
            return buffer.toByteArray()
        }

        fun of(document: PdfDocument): ShadowPdfDocument = Shadow.extract(document)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], shadows = [ShadowPdfDocument::class])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class PdfWriterTest {
    @Test
    fun pdfIsNonEmptyAndWellFormed() {
        val model = MarkdownToDocument.convert("# 标题\n正文段落 **加粗**\n- 要点\n\n| a | b |\n| 1 | 2 |")
        val bytes = PdfWriter.write(model)
        assertTrue(bytes.isNotEmpty())
        assertEquals("%PDF", String(bytes.copyOfRange(0, 4), Charsets.US_ASCII))
        assertTrue(String(bytes, Charsets.US_ASCII).contains("%%EOF"))
    }

    @Test
    fun singlePageForShortDocument() {
        val document = PdfDocument()
        val info = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = document.startPage(info)
        document.finishPage(page)
        val out = ByteArrayOutputStream()
        document.writeTo(out)
        document.close()
        assertEquals(1, ShadowPdfDocument.of(document).getPages().size)
        assertTrue(out.toByteArray().isNotEmpty())
    }

    @Test
    fun longDocumentPaginates() {
        val longText = (1..400).joinToString("\n") { "第 $it 行正文内容，用来触发自动分页逻辑。" }
        val model = MarkdownToDocument.convert(longText)
        val bytes = PdfWriter.write(model)
        val pdf = String(bytes, Charsets.US_ASCII)
        val count = Regex("""/Count (\d+)""").find(pdf)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        assertTrue("expected multiple pages, got $count", count > 1)
    }

    @Test
    fun emptyModelStillProducesOnePage() {
        val bytes = PdfWriter.write(DocumentModel(emptyList()))
        val pdf = String(bytes, Charsets.US_ASCII)
        assertTrue(pdf.contains("/Count 1"))
    }
}
