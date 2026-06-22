package com.andmx.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlExtractorTest {

    private val html = """
        <html><head><title>Hello &amp; World</title>
        <style>.x{color:red}</style></head>
        <body>
          <h1>Heading</h1>
          <p>First&nbsp;paragraph with <b>bold</b>.</p>
          <script>var x = 1 < 2;</script>
          <p>Second line &#65;.</p>
        </body></html>
    """.trimIndent()

    @Test
    fun extractsTitle() {
        assertEquals("Hello & World", HtmlExtractor.title(html))
    }

    @Test
    fun stripsTagsScriptsAndStyles() {
        val text = HtmlExtractor.toText(html)
        assertTrue(text.contains("Heading"))
        assertTrue(text.contains("First paragraph with"))
        assertTrue(text.contains("bold"))
        assertTrue(text.contains("Second line A"))
        assertFalse("script content must be removed", text.contains("var x"))
        assertFalse("style content must be removed", text.contains("color:red"))
        assertFalse("no angle-bracket tags remain", text.contains("<"))
    }
}
