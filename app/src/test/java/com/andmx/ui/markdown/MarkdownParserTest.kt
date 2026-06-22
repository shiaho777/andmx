package com.andmx.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun parsesHeadingsListsAndCode() {
        val md = """
            # Title
            intro paragraph

            - one
            - two

            ```kotlin
            val x = 1
            ```
            > a quote
        """.trimIndent()
        val blocks = MarkdownParser.parse(md)

        assertTrue(blocks[0] is MdBlock.Heading)
        assertEquals(1, (blocks[0] as MdBlock.Heading).level)
        assertTrue(blocks.any { it is MdBlock.Paragraph })
        val bullet = blocks.filterIsInstance<MdBlock.Bullet>().first()
        assertEquals(listOf("one", "two"), bullet.items)
        val code = blocks.filterIsInstance<MdBlock.Code>().first()
        assertEquals("kotlin", code.lang)
        assertEquals("val x = 1", code.code)
        assertTrue(blocks.any { it is MdBlock.Quote })
    }

    @Test
    fun orderedList() {
        val blocks = MarkdownParser.parse("1. a\n2. b\n3. c")
        val o = blocks.filterIsInstance<MdBlock.Ordered>().first()
        assertEquals(listOf("a", "b", "c"), o.items)
    }

    @Test
    fun inlineStylesProducePlainText() {
        // bold/italic/code/link markers should be stripped from visible text
        val s = MarkdownInline.parse("**bold** and `code` and [link](http://x) and *it*").text
        assertEquals("bold and code and link and it", s)
    }
}
