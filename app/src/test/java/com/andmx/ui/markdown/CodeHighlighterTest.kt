package com.andmx.ui.markdown

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class CodeHighlighterTest {

    @Test
    fun preservesAllText() {
        val code = "fun main() {\n  val x = \"hi\" // comment\n  println(x)\n}"
        val out = CodeHighlighter.highlight(code, Color.Black)
        // highlighting must never drop or reorder characters
        assertEquals(code, out.text)
    }

    @Test
    fun handlesEmpty() {
        assertEquals("", CodeHighlighter.highlight("", Color.Black).text)
    }
}
