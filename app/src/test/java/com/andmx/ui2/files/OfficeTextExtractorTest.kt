package com.andmx.ui2.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfficeTextExtractorTest {

    private fun zip(extension: String, entries: Map<String, String>): File {
        val file = Files.createTempFile("office", ".$extension").toFile()
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun extractsDocxParagraphsAndTabs() {
        val file = zip(
            "docx",
            mapOf(
                "word/document.xml" to """
                    <w:document><w:body>
                      <w:p><w:r><w:t>Hello</w:t></w:r><w:r><w:tab/><w:t>world</w:t></w:r></w:p>
                      <w:p><w:r><w:t>Second &amp; last</w:t></w:r></w:p>
                    </w:body></w:document>
                """.trimIndent(),
            ),
        )

        val text = OfficeTextExtractor.extract(file)!!

        assertTrue(text.contains("Hello"))
        assertTrue(text.contains("world"))
        assertTrue(text.contains("Second & last"))
        assertTrue(text.contains("\t"))
    }

    @Test
    fun ordersPptxSlidesNumerically() {
        val file = zip(
            "pptx",
            mapOf(
                "ppt/slides/slide10.xml" to "<p:sld><a:p><a:t>Ten</a:t></a:p></p:sld>",
                "ppt/slides/slide1.xml" to "<p:sld><a:p><a:t>One</a:t></a:p></p:sld>",
                "ppt/slides/slide2.xml" to "<p:sld><a:p><a:t>Two</a:t></a:p></p:sld>",
            ),
        )

        val text = OfficeTextExtractor.extract(file)!!

        assertTrue(text.indexOf("One") < text.indexOf("Two"))
        assertTrue(text.indexOf("Two") < text.indexOf("Ten"))
        assertTrue(text.contains("## 第 1 页"))
        assertTrue(text.contains("## 第 3 页"))
    }

    @Test
    fun resolvesSharedStringsAndInlineCells() {
        val file = zip(
            "xlsx",
            mapOf(
                "xl/sharedStrings.xml" to "<sst><si><t>Name</t></si><si><t>Alice</t></si></sst>",
                "xl/worksheets/sheet1.xml" to """
                    <worksheet><sheetData>
                      <row><c t="s"><v>0</v></c><c t="s"><v>1</v></c></row>
                      <row><c t="inlineStr"><is><t>42</t></is></c></row>
                    </sheetData></worksheet>
                """.trimIndent(),
            ),
        )

        val text = OfficeTextExtractor.extract(file)!!

        assertTrue(text.contains("Name\tAlice"))
        assertTrue(text.contains("42"))
        assertTrue(text.contains("## Sheet 1"))
    }

    @Test
    fun unsupportedOrBrokenFilesReturnNull() {
        val txt = Files.createTempFile("plain", ".txt").toFile().apply {
            writeText("hello")
            deleteOnExit()
        }
        assertNull(OfficeTextExtractor.extract(txt))

        val broken = Files.createTempFile("broken", ".docx").toFile().apply {
            writeText("not a zip")
            deleteOnExit()
        }
        assertNull(OfficeTextExtractor.extract(broken))
        assertEquals(false, OfficeTextExtractor.isSupported("plain.txt"))
        assertEquals(true, OfficeTextExtractor.isSupported("book.DOCX"))
    }
}
