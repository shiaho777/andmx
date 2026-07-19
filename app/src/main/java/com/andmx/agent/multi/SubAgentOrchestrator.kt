package com.andmx.agent.multi

import com.andmx.agent.AgentEngine
import com.andmx.agent.AgentEvent
import com.andmx.agent.Tool
import com.andmx.agent.ToolResult
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
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
) {
    /** Events emitted by sub-agents. */
    sealed interface SubAgentEvent {
        data class Started(val agentId: String, val task: String) : SubAgentEvent
        data class Delta(val agentId: String, val text: String) : SubAgentEvent
        data class Completed(val agentId: String, val result: String) : SubAgentEvent
        data class Failed(val agentId: String, val error: String) : SubAgentEvent
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
    )

    private val _events = MutableSharedFlow<SubAgentEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SubAgentEvent> = _events

    private val semaphore = Semaphore(maxConcurrentThreads)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeAgents = mutableMapOf<String, AgentStateInfo>()
    private val agentJobs = mutableMapOf<String, Job>()
    private val agentLock = kotlinx.coroutines.sync.Mutex()

    data class AgentSnapshot(
        val id: String,
        val task: String,
        val state: AgentState,
        val result: String,
        val createdAt: Long,
    )

    fun listAgents(): List<Pair<String, AgentState>> =
        activeAgents.entries.map { (id, info) -> id to info.state }

    fun listAgentSnapshots(): List<AgentSnapshot> =
        activeAgents.entries
            .map { (id, info) ->
                AgentSnapshot(
                    id = id,
                    task = info.task,
                    state = info.state,
                    result = info.result,
                    createdAt = info.createdAt,
                )
            }
            .sortedByDescending { it.createdAt }

    fun getResult(agentId: String): String? = activeAgents[agentId]?.result

    /** Get the state of a sub-agent. */
    fun getState(agentId: String): AgentState? = activeAgents[agentId]?.state

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
            permissionMode = spec.permissionMode,
            tools = spec.tools,
            disallowedTools = spec.disallowedTools,
            color = spec.color.ifBlank { "blue" },
            background = spec.background,
        )
        val baseTools = toolsFactory()
        val tools = SubagentCatalog.filterTools(baseTools, agentDef)
        val maxSteps = (spec.maxTurns ?: 25).coerceIn(1, 80)

        val (runClient, turn) = resolveRun(spec.model)
        val engine = AgentEngine(
            tools = tools,
            client = runClient,
            systemPrompt = buildSubAgentPrompt(spec.task, spec.systemHint, handoff, agentDef),
            maxSteps = maxSteps,
        )

        agentLock.withLock {
            activeAgents[agentId] = AgentStateInfo(engine, spec.task, AgentState.RUNNING)
        }

        return try {
            semaphore.acquire()
            val result = StringBuilder()
            engine.runTurn(settings, turn, spec.task).collect { event ->
                when (event) {
                    is AgentEvent.AssistantDelta -> _events.tryEmit(SubAgentEvent.Delta(agentId, event.text))
                    is AgentEvent.Assistant -> {
                        result.append(event.text)
                        _events.tryEmit(SubAgentEvent.Completed(agentId, event.text))
                    }
                    is AgentEvent.Failed -> _events.tryEmit(SubAgentEvent.Failed(agentId, event.message))
                    else -> {}
                }
            }
            val finalResult = result.toString().ifBlank { "(子代理未返回结果)" }
            agentLock.withLock {
                activeAgents[agentId]?.let { info ->
                    activeAgents[agentId] = info.copy(state = AgentState.COMPLETED, result = finalResult)
                }
            }
            finalResult
        } catch (t: Throwable) {
            _events.tryEmit(SubAgentEvent.Failed(agentId, t.message ?: "未知错误"))
            agentLock.withLock {
                activeAgents[agentId]?.let { info ->
                    activeAgents[agentId] = info.copy(state = AgentState.FAILED, result = "失败: ${t.message}")
                }
            }
            "子代理失败: ${t.message}"
        } finally {
            agentJobs.remove(agentId)
            semaphore.release()
        }
    }

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
                    is AgentEvent.Failed -> _events.tryEmit(SubAgentEvent.Failed(agentId, event.message))
                    else -> {}
                }
            }
            val finalResult = result.toString().ifBlank { "(子代理未返回结果)" }
            agentLock.withLock {
                activeAgents[agentId]?.let { i ->
                    activeAgents[agentId] = i.copy(state = AgentState.COMPLETED, result = finalResult)
                }
            }
            finalResult
        } catch (t: Throwable) {
            "子代理恢复失败: ${t.message}"
        } finally {
            semaphore.release()
        }
    }

    /**
     * Wait for a sub-agent to complete (non-blocking, returns current result if done).
     * Mirrors Codex's waitAgent.
     */
    suspend fun wait(agentId: String, timeoutMs: Long = 60_000): String {
        val info = activeAgents[agentId] ?: return "子代理 $agentId 不存在"
        if (info.state == AgentState.COMPLETED) return info.result
        if (info.state == AgentState.FAILED) return info.result
        // For running agents, wait on the events flow
        val result = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            _events.first { event ->
                event is SubAgentEvent.Completed && event.agentId == agentId
            } as? SubAgentEvent.Completed
        }
        return result?.result ?: activeAgents[agentId]?.result ?: "(等待超时)"
    }

    /**
     * Close/terminate a sub-agent.
     * Mirrors Codex's closeAgent.
     */
    fun close(agentId: String): Boolean {
        agentJobs.remove(agentId)?.cancel()
        val info = activeAgents[agentId] ?: return false
        activeAgents[agentId] = info.copy(state = AgentState.CLOSED, result = info.result.ifBlank { "(已关闭)" })
        _events.tryEmit(SubAgentEvent.Closed(agentId))
        return true
    }

    fun cancelAll(reason: String = "已停止") {
        agentJobs.values.forEach { it.cancel() }
        agentJobs.clear()
        val ids = activeAgents.keys.toList()
        for (id in ids) {
            val info = activeAgents[id] ?: continue
            if (info.state == AgentState.RUNNING || info.state == AgentState.WAITING) {
                activeAgents[id] = info.copy(state = AgentState.CLOSED, result = reason)
                _events.tryEmit(SubAgentEvent.Failed(id, reason))
                _events.tryEmit(SubAgentEvent.Closed(id))
            }
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

    /** The sub-agent tool that the main agent can call. */
    fun createSubAgentTool(): Tool = SubAgentTool(this)

    /** The multi-agent control tool (spawn/resume/wait/close). */
    fun createMultiAgentTool(): Tool = MultiAgentControlTool(this)

    private fun buildSubAgentPrompt(
        task: String,
        systemHint: String,
        handoff: String = "",
        agent: CustomSubAgent? = null,
    ): String = buildString {
        if (agent != null && (agent.systemPrompt.isNotBlank() || agent.name.isNotBlank())) {
            appendLine(SubagentCatalog.agentSystemBlock(agent))
            appendLine()
        } else {
            appendLine("你是 AndMX 的子代理，负责完成主代理分配给你的特定子任务。")
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
        activeAgents.clear()
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
) : Tool {
    override val name = "Agent"
    override val description =
        "Launch a specialized sub-agent for complex multi-step work. Prefer Explore for broad read-only search; use general-purpose for multi-step research and code tasks; or pass a named custom agent."
    override val risk = com.andmx.agent.ToolRisk.EXECUTE
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("description") {
                put("type", "string")
                put("description", "Short 3-5 word task label")
            }
            putJsonObject("prompt") {
                put("type", "string")
                put("description", "Full task for the agent")
            }
            putJsonObject("run_in_background") {
                put("type", "boolean")
                put("description", "Run without blocking the main agent")
            }
            putJsonObject("subagent_type") {
                put("type", "string")
                put("description", "Agent name: Explore (default), general-purpose, or a user-defined agent")
            }
        }
        putJsonArray("required") { add("description"); add("prompt") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val prompt = args["prompt"]?.jsonPrimitive?.content
            ?: return ToolResult("prompt required", isError = true)
        val description = args["description"]?.jsonPrimitive?.content.orEmpty()
        val kind = args["subagent_type"]?.jsonPrimitive?.content
            ?: args["agent_type"]?.jsonPrimitive?.content
            ?: args["agentType"]?.jsonPrimitive?.content
        val background = args["run_in_background"]?.let { el ->
            runCatching { el.jsonPrimitive.content.toBooleanStrict() }.getOrNull()
                ?: runCatching { el.jsonPrimitive.content.toBoolean() }.getOrNull()
                ?: false
        } == true

        val agent = resolveAgent(kind)
        if (agent != null && !agent.enabled) {
            return ToolResult("Subagent \"${agent.name}\" is disabled", isError = true)
        }
        if (kind != null && kind.isNotBlank() && agent == null) {
            val available = listTypes().joinToString(", ")
            return ToolResult(
                "Unknown subagent_type \"$kind\". Available: $available",
                isError = true,
            )
        }

        val resolved = agent ?: resolveAgent(null)
        val task = if (description.isNotBlank()) "[$description] $prompt" else prompt
        val runBg = background || (resolved?.background == true)
        val agentId = "subagent-${System.currentTimeMillis()}"
        val spec = SubAgentOrchestrator.SpawnSpec(
            task = task,
            systemHint = buildString {
                if (description.isNotBlank()) appendLine("label: $description")
                if (resolved != null) appendLine("subagent_type=${resolved.name}")
                else if (!kind.isNullOrBlank()) appendLine("subagent_type=$kind")
            }.trim(),
            agentId = agentId,
            agentName = resolved?.name.orEmpty().ifBlank { kind.orEmpty().ifBlank { "Explore" } },
            agentDescription = resolved?.description.orEmpty(),
            agentSystem = resolved?.systemPrompt.orEmpty(),
            tools = resolved?.tools ?: listOf("Bash", "Glob", "Grep", "Read", "WebFetch", "WebSearch", "TodoWrite"),
            disallowedTools = resolved?.disallowedTools.orEmpty(),
            maxTurns = resolved?.maxTurns,
            permissionMode = resolved?.permissionMode ?: "default",
            color = resolved?.color.orEmpty(),
            background = runBg,
            model = resolved?.model ?: "inherit",
        )

        if (runBg) {
            orchestrator.spawnAsync(spec)
            return ToolResult(
                "Agent started in background id=$agentId type=${spec.agentName} description=${description.ifBlank { "(none)" }}",
            )
        }
        val result = orchestrator.spawn(spec)
        return ToolResult(
            buildString {
                appendLine("Agent result:")
                appendLine("type: ${spec.agentName}")
                if (description.isNotBlank()) appendLine("description: $description")
                appendLine(result)
            }.trim(),
        )
    }
}
