package com.andmx.ui2.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.andmx.ui2.markdown.InlineParser.UrlAnnotationTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineParserTest {

    private fun spans(text: String) = InlineParser.parse(text, Color.Black).spanStyles

    @Test
    fun plainTextUnchanged() {
        assertEquals("hello world", InlineParser.parse("hello world", Color.Black).text)
    }

    @Test
    fun boldSpan() {
        val out = InlineParser.parse("a **b** c", Color.Black)
        assertEquals("a b c", out.text)
        val bold = out.spanStyles.single()
        assertEquals(FontWeight.Bold, bold.item.fontWeight)
        assertEquals(2, bold.start)
        assertEquals(3, bold.end)
    }

    @Test
    fun italicSpan() {
        val out = InlineParser.parse("*i*", Color.Black)
        assertEquals("i", out.text)
        assertTrue(out.spanStyles.single().item.fontStyle != null)
    }

    @Test
    fun codeSpan() {
        val out = InlineParser.parse("x `y` z", Color.Black)
        assertEquals("x y z", out.text)
        val code = out.spanStyles.single()
        assertEquals(androidx.compose.ui.text.font.FontFamily.Monospace, code.item.fontFamily)
    }

    @Test
    fun linkSpan() {
        val out = InlineParser.parse("[label](https://example.com)", Color.Black)
        assertEquals("label", out.text)
        assertEquals(TextDecoration.Underline, out.spanStyles.single().item.textDecoration)
        assertEquals("https://example.com", out.getStringAnnotations(UrlAnnotationTag, 0, out.length).single().item)
    }

    @Test
    fun linkWithParensInUrl() {
        val out = InlineParser.parse("[wiki](https://en.wikipedia.org/wiki/A_(b))", Color.Black)
        assertEquals("wiki", out.text)
    }

    @Test
    fun linkWithNestedParensAndTrailer() {
        val out = InlineParser.parse("[w](http://x.org/a_(b)_c) tail", Color.Black)
        assertEquals("w tail", out.text)
    }

    @Test
    fun unbalancedLinkParensStayLiteral() {
        val out = InlineParser.parse("[w](http://x.org/a_(b", Color.Black)
        assertEquals("[w](http://x.org/a_(b", out.text)
    }

    @Test
    fun unclosedMarkersRemainLiteral() {
        for (src in listOf("**abc", "`abc", "[abc", "*abc")) {
            assertEquals(src, InlineParser.parse(src, Color.Black).text)
        }
    }

    @Test
    fun emptyInput() {
        assertEquals("", InlineParser.parse("", Color.Black).text)
        assertNull(InlineParser.parse("", Color.Black).spanStyles.firstOrNull())
    }

    @Test
    fun underscoreBoldSpan() {
        val out = InlineParser.parse("a __b__ c", Color.Black)
        assertEquals("a b c", out.text)
        assertEquals(FontWeight.Bold, out.spanStyles.single().item.fontWeight)
    }

    @Test
    fun strikethroughSpan() {
        val out = InlineParser.parse("a ~~b~~ c", Color.Black)
        assertEquals("a b c", out.text)
        assertEquals(TextDecoration.LineThrough, out.spanStyles.single().item.textDecoration)
    }

    @Test
    fun imageRendersAltWithUrlAnnotation() {
        val out = InlineParser.parse("![logo](https://example.com/a.png)", Color.Black)
        assertEquals("logo", out.text)
        assertEquals("https://example.com/a.png", out.getStringAnnotations(UrlAnnotationTag, 0, out.length).single().item)
    }

    @Test
    fun autolinkInAngleBrackets() {
        val out = InlineParser.parse("<https://example.com/x>", Color.Black)
        assertEquals("https://example.com/x", out.text)
        assertEquals("https://example.com/x", out.getStringAnnotations(UrlAnnotationTag, 0, out.length).single().item)
    }

    @Test
    fun bareUrlBecomesLink() {
        val out = InlineParser.parse("see https://example.com/x.", Color.Black)
        assertEquals("see https://example.com/x.", out.text)
        assertEquals("https://example.com/x", out.getStringAnnotations(UrlAnnotationTag, 0, out.length).single().item)
    }

    @Test
    fun escapedMarkerStaysLiteral() {
        assertEquals("*x*", InlineParser.parse("\\*x\\*", Color.Black).text)
    }

    @Test
    fun doubleBacktickCodeSpan() {
        val out = InlineParser.parse("x ``y ` z`` w", Color.Black)
        assertEquals("x y ` z w", out.text)
    }

    @Test
    fun snakeCaseKeepsUnderscores() {
        assertEquals("some_variable_name", InlineParser.parse("some_variable_name", Color.Black).text)
    }
}
