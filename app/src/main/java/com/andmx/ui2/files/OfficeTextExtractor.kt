package com.andmx.ui2.files

import java.io.File
import java.util.zip.ZipFile

/**
 * Plain-text extraction for OOXML documents.
 *
 * A full layout renderer is out of scope on-device, but showing the text is far
 * more useful than a binary placeholder. Formatting, images and charts are not
 * preserved and the UI says so.
 */
object OfficeTextExtractor {
    private const val MAX_FILE_BYTES = 64L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
    private const val MAX_OUTPUT_CHARS = 1_000_000

    fun isSupported(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf("docx", "pptx", "xlsx")

    fun extract(file: File): String? {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return null
        return runCatching {
            ZipFile(file).use { zip ->
                when (file.extension.lowercase()) {
                    "docx" -> docx(zip)
                    "pptx" -> pptx(zip)
                    "xlsx" -> xlsx(zip)
                    else -> null
                }
            }
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }?.take(MAX_OUTPUT_CHARS)
    }

    private fun docx(zip: ZipFile): String? {
        val xml = readEntry(zip, "word/document.xml") ?: return null
        return xmlToText(xml)
    }

    private fun pptx(zip: ZipFile): String? {
        val slides = zip.entries().toList()
            .filter { it.name.matches(Regex("""ppt/slides/slide\d+\.xml""")) }
            .sortedBy { it.name.substringAfterLast("slide").substringBefore('.').toIntOrNull() ?: 0 }
        if (slides.isEmpty()) return null
        return buildString {
            slides.forEachIndexed { index, entry ->
                val xml = readEntry(zip, entry.name) ?: return@forEachIndexed
                val text = xmlToText(xml).trim()
                if (text.isBlank()) return@forEachIndexed
                if (isNotEmpty()) appendLine()
                appendLine("## 第 ${index + 1} 页")
                appendLine(text)
            }
        }
    }

    private fun xlsx(zip: ZipFile): String? {
        val shared = readEntry(zip, "xl/sharedStrings.xml")?.let(::sharedStrings).orEmpty()
        val sheets = zip.entries().toList()
            .filter { it.name.matches(Regex("""xl/worksheets/sheet\d+\.xml""")) }
            .sortedBy { it.name.substringAfterLast("sheet").substringBefore('.').toIntOrNull() ?: 0 }
        if (sheets.isEmpty()) return null
        return buildString {
            sheets.forEachIndexed { index, entry ->
                val xml = readEntry(zip, entry.name) ?: return@forEachIndexed
                val rows = sheetRows(xml, shared)
                if (rows.isEmpty()) return@forEachIndexed
                if (isNotEmpty()) appendLine()
                appendLine("## Sheet ${index + 1}")
                rows.forEach { appendLine(it) }
            }
        }
    }

    private fun sheetRows(xml: String, shared: List<String>): List<String> {
        val rowRegex = Regex("""<row\b[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL)
        val cellRegex = Regex("""<c\b([^>]*)>(.*?)</c>""", RegexOption.DOT_MATCHES_ALL)
        val typeRegex = Regex("""\bt="([^"]*)"""")
        val valueRegex = Regex("""<v[^>]*>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL)
        val inlineRegex = Regex("""<t\b[^>]*>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL)
        return rowRegex.findAll(xml).mapNotNull { row ->
            val cells = cellRegex.findAll(row.groupValues[1]).mapNotNull { cell ->
                val attrs = cell.groupValues[1]
                val body = cell.groupValues[2]
                val type = typeRegex.find(attrs)?.groupValues?.getOrNull(1).orEmpty()
                when (type) {
                    "s" -> {
                        val idx = valueRegex.find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        idx?.let { shared.getOrNull(it) }.orEmpty()
                    }
                    "inlineStr" -> inlineRegex.find(body)?.groupValues?.getOrNull(1)?.let(::unescape).orEmpty()
                    else -> valueRegex.find(body)?.groupValues?.getOrNull(1)?.let(::unescape).orEmpty()
                }
            }.toList()
            cells.joinToString("\t").trimEnd().ifBlank { null }
        }.take(2_000).toList()
    }

    private fun sharedStrings(xml: String): List<String> {
        val siRegex = Regex("""<si\b[^>]*>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL)
        val textRegex = Regex("""<t\b[^>]*>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL)
        return siRegex.findAll(xml).map { si ->
            textRegex.findAll(si.groupValues[1])
                .joinToString("") { unescape(it.groupValues[1]) }
        }.toList()
    }

    private fun xmlToText(xml: String): String {
        var text = xml
        text = text.replace(Regex("""<w:tab\b[^>]*/>"""), "\t")
        text = text.replace(Regex("""<w:br\b[^>]*/>"""), "\n")
        text = text.replace(Regex("""</(w:p|a:p|w:tr)>"""), "\n")
        text = text.replace(Regex("""</(w:tc|a:tc)>"""), "\t")
        text = text.replace(Regex("""<[^>]+>"""), "")
        return unescape(text)
            .lineSequence()
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("""\n{3,}"""), "\n\n")
    }

    private fun readEntry(zip: ZipFile, name: String): String? {
        val entry = zip.getEntry(name) ?: return null
        if (entry.size > MAX_ENTRY_BYTES) return null
        return zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun unescape(raw: String): String = raw
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
