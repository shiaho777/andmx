package com.andmx.agent

import com.andmx.llm.ApiFunctionCall
import com.andmx.llm.ApiMessage
import com.andmx.llm.ApiToolCall
import com.andmx.llm.ChatRequest
import com.andmx.llm.LlmApi
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.settings.ProviderSettings
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZCode ToolSearch/DeferredToolCall（local 模式）在引擎里的接线：
 * deferred 工具不随请求发送，加载后仍只能经 DeferredToolCall 调用。
 */
class AgentEngineToolSearchTest {

    private val turn = TurnContext(
        provider = ProviderDefinition(id = "test", name = "test", baseUrl = "http://x", apiKey = "x"),
        model = "test-model",
    )
    private val settings = ProviderSettings(model = "test-model")

    private class ScriptedLlm(private val script: List<ApiMessage>) : LlmApi {
        val requests = mutableListOf<ChatRequest>()
        private var index = 0

        override suspend fun chat(request: ChatRequest): Result<ApiMessage> {
            requests += request
            return Result.success(script.getOrNull(index++) ?: ApiMessage(role = "assistant", content = "done"))
        }
    }

    private class EchoTool : Tool {
        override val name = "Echo"
        override val description = "echo text back to the caller"
        override val shouldDefer = true
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("value") { put("type", "string") }
            }
            putJsonArray("required") { add("value") }
        }

        var calls = 0
        var lastArgs: JsonObject? = null

        override suspend fun execute(args: JsonObject): ToolResult {
            calls += 1
            lastArgs = args
            return ToolResult("echo:" + (args["value"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        }
    }

    private fun engineWith(echo: EchoTool, registry: ToolSearchRegistry, llm: LlmApi) = AgentEngine(
        tools = listOf(echo, ToolSearchTool(registry), DeferredToolCallTool(registry)),
        client = llm,
        toolSearch = registry,
    )

    private fun toolMessage(requests: List<ChatRequest>, index: Int): String =
        requests[index].messages.last { it.role == "tool" }.content.orEmpty()

    @Test
    fun deferredToolIsLoadedThenCalledThroughWrapper() = runTest {
        val echo = EchoTool()
        val registry = ToolSearchRegistry()
        val llm = ScriptedLlm(
            listOf(
                ApiMessage(
                    role = "assistant",
                    toolCalls = listOf(
                        ApiToolCall(
                            id = "s1",
                            function = ApiFunctionCall(
                                "ToolSearch",
                                """{"query":"select:Echo","max_results":5}""",
                            ),
                        ),
                    ),
                ),
                ApiMessage(
                    role = "assistant",
                    toolCalls = listOf(
                        ApiToolCall(
                            id = "d1",
                            function = ApiFunctionCall(
                                "DeferredToolCall",
                                """{"name":"Echo","input":{"value":"hi"}}""",
                            ),
                        ),
                    ),
                ),
                ApiMessage(role = "assistant", content = "done"),
            ),
        )

        val events = engineWith(echo, registry, llm).runTurn(settings, turn, "go").toList()

        assertEquals(1, echo.calls)
        assertEquals("hi", (echo.lastArgs?.get("value") as? JsonPrimitive)?.contentOrNull)

        val firstTools = llm.requests[0].tools.orEmpty().map { it.function.name }
        assertFalse("Echo" in firstTools)
        assertTrue("ToolSearch" in firstTools)
        assertTrue("DeferredToolCall" in firstTools)

        val secondTools = llm.requests[1].tools.orEmpty().map { it.function.name }
        assertFalse("Echo" in secondTools)

        val announcement = llm.requests[1].messages
            .filter { it.role == "system" }
            .joinToString("\n") { it.content.orEmpty() }
        assertTrue(announcement.contains("Echo"))

        val started = events.filterIsInstance<AgentEvent.ToolStarted>().single { it.id == "d1" }
        assertEquals("Echo", started.name)
        assertEquals("""{"value":"hi"}""", started.arguments)
        assertTrue(
            events.any {
                it is AgentEvent.ToolFinished &&
                    it.name == "Echo" &&
                    it.output == "echo:hi" &&
                    !it.isError
            },
        )
        assertTrue(
            llm.requests[2].messages.any {
                it.role == "tool" && it.name == "DeferredToolCall" && it.content == "echo:hi"
            },
        )
    }

    @Test
    fun directCallToDeferredToolIsRejectedBeforeExecution() = runTest {
        val echo = EchoTool()
        val registry = ToolSearchRegistry()
        val llm = ScriptedLlm(
            listOf(
                ApiMessage(
                    role = "assistant",
                    toolCalls = listOf(
                        ApiToolCall(
                            id = "d1",
                            function = ApiFunctionCall("Echo", """{"value":"hi"}"""),
                        ),
                    ),
                ),
                ApiMessage(role = "assistant", content = "done"),
            ),
        )

        engineWith(echo, registry, llm).runTurn(settings, turn, "go").toList()

        assertEquals(0, echo.calls)
        val error = toolMessage(llm.requests, 1)
        assertTrue(error.contains("deferred"))
        assertTrue(error.contains("ToolSearch"))
    }

    @Test
    fun deferredCallBeforeLoadingSchemaIsRejected() = runTest {
        val echo = EchoTool()
        val registry = ToolSearchRegistry()
        val llm = ScriptedLlm(
            listOf(
                ApiMessage(
                    role = "assistant",
                    toolCalls = listOf(
                        ApiToolCall(
                            id = "d1",
                            function = ApiFunctionCall(
                                "DeferredToolCall",
                                """{"name":"Echo","input":{"value":"hi"}}""",
                            ),
                        ),
                    ),
                ),
                ApiMessage(role = "assistant", content = "done"),
            ),
        )

        engineWith(echo, registry, llm).runTurn(settings, turn, "go").toList()

        assertEquals(0, echo.calls)
        val error = toolMessage(llm.requests, 1)
        assertTrue(error.contains("schema for Echo is not loaded"))
        assertTrue(error.contains("select:Echo"))
    }

    @Test
    fun deferredCallToResidentToolIsRejected() = runTest {
        val echo = EchoTool()
        val registry = ToolSearchRegistry()
        val resident = object : Tool {
            override val name = "Read"
            override val description = "resident"
            override val parameters: JsonObject = buildJsonObject { put("type", "object") }
            override suspend fun execute(args: JsonObject): ToolResult = ToolResult("read")
        }
        val llm = ScriptedLlm(
            listOf(
                ApiMessage(
                    role = "assistant",
                    toolCalls = listOf(
                        ApiToolCall(
                            id = "d1",
                            function = ApiFunctionCall(
                                "DeferredToolCall",
                                """{"name":"Read","input":{}}""",
                            ),
                        ),
                    ),
                ),
                ApiMessage(role = "assistant", content = "done"),
            ),
        )
        val engine = AgentEngine(
            tools = listOf(echo, resident, ToolSearchTool(registry), DeferredToolCallTool(registry)),
            client = llm,
            toolSearch = registry,
        )

        engine.runTurn(settings, turn, "go").toList()

        val error = toolMessage(llm.requests, 1)
        assertTrue(error.contains("resident tool"))
    }

    @Test
    fun toolSearchSchemaIsNotTruncatedByTheHistoryBudget() = runTest {
        val marker = "TAIL_MARKER_AFTER_12K"
        val big = object : Tool {
            override val name = "BigTool"
            override val description = "big schema"
            override val shouldDefer = true
            override val parameters: JsonObject = buildJsonObject {
                put("type", "object")
                put("description", "x".repeat(12_000) + marker)
            }
            override suspend fun execute(args: JsonObject): ToolResult = ToolResult("big")
        }
        val registry = ToolSearchRegistry()
        val llm = ScriptedLlm(
            listOf(
                ApiMessage(
                    role = "assistant",
                    toolCalls = listOf(
                        ApiToolCall(
                            id = "s1",
                            function = ApiFunctionCall(
                                "ToolSearch",
                                """{"query":"select:BigTool","max_results":5}""",
                            ),
                        ),
                    ),
                ),
                ApiMessage(role = "assistant", content = "done"),
            ),
        )
        val engine = AgentEngine(
            tools = listOf(big, ToolSearchTool(registry), DeferredToolCallTool(registry)),
            client = llm,
            toolSearch = registry,
        )

        engine.runTurn(settings, turn, "go").toList()

        val result = llm.requests[1].messages.last { it.role == "tool" }.content.orEmpty()
        assertTrue(result.contains(marker))
    }

    @Test
    fun announcementIsRequestLocalAndNeverPersisted() = runTest {
        val echo = EchoTool()
        val registry = ToolSearchRegistry()
        val llm = ScriptedLlm(listOf(ApiMessage(role = "assistant", content = "done")))
        val engine = engineWith(echo, registry, llm)

        engine.runTurn(settings, turn, "go").toList()

        val announcement = llm.requests[0].messages
            .filter { it.role == "system" }
            .joinToString("\n") { it.content.orEmpty() }
        assertTrue(announcement.contains("Echo"))

        assertFalse(engine.snapshotHistory().any { it.role == "system" && it.content?.contains("deferred tools") == true })
    }
}
