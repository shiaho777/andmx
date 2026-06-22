package com.andmx.ui.markdown

/**
 * A small Markdown block parser (no external deps). Splits text into blocks the
 * renderer can lay out; inline spans (bold/italic/code/link) are parsed
 * separately by [MarkdownInline]. Covers the subset LLM answers actually use.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val items: List<String>) : MdBlock
    data class Ordered(val items: List<String>) : MdBlock
    data class Code(val lang: String?, val code: String) : MdBlock
    data class Quote(val text: String) : MdBlock
}

object MarkdownParser {

    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val bullet = Regex("^\\s*[-*+]\\s+(.*)$")
    private val ordered = Regex("^\\s*\\d+[.)]\\s+(.*)$")
    private val quote = Regex("^>\\s?(.*)$")

    fun parse(md: String): List<MdBlock> {
        val lines = md.replace("\r\n", "\n").split("\n")
        val blocks = ArrayList<MdBlock>()
        var i = 0
        val para = StringBuilder()

        fun flushPara() {
            if (para.isNotBlank()) blocks.add(MdBlock.Paragraph(para.toString().trim()))
            para.setLength(0)
        }

        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trimStart().startsWith("```") -> {
                    flushPara()
                    val lang = line.trimStart().removePrefix("```").trim().ifBlank { null }
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code.appendLine(lines[i]); i++ }
                    blocks.add(MdBlock.Code(lang, code.toString().trimEnd('\n')))
                }
                heading.matches(line) -> {
                    flushPara()
                    val m = heading.find(line)!!
                    blocks.add(MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim()))
                }
                bullet.matches(line) -> {
                    flushPara()
                    val items = ArrayList<String>()
                    while (i < lines.size && bullet.matches(lines[i])) { items.add(bullet.find(lines[i])!!.groupValues[1]); i++ }
                    blocks.add(MdBlock.Bullet(items)); continue
                }
                ordered.matches(line) -> {
                    flushPara()
                    val items = ArrayList<String>()
                    while (i < lines.size && ordered.matches(lines[i])) { items.add(ordered.find(lines[i])!!.groupValues[1]); i++ }
                    blocks.add(MdBlock.Ordered(items)); continue
                }
                quote.matches(line) -> {
                    flushPara()
                    val q = StringBuilder()
                    while (i < lines.size && quote.matches(lines[i])) { q.appendLine(quote.find(lines[i])!!.groupValues[1]); i++ }
                    blocks.add(MdBlock.Quote(q.toString().trim())); continue
                }
                line.isBlank() -> flushPara()
                else -> para.appendLine(line)
            }
            i++
        }
        flushPara()
        return blocks
    }
}
