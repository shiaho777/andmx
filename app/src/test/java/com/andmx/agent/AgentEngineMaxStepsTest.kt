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
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEngineMaxStepsTest {

    private class NoopTool : Tool {
        override val name = "noop"
        override val description = "does nothing"
        override val parameters: JsonObject = buildJsonObject { put("type", "object") }
        override suspend fun execute(args: JsonObject): ToolResult = ToolResult("ok")
    }

    private class EndlessToolLlm : LlmApi {
        var calls = 0
        override suspend fun chat(request: ChatRequest): Result<ApiMessage> {
            calls += 1
            return Result.success(
                ApiMessage(
                    role = "assistant",
                    toolCalls = listOf(
                        ApiToolCall(id = "c$calls", function = ApiFunctionCall("noop", "{}")),
                    ),
                ),
            )
        }
    }

    @Test
    fun maxStepsStopsTheLoopBeforeAnotherModelCall() = runTest {
        val llm = EndlessToolLlm()
        val engine = AgentEngine(
            tools = listOf(NoopTool()),
            client = llm,
            maxSteps = 2,
        )
        val turn = TurnContext(
            provider = ProviderDefinition(id = "test", name = "test", baseUrl = "http://x", apiKey = "x"),
            model = "test-model",
        )

        val events = engine.runTurn(ProviderSettings(model = "test-model"), turn, "go").toList()

        assertEquals(2, llm.calls)
        val failure = events.filterIsInstance<AgentEvent.Failed>().single()
        assertEquals("error_max_turns", failure.errorType)
        assertTrue(failure.message.contains("maxTurns=2"))
    }
}
