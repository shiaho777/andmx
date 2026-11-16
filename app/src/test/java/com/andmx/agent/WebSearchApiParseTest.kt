package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchApiParseTest {

    @Test
    fun tavilyJsonMapsToUnifiedResults() {
        val payload = """
            {"query":"kotlin","results":[
              {"title":"Kotlin docs","url":"https://kotlinlang.org/docs","content":"Official docs","score":0.9},
              {"title":"Blog","url":"https://blog.example.com/k","content":"A post"}
            ]}
        """.trimIndent()
        val results = WebSearchApis.parse("tavily", payload)
        assertEquals(2, results.size)
        assertEquals(WebSearch.Result("Kotlin docs", "https://kotlinlang.org/docs", "Official docs"), results[0])
        assertEquals("A post", results[1].snippet)
    }

    @Test
    fun braveJsonMapsToUnifiedResults() {
        val payload = """
            {"web":{"results":[
              {"title":"Brave hit","url":"https://example.com/b","description":"desc b"},
              {"title":"No url","description":"skip me"}
            ]}}
        """.trimIndent()
        val results = WebSearchApis.parse("brave", payload)
        assertEquals(1, results.size)
        assertEquals(WebSearch.Result("Brave hit", "https://example.com/b", "desc b"), results[0])
    }

    @Test
    fun bingJsonMapsToUnifiedResults() {
        val payload = """
            {"webPages":{"value":[
              {"name":"Bing hit","url":"https://example.com/g","snippet":"desc g"}
            ]}}
        """.trimIndent()
        val results = WebSearchApis.parse("bing", payload)
        assertEquals(listOf(WebSearch.Result("Bing hit", "https://example.com/g", "desc g")), results)
    }

    @Test
    fun malformedOrUnknownPayloadsReturnEmpty() {
        assertTrue(WebSearchApis.parse("tavily", "not json").isEmpty())
        assertTrue(WebSearchApis.parse("tavily", "{}").isEmpty())
        assertTrue(WebSearchApis.parse("brave", """{"web":{}}""").isEmpty())
        assertTrue(WebSearchApis.parse("unknown", """{"results":[]}""").isEmpty())
    }

    @Test
    fun normalizeDedupesCaseInsensitivelyAndCaps() {
        val results = listOf(
            WebSearch.Result(" A ", "https://Example.com/x", " s1 "),
            WebSearch.Result("dup", "https://example.com/x", "s2"),
            WebSearch.Result("blank", " ", "s3"),
            WebSearch.Result("B", "https://example.com/y", "s4"),
        )
        val normalized = WebSearchApis.normalize(results, 5)
        assertEquals(2, normalized.size)
        assertEquals(WebSearch.Result("A", "https://Example.com/x", "s1"), normalized[0])
        assertEquals(WebSearchApis.normalize(results, 1).size, 1)
    }

    @Test
    fun requestForBuildsProviderSpecificRequests() {
        val config = WebSearchApiConfig("tavily", "tv-key")
        val tavily = WebSearchApis.requestFor("tavily", config, "kotlin coroutines", 5)!!
        assertEquals("https://api.tavily.com/search", tavily.url)
        assertEquals("POST", tavily.method)
        assertTrue(tavily.body!!.contains("\"api_key\":\"tv-key\""))
        assertTrue(tavily.body!!.contains("\"max_results\":5"))

        val brave = WebSearchApis.requestFor("brave", WebSearchApiConfig("brave", "br-key"), "a b", 7)!!
        assertEquals("GET", brave.method)
        assertEquals("br-key", brave.headers["X-Subscription-Token"])
        assertTrue(brave.url.contains("q=a+b"))
        assertTrue(brave.url.contains("count=7"))
        assertNull(brave.body)

        val bing = WebSearchApis.requestFor("bing", WebSearchApiConfig("bing", "bi-key"), "q", 3)!!
        assertEquals("bi-key", bing.headers["Ocp-Apim-Subscription-Key"])
        assertNull(WebSearchApis.requestFor("altavista", config, "q", 3))
    }

    @Test
    fun endpointForUnknownProviderIsNull() {
        assertNull(WebSearchApis.endpointFor("", "q", 5))
        assertNull(WebSearchApis.endpointFor("google", "q", 5))
    }

    @Test
    fun ddgFallbackParsesBlockLayout() {
        val html = """
            <div class="result results_links">
              <h2 class="result__title"><a href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fblock.example.com%2Fa&rut=z">Block <b>Title</b></a></h2>
              <div class="result__snippet">Block snippet text</div>
            </div>
        """.trimIndent()
        val results = WebSearch.parse(html)
        assertEquals(1, results.size)
        assertEquals("Block Title", results[0].title)
        assertEquals("https://block.example.com/a", results[0].url)
        assertEquals("Block snippet text", results[0].snippet)
    }

    @Test
    fun ddgGenericFallbackDedupesRedirectAnchors() {
        val html = """
            <a href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fdup.example.com%2Fp">Generic One</a>
            <a href="https://duckduckgo.com/l/?uddg=https%3A%2F%2Fdup.example.com%2Fp&rut=q">Generic Again</a>
            <a href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fother.example.com%2Fp">Generic Two</a>
        """.trimIndent()
        val results = WebSearch.parse(html)
        assertEquals(2, results.size)
        assertEquals("https://dup.example.com/p", results[0].url)
        assertEquals("https://other.example.com/p", results[1].url)
    }

    @Test
    fun ddgPrimaryParserStillWins() {
        val html = """
            <a class="result__a" href="https://primary.example.com/x">Primary</a>
            <a href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fgeneric.example.com%2Fx">Generic</a>
        """.trimIndent()
        val results = WebSearch.parse(html)
        assertEquals(1, results.size)
        assertEquals("https://primary.example.com/x", results[0].url)
    }
}