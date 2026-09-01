package com.andmx.ui2.markdown

sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class Code(
        val lang: String,
        val code: String,
        /** 流式解析时尚未收到收尾围栏的块。 */
        val unclosed: Boolean = false,
        /** [unclosed] 时记录的开围栏行（含缩进），供增量状态重放。 */
        val openingLine: String? = null,
        /** [unclosed] 时内容在完整源码中的起点偏移；非流式为 -1。 */
        val contentStartOffset: Int = -1,
    ) : MdBlock()
    data class List(val ordered: Boolean, val items: kotlin.collections.List<String>) : MdBlock()
    data class Quote(val text: String) : MdBlock()

    /** GFM 管道表格（ZCode markdownTable 对齐）。 */
    data class Table(val header: kotlin.collections.List<String>, val rows: kotlin.collections.List<kotlin.collections.List<String>>) : MdBlock()
}

/** 一个顶层块及其源码偏移，供增量解析冻结使用。 */
data class PositionedParsedBlock(
    val block: MdBlock,
    /** 块首行在输入中的起始偏移。 */
    val startOffset: Int,
    /** 块在输入中的结束偏移（下一块行首）；未闭合块为 -1。 */
    val endOffset: Int,
)

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

    fun parse(markdown: String): List<MdBlock> = parseWithOffsets(markdown).map { it.block }

    /**
     * 块解析并附带源码偏移。[PositionedParsedBlock.endOffset] 指向块结束后
     * 第一个字符（通常为下一块行首），未闭合代码围栏为 -1。
     */
    fun parseWithOffsets(markdown: String): List<PositionedParsedBlock> {
        val blocks = mutableListOf<PositionedParsedBlock>()
        var lineStart = 0
        val len = markdown.length

        while (lineStart < len) {
            val nl = markdown.indexOf('\n', lineStart)
            val lineEnd = if (nl >= 0) nl + 1 else len
            val line = markdown.substring(lineStart, lineEnd).trimEnd('\r', '\n')
            val lineLen = lineEnd - lineStart

            when {
                line.startsWith("```") -> {
                    val lang = line.substring(3).trim()
                    val code = StringBuilder()
                    var i = lineEnd
                    var closed = false
                    while (i < len) {
                        val nl2 = markdown.indexOf('\n', i)
                        val e2 = if (nl2 >= 0) nl2 + 1 else len
                        val l2 = markdown.substring(i, e2).trimEnd('\r', '\n')
                        if (l2.startsWith("```")) { closed = true; i = e2; break }
                        code.append(markdown, i, e2)
                        i = e2
                    }
                    val codeText = code.toString().removeSuffix("\r\n").removeSuffix("\n").removeSuffix("\r")
                    if (closed) {
                        blocks.add(
                            PositionedParsedBlock(MdBlock.Code(lang, codeText), lineStart, i),
                        )
                        lineStart = i
                    } else {
                        // 流式：未闭合围栏保持独立块，其内容起点供增量边界使用。
                        blocks.add(
                            PositionedParsedBlock(
                                MdBlock.Code(
                                    lang = lang,
                                    code = codeText,
                                    unclosed = true,
                                    openingLine = line,
                                    contentStartOffset = lineEnd,
                                ),
                                lineStart,
                                -1,
                            ),
                        )
                        lineStart = len
                    }
                }

                heading.matches(line) -> {
                    val m = heading.find(line)!!
                    blocks.add(
                        PositionedParsedBlock(
                            MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim()),
                            lineStart,
                            lineEnd,
                        ),
                    )
                    lineStart = lineEnd
                }

                line.startsWith(">") -> {
                    val text = line.substring(1).trim()
                    blocks.add(PositionedParsedBlock(MdBlock.Quote(text), lineStart, lineEnd))
                    lineStart = lineEnd
                }

                // GFM 表格：当前行是管道行，下一行是对齐行（---|---）
                isTableLine(line) && lineStart + lineLen < len &&
                    isAlignLine(lineAt(markdown, lineStart + lineLen)) -> {
                    val header = splitTableRow(line)
                    var i2 = lineEndOf(markdown, lineStart + lineLen)
                    val rows = mutableListOf<List<String>>()
                    while (i2 < len) {
                        val l = lineAt(markdown, i2)
                        if (!isTableLine(l)) break
                        val cells = splitTableRow(l).toMutableList()
                        while (cells.size < header.size) cells.add("")
                        rows.add(cells.take(header.size))
                        i2 = lineEndOf(markdown, i2)
                    }
                    blocks.add(PositionedParsedBlock(MdBlock.Table(header, rows), lineStart, i2))
                    lineStart = i2
                }

                unordered.matches(line) -> {
                    val items = mutableListOf<String>()
                    var i = lineStart
                    while (i < len && unordered.matches(lineAt(markdown, i))) {
                        items.add(unordered.find(lineAt(markdown, i))!!.groupValues[1].trim())
                        i = lineEndOf(markdown, i)
                    }
                    blocks.add(PositionedParsedBlock(MdBlock.List(false, items), lineStart, i))
                    lineStart = i
                }

                ordered.matches(line) -> {
                    val items = mutableListOf<String>()
                    var i = lineStart
                    while (i < len && ordered.matches(lineAt(markdown, i))) {
                        items.add(ordered.find(lineAt(markdown, i))!!.groupValues[1].trim())
                        i = lineEndOf(markdown, i)
                    }
                    blocks.add(PositionedParsedBlock(MdBlock.List(true, items), lineStart, i))
                    lineStart = i
                }

                line.isNotBlank() -> {
                    val para = StringBuilder(line)
                    var i = lineEnd
                    while (i < len) {
                        val l = lineAt(markdown, i)
                        val e = lineEndOf(markdown, i)
                        if (
                            l.isBlank() ||
                            heading.matches(l) ||
                            l.startsWith(">") ||
                            l.startsWith("```") ||
                            unordered.matches(l) ||
                            ordered.matches(l) ||
                            (isTableLine(l) && e < len && isAlignLine(lineAt(markdown, e)))
                        ) break
                        para.append(' ').append(l)
                        i = e
                    }
                    blocks.add(
                        PositionedParsedBlock(MdBlock.Paragraph(para.toString()), lineStart, i),
                    )
                    lineStart = i
                }

                else -> lineStart = lineEnd
            }
        }

        return blocks
    }

    private fun lineAt(text: String, start: Int): String {
        val nl = text.indexOf('\n', start)
        val end = if (nl >= 0) nl else text.length
        return text.substring(start, end).trimEnd('\r')
    }

    private fun lineEndOf(text: String, start: Int): Int {
        val nl = text.indexOf('\n', start)
        return if (nl >= 0) nl + 1 else text.length
    }
}
