package com.andmx.agent.office

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class ParagraphStyle { NORMAL, HEADING1, HEADING2, HEADING3, BULLET }

data class InlineRun(val text: String, val bold: Boolean = false)

data class DocParagraph(
    val runs: List<InlineRun>,
    val style: ParagraphStyle = ParagraphStyle.NORMAL,
) {
    constructor(text: String, style: ParagraphStyle = ParagraphStyle.NORMAL) :
        this(listOf(InlineRun(text)), style)

    val text: String get() = runs.joinToString("") { it.text }
}

data class DocTable(val rows: List<List<String>>)

sealed interface DocBlock {
    data class Paragraph(val paragraph: DocParagraph) : DocBlock
    data class Table(val table: DocTable) : DocBlock
}

data class DocumentModel(val blocks: List<DocBlock>) {
    val paragraphs: List<DocParagraph>
        get() = blocks.mapNotNull { (it as? DocBlock.Paragraph)?.paragraph }

    val tables: List<DocTable>
        get() = blocks.mapNotNull { (it as? DocBlock.Table)?.table }

    fun plainText(): String = buildString {
        for (block in blocks) {
            when (block) {
                is DocBlock.Paragraph -> appendLine(block.paragraph.text)
                is DocBlock.Table -> block.table.rows.forEach { row ->
                    appendLine(row.joinToString("\t"))
                }
            }
        }
    }

    fun toSlides(): List<Slide> {
        val slides = ArrayList<Slide>()
        var title: String? = null
        val body = ArrayList<String>()
        fun flush() {
            if (title == null && body.isEmpty()) return
            slides += Slide(title ?: "", body.toList())
            title = null
            body.clear()
        }
        for (block in blocks) {
            when (block) {
                is DocBlock.Paragraph -> {
                    val p = block.paragraph
                    when (p.style) {
                        ParagraphStyle.HEADING1, ParagraphStyle.HEADING2 -> {
                            flush()
                            title = p.text
                        }
                        else -> body += if (p.style == ParagraphStyle.BULLET) "• ${p.text}" else p.text
                    }
                }
                is DocBlock.Table -> block.table.rows.forEach { row ->
                    body += row.joinToString("  |  ")
                }
            }
        }
        flush()
        return slides.ifEmpty { listOf(Slide("", emptyList())) }
    }

    fun toSheetRows(): List<List<String>> {
        val rows = ArrayList<List<String>>()
        for (block in blocks) {
            when (block) {
                is DocBlock.Paragraph -> rows += listOf(block.paragraph.text)
                is DocBlock.Table -> rows += block.table.rows
            }
        }
        return rows
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromContent(content: String): DocumentModel {
            val trimmed = content.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                val structured = runCatching { parseStructured(trimmed) }.getOrNull()
                if (structured != null) return structured
            }
            return MarkdownToDocument.convert(content)
        }

        private fun parseStructured(raw: String): DocumentModel? {
            val element = json.parseToJsonElement(raw)
            val blocksElement = when (element) {
                is JsonObject -> element["blocks"] ?: return null
                is JsonArray -> element
                else -> return null
            }
            val array = blocksElement as? JsonArray ?: return null
            val blocks = ArrayList<DocBlock>()
            for (item in array) {
                val obj = item as? JsonObject ?: return null
                when (obj.str("type")?.lowercase()) {
                    "paragraph", "heading", "bullet" -> {
                        val text = obj.str("text") ?: return null
                        val style = when {
                            obj.str("type")?.lowercase() == "bullet" -> ParagraphStyle.BULLET
                            obj.str("type")?.lowercase() == "heading" -> headingStyle(obj.str("level")?.toIntOrNull() ?: 1)
                            else -> styleOf(obj.str("style"))
                        }
                        val bold = obj.str("bold")?.equals("true", ignoreCase = true) == true
                        blocks += DocBlock.Paragraph(DocParagraph(listOf(InlineRun(text, bold)), style))
                    }
                    "table" -> {
                        val rows = obj["rows"] as? JsonArray ?: return null
                        val parsed = rows.map { row ->
                            val cells = row as? JsonArray ?: return null
                            cells.map { cell ->
                                when (cell) {
                                    is JsonPrimitive -> cell.contentOrNull.orEmpty()
                                    else -> cell.toString()
                                }
                            }
                        }
                        blocks += DocBlock.Table(DocTable(parsed))
                    }
                    else -> return null
                }
            }
            return DocumentModel(blocks)
        }

        private fun headingStyle(level: Int): ParagraphStyle = when (level) {
            1 -> ParagraphStyle.HEADING1
            2 -> ParagraphStyle.HEADING2
            else -> ParagraphStyle.HEADING3
        }

        private fun styleOf(raw: String?): ParagraphStyle = when (raw?.lowercase()) {
            "heading1", "h1" -> ParagraphStyle.HEADING1
            "heading2", "h2" -> ParagraphStyle.HEADING2
            "heading3", "h3" -> ParagraphStyle.HEADING3
            "bullet" -> ParagraphStyle.BULLET
            else -> ParagraphStyle.NORMAL
        }

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull
    }
}

data class Slide(val title: String, val body: List<String>)
