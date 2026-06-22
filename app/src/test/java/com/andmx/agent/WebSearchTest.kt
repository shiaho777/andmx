package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchTest {

    @Test
    fun parsesResultsAndDecodesLinks() {
        val html = """
            <div class="result">
              <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpage&rut=x">Example <b>Title</b></a>
              <a class="result__snippet" href="#">A nice <b>snippet</b> here.</a>
            </div>
        """.trimIndent()
        val results = WebSearch.parse(html)
        assertEquals(1, results.size)
        assertEquals("Example Title", results[0].title)
        assertEquals("https://example.com/page", results[0].url)
        assertTrue(results[0].snippet.contains("snippet"))
    }

    @Test
    fun emptyHtmlNoResults() {
        assertTrue(WebSearch.parse("<html></html>").isEmpty())
    }
}
