package com.andmx.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSearchParsingTest {
    @Test
    fun clampCountDefaultsAndLimits() {
        assertEquals(5, ImageSearchParsing.clampCount(null))
        assertEquals(1, ImageSearchParsing.clampCount(0))
        assertEquals(1, ImageSearchParsing.clampCount(-3))
        assertEquals(20, ImageSearchParsing.clampCount(50))
        assertEquals(8, ImageSearchParsing.clampCount(8))
    }

    @Test
    fun safeSearchParamNormalizes() {
        assertEquals("moderate", ImageSearchParsing.safeSearchParam(null))
        assertEquals("moderate", ImageSearchParsing.safeSearchParam("bogus"))
        assertEquals("strict", ImageSearchParsing.safeSearchParam(" STRICT "))
        assertEquals("off", ImageSearchParsing.safeSearchParam("off"))
    }

    @Test
    fun requestForBuildsProviderRequests() {
        val config = ImageSearchApiConfig("bing", "key123")
        val bing = ImageSearchParsing.requestFor("bing", config, "red panda", 7, "strict")!!
        assertTrue(bing.url.startsWith("https://api.bing.microsoft.com/v7.0/images/search"))
        assertTrue(bing.url.contains("count=7"))
        assertTrue(bing.url.contains("safeSearch=Strict"))
        assertEquals("key123", bing.headers["Ocp-Apim-Subscription-Key"])
        val brave = ImageSearchParsing.requestFor("brave", ImageSearchApiConfig("brave", "tok"), "cats", 5, "off")!!
        assertTrue(brave.url.startsWith("https://api.search.brave.com/res/v1/images/search"))
        assertTrue(brave.url.contains("safeSearch=off"))
        assertEquals("tok", brave.headers["X-Subscription-Token"])
        assertNull(ImageSearchParsing.requestFor("tavily", config, "x", 5, "moderate"))
        assertNull(ImageSearchParsing.requestFor("bing", ImageSearchApiConfig("bing", ""), "x", 5, "moderate"))
    }

    @Test
    fun parseBingImagesPayload() {
        val payload = """
            {"value":[
              {"name":"Panda","contentUrl":"https://img.example.com/p.jpg","thumbnailUrl":"https://t.example.com/p.jpg",
               "hostPageUrl":"https://example.com/pandas","width":800,"height":600},
              {"name":"No URL","contentUrl":"","width":10}
            ]}
        """.trimIndent()
        val results = ImageSearchParsing.parseApi("bing", payload)
        assertEquals(1, results.size)
        assertEquals("Panda", results[0].title)
        assertEquals("https://img.example.com/p.jpg", results[0].imageUrl)
        assertEquals("https://t.example.com/p.jpg", results[0].thumbnailUrl)
        assertEquals("https://example.com/pandas", results[0].sourceUrl)
        assertEquals(800, results[0].width)
        assertEquals(600, results[0].height)
    }

    @Test
    fun parseBraveImagesPayload() {
        val payload = """
            {"results":[
              {"title":"Cat","url":"https://example.com/cats","thumbnail":{"src":"https://t.example.com/c.jpg"},
               "properties":{"url":"https://img.example.com/c.jpg","width":1024,"height":768}}
            ]}
        """.trimIndent()
        val results = ImageSearchParsing.parseApi("brave", payload)
        assertEquals(1, results.size)
        assertEquals("https://img.example.com/c.jpg", results[0].imageUrl)
        assertEquals("https://t.example.com/c.jpg", results[0].thumbnailUrl)
        assertEquals("https://example.com/cats", results[0].sourceUrl)
        assertEquals(1024, results[0].width)
        assertEquals(768, results[0].height)
    }

    @Test
    fun parseApiToleratesGarbage() {
        assertTrue(ImageSearchParsing.parseApi("bing", "not json").isEmpty())
        assertTrue(ImageSearchParsing.parseApi("bing", "{}").isEmpty())
        assertTrue(ImageSearchParsing.parseApi("brave", """{"results":"nope"}""").isEmpty())
        assertTrue(ImageSearchParsing.parseApi("unknown", """{"value":[]}""").isEmpty())
    }

    @Test
    fun extractVqdHandlesVariants() {
        assertEquals("1234-5678", ImageSearchParsing.extractVqd("""<script>vqd="1234-5678";</script>"""))
        assertEquals("42-99", ImageSearchParsing.extractVqd("stuff vqd=42-99&other=1"))
        assertNull(ImageSearchParsing.extractVqd("<html>no token</html>"))
    }

    @Test
    fun parseDdgJsonResults() {
        val payload = """
            {"results":[
              {"title":"Fox","image":"https://img.example.com/f.jpg","thumbnail":"https://t.example.com/f.jpg",
               "url":"https://example.com/fox","width":640,"height":480},
              {"title":"bad","image":"ftp://nope"}
            ]}
        """.trimIndent()
        val parsed = ImageSearchParsing.parseDdgJson(payload)
        assertEquals(2, parsed.size)
        val normalized = ImageSearchParsing.normalize(parsed, 10)
        assertEquals(1, normalized.size)
        assertEquals("Fox", normalized[0].title)
        assertEquals("https://img.example.com/f.jpg", normalized[0].imageUrl)
    }

    @Test
    fun parseDdgHtmlTiles() {
        val html = """
            <div class="tile-wrap">
            <a class="tile  tile--img" href="/l/?kh=-1&uddg=x&iurl=https%3A%2F%2Fimg.example.com%2Fa.jpg&rurl=https%3A%2F%2Fexample.com%2Fa&t=Alpha&iw=100&ih=200&purl=https%3A%2F%2Ft.example.com%2Fa.jpg">img</a>
            <a class="tile  tile--img" href="/l/?iurl=not-a-url">img</a>
            </div>
        """.trimIndent()
        val results = ImageSearchParsing.normalize(ImageSearchParsing.parseDdgHtml(html), 10)
        assertEquals(1, results.size)
        assertEquals("https://img.example.com/a.jpg", results[0].imageUrl)
        assertEquals("Alpha", results[0].title)
        assertEquals(100, results[0].width)
        assertEquals(200, results[0].height)
        assertEquals("https://example.com/a", results[0].sourceUrl)
    }

    @Test
    fun normalizeDeduplicatesAndCaps() {
        val list = listOf(
            ImageSearchResult("A", "https://x.example.com/1.jpg"),
            ImageSearchResult("dup", "https://X.example.com/1.jpg"),
            ImageSearchResult("B", "https://x.example.com/2.jpg"),
            ImageSearchResult("C", "https://x.example.com/3.jpg"),
        )
        val normalized = ImageSearchParsing.normalize(list, 2)
        assertEquals(2, normalized.size)
        assertEquals("A", normalized[0].title)
        assertEquals("B", normalized[1].title)
    }

    @Test
    fun toJsonKeepsNullableFields() {
        val json = Json.parseToJsonElement(
            ImageSearchParsing.toJson(
                "panda",
                listOf(ImageSearchResult("P", "https://x.example.com/p.jpg")),
            ),
        ).jsonObject
        assertEquals("panda", json["query"]!!.jsonPrimitive.content)
        assertEquals("1", json["count"]!!.jsonPrimitive.content)
        val first = json["results"]!!.jsonArray[0].jsonObject
        assertEquals("P", first["title"]!!.jsonPrimitive.content)
        assertEquals("https://x.example.com/p.jpg", first["imageUrl"]!!.jsonPrimitive.content)
        assertTrue(first.getValue("thumbnailUrl").toString() == "null")
        assertTrue(first.getValue("sourceUrl").toString() == "null")
        assertTrue(first.getValue("width").toString() == "null")
        assertTrue(first.getValue("height").toString() == "null")
    }
}
