package com.andmx.agent

import com.andmx.llm.provider.ModelDefinition
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.provider.ProviderKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderNativeSearchTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun provider(kind: ProviderKind, native: Boolean = true) = ProviderDefinition(
        id = "p",
        name = "p",
        kind = kind,
        baseUrl = "https://api.example.com/v1",
        apiKey = "sk-test",
        models = mapOf("m" to ModelDefinition(supportsNativeWebSearch = native)),
    )

    @Test
    fun supportsRequiresUsableProviderNativeModelAndServerToolKind() {
        val responses = HttpProviderNativeSearch { provider(ProviderKind.OPENAI_RESPONSES) to "m" }
        assertTrue(responses.supports())
        val anthropic = HttpProviderNativeSearch { provider(ProviderKind.ANTHROPIC) to "m" }
        assertTrue(anthropic.supports())
        val chat = HttpProviderNativeSearch { provider(ProviderKind.OPENAI) to "m" }
        assertFalse(chat.supports())
        val noFlag = HttpProviderNativeSearch { provider(ProviderKind.ANTHROPIC, native = false) to "m" }
        assertFalse(noFlag.supports())
        val noKey = HttpProviderNativeSearch {
            provider(ProviderKind.ANTHROPIC).copy(apiKey = "") to "m"
        }
        assertFalse(noKey.supports())
        val none = HttpProviderNativeSearch { null }
        assertFalse(none.supports())
    }

    @Test
    fun responsesBodyInjectsWebSearchTool() {
        val body = HttpProviderNativeSearch.nativeRequestBody(
            provider(ProviderKind.OPENAI_RESPONSES),
            "m",
            ProviderKind.OPENAI_RESPONSES,
            "kotlin flow",
            allowed = listOf("kotlinlang.org"),
            blocked = emptyList(),
        )
        val root = json.parseToJsonElement(body).jsonObject
        assertEquals("m", root["model"]!!.jsonPrimitive.content)
        val tool = root["tools"]!!.jsonArray[0].jsonObject
        assertEquals("web_search", tool["type"]!!.jsonPrimitive.content)
        assertEquals(
            "kotlinlang.org",
            tool["filters"]!!.jsonObject["allowed_domains"]!!.jsonArray[0].jsonPrimitive.content,
        )
        assertTrue(root["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.contains("kotlin flow"))
    }

    @Test
    fun anthropicBodyInjectsServerWebSearchTool() {
        val body = HttpProviderNativeSearch.nativeRequestBody(
            provider(ProviderKind.ANTHROPIC),
            "m",
            ProviderKind.ANTHROPIC,
            "kotlin flow",
            allowed = emptyList(),
            blocked = listOf("spam.test"),
        )
        val root = json.parseToJsonElement(body).jsonObject
        val tool = root["tools"]!!.jsonArray[0].jsonObject
        assertEquals("web_search_20250305", tool["type"]!!.jsonPrimitive.content)
        assertEquals("web_search", tool["name"]!!.jsonPrimitive.content)
        assertEquals(
            "spam.test",
            tool["blocked_domains"]!!.jsonArray[0].jsonPrimitive.content,
        )
        assertTrue(root.containsKey("max_tokens"))
    }

    @Test
    fun parsesResponsesUrlCitations() {
        val payload = """
            {"output":[
              {"type":"web_search_call","id":"ws_1"},
              {"type":"message","content":[
                {"type":"output_text","text":"Answer [1]","annotations":[
                  {"type":"url_citation","url":"https://a.example.com/1","title":"Source A"},
                  {"type":"url_citation","url":"https://b.example.com/2","title":"Source B"},
                  {"type":"other","url":"https://skip"}
                ]}
              ]}
            ]}
        """.trimIndent()
        val results = HttpProviderNativeSearch.parseNativeResults(ProviderKind.OPENAI_RESPONSES, payload)
        assertEquals(2, results.size)
        assertEquals(WebSearch.Result("Source A", "https://a.example.com/1", ""), results[0])
        assertEquals("https://b.example.com/2", results[1].url)
    }

    @Test
    fun parsesAnthropicWebSearchToolResults() {
        val payload = """
            {"content":[
              {"type":"text","text":"Answer"},
              {"type":"server_tool_use","id":"srv_1","name":"web_search"},
              {"type":"web_search_tool_result","tool_use_id":"srv_1","content":[
                {"type":"web_search_result","url":"https://c.example.com/3","title":"Source C","page_age":"2 days"},
                {"type":"web_search_tool_result_error","error_code":"unavailable"}
              ]}
            ]}
        """.trimIndent()
        val results = HttpProviderNativeSearch.parseNativeResults(ProviderKind.ANTHROPIC, payload)
        assertEquals(listOf(WebSearch.Result("Source C", "https://c.example.com/3", "")), results)
    }

    @Test
    fun malformedNativePayloadsReturnEmpty() {
        assertTrue(HttpProviderNativeSearch.parseNativeResults(ProviderKind.ANTHROPIC, "oops").isEmpty())
        assertTrue(HttpProviderNativeSearch.parseNativeResults(ProviderKind.OPENAI_RESPONSES, "{}").isEmpty())
        assertTrue(HttpProviderNativeSearch.parseNativeResults(ProviderKind.ANTHROPIC, """{"content":[]}""").isEmpty())
    }
}