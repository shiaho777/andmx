package com.andmx.ui2.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownMathTest {

    @Test
    fun singleLineDisplayMath() {
        val blocks = MarkdownEngine.parse("\$\$x^2 + y^2 = z^2\$\$")
        assertEquals(listOf(MdBlock.Math("x^2 + y^2 = z^2", display = true)), blocks)
    }

    @Test
    fun multiLineDisplayMath() {
        val blocks = MarkdownEngine.parse("$$\n\\begin{aligned}\na &= b \\\\\nc &= d\n\\end{aligned}\n$$")
        assertEquals(
            listOf(MdBlock.Math("\\begin{aligned}\na &= b \\\\\nc &= d\n\\end{aligned}", display = true)),
            blocks,
        )
    }

    @Test
    fun contentOnOpeningAndClosingLines() {
        val blocks = MarkdownEngine.parse("\$\$a + b\n+ c\$\$")
        assertEquals(listOf(MdBlock.Math("a + b\n+ c", display = true)), blocks)
    }

    @Test
    fun nestedSingleDollarKeptInTex() {
        val blocks = MarkdownEngine.parse("$$\n\\text{cost \$5 and \$6}\n$$")
        assertEquals(listOf(MdBlock.Math("\\text{cost \$5 and \$6}", display = true)), blocks)
    }

    @Test
    fun unclosedMathFallsBackToParagraph() {
        val blocks = MarkdownEngine.parse("$$\nx + y\nmore text")
        assertEquals(listOf(MdBlock.Paragraph("$$ x + y more text")), blocks)
    }

    @Test
    fun trailingTextAfterClosingMarkerFallsBackToParagraph() {
        val blocks = MarkdownEngine.parse("\$\$x\$\$ trailing")
        assertEquals(listOf(MdBlock.Paragraph("\$\$x\$\$ trailing")), blocks)
    }

    @Test
    fun emptyMathFallsBackToParagraph() {
        val blocks = MarkdownEngine.parse("$$$$")
        assertEquals(listOf(MdBlock.Paragraph("$$$$")), blocks)
    }

    @Test
    fun inlineDollarStaysParagraph() {
        val blocks = MarkdownEngine.parse("price $5 and $6 total")
        assertEquals(listOf(MdBlock.Paragraph("price $5 and $6 total")), blocks)
    }

    @Test
    fun mathBetweenParagraphs() {
        val blocks = MarkdownEngine.parse("before\n$$\nx = 1\n$$\nafter")
        assertEquals(
            listOf(
                MdBlock.Paragraph("before"),
                MdBlock.Math("x = 1", display = true),
                MdBlock.Paragraph("after"),
            ),
            blocks,
        )
    }

    @Test
    fun mathDoesNotStartInsideCodeFence() {
        val blocks = MarkdownEngine.parse("```\n$$\nx^2\n$$\n```")
        assertEquals(listOf(MdBlock.Code(lang = "", code = "$$\nx^2\n$$")), blocks)
    }

    @Test
    fun codeFenceAfterUnclosedMathStaysCode() {
        val blocks = MarkdownEngine.parse("$$\nx + y\n```\ncode\n```")
        assertEquals(
            listOf(
                MdBlock.Paragraph("$$ x + y"),
                MdBlock.Code(lang = "", code = "code"),
            ),
            blocks,
        )
    }

    @Test
    fun mathThenCodeFenceCoexist() {
        val blocks = MarkdownEngine.parse("\$\$e = mc^2\$\$\n```kotlin\nval x = 1\n```")
        assertEquals(
            listOf(
                MdBlock.Math("e = mc^2", display = true),
                MdBlock.Code(lang = "kotlin", code = "val x = 1"),
            ),
            blocks,
        )
    }

    @Test
    fun offsetsCoverWholeMathBlock() {
        val src = "intro\n\n$$\na = b\n$$\noutro"
        val positioned = MarkdownEngine.parseWithOffsets(src)
        val math = positioned.first { it.block is MdBlock.Math }
        assertEquals(src.indexOf("$$"), math.startOffset)
        assertEquals(src.indexOf("outro"), math.endOffset)
        assertTrue(math.block is MdBlock.Math)
    }
}