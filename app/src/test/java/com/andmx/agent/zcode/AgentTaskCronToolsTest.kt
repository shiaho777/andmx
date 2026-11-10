package com.andmx.agent.zcode

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andmx.agent.GoalToolState
import com.andmx.agent.JsReplTool
import com.andmx.agent.Tool
import com.andmx.agent.TurnContext
import com.andmx.agent.UpdatePlanTool
import com.andmx.agent.multi.SendMessageTool
import com.andmx.agent.multi.SubAgentOrchestrator
import com.andmx.agent.multi.TaskOutputTool
import com.andmx.agent.multi.TaskStopTool
import com.andmx.agent.multi.ZCodeAgentTool
import com.andmx.agent.multi.ZCodeTaskTool
import com.andmx.exec.policy.NetworkPolicy
import com.andmx.llm.ApiMessage
import com.andmx.llm.ChatRequest
import com.andmx.llm.LlmApi
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.settings.CustomSubAgent
import com.andmx.settings.ProviderSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.booleanOrNull
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
class AgentTaskCronToolsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun schemaAndCapabilityMatchUpstream() {
        val tools = portedTools()
        val agent = tools.single { it.name == "Agent" }
        val task = tools.single { it.name == "Task" }
        val output = tools.single { it.name == "TaskOutput" }
        val stop = tools.single { it.name == "TaskStop" }
        val send = tools.single { it.name == "SendMessage" }
        val create = tools.single { it.name == "CronCreate" }
        val list = tools.single { it.name == "CronList" }
        val update = tools.single { it.name == "CronUpdate" }
        val delete = tools.single { it.name == "CronDelete" }
        val pause = tools.single { it.name == "CronPauseSelf" }

        assertEquals(AGENT_PROVIDER_DESCRIPTION, agent.description)
        assertEquals(TASK_PROVIDER_DESCRIPTION, task.description)
        assertTrue(task.description.startsWith("Claude Code-compatible alias for the Agent tool."))
        assertEquals(TASK_OUTPUT_PROVIDER_DESCRIPTION, output.description)
        assertEquals(TASK_STOP_PROVIDER_DESCRIPTION, stop.description)
        assertEquals(SEND_MESSAGE_PROVIDER_DESCRIPTION, send.description)
        assertEquals(CRON_CREATE_DESCRIPTION, create.description)
        assertEquals(CRON_LIST_DESCRIPTION, list.description)
        assertEquals(CRON_UPDATE_DESCRIPTION, update.description)
        assertEquals(CRON_DELETE_DESCRIPTION, delete.description)
        assertTrue(pause.description.contains("only targets the automation of this run"))
        assertSchema(pause, emptyList(), false)
        assertTrue(create.description.contains("\n\nUsage:\n- "))
        assertTrue(create.description.contains("8分钟后"))
        assertTrue(update.description.contains("\n\nUsage:\n- "))

        assertSchema(agent, listOf("description", "prompt"), false)
        assertEquals(
            "A short (3-5 word) description of the task",
            prop(agent, "description")["description"]!!.jsonPrimitive.content,
        )
        assertEquals("boolean", prop(agent, "run_in_background")["type"]!!.jsonPrimitive.content)
        assertSchema(task, listOf("description", "prompt"), false)
        assertSchema(output, listOf("task_id", "block", "timeout"), false)
        assertEquals(true, prop(output, "block")["default"]!!.jsonPrimitive.booleanOrNull)
        assertEquals("30000", prop(output, "timeout")["default"]!!.jsonPrimitive.content)
        assertEquals("600000", prop(output, "timeout")["maximum"]!!.jsonPrimitive.content)
        assertNull(stop.parameters["required"])
        assertEquals(false, stop.parameters["additionalProperties"]!!.jsonPrimitive.booleanOrNull)
        assertEquals("Deprecated: use task_id instead", prop(stop, "shell_id")["description"]!!.jsonPrimitive.content)
        assertSchema(send, listOf("to", "summary", "message"), false)
        assertEquals("20000", prop(send, "message")["maxLength"]!!.jsonPrimitive.content)
        assertSchema(create, listOf("prompt", "title"), false)
        assertEquals("525600", delayMax(create))
        assertSchema(update, listOf("id", "title"), false)
        assertEquals("Automation id returned by CronCreate or CronList", prop(update, "id")["description"]!!.jsonPrimitive.content)
        assertNull(props(update)["enabled"])
        assertSchema(list, emptyList(), false)
        assertEquals(0, props(list).size)
        assertSchema(delete, listOf("id"), false)

        assertCapability(agent, readOnly = true, destructive = false, concurrent = true, timeout = null, bytes = 120_000, scope = "session", risk = "low", approval = false, permission = "subagent")
        assertCapability(task, readOnly = true, destructive = false, concurrent = true, timeout = null, bytes = 120_000, scope = "session", risk = "low", approval = false, permission = "subagent")
        assertCapability(output, readOnly = true, destructive = false, concurrent = true, timeout = null, bytes = 400_000, scope = "none", risk = "low", approval = false, permission = "taskOutput")
        assertCapability(stop, readOnly = false, destructive = false, concurrent = true, timeout = 10_000L, bytes = 100_000, scope = "session", risk = "low", approval = false, permission = "backgroundTask.stop")
        assertCapability(send, readOnly = false, destructive = false, concurrent = true, timeout = 10_000L, bytes = 4_096, scope = "session", risk = "low", approval = false, permission = "agent.message.send")
        assertCapability(create, readOnly = false, destructive = false, concurrent = false, timeout = 30_000L, bytes = 32_000, scope = "workspace", risk = "medium", approval = true, permission = "automation.create")
        assertCapability(list, readOnly = true, destructive = false, concurrent = true, timeout = 30_000L, bytes = 32_000, scope = "none", risk = "low", approval = false, permission = "automation.read")
        assertCapability(update, readOnly = false, destructive = false, concurrent = false, timeout = 30_000L, bytes = 32_000, scope = "workspace", risk = "medium", approval = true, permission = "automation.update")
        assertCapability(delete, readOnly = false, destructive = true, concurrent = false, timeout = 30_000L, bytes = 32_000, scope = "workspace", risk = "medium", approval = true, permission = "automation.delete")
        for (tool in listOf(agent, task, output, stop, send, create, list, update, delete)) {
            assertFalse(tool.stopTurnOnSuccess)
        }
    }

    @Test
    fun errorAndModelTextMatchUpstream() = runTest {
        val orch = orchestrator()
        val agent = ZCodeAgentTool(orch, resolveAgent = { null }, listTypes = { listOf("Explore", "general-purpose") })
        val missing = agent.execute(buildJsonObject { put("description", "find the bug") })
        assertTrue(missing.isError)
        assertEquals(
            "<tool_use_error>InputValidationError: Agent failed due to the following issue:\n" +
                "The required parameter `prompt` is missing</tool_use_error>",
            missing.output,
        )
        val taskMissing = ZCodeTaskTool(orch, resolveAgent = { null }, listTypes = { listOf("Explore") })
            .execute(buildJsonObject { put("description", "find the bug") })
        assertTrue(taskMissing.output.contains("Task failed due to the following issue:"))
        val unknown = agent.execute(buildJsonObject {
            put("description", "find the bug")
            put("prompt", "look through the repo")
            put("subagent_type", "missing")
        })
        assertTrue(unknown.isError)
        assertEquals(
            "Agent type 'missing' not found. Available agents: Explore, general-purpose",
            unknown.output,
        )
        assertEquals(
            "done\nagentId: agent_1 (use SendMessage with to: 'agent_1' to continue this agent)\n<usage>tool_uses: 2\nduration_ms: 9</usage>",
            formatAgentCompletedModelContent("done", "agent_1", 2, 9),
        )
        assertEquals(
            "(Subagent completed but returned no output.)\n" +
                "agentId: agent_1 (use SendMessage with to: 'agent_1' to continue this agent)\n" +
                "<usage>subagent_tokens: 4\ntool_uses: 0\nduration_ms: 1</usage>",
            formatAgentCompletedModelContent("  ", "agent_1", 0, 1, totalTokens = 4),
        )
        val background = formatAgentBackgroundModelContent("agent_1", "/tmp/zcode-agents/agent_1/output.txt", true)
        assertTrue(background.contains("Do not duplicate this agent's work - avoid working with the same files or topics it is using."))
        assertTrue(background.contains("output_file: /tmp/zcode-agents/agent_1/output.txt"))
        assertTrue(background.contains("Do NOT Read or tail this file via the shell tool."))
        assertFalse(background.contains("Briefly tell the user what you launched"))
        val hidden = formatAgentBackgroundModelContent("agent_1", "/tmp/zcode-agents/agent_1/output.txt", false)
        assertTrue(hidden.contains("Briefly tell the user what you launched"))
        assertFalse(hidden.contains("output_file:"))
        val launched = ZCodeAgentTool(
            orch,
            resolveAgent = { type ->
                CustomSubAgent(id = "built-in:${type ?: "general-purpose"}", name = type ?: "general-purpose")
            },
            listTypes = { listOf("general-purpose") },
        ).execute(buildJsonObject {
            put("description", "bg work")
            put("prompt", "do it")
            put("run_in_background", true)
        })
        assertFalse(launched.isError)
        assertTrue(launched.output.contains("output_file:"))
        assertTrue(launched.output.contains("output.txt"))
        assertTrue(launched.output.contains("Do not duplicate this agent's work"))
        val disabled = ZCodeAgentTool(
            orch,
            resolveAgent = { CustomSubAgent(id = "off", name = "Explore", enabled = false) },
            listTypes = { listOf("general-purpose") },
        ).execute(buildJsonObject {
            put("description", "find the bug")
            put("prompt", "look")
            put("subagent_type", "Explore")
        })
        assertTrue(disabled.isError)
        assertEquals(
            "Agent type 'Explore' not found. Available agents: general-purpose",
            disabled.output,
        )

        val omitted = TaskOutputTool(orch).execute(buildJsonObject {})
        assertTrue(omitted.isError)
        assertEquals(
            "<tool_use_error>InputValidationError: TaskOutput failed due to the following issue:\n" +
                "The required parameter `task_id` is missing</tool_use_error>",
            omitted.output,
        )
        val output = TaskOutputTool(orch).execute(buildJsonObject { put("task_id", "") })
        assertEquals("<tool_use_error>Task ID is required</tool_use_error>", output.output)
        val missingTask = TaskOutputTool(orch).execute(buildJsonObject { put("task_id", "missing") })
        assertEquals("<tool_use_error>No task found with ID: missing</tool_use_error>", missingTask.output)
        assertTrue(missingTask.isError)
        assertEquals("<retrieval_status>timeout</retrieval_status>", formatTaskOutputModelContent("timeout"))

        val stop = TaskStopTool(orch)
        assertEquals("Missing required parameter: task_id", stop.execute(buildJsonObject {}).output)
        val invalid = stop.execute(buildJsonObject { put("task_id", "missing_task") })
        assertTrue(invalid.isError)
        assertEquals(
            "Invalid task ID \"missing_task\". This lookup failure does not mean the task has completed or stopped; use the full task_id returned when the task started to stop it.",
            invalid.output,
        )
        assertEquals(
            "{\"message\":\"Successfully stopped task: task_123 (sleep 30)\",\"task_id\":\"task_123\",\"task_type\":\"local_bash\",\"command\":\"sleep 30\"}",
            formatTaskStopModelContent("task_123", "local_bash", "sleep 30"),
        )

        val send = SendMessageTool(orch)
        val missingSummary = send.execute(buildJsonObject {
            put("to", "agent_x")
            put("message", "hello")
        })
        assertTrue(missingSummary.isError)
        assertTrue(missingSummary.output.contains("The required parameter `summary` is missing"))
        val emptyTo = send.execute(buildJsonObject {
            put("to", "")
            put("summary", "say hello now")
            put("message", "hello")
        })
        assertEquals(
            validationIssueContent(
                listOf(ModelValidationIssue.TooSmall("string", 1, true, listOf("to"))),
            ),
            emptyTo.output,
        )
        val missingAgent = send.execute(buildJsonObject {
            put("to", "agent_x")
            put("summary", "say hello now")
            put("message", "hello")
        })
        assertTrue(missingAgent.isError)
        assertEquals("No active local_agent task found for target agent_x.", missingAgent.output)

        val denied = cron(automation = true).Create().execute(buildJsonObject {})
        assertTrue(denied.isError)
        assertEquals("CronCreate is not allowed while running a scheduled automation.", denied.output)
        val relative = cron().Create().execute(buildJsonObject {
            put("title", "8分钟后提醒")
            put("prompt", "提醒上课")
            put("delayMinutes", 8)
            put("cron", "0 9 * * *")
        })
        assertEquals(
            validationIssueContent(
                listOf(
                    ModelValidationIssue.Custom(
                        listOf("cron"),
                        "a relative delay must omit cron; the host computes the schedule from its real clock",
                    ),
                ),
            ),
            relative.output,
        )
        val blankTitle = cron().Create().execute(buildJsonObject {
            put("title", "   ")
            put("prompt", "提醒喝水")
            put("cron", "0 9 * * *")
        })
        assertEquals(
            validationIssueContent(
                listOf(ModelValidationIssue.TooSmall("string", 1, true, listOf("title"))),
            ),
            blankTitle.output,
        )
        val unconfigured = cron().Create().execute(buildJsonObject {
            put("title", "每天喝水")
            put("prompt", "提醒喝水")
            put("cron", "0 9 * * *")
        })
        assertEquals("AutomationPort is not configured for CronCreate", unconfigured.output)
        val listed = cron().ListAll().execute(buildJsonObject {})
        assertEquals("AutomationPort is not configured for CronList", listed.output)
    }

    @Test
    fun cronPauseSelfRequiresAutomationTurnAndStore() = runTest {
        val noContext = cron().PauseSelf().execute(buildJsonObject {})
        assertTrue(noContext.isError)
        assertEquals(
            "CronPauseSelf is only available while running a scheduled automation.",
            noContext.output,
        )
        val noStore = cron(automation = true, automationId = "auto_1").PauseSelf().execute(buildJsonObject {})
        assertTrue(noStore.isError)
        assertEquals("AutomationPort is not configured for CronPauseSelf", noStore.output)
    }

    @Test
    fun defaultToolListDoesNotClaimNodeRepl() {
        val surface = buildZCodeToolSurface(
            context = context,
            networkPolicy = NetworkPolicy(),
            planTool = UpdatePlanTool(),
            goalState = GoalToolState(),
            todo = TodoState(),
            planMode = PlanModeState(),
            cwdProvider = { "/root" },
            includeLegacyAliases = true,
            listModelsProviders = { emptyList() },
        )
        val cronTools = cron().all()
        val repl = JsReplTool(context, "conv-1")
        val tools = surface + cronTools + repl
        assertTrue(tools.none { toolClaimsNodeRepl(it) })
        assertTrue(tools.none { it.name == "js" || it.name == "NodeRepl" || it.name == "node_repl" })
        assertEquals("webview_js", repl.name)
        assertFalse(repl.description.contains("node_repl"))
        assertFalse(repl.description.contains("Node REPL", ignoreCase = true))
        assertEquals(1, tools.count { it.name == "webview_js" })
        assertTrue(surface.none { it.name == "Agent" || it.name == "CronCreate" })
    }

    private fun portedTools(): List<Tool> {
        val orch = orchestrator()
        return orch.createTaskTools() + cron().all()
    }

    private fun cron(automation: Boolean = false, automationId: String? = null) = CronTools(
        store = null,
        conversationId = { 1L },
        currentModel = { "model" },
        isAutomationTurn = { automation },
        automationId = { automationId },
    )

    private fun orchestrator(): SubAgentOrchestrator {
        val provider = ProviderDefinition(
            id = "p",
            name = "p",
            baseUrl = "https://example.com",
            apiKey = "k",
            models = emptyMap(),
        )
        val api = object : LlmApi {
            override suspend fun chat(request: ChatRequest): Result<ApiMessage> =
                Result.success(ApiMessage(role = "assistant", content = "ok"))
        }
        return SubAgentOrchestrator(
            toolsFactory = { emptyList() },
            settings = ProviderSettings(),
            client = api,
            turnProvider = { TurnContext(provider, "m") },
        )
    }

    private fun assertSchema(tool: Tool, required: List<String>, extra: Boolean) {
        assertEquals(extra, tool.parameters["additionalProperties"]!!.jsonPrimitive.booleanOrNull == true)
        assertEquals(false, tool.parameters["additionalProperties"]!!.jsonPrimitive.booleanOrNull)
        assertEquals("https://json-schema.org/draft/2020-12/schema", tool.parameters["\$schema"]!!.jsonPrimitive.content)
        assertEquals("object", tool.parameters["type"]!!.jsonPrimitive.content)
        if (required.isEmpty()) {
            assertNull(tool.parameters["required"])
        } else {
            assertEquals(required, tool.parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
    }

    private fun assertCapability(
        tool: Tool,
        readOnly: Boolean,
        destructive: Boolean,
        concurrent: Boolean,
        timeout: Long?,
        bytes: Int,
        scope: String,
        risk: String,
        approval: Boolean,
        permission: String,
    ) {
        assertEquals(readOnly, tool.readOnly)
        assertEquals(destructive, tool.destructive)
        assertEquals(concurrent, tool.concurrentSafe)
        assertEquals(timeout, tool.timeoutMs)
        assertEquals(bytes, tool.maxOutputBytes)
        assertEquals(scope, tool.sideEffectScope)
        assertEquals(risk, tool.riskLevel)
        assertEquals(approval, tool.needsApproval)
        assertEquals(permission, tool.permissionName)
    }

    private fun props(tool: Tool) = tool.parameters["properties"]!!.jsonObject

    private fun prop(tool: Tool, name: String) = props(tool)[name]!!.jsonObject

    private fun delayMax(tool: Tool): String {
        val oneOf = prop(tool, "delayMinutes")["oneOf"]!!.jsonArray
        return oneOf[0].jsonObject["maximum"]!!.jsonPrimitive.content
    }
}
