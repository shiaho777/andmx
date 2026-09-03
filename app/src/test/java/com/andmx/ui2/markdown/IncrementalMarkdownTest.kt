package com.andmx.ui2.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalMarkdownTest {

    private fun allText(result: IncrementalMarkdown.Result): String =
        (result.frozen + result.tail).joinToString("\n") { block ->
            when (val b = block.node) {
                is MdBlock.Paragraph -> b.text
                is MdBlock.Heading -> "#" + b.text
                is MdBlock.Code -> b.code
                is MdBlock.List -> b.items.joinToString("\n")
                is MdBlock.Quote -> "> " + b.text
                is MdBlock.Table -> b.rows.joinToString("\n") { it.joinToString("|") }
            }
        }

    @Test
    fun appendOnlyStreamProducesStableKeys() {
        val parser = IncrementalMarkdown.Parser()
        val r1 = parser.update("# Title\n\nfirst paragraph ")
        val r2 = parser.update("# Title\n\nfirst paragraph continues\n\nsecond para\n")
        // 冻结块 key 跨 chunk 稳定
        val keys1 = r1.frozen.map { it.key }
        val keys2 = r2.frozen.map { it.key }
        assertTrue(keys2.take(keys1.size) == keys1)
        assertTrue(r2.frozen.size >= r1.frozen.size)
    }

    @Test
    fun frozenBlocksRemainFrozenAcrossChunks() {
        val parser = IncrementalMarkdown.Parser()
        parser.update("para one\n\npara two\n\npara three\n\n")
        val r = parser.update("para one\n\npara two\n\npara three\n\npara four tail")
        assertTrue(r.frozen.map { it.node }.filterIsInstance<MdBlock.Paragraph>()
            .any { it.text == "para one" })
    }

    @Test
    fun nonAppendInputBumpsGeneration() {
        val parser = IncrementalMarkdown.Parser()
        parser.update("original text\n\nmore")
        val r = parser.update("replaced text")
        assertTrue(r.generation > 0)
    }

    @Test
    fun unclosedFenceStreamsIncrementally() {
        val parser = IncrementalMarkdown.Parser()
        val r1 = parser.update("```kotlin\nval a = ")
        val last1 = r1.tail.last().node as MdBlock.Code
        assertTrue(last1.unclosed)
        val r2 = parser.update("```kotlin\nval a = 1\nval b = 2\nval c = ")
        val last2 = r2.tail.last().node as MdBlock.Code
        assertTrue(last2.unclosed)
        // 冻结块不因围栏增长而减少
        assertTrue(r2.frozen.size >= r1.frozen.size)
    }

    @Test
    fun closedFenceFinalizes() {
        val parser = IncrementalMarkdown.Parser()
        parser.update("```kotlin\nval a = 1\n")
        val r = parser.update("```kotlin\nval a = 1\n```\nafter text")
        val code = (r.frozen + r.tail).map { it.node }.filterIsInstance<MdBlock.Code>()
            .first { it.lang == "kotlin" }
        assertFalse(code.unclosed)
        assertEquals("val a = 1", code.code)
    }

    @Test
    fun closingFenceDetection() {
        assertTrue(IncrementalMarkdown.containsClosingFence("code\n```\n", '`', 3))
        assertTrue(IncrementalMarkdown.containsClosingFence("code\n   ```\n", '`', 3))
        assertFalse(IncrementalMarkdown.containsClosingFence("code with ``` inline", '`', 3))
        // 更长的开围栏（4 个反引号）需要 ≥4 的收尾才算闭合。
        assertTrue(IncrementalMarkdown.containsClosingFence("`````\ncode\n`````\n", '`', 5))
    }

    @Test
    fun parseWithOffsetsCoversWholeInput() {
        val src = "# H\n\ntext block\n\n- a\n- b\n"
        val blocks = MarkdownEngine.parseWithOffsets(src)
        assertTrue(blocks.isNotEmpty())
        assertEquals(0, blocks.first().startOffset)
        assertTrue(blocks.last().endOffset <= src.length)
    }

    @Test
    fun tableAndListBlocks() {
        val blocks = MarkdownEngine.parseWithOffsets("| a | b |\n|---|---|\n| 1 | 2 |\n")
        val table = blocks.first().block as MdBlock.Table
        assertEquals(listOf("a", "b"), table.header)
        assertEquals(listOf(listOf("1", "2")), table.rows)
    }
}
