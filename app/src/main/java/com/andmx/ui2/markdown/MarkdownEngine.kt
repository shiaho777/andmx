package com.andmx.ui2.markdown

sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class Code(val lang: String, val code: String) : MdBlock()
    data class List(val ordered: Boolean, val items: kotlin.collections.List<String>) : MdBlock()
    data class Quote(val text: String) : MdBlock()
}

object MarkdownEngine {
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
                    blocks.add(MdBlock.Code(lang, code.joinToString("\n")))
                    i++
                }
                
                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length
                    val text = line.substring(level).trim()
                    blocks.add(MdBlock.Heading(level.coerceIn(1, 6), text))
                    i++
                }
                
                line.startsWith(">") -> {
                    val text = line.substring(1).trim()
                    blocks.add(MdBlock.Quote(text))
                    i++
                }
                
                line.matches(Regex("^[*-]\\s+.*")) -> {
                    val items = mutableListOf<String>()
                    while (i < lines.size && lines[i].matches(Regex("^[*-]\\s+.*"))) {
                        items.add(lines[i].substring(2).trim())
                        i++
                    }
                    blocks.add(MdBlock.List(false, items))
                }
                
                line.matches(Regex("^\\d+\\.\\s+.*")) -> {
                    val items = mutableListOf<String>()
                    while (i < lines.size && lines[i].matches(Regex("^\\d+\\.\\s+.*"))) {
                        items.add(lines[i].substringAfter('.').trim())
                        i++
                    }
                    blocks.add(MdBlock.List(true, items))
                }
                
                line.isNotBlank() -> {
                    val para = mutableListOf(line)
                    i++
                    while (i < lines.size && lines[i].isNotBlank() && 
                           !lines[i].startsWith("#") && 
                           !lines[i].startsWith(">") &&
                           !lines[i].startsWith("```") &&
                           !lines[i].matches(Regex("^[*-]\\s+.*")) &&
                           !lines[i].matches(Regex("^\\d+\\.\\s+.*"))) {
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
