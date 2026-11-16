package com.andmx.agent

import com.andmx.exec.policy.NetworkPolicy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchLayersTest {

    private class FakeDdg(private val html: String) : WebSearchClient {
        val urls = mutableListOf<String>()
        override fun fetch(url: String): String {
            urls += url
            return html
        }
    }

    private class FakeApi(private val payload: String?) : WebSearchApiClient {
        val calls = mutableListOf<WebSearchApis.ApiRequest>()
        override fun request(url: String, method: String, headers: Map<String, String>, body: String?): String {
            calls += WebSearchApis.ApiRequest(url, method, headers, body)
            return payload ?: error("api down")
        }
    }

    private class FakeNative(
        private val supported: Boolean = true,
        private val results: List<WebSearch.Result>? = listOf(WebSearch.Result("Native", "https://native.example.com/n", "")),
    ) : ProviderNativeSearch {
        var calls = 0
        override fun supports(): Boolean = supported
        override fun search(query: String, allowed: List<String>, blocked: List<String>): List<WebSearch.Result> {
            calls++
            return results ?: error("native down")
        }
    }

    private fun args(query: String = "kotlin android") = buildJsonObject { put("query", query) }

    private val ddgHtml = """<a class="result__a" href="https://ddg.example.com/d">Ddg Hit</a>"""

    @Test
    fun nativeLayerWinsWhenSupported() = runTest {
        val native = FakeNative()
        val ddg = FakeDdg(ddgHtml)
        val api = FakeApi("""{"results":[]}""")
        val tool = WebSearchTool(
            client = ddg,
            nativeSearch = native,
            apiConfig = { WebSearchApiConfig("tavily", "key") },
            apiClient = api,
        )
        val result = tool.execute(args())
        assertFalse(result.isError)
        assertTrue(result.output.contains("[Native](https://native.example.com/n)"))
        assertEquals(1, native.calls)
        assertTrue(api.calls.isEmpty())
        assertTrue(ddg.urls.isEmpty())
    }

    @Test
    fun apiLayerUsedWhenNativeUnsupported() = runTest {
        val ddg = FakeDdg(ddgHtml)
        val api = FakeApi("""{"results":[{"title":"Tavily Hit","url":"https://tavily.example.com/t","content":"c"}]}""")
        val tool = WebSearchTool(
            client = ddg,
            nativeSearch = FakeNative(supported = false),
            apiConfig = { WebSearchApiConfig("tavily", "key") },
            apiClient = api,
        )
        val result = tool.execute(args())
        assertFalse(result.isError)
        assertTrue(result.output.contains("[Tavily Hit](https://tavily.example.com/t)"))
        assertEquals(1, api.calls.size)
        assertTrue(ddg.urls.isEmpty())
    }

    @Test
    fun apiFailureFallsBackToDdg() = runTest {
        val ddg = FakeDdg(ddgHtml)
        val tool = WebSearchTool(
            client = ddg,
            nativeSearch = FakeNative(supported = false),
            apiConfig = { WebSearchApiConfig("brave", "key") },
            apiClient = FakeApi(null),
        )
        val result = tool.execute(args())
        assertFalse(result.isError)
        assertTrue(result.output.contains("[Ddg Hit](https://ddg.example.com/d)"))
        assertEquals(1, ddg.urls.size)
    }

    @Test
    fun nativeFailureFallsBackToApiThenDdg() = runTest {
        val ddg = FakeDdg(ddgHtml)
        val api = FakeApi(null)
        val tool = WebSearchTool(
            client = ddg,
            nativeSearch = FakeNative(results = null),
            apiConfig = { WebSearchApiConfig("tavily", "key") },
            apiClient = api,
        )
        val result = tool.execute(args())
        assertFalse(result.isError)
        assertTrue(result.output.contains("[Ddg Hit](https://ddg.example.com/d)"))
        assertEquals(1, api.calls.size)
        assertEquals(1, ddg.urls.size)
    }

    @Test
    fun missingApiKeySkipsApiLayer() = runTest {
        val ddg = FakeDdg(ddgHtml)
        val api = FakeApi("""{"results":[]}""")
        val tool = WebSearchTool(
            client = ddg,
            nativeSearch = FakeNative(supported = false),
            apiConfig = { WebSearchApiConfig("tavily", "") },
            apiClient = api,
        )
        val result = tool.execute(args())
        assertFalse(result.isError)
        assertTrue(api.calls.isEmpty())
        assertEquals(1, ddg.urls.size)
    }

    @Test
    fun deniedApiHostFallsBackToDdg() = runTest {
        val ddg = FakeDdg(ddgHtml)
        val api = FakeApi("""{"results":[]}""")
        val policy = NetworkPolicy(
            listOf(NetworkPolicy.NetworkRule("api.tavily.com", NetworkPolicy.NetworkAction.DENY)),
        )
        val tool = WebSearchTool(
            networkPolicy = policy,
            client = ddg,
            nativeSearch = FakeNative(supported = false),
            apiConfig = { WebSearchApiConfig("tavily", "key") },
            apiClient = api,
        )
        val result = tool.execute(args())
        assertFalse(result.isError)
        assertTrue(api.calls.isEmpty())
        assertEquals(1, ddg.urls.size)
    }

    @Test
    fun maxResultsCapsOutputLinks() = runTest {
        val payload = buildString {
            append("""{"results":[""")
            for (i in 1..5) {
                if (i > 1) append(',')
                append("""{"title":"T$i","url":"https://example.com/p$i","content":"c"}""")
            }
            append("]}")
        }
        val tool = WebSearchTool(
            client = FakeDdg(ddgHtml),
            nativeSearch = FakeNative(supported = false),
            apiConfig = { WebSearchApiConfig("tavily", "key") },
            apiClient = FakeApi(payload),
        )
        val result = tool.execute(buildJsonObject {
            put("query", "many")
            put("max_results", 2)
        })
        assertFalse(result.isError)
        assertTrue(result.output.contains("p1"))
        assertTrue(result.output.contains("p2"))
        assertFalse(result.output.contains("p3"))
    }

    @Test
    fun apiResultsRespectBlockedDomains() = runTest {
        val payload = """{"results":[
            {"title":"Keep","url":"https://keep.example.com/k","content":"c"},
            {"title":"Drop","url":"https://drop.test/d","content":"c"}
        ]}"""
        val tool = WebSearchTool(
            client = FakeDdg(ddgHtml),
            nativeSearch = FakeNative(supported = false),
            apiConfig = { WebSearchApiConfig("tavily", "key") },
            apiClient = FakeApi(payload),
        )
        val result = tool.execute(buildJsonObject {
            put("query", "filter")
            put("blocked_domains", buildJsonArray { add("drop.test") })
        })
        assertFalse(result.isError)
        assertTrue(result.output.contains("keep.example.com"))
        assertFalse(result.output.contains("drop.test"))
    }
}