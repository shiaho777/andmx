package com.andmx.llm

import com.andmx.llm.provider.ModelDefinition
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.provider.ProviderKind
import com.andmx.llm.wire.AnthropicMessagesAdapter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the Anthropic Messages wire adapter: request encoding (system at
 * top level, tool_use/tool_result content blocks, input_schema) and SSE event
 * stream assembly (text deltas + incremental tool input json).
 */
class AnthropicMessagesAdapterTest {

    private val adapter = AnthropicMessagesAdapter
    private val json = Json { ignoreUnknownKeys = true }
    private val provider = ProviderDefinition(
        id = "zai", name = "Z.ai", kind = ProviderKind.ANTHROPIC,
        baseUrl = "https://api.z.ai/v1", apiKey = "key",
        models = mapOf("GLM-5.2" to ModelDefinition(contextWindow = 200_000, maxOutputTokens = 8_192)),
    )

    @Test
    fun endpointAndAuthHeaders() {
        assertEquals("https://api.z.ai/v1/v1/messages", adapter.endpointUrl(provider.baseUrl))
        assertEquals("x-api-key" to "key", adapter.authHeader("key"))
        assertTrue(adapter.extraHeaders().contains("anthropic-version" to "2023-06-01"))
    }

    @Test
    fun encodesSystemAsTopLevel() {
        val req = ChatRequest(
            model = "GLM-5.2",
            messages = listOf(
                ApiMessage(role = "system", content = "你是助手"),
                ApiMessage(role = "user", content = "你好"),
            ),
        )
        val body = json.parseToJsonElement(adapter.encodeRequest(req, provider)).jsonObject
        // system must be a top-level block array, NOT a message in the array.
        val system = body["system"]?.jsonArray ?: error("system missing")
        assertEquals(1, system.size)
        val block = system[0].jsonObject
        assertEquals("text", block["type"]?.jsonPrimitive?.content)
        assertEquals("你是助手", block["text"]?.jsonPrimitive?.content)
        // Leading system block gets its own ephemeral cache breakpoint.
        assertEquals(
            "ephemeral",
            block["cache_control"]?.jsonObject?.get("type")?.jsonPrimitive?.content,
        )
        val messages = body["messages"]?.jsonArray ?: error("missing messages")
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun leadingSystemBlocksGetCacheControlButNotReminders() {
        val req = ChatRequest(
            model = "GLM-5.2",
            messages = listOf(
                ApiMessage(role = "system", content = "cli"),
                ApiMessage(role = "system", content = "stable"),
                ApiMessage(role = "system", content = "dynamic"),
                ApiMessage(role = "system", content = "reminder-block"),
                ApiMessage(role = "user", content = "u1"),
                ApiMessage(role = "system", content = "mid reminder"),
                ApiMessage(role = "assistant", content = "a1"),
            ),
        )
        val body = json.parseToJsonElement(adapter.encodeRequest(req, provider)).jsonObject
        val system = body["system"]?.jsonArray ?: error("system missing")
        assertEquals(5, system.size)
        // First 3 contiguous blocks carry breakpoints; the 4th would exceed
        // Anthropic's per-request cap once the message anchor is added.
        repeat(3) { i ->
            assertEquals(
                "ephemeral",
                system[i].jsonObject["cache_control"]?.jsonObject?.get("type")?.jsonPrimitive?.content,
            )
        }
        assertNull(system[3].jsonObject["cache_control"])
        assertNull(system[4].jsonObject["cache_control"])
        assertEquals("mid reminder", system[4].jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun encodesAssistantToolUseAndToolResult() {
        val req = ChatRequest(
            model = "GLM-5.2",
            messages = listOf(
                ApiMessage(role = "user", content = "列出文件"),
                ApiMessage(
                    role = "assistant",
                    toolCalls = listOf(ApiToolCall(id = "t1", function = ApiFunctionCall("run_shell", "{\"command\":\"ls\"}"))),
                ),
                ApiMessage(role = "tool", content = "file_a\nfile_b", toolCallId = "t1"),
            ),
        )
        val body = json.parseToJsonElement(adapter.encodeRequest(req, provider)).jsonObject
        val messages = body["messages"]?.jsonArray ?: error("missing messages")

        // Assistant turn carries a tool_use block with parsed input object.
        val asst = messages[1].jsonObject
        assertEquals("assistant", asst["role"]?.jsonPrimitive?.content)
        val asstBlocks = asst["content"]?.jsonArray ?: error("missing assistant content")
        val toolUse = asstBlocks.first { it.jsonObject["type"]?.jsonPrimitive?.content == "tool_use" }.jsonObject
        assertEquals("t1", toolUse["id"]?.jsonPrimitive?.content)
        assertEquals("run_shell", toolUse["name"]?.jsonPrimitive?.content)
        assertEquals("ls", toolUse["input"]?.jsonObject?.get("command")?.jsonPrimitive?.content)

        // Tool result rides in a user turn as a tool_result block.
        val toolTurn = messages[2].jsonObject
        assertEquals("user", toolTurn["role"]?.jsonPrimitive?.content)
        val resultBlock = toolTurn["content"]?.jsonArray?.first()?.jsonObject ?: error("missing tool_result")
        assertEquals("tool_result", resultBlock["type"]?.jsonPrimitive?.content)
        assertEquals("t1", resultBlock["tool_use_id"]?.jsonPrimitive?.content)
        assertEquals("file_a\nfile_b", resultBlock["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun encodesToolsWithInputSchema() {
        val req = ChatRequest(
            model = "GLM-5.2",
            messages = listOf(ApiMessage(role = "user", content = "x")),
            tools = listOf(ApiTool(type = "function", function = ApiFunctionDef(
                name = "run_shell",
                description = "run a shell command",
                parameters = kotlinx.serialization.json.buildJsonObject {
                    put("type", "object")
                    put("required", kotlinx.serialization.json.buildJsonArray { add("command") })
                },
            ))),
        )
        val body = json.parseToJsonElement(adapter.encodeRequest(req, provider)).jsonObject
        val tools = body["tools"]?.jsonArray ?: error("missing tools")
        val first = tools.first().jsonObject
        assertEquals("run_shell", first["name"]?.jsonPrimitive?.content)
        assertNotNull(first["input_schema"])
    }

    @Test
    fun assemblesTextStream() = runTest {
        val lines = sequenceOf(
            """data: {"type":"message_start","message":{"id":"m1"}}""",
            """data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"，世界"}}""",
            """data: {"type":"content_block_stop","index":0}""",
            """data: {"type":"message_stop"}""",
        )
        val collected = StringBuilder()
        val msg = adapter.parseStream(lines, onContent = { collected.append(it) })

        assertEquals("你好，世界", collected.toString())
        assertEquals("你好，世界", msg.content)
        assertNull(msg.toolCalls)
    }

    @Test
    fun assemblesToolUseStream() = runTest {
        // input_json_delta delivers the tool input across chunks.
        val lines = sequenceOf(
            """data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t1","name":"run_shell","input":{}}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"comm"}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"and\":\"ls\"}"}}""",
            """data: {"type":"content_block_stop","index":0}""",
            """data: {"type":"message_stop"}""",
        )
        val msg = adapter.parseStream(lines, onContent = {})

        val calls = msg.toolCalls
        assertNotNull(calls)
        assertEquals(1, calls!!.size)
        assertEquals("t1", calls.first().id)
        assertEquals("run_shell", calls.first().function.name)
        assertEquals("""{"command":"ls"}""", calls.first().function.arguments)
    }

    @Test
    fun maxTokensStopReasonNormalizesToLength() = runTest {
        val lines = sequenceOf(
            """data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"cut off"}}""",
            """data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":8192}}""",
            """data: {"type":"message_stop"}""",
        )
        val msg = adapter.parseStream(lines, onContent = {})
        assertEquals("cut off", msg.content)
        assertEquals("length", msg.finishReason)
    }

    @Test
    fun endTurnDoesNotNormalize() {
        val body = """{"content":[{"type":"text","text":"done"}],"stop_reason":"end_turn"}"""
        val msg = adapter.parseResponse(body)
        assertEquals("done", msg.content)
        assertEquals("end_turn", msg.finishReason)
    }
}
