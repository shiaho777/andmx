package com.andmx.ui2.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHighlightTest {

    private val theme = CodeThemes.GithubDark

    private fun stylesOf(code: String) = CodeHighlight.highlight(code, theme).spanStyles

    @Test
    fun preservesAllText() {
        val code = "fun main() {\n  val x = \"hi\" // note\n  println(x)\n}"
        assertEquals(code, CodeHighlight.highlight(code, theme).text)
    }

    @Test
    fun handlesEmpty() {
        assertEquals("", CodeHighlight.highlight("", theme).text)
    }

    @Test
    fun keywordsAreStyled() {
        val spans = stylesOf("val x = 1")
        val keywordSpan = spans.firstOrNull { it.item.color == theme.keyword }
            ?: error("keyword span missing")
        assertEquals(0, keywordSpan.start)
        assertEquals(3, keywordSpan.end)
    }

    @Test
    fun stringsAreStyled() {
        val spans = stylesOf("val s = \"hello\"")
        val stringSpan = spans.firstOrNull { it.item.color == theme.string }
            ?: error("string span missing")
        assertEquals(8, stringSpan.start)
        assertEquals(15, stringSpan.end)
    }

    @Test
    fun commentsAreStyled() {
        val spans = stylesOf("x = 1 // note")
        val commentSpan = spans.firstOrNull { it.item.color == theme.comment }
            ?: error("comment span missing")
        assertEquals(6, commentSpan.start)
        assertEquals(13, commentSpan.end)
    }

    @Test
    fun numbersAreStyled() {
        val spans = stylesOf("y = 3.14")
        val numberSpan = spans.firstOrNull { it.item.color == theme.number }
            ?: error("number span missing")
        assertEquals(4, numberSpan.start)
        assertEquals(8, numberSpan.end)
    }

    @Test
    fun functionCallsAreStyledButNotKeywords() {
        val spans = stylesOf("println(x)")
        val fnSpan = spans.firstOrNull { it.item.color == theme.function }
            ?: error("function span missing")
        assertEquals(0, fnSpan.start)
        assertEquals(7, fnSpan.end)
    }

    @Test
    fun hashCommentsAreStyled() {
        val spans = stylesOf("# python note\ndef f(): pass")
        assertTrue(spans.any { it.item.color == theme.comment })
        assertTrue(spans.any { it.item.color == theme.keyword })
    }

    @Test
    fun allBundledThemesPreserveText() {
        val code = "suspend fun f(a: Int): String = \"v=\$a\" /* tail */"
        for (t in CodeThemes.all) {
            assertEquals(code, CodeHighlight.highlight(code, t).text)
        }
    }
}
