package com.andmx.ui2.chat


import android.content.Context
import com.andmx.agent.AgentEngine
import com.andmx.agent.AgentEvent
import com.andmx.agent.ApplyPatchTool
import com.andmx.agent.ApprovalMode
import com.andmx.agent.ApprovalPolicy
import com.andmx.agent.BrowseTool
import com.andmx.agent.Decision
import com.andmx.agent.EditFileTool
import com.andmx.agent.GitTool
import com.andmx.agent.GlobTool
import com.andmx.agent.GrepTool
import com.andmx.agent.ListDirTool
import com.andmx.agent.ReadFileTool
import com.andmx.agent.ShellTool
import com.andmx.agent.Tool
import com.andmx.agent.ToolRisk
import com.andmx.agent.TurnContext
import com.andmx.agent.UpdatePlanTool
import com.andmx.agent.zcode.isPlanModeAllowed
import com.andmx.agent.zcode.buildZCodeToolSurface
import com.andmx.agent.zcode.PlanModeState
import com.andmx.agent.zcode.TodoState
import com.andmx.agent.zcode.AskQuestion
import com.andmx.agent.zcode.ZCodePrompts
import com.andmx.agent.CreateGoalTool
import com.andmx.agent.UpdateGoalTool
import com.andmx.agent.GetGoalTool
import com.andmx.agent.GoalToolState
import com.andmx.agent.threadHandoffText
import com.andmx.agent.ThreadHandoffContext
import com.andmx.agent.HandoffRunLogItem
import com.andmx.ui.conversation.ConversationGoal
import com.andmx.agent.WebSearchTool
import com.andmx.agent.WriteFileTool
import com.andmx.agent.hooks.HookSystem
import com.andmx.agent.suggestions.AmbientSuggestions
import com.andmx.agent.memory.MemorySystem
import com.andmx.ui.conversation.GoalPhase
import com.andmx.ui.conversation.GoalStatus
import com.andmx.agent.multi.SubAgentOrchestrator
import com.andmx.agent.multi.SubagentCatalog
import com.andmx.agent.multi.ZCodeAgentTool
import com.andmx.agent.plugins.PluginSystem
import com.andmx.agent.plugins.BuiltinPluginSeeder
import com.andmx.agent.plugins.mobile.AndroidDevToolset
import com.andmx.agent.plugins.deviceutils.StorageCleanupToolset
import com.andmx.agent.plugins.htmlvideo.HtmlVideoToolset
import com.andmx.agent.plugins.devforge.DevForgeToolset
import com.andmx.data.ConversationRepository
import com.andmx.data.rollout.EventMsg
import com.andmx.data.rollout.ResponseItem
import com.andmx.data.rollout.RolloutWriter
import com.andmx.data.rollout.SessionResumer
import com.andmx.data.rollout.TurnContext as RolloutTurnContext
import com.andmx.exec.files.GuestFs
import com.andmx.exec.policy.NetworkPolicy
import com.andmx.exec.proot.ProotRuntime
import com.andmx.llm.ApiFunctionCall
import com.andmx.llm.ApiMessage
import com.andmx.llm.ApiToolCall
import com.andmx.llm.LlmClient
import com.andmx.llm.TokenUsage
import com.andmx.llm.TokenUsageTracker
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.mcp.McpManager
import com.andmx.settings.ProviderSettings
import com.andmx.settings.ProviderStore
import com.andmx.settings.SettingsStore
import com.andmx.workspace.WorkspaceAccess
import com.andmx.workspace.WorkspaceKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ChatController(private val context: Context) {
    companion object {
        private const val TOOL_OUTPUT_DB_LIMIT = 12_000
        private const val TOOL_OUTPUT_ROLLOUT_LIMIT = 4_096
        private const val TOOL_OUTPUT_MEMORY_LIMIT = 4_000
        private const val MAX_CACHED_SESSIONS = 4
    }
    private val settingsStore = SettingsStore(context)
    private val providerStore = ProviderStore(context)
    private val repo = ConversationRepository(context)
    private val projectManager = com.andmx.workspace.ProjectManager(context)
    private val access = WorkspaceAccess(context)
    private val networkPolicy = NetworkPolicy.PERMISSIVE
    private val guestFs = GuestFs(ProotRuntime(context))
    private val memorySystem = MemorySystem(guestFs)
    private val pluginSystem = PluginSystem(context, guestFs)
    private val hookSystem = HookSystem(context)
    private val ambientSuggestions = AmbientSuggestions(context)
    @Volatile private var hooksLoaded = false
    private val mcpManager = McpManager(context)
    private val rolloutWriters = ConcurrentHashMap<Long, RolloutWriter>()
    private val tokenTrackers = ConcurrentHashMap<Long, TokenUsageTracker>()
    private val orchestrators = ConcurrentHashMap<Long, SubAgentOrchestrator>()



    private suspend fun resolveSubAgentRun(
        modelSpec: String,
        fallbackProvider: com.andmx.llm.provider.ProviderDefinition,
        conversationId: Long,
    ): Pair<com.andmx.llm.LlmApi, TurnContext> {
        val settings = settingsStore.settings.firstOrNull()
        val baseModel = settings?.model.orEmpty().ifBlank { fallbackProvider.models.keys.firstOrNull().orEmpty() }
        if (com.andmx.agent.multi.SubagentModelCatalog.isInherit(modelSpec)) {
            return LlmClient(fallbackProvider, trackerFor(conversationId)) to TurnContext(fallbackProvider, baseModel)
        }
        val parsed = com.andmx.agent.multi.SubagentModelCatalog.parse(modelSpec)
        val providers = providerStore.providers.firstOrNull().orEmpty().filter { it.enabled }
        val providerId = parsed?.first.orEmpty()
        val modelId = parsed?.second.orEmpty()
        val provider = when {
            providerId.isNotBlank() -> providers.firstOrNull { it.id == providerId }
            modelId.isNotBlank() -> providers.firstOrNull { modelId in it.models } ?: providers.firstOrNull { it.id == fallbackProvider.id }
            else -> null
        } ?: fallbackProvider
        val model = modelId.ifBlank { baseModel }.ifBlank { provider.models.keys.firstOrNull().orEmpty() }
        return LlmClient(provider, trackerFor(conversationId)) to TurnContext(provider, model)
    }

    private fun createZCodeAgentTool(orch: SubAgentOrchestrator): ZCodeAgentTool {
        return ZCodeAgentTool(
            orchestrator = orch,
            resolveAgent = { type ->
                val users = settingsStore.customSubAgents.firstOrNull().orEmpty()
                val state = settingsStore.subagentState.firstOrNull() ?: com.andmx.settings.SubagentStateFile()
                SubagentCatalog.resolve(type, users, state)
            },
            listTypes = {
                val users = settingsStore.customSubAgents.firstOrNull().orEmpty()
                val state = settingsStore.subagentState.firstOrNull() ?: com.andmx.settings.SubagentStateFile()
                SubagentCatalog.listAll(users, state).filter { it.enabled }.map { it.name }
            },
        )
    }


    private val sessions = ConcurrentHashMap<Long, Session>()
    private val sessionAccessOrder = java.util.Collections.synchronizedList(mutableListOf<Long>())
    @Volatile private var pluginTools: List<Tool> = emptyList()
    @Volatile private var mobileDevTools: List<Tool> = emptyList()
    @Volatile private var mcpTools: List<Tool> = emptyList()
    @Volatile private var mcpConfigLoaded: String? = null
    @Volatile private var pluginsLoaded = false
    @Volatile private var lastPluginReloadToken = -1L
    @Volatile private var lastHooksReloadToken = -1L

    private val sharedExtraTools: List<Tool>
        get() = pluginTools + mobileDevTools + mcpTools

    private val _pendingApproval = MutableStateFlow<ApprovalRequest?>(null)
    val pendingApproval: StateFlow<ApprovalRequest?> = _pendingApproval.asStateFlow()

    private val _planSteps = MutableStateFlow<List<UpdatePlanTool.PlanStep>>(emptyList())
    val planSteps: StateFlow<List<UpdatePlanTool.PlanStep>> = _planSteps.asStateFlow()

    private val _mcpStatus = MutableStateFlow<List<McpManager.Connected>>(emptyList())
    val mcpStatus: StateFlow<List<McpManager.Connected>> = _mcpStatus.asStateFlow()

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _subAgents = MutableStateFlow<List<SubAgentUi>>(emptyList())
    val subAgents: StateFlow<List<SubAgentUi>> = _subAgents.asStateFlow()

    private val _tokenUsage = MutableStateFlow(TokenUsageUi())
    val tokenUsage: StateFlow<TokenUsageUi> = _tokenUsage.asStateFlow()

    private val _goal = MutableStateFlow(ConversationGoal())
    val goal: StateFlow<ConversationGoal> = _goal.asStateFlow()

    val ambient: StateFlow<List<AmbientSuggestions.Suggestion>> = ambientSuggestions.suggestions

    data class TokenUsageUi(
        val input: Int = 0,
        val output: Int = 0,
        val total: Int = 0,
        val lastTotal: Int = 0,
    )
    private val subAgentWatchJobs = ConcurrentHashMap<Long, kotlinx.coroutines.Job>()
    private val liveChatEvents = ConcurrentHashMap<Long, kotlinx.coroutines.flow.MutableSharedFlow<ChatEvent>>()

    private fun liveBus(conversationId: Long): kotlinx.coroutines.flow.MutableSharedFlow<ChatEvent> =
        liveChatEvents.getOrPut(conversationId) {
            kotlinx.coroutines.flow.MutableSharedFlow(extraBufferCapacity = 64)
        }

    fun sideEvents(conversationId: Long): kotlinx.coroutines.flow.SharedFlow<ChatEvent> =
        liveBus(conversationId)

    data class SubAgentUi(
        val id: String,
        val task: String,
        val state: String,
        val resultPreview: String = "",
        val conversationId: Long,
    )

    data class ApprovalRequest(
        val conversationId: Long,
        val toolName: String,
        val risk: ToolRisk,
        val summary: String,
        val modeLabel: String,
        val id: String = "appr-${System.currentTimeMillis()}",
        val createdAt: Long = System.currentTimeMillis(),
        val kind: String = "tool",
        val questions: List<AskQuestion> = emptyList(),
        val planText: String = "",
    )

    private class Session(
        val conversationId: Long,
        var engine: AgentEngine,
        var providerId: String,
        var model: String,
        val planTool: UpdatePlanTool,
        val goalState: GoalToolState,
        val todoState: TodoState = TodoState(),
        val planModeState: PlanModeState = PlanModeState(),
        var approvalMode: ExecMode,
        var pending: CompletableDeferred<Boolean>? = null,
        var pendingAnswer: CompletableDeferred<String>? = null,
        val turnToolOutputs: MutableList<Pair<String, String>> = mutableListOf(),
        var lastUserText: String = "",
        var lastAssistantText: String = "",
    )

    fun resolveApproval(allow: Boolean) {
        val req = _pendingApproval.value
        _pendingApproval.value = null
        val session = req?.let { sessions[it.conversationId] }
        session?.pending?.complete(allow)
        session?.pending = null
        if (session?.pendingAnswer != null) {
            session.pendingAnswer?.complete(
                if (allow) """{"answers":{"__default__":"approved"}}""" else """{"answers":{"__default__":"rejected"}}"""
            )
            session.pendingAnswer = null
        }
    }

    fun resolveUserQuestion(answersJson: String) {
        val req = _pendingApproval.value
        _pendingApproval.value = null
        val session = req?.let { sessions[it.conversationId] }
        session?.pendingAnswer?.complete(answersJson)
        session?.pendingAnswer = null
        session?.pending?.complete(true)
        session?.pending = null
    }

    fun resolvePlanApproval(allow: Boolean) {
        resolveApproval(allow)
    }

    fun reloadPlugins() {
        controllerScope.launch {
            pluginTools = runCatching { pluginSystem.loadPluginTools(context) }.getOrDefault(emptyList())
            pluginsLoaded = true
            lastPluginReloadToken = pluginSystem.currentReloadToken()
            hooksLoaded = false
            runCatching { ensureHooksLoaded() }
        }
    }


    fun stopTurn(conversationId: Long) {
        resolveApproval(false)
        orchestrators[conversationId]?.cancelAll("用户停止")
        refreshSubAgents(conversationId)
        val session = sessions[conversationId]
        session?.pending?.complete(false)
        session?.pending = null
        session?.pendingAnswer?.complete("""{"answers":{"__default__":"cancelled"}}""")
        session?.pendingAnswer = null
        controllerScope.launch {
            if (hookSystem.hasHooksFor(HookSystem.HookEvent.STOP)) {
                runCatching {
                    hookSystem.runEvent(
                        HookSystem.HookEvent.STOP,
                        HookSystem.HookContext(
                            sessionInfo = mapOf("conversation_id" to conversationId.toString()),
                        ),
                    )
                }
            }
            runCatching { writerFor(conversationId).flush() }
            session?.goalState?.goal?.let { persistGoal(conversationId, it) }
        }
    }

    fun estimateContextTokens(conversationId: Long): Int {
        val session = sessions[conversationId] ?: return 0
        val texts = session.engine.snapshotHistory().mapNotNull { m ->
            m.content ?: m.toolCalls?.joinToString { it.function.arguments }
        }
        return com.andmx.agent.TokenEstimate.estimateAll(texts)
    }

    fun clearSession(conversationId: Long) {
        sessions.remove(conversationId)
        orchestrators.remove(conversationId)?.shutdown()
        subAgentWatchJobs.remove(conversationId)?.cancel()
        rolloutWriters.remove(conversationId)?.let { w ->
            controllerScope.launch { runCatching { w.close() } }
        }
        liveChatEvents.remove(conversationId)
        tokenTrackers.remove(conversationId)
        sessionAccessOrder.remove(conversationId)
        if (_pendingApproval.value?.conversationId == conversationId) {
            _pendingApproval.value = null
        }
        _subAgents.value = _subAgents.value.filterNot { it.conversationId == conversationId }
    }

    suspend fun reseedFromDb(conversationId: Long) {
        val session = sessions[conversationId] ?: return
        val history = rebuildHistory(conversationId)
        session.engine.seed(history)
        session.turnToolOutputs.clear()
        session.lastUserText = history.lastOrNull { it.role == "user" }?.content.orEmpty()
    }


    private fun touchSession(conversationId: Long) {
        synchronized(sessionAccessOrder) {
            sessionAccessOrder.remove(conversationId)
            sessionAccessOrder.add(conversationId)
        }
    }

    private fun trimSessionCache(keepId: Long) {
        val victims = mutableListOf<Long>()
        synchronized(sessionAccessOrder) {
            while (sessionAccessOrder.size > MAX_CACHED_SESSIONS) {
                val oldest = sessionAccessOrder.firstOrNull { it != keepId } ?: break
                sessionAccessOrder.remove(oldest)
                victims += oldest
            }
        }
        victims.forEach { id ->
            if (id == keepId) return@forEach
            if (_pendingApproval.value?.conversationId == id) return@forEach
            val session = sessions[id]
            if (session?.pending != null) return@forEach
            clearSession(id)
        }
    }

    suspend fun focusConversation(conversationId: Long) {
        val existing = sessions[conversationId]
        refreshSubAgents(conversationId)
        refreshTokenUsage(conversationId)
        if (existing != null) {
            _planSteps.value = existing.planTool.state.value
            _goal.value = existing.goalState.goal
            touchSession(conversationId)
            return
        }
        val restored = restoreGoalFromDb(conversationId)
        _goal.value = restored
        val planTool = UpdatePlanTool()
        restorePlanFromHistory(conversationId, planTool)
        _planSteps.value = planTool.state.value
    }

    suspend fun sendMessage(
        conversationId: Long,
        text: String,
        images: List<String> = emptyList(),
    ): Flow<ChatEvent> = flow {
        emit(ChatEvent.UserMessage(text))
        repo.addMessage(conversationId, "user", text)
        runCatching {
            val conv = repo.conversation(conversationId)
            if (conv != null && conv.firstUserMessage.isBlank()) {
                val dao = com.andmx.data.AndmxDatabase.get(context).dao()
                dao.updateSessionMetadata(
                    id = conversationId,
                    path = conv.rolloutPath,
                    sessionId = conv.sessionId,
                    sandboxPolicy = conv.sandboxPolicy,
                    model = conv.model,
                    reasoningEffort = conv.reasoningEffort,
                    memoryMode = conv.memoryMode,
                    firstUserMessage = text.take(200),
                )
            }
        }

        val settings = settingsStore.settings.firstOrNull()
            ?: return@flow emit(ChatEvent.Error("设置未初始化"))
        if (!settings.hasSelection) {
            return@flow emit(ChatEvent.Error("请先选择模型"))
        }
        val providers = providerStore.providers.firstOrNull().orEmpty()
        val provider = providers.firstOrNull { it.id == settings.activeProviderId && it.enabled }
            ?: providerStore.primary.firstOrNull()
            ?: return@flow emit(ChatEvent.Error("未配置提供商"))
        if (!provider.isUsable) {
            return@flow emit(ChatEvent.Error("当前提供商不可用，请检查 API Key"))
        }

        ensureExtraTools(settings)
        ensureHooksLoaded()
        val session = obtainSession(conversationId, provider, settings)
        session.lastUserText = text
        session.turnToolOutputs.clear()
        _planSteps.value = session.planTool.state.value
        var modelInput = text
        if (hookSystem.hasHooksFor(HookSystem.HookEvent.USER_PROMPT_SUBMIT)) {
            val hookResult = runCatching {
                hookSystem.runEvent(
                    HookSystem.HookEvent.USER_PROMPT_SUBMIT,
                    HookSystem.HookContext(userInput = text),
                )
            }.getOrNull()
            if (hookResult != null) {
                if (hookResult.decision == HookSystem.HookDecision.BLOCK) {
                    emit(ChatEvent.Error(hookResult.message?.ifBlank { "被 UserPromptSubmit hook 拦截" } ?: "被 UserPromptSubmit hook 拦截"))
                    emit(ChatEvent.Done)
                    return@flow
                }
                if (hookResult.decision == HookSystem.HookDecision.MODIFY && !hookResult.modifiedInput.isNullOrBlank()) {
                    modelInput = hookResult.modifiedInput!!
                }
                val fragment = hookResult.promptFragment?.trim().orEmpty()
                if (fragment.isNotEmpty()) {
                    modelInput = "$modelInput\n\n# Hook context\n$fragment"
                }
            }
        }
        val turn = TurnContext(provider, settings.model)
        val toolArgsById = mutableMapOf<String, String>()
        ensureRolloutSession(conversationId, provider, settings)
        val writer = writerFor(conversationId)
        val turnId = "turn-${System.currentTimeMillis()}"
        runCatching {
            writer.writeTurnContext(
                com.andmx.data.rollout.TurnContext(
                    turnId = turnId,
                    cwd = access.guestCwd(),
                    currentDate = java.time.LocalDate.now().toString(),
                    timezone = java.util.TimeZone.getDefault().id,
                    approvalPolicy = ExecMode.from(settings.approvalMode).id,
                    sandboxPolicy = if (projectManager.isRemote) "remote-ssh" else "workspace-write",
                    model = settings.model,
                    personality = settings.persona,
                ),
            )
            writer.writeResponseItem(
                ResponseItem(type = "message", role = "user", content = text, turnId = turnId),
            )
            writer.writeEventMsg(EventMsg(type = "task_started", turnId = turnId, startedAt = System.currentTimeMillis()))
        }

        try {
            session.engine.runTurn(settings, turn, modelInput, images = images).collect { event ->
                handleAgentEvent(
                    conversationId = conversationId,
                    session = session,
                    writer = writer,
                    turnId = turnId,
                    toolArgsById = toolArgsById,
                    event = event,
                    userTextForTitle = text,
                )
            }
        } catch (e: Exception) {
            emit(ChatEvent.Error(e.message ?: "未知错误"))
        } finally {
            if (_pendingApproval.value?.conversationId == conversationId) {
                _pendingApproval.value = null
                session.pending?.complete(false)
                session.pending = null
            }
        }
    }



    private suspend fun kotlinx.coroutines.flow.FlowCollector<ChatEvent>.handleAgentEvent(
        conversationId: Long,
        session: Session,
        writer: RolloutWriter,
        turnId: String,
        toolArgsById: MutableMap<String, String>,
        event: AgentEvent,
        userTextForTitle: String? = null,
    ) {
        when (event) {
            is AgentEvent.AssistantDelta -> emit(ChatEvent.AssistantChunk(event.text))
            is AgentEvent.ReasoningDelta -> emit(ChatEvent.ReasoningChunk(event.text))
            is AgentEvent.ReasoningDone -> emit(ChatEvent.ReasoningDone)
            is AgentEvent.ToolCallArgsDelta -> emit(ChatEvent.ToolCallArgsDelta(event.index, event.id, event.name, event.argumentsSoFar))
            is AgentEvent.Assistant -> {
                emit(ChatEvent.AssistantComplete(event.text))
                if (event.text.isNotBlank()) {
                    session.lastAssistantText = event.text
                    repo.addMessage(conversationId, "assistant", event.text)
                    runCatching {
                        writer.writeResponseItem(
                            ResponseItem(type = "message", role = "assistant", content = event.text, turnId = turnId),
                        )
                    }
                }
            }
            is AgentEvent.ToolStarted -> {
                toolArgsById[event.id] = event.arguments
                emit(ChatEvent.ToolCallStarted(event.id, event.name, event.arguments))
                runCatching {
                    writer.writeResponseItem(
                        ResponseItem(
                            type = "function_call",
                            toolCallId = event.id,
                            toolName = event.name,
                            toolArgs = event.arguments,
                            turnId = turnId,
                        ),
                    )
                }
            }
            is AgentEvent.ToolFinished -> {
                val args = toolArgsById.remove(event.id).orEmpty()
                val out = event.output
                val outDb = out.take(TOOL_OUTPUT_DB_LIMIT)
                val outMem = out.take(TOOL_OUTPUT_MEMORY_LIMIT)
                session.turnToolOutputs += event.name to outMem
                if (session.turnToolOutputs.size > 24) {
                    session.turnToolOutputs.subList(0, session.turnToolOutputs.size - 24).clear()
                }
                emit(ChatEvent.ToolCallFinished(event.id, outDb, event.isError))
                repo.addMessage(
                    conversationId,
                    "tool",
                    outDb,
                    toolName = event.name,
                    toolArgs = args,
                    toolError = event.isError,
                )
                runCatching {
                    writer.writeResponseItem(
                        ResponseItem(
                            type = "function_call_output",
                            toolCallId = event.id,
                            toolName = event.name,
                            toolArgs = args,
                            toolOutput = out.take(TOOL_OUTPUT_ROLLOUT_LIMIT),
                            isError = event.isError,
                            turnId = turnId,
                        ),
                    )
                }
                if (event.name == "update_plan") {
                    _planSteps.value = session.planTool.state.value
                    emit(
                        ChatEvent.PlanUpdated(
                            session.planTool.state.value.map {
                                PlanStepUi(it.content, it.status.name.lowercase())
                            },
                        ),
                    )
                }
                if (userTextForTitle != null) {
                    maybeAutoTitle(conversationId, event.name, userTextForTitle)
                }
            }
            is AgentEvent.Failed -> {
                runCatching {
                    writer.writeEventMsg(
                        EventMsg(type = "task_failed", turnId = turnId, errorMessage = event.message),
                    )
                }
                emit(ChatEvent.Error(event.message))
            }
            is AgentEvent.Done -> {
                extractMemory(session, conversationId)
                runCatching {
                    val usage = trackerFor(conversationId).lastTurnUsage.value
                    if (usage.inputTokens > 0 || usage.outputTokens > 0 || usage.totalTokens > 0) {
                        writer.writeEventMsg(
                            EventMsg(
                                type = "token_usage",
                                turnId = turnId,
                                inputTokens = usage.inputTokens,
                                cachedInputTokens = usage.cachedInputTokens,
                                outputTokens = usage.outputTokens,
                                reasoningOutputTokens = usage.reasoningOutputTokens,
                                totalTokens = usage.totalTokens.takeIf { it > 0 }
                                    ?: (usage.inputTokens + usage.outputTokens),
                            ),
                        )
                    }
                    writer.writeEventMsg(EventMsg(type = "task_completed", turnId = turnId))
                }
                applyGoalTokenUsage(session)
                refreshTokenUsage(conversationId)
                _goal.value = session.goalState.goal
                persistGoal(conversationId, session.goalState.goal)
                refreshAmbient(conversationId, session)
                runCatching { writer.flush() }
                emit(ChatEvent.Done)
            }
        }
    }

    suspend fun regenerateTurn(conversationId: Long): Flow<ChatEvent> = flow {
        val settings = settingsStore.settings.firstOrNull()
            ?: return@flow emit(ChatEvent.Error("设置未初始化"))
        if (!settings.hasSelection) return@flow emit(ChatEvent.Error("请先选择模型"))
        val providers = providerStore.providers.firstOrNull().orEmpty()
        val provider = providers.firstOrNull { it.id == settings.activeProviderId && it.enabled }
            ?: providerStore.primary.firstOrNull()
            ?: return@flow emit(ChatEvent.Error("未配置提供商"))
        if (!provider.isUsable) return@flow emit(ChatEvent.Error("当前提供商不可用，请检查 API Key"))
        ensureExtraTools(settings)
        ensureHooksLoaded()
        val session = obtainSession(conversationId, provider, settings)
        session.turnToolOutputs.clear()
        ensureRolloutSession(conversationId, provider, settings)
        val writer = writerFor(conversationId)
        val turnId = "turn-regen-${System.currentTimeMillis()}"
        val turn = TurnContext(provider, settings.model)
        val toolArgsById = mutableMapOf<String, String>()
        runCatching {
            writer.writeTurnContext(
                com.andmx.data.rollout.TurnContext(
                    turnId = turnId,
                    cwd = access.guestCwd(),
                    currentDate = java.time.LocalDate.now().toString(),
                    timezone = java.util.TimeZone.getDefault().id,
                    approvalPolicy = ExecMode.from(settings.approvalMode).id,
                    sandboxPolicy = if (projectManager.isRemote) "remote-ssh" else "workspace-write",
                    model = settings.model,
                    personality = settings.persona,
                ),
            )
            writer.writeEventMsg(EventMsg(type = "task_started", turnId = turnId, startedAt = System.currentTimeMillis()))
        }
        try {
            session.engine.regenerate(settings, turn).collect { event ->
                handleAgentEvent(
                    conversationId = conversationId,
                    session = session,
                    writer = writer,
                    turnId = turnId,
                    toolArgsById = toolArgsById,
                    event = event,
                )
            }
        } catch (e: Exception) {
            emit(ChatEvent.Error(e.message ?: "未知错误"))
        } finally {
            if (_pendingApproval.value?.conversationId == conversationId) {
                _pendingApproval.value = null
                session.pending?.complete(false)
                session.pending = null
            }
        }
    }


    suspend fun compactConversation(conversationId: Long): String {
        val settings = settingsStore.settings.firstOrNull()
            ?: return "设置未初始化"
        val providers = providerStore.providers.firstOrNull().orEmpty()
        val provider = providers.firstOrNull { it.id == settings.activeProviderId && it.enabled }
            ?: providerStore.primary.firstOrNull()
            ?: return "未配置提供商"
        ensureExtraTools(settings)
        ensureHooksLoaded()
        val session = obtainSession(conversationId, provider, settings)
        val msg = session.engine.compactNow(settings, TurnContext(provider, settings.model))
            ?: return "上下文较短，无需压缩"
        refreshTokenUsage(conversationId)
        return msg
    }

    suspend fun statusText(conversationId: Long): String {
        val settings = settingsStore.settings.firstOrNull()
        val conv = repo.conversation(conversationId)
        val session = sessions[conversationId]
        val usage = tokenTrackers[conversationId]?.sessionUsage?.value
        val plan = session?.planTool?.state?.value.orEmpty()
        val sub = orchestrators[conversationId]?.listAgentSnapshots().orEmpty()
        val tokens = estimateContextTokens(conversationId)
        return buildString {
            appendLine("会话 #${conversationId}")
            appendLine("标题：${conv?.title ?: "新任务"}")
            appendLine("模型：${settings?.model.orEmpty().ifBlank { conv?.model.orEmpty() }}")
            appendLine("执行模式：${ExecMode.from(settings?.approvalMode.orEmpty()).label}")
            appendLine("工作区：${access.displayCwd()}")
            appendLine("估算上下文：~$tokens tokens")
            if (usage != null && (usage.inputTokens > 0 || usage.outputTokens > 0)) {
                appendLine("累计用量：in ${usage.inputTokens} / out ${usage.outputTokens} / total ${usage.totalTokens}")
            }
            if (plan.isNotEmpty()) {
                appendLine("计划：${plan.count { it.status.name == "COMPLETED" }}/${plan.size} 完成")
            }
            if (sub.isNotEmpty()) {
                appendLine("子代理：${sub.count { it.state.name == "RUNNING" }} 运行中 / ${sub.size} 总计")
            }
            if (mcpTools.isNotEmpty()) {
                appendLine("MCP 工具：${mcpTools.size}")
            }
            val hooks = hookSystem.listHooks()
            if (hooks.isNotEmpty()) {
                appendLine("Hooks：${hooks.size}")
            }
            val g = session?.goalState?.goal ?: _goal.value
            if (g.hasGoal) {
                appendLine("目标：${g.text.take(80)} · ${g.status.label}")
                if (g.tokenBudget > 0) appendLine("目标预算：${g.tokensUsed}/${g.tokenBudget}")
            }
        }.trim()
    }

        private suspend fun extractMemory(session: Session, conversationId: Long) {
        val tools = session.turnToolOutputs.toList()
        if (tools.isEmpty()) return
        val user = session.lastUserText
        val assistant = session.lastAssistantText
        if (user.isBlank() || assistant.isBlank()) return
        runCatching {
            memorySystem.extractFromTurn(
                userMessage = user,
                assistantMessage = assistant,
                toolOutputs = tools,
                sessionId = conversationId.toString(),
            )
        }
    }

    private suspend fun ensureExtraTools(settings: ProviderSettings) {
        val token = pluginSystem.currentReloadToken()
        if (!pluginsLoaded || lastPluginReloadToken != token) {
            pluginsLoaded = true
            lastPluginReloadToken = token
            runCatching { BuiltinPluginSeeder.ensureSeeded(context, guestFs) }
            pluginTools = runCatching { pluginSystem.loadPluginTools(context) }.getOrDefault(emptyList())
            mobileDevTools = runCatching {
                val enabled = pluginSystem.discover().plugins.filter { it.enabled }
                val androidOn = enabled.any {
                    PluginSystem.BuiltinNativeMcp.providesAndroidTools(it.manifest.name)
                }
                val storageOn = enabled.any {
                    PluginSystem.BuiltinNativeMcp.providesStorageTools(it.manifest.name)
                }
                val htmlVideoOn = enabled.any {
                    PluginSystem.BuiltinNativeMcp.providesHtmlVideoTools(it.manifest.name)
                }
                val forgeOn = enabled.any {
                    PluginSystem.BuiltinNativeMcp.providesForgeTools(it.manifest.name)
                }
                buildList {
                    if (androidOn) addAll(AndroidDevToolset(context).tools())
                    if (storageOn) addAll(StorageCleanupToolset(context).tools())
                    if (htmlVideoOn) addAll(HtmlVideoToolset(context).tools())
                    if (forgeOn) addAll(DevForgeToolset(context).tools())
                }
            }.getOrDefault(emptyList())
        }
        val mcpText = settings.mcpServers
        if (mcpText != mcpConfigLoaded) {
            mcpConfigLoaded = mcpText
            mcpTools = if (mcpText.isNotBlank()) {
                runCatching { mcpManager.connectAll(mcpText) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            _mcpStatus.value = mcpManager.connected
        }
    }

    private suspend fun obtainSession(
        conversationId: Long,
        provider: ProviderDefinition,
        settings: ProviderSettings,
    ): Session {
        val execMode = ExecMode.from(settings.approvalMode)
        val existing = sessions[conversationId]
        if (existing != null &&
            existing.providerId == provider.id &&
            existing.model == settings.model
        ) {
            existing.approvalMode = execMode
            existing.engine.setCustomInstructions(settings.customInstructions)
            existing.engine.setPersona(settings.persona)
            if (sharedExtraTools.isNotEmpty()) {
                val known = existing.engine.listTools().map { it.first }.toSet()
                val missing = sharedExtraTools.filter { it.name !in known }
                if (missing.isNotEmpty()) existing.engine.addTools(missing)
            }
            if (!orchestrators.containsKey(conversationId)) {
                val orch = SubAgentOrchestrator(
                    toolsFactory = {
                        buildTools(existing.planTool, existing.goalState, existing.todoState, existing.planModeState, conversationId).filter { it.name != "spawn_agent" && it.name != "Agent" && it.name != "multi_agent" } + sharedExtraTools
                    },
                    settings = settings,
                    client = LlmClient(provider, trackerFor(conversationId)),
                    turnProvider = { TurnContext(provider, settings.model) },
                    parentHistoryProvider = { existing.engine.snapshotHistory() },
                    resolveRun = { modelSpec ->
                        resolveSubAgentRun(modelSpec, provider, conversationId)
                    },
                )
                orchestrators[conversationId] = orch
                val known = existing.engine.listTools().map { it.first }.toSet()
                val multi = listOf(orch.createSubAgentTool(), orch.createMultiAgentTool(), createZCodeAgentTool(orch)).filter { it.name !in known }
                if (multi.isNotEmpty()) existing.engine.addTools(multi)
                watchSubAgents(conversationId, orch)
            } else {
                refreshSubAgents(conversationId)
            }
            _goal.value = existing.goalState.goal
            touchSession(conversationId)
            trimSessionCache(conversationId)
            return existing
        }

        ensureHooksLoaded()
        val planTool = existing?.planTool ?: UpdatePlanTool()
        val goalState = existing?.goalState ?: GoalToolState()
        val todoState = existing?.todoState ?: TodoState()
        val planModeState = existing?.planModeState ?: PlanModeState()
        if (execMode == ExecMode.PLAN) planModeState.enter()
        if (!goalState.goal.hasGoal) {
            val fromDb = restoreGoalFromDb(conversationId)
            if (fromDb.hasGoal) {
                goalState.setGoal(fromDb)
            } else if (_goal.value.hasGoal) {
                goalState.setGoal(_goal.value)
            }
        }
        goalState.onGoalChange = { g ->
            if (sessions[conversationId]?.goalState === goalState) {
                _goal.value = g
            }
            controllerScope.launch {
                persistGoal(conversationId, g)
            }
        }
        val tools = buildTools(planTool, goalState, todoState, planModeState, conversationId)
        val client = LlmClient(provider, trackerFor(conversationId))
        val system = buildZCodeSystemPrompt(
            settings = settings,
            provider = provider,
            mode = execMode,
        )
        val engine = AgentEngine(
            tools = tools,
            client = client,
            systemPrompt = system,
            hooks = hookSystem,
            approve = { tool, args ->
                val liveMode = sessions[conversationId]?.approvalMode ?: execMode
                approveTool(conversationId, liveMode, tool, args)
            },
        )
        if (sharedExtraTools.isNotEmpty()) {
            engine.addTools(sharedExtraTools)
        }
        val orch = SubAgentOrchestrator(
            toolsFactory = {
                buildTools(planTool, goalState, todoState, planModeState, conversationId).filter { it.name != "spawn_agent" && it.name != "Agent" && it.name != "multi_agent" } + sharedExtraTools
            },
            settings = settings,
            client = client,
            turnProvider = { TurnContext(provider, settings.model) },
            parentHistoryProvider = { engine.snapshotHistory() },
        
            resolveRun = { modelSpec ->
                resolveSubAgentRun(modelSpec, provider, conversationId)
            },
        )
        orchestrators[conversationId] = orch
        engine.addTools(listOf(orch.createSubAgentTool(), orch.createMultiAgentTool(), createZCodeAgentTool(orch)))
        engine.setCustomInstructions(settings.customInstructions)
        engine.setPersona(settings.persona)

        val history = loadHistoryForEngine(conversationId)
        engine.seed(history)
        restorePlanFromHistory(conversationId, planTool)
        _planSteps.value = planTool.state.value
        watchSubAgents(conversationId, orch)

        val session = Session(
            conversationId = conversationId,
            engine = engine,
            providerId = provider.id,
            model = settings.model,
            planTool = planTool,
            goalState = goalState,
            todoState = todoState,
            planModeState = planModeState,
            approvalMode = execMode,
        )
        _goal.value = goalState.goal
        sessions[conversationId] = session
        touchSession(conversationId)
        trimSessionCache(conversationId)
        if (hookSystem.hasHooksFor(HookSystem.HookEvent.SESSION_START)) {
            controllerScope.launch {
                runCatching {
                    hookSystem.runEvent(
                        HookSystem.HookEvent.SESSION_START,
                        HookSystem.HookContext(
                            sessionInfo = mapOf(
                                "conversation_id" to conversationId.toString(),
                                "model" to settings.model,
                                "cwd" to access.guestCwd(),
                            ),
                        ),
                    )
                }
            }
        }
        return session
    }

    private suspend fun loadHistoryForEngine(conversationId: Long): List<ApiMessage> {
        val conv = repo.conversation(conversationId)
        val path = conv?.rolloutPath.orEmpty()
        if (path.isNotBlank()) {
            val file = File(path)
            if (file.exists() && file.length() > 0L) {
                val resumed = runCatching { SessionResumer().resume(file) }.getOrNull()
                if (resumed != null) {
                    if (resumed.totalInputTokens > 0 || resumed.totalOutputTokens > 0) {
                        trackerFor(conversationId).restore(
                            input = resumed.totalInputTokens.toInt(),
                            output = resumed.totalOutputTokens.toInt(),
                            cached = resumed.totalCachedTokens.toInt(),
                            total = (resumed.totalInputTokens + resumed.totalOutputTokens).toInt(),
                        )
                        refreshTokenUsage(conversationId)
                    }
                    if (resumed.wasTruncated) {
                        liveBus(conversationId).tryEmit(
                            ChatEvent.AssistantComplete("_(已从 rollout 恢复：截断了未完成的最后一轮)_"),
                        )
                    }
                    val msgs = resumed.messages.filter { it.role != "system" }
                    if (msgs.isNotEmpty()) return msgs
                }
            }
        }
        return rebuildHistory(conversationId)
    }

    private fun buildTools(
        planTool: UpdatePlanTool,
        goalState: GoalToolState,
        todo: TodoState = TodoState(),
        planMode: PlanModeState = PlanModeState(),
        conversationId: Long = 0L,
    ): List<Tool> {
        val cwd = { access.guestCwd() }
        return buildZCodeToolSurface(
            context = context,
            networkPolicy = networkPolicy,
            planTool = planTool,
            goalState = goalState,
            todo = todo,
            planMode = planMode,
            cwdProvider = cwd,
            onPlanModeChange = { mode ->
                sessions[conversationId]?.approvalMode = mode
                controllerScope.launch {
                    runCatching {
                        val s = settingsStore.settings.firstOrNull() ?: return@launch
                        settingsStore.update(s.copy(approvalMode = mode.id))
                    }
                }
            },
            askUser = ask@{ questions, rawArgs ->
                val session = sessions[conversationId] ?: return@ask "会话不可用"
                val deferred = kotlinx.coroutines.CompletableDeferred<String>()
                session.pendingAnswer = deferred
                val summary = questions.joinToString(" · ") { q ->
                    q.header.ifBlank { q.question.take(24) }
                }.ifBlank { "需要你的决定" }
                _pendingApproval.value = ApprovalRequest(
                    conversationId = conversationId,
                    toolName = "AskUserQuestion",
                    risk = ToolRisk.READ,
                    summary = summary,
                    modeLabel = session.approvalMode.label,
                    kind = "ask_user",
                    questions = questions,
                )
                deferred.await()
            },
            readSession = { sessionId, query, strategy, maxTokens ->
                readSessionContext(sessionId, query, strategy, maxTokens)
            },
            invokeSkill = { skill, args ->
                invokeSkillTool(skill, args)
            },
            requestEnterPlanApproval = enter@{ reason ->
                val session = sessions[conversationId] ?: return@enter false
                val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                session.pending = deferred
                _pendingApproval.value = ApprovalRequest(
                    conversationId = conversationId,
                    toolName = "EnterPlanMode",
                    risk = ToolRisk.READ,
                    summary = reason.ifBlank { "进入计划模式" },
                    modeLabel = session.approvalMode.label,
                    kind = "enter_plan",
                )
                deferred.await()
            },
            requestExitPlanApproval = exit@{ plan ->
                val session = sessions[conversationId] ?: return@exit false
                val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                session.pending = deferred
                _pendingApproval.value = ApprovalRequest(
                    conversationId = conversationId,
                    toolName = "ExitPlanMode",
                    risk = ToolRisk.READ,
                    summary = plan.take(240),
                    modeLabel = session.approvalMode.label,
                    kind = "exit_plan",
                    planText = plan,
                )
                deferred.await()
            },
        )
    }

    private suspend fun readSessionContext(
        sessionId: String,
        query: String,
        strategy: String,
        maxTokens: Int,
    ): String {
        val rawId = sessionId.removePrefix("sess_").removePrefix("#")
        val convId = rawId.toLongOrNull()
            ?: repo.conversationsByArchived(false).firstOrNull {
                it.sessionId == sessionId || it.sessionId == rawId || it.id.toString() == rawId
            }?.id
            ?: return "未找到会话 $sessionId"
        val msgs = repo.messages(convId).takeLast(40)
        val joined = msgs.joinToString("\n") { m ->
            val role = m.role
            val body = m.content.take(800)
            "[$role] $body"
        }
        val filtered = if (query.isBlank()) joined else {
            msgs.filter {
                it.content.contains(query, ignoreCase = true) ||
                    (it.toolName?.contains(query, ignoreCase = true) == true)
            }.joinToString("\n") { "[${it.role}] ${it.content.take(800)}" }
                .ifBlank { joined }
        }
        val header = "session=$sessionId strategy=$strategy query=$query"
        return (header + "\n" + filtered).take(maxTokens.coerceIn(500, 12000) * 4)
    }

    private suspend fun invokeSkillTool(skill: String, args: String?): String {
        val rawName = skill.trim().removePrefix("/")
        val installed = runCatching { com.andmx.agent.plugins.SkillInstaller(guestFs).listInstalled() }.getOrDefault(emptyList())
        val match = installed.firstOrNull {
            it.name.equals(rawName, true)
                || it.name.equals(rawName.substringAfterLast(':'), true)
        }
        if (match != null) {
            val skillPath = match.path
            val body = if (match.hasSkillMd) {
                runCatching { access.readText("$skillPath/SKILL.md") }.getOrDefault("")
            } else ""
            val scripts = runCatching {
                access.list(skillPath).filter {
                    it.endsWith(".sh") || it.endsWith(".py") || it.endsWith(".js")
                }.take(12)
            }.getOrDefault(emptyList())
            return formatSkillPayload(match.name, skillPath, body, scripts, args)
        }
        val pluginSkills = runCatching { pluginSystem.listInvocableSkills() }.getOrDefault(emptyList())
        val pluginHit = pluginSkills.firstOrNull { (id, path) ->
            id.equals(rawName, true)
                || id.substringAfter(':').equals(rawName, true)
                || path.endsWith("/$rawName")
                || path.endsWith("/$rawName/SKILL.md")
        }
        if (pluginHit != null) {
            val (id, path) = pluginHit
            val body = runCatching { access.readText(path) }.getOrDefault("")
            val dir = path.substringBeforeLast('/')
            val scripts = runCatching {
                access.list(dir).filter {
                    it.endsWith(".sh") || it.endsWith(".py") || it.endsWith(".js")
                }.take(12)
            }.getOrDefault(emptyList())
            return formatSkillPayload(id, path, body, scripts, args)
        }
        val names = (installed.map { it.name } + pluginSkills.map { it.first }).joinToString()
        return "技能未找到: $rawName。可用: ${names.ifBlank { "(无)" }}。Only invoke skills listed as available."
    }

    private fun formatSkillPayload(
        name: String,
        path: String,
        body: String,
        scripts: List<String>,
        args: String?,
    ): String = buildString {
        appendLine("<command-name>$name</command-name>")
        appendLine("<command-message>Skill loaded into context. Follow the instructions below.</command-message>")
        if (!args.isNullOrBlank()) appendLine("<command-args>$args</command-args>")
        appendLine()
        appendLine("# Skill: $name")
        appendLine("Path: $path")
        if (scripts.isNotEmpty()) appendLine("Bundled files: ${scripts.joinToString()}")
        appendLine()
        append(body.ifBlank { "(skill at $path; empty body)" })
        appendLine()
        appendLine()
        appendLine("Treat the skill body as loaded instructions for this turn. Do not re-invoke Skill for the same name.")
    }.take(14_000)


    private suspend fun rebuildHistory(conversationId: Long): List<ApiMessage> {
        val msgs = repo.messages(conversationId)
        val out = mutableListOf<ApiMessage>()
        var i = 0
        while (i < msgs.size) {
            val m = msgs[i]
            when (m.role) {
                "user" -> {
                    out += ApiMessage(role = "user", content = m.content)
                    i++
                }
                "assistant" -> {
                    out += ApiMessage(role = "assistant", content = m.content)
                    i++
                }
                "tool" -> {
                    val batch = mutableListOf<ApiToolCall>()
                    val results = mutableListOf<ApiMessage>()
                    while (i < msgs.size && msgs[i].role == "tool") {
                        val tm = msgs[i]
                        val callId = "hist-${tm.id}"
                        batch += ApiToolCall(
                            id = callId,
                            function = ApiFunctionCall(
                                name = tm.toolName ?: "tool",
                                arguments = tm.toolArgs.ifBlank { "{}" },
                            ),
                        )
                        results += ApiMessage(
                            role = "tool",
                            content = tm.content,
                            toolCallId = callId,
                            name = tm.toolName,
                        )
                        i++
                    }
                    out += ApiMessage(
                        role = "assistant",
                        content = null,
                        toolCalls = batch,
                    )
                    out += results
                }
                else -> i++
            }
        }
        return out
    }

    private suspend fun approveTool(
        conversationId: Long,
        mode: ExecMode,
        tool: Tool,
        args: JsonObject,
    ): Boolean {
        val sessionLive = sessions[conversationId]
        val planActive = sessionLive?.planModeState?.active == true || mode == ExecMode.PLAN
        if (planActive && !isPlanModeAllowed(tool.name)) {
            return false
        }
        val decision = when (mode) {
            ExecMode.FULL -> Decision.AUTO
            ExecMode.PLAN -> {
                if (isPlanModeAllowed(tool.name) || tool.risk == ToolRisk.READ) Decision.AUTO
                else Decision.DENY
            }
            ExecMode.AUTO_EDIT -> when (tool.risk) {
                ToolRisk.READ, ToolRisk.WRITE -> Decision.AUTO
                else -> Decision.PROMPT
            }
            ExecMode.CONFIRM -> ApprovalPolicy.decide(ApprovalMode.ASK, tool.risk)
        }
        return when (decision) {
            Decision.AUTO -> true
            Decision.DENY -> false
            Decision.PROMPT -> {
                val session = sessions[conversationId] ?: return false
                val deferred = CompletableDeferred<Boolean>()
                session.pending = deferred
                val summary = buildApprovalSummary(tool, args)
                _pendingApproval.value = ApprovalRequest(
                    conversationId = conversationId,
                    toolName = tool.name,
                    risk = tool.risk,
                    summary = summary,
                    modeLabel = mode.label,
                )
                deferred.await()
            }
        }
    }

    private fun buildApprovalSummary(tool: Tool, args: JsonObject): String {
        val preview = runCatching {
            com.andmx.agent.ToolArgs.preview(tool.name, args.toString())
        }.getOrDefault("")
        return buildString {
            append(tool.name)
            if (preview.isNotBlank()) append(" · ").append(preview.take(160))
            else {
                val raw = args.toString()
                if (raw.length > 2) append(" · ").append(raw.take(160))
            }
        }
    }


    private suspend fun buildZCodeSystemPrompt(
        settings: ProviderSettings,
        provider: ProviderDefinition,
        mode: ExecMode,
    ): String {
        val gitPath = if (projectManager.isRemote) {
            projectManager.currentRemoteSpec()?.remotePath
                ?: projectManager.hostPath.value
        } else {
            projectManager.guestMountPath
        }
        val git = runCatching {
            gitPath?.let { com.andmx.workspace.GitBaseline(context).collectGitInfo(it) }
        }.getOrNull()
        val host = projectManager.hostPath.value
        val gitStatus = when {
            git == null -> ""
            !git.isRepo -> ""
            host != null && !projectManager.isRemote -> {
                val info = runCatching {
                    com.andmx.workspace.GitBaseline(context).collectHostGitInfo(host)
                }.getOrNull()
                when {
                    info == null && git.hasChanges -> "${git.dirtyFileCount} files dirty"
                    info == null -> "clean"
                    info.dirtyFileCount > 0 -> "${info.dirtyFileCount} files dirty"
                    else -> "clean"
                }
            }
            git.hasChanges -> "${git.dirtyFileCount} files dirty"
            else -> "clean"
        }
        val skills = runCatching {
            com.andmx.agent.plugins.SkillInstaller(guestFs).listInstalled()
        }.getOrDefault(emptyList())
        val pluginSkills = runCatching { pluginSystem.listInvocableSkills() }.getOrDefault(emptyList())
        val skillsHint = buildString {
            skills.forEach { skill ->
                appendLine("- ${skill.name}: invoke via Skill tool with skill=\"${skill.name}\" (slash form /${skill.name})")
            }
            pluginSkills.forEach { (id, _) ->
                appendLine("- $id: invoke via Skill tool with skill=\"$id\" (plugin skill)")
            }
        }.trim()
        val agents = runCatching {
            val cwd = access.guestCwd()
            val candidates = listOf("AGENTS.md", "CLAUDE.md", "CODEX.md", ".zcode/AGENTS.md")
            candidates.mapNotNull { name ->
                val doc = runCatching { access.readText("$cwd/$name") }.getOrNull()
                if (doc.isNullOrBlank()) {
                    null
                } else {
                    buildString {
                        append("## ")
                        append(name)
                        appendLine()
                        append(doc.take(6000))
                    }
                }
            }.joinToString("\n\n")
        }.getOrDefault("")
        val memory = runCatching { memorySystem.promptFragment() }.getOrDefault("")
        val extra = buildString {
            if (sharedExtraTools.isNotEmpty()) {
                appendLine("# 扩展工具")
                appendLine("已加载 ${sharedExtraTools.size} 个 MCP/插件工具。名称带服务器前缀时表示来自 MCP。")
            }
            if (memory.isNotBlank()) {
                appendLine()
                append(memory.trimEnd())
            }
        }
        val env = ZCodePrompts.SessionEnv(
            cwd = access.guestCwd(),
            isGitRepo = git?.isRepo == true,
            platform = "android",
            shell = "sh",
            osVersion = "Android (proot Alpine guest)",
            modelLabel = provider.name + "/" + settings.model,
            branch = git?.branch.orEmpty(),
            mainBranch = "main",
            gitUser = git?.userName.orEmpty(),
            gitStatus = gitStatus,
            skillsHint = skillsHint,
        )
        return ZCodePrompts.assemble(
            mode = mode,
            env = env,
            projectDocs = agents,
            customInstructions = settings.customInstructions,
            persona = settings.persona,
            extra = extra,
        )
    }

    private suspend fun buildWorkspaceContext(settings: ProviderSettings): String {
        val kind = projectManager.workspaceKind.value
        val workspaceLine = when (kind) {
            WorkspaceKind.REMOTE -> {
                val spec = projectManager.currentRemoteSpec()
                "当前工作区为远程 SSH：${spec?.workspaceUri ?: projectManager.hostPath.value.orEmpty()}。所有 shell/文件/git 工具都在远端执行。"
            }
            WorkspaceKind.LOCAL -> {
                val host = projectManager.hostPath.value
                if (host.isNullOrBlank()) {
                    "当前尚未选择本地项目；默认工作目录为 /root。"
                } else {
                    "当前工作区为本地项目：$host（沙箱内映射为 ${projectManager.guestMountPath}）。"
                }
            }
        }
        val agents = runCatching { access.loadAgentsMdFragment() }.getOrDefault("")
        return buildString {
            appendLine("# 当前工作区")
            appendLine(workspaceLine)
            appendLine("工作目录：${access.guestCwd()}")
            appendLine("执行模式：${ExecMode.from(settings.approvalMode).label}")
            appendLine("优先工具：run_shell / read_file / write_file / edit_file / apply_patch / grep / glob / git / list_dir / browse / web_search / update_plan / create_goal / update_goal / get_goal / spawn_agent / multi_agent。")
            appendLine("复杂可并行子任务可用 spawn_agent 委派；需要多线程协作时用 multi_agent。")
            appendLine("搜索代码用 grep/glob，不要用 python 整文件 dump。")
            appendLine("改文件前先读再写；提交前用 git status/diff 自检。")
            if (agents.isNotBlank()) {
                appendLine()
                append(agents.trimEnd())
            }
        }
    }


    private fun writerFor(conversationId: Long): RolloutWriter =
        rolloutWriters.getOrPut(conversationId) { RolloutWriter(context) }

    private fun trackerFor(conversationId: Long): TokenUsageTracker =
        tokenTrackers.getOrPut(conversationId) { TokenUsageTracker() }

    private fun refreshTokenUsage(conversationId: Long) {
        val tracker = tokenTrackers[conversationId] ?: return
        val session = tracker.sessionUsage.value
        val last = tracker.lastTurnUsage.value
        _tokenUsage.value = TokenUsageUi(
            input = session.inputTokens,
            output = session.outputTokens,
            total = session.totalTokens.takeIf { it > 0 } ?: (session.inputTokens + session.outputTokens),
            lastTotal = last.totalTokens.takeIf { it > 0 } ?: (last.inputTokens + last.outputTokens),
        )
    }

    private suspend fun ensureRolloutSession(
        conversationId: Long,
        provider: ProviderDefinition,
        settings: ProviderSettings,
    ) {
        val conv = repo.conversation(conversationId) ?: return
        val writer = writerFor(conversationId)
        val existingPath = conv.rolloutPath
        val currentPath = writer.currentFilePath().orEmpty()
        if (existingPath.isNotBlank() &&
            currentPath == existingPath &&
            writer.currentSessionId().isNotBlank()
        ) {
            return
        }
        if (existingPath.isNotBlank() && File(existingPath).exists()) {
            val sid = conv.sessionId.ifBlank { File(existingPath).nameWithoutExtension }
            val attached = runCatching {
                writer.attachExisting(File(existingPath), sid)
            }.isSuccess
            if (attached) return
        }
        val sid = writer.startSession(
            cwd = access.displayCwd(),
            modelProvider = provider.id,
            baseInstructions = settings.customInstructions,
            cliVersion = "andmx-ui2",
        )
        persistRolloutMeta(conversationId, writer, sid, settings, conv.memoryMode, conv.firstUserMessage)
    }

    private suspend fun persistRolloutMeta(
        conversationId: Long,
        writer: RolloutWriter,
        sid: String,
        settings: ProviderSettings,
        memoryMode: String,
        firstUserMessage: String,
    ) {
        runCatching {
            val dao = com.andmx.data.AndmxDatabase.get(context).dao()
            dao.updateSessionMetadata(
                id = conversationId,
                path = writer.currentFilePath().orEmpty(),
                sessionId = sid,
                sandboxPolicy = if (projectManager.isRemote) "remote-ssh" else "workspace-write",
                model = settings.model,
                reasoningEffort = settings.reasoningEffort,
                memoryMode = memoryMode.ifBlank { "enabled" },
                firstUserMessage = firstUserMessage,
            )
        }
    }

    private fun watchSubAgents(conversationId: Long, orch: SubAgentOrchestrator) {
        subAgentWatchJobs.remove(conversationId)?.cancel()
        subAgentWatchJobs[conversationId] = controllerScope.launch {
            refreshSubAgents(conversationId)
            orch.events.collect { ev ->
                refreshSubAgents(conversationId)
                val bus = liveBus(conversationId)
                when (ev) {
                    is SubAgentOrchestrator.SubAgentEvent.Started ->
                        bus.tryEmit(ChatEvent.SubAgentStarted(ev.agentId, ev.task))
                    is SubAgentOrchestrator.SubAgentEvent.Delta ->
                        bus.tryEmit(ChatEvent.SubAgentDelta(ev.agentId, ev.text))
                    is SubAgentOrchestrator.SubAgentEvent.Completed ->
                        bus.tryEmit(ChatEvent.SubAgentCompleted(ev.agentId, ev.result))
                    is SubAgentOrchestrator.SubAgentEvent.Failed ->
                        bus.tryEmit(ChatEvent.SubAgentFailed(ev.agentId, ev.error))
                    else -> {}
                }
            }
        }
    }

    private fun refreshSubAgents(conversationId: Long) {
        val orch = orchestrators[conversationId]
        val snapshots = orch?.listAgentSnapshots().orEmpty()
        val others = _subAgents.value.filterNot { it.conversationId == conversationId }
        val mapped = snapshots.map {
            SubAgentUi(
                id = it.id,
                task = it.task,
                state = it.state.name,
                resultPreview = it.result.take(120),
                conversationId = conversationId,
            )
        }
        _subAgents.value = others + mapped
    }

    private fun applyGoalTokenUsage(session: Session) {
        val usage = tokenTrackers[session.conversationId]?.lastTurnUsage?.value ?: return
        val add = usage.totalTokens.takeIf { it > 0 } ?: (usage.inputTokens + usage.outputTokens)
        if (add <= 0) return
        val g = session.goalState.goal
        if (!g.hasGoal) return
        val next = g.copy(tokensUsed = g.tokensUsed + add, updatedAt = System.currentTimeMillis())
        session.goalState.setGoal(next)
        controllerScope.launch { persistGoal(session.conversationId, next) }
    }


    suspend fun handoffText(conversationId: Long): String {
        val settings = settingsStore.settings.firstOrNull()
        val conv = repo.conversation(conversationId)
        val session = sessions[conversationId]
        val goal = session?.goalState?.goal ?: _goal.value
        val tools = session?.turnToolOutputs.orEmpty()
        val plan = session?.planTool?.state?.value.orEmpty()
        val git = runCatching {
            com.andmx.workspace.GitBaseline(context).collectGitInfo(access.displayCwd())
        }.getOrNull()
        val recent = repo.messages(conversationId).takeLast(8).map {
            HandoffRunLogItem(
                title = it.toolName ?: it.role,
                state = if (it.toolError) "失败" else "完成",
                detail = it.content.take(160),
            )
        }
        return threadHandoffText(
            ThreadHandoffContext(
                project = access.displayCwd(),
                model = settings?.model.orEmpty().ifBlank { conv?.model.orEmpty() },
                approvalModeLabel = ExecMode.from(settings?.approvalMode.orEmpty()).label,
                goalText = goal.text,
                goalPhaseLabel = goal.status.label,
                goalNote = goal.note,
                tokenEstimate = estimateContextTokens(conversationId),
                contextPressureLabel = when {
                    estimateContextTokens(conversationId) > 90_000 -> "偏高"
                    estimateContextTokens(conversationId) > 40_000 -> "中等"
                    else -> "轻量"
                },
                messageCount = repo.messages(conversationId).size,
                toolCount = tools.size,
                mcpServerCount = mcpManager.connected.size,
                changedFiles = emptyList(),
                sourceLinks = emptyList(),
                recentActivity = recent,
                planItems = plan.map {
                    com.andmx.agent.TaskPlanItem(
                        title = it.content,
                        detail = "",
                        status = when (it.status.name) {
                            "COMPLETED" -> com.andmx.agent.PlanItemStatus.DONE
                            "IN_PROGRESS" -> com.andmx.agent.PlanItemStatus.ACTIVE
                            else -> com.andmx.agent.PlanItemStatus.PENDING
                        },
                    )
                },
                runtimeEnvironment = listOf(
                    if (projectManager.isRemote) "remote-ssh" else "local-proot",
                    access.displayCwd(),
                ),
            ),
        )
    }

    suspend fun exportConversationMarkdown(conversationId: Long): String {
        val conv = repo.conversation(conversationId)
        val msgs = repo.messages(conversationId)
        val md = buildString {
            appendLine("# ${conv?.title ?: "AndMX 对话"}")
            appendLine()
            appendLine("- 项目: `${conv?.project.orEmpty()}`")
            appendLine("- 模型: `${conv?.model.orEmpty()}`")
            appendLine("- 导出时间: ${java.time.Instant.now()}")
            appendLine()
            msgs.forEach { m ->
                when (m.role) {
                    "user" -> {
                        appendLine("## User")
                        appendLine(m.content)
                        appendLine()
                    }
                    "assistant" -> {
                        appendLine("## Assistant")
                        appendLine(m.content)
                        appendLine()
                    }
                    "tool" -> {
                        appendLine("### Tool · ${m.toolName.orEmpty()}")
                        if (m.toolArgs.isNotBlank()) {
                            appendLine("```json")
                            appendLine(m.toolArgs.take(2000))
                            appendLine("```")
                        }
                        appendLine("```")
                        appendLine(m.content.take(4000))
                        appendLine("```")
                        appendLine()
                    }
                }
            }
        }
        val path = "/root/andmx-export-${System.currentTimeMillis()}.md"
        val ok = runCatching { guestFs.writeText(path, md) }.isSuccess
        return if (ok) {
            "已导出到 `$path`（${msgs.size} 条）"
        } else {
            "导出失败（guest 写入失败）\n\n$md"
        }
    }

    suspend fun checkpointConversation(conversationId: Long): String {
        val settings = settingsStore.settings.firstOrNull() ?: return "设置未初始化"
        val providers = providerStore.providers.firstOrNull().orEmpty()
        val provider = providers.firstOrNull { it.id == settings.activeProviderId && it.enabled }
            ?: providerStore.primary.firstOrNull()
            ?: return "未配置提供商"
        ensureExtraTools(settings)
        val session = obtainSession(conversationId, provider, settings)
        val summary = session.engine.checkpointNow(
            settings,
            TurnContext(provider, settings.model),
            goal = session.goalState.goal.text,
        ) ?: return "无法生成交接检查点"
        return "已生成交接检查点并压缩历史。\n\n$summary"
    }

    fun setGoalText(conversationId: Long, text: String) {
        val now = System.currentTimeMillis()
        val session = sessions[conversationId]
        val next = if (session != null) {
            val cur = session.goalState.goal
            ConversationGoal(
                text = text,
                status = if (text.isBlank()) GoalStatus.EMPTY else GoalStatus.ACTIVE,
                phase = if (text.isBlank()) GoalPhase.EMPTY else GoalPhase.RUNNING,
                tokenBudget = cur.tokenBudget,
                tokensUsed = cur.tokensUsed,
                startedAt = cur.startedAt.takeIf { it > 0 } ?: now,
                updatedAt = now,
            ).also { session.goalState.setGoal(it) }
        } else {
            ConversationGoal(
                text = text,
                status = if (text.isBlank()) GoalStatus.EMPTY else GoalStatus.ACTIVE,
                phase = if (text.isBlank()) GoalPhase.EMPTY else GoalPhase.RUNNING,
                startedAt = now,
                updatedAt = now,
            )
        }
        _goal.value = next
        controllerScope.launch { persistGoal(conversationId, next) }
    }


    fun setGoalStatus(conversationId: Long, status: GoalStatus, note: String = "") {
        val session = sessions[conversationId]
        val cur = session?.goalState?.goal ?: _goal.value
        if (!cur.hasGoal && status != GoalStatus.EMPTY) return
        val now = System.currentTimeMillis()
        val phase = when (status) {
            GoalStatus.ACTIVE -> GoalPhase.RUNNING
            GoalStatus.PAUSED -> GoalPhase.PAUSED
            GoalStatus.BLOCKED, GoalStatus.USAGE_LIMITED, GoalStatus.BUDGET_LIMITED -> GoalPhase.FAILED
            GoalStatus.COMPLETE -> GoalPhase.READY
            GoalStatus.EMPTY -> GoalPhase.EMPTY
        }
        val next = cur.copy(
            status = status,
            phase = phase,
            updatedAt = now,
            note = note.ifBlank { cur.note },
        )
        session?.goalState?.setGoal(next)
        _goal.value = next
        controllerScope.launch { persistGoal(conversationId, next) }
    }


    fun dismissAmbient(id: String) {
        ambientSuggestions.dismiss(id)
    }

    private suspend fun refreshAmbient(conversationId: Long, session: Session) {
    }

    private suspend fun ensureHooksLoaded() {
        val token = pluginSystem.currentReloadToken()
        if (hooksLoaded && lastHooksReloadToken == token) return
        hooksLoaded = true
        lastHooksReloadToken = token
        val configs = mutableListOf<HookSystem.HookConfig>()
        runCatching {
            val path = "/root/.andmx/hooks.json"
            if (guestFs.exists(path)) {
                val raw = guestFs.readText(path)
                val el = kotlinx.serialization.json.Json.parseToJsonElement(raw)
                val arr = el as? kotlinx.serialization.json.JsonArray
                    ?: (el as? JsonObject)?.get("hooks") as? kotlinx.serialization.json.JsonArray
                arr?.forEach { item ->
                    val obj = item as? JsonObject ?: return@forEach
                    val eventRaw = (obj["event"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                    val command = (obj["command"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                    if (command.isBlank()) return@forEach
                    val event = HookSystem.parseEvent(eventRaw) ?: return@forEach
                    val timeout = (obj["timeout_ms"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
                        ?: (obj["timeoutMs"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
                        ?: 10_000L
                    val name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                    configs += HookSystem.HookConfig(
                        event = event,
                        command = command,
                        timeoutMs = timeout,
                        name = name.ifBlank { "config:$eventRaw" },
                    )
                }
            }
        }
        runCatching {
            val discovery = pluginSystem.discover()
            discovery.plugins.filter { it.enabled }.forEach { plugin ->
                plugin.manifest.hooks.forEach { entry ->
                    if (entry.command.isBlank()) return@forEach
                    val event = HookSystem.parseEvent(entry.event) ?: return@forEach
                    val command = if (entry.command.startsWith("/") || entry.command.contains("&&")) {
                        entry.command
                    } else {
                        "cd '${plugin.dir}' && ${entry.command}"
                    }
                    configs += HookSystem.HookConfig(
                        event = event,
                        command = command,
                        timeoutMs = entry.timeoutMs,
                        name = entry.name.ifBlank { "${plugin.manifest.name}:${entry.event}" },
                    )
                }
            }
        }
        hookSystem.replaceHooks(configs)
    }

    private suspend fun restoreGoalFromDb(conversationId: Long): ConversationGoal {
        val entity = repo.conversation(conversationId) ?: return ConversationGoal()
        val text = entity.goalText.trim()
        if (text.isBlank()) return ConversationGoal()
        val rawPhase = runCatching { GoalPhase.valueOf(entity.goalPhase) }.getOrDefault(GoalPhase.READY)
        val phase = when (rawPhase) {
            GoalPhase.RUNNING, GoalPhase.WAITING_APPROVAL -> GoalPhase.READY
            else -> rawPhase
        }
        val status = when (phase) {
            GoalPhase.RUNNING -> GoalStatus.ACTIVE
            GoalPhase.PAUSED -> GoalStatus.PAUSED
            GoalPhase.FAILED -> GoalStatus.BLOCKED
            GoalPhase.READY -> GoalStatus.COMPLETE
            GoalPhase.NEEDS_SETUP -> GoalStatus.BLOCKED
            GoalPhase.WAITING_APPROVAL -> GoalStatus.ACTIVE
            GoalPhase.EMPTY -> GoalStatus.EMPTY
        }
        val now = System.currentTimeMillis()
        return ConversationGoal(
            text = text,
            status = status,
            phase = phase,
            tokenBudget = entity.goalTokenBudget,
            tokensUsed = entity.goalTokensUsed,
            startedAt = entity.goalStartedAt.takeIf { it > 0L } ?: now,
            updatedAt = entity.goalUpdatedAt.takeIf { it > 0L } ?: now,
            note = entity.goalNote,
        )
    }

    private suspend fun persistGoal(conversationId: Long, goal: ConversationGoal) {
        if (conversationId <= 0L) return
        runCatching {
            repo.updateGoal(
                conversationId = conversationId,
                text = goal.text,
                phase = goal.phase.name,
                startedAt = goal.startedAt,
                updatedAt = goal.updatedAt,
                note = goal.note,
                tokenBudget = goal.tokenBudget,
                tokensUsed = goal.tokensUsed,
            )
        }
    }

    private suspend fun restorePlanFromHistory(conversationId: Long, planTool: UpdatePlanTool) {
        val msgs = repo.messages(conversationId)
        val last = msgs.lastOrNull {
            it.role == "tool" && it.toolName == "update_plan" && it.toolArgs.isNotBlank()
        } ?: return
        runCatching {
            val el = kotlinx.serialization.json.Json.parseToJsonElement(last.toolArgs)
            val obj = el as? JsonObject ?: return
            planTool.execute(obj)
        }
    }

    private suspend fun maybeAutoTitle(conversationId: Long, toolName: String, userText: String) {
        if (toolName !in setOf("write_file", "edit_file", "apply_patch", "run_shell", "grep", "glob")) return
        val conv = repo.conversation(conversationId) ?: return
        if (conv.title != "新任务" && conv.title.isNotBlank()) return
        val title = userText.lineSequence().firstOrNull { it.isNotBlank() }?.take(32) ?: return
        repo.rename(conversationId, title)
    }
}
