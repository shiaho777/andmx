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

/**
 * turnSteer（ZCode 对齐）：运行中的 turn 在下一次模型请求前能看到
 * [AgentEngine.injectUserMessage] 注入的引导消息；空白注入被忽略。
 */
class AgentEngineSteerTest {

    /** 无操作工具：让首轮带工具调用，从而进入第二步请求。 */
    private class NoopTool : Tool {
        override val name = "noop"
        override val description = "does nothing"
        override val parameters: JsonObject = buildJsonObject { put("type", "object") }
        override suspend fun execute(args: JsonObject): ToolResult = ToolResult("ok")
    }

    /** 首轮请求进行中注入引导消息，随后请求一个工具调用；第二步记录 user 消息。 */
    private class SteerableLlm(
        private val engineProvider: () -> AgentEngine?,
        private val injectText: String,
    ) : LlmApi {
        val userTexts = mutableListOf<List<String?>>()
        var calls = 0
        override suspend fun chat(request: ChatRequest): Result<ApiMessage> {
            calls++
            userTexts += request.messages.filter { it.role == "user" }.map { it.content }
            if (calls == 1) {
                engineProvider()?.injectUserMessage(injectText)
                return Result.success(
                    ApiMessage(
                        role = "assistant",
                        toolCalls = listOf(ApiToolCall(id = "c1", function = ApiFunctionCall("noop", "{}"))),
                    ),
                )
            }
            return Result.success(ApiMessage(role = "assistant", content = "完成"))
        }
    }

    private fun newTurn() = TurnContext(
        provider = ProviderDefinition(id = "test", name = "test", baseUrl = "http://x", apiKey = "x"),
        model = "test-model",
    )

    @Test
    fun injectedMessageIsSeenByNextModelStep() = runTest {
        var engine: AgentEngine? = null
        val llm = SteerableLlm({ engine }, "优先处理测试目录")
        val realEngine = AgentEngine(tools = listOf(NoopTool()), client = llm)
        engine = realEngine

        val events = realEngine.runTurn(ProviderSettings(model = "test-model"), newTurn(), "开始任务").toList()

        assertTrue(events.last() is AgentEvent.Done)
        assertEquals(2, llm.calls)
        val second = llm.userTexts[1]
        assertTrue(
            "第二步应看到注入的引导消息: $second",
            second.any { it?.contains("优先处理测试目录") == true },
        )
    }

    @Test
    fun blankInjectionIsIgnored() = runTest {
        var engine: AgentEngine? = null
        val llm = SteerableLlm({ engine }, "   ")
        val realEngine = AgentEngine(tools = listOf(NoopTool()), client = llm)
        engine = realEngine

        val events = realEngine.runTurn(ProviderSettings(model = "test-model"), newTurn(), "开始").toList()

        assertTrue(events.last() is AgentEvent.Done)
        assertEquals(2, llm.calls)
        llm.userTexts.forEach { users ->
            assertEquals("空白注入不应产生额外 user 消息", 1, users.size)
        }
    }
}
