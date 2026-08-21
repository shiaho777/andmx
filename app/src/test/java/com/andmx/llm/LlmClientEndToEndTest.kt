package com.andmx.llm

import com.andmx.agent.AgentEngine
import com.andmx.agent.AgentEvent
import com.andmx.agent.Tool
import com.andmx.agent.ToolResult
import com.andmx.agent.TurnContext
import com.andmx.llm.provider.ModelDefinition
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.provider.ProviderKind
import com.andmx.settings.ProviderSettings
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end smoke over real HTTP: LlmClient transport (headers, SSE framing,
 * retries, usage recording) plus a full AgentEngine tool loop, against an
 * in-process MockWebServer. No client-side mocks.
 */
class LlmClientEndToEndTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun provider(kind: ProviderKind = ProviderKind.OPENAI, streamRetries: Int = 1) =
        ProviderDefinition(
            id = "test", name = "test", kind = kind,
            baseUrl = server.url("/").toString().trimEnd('/'), apiKey = "secret-key",
            requestMaxRetries = 0, streamMaxRetries = streamRetries,
            models = mapOf("test-model" to ModelDefinition(contextWindow = 128_000)),
        )

    private fun sseResponse(vararg chunks: String): MockResponse {
        val body = chunks.joinToString("\n\n") + "\n\n"
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)
    }

    private fun jsonResponse(status: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(status)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    @Test
    fun streamsOpenAiChatOverRealHttp() = runTest {
        server.enqueue(
            sseResponse(
                """data: {"choices":[{"delta":{"role":"assistant","content":"He"}}]}""",
                """data: {"choices":[{"delta":{"content":"llo"}}]}""",
                """data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}""",
                "data: [DONE]",
            ),
        )
        val tracker = TokenUsageTracker()
        val client = LlmClient(provider(), tracker = tracker)
        val events = client.chatStream(
            ChatRequest(model = "test-model", messages = listOf(ApiMessage(role = "user", content = "hi")), stream = true),
        ).toList()

        val contents = events.filterIsInstance<LlmStreamEvent.Content>()
        assertEquals(listOf("He", "llo"), contents.map { it.delta })
        assertEquals("Hello", events.filterIsInstance<LlmStreamEvent.Completed>().single().message.content)
        assertEquals(3, tracker.sessionUsage.value.inputTokens)
        assertEquals(2, tracker.sessionUsage.value.outputTokens)

        val sent = server.takeRequest()
        assertEquals("Bearer secret-key", sent.headers["Authorization"])
        val body = json.parseToJsonElement(sent.body.readUtf8()).jsonObject
        assertEquals("test-model", body["model"]?.jsonPrimitive?.content)
        assertTrue(body["stream"]?.jsonPrimitive?.content == "true")
    }

    @Test
    fun nonStreamingChatParsesAndRecordsUsage() = runTest {
        server.enqueue(
            jsonResponse(
                200,
                """{"choices":[{"message":{"role":"assistant","content":"answer"},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":4,"total_tokens":11}}""",
            ),
        )
        val tracker = TokenUsageTracker()
        val client = LlmClient(provider(), tracker = tracker)
        val result = client.chat(ChatRequest(model = "test-model", messages = listOf(ApiMessage(role = "user", content = "q"))))
        assertEquals("answer", result.getOrNull()?.content)
        assertEquals(7, tracker.sessionUsage.value.inputTokens)
        assertEquals(4, tracker.sessionUsage.value.outputTokens)
    }

    @Test
    fun httpErrorFailsWithoutRetryWhenRetriesDisabled() = runTest {
        server.enqueue(jsonResponse(500, """{"error":{"message":"boom"}}"""))
        server.enqueue(jsonResponse(500, """{"error":{"message":"boom"}}"""))
        val client = LlmClient(provider(streamRetries = 0))

        val result = client.chat(ChatRequest(model = "test-model", messages = listOf(ApiMessage(role = "user", content = "q"))))
        assertTrue(result.isFailure)

        try {
            client.chatStream(
                ChatRequest(model = "test-model", messages = listOf(ApiMessage(role = "user", content = "q")), stream = true),
            ).toList()
            error("expected stream failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("500"))
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun requestRetriesOnceThenSucceeds() = runTest {
        server.enqueue(jsonResponse(503, "unavailable"))
        server.enqueue(jsonResponse(200, """{"choices":[{"message":{"role":"assistant","content":"recovered"}}]}"""))
        val client = LlmClient(provider().copy(requestMaxRetries = 1))
        val result = client.chat(ChatRequest(model = "test-model", messages = listOf(ApiMessage(role = "user", content = "q"))))
        assertEquals("recovered", result.getOrNull()?.content)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun agentToolLoopFeedsToolResultBackOverTheWire() = runTest {
        val echoTool = object : Tool {
            override val name = "echo"
            override val description = "echo"
            override val parameters = buildJsonObject { put("type", "object") }
            override suspend fun execute(args: kotlinx.serialization.json.JsonObject): ToolResult {
                return ToolResult("echoed:${args["text"]?.jsonPrimitive?.content}")
            }
        }
        server.enqueue(
            sseResponse(
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"echo","arguments":""}}]}}]}""",
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"text\":\"hi\"}"}}]}}]}""",
                """data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
                "data: [DONE]",
            ),
        )
        server.enqueue(
            sseResponse(
                """data: {"choices":[{"delta":{"content":"done"},"finish_reason":"stop"}]}""",
                "data: [DONE]",
            ),
        )

        val engine = AgentEngine(tools = listOf(echoTool), client = LlmClient(provider()))
        val turn = TurnContext(provider = provider(), model = "test-model")
        val events = engine.runTurn(ProviderSettings(model = "test-model"), turn, "请回显").toList()

        assertEquals("echo", events.filterIsInstance<AgentEvent.ToolStarted>().single().name)
        assertEquals("echoed:hi", events.filterIsInstance<AgentEvent.ToolFinished>().single().output)
        assertEquals("done", events.filterIsInstance<AgentEvent.Assistant>().single().text)
        assertTrue(events.last() is AgentEvent.Done)

        server.takeRequest()
        val second = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val toolMsg = second["messages"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["role"]?.jsonPrimitive?.content == "tool" }
            ?: error("second request must carry the tool result")
        assertEquals("call_1", toolMsg["tool_call_id"]?.jsonPrimitive?.content)
        assertEquals("echoed:hi", toolMsg["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun responsesProtocolStreamsOverRealHttp() = runTest {
        server.enqueue(
            sseResponse(
                """data: {"type":"response.created","response":{"id":"resp_1"}}""",
                """data: {"type":"response.output_text.delta","delta":"wor"}""",
                """data: {"type":"response.output_text.delta","delta":"ld"}""",
                """data: {"type":"response.completed","response":{"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"world"}]}],"usage":{"input_tokens":6,"output_tokens":3}}}""",
            ),
        )
        val def = provider(kind = ProviderKind.OPENAI_RESPONSES)
            .copy(models = mapOf("gpt-5" to ModelDefinition(contextWindow = 128_000)))
        val tracker = TokenUsageTracker()
        val client = LlmClient(def, tracker = tracker)
        val events = client.chatStream(
            ChatRequest(
                model = "gpt-5",
                messages = listOf(ApiMessage(role = "system", content = "be nice"), ApiMessage(role = "user", content = "hi")),
                stream = true,
            ),
        ).toList()

        assertEquals(listOf("wor", "ld"), events.filterIsInstance<LlmStreamEvent.Content>().map { it.delta })
        assertEquals("world", events.filterIsInstance<LlmStreamEvent.Completed>().single().message.content)
        assertEquals(6, tracker.sessionUsage.value.inputTokens)
        assertEquals(3, tracker.sessionUsage.value.outputTokens)

        val sent = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("gpt-5", sent["model"]?.jsonPrimitive?.content)
        assertEquals("be nice", sent["instructions"]?.jsonPrimitive?.content)
    }
}
