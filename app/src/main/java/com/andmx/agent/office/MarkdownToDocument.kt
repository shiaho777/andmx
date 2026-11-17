package com.andmx.agent.office

object MarkdownToDocument {
    private val heading = Regex("""^(#{1,3})\s+(.*)$""")
    private val bullet = Regex("""^\s*[-*+]\s+(.*)$""")
    private val boldSplit = Regex("""\*\*(.+?)\*\*""")

    fun convert(markdown: String): DocumentModel {
        val blocks = ArrayList<DocBlock>()
        val lines = markdown.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> index += 1
                isTableLine(trimmed) -> {
                    val rows = ArrayList<List<String>>()
                    while (index < lines.size && isTableLine(lines[index].trim())) {
                        val cells = splitTableRow(lines[index].trim())
                        if (!isSeparatorRow(cells)) rows += cells
                        index += 1
                    }
                    if (rows.isNotEmpty()) blocks += DocBlock.Table(DocTable(rows))
                }
                heading.matches(trimmed) -> {
                    val match = heading.matchEntire(trimmed)!!
                    val style = when (match.groupValues[1].length) {
                        1 -> ParagraphStyle.HEADING1
                        2 -> ParagraphStyle.HEADING2
                        else -> ParagraphStyle.HEADING3
                    }
                    blocks += DocBlock.Paragraph(DocParagraph(parseRuns(match.groupValues[2]), style))
                    index += 1
                }
                bullet.matches(line) -> {
                    val text = bullet.matchEntire(line)!!.groupValues[1]
                    blocks += DocBlock.Paragraph(DocParagraph(parseRuns(text), ParagraphStyle.BULLET))
                    index += 1
                }
                else -> {
                    blocks += DocBlock.Paragraph(DocParagraph(parseRuns(trimmed), ParagraphStyle.NORMAL))
                    index += 1
                }
            }
        }
        return DocumentModel(blocks)
    }

    fun parseRuns(text: String): List<InlineRun> {
        val runs = ArrayList<InlineRun>()
        var cursor = 0
        for (match in boldSplit.findAll(text)) {
            if (match.range.first > cursor) {
                runs += InlineRun(text.substring(cursor, match.range.first))
            }
            runs += InlineRun(match.groupValues[1], bold = true)
            cursor = match.range.last + 1
        }
        if (cursor < text.length) runs += InlineRun(text.substring(cursor))
        return runs.ifEmpty { listOf(InlineRun("")) }
    }

    private fun isTableLine(trimmed: String): Boolean =
        trimmed.startsWith("|") && trimmed.length > 1

    private fun splitTableRow(trimmed: String): List<String> =
        trimmed.removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    private fun isSeparatorRow(cells: List<String>): Boolean =
        cells.isNotEmpty() && cells.all { cell ->
            cell.isNotEmpty() && cell.all { it == '-' || it == ':' || it == ' ' }
        }
}
