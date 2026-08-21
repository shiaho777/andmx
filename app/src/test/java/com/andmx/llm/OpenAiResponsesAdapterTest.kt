package com.andmx.llm

import com.andmx.llm.provider.ModelDefinition
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.provider.ProviderKind
import com.andmx.llm.provider.ReasoningConfig
import com.andmx.llm.provider.ReasoningStyle
import com.andmx.llm.wire.OpenAiResponsesAdapter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiResponsesAdapterTest {

    private val adapter = OpenAiResponsesAdapter
    private val json = Json { ignoreUnknownKeys = true }
    private val provider = ProviderDefinition(
        id = "openai", name = "OpenAI", kind = ProviderKind.OPENAI_RESPONSES,
        baseUrl = "https://api.openai.com/v1", apiKey = "key",
        models = mapOf(
            "gpt-5" to ModelDefinition(
                contextWindow = 400_000,
                reasoning = ReasoningConfig(
                    style = ReasoningStyle.EFFORT,
                    effortLevels = listOf("minimal", "low", "medium", "high"),
                ),
            ),
        ),
    )

    private fun encode(vararg messages: ApiMessage, tools: List<ApiTool>? = null, effort: String? = null, stream: Boolean = false): JsonObject =
        json.parseToJsonElement(
            adapter.encodeRequest(
                ChatRequest(model = "gpt-5", messages = messages.toList(), tools = tools, reasoningEffort = effort, stream = stream),
                provider,
            ),
        ).jsonObject

    @Test
    fun endpointAndAuthHeaders() {
        assertEquals("https://api.openai.com/v1/responses", adapter.endpointUrl(provider.baseUrl))
        assertEquals("Authorization" to "Bearer key", adapter.authHeader("key"))
    }

    @Test
    fun encodesSystemAsTopLevelInstructions() {
        val body = encode(ApiMessage(role = "system", content = "你是助手"), ApiMessage(role = "user", content = "你好"))
        assertEquals("你是助手", body["instructions"]?.jsonPrimitive?.content)
        val input = body["input"]?.jsonArray ?: error("missing input")
        assertEquals(1, input.size)
        val user = input[0].jsonObject
        assertEquals("user", user["role"]?.jsonPrimitive?.content)
        val part = user["content"]?.jsonArray?.get(0)?.jsonObject
        assertEquals("input_text", part?.get("type")?.jsonPrimitive?.content)
        assertEquals("你好", part?.get("text")?.jsonPrimitive?.content)
    }

    @Test
    fun encodesToolExchangeAsFunctionCallItems() {
        val body = encode(
            ApiMessage(role = "user", content = "列出文件"),
            ApiMessage(
                role = "assistant",
                content = "好的",
                toolCalls = listOf(ApiToolCall(id = "call_1", function = ApiFunctionCall("shell", "{\"command\":\"ls\"}"))),
            ),
            ApiMessage(role = "tool", content = "a.txt", toolCallId = "call_1"),
        )
        val input = body["input"]?.jsonArray!!
        assertEquals(4, input.size)

        val assistantText = input[1].jsonObject
        assertEquals("assistant", assistantText["role"]?.jsonPrimitive?.content)
        assertEquals("output_text", assistantText["content"]?.jsonArray?.get(0)?.jsonObject?.get("type")?.jsonPrimitive?.content)

        val call = input[2].jsonObject
        assertEquals("function_call", call["type"]?.jsonPrimitive?.content)
        assertEquals("call_1", call["call_id"]?.jsonPrimitive?.content)
        assertEquals("shell", call["name"]?.jsonPrimitive?.content)
        assertEquals("{\"command\":\"ls\"}", call["arguments"]?.jsonPrimitive?.content)

        val output = input[3].jsonObject
        assertEquals("function_call_output", output["type"]?.jsonPrimitive?.content)
        assertEquals("call_1", output["call_id"]?.jsonPrimitive?.content)
        assertEquals("a.txt", output["output"]?.jsonPrimitive?.content)
    }

    @Test
    fun encodesToolsInFlatResponsesFormat() {
        val body = encode(
            ApiMessage(role = "user", content = "hi"),
            tools = listOf(ApiTool(function = ApiFunctionDef("shell", "run", buildJsonObject { put("type", "object") }))),
        )
        val tools = body["tools"]?.jsonArray ?: error("missing tools")
        val tool = tools[0].jsonObject
        assertEquals("function", tool["type"]?.jsonPrimitive?.content)
        assertEquals("shell", tool["name"]?.jsonPrimitive?.content)
        assertTrue(tool.containsKey("parameters"))
        assertNull(tool["function"])
    }

    @Test
    fun encodesReasoningEffortOnlyForAcceptedLevels() {
        val on = encode(ApiMessage(role = "user", content = "hi"), effort = "high")
        val reasoning = on["reasoning"]?.jsonObject ?: error("missing reasoning")
        assertEquals("high", reasoning["effort"]?.jsonPrimitive?.content)
        assertEquals("auto", reasoning["summary"]?.jsonPrimitive?.content)

        assertNull(encode(ApiMessage(role = "user", content = "hi"), effort = "bogus")["reasoning"])
        assertNull(encode(ApiMessage(role = "user", content = "hi"), effort = "off")["reasoning"])
        assertNull(encode(ApiMessage(role = "user", content = "hi"), effort = null)["reasoning"])
    }

    @Test
    fun alwaysStatelessAndStreamsOnDemand() {
        val body = encode(ApiMessage(role = "user", content = "hi"))
        assertEquals("false", body["store"]?.jsonPrimitive?.content)
        assertNull(body["stream"])

        val streaming = encode(ApiMessage(role = "user", content = "hi"), stream = true)
        assertEquals("true", streaming["stream"]?.jsonPrimitive?.content)
    }

    @Test
    fun encodesUserImagesAsInputImageParts() {
        val body = encode(ApiMessage(role = "user", content = "看图", imageUrls = listOf("data:image/png;base64,AAA")))
        val parts = body["input"]?.jsonArray?.get(0)?.jsonObject?.get("content")?.jsonArray!!
        assertEquals(2, parts.size)
        assertEquals("input_text", parts[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("input_image", parts[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("data:image/png;base64,AAA", parts[1].jsonObject["image_url"]?.jsonPrimitive?.content)
    }

    @Test
    fun encodesToolOutputImagesAsRichOutputParts() {
        val body = encode(
            ApiMessage(role = "tool", content = "截图", toolCallId = "call_9", imageUrls = listOf("data:image/png;base64,BBB")),
        )
        val output = body["input"]?.jsonArray?.get(0)?.jsonObject?.get("output")?.jsonArray!!
        assertEquals(2, output.size)
        assertEquals("output_text", output[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("input_image", output[1].jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun parsesNonStreamingOutputArray() {
        val body = """
            {"id":"resp_1","status":"completed","output":[
              {"type":"reasoning","id":"rs_1","summary":[]},
              {"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"Hello world"}]},
              {"type":"function_call","id":"fc_1","call_id":"call_7","name":"shell","arguments":"{\"command\":\"ls\"}"}
            ]}
        """.trimIndent()
        val msg = adapter.parseResponse(body)
        assertEquals("Hello world", msg.content)
        val call = msg.toolCalls?.single() ?: error("missing toolCalls")
        assertEquals("call_7", call.id)
        assertEquals("shell", call.function.name)
        assertEquals("{\"command\":\"ls\"}", call.function.arguments)
    }

    @Test
    fun streamsTextReasoningAndToolCallDeltas() = runTest {
        val lines = listOf(
            """data: {"type":"response.created","response":{"id":"resp_1"}}""",
            """data: {"type":"response.reasoning_summary_text.delta","delta":"thinking"}""",
            """data: {"type":"response.output_text.delta","item_id":"msg_1","delta":"Hel"}""",
            """data: {"type":"response.output_text.delta","item_id":"msg_1","delta":"lo"}""",
            """data: {"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"shell","arguments":""}}""",
            """data: {"type":"response.function_call_arguments.delta","output_index":1,"item_id":"fc_1","delta":"{\"cmd\""}""",
            """data: {"type":"response.function_call_arguments.delta","output_index":1,"item_id":"fc_1","delta":":1}"}""",
            """data: {"type":"response.completed","response":{"output":[
                {"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello"}]},
                {"type":"function_call","call_id":"call_1","name":"shell","arguments":"{\"cmd\":1}"}
            ],"usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}}""",
        ).asSequence()

        val contents = mutableListOf<String>()
        val reasonings = mutableListOf<String>()
        val toolEvents = mutableListOf<LlmStreamEvent.ToolCallDelta>()
        val msg = adapter.parseStream(
            lines,
            onContent = { contents += it },
            onReasoning = { reasonings += it },
            onToolCall = { index, id, name, delta -> toolEvents += LlmStreamEvent.ToolCallDelta(index, id, name, delta) },
        )

        assertEquals(listOf("Hel", "lo"), contents)
        assertEquals(listOf("thinking"), reasonings)
        assertEquals("call_1", toolEvents.first().id)
        assertEquals("shell", toolEvents.first().name)
        assertEquals("Hello", msg.content)
        val call = msg.toolCalls?.single() ?: error("missing toolCalls")
        assertEquals("call_1", call.id)
        assertEquals("{\"cmd\":1}", call.function.arguments)
    }

    @Test
    fun fallsBackToBuffersWithoutCompletedEvent() = runTest {
        val lines = listOf(
            """data: {"type":"response.output_text.delta","delta":"partial"}""",
            """data: {"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","call_id":"call_2","name":"read","arguments":""}}""",
            """data: {"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\"path\""}""",
        ).asSequence()

        val msg = adapter.parseStream(lines, onContent = {}, onReasoning = {}, onToolCall = { _, _, _, _ -> })
        assertEquals("partial", msg.content)
        val call = msg.toolCalls?.single() ?: error("missing toolCalls")
        assertEquals("call_2", call.id)
        assertEquals("read", call.function.name)
        assertEquals("{\"path\"", call.function.arguments)
    }

    @Test
    fun failedEventThrows() = runTest {
        val lines = listOf(
            """data: {"type":"response.failed","response":{"error":{"message":"bad request"}}}""",
        ).asSequence()
        try {
            adapter.parseStream(lines, onContent = {}, onReasoning = {}, onToolCall = { _, _, _, _ -> })
            error("expected failure")
        } catch (e: IllegalStateException) {
            assertEquals("bad request", e.message)
        }
    }

    @Test
    fun usageExtractsAndParses() {
        val usage = adapter.extractUsage("""{"usage":{"input_tokens":10,"output_tokens":5}}""")
            ?: error("missing usage")
        val parsed = TokenUsageTracker.parseUsage(usage)
        assertEquals(10, parsed.inputTokens)
        assertEquals(5, parsed.outputTokens)
        assertEquals(15, parsed.totalTokens)
    }
}
