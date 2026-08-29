package com.andmx.agent

import com.andmx.llm.ApiFunctionCall
import com.andmx.llm.ApiMessage
import com.andmx.llm.ApiToolCall
import com.andmx.llm.ChatRequest
import com.andmx.llm.LlmApi
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.settings.ProviderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine-level behavior of the tool guards: a declared deadline, a refused or
 * unanswerable approval, and the repeat reminder all reach the model as
 * distinct facts it can act on.
 */
class AgentEngineToolGuardTest {

    private val turn = TurnContext(
        provider = ProviderDefinition(id = "test", name = "test", baseUrl = "http://x", apiKey = "x"),
        model = "test-model",
    )

    private val settings = ProviderSettings(model = "test-model")

    /** Replays a scripted assistant message per request, recording every one. */
    private class ScriptedLlm(private val script: List<ApiMessage>) : LlmApi {
        val requests = mutableListOf<ChatRequest>()
        private var index = 0
        override suspend fun chat(request: ChatRequest): Result<ApiMessage> {
            requests += request
            val msg = script.getOrNull(index++) ?: ApiMessage(role = "assistant", content = "done")
            return Result.success(msg)
        }
    }

    private fun toolCall(name: String, args: String, id: String = "c1") =
        ApiMessage(role = "assistant", toolCalls = listOf(ApiToolCall(id = id, function = ApiFunctionCall(name, args))))

    private fun finished(events: List<AgentEvent>): AgentEvent.ToolFinished =
        events.filterIsInstance<AgentEvent.ToolFinished>().first()

    @Test
    fun aDeclaredDeadlineBecomesAnErrorResult() = runTest {
        val slow = object : Tool {
            override val name = "slow"
            override val description = "never finishes"
            override val parameters: JsonObject = buildJsonObject { put("type", "object") }
            override val timeoutMs: Long = 50
            override suspend fun execute(args: JsonObject): ToolResult {
                delay(10_000)
                return ToolResult("done")
            }
        }
        val llm = ScriptedLlm(listOf(toolCall("slow", "{}")))
        val engine = AgentEngine(tools = listOf(slow), client = llm)

        val events = engine.runTurn(settings, turn, "go").toList()
        val result = finished(events)

        assertTrue("超时必须是错误结果", result.isError)
        assertTrue(result.output.contains("timed out after 50ms"))
    }

    @Test
    fun stoppingTheTurnIsNotReportedAsATimeout() = runTest {
        val hanging = object : Tool {
            override val name = "slow"
            override val description = "never finishes"
            override val parameters: JsonObject = buildJsonObject { put("type", "object") }
            override val timeoutMs: Long = 60_000
            override suspend fun execute(args: JsonObject): ToolResult {
                delay(10_000)
                return ToolResult("done")
            }
        }
        val llm = ScriptedLlm(listOf(toolCall("slow", "{}")))
        val engine = AgentEngine(tools = listOf(hanging), client = llm)

        var reportedAsTimeout = false
        try {
            engine.runTurn(settings, turn, "go").collect { event ->
                if (event is AgentEvent.ToolFinished && event.output.contains("timed out")) {
                    reportedAsTimeout = true
                }
                if (event is AgentEvent.ToolStarted) throw CancellationException("user stopped")
            }
        } catch (c: CancellationException) {
            // expected: the stop travels out as a cancellation
        }
        assertFalse("停止不应被当成工具超时反馈给模型", reportedAsTimeout)
    }

    @Test
    fun anUnanswerableApprovalDoesNotReadAsAUserRefusal() = runTest {
        val tool = object : Tool {
            var invoked = false
            override val name = "probe"
            override val description = "probe"
            override val parameters: JsonObject = buildJsonObject { put("type", "object") }
            override suspend fun execute(args: JsonObject): ToolResult {
                invoked = true
                return ToolResult("ran")
            }
        }
        val llm = ScriptedLlm(listOf(toolCall("probe", "{}")))
        val engine = AgentEngine(
            tools = listOf(tool),
            client = llm,
            approve = { _, _ -> ApprovalOutcome.Unavailable("会话不可用") },
        )

        val result = finished(engine.runTurn(settings, turn, "go").toList())

        assertTrue(result.isError)
        assertTrue(result.output.contains("无法获得执行授权"))
        assertFalse(result.output.contains("拒绝"))
        assertFalse("没有审批方就不该执行", tool.invoked)
    }

    @Test
    fun aRefusalNamesTheRefusal() = runTest {
        val tool = object : Tool {
            override val name = "probe"
            override val description = "probe"
            override val parameters: JsonObject = buildJsonObject { put("type", "object") }
            override suspend fun execute(args: JsonObject): ToolResult = ToolResult("ran")
        }
        val llm = ScriptedLlm(listOf(toolCall("probe", "{}")))
        val engine = AgentEngine(
            tools = listOf(tool),
            client = llm,
            approve = { _, _ -> ApprovalOutcome.Rejected("只读模式") },
        )

        val result = finished(engine.runTurn(settings, turn, "go").toList())

        assertTrue(result.output.contains("已被拒绝执行"))
        assertTrue(result.output.contains("只读模式"))
    }

    @Test
    fun theThirdIdenticalCallDrawsAReminder() = runTest {
        val tool = object : Tool {
            override val name = "grep"
            override val description = "grep"
            override val parameters: JsonObject = buildJsonObject { put("type", "object") }
            override suspend fun execute(args: JsonObject): ToolResult = ToolResult("no match")
        }
        val script = (1..4).map { toolCall("grep", "{\"pattern\":\"a\"}", id = "c$it") } +
            ApiMessage(role = "assistant", content = "done")
        val llm = ScriptedLlm(script)
        val engine = AgentEngine(tools = listOf(tool), client = llm)

        engine.runTurn(settings, turn, "find it").toList()

        fun requestMentionsRepeat(index: Int) = llm.requests.getOrNull(index)
            ?.messages?.any { it.role == "system" && it.content?.contains("repeating the exact same tool call") == true }
            ?: false

        assertFalse("第一次调用不该提醒", requestMentionsRepeat(1))
        assertFalse("第二次调用不该提醒", requestMentionsRepeat(2))
        assertTrue("第三次重复调用后应注入提醒", requestMentionsRepeat(3))
    }

    @Test
    fun callsThatKeepBeingRefusedStillCountAsALoop() = runTest {
        val tool = object : Tool {
            override val name = "bash"
            override val description = "bash"
            override val parameters: JsonObject = buildJsonObject { put("type", "object") }
            override suspend fun execute(args: JsonObject): ToolResult = ToolResult("ran")
        }
        val script = (1..4).map { toolCall("bash", "{\"command\":\"rm -rf /\"}", id = "c$it") } +
            ApiMessage(role = "assistant", content = "done")
        val llm = ScriptedLlm(script)
        val engine = AgentEngine(
            tools = listOf(tool),
            client = llm,
            approve = { _, _ -> ApprovalOutcome.Rejected("只读模式") },
        )

        engine.runTurn(settings, turn, "do it").toList()

        val reminded = llm.requests.any { req ->
            req.messages.any { it.role == "system" && it.content?.contains("repeating the exact same tool call") == true }
        }
        assertTrue("反复撞墙的调用也应被识别为循环", reminded)
    }

    @Test
    fun aDifferentCallStartsAFreshChain() = runTest {
        val tool = object : Tool {
            override val name = "grep"
            override val description = "grep"
            override val parameters: JsonObject = buildJsonObject { put("type", "object") }
            override suspend fun execute(args: JsonObject): ToolResult = ToolResult("no match")
        }
        val script = listOf(
            toolCall("grep", "{\"pattern\":\"a\"}", id = "c1"),
            toolCall("grep", "{\"pattern\":\"a\"}", id = "c2"),
            toolCall("grep", "{\"pattern\":\"b\"}", id = "c3"),
            toolCall("grep", "{\"pattern\":\"b\"}", id = "c4"),
            ApiMessage(role = "assistant", content = "done"),
        )
        val llm = ScriptedLlm(script)
        val engine = AgentEngine(tools = listOf(tool), client = llm)

        engine.runTurn(settings, turn, "find it").toList()

        val reminded = llm.requests.any { req ->
            req.messages.any { it.role == "system" && it.content?.contains("repeating the exact same tool call") == true }
        }
        assertFalse("参数变化后计数应归零", reminded)
    }

    @Test
    fun theDefaultGateRunsToolsWithoutAsking() = runTest {
        val tool = object : Tool {
            var invoked = false
            override val name = "probe"
            override val description = "probe"
            override val parameters: JsonObject = buildJsonObject { put("type", "object") }
            override suspend fun execute(args: JsonObject): ToolResult {
                invoked = true
                return ToolResult("ran")
            }
        }
        val llm = ScriptedLlm(listOf(toolCall("probe", "{}")))
        val engine = AgentEngine(tools = listOf(tool), client = llm)

        val events = engine.runTurn(settings, turn, "go").toList()

        assertTrue(tool.invoked)
        assertEquals("ran", finished(events).output)
    }
}
