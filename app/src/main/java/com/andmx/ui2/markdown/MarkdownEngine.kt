package com.andmx.ui2.markdown

sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class Code(val lang: String, val code: String) : MdBlock()
    data class List(val ordered: Boolean, val items: kotlin.collections.List<String>) : MdBlock()
    data class Quote(val text: String) : MdBlock()

    /** GFM 管道表格（ZCode markdownTable 对齐）。 */
    data class Table(val header: kotlin.collections.List<String>, val rows: kotlin.collections.List<kotlin.collections.List<String>>) : MdBlock()
}

object MarkdownEngine {
    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val unordered = Regex("^\\s*[-*+]\\s+(.*)$")
    private val ordered = Regex("^\\s*\\d+[.)]\\s+(.*)$")
    private val tableRow = Regex("^\\s*\\|(.+)\\|?\\s*$")
    private val tableAlign = Regex("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$")

    /**
     * 解析一行管道表格为单元格。容错：缺首尾管道、缺格补空、管道转义 `\|`。
     */
    fun splitTableRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.substringBeforeLast('|')
        val cells = mutableListOf<String>()
        val sb = StringBuilder()
        var escaped = false
        for (c in s) {
            when {
                escaped -> { sb.append(c); escaped = false }
                c == '\\' -> escaped = true
                c == '|' -> { cells.add(sb.toString().trim()); sb.clear() }
                else -> sb.append(c)
            }
        }
        cells.add(sb.toString().trim())
        return cells
    }

    private fun isTableLine(line: String): Boolean = tableRow.matches(line)

    private fun isAlignLine(line: String): Boolean =
        line.contains('-') && tableAlign.matches(line)

    fun parse(markdown: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val lines = markdown.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            when {
                line.startsWith("```") -> {
                    val lang = line.substring(3).trim()
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].startsWith("```")) {
                        code.add(lines[i])
                        i++
                    }
                    if (i < lines.size && lines[i].startsWith("```")) {
                        i++
                    }
                    blocks.add(MdBlock.Code(lang, code.joinToString("\n")))
                }

                heading.matches(line) -> {
                    val m = heading.find(line)!!
                    blocks.add(MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim()))
                    i++
                }

                line.startsWith(">") -> {
                    val text = line.substring(1).trim()
                    blocks.add(MdBlock.Quote(text))
                    i++
                }

                // GFM 表格：当前行是管道行，下一行是对齐行（---|---）
                isTableLine(line) && i + 1 < lines.size && isAlignLine(lines[i + 1]) -> {
                    val header = splitTableRow(line)
                    val alignCells = splitTableRow(lines[i + 1])
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && isTableLine(lines[i])) {
                        val cells = splitTableRow(lines[i]).toMutableList()
                        while (cells.size < header.size) cells.add("")
                        rows.add(cells.take(header.size))
                        i++
                    }
                    blocks.add(MdBlock.Table(header, rows))
                }

                unordered.matches(line) -> {
                    val items = mutableListOf<String>()
                    while (i < lines.size && unordered.matches(lines[i])) {
                        items.add(unordered.find(lines[i])!!.groupValues[1].trim())
                        i++
                    }
                    blocks.add(MdBlock.List(false, items))
                }

                ordered.matches(line) -> {
                    val items = mutableListOf<String>()
                    while (i < lines.size && ordered.matches(lines[i])) {
                        items.add(ordered.find(lines[i])!!.groupValues[1].trim())
                        i++
                    }
                    blocks.add(MdBlock.List(true, items))
                }

                line.isNotBlank() -> {
                    val para = mutableListOf(line)
                    i++
                    while (
                        i < lines.size &&
                        lines[i].isNotBlank() &&
                        !heading.matches(lines[i]) &&
                        !lines[i].startsWith(">") &&
                        !lines[i].startsWith("```") &&
                        !unordered.matches(lines[i]) &&
                        !ordered.matches(lines[i]) &&
                        !(isTableLine(lines[i]) && i + 1 < lines.size && isAlignLine(lines[i + 1]))
                    ) {
                        para.add(lines[i])
                        i++
                    }
                    blocks.add(MdBlock.Paragraph(para.joinToString(" ")))
                }

                else -> i++
            }
        }

        return blocks
    }
}
