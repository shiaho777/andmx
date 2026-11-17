package com.andmx.agent

import com.andmx.exec.policy.NetworkPolicy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSearchToolTest {
    private class FakeClient(private val responses: Map<String, String>) : WebSearchClient {
        val urls = mutableListOf<String>()
        override fun fetch(url: String): String {
            urls += url
            return responses.entries.firstOrNull { url.startsWith(it.key) }?.value
                ?: throw IllegalStateException("no fake response for $url")
        }
    }

    private class FakeApiClient(private val payload: String, private val fail: Boolean = false) : WebSearchApiClient {
        val urls = mutableListOf<String>()
        override fun request(url: String, method: String, headers: Map<String, String>, body: String?): String {
            urls += url
            if (fail) throw IllegalStateException("api down")
            return payload
        }
    }

    private val bingPayload = """
        {"value":[{"name":"Panda","contentUrl":"https://img.example.com/p.jpg","hostPageUrl":"https://example.com/p","width":800,"height":600}]}
    """.trimIndent()

    private fun args(query: String, count: Int? = null, safe: String? = null) = buildJsonObject {
        put("query", query)
        count?.let { put("count", it) }
        safe?.let { put("safeSearch", it) }
    }

    private fun results(output: String) =
        Json.parseToJsonElement(output).jsonObject["results"]!!.jsonArray

    @Test
    fun schemaAndCapabilities() {
        val tool = ImageSearchTool()
        assertEquals("ImageSearch", tool.name)
        assertTrue(tool.readOnly)
        assertTrue(tool.concurrentSafe)
        assertFalse(tool.needsApproval)
        assertEquals("network", tool.sideEffectScope)
        assertEquals(60_000L, tool.timeoutMs)
        val props = tool.parameters["properties"]!!.jsonObject
        assertEquals(setOf("query", "count", "safeSearch"), props.keys)
        assertEquals(listOf("query"), tool.parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun missingOrShortQueryFails() = runTest {
        val client = FakeClient(emptyMap())
        val tool = ImageSearchTool(client = client)
        val missing = tool.execute(buildJsonObject {})
        assertTrue(missing.isError)
        assertEquals("query is required", missing.output)
        val short = tool.execute(args("a"))
        assertTrue(short.isError)
        assertTrue(client.urls.isEmpty())
    }

    @Test
    fun usesConfiguredBingApi() = runTest {
        val api = FakeApiClient(bingPayload)
        val client = FakeClient(emptyMap())
        val tool = ImageSearchTool(
            client = client,
            apiConfig = { ImageSearchApiConfig("bing", "key") },
            apiClient = api,
        )
        val result = tool.execute(args("red panda", count = 3, safe = "strict"))
        assertFalse(result.isError)
        val parsed = results(result.output)
        assertEquals(1, parsed.size)
        assertEquals("https://img.example.com/p.jpg", parsed[0].jsonObject["imageUrl"]!!.jsonPrimitive.content)
        assertEquals(1, api.urls.size)
        assertTrue(api.urls[0].contains("safeSearch=Strict"))
        assertTrue(api.urls[0].contains("count=3"))
        assertTrue(client.urls.isEmpty())
    }

    @Test
    fun apiFailureFallsBackToDdg() = runTest {
        val ddgJson = """
            {"results":[{"title":"Fox","image":"https://img.example.com/f.jpg","url":"https://example.com/fox","width":640,"height":480}]}
        """.trimIndent()
        val client = FakeClient(
            mapOf(
                "https://duckduckgo.com/i.js" to ddgJson,
                "https://duckduckgo.com/?" to """<html><script>vqd="999-111";</script></html>""",
            ),
        )
        val tool = ImageSearchTool(
            client = client,
            apiConfig = { ImageSearchApiConfig("bing", "key") },
            apiClient = FakeApiClient("", fail = true),
        )
        val result = tool.execute(args("fox"))
        assertFalse(result.isError)
        assertEquals(1, results(result.output).size)
        assertTrue(client.urls.any { it.startsWith("https://duckduckgo.com/i.js") && it.contains("vqd=999-111") })
    }

    @Test
    fun ddgHtmlFallbackWithoutVqd() = runTest {
        val html = """
            <a class="tile tile--img" href="/l/?iurl=https%3A%2F%2Fimg.example.com%2Fb.jpg&t=Bear&iw=10&ih=20">img</a>
        """.trimIndent()
        val client = FakeClient(mapOf("https://duckduckgo.com/?" to html))
        val result = ImageSearchTool(client = client).execute(args("bear"))
        assertFalse(result.isError)
        val parsed = results(result.output)
        assertEquals(1, parsed.size)
        assertEquals("https://img.example.com/b.jpg", parsed[0].jsonObject["imageUrl"]!!.jsonPrimitive.content)
        assertEquals(1, client.urls.size)
    }

    @Test
    fun countIsClampedInDdgJsonPath() = runTest {
        val many = buildString {
            append("""{"results":[""")
            for (i in 1..30) {
                if (i > 1) append(",")
                append("""{"title":"I$i","image":"https://img.example.com/$i.jpg"}""")
            }
            append("]}")
        }
        val client = FakeClient(
            mapOf(
                "https://duckduckgo.com/i.js" to many,
                "https://duckduckgo.com/?" to """vqd="1-2";""",
            ),
        )
        val result = ImageSearchTool(client = client).execute(args("many", count = 99))
        assertFalse(result.isError)
        assertEquals(20, results(result.output).size)
    }

    @Test
    fun deniedDdgHostFailsWithoutFetch() = runTest {
        val client = FakeClient(emptyMap())
        val policy = NetworkPolicy(
            listOf(NetworkPolicy.NetworkRule("duckduckgo.com", NetworkPolicy.NetworkAction.DENY)),
        )
        val result = ImageSearchTool(networkPolicy = policy, client = client).execute(args("panda"))
        assertTrue(result.isError)
        assertTrue(client.urls.isEmpty())
    }

    @Test
    fun allEndpointsFailingIsAnError() = runTest {
        val result = ImageSearchTool(client = FakeClient(emptyMap())).execute(args("panda"))
        assertTrue(result.isError)
        assertTrue(result.output.contains("ImageSearch request failed"))
    }
}
