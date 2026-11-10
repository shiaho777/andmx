package com.andmx.ui2.chat


import android.content.Context
import android.os.Build
import com.andmx.BuildConfig
import com.andmx.android.AndroidContextProvider
import com.andmx.agent.AgentEngine
import com.andmx.agent.AgentEvent
import com.andmx.agent.AllowedPrompts
import com.andmx.agent.ApplyPatchTool
import com.andmx.agent.ApprovalMode
import com.andmx.agent.ApprovalOutcome
import com.andmx.agent.ApprovalPolicy
import com.andmx.agent.ApprovalRuleStore
import com.andmx.agent.BrowseTool
import com.andmx.agent.ContextBreakdown
import com.andmx.agent.ToolArgs
import com.andmx.agent.TracedLlm
import com.andmx.agent.ModelCallTrace
import com.andmx.agent.PermissionModes
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
import com.andmx.agent.zcode.WorkflowTools
import com.andmx.agent.zcode.applyDynamicWorkflowGate
import com.andmx.agent.zcode.buildZCodeToolSurface
import com.andmx.agent.zcode.CronTools
import com.andmx.agent.zcode.PlanModeState
import com.andmx.agent.zcode.TodoState
import com.andmx.agent.zcode.AskQuestion
import com.andmx.agent.zcode.AskUserQuestionParser
import com.andmx.agent.zcode.ZCodePrompts
import com.andmx.agent.CreateGoalTool
import com.andmx.agent.UpdateGoalTool
import com.andmx.agent.GetGoalTool
import com.andmx.agent.GoalToolState
import com.andmx.agent.threadHandoffText
import com.andmx.agent.ThreadHandoffContext
import com.andmx.agent.HandoffRunLogItem
import com.andmx.agent.ConversationGoal
import com.andmx.agent.AndroidUsePolicy
import com.andmx.agent.WebFetchPolicy
import com.andmx.agent.WebSearchTool
import com.andmx.agent.WriteFileTool
import com.andmx.agent.hooks.HookSystem
import com.andmx.agent.workflow.CreateWorkflowSource
import com.andmx.agent.workflow.ModelReferenceResolution
import com.andmx.agent.workflow.ModelReferenceResolver
import com.andmx.agent.workflow.dynamic.DynamicAskRequest
import com.andmx.agent.workflow.dynamic.DynamicAskResult
import com.andmx.agent.workflow.dynamic.DynamicWorkflowHost
import com.andmx.agent.workflow.dynamic.DynamicWorkflowRunner
import com.andmx.data.WorkflowJournalStore
import com.andmx.agent.suggestions.AmbientSuggestions
import com.andmx.agent.memory.MemorySystem
import com.andmx.agent.GoalPhase
import com.andmx.agent.GoalStatus
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
import com.andmx.data.CronStore
import com.andmx.data.rollout.EventMsg
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
import com.andmx.mcp.McpContextTools
import com.andmx.mcp.McpManager
import com.andmx.settings.ProviderSettings
import com.andmx.settings.ProviderStore
import com.andmx.settings.SettingsStore
import com.andmx.workspace.WorkspaceAccess
import com.andmx.workspace.WorkspaceKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ChatController private constructor(private val context: Context) {
    companion object {
        private const val TOOL_OUTPUT_DB_LIMIT = 12_000
        private const val TOOL_OUTPUT_MEMORY_LIMIT = 4_000
        private const val MAX_CACHED_SESSIONS = 4
        private const val ASK_HIDDEN_GRACE_MS = 60_000L
        private const val ASK_AUTO_RESOLVE_MS = 300_000L

        @Volatile private var instance: ChatController? = null

        fun get(context: Context): ChatController {
            val app = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: ChatController(app).also { instance = it }
            }
        }
    }
    private val settingsStore = SettingsStore(context)
    private val providerStore = ProviderStore(context)
    private val repo = ConversationRepository(context)
    private val projectManager = com.andmx.workspace.ProjectManager(context)
    private val access = WorkspaceAccess(context)
    private val networkPolicy = NetworkPolicy.PERMISSIVE
    private val guestFs = GuestFs(ProotRuntime(context))
    private val savedWorkflowStore = com.andmx.agent.workflow.SavedWorkflowStore(
        com.andmx.agent.workflow.GuestWorkflowFileSystem(guestFs),
        cwd = { access.guestCwd() },
    )

    /**
     * 脚本静态分析器：上游 `analyzeWorkflowScript` 原样跑在 WebView 上。
     *
     * 单槽记忆是上游 `workflow-script-analysis.ts` 的同构件——审批 gate 分析一次、handler 又
     * 分析一次，按脚本原文折叠成一次编译。
     */
    private val workflowAnalyzer by lazy {
        com.andmx.agent.workflow.script.MemoizedWorkflowScriptAnalyzer(
            com.andmx.agent.workflow.script.WebViewWorkflowScriptAnalyzer(context.applicationContext),
        )
    }
    private val memorySystem = MemorySystem(guestFs)
    private val memoryAgent = com.andmx.agent.memory.MemoryAgentRunner(
        guestFs,
        workspaceReadTools = {
            listOf(
                com.andmx.agent.ReadFileTool(context),
                com.andmx.agent.GrepTool(context),
                com.andmx.agent.GlobTool(context),
            )
        },
    )
    private val pluginSystem = PluginSystem(context, guestFs)
    private val hookSystem = HookSystem(context)
    private val ambientSuggestions = AmbientSuggestions(context)
    @Volatile private var hooksLoaded = false
    @Volatile private var hooksFileStamp = 0L
    @Volatile private var hooksProjectStamp = -1L
    @Volatile private var hooksTrustRevision = -1L
    private val mcpManager = McpManager(context)
    private val rolloutWriters = ConcurrentHashMap<Long, RolloutWriter>()
    private val tokenTrackers = ConcurrentHashMap<Long, TokenUsageTracker>()
    private val allowedPromptsByConversation = ConcurrentHashMap<Long, AllowedPrompts.Grants>()
    private val orchestrators = ConcurrentHashMap<Long, SubAgentOrchestrator>()
    private val backgroundTasksByConversation = ConcurrentHashMap<Long, com.andmx.agent.BackgroundTasks>()
    private val readFileStates = ConcurrentHashMap<Long, com.andmx.agent.ReadFileState>()
    private val shellWatchJobs = ConcurrentHashMap<Long, Job>()
    private val titleSidecarAttempted = ConcurrentHashMap.newKeySet<Long>()

    /**
     * 最近一次读到的设置快照。buildTools 是同步函数，拿不到 DataStore 的挂起读取，
     * 而动态工作流门又必须在每次建工具面时按当前设置求值，所以在这里缓存一份。
     */
    @Volatile private var settingsSnapshot = ProviderSettings()

    private val dynamicWorkflowEnabled: Boolean
        get() = settingsSnapshot.dynamicWorkflowToolsEnabled

    private fun backgroundTasksFor(conversationId: Long): com.andmx.agent.BackgroundTasks =
        backgroundTasksByConversation.getOrPut(conversationId) {
            com.andmx.agent.BackgroundTasks(context).also { tasks ->
                controllerScope.launch {
                    tasks.events.collect { ev ->
                        if (ev is com.andmx.agent.BackgroundTasks.Event.Terminated) {
                            sessions[conversationId]?.engine?.injectSystemReminder(
                                com.andmx.agent.SystemReminder.Source.TASK_STATUS,
                                tasks.notificationBody(ev.task),
                            )
                        }
                    }
                }
            }
        }

    fun previewFileExists(path: String): Boolean {
        if (projectManager.isRemote) return true
        if (path.startsWith("/storage/") || path.startsWith("/sdcard/")) return true
        if (runCatching { java.io.File(path).isFile }.getOrDefault(false)) return true
        val host = projectManager.hostPath.value ?: return false
        val target = com.andmx.ui2.files.resolveLocalBrowseTarget(
            initialPath = path,
            rootPath = host,
            hostPath = host,
            guestMount = projectManager.guestMountPath,
            rootfsRoot = guestFs.rootfsRoot.absolutePath,
        )
        return target.file != null
    }

    fun backgroundTasks(conversationId: Long): kotlinx.coroutines.flow.StateFlow<List<com.andmx.agent.BackgroundTasks.Snapshot>> =
        backgroundTasksFor(conversationId).taskList

    fun stopBackgroundTask(conversationId: Long, taskId: String): Boolean =
        backgroundTasksFor(conversationId).stop(taskId)

    fun backgroundTaskOutput(conversationId: Long, taskId: String, maxChars: Int = 12_000): String? =
        backgroundTasksFor(conversationId).tail(taskId, maxChars)



    private suspend fun resolveSubAgentRun(
        modelSpec: String,
        fallbackProvider: com.andmx.llm.provider.ProviderDefinition,
        conversationId: Long,
    ): Pair<com.andmx.llm.LlmApi, TurnContext> {
        val settings = settingsStore.settings.firstOrNull()
        val baseModel = settings?.model.orEmpty().ifBlank { fallbackProvider.models.keys.firstOrNull().orEmpty() }
        if (com.andmx.agent.multi.SubagentModelCatalog.isInherit(modelSpec)) {
            return TracedLlm(LlmClient(fallbackProvider, trackerFor(conversationId)), ModelCallTrace.Source.SUBAGENT) to TurnContext(fallbackProvider, baseModel)
        }
        val (baseSpec, level) = com.andmx.agent.multi.SubagentModelCatalog.splitLevel(modelSpec)
        val parsed = com.andmx.agent.multi.SubagentModelCatalog.parse(baseSpec)
        val providers = providerStore.providers.firstOrNull().orEmpty().filter { it.enabled }
        val providerId = parsed?.first.orEmpty()
        val modelId = parsed?.second.orEmpty()
        val provider = when {
            providerId.isNotBlank() -> providers.firstOrNull { it.id == providerId }
            modelId.isNotBlank() -> providers.firstOrNull { modelId in it.models } ?: providers.firstOrNull { it.id == fallbackProvider.id }
            else -> null
        } ?: fallbackProvider
        val model = modelId.ifBlank { baseModel }.ifBlank { provider.models.keys.firstOrNull().orEmpty() }
        return TracedLlm(LlmClient(provider, trackerFor(conversationId)), ModelCallTrace.Source.SUBAGENT) to TurnContext(provider, model, reasoningOverride = level)
    }

    private suspend fun loadSubagentExtras(): List<com.andmx.settings.CustomSubAgent> {
        val workspace = projectManager.hostPath.value
        return com.andmx.agent.multi.SubagentStorage.loadDiscoveredAgents(context, workspace)
    }

    private fun createZCodeAgentTools(orch: SubAgentOrchestrator, conversationId: Long): List<Tool> {
        val resolveAgent: suspend (String?) -> com.andmx.settings.CustomSubAgent? = { type ->
            val users = settingsStore.customSubAgents.firstOrNull().orEmpty()
            val state = settingsStore.subagentState.firstOrNull() ?: com.andmx.settings.SubagentStateFile()
            val extras = loadSubagentExtras()
            SubagentCatalog.resolve(type, users, state, extras)
        }
        val listTypes: suspend () -> List<String> = {
            val users = settingsStore.customSubAgents.firstOrNull().orEmpty()
            val state = settingsStore.subagentState.firstOrNull() ?: com.andmx.settings.SubagentStateFile()
            val extras = loadSubagentExtras()
            SubagentCatalog.listAll(users, state, extras).filter { it.enabled }.map { it.name }
        }
        return orch.createTaskTools(resolveAgent, listTypes, backgroundTasks = backgroundTasksFor(conversationId))
    }


    private val sessions = ConcurrentHashMap<Long, Session>()
    private val approvalMutex = Mutex()
    private val sessionAccessOrder = java.util.Collections.synchronizedList(mutableListOf<Long>())
    @Volatile private var pluginTools: List<Tool> = emptyList()
    @Volatile private var mobileDevTools: List<Tool> = emptyList()
    @Volatile private var mcpTools: List<Tool> = emptyList()
    @Volatile private var mcpContextTools: List<Tool> = emptyList()
    @Volatile private var mcpConfigLoaded: String? = null
    @Volatile private var pluginsLoaded = false
    @Volatile private var lastPluginReloadToken = -1L
    @Volatile private var lastHooksReloadToken = -1L

    private val sharedExtraTools: List<Tool>
        get() = pluginTools + mobileDevTools + mcpTools + mcpContextTools

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

    @Volatile private var focusedConversationId: Long = 0L
    private val _planOverlayActive = MutableStateFlow(false)
    val planOverlayActive: StateFlow<Boolean> = _planOverlayActive.asStateFlow()

    private fun syncPlanOverlay() {
        val id = focusedConversationId
        _planOverlayActive.value =
            sessions[id]?.approvalMode?.collaboration == com.andmx.agent.permission.CollaborationMode.PLAN
    }

    /** PLAN is a session collaboration mode. Non-plan selections replace it. */
    fun currentExecMode(conversationId: Long): ExecMode? = sessions[conversationId]?.approvalMode

    fun applyExecMode(mode: ExecMode, conversationId: Long) {
        val session = sessions[conversationId]
        val state = session?.planModeState
        if (mode.collaboration == com.andmx.agent.permission.CollaborationMode.PLAN) {
            val from = session?.approvalMode?.takeIf {
                it.collaboration != com.andmx.agent.permission.CollaborationMode.PLAN
            } ?: ExecMode.BUILD
            state?.enter(from)
        } else {
            state?.adopt(mode)
        }
        if (session != null) session.approvalMode = state?.mode ?: mode
        controllerScope.launch {
            runCatching {
                val s = settingsStore.settings.firstOrNull() ?: return@runCatching
                settingsStore.update(s.copy(approvalMode = mode.id))
            }
        }
        syncPlanOverlay()
    }

    fun exitPlan(conversationId: Long) {
        val back = sessions[conversationId]?.planModeState?.previous ?: ExecMode.BUILD
        applyExecMode(back, conversationId)
    }

    val ambient: StateFlow<List<AmbientSuggestions.Suggestion>> = ambientSuggestions.suggestions

    data class TokenUsageUi(
        val input: Int = 0,
        val output: Int = 0,
        val total: Int = 0,
        val lastTotal: Int = 0,
    )

    /** 上下文来源分解 UI 模型（ZCode chat.contextUsage.breakdown 对齐）。 */
    data class ContextBreakdownItem(val label: String, val chars: Int, val percent: Double)

    private val _contextBreakdown = MutableStateFlow<List<ContextBreakdownItem>>(emptyList())
    val contextBreakdown: StateFlow<List<ContextBreakdownItem>> = _contextBreakdown.asStateFlow()

    private fun refreshContextBreakdown(conversationId: Long) {
        val session = sessions[conversationId] ?: return
        val tools = session.engine.listTools()
        val mcp = tools.count { ToolArgs.isMcpName(it.first) }
        val builtin = (tools.size - mcp).coerceAtLeast(0)
        val result = ContextBreakdown.compute(
            history = session.engine.snapshotHistory(),
            systemToolCount = builtin,
            mcpToolCount = mcp,
        )
        _contextBreakdown.value = result.withPercent().map { (item, percent) ->
            ContextBreakdownItem(item.source.label, item.chars, percent)
        }
    }

    private val subAgentWatchJobs = ConcurrentHashMap<Long, kotlinx.coroutines.Job>()
    private val approvalRulesByConversation = ConcurrentHashMap<Long, ConcurrentHashMap<String, ApprovalScope>>()
    private val liveChatEvents = ConcurrentHashMap<Long, kotlinx.coroutines.flow.MutableSharedFlow<ChatEvent>>()
    private val cronStore = CronStore(context)
    private val runningConversations = ConcurrentHashMap.newKeySet<Long>()
    private val turnJobs = ConcurrentHashMap<Long, Job>()
    private val _runningIds = MutableStateFlow<Set<Long>>(emptySet())
    val runningIds: StateFlow<Set<Long>> = _runningIds.asStateFlow()

    fun isTurnRunning(conversationId: Long): Boolean =
        turnJobs[conversationId]?.isActive == true || runningConversations.contains(conversationId)

    private fun publishRunning() {
        _runningIds.value = runningConversations.toSet()
    }

    /** 回合跑在进程级作用域里，切换会话或退出界面都不会取消它。 */
    fun launchTurn(conversationId: Long, text: String, images: List<String> = emptyList()): Job {
        turnJobs[conversationId]?.let { if (it.isActive) return it }
        return startTurnJob(conversationId) {
            sendMessage(conversationId, text, images).collect { ev ->
                liveBus(conversationId).tryEmit(ev)
            }
        }
    }

    fun launchRegenerate(conversationId: Long): Job {
        turnJobs[conversationId]?.let { if (it.isActive) return it }
        return startTurnJob(conversationId) {
            regenerateTurn(conversationId).collect { ev ->
                liveBus(conversationId).tryEmit(ev)
            }
        }
    }

    fun cancelTurn(conversationId: Long) {
        turnJobs[conversationId]?.cancel()
        stopTurn(conversationId)
    }

    private fun startTurnJob(conversationId: Long, block: suspend () -> Unit): Job {
        val job = controllerScope.launch(start = CoroutineStart.LAZY) {
            runningConversations += conversationId
            publishRunning()
            AgentRunService.acquire(context)
            try {
                block()
            } finally {
                AgentRunService.release(context)
                runningConversations -= conversationId
                publishRunning()
            }
        }
        turnJobs[conversationId] = job
        job.invokeOnCompletion {
            if (turnJobs[conversationId] === job) turnJobs.remove(conversationId)
        }
        job.start()
        return job
    }
    private val automationTurnIds = ConcurrentHashMap.newKeySet<Long>()
    private val offPeakTurnIds = ConcurrentHashMap.newKeySet<Long>()
    private val workflowStore = com.andmx.data.WorkflowStore(context, guestFs)
    val workflowService = com.andmx.agent.workflow.WorkflowService(
        scope = controllerScope,
        store = workflowStore,
        actorRunner = { input -> runWorkflowActor(input) },
    )
    private val workflowJournalStore = WorkflowJournalStore(context)
    private val workflowActorStore = com.andmx.data.WorkflowActorStore(context)
    private val dynamicActorHistoryCache = ConcurrentHashMap<String, String>()
    private val dynamicActorCounts = ConcurrentHashMap<String, Int>()
    private val workflowEscalations = com.andmx.agent.workflow.dynamic.WorkflowEscalationRegistry()
    private val dynamicActorJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val dynamicActorHistorySerializer =
        kotlinx.serialization.builtins.ListSerializer(com.andmx.llm.ApiMessage.serializer())
    private val dynamicAskJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val dynamicActorPersonas = ConcurrentHashMap<String, String>()
    private val dynamicWorkflowHost = object : DynamicWorkflowHost {
        override suspend fun createActor(
            runId: String,
            siteId: String,
            ordinal: Int,
            personaJson: String,
            seedJson: String,
        ): String {
            val sessionId = "session:$runId:$siteId@$ordinal"
            dynamicActorPersonas[sessionId] = personaJson
            return sessionId
        }

        override fun actorMessageCount(sessionId: String): Int =
            dynamicActorCounts[sessionId] ?: 0

        override suspend fun ask(request: DynamicAskRequest): DynamicAskResult {
            val job = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
            if (job != null) dynamicAskJobs[request.requestId] = job
            return try {
                runDynamicAsk(request, continuation = null)
            } finally {
                dynamicAskJobs.remove(request.requestId)
            }
        }

        override suspend fun continueAsk(
            request: DynamicAskRequest,
            verdictJson: String,
        ): DynamicAskResult {
            val job = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
            if (job != null) dynamicAskJobs[request.requestId] = job
            return try {
                runDynamicAsk(request, continuation = verdictJson)
            } finally {
                dynamicAskJobs.remove(request.requestId)
            }
        }

        override fun cancelAsk(requestId: String) {
            dynamicAskJobs.remove(requestId)?.cancel()
        }

        override suspend fun worldRead(op: String, argsJson: String): String =
            dynamicWorldRead(op, argsJson)

        override suspend fun publishArtifact(runId: String, requestJson: String): String =
            dynamicPublishArtifact(runId, requestJson)

        override suspend fun markMutating(runId: String, siteId: String, ordinal: Int) {
            runCatching { dynamicWorkflowRunner.markMutating(runId, siteId, ordinal) }
        }
    }
    val dynamicWorkflowRunner: DynamicWorkflowRunner by lazy {
        DynamicWorkflowRunner(
            context = context,
            analyzer = workflowAnalyzer,
            host = dynamicWorkflowHost,
            runStore = workflowStore,
            journalStore = workflowJournalStore,
            scope = controllerScope,
        )
    }

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
        val autoDeadlineAt: Long? = null,
        val countdownVisibleAt: Long? = null,
        /** 原始输入预览（命令/路径/URL 单行 mono 展示，上游 previewPermissionInput）。 */
        val inputPreview: String = "",
        /** 选择「允许本会话/本项目」时将持久化的规则描述（上游 approvalPermissionScopes）。 */
        val rulePreview: String = "",
        /** 审批原因（plan 模式拒绝等场景的描述字段）。 */
        val reason: String = "",
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
        val permissions: com.andmx.agent.permission.PermissionService = com.andmx.agent.permission.PermissionService(),
        var pending: CompletableDeferred<Boolean>? = null,
        var pendingArgs: String? = null,
        var pendingAnswer: CompletableDeferred<String>? = null,
        var answerTimer: Job? = null,
        var answerSnoozed: Boolean = false,
        val turnToolOutputs: MutableList<Pair<String, String>> = mutableListOf(),
        var lastUserText: String = "",
        var lastAssistantText: String = "",
        var lastAssistantTurnId: String? = null,
    )

    /** 审批作用域（ZCode chat.permission 对齐）。 */
    enum class ApprovalScope { ONCE, SESSION_ALLOW, SESSION_DENY, PROJECT_ALLOW, PROJECT_DENY, PROJECT_ASK }

    val approvalRuleStore = com.andmx.agent.ApprovalRuleStore(context)
    private val sessionEventStore = com.andmx.data.session.SessionEventStore(
        java.io.File(context.filesDir, "session-events"),
    )
    private val sessionTraces = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /**
     * AskUserQuestion 自动决议全局开关（上游 interaction-registry autoResolution 对齐）：
     * 开启时提问先经 60s 隐藏宽限，之后显示倒计时，300s 未作答自动以空答案继续；
     * 关闭时所有活动倒计时立即转为 snoozed（无限等待用户）。
     */
    private val _questionAutoResolve = MutableStateFlow(true)
    val questionAutoResolve: StateFlow<Boolean> = _questionAutoResolve.asStateFlow()

    fun setQuestionAutoResolve(enabled: Boolean) {
        controllerScope.launch {
            val cur = settingsStore.settings.firstOrNull() ?: ProviderSettings()
            settingsStore.update(cur.copy(askAutoResolve = enabled))
        }
    }

    /** 用户点「稍后」：停掉倒计时，问题无限期挂起直到作答或取消。 */
    fun snoozeUserQuestion() {
        val req = _pendingApproval.value ?: return
        if (req.kind != "ask_user") return
        val session = sessions[req.conversationId] ?: return
        snoozeAnswerTimer(session)
        _pendingApproval.value = req.copy(autoDeadlineAt = null, countdownVisibleAt = null)
    }

    private fun snoozeAnswerTimer(session: Session) {
        session.answerSnoozed = true
        session.answerTimer?.cancel()
        session.answerTimer = null
    }

    private fun clearAnswerTimer(session: Session?) {
        session?.answerTimer?.cancel()
        session?.answerTimer = null
        session?.answerSnoozed = false
    }

    init {
        controllerScope.launch {
            settingsStore.settings.collect { s ->
                val dynamicWorkflowChanged =
                    s.dynamicWorkflowToolsEnabled != settingsSnapshot.dynamicWorkflowToolsEnabled
                settingsSnapshot = s
                if (dynamicWorkflowChanged) {
                    // 工具面在会话建立时装配，切换开关后必须重建空闲会话才能生效。
                    sessions.keys.toList()
                        .filter { it !in runningConversations }
                        .forEach { clearSession(it) }
                }
                WebFetchPolicy.browserEnabled = s.browserControlEnabled
                WebFetchPolicy.allowInsecureCertificates = s.allowInsecureCertificates
                AndroidUsePolicy.enabled = s.androidUseEnabled
                val was = _questionAutoResolve.value
                _questionAutoResolve.value = s.askAutoResolve
                if (was && !s.askAutoResolve) {
                    sessions.values.forEach { snoozeAnswerTimer(it) }
                    val cur = _pendingApproval.value
                    if (cur?.kind == "ask_user" && cur.autoDeadlineAt != null) {
                        _pendingApproval.value = cur.copy(autoDeadlineAt = null, countdownVisibleAt = null)
                    }
                }
            }
        }
        controllerScope.launch {
            while (true) {
                delay(15_000L)
                runCatching { cronTick() }
                runCatching { scheduleAutomationWake() }
            }
        }
        com.andmx.automation.AutomationWake.onDue = { cronTick() }
        controllerScope.launch { runCatching { scheduleAutomationWake() } }
    }

    /**
     * ZCode automation scheduler 对齐：到期任务经 sendMessage 复用主循环跑一轮；
     * 会话正在跑普通回合时顺延 60s，避免两条流并发写同一 history。
     */
    private suspend fun cronTick() {
        cronStore.due(System.currentTimeMillis()).forEach { auto ->
            if (runningConversations.contains(auto.conversationId)) {
                cronStore.defer(auto.id, 60_000L)
            } else {
                controllerScope.launch { runAutomation(auto) }
            }
        }
    }

    /**
     * Entry point for the alarm receiver when no UI created the controller.
     * The turn itself immediately acquires AgentRunService, so the receiver can
     * return without holding a broadcast for the whole model call.
     */
    suspend fun runDueAutomationsNow() {
        cronTick()
        scheduleAutomationWake()
    }

    private suspend fun runAutomation(auto: com.andmx.data.CronAutomationEntity) {
        val offPeak = auto.title.startsWith("[闲时]")
        val bucket = if (offPeak) offPeakTurnIds else automationTurnIds
        bucket += auto.conversationId
        try {
            launchTurn(auto.conversationId, auto.prompt).join()
        } finally {
            bucket -= auto.conversationId
            runCatching { cronStore.markRan(auto.id) }
            runCatching { scheduleAutomationWake() }
            runCatching {
                repo.setTaskStatus(auto.conversationId, "completed")
                repo.setUnreadAt(auto.conversationId, System.currentTimeMillis())
            }
        }
    }

    private suspend fun scheduleAutomationWake() {
        val next = cronStore.list()
            .filter { it.enabled && it.lifecycleStatus == "active" && it.nextRunAt > 0L }
            .minOfOrNull { it.nextRunAt }
            ?: return
        com.andmx.automation.AutomationWake.schedule(context, next)
    }

    /**
     * dwf actor 回合（ZCode workflow_child 对齐）：隔离 AgentEngine 跑子会话，
     * 工具面 = 会话全量 - workflow 变更工具 + submit_result/escalate。
     * escalate 经 ask_user 审批路径回到 run 所属会话的用户。
     */
    private suspend fun runWorkflowActor(
        input: com.andmx.agent.workflow.WorkflowAgentInput,
    ): com.andmx.agent.workflow.WorkflowAgentResult {
        val convId = workflowStore.runConversationId(input.runId)
        val settings = settingsStore.settings.firstOrNull()
            ?: return com.andmx.agent.workflow.WorkflowAgentResult("settings unavailable")
        val providers = providerStore.providers.firstOrNull().orEmpty()
        val provider = providers.firstOrNull { it.id == settings.activeProviderId && it.enabled }
            ?: providerStore.primary.firstOrNull()
            ?: return com.andmx.agent.workflow.WorkflowAgentResult("no provider")
        val client = TracedLlm(LlmClient(provider, trackerFor(convId)), ModelCallTrace.Source.WORKFLOW)
        input.onChildSessionStarted?.invoke("wf_actor_${input.activityId}", settings.model)
        val submitSlot = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val blocked = setOf(
            "CreateWorkflow", "AmendWorkflow", "SaveWorkflow",
            "ResumeWorkflowRun", "CancelWorkflowRun",
        )
        val tools = buildTools(
            UpdatePlanTool(), GoalToolState(), TodoState(), PlanModeState(), convId,
        ).filter { it.name !in blocked } + listOf(
            com.andmx.agent.workflow.SubmitResultTool(submitSlot),
            com.andmx.agent.workflow.EscalateTool { question, ctx ->
                askWorkflowEscalation(convId, input, question, ctx)
            },
        )
        val systemPrompt = buildString {
            append("You are a workflow actor running inside a ZCode-style durable workflow.\n")
            append("Run: ${input.runId} | phase: ${input.phase}")
            input.node?.let { append(" | node: ${it.id}") }
            input.collection?.let { append(" | collection: ${it.collectionId}") }
            append("\nDo not create, amend, or resume workflows — you are inside one.")
            append("\nReturn your result as the final assistant message, or call submit_result for structured output.")
        }
        val engine = AgentEngine(
            tools = tools,
            client = client,
            systemPrompt = systemPrompt,
            approve = { tool, args ->
                if (convId > 0L) {
                    val mode = sessions[convId]?.approvalMode ?: ExecMode.from(settings.approvalMode)
                    approveTool(convId, mode, tool, args)
                } else {
                    ApprovalOutcome.Rejected("Workflow run has no conversation to approve tools")
                }
            },
        )
        var finalText = ""
        var failure: String? = null
        try {
            engine.runTurn(settings, TurnContext(provider, settings.model), input.prompt)
                .collect { ev ->
                    when (ev) {
                        is AgentEvent.Assistant -> finalText = ev.text
                        is AgentEvent.Failed -> failure = ev.message
                        else -> {}
                    }
                }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e.message
        }
        failure?.let { throw IllegalStateException(it) }
        return com.andmx.agent.workflow.WorkflowAgentResult(
            response = submitSlot.get() ?: finalText,
            sessionId = "wf_actor_${input.activityId}",
            model = settings.model,
        )
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private suspend fun runDynamicAsk(
        request: DynamicAskRequest,
        continuation: String?,
    ): DynamicAskResult {
        val convId = request.conversationId
        val settings = settingsStore.settings.firstOrNull()
            ?: return DynamicAskResult.Fail("settings unavailable")
        val providers = providerStore.providers.firstOrNull().orEmpty()
        val provider = providers.firstOrNull { it.id == settings.activeProviderId && it.enabled }
            ?: providerStore.primary.firstOrNull()
            ?: return DynamicAskResult.Fail("no provider")
        val (client, turnContext) =
            if (com.andmx.agent.multi.SubagentModelCatalog.isInherit(request.subagentModel)) {
                TracedLlm(
                    LlmClient(provider, trackerFor(convId)),
                    ModelCallTrace.Source.WORKFLOW,
                ) to TurnContext(provider, settings.model)
            } else {
                resolveSubAgentRun(request.subagentModel.orEmpty(), provider, convId)
            }
        val persona = runCatching {
            Json.parseToJsonElement(
                request.personaJson ?: dynamicActorPersonas[request.sessionId].orEmpty(),
            ).jsonObject
        }.getOrNull()
        val personaName = persona?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
        val personaSystem = persona?.get("system")?.jsonPrimitive?.contentOrNull.orEmpty()
        val submitSlot = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val blocked = setOf(
            "CreateWorkflow", "AmendWorkflow", "SaveWorkflow",
            "ResumeWorkflowRun", "CancelWorkflowRun",
        )
        val tools = buildTools(
            UpdatePlanTool(), GoalToolState(), TodoState(), PlanModeState(), convId,
        ).filter { it.name !in blocked }.toMutableList().apply {
            if (request.typed || request.schemaJson != null) add(com.andmx.agent.workflow.SubmitResultTool(submitSlot))
            val escalationCount = java.util.concurrent.atomic.AtomicInteger(0)
            add(
                com.andmx.agent.workflow.EscalateTool { question, ctx ->
                    if (escalationCount.incrementAndGet() > com.andmx.agent.workflow.dynamic.MAX_ESCALATIONS_PER_ASK) {
                        return@EscalateTool com.andmx.agent.workflow.dynamic.ESCALATION_BUDGET_EXHAUSTED
                    }
                    escalateDynamicQuestion(request, personaName, question, ctx)
                },
            )
        }
        val systemPrompt = buildString {
            append("You are a dynamic-workflow actor.\n")
            append("Run: ").append(request.runId)
            if (personaName.isNotBlank()) append(" | actor: ").append(personaName)
            append("\nWork on the instructions from the coordinator.")
            if (personaSystem.isNotBlank()) append("\n\n").append(personaSystem)
            if (request.typed) {
                append("\n\nThis is a typed ask. Call submit_result with a JSON value matching the schema.")
                request.schemaJson?.let { append("\nSchema: ").append(it.take(4_000)) }
            } else {
                append("\nReturn the result as your final assistant message.")
            }
            if (!continuation.isNullOrBlank()) {
                append("\n\nA previous submission was rejected. Fix it and submit again.\n")
                append(continuation.take(4_000))
            }
        }
        val engine = AgentEngine(
            tools = tools,
            client = client,
            systemPrompt = systemPrompt,
            maxSteps = 24,
            approve = { tool, args ->
                if (tool.risk == ToolRisk.WRITE || tool.risk == ToolRisk.EXECUTE) {
                    dynamicWorkflowHost.markMutating(request.runId, request.siteId, request.ordinal)
                }
                val mode = sessions[convId]?.approvalMode ?: ExecMode.from(settings.approvalMode)
                approveTool(convId, mode, tool, args)
            },
        )
        loadDynamicActorHistory(request).takeIf { it.isNotEmpty() }?.let(engine::seed)
        var finalText = ""
        var failure: String? = null
        try {
            engine.runTurn(settings, turnContext, request.instructions)
                .collect { ev ->
                    when (ev) {
                        is AgentEvent.Assistant -> finalText = ev.text
                        is AgentEvent.Failed -> failure = ev.message
                        else -> {}
                    }
                }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e.message
        }
        failure?.let { return DynamicAskResult.Fail(it) }
        saveDynamicActorHistory(request, engine)
        val submitted = submitSlot.get()
        if (submitted != null) return DynamicAskResult.Submit(submitted)
        if (request.typed) return DynamicAskResult.Fail("typed ask ended without submit_result")
        return DynamicAskResult.Text(finalText)
    }

    private suspend fun loadDynamicActorHistory(
        request: DynamicAskRequest,
    ): List<com.andmx.llm.ApiMessage> {
        if (request.sessionId.isBlank()) return emptyList()
        val direct = readActorHistory(request.sessionId)
        if (direct.isNotEmpty()) return direct
        val seed = parseActorSeed(request.seedJson) ?: return emptyList()
        val source = readActorHistory(seed.first)
        if (source.isEmpty() || seed.second <= 0) return emptyList()
        val prefix = com.andmx.agent.workflow.dynamic.DynamicActorHistory.copyPrefix(
            source,
            seed.second,
        )
        if (prefix.isEmpty()) return emptyList()
        val raw = runCatching {
            dynamicActorJson.encodeToString(dynamicActorHistorySerializer, prefix)
        }.getOrNull() ?: return emptyList()
        dynamicActorHistoryCache[request.sessionId] = raw
        dynamicActorCounts[request.sessionId] = prefix.size
        runCatching { workflowActorStore.save(request.sessionId, request.runId, raw) }
        return prefix
    }

    private suspend fun readActorHistory(sessionId: String): List<com.andmx.llm.ApiMessage> {
        if (sessionId.isBlank()) return emptyList()
        val raw = dynamicActorHistoryCache[sessionId]
            ?: workflowActorStore.load(sessionId)?.also { dynamicActorHistoryCache[sessionId] = it }
            ?: return emptyList()
        return runCatching {
            dynamicActorJson.decodeFromString(dynamicActorHistorySerializer, raw)
        }.getOrDefault(emptyList())
    }

    private fun parseActorSeed(seedJson: String?): Pair<String, Int>? {
        if (seedJson.isNullOrBlank() || seedJson == "null") return null
        return runCatching {
            val obj = Json.parseToJsonElement(seedJson).jsonObject
            val source = obj["sourceSessionId"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: return null
            source to (obj["messageCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0)
        }.getOrNull()
    }

    private suspend fun saveDynamicActorHistory(request: DynamicAskRequest, engine: AgentEngine) {
        if (request.sessionId.isBlank()) return
        val snapshot = com.andmx.agent.workflow.dynamic.DynamicActorHistory.snapshot(
            engine.snapshotHistory(),
        )
        if (snapshot.isEmpty()) return
        val raw = runCatching {
            dynamicActorJson.encodeToString(dynamicActorHistorySerializer, snapshot)
        }.getOrNull() ?: return
        dynamicActorHistoryCache[request.sessionId] = raw
        dynamicActorCounts[request.sessionId] = snapshot.size
        runCatching { workflowActorStore.save(request.sessionId, request.runId, raw) }
    }

    private suspend fun dynamicWorldRead(op: String, argsJson: String): String {
        val args = runCatching { Json.parseToJsonElement(argsJson).jsonArray }.getOrDefault(JsonArray(emptyList()))
        fun arg(index: Int): String =
            (args.getOrNull(index) as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun argInt(index: Int, fallback: Int): Int =
            (args.getOrNull(index) as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: fallback
        val cwd = access.guestCwd()
        return when (op) {
            "read" -> {
                val path = arg(0).ifBlank { return JsonPrimitive("").toString() }
                JsonPrimitive(access.readText(path, limit = 512 * 1024)).toString()
            }
            "glob" -> {
                val pattern = arg(0).ifBlank { return JsonArray(emptyList()).toString() }
                val matcher = runCatching {
                    java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$pattern")
                }.getOrNull()
                val base = access.hostFile(cwd) ?: return JsonArray(emptyList()).toString()
                val rootPath = base.toPath()
                val out = mutableListOf<String>()
                java.nio.file.Files.walk(rootPath).use { stream ->
                    stream.filter { java.nio.file.Files.isRegularFile(it) }
                        .forEach { path ->
                            if (out.size >= 2_000) return@forEach
                            val rel = rootPath.relativize(path).toString().replace('\\', '/')
                            if (matcher == null || matcher.matches(java.nio.file.Path.of(rel)) ||
                                matcher.matches(path.fileName)
                            ) {
                                out += rel
                            }
                        }
                }
                buildJsonArray { out.sorted().forEach { add(JsonPrimitive(it)) } }.toString()
            }
            "grep" -> {
                val pattern = arg(0)
                if (pattern.isBlank()) return JsonArray(emptyList()).toString()
                val regex = runCatching { Regex(pattern) }.getOrElse {
                    throw IllegalArgumentException("invalid grep pattern: ${it.message}")
                }
                val glob = arg(1).takeIf { it.isNotBlank() }
                val matcher = glob?.let {
                    runCatching { java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$it") }.getOrNull()
                }
                val base = access.hostFile(cwd) ?: return JsonArray(emptyList()).toString()
                val rootPath = base.toPath()
                val out = mutableListOf<JsonObject>()
                var bytes = 0
                java.nio.file.Files.walk(rootPath).use { stream ->
                    stream.filter { java.nio.file.Files.isRegularFile(it) }.forEach { path ->
                        if (out.size >= 2_000 || bytes > 256 * 1024) return@forEach
                        val rel = rootPath.relativize(path).toString().replace('\\', '/')
                        if (matcher != null && !matcher.matches(java.nio.file.Path.of(rel)) &&
                            !matcher.matches(path.fileName)
                        ) {
                            return@forEach
                        }
                        if (java.nio.file.Files.size(path) > 512 * 1024) return@forEach
                        val text = runCatching { path.toFile().readText() }.getOrNull() ?: return@forEach
                        text.lineSequence().forEachIndexed { index, line ->
                            if (regex.containsMatchIn(line)) {
                                out += buildJsonObject {
                                    put("path", rel)
                                    put("line", index + 1)
                                    put("text", line)
                                }
                                bytes += line.length + rel.length + 24
                            }
                        }
                    }
                }
                buildJsonArray { out.forEach { add(it) } }.toString()
            }
            "git-changed-files" -> {
                val base = arg(0).takeIf { it.isNotBlank() }
                val diff = access.executeShell(
                    if (base == null) "git diff --name-only" else "git diff --name-only ${shellQuote(base)}",
                    cwd = cwd,
                ).stdout
                val untracked = access.executeShell(
                    "git ls-files --others --exclude-standard",
                    cwd = cwd,
                ).stdout
                val paths = (diff.lineSequence() + untracked.lineSequence())
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
                    .toList()
                buildJsonArray { paths.forEach { add(JsonPrimitive(it)) } }.toString()
            }
            "git-diff" -> {
                val base = arg(0).takeIf { it.isNotBlank() }
                val path = arg(1).takeIf { it.isNotBlank() }
                val cmd = buildString {
                    append("git diff --no-color")
                    if (base != null) append(' ').append(shellQuote(base))
                    if (path != null) append(" -- ").append(shellQuote(path))
                }
                JsonPrimitive(access.executeShell(cmd, cwd = cwd).stdout.take(512 * 1024)).toString()
            }
            "git-status" -> {
                val raw = access.executeShell("git status --porcelain=v1 -b", cwd = cwd)
                if (raw.exitCode != 0) return JsonObject(emptyMap()).toString()
                val lines = raw.stdout.lineSequence().toList()
                val branch = lines.firstOrNull { it.startsWith("## ") }
                    ?.removePrefix("## ")
                    ?.substringBefore("...")
                    ?.substringBefore(' ')
                    ?.takeIf { it.isNotBlank() && !it.startsWith("HEAD") }
                val staged = mutableListOf<String>()
                val unstaged = mutableListOf<String>()
                val untracked = mutableListOf<String>()
                lines.drop(1).forEach { line ->
                    if (line.length < 3) return@forEach
                    val x = line[0]
                    val y = line[1]
                    val path = line.substring(3).trim()
                    when {
                        x == '?' && y == '?' -> untracked += path
                        else -> {
                            if (x != ' ') staged += path
                            if (y != ' ') unstaged += path
                        }
                    }
                }
                buildJsonObject {
                    branch?.let { put("branch", it) }
                    put("clean", staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty())
                    put("staged", buildJsonArray { staged.forEach { add(JsonPrimitive(it)) } })
                    put("unstaged", buildJsonArray { unstaged.forEach { add(JsonPrimitive(it)) } })
                    put("untracked", buildJsonArray { untracked.forEach { add(JsonPrimitive(it)) } })
                }.toString()
            }
            "git-log" -> {
                val count = argInt(0, 20).coerceIn(1, 100)
                val raw = access.executeShell(
                    "git log -n $count --pretty=format:%H%x1f%s%x1f%an%x1f%aI",
                    cwd = cwd,
                )
                val commits = raw.stdout.lineSequence().mapNotNull { line ->
                    val parts = line.split('\u001f')
                    if (parts.size < 4) null else buildJsonObject {
                        put("hash", parts[0])
                        put("subject", parts[1])
                        put("author", parts[2])
                        put("date", parts[3])
                    }
                }.toList()
                buildJsonArray { commits.forEach { add(it) } }.toString()
            }
            "run" -> {
                val command = arg(0)
                if (command.isBlank()) throw IllegalArgumentException("world.run requires a command")
                val argv = (args.getOrNull(1) as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
                val full = (listOf(command) + argv).joinToString(" ") { shellQuote(it) }
                val timeout = (args.getOrNull(2) as? JsonObject)
                    ?.get("timeoutMs")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                val result = kotlinx.coroutines.withTimeoutOrNull(
                    (timeout ?: 120_000L).coerceIn(1_000L, 600_000L),
                ) {
                    access.executeShell(full, cwd = cwd)
                } ?: return buildJsonObject {
                    put("exitCode", 124)
                    put("stdout", "")
                    put("stderr", "world.run timed out")
                }.toString()
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout.take(256 * 1024))
                    put("stderr", result.stderr.take(256 * 1024))
                }.toString()
            }
            else -> throw IllegalArgumentException("unsupported world read: $op")
        }
    }

    private suspend fun dynamicPublishArtifact(runId: String, requestJson: String): String {
        val request = runCatching { Json.parseToJsonElement(requestJson).jsonObject }.getOrElse {
            throw IllegalArgumentException("invalid artifact request: ${it.message}")
        }
        val id = request["id"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "artifact" }
        val op = request["op"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "markdown" }
        val version = request["version"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
        val sourcePath = request["path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val content = request["content"]?.jsonPrimitive?.contentOrNull
            ?: sourcePath?.let { access.readText(it) }
            ?: ""
        val suffix = when (op) {
            "markdown" -> ".md"
            "file" -> sourcePath?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".bin"
            else -> ".bin"
        }
        val relative = "artifacts/$id-v$version$suffix".replace("..", "_")
        val stored = workflowStore.writeArtifact(runId, relative, content)
        val contentType = when {
            op == "markdown" -> "text/markdown"
            suffix == ".txt" -> "text/plain"
            suffix == ".json" -> "application/json"
            else -> "application/octet-stream"
        }
        return buildJsonObject {
            put("id", id)
            put("kind", op)
            put("version", version)
            put("contentType", contentType)
            put("bytes", content.toByteArray().size)
            put("uri", stored)
            sourcePath?.let { put("sourcePath", it) }
            put("publishedAt", System.currentTimeMillis())
        }.toString()
    }

    private suspend fun escalateDynamicQuestion(
        request: DynamicAskRequest,
        personaName: String,
        question: String,
        context: String,
    ): String {
        val convId = request.conversationId
        val engine = sessions[convId]?.engine
        val turnJob = turnJobs[convId]?.takeIf { it.isActive }
        val askUser: suspend () -> String = {
            askWorkflowEscalation(
                convId,
                com.andmx.agent.workflow.WorkflowAgentInput(
                    activityId = request.requestId,
                    phase = "dynamic",
                    prompt = request.instructions,
                    runId = request.runId,
                    task = request.instructions,
                ),
                question,
                context,
            )
        }
        if (engine == null || turnJob == null) return askUser()
        val qid = workflowEscalations.mintQuestionId(request.runId)
        val actorRef = "${request.siteId}#${request.ordinal}"
        val actorName = personaName.takeIf { it.isNotEmpty() }
        val normalizedContext = context.trim().takeIf { it.isNotEmpty() }
        val answer = CompletableDeferred<String>()
        workflowEscalations.park(
            com.andmx.agent.workflow.dynamic.PendingWorkflowQuestion(
                qid = qid,
                runId = request.runId,
                actor = actorRef,
                actorName = actorName,
                question = question,
                context = normalizedContext,
                askedAt = System.currentTimeMillis(),
            ),
        ) { answer.complete(it) }
        engine.injectSystemReminder(
            com.andmx.agent.SystemReminder.Source.QUEUED_SYSTEM_NOTIFICATION,
            com.andmx.agent.workflow.dynamic.formatWorkflowEscalationNotification(
                runLabel = request.runId,
                runId = request.runId,
                qid = qid,
                actor = actorName ?: actorRef,
                question = question,
                context = normalizedContext,
            ),
        )
        val turnEnded = CompletableDeferred<Unit>()
        val handle = turnJob.invokeOnCompletion { turnEnded.complete(Unit) }
        return try {
            kotlinx.coroutines.selects.select<String?> {
                answer.onAwait { it }
                turnEnded.onAwait { null }
            } ?: run {
                if (workflowEscalations.withdraw(qid)) askUser() else answer.await()
            }
        } finally {
            handle.dispose()
            workflowEscalations.withdraw(qid)
        }
    }

    private suspend fun askWorkflowEscalation(
        conversationId: Long,
        input: com.andmx.agent.workflow.WorkflowAgentInput,
        question: String,
        context: String,
    ): String {
        val session = sessions[conversationId]
            ?: return com.andmx.agent.workflow.EscalateTool.DEFERRED
        val deferred = kotlinx.coroutines.CompletableDeferred<String>()
        session.pendingAnswer = deferred
        _pendingApproval.value = ApprovalRequest(
            conversationId = conversationId,
            toolName = "escalate",
            risk = ToolRisk.READ,
            summary = "workflow ${input.runId}: $question",
            modeLabel = session.approvalMode.label,
            kind = "ask_user",
            questions = listOf(
                AskQuestion(
                    header = "workflow",
                    question = question,
                    options = emptyList(),
                ),
            ),
        )
        notifyIfBackground(conversationId, "工作流等待确认", question)
        return try {
            val raw = deferred.await()
            val (answers, _) = AskUserQuestionParser.parseAnswersJson(raw)
            answers.values.firstOrNull()?.jsonPrimitive?.contentOrNull
                ?: com.andmx.agent.workflow.EscalateTool.DEFERRED
        } finally {
            session.pendingAnswer = null
        }
    }

    fun resolveApproval(allow: Boolean) {
        resolveApprovalScoped(allow, ApprovalScope.ONCE)
    }

    /**
     * 带作用域的审批：本会话允许/拒绝会为该会话登记一条 grant/deny 规则，
     * 后续相同 (tool, key) 的请求不再弹窗；PROJECT_ALLOW 额外写入持久存储
     * （ZCode allowForProject 对齐），跨会话生效。
     */
    fun resolveApprovalScoped(allow: Boolean, scope: ApprovalScope) {
        val req = _pendingApproval.value
        _pendingApproval.value = null
        val session = req?.let { sessions[it.conversationId] }
        val allowed = allow && scope != ApprovalScope.SESSION_DENY && scope != ApprovalScope.PROJECT_DENY
        if (req != null && session != null && scope != ApprovalScope.ONCE) {
            val rule = approvalRuleKey(req.toolName, session.pendingArgs.orEmpty())
            if (rule != null) {
                approvalRulesByConversation
                    .computeIfAbsent(req.conversationId) { ConcurrentHashMap() }[rule] = scope
                val behavior = when (scope) {
                    ApprovalScope.PROJECT_ALLOW -> ApprovalRuleStore.RuleBehavior.ALLOW
                    ApprovalScope.PROJECT_DENY -> ApprovalRuleStore.RuleBehavior.DENY
                    ApprovalScope.PROJECT_ASK -> ApprovalRuleStore.RuleBehavior.ASK
                    else -> null
                }
                if (behavior != null) {
                    val key = rule.split(':', limit = 2)
                    approvalRuleStore.add(projectKey(), key.first(), rule, behavior)
                }
                if (scope == ApprovalScope.SESSION_ALLOW) {
                    val parts = rule.split(':', limit = 2)
                    session.permissions.grantSessionAllow(
                        com.andmx.agent.ApprovalRuleStore.wireToolName(parts.first()),
                        com.andmx.agent.ApprovalRuleStore.ruleContentOf(
                            com.andmx.agent.ApprovalRuleStore.Rule(parts.first(), rule),
                        ),
                    )
                }
                if (scope == ApprovalScope.SESSION_DENY) {
                    val parts = rule.split(':', limit = 2)
                    session.permissions.denyForSession(
                        com.andmx.agent.ApprovalRuleStore.wireToolName(parts.first()),
                        com.andmx.agent.ApprovalRuleStore.ruleContentOf(
                            com.andmx.agent.ApprovalRuleStore.Rule(parts.first(), rule),
                        ),
                    )
                }
                if (scope == ApprovalScope.SESSION_ALLOW || scope == ApprovalScope.SESSION_DENY) {
                    persistPermissionGrants(req.conversationId)
                }
            }
        }
        session?.pending?.complete(allowed)
        session?.pending = null
        session?.pendingArgs = null
        if (session?.pendingAnswer != null) {
            session.pendingAnswer?.complete(
                if (allowed) """{"answers":{"__default__":"approved"}}""" else """{"answers":{"__default__":"rejected"}}"""
            )
            session.pendingAnswer = null
            clearAnswerTimer(session)
        }
    }

    /** 审批面板展示用：本会话/项目级允许将持久化的规则描述（上游 scope 行）。 */
    private fun approvalRulePreview(toolName: String, args: String): String {
        val key = approvalRuleKey(toolName, args) ?: return ""
        return when {
            key.endsWith(":any") -> "所有 ${ToolArgs.canonical(toolName)} 调用"
            key.contains(":prefix:") -> "命令前缀: ${key.substringAfter(":prefix:")} …"
            key.contains(":file:") -> "文件: ${key.substringAfter(":file:")}"
            else -> key
        }
    }

    /** 规则键：命令类按前缀（首个 token），文件/其它按工具名 + 摘要 key。 */
    private fun approvalRuleKey(toolName: String, args: String): String? {
        val canonical = ToolArgs.canonical(toolName)
        return when (canonical) {
            "shell", "git" -> {
                val command = ToolArgs.shellCommand(toolName, args)
                if (command.isBlank()) null
                else "$canonical:prefix:${command.trim().split(Regex("\\s+")).first().lowercase()}"
            }
            "read", "write", "edit", "multiedit", "patch", "list", "grep", "glob" -> {
                val path = ToolArgs.filePath(toolName, args)
                if (path.isBlank()) null
                else "$canonical:file:${path.trimEnd('*')}"
            }
            else -> "$canonical:any"
        }
    }

    private fun sessionRuleFor(conversationId: Long, toolName: String, args: String): ApprovalScope? {
        val rule = approvalRuleKey(toolName, args) ?: return null
        val scoped = approvalRulesByConversation[conversationId]?.get(rule)
        if (scoped != null && scoped != ApprovalScope.ONCE) return scoped
        // 文件规则同时命中同路径其它编辑操作；命令前缀规则在 approvalRuleKey 已归一
        return null
    }

    /** 当前项目键（本地 hostPath / 远程 workspaceUri），持久规则按此隔离。 */
    fun projectKey(): String =
        com.andmx.agent.ApprovalRuleStore.projectKeyOf(projectManager.hostPath.value)

    fun resolveUserQuestion(answersJson: String) {
        val req = _pendingApproval.value
        _pendingApproval.value = null
        val session = req?.let { sessions[it.conversationId] }
        session?.pendingAnswer?.complete(answersJson)
        session?.pendingAnswer = null
        clearAnswerTimer(session)
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
        backgroundTasksByConversation[conversationId]?.cancelAll()
        refreshSubAgents(conversationId)
        val session = sessions[conversationId]
        session?.pending?.complete(false)
        session?.pending = null
        session?.pendingAnswer?.complete("""{"answers":{"__default__":"cancelled"}}""")
        session?.pendingAnswer = null
        clearAnswerTimer(session)
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

    /**
     * turnSteer（ZCode 对齐）：把用户消息注入运行中会话的引擎历史，
     * 下一个模型步即会看到；仅在有活跃会话时生效。
     */
    /** 模型可见且随会话持久化的 system-reminder（rewind/fork 等历史连续性事件）。 */
    suspend fun injectPersistedReminder(conversationId: Long, source: com.andmx.agent.SystemReminder.Source, body: String) {
        if (body.isBlank()) return
        val wrapped = com.andmx.agent.SystemReminder.wrap(source, body).trimEnd()
        runCatching { repo.addMessage(conversationId, "reminder", wrapped) }
        sessions[conversationId]?.engine?.injectSystemReminder(source, body)
    }

    fun injectUserMessage(conversationId: Long, text: String) {
        val session = sessions[conversationId] ?: return
        session.engine.injectUserMessage(text)
    }

    fun clearSession(conversationId: Long) {
        sessions.remove(conversationId)
        orchestrators.remove(conversationId)?.shutdown()
        subAgentWatchJobs.remove(conversationId)?.cancel()
        shellWatchJobs.remove(conversationId)?.cancel()
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
        injectPlanFileReminderIfPresent(conversationId, session.engine)
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
            if (isTurnRunning(id)) return@forEach
            val session = sessions[id]
            if (session?.pending != null) return@forEach
            clearSession(id)
        }
    }

    suspend fun focusConversation(conversationId: Long) {
        focusedConversationId = conversationId
        val existing = sessions[conversationId]
        refreshSubAgents(conversationId)
        refreshTokenUsage(conversationId)
        syncPlanOverlay()
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
    ): Flow<ChatEvent> = sendMessageFlow(conversationId, text, images)

    private fun sendMessageFlow(
        conversationId: Long,
        text: String,
        images: List<String> = emptyList(),
    ): Flow<ChatEvent> = flow {
        emit(ChatEvent.UserMessage(text, images))
        repo.addMessage(conversationId, "user", text, imageUrls = images)
        var firstUserMessage = false
        runCatching {
            val conv = repo.conversation(conversationId)
            if (conv != null && conv.firstUserMessage.isBlank()) {
                firstUserMessage = true
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

        if (firstUserMessage && titleSidecarAttempted.add(conversationId)) {
            launchTitleSidecar(conversationId, text, provider, settings.model)
        }

        ensureExtraTools(settings)
        ensureHooksLoaded()
        val session = obtainSession(conversationId, provider, settings)
        session.lastUserText = text
        session.turnToolOutputs.clear()
        val memoryRecall = if (settings.memoryEnabled) {
            runCatching { memorySystem.recallFragment(text) }.getOrDefault("")
        } else {
            ""
        }
        session.engine.setTurnMemoryFragment(memoryRecall)
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
        ensureRolloutSession(conversationId, provider, settings)
        val turn = TurnContext(
            provider,
            settings.model,
            transcriptPath = repo.conversation(conversationId)?.rolloutPath,
        )
        val toolArgsById = mutableMapOf<String, String>()
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
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
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
            is AgentEvent.StepStarted -> emit(ChatEvent.StepStarted(event.turn, event.step, event.startedAtMs))
            is AgentEvent.FirstToken -> emit(ChatEvent.FirstToken(event.turn, event.step, event.atMs))
            is AgentEvent.ReasoningDelta -> emit(ChatEvent.ReasoningChunk(event.text))
            is AgentEvent.ReasoningDone -> emit(ChatEvent.ReasoningDone)
            is AgentEvent.ToolCallArgsDelta -> emit(ChatEvent.ToolCallArgsDelta(event.index, event.id, event.name, event.argumentsSoFar))
            is AgentEvent.Assistant -> {
                emit(ChatEvent.AssistantComplete(event.text))
                if (event.text.isNotBlank()) {
                    session.lastAssistantText = event.text
                    session.lastAssistantTurnId = turnId
                    repo.addMessage(conversationId, "assistant", event.text)
                }
            }
            is AgentEvent.ToolStarted -> {
                toolArgsById[event.id] = event.arguments
                emit(ChatEvent.ToolCallStarted(event.id, event.name, event.arguments))
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
                emit(ChatEvent.ToolCallFinished(event.id, outDb, event.isError, event.imageUrls, event.durationMs))
                repo.addMessage(
                    conversationId,
                    "tool",
                    outDb,
                    toolName = event.name,
                    toolArgs = args,
                    toolError = event.isError,
                    imageUrls = event.imageUrls.orEmpty(),
                )
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
            is AgentEvent.GoalVerifying -> {
                emit(ChatEvent.GoalVerifying(event.iteration))
            }
            is AgentEvent.GoalVerified -> {
                emit(
                    ChatEvent.GoalVerified(
                        iteration = event.iteration,
                        passed = event.passed,
                        reason = event.reason,
                        nextAction = event.nextAction,
                    ),
                )
                runCatching {
                    writer.writeEventMsg(
                        EventMsg(
                            type = "goal_verification",
                            turnId = turnId,
                            errorMessage = if (event.passed) null else event.reason,
                        ),
                    )
                }
            }
            is AgentEvent.Retrying -> {
                emit(ChatEvent.Retrying(event.attempt, event.maxAttempts, event.delayMs))
            }
            is AgentEvent.Failed -> {
                runCatching {
                    writer.writeEventMsg(
                        EventMsg(type = "task_failed", turnId = turnId, errorMessage = event.message),
                    )
                }
                notifyIfBackground(conversationId, "任务失败", event.message)
                runCatching { repo.setTaskStatus(conversationId, "failed") }
                if (focusedConversationId != conversationId) {
                    runCatching { repo.setUnreadAt(conversationId, System.currentTimeMillis()) }
                }
                emit(ChatEvent.Error(event.message))
            }
            is AgentEvent.Done -> {
                // 上游 turn_complete 兜底：整回合无 Assistant 事件但 history 末尾已有
                // 权威回答（流中断/通知驱动路径）时补写一条，按内容防双写。
                if (session.lastAssistantTurnId != turnId) {
                    val fallback = session.engine.snapshotHistory()
                        .lastOrNull { it.role == "assistant" }?.content?.trim().orEmpty()
                    if (fallback.isNotBlank() && fallback != session.lastAssistantText.trim()) {
                        session.lastAssistantText = fallback
                        session.lastAssistantTurnId = turnId
                        repo.addMessage(conversationId, "assistant", fallback)
                        emit(ChatEvent.AssistantComplete(fallback))
                    }
                }
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
                notifyIfBackground(
                    conversationId, "任务完成",
                    session.lastAssistantText.trim().ifBlank { "回合已结束" },
                )
                runCatching { repo.setTaskStatus(conversationId, "completed") }
                if (focusedConversationId != conversationId) {
                    runCatching { repo.setUnreadAt(conversationId, System.currentTimeMillis()) }
                }
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
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
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


    suspend fun compactConversation(conversationId: Long, instructions: String = ""): String {
        val settings = settingsStore.settings.firstOrNull()
            ?: return "设置未初始化"
        val providers = providerStore.providers.firstOrNull().orEmpty()
        val provider = providers.firstOrNull { it.id == settings.activeProviderId && it.enabled }
            ?: providerStore.primary.firstOrNull()
            ?: return "未配置提供商"
        ensureExtraTools(settings)
        ensureHooksLoaded()
        val session = obtainSession(conversationId, provider, settings)
        val msg = session.engine.compactNow(settings, TurnContext(provider, settings.model), instructions)
            ?: return "上下文较短，无需压缩"
        refreshTokenUsage(conversationId)
        return msg
    }

    data class RewindResult(
        val reverted: Int,
        val unsafe: Int,
        val unsafePaths: List<String>,
    )

    /**
     * Revert agent-made file changes back to their pre-edit origin. Mirrors
     * ZCode's rewind safety model: each tracked file is re-read first; only
     * those whose current content still matches the recorded post-edit content
     * are restored. Anything changed afterwards (by bash, the user, or another
     * tool) is reported as unsafe and left untouched.
     */
    suspend fun revertFileChanges(conversationId: Long, sinceMs: Long = 0L): RewindResult {
        val pending = com.andmx.workspace.ChangeTracker.changesFor(conversationId).value
            .filter { it.timestamp >= sinceMs }
        if (pending.isEmpty()) return RewindResult(0, 0, emptyList())
        var reverted = 0
        val unsafe = mutableListOf<String>()
        for (change in pending) {
            val resolved = runCatching { access.resolvePath(change.path) }.getOrDefault(change.path)
            val current = runCatching { access.readText(resolved) }.getOrNull()
            if (current == null || current != change.newContent) {
                unsafe += change.path
                continue
            }
            runCatching {
                if (change.isNew) {
                    access.deleteFile(resolved)
                } else {
                    access.writeText(resolved, change.oldContent)
                }
                com.andmx.workspace.ChangeTracker.remove(conversationId, change.path)
                reverted++
            }.onFailure { unsafe += change.path }
        }
        return RewindResult(reverted, unsafe.size, unsafe)
    }

    /** 单文件还原：同样的“当前内容必须仍等于记录的修改后内容”安全闸。 */
    suspend fun revertFileChange(conversationId: Long, path: String): RewindResult {
        val change = com.andmx.workspace.ChangeTracker.changesFor(conversationId).value
            .firstOrNull { com.andmx.workspace.GuestPaths.same(it.path, path) }
            ?: return RewindResult(0, 0, emptyList())
        val resolved = runCatching { access.resolvePath(change.path) }.getOrDefault(change.path)
        val current = runCatching { access.readText(resolved) }.getOrNull()
        if (current == null || current != change.newContent) {
            return RewindResult(0, 1, listOf(change.path))
        }
        return runCatching {
            if (change.isNew) access.deleteFile(resolved) else access.writeText(resolved, change.oldContent)
            com.andmx.workspace.ChangeTracker.remove(conversationId, change.path)
            RewindResult(1, 0, emptyList())
        }.getOrElse { RewindResult(0, 1, listOf(change.path)) }
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

        private fun extractMemory(session: Session, conversationId: Long) {
        val tools = session.turnToolOutputs.toList()
        if (tools.isEmpty()) return
        val user = session.lastUserText
        val assistant = session.lastAssistantText
        if (user.isBlank() || assistant.isBlank()) return
        controllerScope.launch {
            val enabled = runCatching { settingsStore.settings.firstOrNull()?.memoryEnabled ?: true }.getOrDefault(true)
            if (!enabled) return@launch
            runCatching {
                memorySystem.extractFromTurn(
                    userMessage = user,
                    assistantMessage = assistant,
                    toolOutputs = tools,
                    sessionId = conversationId.toString(),
                )
            }
            if (com.andmx.agent.memory.MemoryAgentRunner.shouldSkip(
                    user, tools, MemorySystem.MEMORY_DIR,
                )
            ) {
                return@launch
            }
            val settings = settingsStore.settings.firstOrNull() ?: return@launch
            val provider = providerStore.providers.firstOrNull().orEmpty()
                .firstOrNull { it.id == settings.activeProviderId && it.enabled }
                ?: providerStore.primary.firstOrNull()
                ?: return@launch
            val model = settings.model.ifBlank {
                provider.models.keys.firstOrNull().orEmpty()
            }
            val transcript = buildList {
                add("[user] ${user.take(2_000)}")
                tools.forEach { (n, o) -> add("[tool:$n] ${o.take(600)}") }
                add("[assistant] ${assistant.take(3_000)}")
            }
            runCatching {
                memoryAgent.extract(
                    client = LlmClient(provider, trackerFor(conversationId)),
                    provider = provider,
                    model = model,
                    settings = settings,
                    transcript = transcript,
                )
            }
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
            mcpContextTools = mcpContextToolsFor(mcpManager)
            _mcpStatus.value = mcpManager.connected
        }
    }

    private fun mcpContextToolsFor(manager: McpManager): List<Tool> =
        if (manager.connected.isEmpty()) emptyList() else McpContextTools.build(manager)

    /** /mcp connect <server>：按配置名连接单个 MCP 服务器并把工具挂入会话工具面。 */
    suspend fun mcpConnect(name: String): String {
        val settings = settingsStore.settings.firstOrNull() ?: return "设置不可用。"
        val cfg = com.andmx.mcp.McpServerConfig.parse(settings.mcpServers)
            .firstOrNull { it.name == name } ?: return "未配置服务器 `$name`。"
        if (mcpManager.connected.any { it.name == name }) return "`$name` 已连接。"
        val added = runCatching { mcpManager.connectOne(cfg) }
            .getOrElse { return "连接失败：${it.message}" }
        if (added.isEmpty()) return "连接失败：未获得工具（查看日志）。"
        mcpTools = mcpTools + added
        mcpContextTools = mcpContextToolsFor(mcpManager)
        _mcpStatus.value = mcpManager.connected
        return "已连接 `$name` · ${added.size} tools"
    }

    /** /mcp disconnect <server>：断开并按 `name__` 前缀摘掉该服务器的工具。 */
    fun mcpDisconnect(name: String): String {
        if (!mcpManager.disconnect(name)) return "`$name` 未连接。"
        mcpTools = mcpTools.filterNot { it.name.startsWith("${name}__") }
        mcpContextTools = mcpContextToolsFor(mcpManager)
        _mcpStatus.value = mcpManager.connected
        return "已断开 `$name`。"
    }

    /** /plugins enable|disable <id>：持久化开关并重载插件工具面。 */
    suspend fun pluginSetEnabled(name: String, enabled: Boolean): String {
        val found = runCatching { pluginSystem.discover().plugins }
            .getOrDefault(emptyList())
            .firstOrNull { it.manifest.name.equals(name, true) || it.manifest.name == name }
            ?: return "未找到插件 `$name`。"
        val ok = runCatching { pluginSystem.setEnabled(found.manifest.name, enabled) }
            .getOrDefault(false)
        if (!ok) return "无法${if (enabled) "启用" else "禁用"} `$name`。"
        lastPluginReloadToken = -1L
        pluginTools = runCatching { pluginSystem.loadPluginTools(context) }.getOrDefault(pluginTools)
        return "已${if (enabled) "启用" else "禁用"}插件 `${found.manifest.name}`（新会话生效全部能力）。"
    }

    suspend fun pluginStatusLines(): List<String> = runCatching {
        pluginSystem.discover().plugins.map { p ->
            "- ${if (p.enabled) "✓" else "○"} `${p.manifest.name}`" +
                (p.manifest.description.take(50).takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        }
    }.getOrDefault(emptyList())

    private suspend fun obtainSession(
        conversationId: Long,
        provider: ProviderDefinition,
        settings: ProviderSettings,
    ): Session {
        com.andmx.workspace.ChangeTracker.load(conversationId)
        val execMode = ExecMode.from(settings.approvalMode)
        val existing = sessions[conversationId]
        if (existing != null &&
            existing.providerId == provider.id &&
            existing.model == settings.model
        ) {
            if (existing.planModeState.collaboration == com.andmx.agent.permission.CollaborationMode.PLAN) {
                existing.approvalMode = existing.planModeState.mode
            } else {
                existing.planModeState.syncTo(execMode)
                existing.approvalMode = existing.planModeState.mode
            }
            val (systemBlocks, metaUser) = buildZCodePromptParts(settings, provider)
            existing.engine.setSystemBlocks(systemBlocks)
            existing.engine.setMetaUserContext(metaUser)
            existing.engine.setPlanModeProvider {
                existing.approvalMode.collaboration == com.andmx.agent.permission.CollaborationMode.PLAN
            }
            if (sharedExtraTools.isNotEmpty()) {
                val known = existing.engine.listTools().map { it.first }.toSet()
                val missing = sharedExtraTools.filter { it.name !in known }
                if (missing.isNotEmpty()) existing.engine.addTools(missing)
            }
            if (!orchestrators.containsKey(conversationId)) {
                val orch = SubAgentOrchestrator(
                    toolsFactory = {
                        buildTools(existing.planTool, existing.goalState, existing.todoState, existing.planModeState, conversationId).filter { it.name != "spawn_agent" && it.name != "Agent" && it.name != "multi_agent" && it.name != "Task" && it.name != "TaskOutput" && it.name != "TaskStop" } + sharedExtraTools
                    },
                    settings = settings,
                    client = TracedLlm(LlmClient(provider, trackerFor(conversationId)), ModelCallTrace.Source.SUBAGENT),
                    turnProvider = { TurnContext(provider, settings.model) },
                    parentHistoryProvider = { existing.engine.snapshotHistory() },
                    resolveRun = { modelSpec ->
                        resolveSubAgentRun(modelSpec, provider, conversationId)
                    },
                    agentsMdProvider = {
                        runCatching { access.loadAgentsMdFragment() }.getOrDefault("")
                    },
                    coordinatorMessageSink = { fromId, msg ->
                        injectUserMessage(
                            conversationId,
                            "<agent-message from=\"${escXmlAttr(fromId)}\">$msg</agent-message>",
                        )
                    },
                )
                orchestrators[conversationId] = orch
                val known = existing.engine.listTools().map { it.first }.toSet()
                val multi = (listOf(orch.createSubAgentTool(), orch.createMultiAgentTool()) + createZCodeAgentTools(orch, conversationId)).filter { it.name !in known }
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
        val tools = buildTools(
            planTool = planTool,
            goalState = goalState,
            todo = todoState,
            planMode = planModeState,
            conversationId = conversationId,
            onShell = { shell ->
                shellWatchJobs.remove(conversationId)?.cancel()
                shellWatchJobs[conversationId] = controllerScope.launch {
                    shell.events.collect { ev ->
                        if (ev is com.andmx.agent.ShellTool.ShellEvent.Delta) {
                            liveBus(conversationId).tryEmit(ChatEvent.ToolOutputDelta(ev.callId, ev.chunk))
                        }
                    }
                }
            },
        )
        val client = TracedLlm(LlmClient(provider, trackerFor(conversationId)), ModelCallTrace.Source.MAIN)
        val (systemBlocks, metaUser) = buildZCodePromptParts(
            settings = settings,
            provider = provider,
        )
        val toolSearchRegistry = com.andmx.agent.ToolSearchRegistry()
        val engine = AgentEngine(
            tools = tools,
            client = client,
            systemPromptBlocks = systemBlocks,
            hooks = hookSystem,
            goalState = goalState,
            readFileState = readFileStates.getOrPut(conversationId) { com.andmx.agent.ReadFileState() },
            shellEnvProvider = { access.guestCwd() },
            planFileReminderProvider = { planFileReminderBody(conversationId) },
            approve = { tool, args ->
                val liveMode = sessions[conversationId]?.approvalMode ?: execMode
                approveTool(conversationId, liveMode, tool, args)
            },
            hiddenTools = {
                com.andmx.agent.TurnToolGate.hidden(
                    automation = automationTurnIds.contains(conversationId),
                    offPeak = offPeakTurnIds.contains(conversationId),
                ) + (if (WebFetchPolicy.browserEnabled) emptySet() else WebFetchPolicy.toolNames) +
                    (if (AndroidUsePolicy.enabled) emptySet() else setOf(AndroidUsePolicy.TOOL))
            },
            sessionEvents = { type, payload ->
                val trace = sessionTraces.computeIfAbsent(conversationId) {
                    java.util.UUID.randomUUID().toString()
                }
                sessionEventStore.append(conversationId, trace, type, payload)
            },
            toolSearch = toolSearchRegistry,
        )
        engine.addTools(toolSearchRegistry.controlTools())
        engine.setMetaUserContext(metaUser)
        engine.setTodoItemsProvider { todoItemsJson(todoState) }
        if (sharedExtraTools.isNotEmpty()) {
            engine.addTools(sharedExtraTools)
        }
        val orch = SubAgentOrchestrator(
            toolsFactory = {
                buildTools(planTool, goalState, todoState, planModeState, conversationId).filter { it.name != "spawn_agent" && it.name != "Agent" && it.name != "multi_agent" && it.name != "Task" && it.name != "TaskOutput" && it.name != "TaskStop" } + sharedExtraTools
            },
            settings = settings,
            client = client,
            turnProvider = { TurnContext(provider, settings.model) },
            parentHistoryProvider = { engine.snapshotHistory() },
        
            resolveRun = { modelSpec ->
                resolveSubAgentRun(modelSpec, provider, conversationId)
            },
            agentsMdProvider = {
                runCatching { access.loadAgentsMdFragment() }.getOrDefault("")
            },
            coordinatorMessageSink = { fromId, msg ->
                injectUserMessage(
                    conversationId,
                    "<agent-message from=\"${escXmlAttr(fromId)}\">$msg</agent-message>",
                )
            },
            dynamicWorkflowEnabled = dynamicWorkflowEnabled,
            approveTool = { permissionMode, tool, args ->
                approveSubagentTool(conversationId, permissionMode, tool, args)
            },
        )
        orchestrators[conversationId] = orch
        engine.addTools(listOf(orch.createSubAgentTool(), orch.createMultiAgentTool()) + createZCodeAgentTools(orch, conversationId))
        engine.setPlanModeProvider {
            (sessions[conversationId]?.approvalMode ?: planModeState.mode).collaboration ==
                com.andmx.agent.permission.CollaborationMode.PLAN
        }

        val history = loadHistoryForEngine(conversationId)
        engine.seed(history)
        injectPlanFileReminderIfPresent(conversationId, engine)
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
            permissions = restorePermissionGrants(conversationId),
        )
        planModeState.syncTo(execMode)
        _goal.value = goalState.goal
        sessions[conversationId] = session
        if (focusedConversationId == 0L) focusedConversationId = conversationId
        syncPlanOverlay()
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
        val projected = sessionEventStore.replayMessages(conversationId)
        if (projected.isNotEmpty()) return projected
        val legacy = loadLegacyHistory(conversationId)
        if (legacy.isNotEmpty()) {
            val trace = sessionTraces.computeIfAbsent(conversationId) {
                java.util.UUID.randomUUID().toString()
            }
            sessionEventStore.importMessages(conversationId, trace, legacy)
        }
        return legacy
    }

    private suspend fun loadLegacyHistory(conversationId: Long): List<ApiMessage> {
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
        onShell: ((com.andmx.agent.ShellTool) -> Unit)? = null,
    ): List<Tool> {
        val cwd = { access.guestCwd() }
        val dynamicWorkflow = if (dynamicWorkflowEnabled) {
            WorkflowTools(
                isAutomationTurn = { automationTurnIds.contains(conversationId) },
                hasLoadedSkill = { skillName ->
                    com.andmx.agent.LoadedSkills.sessionHasLoadedSkill(
                        sessions[conversationId]?.engine?.snapshotHistory().orEmpty(),
                        skillName,
                    )
                },
                service = workflowService,
                savedWorkflows = savedWorkflowStore,
                readText = { path -> runCatching { guestFs.readText(path) }.getOrNull() },
                analyzer = workflowAnalyzer,
                subagentModelCatalog = CreateWorkflowSource.SubagentModelCatalog { requested ->
                    val current = settingsStore.settings.firstOrNull()
                    val entries = ModelReferenceResolver.catalogEntries(
                        providers = providerStore.providers.firstOrNull().orEmpty(),
                        currentProviderId = current?.activeProviderId.orEmpty(),
                        currentModelId = current?.model.orEmpty(),
                    )
                    when (val resolution = ModelReferenceResolver.resolve(requested, entries)) {
                        is ModelReferenceResolution.Ok ->
                            CreateWorkflowSource.Canonicalization.Ok(resolution.canonical)
                        is ModelReferenceResolution.Failed ->
                            CreateWorkflowSource.Canonicalization.Failed(resolution.message)
                    }
                },
                conversationId = conversationId,
                cwd = cwd,
                dynamicRunner = dynamicWorkflowRunner,
                escalations = workflowEscalations,
            ).all()
        } else {
            emptyList()
        }
        return applyDynamicWorkflowGate(
            buildZCodeToolSurface(
            context = context,
            networkPolicy = networkPolicy,
            planTool = planTool,
            goalState = goalState,
            todo = todo,
            planMode = planMode,
            cwdProvider = cwd,
            onPlanModeChange = { mode -> applyExecMode(mode, conversationId) },
            currentMode = { sessions[conversationId]?.approvalMode ?: planMode.mode },
            askUser = ask@{ questions, rawArgs ->
                val session = sessions[conversationId] ?: return@ask "会话不可用"
                val deferred = kotlinx.coroutines.CompletableDeferred<String>()
                session.pendingAnswer = deferred
                session.answerSnoozed = false
                val summary = questions.joinToString(" · ") { q ->
                    q.header.ifBlank { q.question.take(24) }
                }.ifBlank { "需要你的决定" }
                val startedAt = System.currentTimeMillis()
                val autoResolve = _questionAutoResolve.value
                _pendingApproval.value = ApprovalRequest(
                    conversationId = conversationId,
                    toolName = "AskUserQuestion",
                    risk = ToolRisk.READ,
                    summary = summary,
                    modeLabel = session.approvalMode.label,
                    kind = "ask_user",
                    questions = questions,
                    autoDeadlineAt = if (autoResolve) startedAt + ASK_AUTO_RESOLVE_MS else null,
                    countdownVisibleAt = if (autoResolve) startedAt + ASK_HIDDEN_GRACE_MS else null,
                )
                notifyIfBackground(conversationId, "等待你的回答", summary)
                if (autoResolve) {
                    session.answerTimer = controllerScope.launch {
                        delay(ASK_AUTO_RESOLVE_MS)
                        if (!session.answerSnoozed && deferred.isActive) {
                            deferred.complete("""{"answers":{}}""")
                            val cur = _pendingApproval.value
                            if (cur?.kind == "ask_user" && cur.conversationId == conversationId) {
                                _pendingApproval.value = null
                            }
                        }
                    }
                }
                try {
                    deferred.await()
                } finally {
                    clearAnswerTimer(session)
                    session.pendingAnswer = null
                }
            },
            readSession = { sessionId, query, strategy, maxTokens ->
                readSessionContext(conversationId, sessionId, query, strategy, maxTokens)
            },
            invokeSkill = { skill, _ ->
                invokeSkillTool(skill)
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
                deferred.await().also { approved ->
                    if (approved) writePlanFile(conversationId, plan)
                }
            },
            allowedPromptsSink = { entries ->
                allowedPromptsByConversation.getOrPut(conversationId) { AllowedPrompts.Grants() }
                    .addAll(entries)
            },
            backgroundTasks = backgroundTasksFor(conversationId),
            listModelsProviders = { providerStore.providers.firstOrNull().orEmpty() },
            listModelsCurrent = {
                val s = settingsStore.settings.firstOrNull()
                (s?.activeProviderId.orEmpty()) to (s?.model.orEmpty())
            },
            readFileState = readFileStates.getOrPut(conversationId) { com.andmx.agent.ReadFileState() },
            webFetchSummarizer = { content, prompt ->
                summarizeFetchedContent(content, prompt, conversationId)
            },
            onShell = onShell,
            conversationId = conversationId,
        ) + CronTools(
            store = cronStore,
            conversationId = { conversationId },
            currentModel = { settingsStore.settings.firstOrNull()?.model.orEmpty() },
            isAutomationTurn = { automationTurnIds.contains(conversationId) },
            offPeakStartHour = { settingsStore.settings.firstOrNull()?.offPeakStartHour ?: 0 },
        ).all() + dynamicWorkflow + com.andmx.agent.JsReplTool(context, "conv-$conversationId"),
            enabled = dynamicWorkflowEnabled,
        )
    }

    private suspend fun summarizeFetchedContent(
        content: String,
        prompt: String,
        conversationId: Long,
    ): String? {
        val settings = settingsStore.settings.firstOrNull() ?: return null
        val providers = providerStore.providers.firstOrNull().orEmpty()
        val provider = providers.firstOrNull { it.id == settings.activeProviderId && it.enabled }
            ?: providerStore.primary.firstOrNull() ?: return null
        val client = TracedLlm(LlmClient(provider, trackerFor(conversationId)), ModelCallTrace.Source.WEB_FETCH)
        val instruction = """
            Provide a concise response based only on the content above. In your response:
             - Enforce a strict 125-character maximum for quotes from any source document. Open Source Software is ok as long as we respect the license.
             - Use quotation marks for exact language from articles; any language outside of the quotation should never be word-for-word the same.
             - You are not a lawyer and never comment on the legality of your own prompts and responses.
             - Never produce or reproduce exact song lyrics.
        """.trimIndent()
        val text = """
            Web page content:
            ---
            $content
            ---

            $prompt

            $instruction
        """.trimIndent()
        val req = com.andmx.llm.ChatRequest(
            model = settings.model,
            messages = listOf(com.andmx.llm.ApiMessage(role = "user", content = text)),
        )
        return client.chat(req).getOrNull()?.content?.takeIf { it.isNotBlank() }
    }

    private suspend fun readSessionContext(
        conversationId: Long,
        sessionId: String,
        query: String,
        strategy: String,
        maxTokens: Int,
    ): com.andmx.agent.zcode.SessionContextRead {
        val current = sessionId == "current" || matchesConversation(conversationId, sessionId)
        if (strategy == "topic" && !current) {
            return com.andmx.agent.zcode.SessionContextRead(
                status = "failed",
                source = "none",
                sessionId = sessionId,
                content = "Topic history can only be read from the current session",
                error = "Topic history can only be read from the current session",
            )
        }
        if (strategy == "topic") {
            val id = if (sessionId == "current") conversationId.toString() else sessionId
            return com.andmx.agent.zcode.SessionContextRead(
                status = "not_found",
                source = "local",
                sessionId = id,
                content = com.andmx.agent.zcode.TOPIC_ARCHIVE_EMPTY_CONTENT,
            )
        }
        val lookupId = if (sessionId == "current") conversationId.toString() else sessionId
        val rawId = lookupId.removePrefix("sess_").removePrefix("#")
        val convId = when {
            sessionId == "current" -> conversationId
            else -> rawId.toLongOrNull()
                ?: repo.conversationsByArchived(false).firstOrNull {
                    it.sessionId == sessionId || it.sessionId == rawId || it.id.toString() == rawId
                }?.id
        } ?: return com.andmx.agent.zcode.SessionContextRead(
            status = "not_found",
            sessionId = lookupId,
            content = "No persisted session was found for $lookupId.",
        )
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
        val budget = maxTokens.coerceIn(1, 12_000) * 4
        return com.andmx.agent.zcode.SessionContextRead(
            status = "success",
            source = "local",
            sessionId = lookupId,
            content = filtered.take(budget),
            truncated = filtered.length > budget,
        )
    }

    private suspend fun matchesConversation(conversationId: Long, sessionId: String): Boolean {
        if (sessionId == conversationId.toString() || sessionId == "sess_$conversationId") return true
        val conv = repo.conversation(conversationId) ?: return false
        return conv.sessionId.isNotBlank() && (conv.sessionId == sessionId || conv.sessionId == sessionId.removePrefix("sess_"))
    }

    private suspend fun invokeSkillTool(skill: String): String {
        val rawName = skill.trim().removePrefix("/")
        val installed = runCatching { com.andmx.agent.plugins.SkillInstaller(guestFs).listInstalled() }.getOrDefault(emptyList())
        val match = installed.firstOrNull {
            it.name.equals(rawName, true)
                || it.name.equals(rawName.substringAfterLast(':'), true)
        }
        if (match != null) {
            val body = if (match.hasSkillMd) {
                runCatching { access.readText("${match.path}/SKILL.md") }.getOrDefault("")
            } else ""
            return formatSkillPayload(match.name, match.path, body)
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
            return formatSkillPayload(id, path, body)
        }
        throw IllegalStateException("Skill not found: $rawName")
    }

    private fun formatSkillPayload(
        name: String,
        path: String,
        body: String,
    ): String = ChatControllerLogic.formatSkillPayload(name, path, body)


    private suspend fun rebuildHistory(conversationId: Long): List<ApiMessage> =
        ChatControllerLogic.rebuildHistory(repo.messages(conversationId))

    private suspend fun restorePermissionGrants(conversationId: Long): com.andmx.agent.permission.PermissionService {
        val service = com.andmx.agent.permission.PermissionService()
        val raw = runCatching {
            com.andmx.data.AndmxDatabase.get(context).dao()
                .getConversation(conversationId)?.permissionGrantsJson.orEmpty()
        }.getOrDefault("")
        service.restoreSessionGrants(com.andmx.agent.permission.PermissionGrantsCodec.decode(raw))
        return service
    }

    private fun persistPermissionGrants(conversationId: Long) {
        val session = sessions[conversationId] ?: return
        val json = com.andmx.agent.permission.PermissionGrantsCodec.encode(
            session.permissions.sessionGrants(),
        )
        controllerScope.launch {
            runCatching {
                com.andmx.data.AndmxDatabase.get(context).dao()
                    .setPermissionGrants(conversationId, json)
            }
        }
    }

    private suspend fun approveTool(
        conversationId: Long,
        mode: ExecMode,
        tool: Tool,
        args: JsonObject,
    ): ApprovalOutcome = approveToolWith(
        conversationId = conversationId,
        collaboration = mode.collaboration,
        labelMode = mode,
        tool = tool,
        args = args,
    )

    /**
     * 子代理/工作流 actor 的审批入口：显式 permissionMode 覆盖父会话模式，
     * 但父会话处于 Plan 时始终强制 Plan，避免子代理绕过只读约束。
     */
    private suspend fun approveSubagentTool(
        conversationId: Long,
        permissionMode: String,
        tool: Tool,
        args: JsonObject,
    ): ApprovalOutcome {
        val parentMode = sessions[conversationId]?.approvalMode ?: ExecMode.from(settingsSnapshot.approvalMode)
        val parentPlan = parentMode.collaboration == com.andmx.agent.permission.CollaborationMode.PLAN
        val declared = com.andmx.agent.permission.CollaborationMode.fromSubagentPermissionMode(permissionMode)
        val collaboration = when {
            parentPlan -> com.andmx.agent.permission.CollaborationMode.PLAN
            declared != null -> declared
            else -> parentMode.collaboration
        }
        val labelMode = when (collaboration) {
            com.andmx.agent.permission.CollaborationMode.PLAN -> ExecMode.PLAN
            com.andmx.agent.permission.CollaborationMode.EDIT -> ExecMode.EDIT
            com.andmx.agent.permission.CollaborationMode.AUTO -> ExecMode.AUTO
            com.andmx.agent.permission.CollaborationMode.YOLO -> ExecMode.YOLO
            com.andmx.agent.permission.CollaborationMode.GUARDED -> ExecMode.GUARDED
            com.andmx.agent.permission.CollaborationMode.BUILD -> ExecMode.BUILD
        }
        return approveToolWith(
            conversationId = conversationId,
            collaboration = collaboration,
            labelMode = labelMode,
            tool = tool,
            args = args,
            denyPrompts = permissionMode == "dontAsk" && !parentPlan,
        )
    }

    private suspend fun approveToolWith(
        conversationId: Long,
        collaboration: com.andmx.agent.permission.CollaborationMode,
        labelMode: ExecMode,
        tool: Tool,
        args: JsonObject,
        denyPrompts: Boolean = false,
    ): ApprovalOutcome {
        val planActive = collaboration == com.andmx.agent.permission.CollaborationMode.PLAN
        var capability = com.andmx.agent.permission.PermissionToolCapability(
            userApprovalRule = tool.userApprovalRule,
            allowedInPlanMode = tool.allowedInPlanMode,
            alwaysAsk = tool.alwaysAsk,
            readOnly = tool.readOnly,
            destructive = tool.destructive,
            requiresUserInteraction = tool.requiresUserInteraction,
            sideEffectScope = tool.sideEffectScope,
            riskLevel = tool.riskLevel,
            needsApproval = tool.needsApproval,
            permissionName = tool.permissionName,
        )
        if (com.andmx.agent.BashReadonlyPolicy.isShellTool(tool.name)) {
            capability = com.andmx.agent.permission.bashRuntimeCapability(
                args["command"]?.jsonPrimitive?.content,
                capability,
                collaboration,
            )
        }
        val command = args["command"]?.jsonPrimitive?.content
        val grantRules = if (tool.name == "Bash" && command != null) {
            val grants = allowedPromptsByConversation[conversationId]
            if (grants != null && AllowedPrompts.grantsFor(command, grants.promptsFor("Bash"))) {
                listOf(com.andmx.agent.permission.PermissionRuleValue("Bash", command))
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }
        val rules = approvalRuleStore.ruleset(projectKey()).let { it.copy(allow = it.allow + grantRules) }
        val decision = (sessions[conversationId]?.permissions ?: com.andmx.agent.permission.PermissionService()).checkPermission(
            com.andmx.agent.permission.PermissionContext(
                toolName = tool.name,
                input = args,
                riskLevel = capability.riskLevel ?: tool.riskLevel,
                mode = collaboration,
                planEnabled = planActive,
                workingDirectory = access.guestCwd(),
            ),
            capability,
            rules,
        )
        return when {
            decision.decision == "allow" -> ApprovalOutcome.AllowedOnce
            decision.decision == "deny" -> ApprovalOutcome.Rejected(decision.reason)
            denyPrompts -> ApprovalOutcome.Rejected("Subagent permission mode dontAsk denies prompts")
            tool.name == "ExitPlanMode" && decision.ruleId == "tool.userInteraction" ->
                ApprovalOutcome.AllowedOnce
            else -> approvalMutex.withLock {
                prompt(conversationId, tool, args, labelMode, decision.reason)
            }
        }
    }

    private suspend fun prompt(
        conversationId: Long,
        tool: Tool,
        args: JsonObject,
        mode: ExecMode,
        reason: String? = null,
    ): ApprovalOutcome {
        // No live session means no UI can answer the question. That is
        // not a refusal, and the model can only tell the difference if
        // the two are reported differently.
        val session = sessions[conversationId]
            ?: return ApprovalOutcome.Unavailable("会话不可用")
        val deferred = CompletableDeferred<Boolean>()
        session.pending = deferred
        session.pendingArgs = args.toString()
        val summary = buildApprovalSummary(tool, args) + (reason?.let { "（$it）" } ?: "")
        _pendingApproval.value = ApprovalRequest(
            conversationId = conversationId,
            toolName = tool.name,
            risk = tool.risk,
            summary = summary,
            modeLabel = mode.label,
            inputPreview = ruleSubject(tool.name, args).take(160),
            rulePreview = approvalRulePreview(tool.name, args.toString()),
            reason = reason.orEmpty(),
        )
        notifyIfBackground(conversationId, "等待确认", summary)
        // 上游 PermissionRequest hook：与审批 UI 并行触发（外部审批桥接语义），不阻塞 deferred
        if (hookSystem.hasHooksFor(com.andmx.agent.hooks.HookSystem.HookEvent.PERMISSION_REQUEST)) {
            controllerScope.launch {
                runCatching {
                    hookSystem.runEvent(
                        com.andmx.agent.hooks.HookSystem.HookEvent.PERMISSION_REQUEST,
                        com.andmx.agent.hooks.HookSystem.HookContext(
                            toolName = tool.name,
                            toolArgs = args.toString(),
                        ),
                    )
                }
            }
        }
        return if (deferred.await()) ApprovalOutcome.AllowedOnce else ApprovalOutcome.Rejected()
    }

private fun PermissionModes.Effect.toDecision(): Decision = when (this) {
    PermissionModes.Effect.ALLOW -> Decision.AUTO
    PermissionModes.Effect.ASK -> Decision.PROMPT
    PermissionModes.Effect.DENY -> Decision.DENY
}

/** 权限规则的匹配主体：命令原文 / 文件路径 / url，与 ruleContent 对齐比较。 */
    private fun ruleSubject(toolName: String, args: JsonObject): String {
        val canonical = ToolArgs.canonical(toolName)
        return when (canonical) {
            "shell", "git" -> {
                val desc = args["description"]?.jsonPrimitive?.content.orEmpty()
                val cmd = ToolArgs.shellCommand(toolName, args.toString()).ifBlank {
                    args["command"]?.jsonPrimitive?.content.orEmpty()
                }
                if (desc.isNotBlank()) "$desc\n$cmd" else cmd
            }
            else -> ToolArgs.filePath(toolName, args.toString()).ifBlank {
                args["url"]?.jsonPrimitive?.content
                    ?: args["path"]?.jsonPrimitive?.content
                    ?: args["file_path"]?.jsonPrimitive?.content
                    ?: args["pattern"]?.jsonPrimitive?.content
                    ?: canonical
            }
        }
    }

    private fun buildApprovalSummary(tool: Tool, args: JsonObject): String =
        ChatControllerLogic.approvalSummary(
            toolName = tool.name,
            preview = runCatching {
                com.andmx.agent.ToolArgs.preview(tool.name, args.toString())
            }.getOrDefault(""),
            rawArgs = args.toString(),
        )


    private suspend fun buildZCodePromptParts(
        settings: ProviderSettings,
        provider: ProviderDefinition,
    ): Pair<List<String>, String> {
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
        val skillEntries = buildList {
            skills.forEach { skill ->
                add(
                    ZCodePrompts.SkillEntry(
                        name = skill.name,
                        description = skill.description.ifBlank { "Invoke via the Skill tool (slash form /${skill.name})" },
                        path = skill.path,
                    ),
                )
            }
            pluginSkills.forEach { (id, _) ->
                add(
                    ZCodePrompts.SkillEntry(
                        name = id,
                        description = "Plugin skill; invoke via the Skill tool",
                        path = "plugin:$id",
                        qualifiedName = "plugin:$id",
                    ),
                )
            }
        }
        val instructionSources = runCatching {
            val cwd = access.guestCwd()
            listOf("AGENTS.md", "CLAUDE.md", "CODEX.md", ".zcode/AGENTS.md").mapNotNull { name ->
                val doc = runCatching { access.readText("$cwd/$name") }.getOrNull()
                if (doc.isNullOrBlank()) {
                    null
                } else {
                    ZCodePrompts.InstructionSource(path = "$cwd/$name", content = doc.take(6000))
                }
            }
        }.getOrDefault(emptyList())
        val memory = if (!settings.memoryEnabled) "" else runCatching { memorySystem.promptFragment() }.getOrDefault("")
        val extra = buildString {
            if (sharedExtraTools.isNotEmpty()) {
                appendLine("# 扩展工具")
                appendLine("已加载 ${sharedExtraTools.size} 个 MCP/插件工具。名称带服务器前缀时表示来自 MCP。")
            }
        }
        val deviceNote = runCatching { AndroidContextProvider(context).environmentNote() }.getOrDefault("")
        val shellKind = if (BuildConfig.FLAVOR == "proot") {
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), shell in proot Alpine guest"
        } else {
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), host shell"
        }
        val env = ZCodePrompts.SessionEnv(
            cwd = access.guestCwd(),
            isGitRepo = git?.isRepo == true,
            platform = "android",
            shell = "sh",
            osVersion = shellKind,
            modelLabel = provider.name + "/" + settings.model,
            branch = git?.branch.orEmpty(),
            mainBranch = "main",
            gitUser = git?.userName.orEmpty(),
            gitStatus = gitStatus,
            deviceNote = deviceNote,
        )
        val userInstructions = runCatching {
            guestFs.readText("/root/.zcode/AGENTS.md", 100 * 1024)
        }.getOrNull().orEmpty()
        val customInstructions = listOf(settings.customInstructions.trim(), userInstructions.trim())
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
        val system = ZCodePrompts.assembleBlocks(
            env = env,
            customInstructions = customInstructions,
            persona = settings.persona,
            extra = extra,
            hasSkills = skillEntries.isNotEmpty(),
            memory = memory,
            outputStyleName = settings.outputStyleName,
            outputStylePrompt = settings.outputStylePrompt,
        )
        val metaUser = ZCodePrompts.metaUserContext(
            instructionSources = instructionSources,
            skills = skillEntries,
            dateIso = java.time.LocalDate.now().toString(),
        )
        return system to metaUser
    }

    private fun todoItemsJson(state: TodoState): String? {
        val items = state.items.value
        if (items.isEmpty()) return null
        return buildJsonArray {
            items.forEach { item ->
                addJsonObject {
                    put("content", item.content)
                    put("status", item.status)
                    put("priority", item.priority)
                }
            }
        }.toString()
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
            appendLine("优先工具：Read / Write / Edit / Bash / Grep / Glob / WebFetch / WebSearch / TodoWrite / Agent / Task / TaskOutput / TaskStop / Skill / AskUserQuestion / EnterPlanMode / ExitPlanMode。")
            appendLine("复杂可并行子任务用 Agent 或 Task；后台任务用 TaskOutput 读结果、TaskStop 停止。")
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
        refreshContextBreakdown(conversationId)
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
            baseInstructions = listOf(
                settings.customInstructions.trim(),
                runCatching { guestFs.readText("/root/.zcode/AGENTS.md", 100 * 1024) }
                    .getOrNull().orEmpty().trim(),
            ).filter { it.isNotEmpty() }.joinToString("\n\n"),
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
                    is SubAgentOrchestrator.SubAgentEvent.ToolActivity ->
                        bus.tryEmit(ChatEvent.SubAgentToolActivity(ev.agentId, ev.toolName, ev.detail, ev.running))
                    is SubAgentOrchestrator.SubAgentEvent.Terminated -> {
                        // ZCode queued_system_notification：后台子代理终态
                        // 以 <system-reminder> 形式推回主代理的下一个模型步。
                        if (ev.background) {
                            val body = orch.notificationFor(ev.agentId)
                            if (body != null) {
                                sessions[conversationId]?.engine?.injectSystemReminder(
                                    com.andmx.agent.SystemReminder.Source.QUEUED_SYSTEM_NOTIFICATION,
                                    body,
                                )
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    /** 后台时发系统通知（「任务完成/失败/等待确认」），受设置页通知开关控制。 */
    private suspend fun notifyIfBackground(conversationId: Long, title: String, body: String) {
        val s = settingsStore.settings.firstOrNull() ?: return
        if (!s.notification) return
        TurnNotifier.maybeNotify(context, conversationId, title, body, s.notificationSound)
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
        if (session.engine.managesGoalTokens) return
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

    suspend fun conversationMarkdownForShare(conversationId: Long): String {
        val conv = repo.conversation(conversationId)
        val msgs = repo.messages(conversationId)
        return conversationMarkdown(conv, msgs, java.time.Instant.now())
    }

    suspend fun exportConversationMarkdown(conversationId: Long): String {
        val conv = repo.conversation(conversationId)
        val msgs = repo.messages(conversationId)
        val md = conversationMarkdown(conv, msgs, java.time.Instant.now())
        val path = "/root/andmx-export-${System.currentTimeMillis()}.md"
        val ok = runCatching { guestFs.writeText(path, md) }.isSuccess
        return if (ok) {
            "已导出到 `$path`（${msgs.size} 条）"
        } else {
            "导出失败（guest 写入失败）\n\n$md"
        }
    }

    suspend fun noteConversationRewind(conversationId: Long, userText: String) {
        val events = sessionEventStore.read(conversationId)
        val checkpoint = events.lastOrNull { event ->
            event.type == com.andmx.data.session.SessionEventType.USER_MESSAGE &&
                event.payload["text"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } == userText
        }?.sequenceNumber ?: return
        val trace = sessionTraces.computeIfAbsent(conversationId) {
            java.util.UUID.randomUUID().toString()
        }
        sessionEventStore.rewind(conversationId, trace, checkpoint)
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
        val stamp = runCatching { guestFs.resolve("/root/.andmx/hooks.json").lastModified() }.getOrDefault(0L)
        val projectStamp = runCatching { access.statMtime(com.andmx.agent.hooks.ProjectHooks.RELATIVE_PATH) }
            .getOrNull() ?: 0L
        val trustRevision = com.andmx.agent.hooks.HookTrustStore.revision.value
        if (hooksLoaded &&
            lastHooksReloadToken == token &&
            hooksFileStamp == stamp &&
            hooksProjectStamp == projectStamp &&
            hooksTrustRevision == trustRevision
        ) {
            return
        }
        hooksLoaded = true
        lastHooksReloadToken = token
        hooksFileStamp = stamp
        hooksProjectStamp = projectStamp
        hooksTrustRevision = trustRevision
        val configs = mutableListOf<HookSystem.HookConfig>()
        runCatching {
            val path = "/root/.andmx/hooks.json"
            if (guestFs.exists(path)) {
                configs += com.andmx.agent.hooks.HookConfigParser.parse(
                    raw = guestFs.readText(path),
                    source = HookSystem.HookSource.USER,
                )
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
                        matcher = entry.matcher,
                        source = HookSystem.HookSource.PLUGIN,
                    )
                }
            }
        }
        runCatching {
            val workspaceKey = com.andmx.agent.hooks.ProjectHooks.workspaceKey(projectManager)
            val snapshot = com.andmx.agent.hooks.ProjectHooks.load(access, workspaceKey)
            if (snapshot != null && snapshot.trusted) {
                configs += snapshot.hooks
            }
        }
        hookSystem.executor = if (projectManager.isRemote) {
            projectManager.currentRemoteSpec()?.let {
                com.andmx.exec.remote.RemoteSshEnvironment(context, it)
            }
        } else {
            null
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

    private suspend fun writePlanFile(conversationId: Long, plan: String) {
        if (plan.isBlank()) return
        runCatching {
            access.writeText(com.andmx.agent.PlanFiles.planRelativePath(conversationId), plan)
        }
    }

    private suspend fun planFileReminderBody(conversationId: Long): String? {
        val rel = com.andmx.agent.PlanFiles.planRelativePath(conversationId)
        val content = runCatching {
            access.readText(rel, com.andmx.agent.PlanFiles.MAX_PLAN_CHARS * 4)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return com.andmx.agent.PlanFiles.formatPlanFileReference(content, rel)
    }

    private suspend fun injectPlanFileReminderIfPresent(conversationId: Long, engine: AgentEngine) {
        planFileReminderBody(conversationId)?.let {
            engine.injectSystemReminder(com.andmx.agent.SystemReminder.Source.PLAN_FILE_REFERENCE, it)
        }
    }

    fun initPrompt(args: String): String =
        com.andmx.agent.InitPrompt.build(args, access.guestCwd())

    fun workflowPrompt(args: String): String =
        com.andmx.agent.WorkflowPrompt.expand(args, dynamicWorkflowEnabled)

    fun workflowBlankArgsNotice(): String =
        com.andmx.agent.WorkflowPrompt.blankArgsNotice(dynamicWorkflowEnabled)

    /**
     * 上游 title-generation-sidecar：首发消息触发一次旁路标题生成。
     * 不阻塞当前回合；仅在标题仍为默认（或为首行兜底标题）时落名，
     * 失败时 maybeAutoTitle 的首行兜底继续生效。
     */
    private fun launchTitleSidecar(
        conversationId: Long,
        userText: String,
        provider: com.andmx.llm.provider.ProviderDefinition,
        model: String,
    ) {
        controllerScope.launch {
            val client = TracedLlm(LlmClient(provider, trackerFor(conversationId)), ModelCallTrace.Source.SESSION_TITLE)
            val title = com.andmx.agent.TitleGenerator.generate(client, model, userText) ?: return@launch
            val conv = repo.conversation(conversationId) ?: return@launch
            val derived = userText.lineSequence().firstOrNull { it.isNotBlank() }?.take(32)
            if (conv.title == "新任务" || conv.title == derived) {
                repo.rename(conversationId, title)
            }
        }
    }

    private suspend fun maybeAutoTitle(conversationId: Long, toolName: String, userText: String) {
        if (toolName !in setOf(
                "write_file", "Write", "edit_file", "Edit", "apply_patch",
                "run_shell", "Bash", "grep", "glob",
            )
        ) return
        val conv = repo.conversation(conversationId) ?: return
        if (conv.title != "新任务" && conv.title.isNotBlank()) return
        val title = userText.lineSequence().firstOrNull { it.isNotBlank() }?.take(32) ?: return
        repo.rename(conversationId, title)
    }
}

private fun escXmlAttr(s: String): String =
    s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
