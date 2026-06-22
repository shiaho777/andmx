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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the agent orchestration: model requests a tool, the engine runs it,
 * feeds the result back, and the model then answers in plain text.
 */
class AgentEngineTest {

    /** A tool that echoes its `text` argument back, recording invocation. */
    private class EchoTool : Tool {
        var invokedWith: String? = null
        override val name = "echo"
        override val description = "echo"
        override val parameters: JsonObject = buildJsonObject { put("type", "object") }
        override suspend fun execute(args: JsonObject): ToolResult {
            invokedWith = args["text"]?.jsonPrimitive?.content
            return ToolResult("echoed:${invokedWith}")
        }
    }

    /** Scripted LLM: first turn asks for the tool, second turn answers. */
    private class ScriptedLlm : LlmApi {
        var calls = 0
        override suspend fun chat(request: ChatRequest): Result<ApiMessage> {
            calls++
            return if (calls == 1) {
                Result.success(
                    ApiMessage(
                        role = "assistant",
                        toolCalls = listOf(
                            ApiToolCall(id = "c1", function = ApiFunctionCall("echo", "{\"text\":\"hi\"}")),
                        ),
                    ),
                )
            } else {
                // Ensure the tool result was fed back into the history.
                val sawToolResult = request.messages.any { it.role == "tool" && it.content?.contains("echoed:hi") == true }
                Result.success(
                    ApiMessage(role = "assistant", content = if (sawToolResult) "完成: 工具返回 echoed:hi" else "缺少工具结果"),
                )
            }
        }
    }

    @Test
    fun runsToolThenAnswers() = runTest {
        val tool = EchoTool()
        val llm = ScriptedLlm()
        val engine = AgentEngine(tools = listOf(tool), client = llm)
        val turn = TurnContext(
            provider = ProviderDefinition(id = "test", name = "test", baseUrl = "http://x", apiKey = "x"),
            model = "test-model",
        )

        val events = engine.runTurn(ProviderSettings(model = "test-model"), turn, "请回显 hi").toList()

        assertEquals("hi", tool.invokedWith)

        val started = events.filterIsInstance<AgentEvent.ToolStarted>()
        assertEquals(1, started.size)
        assertEquals("echo", started.first().name)

        val finished = events.filterIsInstance<AgentEvent.ToolFinished>()
        assertEquals("echoed:hi", finished.first().output)

        val answer = events.filterIsInstance<AgentEvent.Assistant>().last().text
        assertTrue("应基于工具结果作答: $answer", answer.contains("echoed:hi"))

        assertTrue(events.last() is AgentEvent.Done)
        assertEquals(2, llm.calls)
    }
}
