package com.andmx.ui2.markdown

sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class Code(val lang: String, val code: String) : MdBlock()
    data class List(val ordered: Boolean, val items: kotlin.collections.List<String>) : MdBlock()
    data class Quote(val text: String) : MdBlock()
}

object MarkdownEngine {
    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val unordered = Regex("^\\s*[-*+]\\s+(.*)$")
    private val ordered = Regex("^\\s*\\d+[.)]\\s+(.*)$")

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
                        !ordered.matches(lines[i])
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
