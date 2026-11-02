package com.andmx.agent.multi

import com.andmx.agent.AgentEngine
import com.andmx.agent.AgentEvent
import com.andmx.agent.ApprovalOutcome
import com.andmx.agent.Tool
import com.andmx.agent.ToolResult
import com.andmx.agent.zcode.AGENT_MODEL_BYTES
import com.andmx.agent.zcode.AGENT_PARAMETERS
import com.andmx.agent.zcode.agentProviderDescription
import com.andmx.agent.zcode.taskProviderDescription
import com.andmx.agent.zcode.SEND_MESSAGE_MAX_CONTENT_CHARS
import com.andmx.agent.zcode.SEND_MESSAGE_MODEL_BYTES
import com.andmx.agent.zcode.SEND_MESSAGE_PARAMETERS
import com.andmx.agent.zcode.SEND_MESSAGE_PROVIDER_DESCRIPTION
import com.andmx.agent.zcode.SEND_MESSAGE_TIMEOUT_MS
import com.andmx.agent.zcode.TASK_OUTPUT_MODEL_BYTES
import com.andmx.agent.zcode.TASK_OUTPUT_PARAMETERS
import com.andmx.agent.zcode.TASK_OUTPUT_PROVIDER_DESCRIPTION
import com.andmx.agent.zcode.TASK_PROVIDER_DESCRIPTION
import com.andmx.agent.zcode.TASK_STOP_MODEL_BYTES
import com.andmx.agent.zcode.TASK_STOP_PARAMETERS
import com.andmx.agent.zcode.TASK_STOP_PROVIDER_DESCRIPTION
import com.andmx.agent.zcode.TASK_STOP_TIMEOUT_MS
import com.andmx.agent.zcode.ambiguousAgentType
import com.andmx.agent.zcode.ModelValidationIssue
import com.andmx.agent.zcode.formatAgentBackgroundModelContent
import com.andmx.agent.zcode.formatAgentCompletedModelContent
import com.andmx.agent.zcode.handlerFailureContent
import com.andmx.agent.zcode.validationIssueContent
import com.andmx.agent.zcode.formatTaskOutputModelContent
import com.andmx.agent.zcode.formatTaskStopModelContent
import com.andmx.agent.zcode.invalidTaskId
import com.andmx.agent.zcode.missingLocalAgent
import com.andmx.agent.zcode.queuedSendMessage
import com.andmx.agent.zcode.strictIssues
import com.andmx.agent.zcode.toolUseError
import com.andmx.agent.zcode.unknownAgentType
import com.andmx.exec.files.GuestFs
import com.andmx.llm.ApiMessage
import com.andmx.settings.CustomSubAgent
import com.andmx.settings.ProviderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Multi-agent orchestration — mirrors Codex's multi_agent_v2 / fanout system.
 *
 * Allows the main agent to spawn sub-agents for parallel task decomposition.
 * Each sub-agent runs its own AgentEngine loop with a scoped prompt and
 * shares the same sandbox. Sub-agents can communicate via a shared message bus.
 *
 * Concurrency is limited by [maxConcurrentThreads] to avoid overwhelming
 * the device.
 *
 * Supports the full Codex sub-agent lifecycle:
 * - spawnAgent: create a new sub-agent with a task
 * - resumeAgent: resume a suspended sub-agent with new input
 * - waitAgent: wait for a sub-agent to complete
 * - closeAgent: terminate a sub-agent
 * - listAgents: list all active sub-agents
 */
class SubAgentOrchestrator(
    private val toolsFactory: () -> List<Tool>,
    private val settings: ProviderSettings,
    private val client: com.andmx.llm.LlmApi,
    private val turnProvider: () -> com.andmx.agent.TurnContext,
    private val maxConcurrentThreads: Int = 3,
    private val parentHistoryProvider: () -> List<com.andmx.llm.ApiMessage> = { emptyList() },
    private val resolveRun: suspend (modelSpec: String) -> Pair<com.andmx.llm.LlmApi, com.andmx.agent.TurnContext> = { _ ->
        client to turnProvider()
    },
    private val agentsMdProvider: suspend () -> String = { "" },
    /** 子代理→协调者回话（上游 RespondToCoordinator 的投递端口）。 */
    private val coordinatorMessageSink: (fromAgentId: String, message: String) -> Unit = { _, _ -> },
    /**
     * 动态工作流工具面是否启用。上游在注册 Agent 工具时就按当前 flag 生成描述，
     * 因此这条链必须是运行时值，不能是编译期常量。
     */
    private val dynamicWorkflowEnabled: Boolean = false,
    /**
     * 子代理工具审批。permissionMode 是 agent profile 的声明值；
     * null 时保持旧行为（子代理自治，全部放行）。
     */
    private val approveTool: (suspend (permissionMode: String, tool: Tool, args: JsonObject) -> ApprovalOutcome)? = null,
) {
    /** Events emitted by sub-agents. */
    sealed interface SubAgentEvent {
        data class Started(val agentId: String, val task: String) : SubAgentEvent
        data class Delta(val agentId: String, val text: String) : SubAgentEvent
        data class Completed(val agentId: String, val result: String) : SubAgentEvent
        /** 子代理内部工具调用活动（上游 subagent transcript 对齐）。 */
        data class ToolActivity(
            val agentId: String,
            val toolName: String,
            val detail: String,
            val running: Boolean,
        ) : SubAgentEvent
        data class Failed(val agentId: String, val error: String) : SubAgentEvent
        /** Terminal state reached — emitted once per run (ZCode queued_system_notification 的触发点). */
        data class Terminated(
            val agentId: String,
            val status: String,
            val result: String,
            val background: Boolean,
        ) : SubAgentEvent
        data class Suspended(val agentId: String, val reason: String) : SubAgentEvent
        data class Resumed(val agentId: String, val input: String) : SubAgentEvent
        data class Closed(val agentId: String) : SubAgentEvent
    }

    /** State of a sub-agent. */
    enum class AgentState { RUNNING, WAITING, COMPLETED, FAILED, CLOSED }

    /** Internal state tracking for a sub-agent. */
    private data class AgentStateInfo(
        val engine: AgentEngine,
        val task: String,
        val state: AgentState,
        val result: String = "",
        val history: MutableList<com.andmx.llm.ApiMessage> = mutableListOf(),
        val createdAt: Long = System.currentTimeMillis(),
        val background: Boolean = false,
        val agentType: String = "",
        val outputFile: String = "",
    )

    private val _events = MutableSharedFlow<SubAgentEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SubAgentEvent> = _events

    private val semaphore = Semaphore(maxConcurrentThreads)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeAgents = mutableMapOf<String, AgentStateInfo>()
    private val agentJobs = mutableMapOf<String, Job>()
    private val agentLock = ReentrantLock()

    data class AgentSnapshot(
        val id: String,
        val task: String,
        val state: AgentState,
        val result: String,
        val createdAt: Long,
        val background: Boolean = false,
        val agentType: String = "",
    )

    fun listAgents(): List<Pair<String, AgentState>> =
        agentLock.withLock {
            activeAgents.entries.map { (id, info) -> id to info.state }
        }

    fun listAgentSnapshots(): List<AgentSnapshot> {
        val snapshots = agentLock.withLock {
            activeAgents.entries.map { (id, info) ->
                AgentSnapshot(
                    id = id,
                    task = info.task,
                    state = info.state,
                    result = info.result,
                    createdAt = info.createdAt,
                    background = info.background,
                    agentType = info.agentType,
                )
            }
        }
        return snapshots.sortedByDescending { it.createdAt }
    }

    fun getResult(agentId: String): String? = agentLock.withLock { activeAgents[agentId]?.result }

    /** Get the state of a sub-agent. */
    fun getState(agentId: String): AgentState? = agentLock.withLock { activeAgents[agentId]?.state }

    /**
     * Spawn a single sub-agent for a specific task.
     * Returns the sub-agent's final answer.
     * Mirrors Codex's spawnAgent.
     */
    data class SpawnSpec(
        val task: String,
        val systemHint: String = "",
        val agentId: String = "subagent-${System.currentTimeMillis()}",
        val agentName: String = "",
        val agentDescription: String = "",
        val agentSystem: String = "",
        val tools: List<String> = listOf("*"),
        val disallowedTools: List<String> = emptyList(),
        val maxTurns: Int? = null,
        val permissionMode: String = "default",
        val color: String = "",
        val background: Boolean = false,
        val model: String = "inherit",
        val thoughtLevel: String = "",
        val injectAgentsMd: Boolean = true,
        val outputFile: String = "",
    )

    suspend fun spawn(task: String, systemHint: String = "", agentId: String = "subagent-${System.currentTimeMillis()}"): String {
        return spawn(SpawnSpec(task = task, systemHint = systemHint, agentId = agentId))
    }

    suspend fun spawn(spec: SpawnSpec): String {
        val agentId = spec.agentId
        _events.tryEmit(SubAgentEvent.Started(agentId, spec.task))
        agentJobs[agentId] = currentCoroutineContext()[Job] ?: Job()

        val handoff = buildParentHandoff()
        val agentDef = CustomSubAgent(
            id = agentId,
            name = spec.agentName.ifBlank { "subagent" },
            description = spec.agentDescription,
            systemPrompt = spec.agentSystem,
            model = spec.model,
            thoughtLevel = spec.thoughtLevel,
            permissionMode = spec.permissionMode,
            tools = spec.tools,
            disallowedTools = spec.disallowedTools,
            color = spec.color.ifBlank { "blue" },
            background = spec.background,
            injectAgentsMd = spec.injectAgentsMd,
        )
        val baseTools = toolsFactory()
        val tools = SubagentCatalog.filterTools(baseTools, agentDef) +
            RespondToCoordinatorTool(agentId, coordinatorMessageSink)
        val (runClient, baseTurn) = resolveRun(spec.model)
        val thought = spec.thoughtLevel.trim()
        val turn = if (thought.isBlank() || thought.equals("inherit", ignoreCase = true)) {
            baseTurn
        } else {
            baseTurn.copy(reasoningOverride = thought)
        }
        val agentsMd = if (spec.injectAgentsMd) {
            runCatching { agentsMdProvider() }.getOrDefault("").trim()
        } else {
            ""
        }
        val engine = AgentEngine(
            tools = tools,
            client = runClient,
            systemPrompt = buildSubAgentPrompt(spec.task, spec.systemHint, handoff, agentDef, agentsMd),
            approve = { tool, args ->
                approveTool?.invoke(spec.permissionMode, tool, args) ?: ApprovalOutcome.AllowedOnce
            },
        )

        val outputFile = spec.outputFile.ifBlank { agentOutputFile(agentId) }
        agentLock.withLock {
            activeAgents[agentId] = AgentStateInfo(
                engine, spec.task, AgentState.RUNNING,
                background = spec.background, agentType = spec.agentName,
                outputFile = outputFile,
            )
        }
        val startedAt = System.currentTimeMillis()

        return try {
            semaphore.acquire()
            val result = StringBuilder()
            var toolUseCount = 0
            engine.runTurn(settings, turn, spec.task).collect { event ->
                when (event) {
                    is AgentEvent.AssistantDelta -> _events.tryEmit(SubAgentEvent.Delta(agentId, event.text))
                    is AgentEvent.Assistant -> {
                        result.append(event.text)
                        _events.tryEmit(SubAgentEvent.Completed(agentId, event.text))
                    }
                    is AgentEvent.ToolStarted -> {
                        toolUseCount += 1
                        _events.tryEmit(SubAgentEvent.ToolActivity(agentId, event.name, event.arguments.take(120), true))
                    }
                    is AgentEvent.ToolFinished ->
                        _events.tryEmit(SubAgentEvent.ToolActivity(agentId, event.name, event.output.take(120), false))
                    is AgentEvent.Failed -> _events.tryEmit(SubAgentEvent.Failed(agentId, event.message))
                    else -> {}
                }
            }
            val finalResult = result.toString()
            agentLock.withLock {
                activeAgents[agentId]?.let { info ->
                    activeAgents[agentId] = info.copy(state = AgentState.COMPLETED, result = finalResult)
                }
            }
            emitTerminated(agentId, "completed", finalResult, spec.background, spec.agentName, spec.task, startedAt, toolUseCount)
            finalResult
        } catch (t: Throwable) {
            _events.tryEmit(SubAgentEvent.Failed(agentId, t.message ?: "未知错误"))
            agentLock.withLock {
                activeAgents[agentId]?.let { info ->
                    activeAgents[agentId] = info.copy(state = AgentState.FAILED, result = "失败: ${t.message}")
                }
            }
            emitTerminated(agentId, "failed", "失败: ${t.message}", spec.background, spec.agentName, spec.task, startedAt)
            "子代理失败: ${t.message}"
        } finally {
            agentJobs.remove(agentId)
            semaphore.release()
        }
    }

    private fun emitTerminated(
        agentId: String,
        status: String,
        result: String,
        background: Boolean,
        agentType: String,
        task: String,
        startedAt: Long,
        toolUseCount: Int = 0,
    ) {
        _events.tryEmit(
            SubAgentEvent.Terminated(
                agentId = agentId,
                status = status,
                result = result,
                background = background,
            ),
        )
        lastNotification[agentId] = formatAgentTaskNotification(
            taskId = agentId,
            agentId = agentId,
            subagentType = agentType,
            status = status,
            description = task,
            result = result.takeIf { status == "completed" },
            error = result.takeIf { status != "completed" },
            durationMs = System.currentTimeMillis() - startedAt,
            toolUseCount = toolUseCount,
        )
        lastRunStats[agentId] = RunStats(toolUseCount, System.currentTimeMillis() - startedAt)
    }

    /** 最近一次终态通知体（queued_system_notification 的 body），供测试/回放用。 */
    private val lastNotification = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun notificationFor(agentId: String): String? = lastNotification[agentId]

    data class RunStats(val toolUseCount: Int, val durationMs: Long)
    private val lastRunStats = java.util.concurrent.ConcurrentHashMap<String, RunStats>()
    fun runStatsFor(agentId: String): RunStats? = lastRunStats[agentId]

    /**
     * Resume a suspended/completed sub-agent with new input.
     * Mirrors Codex's resumeAgent.
     */
    suspend fun resume(agentId: String, input: String): String {
        val info = agentLock.withLock {
            val i = activeAgents[agentId]
            if (i != null && i.state != AgentState.CLOSED) {
                activeAgents[agentId] = i.copy(state = AgentState.RUNNING)
                i
            } else null
        } ?: return "子代理 $agentId 不存在或已关闭"

        _events.tryEmit(SubAgentEvent.Resumed(agentId, input))

        var toolUseCount = 0
        return try {
            semaphore.acquire()
            val result = StringBuilder()
            info.engine.runTurn(settings, turnProvider(), input).collect { event ->
                when (event) {
                    is AgentEvent.AssistantDelta -> _events.tryEmit(SubAgentEvent.Delta(agentId, event.text))
                    is AgentEvent.Assistant -> {
                        result.append(event.text)
                        _events.tryEmit(SubAgentEvent.Completed(agentId, event.text))
                    }
                    is AgentEvent.ToolStarted -> {
                        toolUseCount += 1
                        _events.tryEmit(SubAgentEvent.ToolActivity(agentId, event.name, event.arguments.take(120), true))
                    }
                    is AgentEvent.ToolFinished ->
                        _events.tryEmit(SubAgentEvent.ToolActivity(agentId, event.name, event.output.take(120), false))
                    is AgentEvent.Failed -> _events.tryEmit(SubAgentEvent.Failed(agentId, event.message))
                    else -> {}
                }
            }
            val finalResult = result.toString()
            agentLock.withLock {
                activeAgents[agentId]?.let { i ->
                    activeAgents[agentId] = i.copy(state = AgentState.COMPLETED, result = finalResult)
                }
            }
            emitTerminated(agentId, "completed", finalResult, info.background, info.agentType, info.task, info.createdAt, toolUseCount)
            finalResult
        } catch (t: Throwable) {
            emitTerminated(agentId, "failed", "失败: ${t.message}", info.background, info.agentType, info.task, info.createdAt, toolUseCount)
            "子代理恢复失败: ${t.message}"
        } finally {
            semaphore.release()
        }
    }

    /**
     * Resume a terminal-state sub-agent in the background (ZCode SendMessage 对齐):
     * the resumed run counts as background so its terminal notification reaches
     * the parent via queued_system_notification. Returns false for missing,
     * running, or closed agents.
     */
    fun resumeAsync(agentId: String, input: String): Boolean {
        val accepted = agentLock.withLock {
            val info = activeAgents[agentId] ?: return@withLock false
            if (info.state == AgentState.RUNNING || info.state == AgentState.CLOSED) return@withLock false
            activeAgents[agentId] = info.copy(background = true)
            true
        }
        if (!accepted) return false
        val job = scope.launch {
            try {
                resume(agentId, input)
            } finally {
                agentJobs.remove(agentId)
            }
        }
        agentJobs[agentId] = job
        return true
    }

    /**
     * Wait for a sub-agent to complete (non-blocking, returns current result if done).
     * Mirrors Codex's waitAgent.
     */
    suspend fun wait(agentId: String, timeoutMs: Long = 60_000): String {
        val info = agentLock.withLock { activeAgents[agentId] } ?: return "子代理 $agentId 不存在"
        if (info.state == AgentState.COMPLETED) return info.result
        if (info.state == AgentState.FAILED) return info.result
        // For running agents, wait on the events flow
        val result = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            _events.first { event ->
                event is SubAgentEvent.Completed && event.agentId == agentId
            } as? SubAgentEvent.Completed
        }
        return result?.result ?: agentLock.withLock { activeAgents[agentId]?.result } ?: "(等待超时)"
    }

    /**
     * Close/terminate a sub-agent.
     * Mirrors Codex's closeAgent.
     */
    fun close(agentId: String): Boolean {
        agentJobs.remove(agentId)?.cancel()
        val closed = agentLock.withLock {
            val info = activeAgents[agentId] ?: return@withLock false
            activeAgents[agentId] = info.copy(state = AgentState.CLOSED, result = info.result.ifBlank { "(已关闭)" })
            true
        }
        if (!closed) return false
        _events.tryEmit(SubAgentEvent.Closed(agentId))
        return true
    }

    fun cancelAll(reason: String = "已停止") {
        agentJobs.values.forEach { it.cancel() }
        agentJobs.clear()
        val closedIds = agentLock.withLock {
            activeAgents.keys.toList().mapNotNull { id ->
                val info = activeAgents[id] ?: return@mapNotNull null
                if (info.state == AgentState.RUNNING || info.state == AgentState.WAITING) {
                    activeAgents[id] = info.copy(state = AgentState.CLOSED, result = reason)
                    id
                } else {
                    null
                }
            }
        }
        for (id in closedIds) {
            _events.tryEmit(SubAgentEvent.Failed(id, reason))
            _events.tryEmit(SubAgentEvent.Closed(id))
        }
    }

    /**
     * Spawn multiple sub-agents in parallel (fanout).
     * Returns all results in order.
     */
    suspend fun fanout(tasks: List<Pair<String, String>>): List<String> {
        val deferred = tasks.map { (task, hint) ->
            scope.async { spawn(task, hint) }
        }
        return deferred.awaitAll()
    }

    /**
     * Spawn a sub-agent and return immediately (async, non-blocking).
     * The caller can check status via [listAgents] or wait via [wait].
     */
    fun spawnAsync(task: String, systemHint: String = ""): String {
        return spawnAsync(SpawnSpec(task = task, systemHint = systemHint))
    }

    fun spawnAsync(spec: SpawnSpec): String {
        val agentId = spec.agentId.ifBlank { "subagent-${System.currentTimeMillis()}" }
        val fixed = if (spec.agentId == agentId) spec else spec.copy(agentId = agentId)
        val job = scope.launch {
            try {
                spawn(fixed)
            } finally {
                agentJobs.remove(agentId)
            }
        }
        agentJobs[agentId] = job
        return agentId
    }

    fun queueAgentMessage(agentId: String, message: String): Boolean {
        val engine = agentLock.withLock { activeAgents[agentId]?.engine } ?: return false
        engine.injectUserMessage(message)
        return true
    }

    fun prepareAgentOutputFile(agentId: String): String = agentOutputFile(agentId)

    fun outputFileFor(agentId: String): String =
        agentLock.withLock { activeAgents[agentId]?.outputFile?.takeIf { it.isNotBlank() } }
            ?: agentOutputFile(agentId)

    /** The sub-agent tool that the main agent can call. */
    fun createSubAgentTool(): Tool = SubAgentTool(this)

    /** The multi-agent control tool (spawn/resume/wait/close). */
    fun createMultiAgentTool(): Tool = MultiAgentControlTool(this)

    fun createTaskTools(
        resolveAgent: suspend (String?) -> CustomSubAgent? = { null },
        listTypes: suspend () -> List<String> = { listOf("Explore", "general-purpose") },
        backgroundTasks: com.andmx.agent.BackgroundTasks? = null,
    ): List<Tool> {
        val agent = ZCodeAgentTool(
            this, resolveAgent, listTypes,
            dynamicWorkflowEnabled = dynamicWorkflowEnabled,
        )
        return listOf(
            agent,
            ZCodeTaskTool(
                this, resolveAgent, listTypes,
                dynamicWorkflowEnabled = dynamicWorkflowEnabled,
            ),
            TaskOutputTool(this, backgroundTasks),
            TaskStopTool(this, backgroundTasks),
            SendMessageTool(this),
        )
    }

    private fun buildSubAgentPrompt(
        task: String,
        systemHint: String,
        handoff: String = "",
        agent: CustomSubAgent? = null,
        agentsMd: String = "",
    ): String = buildString {
        if (agent != null && (agent.systemPrompt.isNotBlank() || agent.name.isNotBlank())) {
            appendLine(SubagentCatalog.agentSystemBlock(agent))
            appendLine()
        } else {
            appendLine("你是 AndMX 的子代理，负责完成主代理分配给你的特定子任务。")
            appendLine()
        }
        if (agentsMd.isNotBlank()) {
            appendLine(agentsMd.trim())
            appendLine()
        }
        appendLine("## 任务")
        appendLine(task)
        appendLine()
        if (systemHint.isNotBlank()) {
            appendLine("## 上下文")
            appendLine(systemHint)
            appendLine()
        }
        if (handoff.isNotBlank()) {
            appendLine("## 主代理进度交接")
            appendLine(handoff)
            appendLine()
        }
        appendLine("## 规则")
        appendLine("- 专注完成分配的子任务，不要扩展范围")
        appendLine("- 使用工具验证事实，不要猜测")
        appendLine("- 完成后简洁报告结果")
        appendLine("- 你与主代理共享同一个 Linux 沙箱")
    }

    /**
     * Build a concise handoff from the parent agent's recent history (no LLM
     * call — just the last few exchanges, truncated), so the sub-agent knows
     * what has already been tried without re-exploring.
     */
    private fun buildParentHandoff(): String {
        val parent = parentHistoryProvider().filter { it.role != "system" }
        if (parent.isEmpty()) return ""
        // Take the last ~6 messages, summarize each to a single line.
        val recent = parent.takeLast(6)
        return buildString {
            recent.forEach { m ->
                val role = when (m.role) { "user" -> "用户"; "assistant" -> "主代理"; "tool" -> "工具结果"; else -> m.role }
                val text = m.content?.take(200) ?: m.toolCalls?.joinToString { "[调用 ${it.function.name}]" }.orEmpty()
                if (text.isNotBlank()) appendLine("- [$role] $text")
            }
        }.trim()
    }

    /** Clean up all active sub-agents. */
    fun shutdown() {
        cancelAll("已关闭")
        agentLock.withLock { activeAgents.clear() }
        agentJobs.clear()
        scope.cancel()
    }
}

/**
 * The "spawn_agent" tool — lets the main agent delegate sub-tasks.
 */
class SubAgentTool(private val orchestrator: SubAgentOrchestrator) : Tool {
    override val name = "spawn_agent"
    override val description =
        "生成一个子代理来并行处理子任务。子代理拥有自己的 agent 循环和工具集，" +
            "共享同一个沙箱。适用于大型任务的分解并行处理。" +
            "传入任务描述和可选的上下文提示。"
    override val risk = com.andmx.agent.ToolRisk.EXECUTE
    override val concurrentSafe = true

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("task") {
                put("type", "string")
                put("description", "子任务描述 (简短明确)")
            }
            putJsonObject("context") {
                put("type", "string")
                put("description", "可选的上下文提示 (项目信息、已有发现等)")
            }
        }
        putJsonArray("required") { add("task") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val task = args["task"]?.jsonPrimitive?.content
            ?: return ToolResult("缺少参数 task", isError = true)
        val context = args["context"]?.jsonPrimitive?.content ?: ""

        val result = orchestrator.spawn(task, context)
        return ToolResult("子代理结果:\n$result")
    }
}

/**
 * Multi-agent control tool — provides spawn/resume/wait/close/list operations.
 * Mirrors Codex's multi_agent_v2 API: spawnAgent, resumeAgent, waitAgent, closeAgent.
 */
class MultiAgentControlTool(private val orchestrator: SubAgentOrchestrator) : Tool {
    override val name = "multi_agent"
    override val description =
        "多代理控制工具。支持操作: spawn(生成子代理), resume(恢复子代理), " +
            "wait(等待子代理完成), close(关闭子代理), list(列出所有子代理)。"
    override val risk = com.andmx.agent.ToolRisk.EXECUTE

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "操作类型: spawn, resume, wait, close, list")
                putJsonArray("enum") {
                    add("spawn"); add("resume"); add("wait"); add("close"); add("list")
                }
            }
            putJsonObject("task") {
                put("type", "string")
                put("description", "(spawn) 子任务描述")
            }
            putJsonObject("context") {
                put("type", "string")
                put("description", "(spawn) 可选上下文提示")
            }
            putJsonObject("agent_id") {
                put("type", "string")
                put("description", "(resume/wait/close) 子代理 ID")
            }
            putJsonObject("input") {
                put("type", "string")
                put("description", "(resume) 恢复时的新输入")
            }
        }
        putJsonArray("required") { add("action") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val action = args["action"]?.jsonPrimitive?.content
            ?: return ToolResult("缺少参数 action", isError = true)

        return when (action) {
            "spawn" -> {
                val task = args["task"]?.jsonPrimitive?.content
                    ?: return ToolResult("spawn 缺少参数 task", isError = true)
                val context = args["context"]?.jsonPrimitive?.content ?: ""
                val result = orchestrator.spawn(task, context)
                ToolResult("子代理结果:\n$result")
            }
            "resume" -> {
                val agentId = args["agent_id"]?.jsonPrimitive?.content
                    ?: return ToolResult("resume 缺少参数 agent_id", isError = true)
                val input = args["input"]?.jsonPrimitive?.content
                    ?: return ToolResult("resume 缺少参数 input", isError = true)
                val result = orchestrator.resume(agentId, input)
                ToolResult("子代理恢复结果:\n$result")
            }
            "wait" -> {
                val agentId = args["agent_id"]?.jsonPrimitive?.content
                    ?: return ToolResult("wait 缺少参数 agent_id", isError = true)
                val result = orchestrator.wait(agentId)
                ToolResult("子代理结果:\n$result")
            }
            "close" -> {
                val agentId = args["agent_id"]?.jsonPrimitive?.content
                    ?: return ToolResult("close 缺少参数 agent_id", isError = true)
                val ok = orchestrator.close(agentId)
                ToolResult(if (ok) "子代理 $agentId 已关闭" else "子代理 $agentId 不存在")
            }
            "list" -> {
                val agents = orchestrator.listAgents()
                if (agents.isEmpty()) {
                    ToolResult("当前没有活动子代理")
                } else {
                    val text = agents.joinToString("\n") { (id, state) ->
                        "- $id: $state"
                    }
                    ToolResult("活动子代理:\n$text")
                }
            }
            else -> ToolResult("未知操作: $action", isError = true)
        }
    }
}


class ZCodeAgentTool(
    private val orchestrator: SubAgentOrchestrator,
    private val resolveAgent: suspend (String?) -> CustomSubAgent? = { null },
    private val listTypes: suspend () -> List<String> = { listOf("Explore", "general-purpose") },
    private val callerToolNames: () -> Collection<String> = { listOf("Read", "Bash") },
    private val reportedName: String = "Agent",
    private val dynamicWorkflowEnabled: Boolean = false,
) : Tool {
    override val name = reportedName
    override val shouldDefer = true
    override val description = agentProviderDescription(dynamicWorkflowEnabled)
    override val risk = com.andmx.agent.ToolRisk.READ
    override val readOnly = true
    override val destructive = false
    override val concurrentSafe = true
    override val needsApproval = false
    override val timeoutMs: Long? = null
    override val maxOutputBytes = AGENT_MODEL_BYTES
    override val sideEffectScope = "session"
    override val riskLevel = "low"
    override val permissionName = "subagent"
    override val parameters: JsonObject = AGENT_PARAMETERS

    override suspend fun execute(args: JsonObject): ToolResult {
        val issues = strictIssues(args, AGENT_FIELDS, listOf("description", "prompt")) { key, value ->
            when (key) {
                "run_in_background" -> if (value.asBoolean() == null) "boolean" else null
                else -> if (value.asString() == null) "string" else null
            }
        }
        if (issues.isNotEmpty()) return ToolResult(toolUseError(name, issues), isError = true)
        val prompt = args.asString("prompt").orEmpty()
        val description = args.asString("description").orEmpty()
        val requested = args.asString("subagent_type")?.trim().orEmpty().ifEmpty { "general-purpose" }
        val background = args["run_in_background"]?.asBoolean() == true
        val available = listTypes()
        val direct = resolveAgent(requested)?.takeIf { it.enabled }
        val resolved = when {
            direct != null -> direct
            else -> {
                val norm = normalizeAgentType(requested)
                val matches = if (norm.isEmpty()) emptyList() else available.filter { normalizeAgentType(it) == norm }
                when {
                    matches.size > 1 -> return ToolResult(ambiguousAgentType(requested, matches), isError = true)
                    matches.size == 1 -> resolveAgent(matches[0])?.takeIf { it.enabled }
                    else -> null
                }
            }
        }
        if (resolved == null) {
            return ToolResult(unknownAgentType(requested, available), isError = true)
        }
        val task = if (description.isNotBlank()) "[$description] $prompt" else prompt
        val runBg = background || resolved.background
        val agentId = "agent_${UUID.randomUUID()}"
        val spec = SubAgentOrchestrator.SpawnSpec(
            task = task,
            systemHint = buildString {
                if (description.isNotBlank()) appendLine("label: $description")
                appendLine("subagent_type=${resolved.name}")
            }.trim(),
            agentId = agentId,
            agentName = resolved.name.ifBlank { requested },
            agentDescription = resolved.description,
            agentSystem = resolved.systemPrompt,
            tools = resolved.tools,
            disallowedTools = resolved.disallowedTools,
            maxTurns = resolved.maxTurns,
            permissionMode = resolved.permissionMode.ifBlank { "default" },
            color = resolved.color,
            background = runBg,
            model = resolved.model.ifBlank { "inherit" },
            thoughtLevel = resolved.thoughtLevel,
            injectAgentsMd = resolved.injectAgentsMd,
            outputFile = orchestrator.prepareAgentOutputFile(agentId),
        )
        if (runBg) {
            orchestrator.spawnAsync(spec)
            val names = callerToolNames().toSet()
            val canRead = names.contains("Read") || names.contains("Bash")
            return ToolResult(formatAgentBackgroundModelContent(agentId, spec.outputFile, canRead))
        }
        val result = orchestrator.spawn(spec)
        val stats = orchestrator.runStatsFor(agentId)
        return ToolResult(
            formatAgentCompletedModelContent(
                text = result,
                agentId = agentId,
                toolUses = stats?.toolUseCount ?: 0,
                durationMs = stats?.durationMs ?: 0L,
            ),
        )
    }
}

class ZCodeTaskTool(
    private val orchestrator: SubAgentOrchestrator,
    private val resolveAgent: suspend (String?) -> CustomSubAgent? = { null },
    private val listTypes: suspend () -> List<String> = { listOf("Explore", "general-purpose") },
    private val callerToolNames: () -> Collection<String> = { listOf("Read", "Bash") },
    private val dynamicWorkflowEnabled: Boolean = false,
) : Tool {
    private val agentTool = ZCodeAgentTool(
        orchestrator, resolveAgent, listTypes, callerToolNames,
        reportedName = "Task",
        dynamicWorkflowEnabled = dynamicWorkflowEnabled,
    )
    override val name = "Task"
    override val shouldDefer = true
    override val description = taskProviderDescription(dynamicWorkflowEnabled)
    override val risk = com.andmx.agent.ToolRisk.READ
    override val readOnly = true
    override val destructive = false
    override val concurrentSafe = true
    override val needsApproval = false
    override val timeoutMs: Long? = null
    override val maxOutputBytes = AGENT_MODEL_BYTES
    override val sideEffectScope = "session"
    override val riskLevel = "low"
    override val permissionName = "subagent"
    override val parameters: JsonObject = agentTool.parameters
    override suspend fun execute(args: JsonObject): ToolResult = agentTool.execute(args)
}

class TaskOutputTool(
    private val orchestrator: SubAgentOrchestrator,
    private val backgroundTasks: com.andmx.agent.BackgroundTasks? = null,
) : Tool {
    override val name = "TaskOutput"
    override val shouldDefer = true
    override val description = TASK_OUTPUT_PROVIDER_DESCRIPTION
    override val risk = com.andmx.agent.ToolRisk.READ
    override val readOnly = true
    override val destructive = false
    override val concurrentSafe = true
    override val needsApproval = false
    override val timeoutMs: Long? = null
    override val maxOutputBytes = TASK_OUTPUT_MODEL_BYTES
    override val sideEffectScope = "none"
    override val riskLevel = "low"
    override val permissionName = "taskOutput"
    override val parameters: JsonObject = TASK_OUTPUT_PARAMETERS

    override suspend fun execute(args: JsonObject): ToolResult {
        val issues = strictIssues(args, TASK_OUTPUT_FIELDS, listOf("task_id")) { key, value ->
            when (key) {
                "task_id" -> if (value.asString() == null) "string" else null
                "block" -> if (value.asBoolean() == null) "boolean" else null
                "timeout" -> if (value.asDouble() == null) "number" else null
                else -> null
            }
        }
        if (issues.isNotEmpty()) return ToolResult(toolUseError(name, issues), isError = true)
        val id = args.asString("task_id").orEmpty()
        if (id.isEmpty()) return ToolResult(handlerFailureContent("Task ID is required"), isError = true)
        val block = args["block"]?.asBoolean() ?: true
        val timeout = args["timeout"]?.asDouble()?.let { raw ->
            if (raw < 0.0 || raw > 600_000.0) {
                return ToolResult("timeout must be between 0 and 600000", isError = true)
            }
            raw.toLong()
        } ?: 30_000L
        val initial = lookupTask(id) ?: return ToolResult(handlerFailureContent("No task found with ID: $id"), isError = true)
        if (!block) {
            return ToolResult(renderTask(if (initial.active) "not_ready" else "success", initial))
        }
        val waited = waitForTask(id, timeout) ?: return ToolResult(formatTaskOutputModelContent("timeout"))
        val status = if (waited.active) "timeout" else "success"
        return ToolResult(renderTask(status, waited))
    }

    private fun lookupTask(id: String): ProjectedTask? {
        val snap = orchestrator.listAgentSnapshots().firstOrNull { it.id == id }
        if (snap != null) {
            val state = orchestrator.getState(id) ?: snap.state
            return ProjectedTask(
                id = id,
                taskType = "local_agent",
                status = agentStatus(state),
                active = state == SubAgentOrchestrator.AgentState.RUNNING || state == SubAgentOrchestrator.AgentState.WAITING,
                output = snap.result,
                exitCode = null,
                error = if (state == SubAgentOrchestrator.AgentState.FAILED) snap.result else null,
                outputFile = null,
            )
        }
        val bg = backgroundTasks?.get(id) ?: return null
        return ProjectedTask(
            id = id,
            taskType = "local_bash",
            status = bg.statusWord,
            active = bg.state == com.andmx.agent.BackgroundTasks.TaskState.RUNNING,
            output = backgroundTasks.tail(id, TASK_OUTPUT_MODEL_BYTES).orEmpty(),
            exitCode = bg.exitCode,
            error = null,
            outputFile = bg.outputPath,
        )
    }

    private suspend fun waitForTask(id: String, timeoutMs: Long): ProjectedTask? {
        val started = System.currentTimeMillis()
        while (true) {
            val task = lookupTask(id) ?: return null
            if (!task.active) return task
            if (System.currentTimeMillis() - started >= timeoutMs) return task
            kotlinx.coroutines.delay(100)
        }
    }

    private fun renderTask(retrieval: String, task: ProjectedTask): String =
        formatTaskOutputModelContent(
            retrievalStatus = retrieval,
            taskId = task.id,
            taskType = task.taskType,
            status = task.status,
            output = task.output,
            exitCode = task.exitCode,
            error = task.error,
            outputFile = task.outputFile,
        )
}

class SendMessageTool(
    private val orchestrator: SubAgentOrchestrator,
) : Tool {
    override val name = "SendMessage"
    override val shouldDefer = true
    override val description = SEND_MESSAGE_PROVIDER_DESCRIPTION
    override val risk = com.andmx.agent.ToolRisk.EXECUTE
    override val readOnly = false
    override val destructive = false
    override val concurrentSafe = true
    override val needsApproval = false
    override val timeoutMs = SEND_MESSAGE_TIMEOUT_MS
    override val maxOutputBytes = SEND_MESSAGE_MODEL_BYTES
    override val sideEffectScope = "session"
    override val riskLevel = "low"
    override val permissionName = "agent.message.send"
    override val parameters: JsonObject = SEND_MESSAGE_PARAMETERS

    override suspend fun execute(args: JsonObject): ToolResult {
        val issues = strictIssues(args, SEND_MESSAGE_FIELDS, listOf("to", "summary", "message")) { _, value ->
            if (value.asString() == null) "string" else null
        }
        if (issues.isNotEmpty()) return ToolResult(toolUseError(name, issues), isError = true)
        val to = args.asString("to").orEmpty()
        val summary = args.asString("summary").orEmpty()
        val message = args.asString("message").orEmpty()
        val bounds = mutableListOf<ModelValidationIssue>()
        fun checkBound(key: String, value: String, max: Int) {
            when {
                value.length < 1 -> bounds += ModelValidationIssue.TooSmall("string", 1, true, listOf(key))
                value.length > max -> bounds += ModelValidationIssue.TooBig("string", max, true, listOf(key))
            }
        }
        checkBound("to", to, 200)
        checkBound("summary", summary, 200)
        checkBound("message", message, SEND_MESSAGE_MAX_CONTENT_CHARS)
        if (bounds.isNotEmpty()) return ToolResult(validationIssueContent(bounds), isError = true)
        return when (orchestrator.getState(to)) {
            null, SubAgentOrchestrator.AgentState.CLOSED ->
                ToolResult(missingLocalAgent(to), isError = true)
            SubAgentOrchestrator.AgentState.RUNNING,
            SubAgentOrchestrator.AgentState.WAITING,
            -> {
                if (!orchestrator.queueAgentMessage(to, message)) {
                    ToolResult(missingLocalAgent(to), isError = true)
                } else {
                    ToolResult(queuedSendMessage(to))
                }
            }
            else -> {
                val prior = orchestrator.getState(to) ?: return ToolResult(missingLocalAgent(to), isError = true)
                if (!orchestrator.resumeAsync(to, message)) {
                    ToolResult(missingLocalAgent(to), isError = true)
                } else {
                    ToolResult(
                        "Agent \"$to\" was stopped (${agentStatus(prior)}); resumed it in the background with your message. You'll be notified when it finishes. Output: ${orchestrator.outputFileFor(to)}",
                    )
                }
            }
        }
    }
}

class TaskStopTool(
    private val orchestrator: SubAgentOrchestrator,
    private val backgroundTasks: com.andmx.agent.BackgroundTasks? = null,
) : Tool {
    override val name = "TaskStop"
    override val shouldDefer = true
    override val description = TASK_STOP_PROVIDER_DESCRIPTION
    override val risk = com.andmx.agent.ToolRisk.EXECUTE
    override val readOnly = false
    override val destructive = false
    override val concurrentSafe = true
    override val needsApproval = false
    override val timeoutMs = TASK_STOP_TIMEOUT_MS
    override val maxOutputBytes = TASK_STOP_MODEL_BYTES
    override val sideEffectScope = "session"
    override val riskLevel = "low"
    override val permissionName = "backgroundTask.stop"
    override val parameters: JsonObject = TASK_STOP_PARAMETERS

    override suspend fun execute(args: JsonObject): ToolResult {
        val issues = strictIssues(args, TASK_STOP_FIELDS, emptyList()) { _, value ->
            if (value.asString() == null) "string" else null
        }
        if (issues.isNotEmpty()) return ToolResult(toolUseError(name, issues), isError = true)
        val id = args.asString("task_id") ?: args.asString("shell_id")
        if (id.isNullOrBlank()) return ToolResult("Missing required parameter: task_id", isError = true)
        val state = orchestrator.getState(id)
        if (state != null) {
            if (state != SubAgentOrchestrator.AgentState.RUNNING && state != SubAgentOrchestrator.AgentState.WAITING) {
                return ToolResult("Task $id is not running (status: ${agentStatus(state)})", isError = true)
            }
            val command = orchestrator.listAgentSnapshots().firstOrNull { it.id == id }?.task?.takeIf { it.isNotBlank() }
            if (!orchestrator.close(id)) return ToolResult("Task $id cannot be stopped", isError = true)
            return ToolResult(formatTaskStopModelContent(id, "local_agent", command))
        }
        val bg = backgroundTasks?.get(id)
            ?: return ToolResult(invalidTaskId(id), isError = true)
        if (bg.state != com.andmx.agent.BackgroundTasks.TaskState.RUNNING) {
            return ToolResult("Task $id is not running (status: ${bg.statusWord})", isError = true)
        }
        if (!backgroundTasks.stop(id)) return ToolResult("Task $id cannot be stopped", isError = true)
        return ToolResult(formatTaskStopModelContent(id, "local_bash", bg.command))
    }
}

private val AGENT_FIELDS = listOf("description", "prompt", "subagent_type", "run_in_background")
private val TASK_OUTPUT_FIELDS = listOf("task_id", "block", "timeout")
private val TASK_STOP_FIELDS = listOf("task_id", "shell_id")
private val SEND_MESSAGE_FIELDS = listOf("to", "summary", "message")

private data class ProjectedTask(
    val id: String,
    val taskType: String,
    val status: String,
    val active: Boolean,
    val output: String,
    val exitCode: Int?,
    val error: String?,
    val outputFile: String?,
)

private fun agentStatus(state: SubAgentOrchestrator.AgentState): String = when (state) {
    SubAgentOrchestrator.AgentState.RUNNING -> "running"
    SubAgentOrchestrator.AgentState.WAITING -> "pending"
    SubAgentOrchestrator.AgentState.COMPLETED -> "completed"
    SubAgentOrchestrator.AgentState.FAILED -> "failed"
    SubAgentOrchestrator.AgentState.CLOSED -> "killed"
}

private fun agentOutputFile(agentId: String): String {
    val dir = java.io.File(System.getProperty("java.io.tmpdir"), "zcode-agents/local/$agentId")
    dir.mkdirs()
    val file = java.io.File(dir, "output.txt")
    if (!file.exists()) file.writeText("")
    return file.absolutePath
}

private fun normalizeAgentType(agentType: String): String {
    val normalized = Normalizer.normalize(agentType.trim(), Normalizer.Form.NFKC)
        .lowercase()
        .replace(Regex("[\\p{IsWhite_Space}\\p{Pd}_]+"), "")
    return normalized
}

private fun JsonObject.asString(key: String): String? = this[key]?.asString()

private fun JsonElement.asString(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is JsonNull || !primitive.isString) return null
    return primitive.content
}

private fun JsonElement.asBoolean(): Boolean? {
    val primitive = this as? JsonPrimitive ?: return null
    return when (primitive.content) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

private fun JsonElement.asDouble(): Double? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    if (primitive.content == "true" || primitive.content == "false") return null
    return primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull()
}

private const val TASK_NOTIFICATION_MAX_CHARS = 120_000

/**
 * ZCode `formatLocalAgentTaskNotification` 对齐：后台任务终态推送的
 * `<task-notification>` XML 体（queued_system_notification 的 body）。
 */
internal fun formatAgentTaskNotification(
    taskId: String,
    agentId: String,
    subagentType: String,
    status: String,
    description: String,
    result: String? = null,
    error: String? = null,
    durationMs: Long? = null,
    toolUseCount: Int = 0,
): String {
    val summary = buildString {
        append("Agent ").append(subagentType.ifBlank { "subagent" })
        append(" task \"").append(description).append("\" ").append(status).append('.')
        if (status == "failed" && !error.isNullOrBlank()) append(' ').append(error.trim())
    }
    val lines = mutableListOf("<task-notification>", "<task-id>${esc(taskId)}</task-id>")
    lines += "<agent-id>${esc(agentId)}</agent-id>"
    if (subagentType.isNotBlank()) lines += "<subagent-type>${esc(subagentType)}</subagent-type>"
    lines += "<status>${esc(status)}</status>"
    lines += "<summary>${esc(summary)}</summary>"
    if (result != null) lines += "<result>${esc(result)}</result>"
    if (error != null) lines += "<error>${esc(error)}</error>"
    if (durationMs != null) {
        lines += "<usage><duration-ms>$durationMs</duration-ms><tool-uses>$toolUseCount</tool-uses></usage>"
    }
    lines += "</task-notification>"
    return lines.joinToString("\n").take(TASK_NOTIFICATION_MAX_CHARS)
}

private fun esc(s: String): String = s
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** 子代理→协调者回话（上游 RespondToCoordinator）：仅注册进子代理工具面。 */
class RespondToCoordinatorTool(
    private val agentId: String,
    private val sink: (fromAgentId: String, message: String) -> Unit,
) : Tool {
    override val name = "RespondToCoordinator"
    override val shouldDefer = true
    override val description =
        "# RespondToCoordinator\n\n" +
            "Reply to the agent that spawned or messaged you.\n\n" +
            "```json\n" +
            "{\"message\": \"done: found 3 candidates, recommend option B\"}\n" +
            "```\n\n" +
            "Your plain text output is NOT delivered to the coordinator — you MUST call this tool " +
            "to answer its message or report a final result it asked for.\n"
    override val risk = com.andmx.agent.ToolRisk.EXECUTE
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("message") {
                put("type", "string")
                put("description", "Reply content delivered to the coordinator")
            }
        }
        putJsonArray("required") { add("message") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val message = args["message"]?.jsonPrimitive?.content
            ?: return ToolResult("{\"success\":false,\"message\":\"message required\"}", isError = true)
        sink(agentId, message.take(4_096))
        return ToolResult("{\"success\":true,\"message\":\"Delivered to coordinator\"}")
    }
}
