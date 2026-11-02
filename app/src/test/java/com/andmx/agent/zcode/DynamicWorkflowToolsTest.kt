package com.andmx.agent.zcode

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andmx.agent.AgentEngine
import com.andmx.agent.AgentEvent
import com.andmx.agent.GoalToolState
import com.andmx.agent.Tool
import com.andmx.agent.ToolDependency
import com.andmx.agent.ToolResult
import com.andmx.agent.ToolRisk
import com.andmx.agent.ToolScheduler
import com.andmx.agent.UpdatePlanTool
import com.andmx.agent.WebSearchTool
import com.andmx.agent.toolScheduleDependency
import com.andmx.agent.turn.TurnMachine
import com.andmx.agent.turn.TurnPhase
import com.andmx.agent.workflow.EscalateTool
import com.andmx.agent.workflow.SubmitResultTool
import com.andmx.data.session.SessionEventType
import com.andmx.exec.policy.NetworkPolicy
import com.andmx.llm.ApiFunctionCall
import com.andmx.llm.ApiMessage
import com.andmx.llm.ApiToolCall
import com.andmx.llm.ChatRequest
import com.andmx.llm.LlmApi
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.settings.ProviderSettings
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DynamicWorkflowToolsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun dynamicNamesFollowTheRuntimeGate() {
        assertFalse(DYNAMIC_WORKFLOW_TOOLS_DEFAULT)
        assertTrue(DYNAMIC_WORKFLOW_DEFAULT_PIN.contains("defaults the tools on when the flag is absent"))
        assertTrue(DYNAMIC_WORKFLOW_DEFAULT_PIN.contains("fail-closed"))
        assertTrue(DYNAMIC_WORKFLOW_DEFAULT_PIN.contains("resolves it at runtime from settings"))
        val declared = WorkflowTools().all() + ListModelsTool({ emptyList() }, { "" to "" })
        val off = applyDynamicWorkflowGate(declared)
        assertTrue(DYNAMIC_WORKFLOW_TOOL_NAMES.none { name -> off.any { it.name == name } })
        val on = applyDynamicWorkflowGate(declared, enabled = true)
        assertEquals(DYNAMIC_WORKFLOW_TOOL_NAMES, on.map { it.name }.toSet())
        assertFalse(agentProviderDescription(dynamicWorkflowEnabled = false).contains("CreateWorkflow"))
        assertTrue(agentProviderDescription(dynamicWorkflowEnabled = true).contains("CreateWorkflow"))
        assertFalse(AGENT_PROVIDER_DESCRIPTION.contains("CreateWorkflow"))
        assertFalse(taskProviderDescription(dynamicWorkflowEnabled = false).contains("CreateWorkflow"))
        assertTrue(
            taskProviderDescription(dynamicWorkflowEnabled = true)
                .contains("Claude Code-compatible alias for the Agent tool"),
        )
    }

    @Test
    fun introspectionToolsDegradeWhenNoRunPortIsInjected() = runTest {
        val byName = WorkflowTools().all().associateBy { it.name }
        val listRuns = byName.getValue(LIST_WORKFLOW_RUNS_TOOL_NAME).execute(buildJsonObject { })
        assertTrue(listRuns.isError)
        assertEquals(WORKFLOW_INTROSPECTION_UNAVAILABLE, listRuns.output)
        val getRun = byName.getValue(GET_WORKFLOW_RUN_TOOL_NAME).execute(
            buildJsonObject { put("run_id", "wf_missing") },
        )
        assertTrue(getRun.isError)
        assertEquals(WORKFLOW_INTROSPECTION_UNAVAILABLE, getRun.output)
        val listSaved = byName.getValue(LIST_SAVED_WORKFLOWS_TOOL_NAME).execute(buildJsonObject { })
        assertTrue(listSaved.isError)
        assertEquals(WORKFLOW_SAVED_LIST_UNAVAILABLE, listSaved.output)
    }

    @Test
    fun submitResultStopsTheTurnAndBashDoesNot() {
        val submit = SubmitResultTool(AtomicReference(null))
        val bash = bash()
        assertTrue(submit.stopTurnOnSuccess)
        assertFalse(bash.stopTurnOnSuccess)
        assertEquals(SUBMIT_RESULT_TOOL_NAME, submit.name)
        WorkflowTools().all().forEach { assertFalse(it.stopTurnOnSuccess) }
        assertFalse(EscalateTool { _, _ -> "ok" }.stopTurnOnSuccess)
    }

    @Test
    fun declarationsMatchTheUpstreamCapabilities() {
        val tools = WorkflowTools().all().associateBy { it.name }
        assertCapability(tools.getValue(CREATE_WORKFLOW_TOOL_NAME), readOnly = false, concurrent = true, approval = true, timeout = 15_000L, bytes = 24_000, scope = "none", risk = "low", permission = "createWorkflow", alwaysAsk = true)
        assertCapability(tools.getValue(AMEND_WORKFLOW_TOOL_NAME), readOnly = false, concurrent = true, approval = true, timeout = 15_000L, bytes = 24_000, scope = "none", risk = "low", permission = "createWorkflow", alwaysAsk = true)
        assertCapability(tools.getValue(SAVE_WORKFLOW_TOOL_NAME), readOnly = false, concurrent = false, approval = true, timeout = 15_000L, bytes = 24_000, scope = "workspace", risk = "medium", permission = "saveWorkflow", alwaysAsk = true)
        assertCapability(tools.getValue(EVAL_WORKFLOW_SNIPPET_TOOL_NAME), readOnly = false, concurrent = true, approval = true, timeout = 660_000L, bytes = 24_000, scope = "workspace", risk = "low", permission = "evalWorkflowSnippet", alwaysAsk = false)
        assertCapability(tools.getValue(LIST_WORKFLOW_RUNS_TOOL_NAME), readOnly = true, concurrent = true, approval = false, timeout = 10_000L, bytes = 24_000, scope = "none", risk = "low", permission = "listWorkflowRuns", alwaysAsk = false)
        assertCapability(tools.getValue(GET_WORKFLOW_RUN_TOOL_NAME), readOnly = true, concurrent = true, approval = false, timeout = 10_000L, bytes = 400_000, scope = "none", risk = "low", permission = "getWorkflowRun", alwaysAsk = false)
        assertCapability(tools.getValue(RESUME_WORKFLOW_RUN_TOOL_NAME), readOnly = false, concurrent = true, approval = false, timeout = 15_000L, bytes = 24_000, scope = "none", risk = "low", permission = "resumeWorkflowRun", alwaysAsk = false)
        assertCapability(tools.getValue(RESOLVE_WORKFLOW_QUESTION_TOOL_NAME), readOnly = false, concurrent = true, approval = false, timeout = 15_000L, bytes = 24_000, scope = "none", risk = "low", permission = "resolveWorkflowQuestion", alwaysAsk = false)
        assertCapability(tools.getValue(FILL_WORKFLOW_HOLE_TOOL_NAME), readOnly = false, concurrent = true, approval = true, timeout = 15_000L, bytes = 24_000, scope = "none", risk = "low", permission = "createWorkflow", alwaysAsk = true)
        assertCapability(tools.getValue(LIST_SAVED_WORKFLOWS_TOOL_NAME), readOnly = true, concurrent = true, approval = false, timeout = 10_000L, bytes = 24_000, scope = "none", risk = "low", permission = "listSavedWorkflows", alwaysAsk = false)
        val submit = SubmitResultTool(AtomicReference(null))
        assertCapability(submit, readOnly = false, concurrent = false, approval = false, timeout = null, bytes = 16_000, scope = "session", risk = "low", permission = "workflow.submitResult", alwaysAsk = false)
        val escalate = EscalateTool { _, _ -> "ok" }
        assertCapability(escalate, readOnly = false, concurrent = true, approval = false, timeout = null, bytes = 16_000, scope = "session", risk = "low", permission = "workflow.escalate", alwaysAsk = false)
        assertEquals("https://json-schema.org/draft/2020-12/schema", tools.getValue(CREATE_WORKFLOW_TOOL_NAME).parameters["\$schema"]!!.jsonPrimitive.content)
        assertEquals("false", tools.getValue(CREATE_WORKFLOW_TOOL_NAME).parameters["additionalProperties"]!!.jsonPrimitive.content)
        assertNull(tools.getValue(CREATE_WORKFLOW_TOOL_NAME).parameters["required"])
        assertEquals(listOf("run_id"), required(tools.getValue(AMEND_WORKFLOW_TOOL_NAME)))
        assertEquals(listOf("name", "description", "scope"), required(tools.getValue(SAVE_WORKFLOW_TOOL_NAME)))
        assertEquals(listOf("run_id", "hole_id"), required(tools.getValue(FILL_WORKFLOW_HOLE_TOOL_NAME)))
        assertEquals(listOf("question_id", "answer"), required(tools.getValue(RESOLVE_WORKFLOW_QUESTION_TOOL_NAME)))
        assertEquals(listOf("result"), required(submit))
        assertEquals(listOf("question"), required(escalate))
        assertEquals(600_000L, bash().resolveTimeoutMs(buildJsonObject { put("timeout", 900_000) }))
    }

    @Test
    fun enabledDeclarationsDoNotClaimARunExecuted() = runTest {
        val create = WorkflowTools(hasLoadedSkill = { true }).all().first { it.name == CREATE_WORKFLOW_TOOL_NAME }
        val result = create.execute(buildJsonObject { put("script", "export {}") })
        assertTrue(result.isError)
        // 没有分析器的会话必须失败关闭：编不过的检查不能因为少了个依赖就变成默认通过。
        assertTrue(result.output.contains("no workflow script analyzer"))
        assertTrue(result.output.contains("Nothing was executed"))
        assertFalse(result.output.contains("Workflow run started"))
        assertFalse(result.output.contains("backgroundTaskId"))
        val runs = WorkflowTools().all().first { it.name == LIST_WORKFLOW_RUNS_TOOL_NAME }
        val listed = runs.execute(buildJsonObject { })
        assertTrue(listed.isError)
        assertEquals(WORKFLOW_INTROSPECTION_UNAVAILABLE, listed.output)
    }

    @Test
    fun nonConcurrentToolIsNotScheduledBesideAnotherCall() {
        val search = WebSearchTool(NetworkPolicy())
        val dependency = toolScheduleDependency("search", search.name, search)
        assertEquals(true, dependency.readOnly)
        assertEquals(true, dependency.concurrentSafe)
        assertEquals("network", dependency.sideEffectScope)
        val unsafe = object : Tool {
            override val name = "Edit"
            override val description = ""
            override val parameters = buildJsonObject { }
            override val risk = ToolRisk.WRITE
            override val readOnly = false
            override val concurrentSafe = false
            override val sideEffectScope = "workspace"
            override suspend fun execute(args: JsonObject) = ToolResult("ok")
        }
        val scheduled = ToolScheduler().schedule(
            listOf(
                toolScheduleDependency("a", search.name, search),
                toolScheduleDependency("c", search.name, search),
                toolScheduleDependency("b", unsafe.name, unsafe),
            ),
        )
        assertTrue(scheduled.parallelGroups.none { it.size > 1 && "b" in it })
        assertEquals(listOf(listOf("a", "c"), listOf("b")), scheduled.parallelGroups)
        val namedOnly = ToolScheduler().schedule(
            listOf(
                ToolDependency(toolCallId = "a", toolName = "Read", concurrentSafe = false),
                ToolDependency(toolCallId = "b", toolName = "Grep", concurrentSafe = true),
            ),
        )
        assertEquals(listOf(listOf("a"), listOf("b")), namedOnly.parallelGroups)
    }

    @Test
    fun timeoutOfOneCallLeavesTheTurnAbleToTakeTheNextResult() = runTest {
        val slow = object : Tool {
            override val name = "slow"
            override val description = ""
            override val parameters = buildJsonObject { }
            override val concurrentSafe = false
            override val timeoutMs = 50L
            override suspend fun execute(args: JsonObject): ToolResult {
                delay(10_000)
                return ToolResult("late")
            }
        }
        val next = object : Tool {
            override val name = "next"
            override val description = ""
            override val parameters = buildJsonObject { }
            override val concurrentSafe = false
            override suspend fun execute(args: JsonObject) = ToolResult("next-ok")
        }
        var calls = 0
        val llm = object : LlmApi {
            override suspend fun chat(request: ChatRequest): Result<ApiMessage> {
                calls += 1
                return Result.success(
                    if (calls == 1) {
                        ApiMessage(
                            role = "assistant",
                            toolCalls = listOf(
                                ApiToolCall(id = "c0", function = ApiFunctionCall("slow", "{}")),
                                ApiToolCall(id = "c1", function = ApiFunctionCall("next", "{}")),
                            ),
                        )
                    } else {
                        ApiMessage(role = "assistant", content = "done")
                    },
                )
            }
        }
        val errors = mutableListOf<String>()
        val events = AgentEngine(
            tools = listOf(slow, next),
            client = llm,
            sessionEvents = { type, payload ->
                if (type == SessionEventType.TOOL_CALL_ERROR) {
                    errors += payload["errorType"]?.jsonPrimitive?.content.orEmpty()
                }
            },
        ).runTurn(
            ProviderSettings(model = "m"),
            com.andmx.agent.TurnContext(
                provider = ProviderDefinition(id = "t", name = "t", baseUrl = "http://x", apiKey = "x"),
                model = "m",
            ),
            "go",
        ).toList()
        assertEquals(2, calls)
        assertEquals(listOf("tool_timeout"), errors)
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>()
        assertTrue(finished.single { it.name == "slow" }.isError)
        assertTrue(finished.single { it.name == "slow" }.output.contains("timed out"))
        assertEquals("next-ok", finished.single { it.name == "next" }.output)
        assertFalse(finished.single { it.name == "next" }.isError)
        assertTrue(events.none { it is AgentEvent.Failed })
        assertTrue(events.last() is AgentEvent.Done)
        val machine = TurnMachine.create("s", 1, "go", "trace", "turn")
        machine.start()
        machine.beginModelRequest("m")
        machine.scheduleTools(listOf("c0" to "slow", "c1" to "next"))
        machine.startToolExecution()
        machine.completeTool("c0", success = false)
        val accepted = machine.completeTool("c1", success = true, content = "next-ok")
        assertEquals(TurnPhase.EXECUTING_TOOLS, accepted.phase)
        assertEquals("failed", accepted.toolCalls.single { it.id == "c0" }.status)
        assertEquals("completed", accepted.toolCalls.single { it.id == "c1" }.status)
        assertEquals("next-ok", accepted.toolCalls.single { it.id == "c1" }.content)
        machine.aggregateResults()
        assertEquals(TurnPhase.AGGREGATING_RESULTS, machine.state.phase)
    }

    @Test
    fun outputAboveTheBudgetIsKept() = runTest {
        val huge = "x".repeat(4_000)
        val fat = object : Tool {
            override val name = "fat"
            override val description = ""
            override val parameters = buildJsonObject { }
            override val maxOutputBytes = 64
            override val concurrentSafe = true
            override suspend fun execute(args: JsonObject) = ToolResult(huge)
        }
        var second: ChatRequest? = null
        var calls = 0
        val llm = object : LlmApi {
            override suspend fun chat(request: ChatRequest): Result<ApiMessage> {
                calls += 1
                if (calls == 2) second = request
                return Result.success(
                    if (calls == 1) {
                        ApiMessage(
                            role = "assistant",
                            toolCalls = listOf(ApiToolCall(id = "c0", function = ApiFunctionCall("fat", "{}"))),
                        )
                    } else {
                        ApiMessage(role = "assistant", content = "done")
                    },
                )
            }
        }
        val engine = AgentEngine(tools = listOf(fat), client = llm)
        val events = engine.runTurn(
            ProviderSettings(model = "m"),
            com.andmx.agent.TurnContext(
                provider = ProviderDefinition(id = "t", name = "t", baseUrl = "http://x", apiKey = "x"),
                model = "m",
            ),
            "go",
        ).toList()
        val fed = second?.messages?.single { it.role == "tool" && it.toolCallId == "c0" }?.content.orEmpty()
        assertTrue(fed.isNotEmpty())
        assertTrue(fed.contains("persisted-output") || fed.contains("resultBudget"))
        assertFalse(fed == huge)
        assertTrue(events.none { it is AgentEvent.Failed })
        assertEquals(huge, events.filterIsInstance<AgentEvent.ToolFinished>().single().output)
    }

    @Test
    fun serialToolDoesNotOverlapANeighbor() = runTest {
        val overlap = AtomicInteger(0)
        val maxOverlap = AtomicInteger(0)
        fun tool(toolName: String, safe: Boolean) = object : Tool {
            override val name = toolName
            override val description = ""
            override val parameters = buildJsonObject { }
            override val concurrentSafe = safe
            override suspend fun execute(args: JsonObject): ToolResult {
                val now = overlap.incrementAndGet()
                maxOverlap.updateAndGet { current -> maxOf(current, now) }
                delay(30)
                overlap.decrementAndGet()
                return ToolResult(toolName)
            }
        }
        val llm = object : LlmApi {
            private var calls = 0
            override suspend fun chat(request: ChatRequest): Result<ApiMessage> {
                calls += 1
                return Result.success(
                    if (calls == 1) {
                        ApiMessage(
                            role = "assistant",
                            toolCalls = listOf(
                                ApiToolCall(id = "c0", function = ApiFunctionCall("safe", "{}")),
                                ApiToolCall(id = "c1", function = ApiFunctionCall("unsafe", "{}")),
                            ),
                        )
                    } else {
                        ApiMessage(role = "assistant", content = "done")
                    },
                )
            }
        }
        AgentEngine(
            tools = listOf(tool("safe", true), tool("unsafe", false)),
            client = llm,
        ).runTurn(
            ProviderSettings(model = "m"),
            com.andmx.agent.TurnContext(
                provider = ProviderDefinition(id = "t", name = "t", baseUrl = "http://x", apiKey = "x"),
                model = "m",
            ),
            "go",
        ).toList()
        assertEquals(1, maxOverlap.get())
    }

    private fun bash(): Tool = buildZCodeToolSurface(
        context = context,
        networkPolicy = NetworkPolicy(),
        planTool = UpdatePlanTool(),
        goalState = GoalToolState(),
        todo = TodoState(),
        planMode = PlanModeState(),
        cwdProvider = { "/root" },
        includeLegacyAliases = false,
    ).single { it.name == "Bash" }

    private fun required(tool: Tool): List<String> =
        tool.parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun assertCapability(
        tool: Tool,
        readOnly: Boolean,
        concurrent: Boolean,
        approval: Boolean,
        timeout: Long?,
        bytes: Int,
        scope: String,
        risk: String,
        permission: String,
        alwaysAsk: Boolean,
    ) {
        assertEquals(readOnly, tool.readOnly)
        assertEquals(false, tool.destructive)
        assertEquals(concurrent, tool.concurrentSafe)
        assertEquals(approval, tool.needsApproval)
        assertEquals(timeout, tool.timeoutMs)
        assertEquals(bytes, tool.maxOutputBytes)
        assertEquals(scope, tool.sideEffectScope)
        assertEquals(risk, tool.riskLevel)
        assertEquals(permission, tool.permissionName)
        assertEquals(alwaysAsk, tool.alwaysAsk)
    }
}
