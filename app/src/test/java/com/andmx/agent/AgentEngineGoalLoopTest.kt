package com.andmx.agent

import com.andmx.llm.ApiMessage
import com.andmx.llm.ChatRequest
import com.andmx.llm.LlmApi
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.settings.ProviderSettings
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZCode 3.11.x goal 自主交付循环：回合终答 → 独立 verifier 判定 →
 * 未过则注入 continuation 续跑，直到通过 / 预算耗尽 / 迭代上限。
 */
class AgentEngineGoalLoopTest {

    /**
     * Scripted LLM: verification requests（末条 user 含 "Verify whether the
     * active session goal"）按队列返回 JSON 判定，其余按队列返回普通答复。
     * 续跑注入消息（"Continue working toward the active session goal"）被记录。
     */
    private class ScriptedLlm(
        answers: List<String>,
        verdicts: List<String>,
    ) : LlmApi {
        var calls = 0
        var sawContinuation = false
        private val answerQueue = ArrayDeque(answers)
        private val verdictQueue = ArrayDeque(verdicts)

        override suspend fun chat(request: ChatRequest): Result<ApiMessage> {
            calls++
            val last = request.messages.lastOrNull()?.content.orEmpty()
            if (last.contains("Verify whether the active session goal")) {
                val v = verdictQueue.removeFirstOrNull()
                    ?: """{"passed": true, "reason": "default pass", "nextAction": ""}"""
                return Result.success(ApiMessage(role = "assistant", content = v))
            }
            if (last.contains("Continue working toward the active session goal")) {
                sawContinuation = true
            }
            return Result.success(
                ApiMessage(role = "assistant", content = answerQueue.removeFirstOrNull() ?: "done"),
            )
        }
    }

    private fun engineWithGoal(
        llm: LlmApi,
        goal: ConversationGoal,
        maxGoalIterations: Int = 20,
    ): Pair<AgentEngine, GoalToolState> {
        val state = GoalToolState()
        state.setGoal(goal)
        return AgentEngine(
            tools = emptyList(),
            client = llm,
            goalState = state,
            maxGoalIterations = maxGoalIterations,
        ) to state
    }

    private val turn = TurnContext(
        provider = ProviderDefinition(id = "test", name = "test", baseUrl = "http://x", apiKey = "x"),
        model = "test-model",
    )
    private val settings = ProviderSettings(model = "test-model")

    private fun activeGoal(
        text: String = "实现功能 X",
        budget: Int = 0,
        tokensUsed: Int = 0,
    ) = ConversationGoal(
        text = text,
        status = GoalStatus.ACTIVE,
        phase = GoalStatus.ACTIVE.toPhase(),
        tokenBudget = budget,
        tokensUsed = tokensUsed,
        startedAt = System.currentTimeMillis() - 5_000,
        updatedAt = System.currentTimeMillis(),
    )

    @Test
    fun verifierPasses_goalCompletes() = runTest {
        val llm = ScriptedLlm(
            answers = listOf("已全部完成"),
            verdicts = listOf("""{"passed": true, "reason": "需求均已交付", "nextAction": ""}"""),
        )
        val (engine, state) = engineWithGoal(llm, activeGoal())

        val events = engine.runTurn(settings, turn, "做功能 X").toList()

        assertEquals(2, llm.calls)
        val verifying = events.filterIsInstance<AgentEvent.GoalVerifying>()
        assertEquals(listOf(1), verifying.map { it.iteration })
        val verified = events.filterIsInstance<AgentEvent.GoalVerified>()
        assertEquals(1, verified.size)
        assertTrue(verified.first().passed)
        assertEquals(GoalStatus.COMPLETE, state.goal.status)
        assertEquals(1, state.goal.goalIteration)
        assertEquals("需求均已交付", state.goal.lastVerifyReason)
        assertTrue(state.goal.timeUsedSeconds >= 0)
        assertTrue(events.last() is AgentEvent.Done)
        assertFalse(llm.sawContinuation)
    }

    @Test
    fun verifierFails_continuationInjectedThenPasses() = runTest {
        val llm = ScriptedLlm(
            answers = listOf("第一版完成", "补上测试,完成"),
            verdicts = listOf(
                """{"passed": false, "reason": "缺少测试覆盖", "nextAction": "补单元测试"}""",
                """{"passed": true, "reason": "测试已补齐", "nextAction": ""}""",
            ),
        )
        val (engine, state) = engineWithGoal(llm, activeGoal())

        val events = engine.runTurn(settings, turn, "做功能 X").toList()

        assertEquals(4, llm.calls)
        assertTrue(llm.sawContinuation)
        val verified = events.filterIsInstance<AgentEvent.GoalVerified>()
        assertEquals(2, verified.size)
        assertFalse(verified[0].passed)
        assertEquals("补单元测试", verified[0].nextAction)
        assertTrue(verified[1].passed)
        assertEquals(GoalStatus.COMPLETE, state.goal.status)
        assertEquals(2, state.goal.goalIteration)
        assertTrue(events.last() is AgentEvent.Done)
    }

    @Test
    fun budgetExhausted_goalBudgetLimited() = runTest {
        val llm = ScriptedLlm(
            answers = listOf("完成了一部分"),
            verdicts = listOf("""{"passed": false, "reason": "还差一半", "nextAction": "继续"}"""),
        )
        val (engine, state) = engineWithGoal(
            llm,
            activeGoal(budget = 100, tokensUsed = 100),
        )

        val events = engine.runTurn(settings, turn, "做功能 X").toList()

        assertEquals(2, llm.calls)
        assertEquals(GoalStatus.BUDGET_LIMITED, state.goal.status)
        assertFalse(llm.sawContinuation)
        assertTrue(events.last() is AgentEvent.Done)
    }

    @Test
    fun iterationCapStopsLoop() = runTest {
        val llm = ScriptedLlm(
            answers = List(5) { "第 ${it + 1} 版" },
            verdicts = List(5) { """{"passed": false, "reason": "还不够", "nextAction": "继续"}""" },
        )
        val (engine, state) = engineWithGoal(llm, activeGoal(), maxGoalIterations = 2)

        val events = engine.runTurn(settings, turn, "做功能 X").toList()

        // 2 iterations: answer+verify ×2, then cap stops further continuation.
        assertEquals(4, llm.calls)
        assertEquals(2, state.goal.goalIteration)
        assertEquals(GoalStatus.ACTIVE, state.goal.status)
        assertTrue(events.last() is AgentEvent.Done)
    }

    @Test
    fun parseVerdict_fallbacks() {
        val verifier = GoalVerifier(ScriptedLlm(emptyList(), emptyList()))

        val plain = verifier.parseVerdict("""{"passed": false, "reason": "缺测试", "nextAction": "补"}""")
        assertFalse(plain.passed)
        assertEquals("缺测试", plain.reason)
        assertEquals("补", plain.nextAction)

        val fenced = verifier.parseVerdict("```json\n{\"passed\": true, \"reason\": \"ok\", \"nextAction\": \"\"}\n```")
        assertTrue(fenced.passed)

        val padded = verifier.parseVerdict("结论如下: {\"passed\": false, \"reason\": \"r\", \"nextAction\": \"a\"} 以上")
        assertFalse(padded.passed)
        assertEquals("r", padded.reason)

        val missingNextAction = verifier.parseVerdict("结论如下: {\"passed\": false, \"reason\": \"r\"} 以上")
        assertFalse(missingNextAction.passed)
        assertTrue(missingNextAction.reason.contains("did not return valid JSON"))

        val garbage = verifier.parseVerdict("not json at all")
        assertFalse(garbage.passed)
        assertTrue(garbage.reason.contains("did not return valid JSON"))
        assertFalse(garbage.nextAction.isBlank())

        val empty = verifier.parseVerdict("")
        assertFalse(empty.passed)
        assertTrue(empty.reason.contains("did not return valid JSON"))

        val missingFields = verifier.parseVerdict("""{"reason": "no passed flag"}""")
        assertFalse(missingFields.passed)
        assertTrue(missingFields.reason.contains("did not return valid JSON"))
    }

    private class ValidationTool(private val result: ToolResult) : Tool {
        override val name = "run_shell"
        override val description = "Test validation"
        override val parameters = kotlinx.serialization.json.JsonObject(emptyMap())
        override val risk = ToolRisk.EXECUTE
        var calls = 0
        override suspend fun execute(args: kotlinx.serialization.json.JsonObject): ToolResult {
            calls++
            assertEquals("true", args["strict_cwd"].toString())
            assertEquals("120000", args["timeout_ms"].toString())
            assertEquals("8000", args["max_output_chars"].toString())
            return result
        }
    }

    @Test
    fun successfulCommandsStillRequireModelVerification() = runTest {
        val tool = ValidationTool(ToolResult("[exit=0]"))
        val llm = ScriptedLlm(listOf("done"), emptyList())
        val state = GoalToolState().apply { setGoal(activeGoal().copy(validationCommands = listOf("test -f result"))) }
        var approvals = 0
        val engine = AgentEngine(tools = listOf(tool), client = llm, goalState = state,
            approve = { _, _ -> approvals++; ApprovalOutcome.AllowedOnce })
        engine.runTurn(settings, turn, "work").toList()
        assertEquals(1, tool.calls)
        assertEquals(1, approvals)
        assertEquals(2, llm.calls)
        assertEquals(GoalStatus.COMPLETE, state.goal.status)
    }

    @Test
    fun failedCommandsCannotCompleteGoal() = runTest {
        val tool = ValidationTool(ToolResult("[exit=1]", isError = true))
        val llm = ScriptedLlm(listOf("done"), emptyList())
        val state = GoalToolState().apply { setGoal(activeGoal().copy(validationCommands = listOf("false"))) }
        val engine = AgentEngine(tools = listOf(tool), client = llm, goalState = state, maxGoalIterations = 1)
        val events = engine.runTurn(settings, turn, "work").toList()
        assertEquals(1, tool.calls)
        assertEquals(1, llm.calls)
        assertEquals(GoalStatus.ACTIVE, state.goal.status)
        assertFalse(events.filterIsInstance<AgentEvent.GoalVerified>().single().passed)
    }

    @Test
    fun rejectedValidationNeverRunsCommand() = runTest {
        val tool = ValidationTool(ToolResult("[exit=0]"))
        val llm = ScriptedLlm(listOf("done"), emptyList())
        val state = GoalToolState().apply { setGoal(activeGoal().copy(validationCommands = listOf("test -f result"))) }
        val engine = AgentEngine(tools = listOf(tool), client = llm, goalState = state, maxGoalIterations = 1,
            approve = { _, _ -> ApprovalOutcome.Rejected("test refusal") })
        engine.runTurn(settings, turn, "work").toList()
        assertEquals(0, tool.calls)
        assertEquals(1, llm.calls)
        assertEquals(GoalStatus.ACTIVE, state.goal.status)
    }

    @Test
    fun noGoal_noVerification() = runTest {
        val llm = ScriptedLlm(answers = listOf("done"), verdicts = emptyList())
        val engine = AgentEngine(tools = emptyList(), client = llm)

        val events = engine.runTurn(settings, turn, "hello").toList()

        assertEquals(1, llm.calls)
        assertTrue(events.none { it is AgentEvent.GoalVerifying })
        assertTrue(events.last() is AgentEvent.Done)
    }

    @Test
    fun verifierFailure_turnEndsGoalStaysActive() = runTest {
        val llm = object : LlmApi {
            var calls = 0
            override suspend fun chat(request: ChatRequest): Result<ApiMessage> {
                calls++
                val last = request.messages.lastOrNull()?.content.orEmpty()
                if (last.contains("Verify whether the active session goal")) {
                    throw RuntimeException("network down")
                }
                return Result.success(ApiMessage(role = "assistant", content = "done"))
            }
        }
        val (engine, state) = engineWithGoal(llm, activeGoal())

        val events = engine.runTurn(settings, turn, "做功能 X").toList()

        assertEquals(2, llm.calls)
        assertEquals(GoalStatus.ACTIVE, state.goal.status)
        assertTrue(events.any { it is AgentEvent.Failed })
        assertTrue(events.last() is AgentEvent.Done)
    }
}
