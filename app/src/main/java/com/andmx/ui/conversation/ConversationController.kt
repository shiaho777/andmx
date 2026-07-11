package com.andmx.ui.conversation

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.andmx.BuildConfig
import com.andmx.agent.AgentEngine
import com.andmx.agent.AgentEvent
import com.andmx.agent.AgentMethodologyContext
import com.andmx.agent.ApplyPatchTool
import com.andmx.agent.ApprovalMode
import com.andmx.agent.ApprovalPolicy
import com.andmx.agent.BrowseTool
import com.andmx.agent.ComputerUseTool
import com.andmx.agent.ContextSnapshot
import com.andmx.agent.Decision
import com.andmx.agent.EditFileTool
import com.andmx.agent.GitTool
import com.andmx.agent.GoalAction
import com.andmx.agent.HandoffRunLogItem
import com.andmx.agent.InstructionStackSnapshot
import com.andmx.agent.ListDirTool
import com.andmx.agent.ReadFileTool
import com.andmx.agent.ShellTool
import com.andmx.agent.ThreadHandoffContext
import com.andmx.agent.Tool
import com.andmx.agent.ToolCapability
import com.andmx.agent.ToolRisk
import com.andmx.agent.TaskPlanSnapshot
import com.andmx.agent.ToolArgs
import com.andmx.agent.WebSearchTool
import com.andmx.agent.WriteFileTool
import com.andmx.agent.agentMethodologyText
import com.andmx.agent.changeSummaryText
import com.andmx.agent.changeSummaryItems
import com.andmx.agent.contextPressureLabel
import com.andmx.agent.contextSnapshotText
import com.andmx.agent.description
import com.andmx.agent.inferTaskPlan
import com.andmx.agent.instructionStackText
import com.andmx.agent.label
import com.andmx.agent.riskOrder
import com.andmx.agent.taskPlanText
import com.andmx.agent.threadHandoffText
import com.andmx.agent.toCapabilities
import com.andmx.data.ConversationRepository
import com.andmx.llm.ApiMessage
import com.andmx.settings.ProviderSettings
import com.andmx.settings.SettingsStore
import com.andmx.ui.workbench.ArtifactKind
import com.andmx.ui.workbench.ArtifactState
import com.andmx.ui.workbench.BackgroundTaskKind
import com.andmx.ui.workbench.BackgroundTaskState
import com.andmx.ui.workbench.BackgroundTaskStatus
import com.andmx.ui.workbench.ComposerDraftState
import com.andmx.ui.workbench.ExecutionSessionState
import com.andmx.ui.workbench.FocusTarget
import com.andmx.ui.workbench.GoalState
import com.andmx.ui.workbench.JumpPoint
import com.andmx.ui.workbench.SidePanelTabId
import com.andmx.ui.workbench.TerminalBindingState
import com.andmx.ui.workbench.ToolExecutionState
import com.andmx.ui.workbench.ToolExecutionStatus
import com.andmx.ui.workbench.TurnState
import com.andmx.ui.workbench.TurnStatus
import com.andmx.ui.workbench.toolTerminalSessionKey
import com.andmx.ui.workbench.toolTarget
import com.andmx.ui.workbench.runLogEntries
import com.andmx.ui.workbench.runLogStateLabel
import com.andmx.ui.workbench.activitySummaryText
import com.andmx.ui.workbench.buildAgentInspectorSnapshot
import com.andmx.ui.workbench.buildRuntimeEnvironmentSummary
import com.andmx.ui.workbench.buildInstructionStackSummary
import com.andmx.ui.workbench.runtimeEnvironmentStatusText
import com.andmx.ui.workbench.buildSessionChecklist
import com.andmx.ui.workbench.buildNextActionDecision
import com.andmx.ui.workbench.buildEvidenceLedger
import com.andmx.ui.workbench.buildCodexParityAudit
import com.andmx.ui.workbench.buildDeliveryReport
import com.andmx.ui.workbench.buildAgentArchitectureBlueprint
import com.andmx.ui.workbench.buildCodexSurfaceMap
import com.andmx.ui.workbench.evidenceLedgerText
import com.andmx.ui.workbench.codexParityText
import com.andmx.ui.workbench.deliveryReportText
import com.andmx.ui.workbench.agentArchitectureText
import com.andmx.ui.workbench.codexSurfaceMapText
import com.andmx.ui.workbench.buildVisualAcceptanceSummary
import com.andmx.ui.workbench.visualAcceptanceText
import com.andmx.ui.workbench.buildCodexDesignSystemAudit
import com.andmx.ui.workbench.codexDesignSystemText
import com.andmx.ui.workbench.buildCodexSelfModel
import com.andmx.ui.workbench.codexSelfModelText
import com.andmx.ui.workbench.buildCodexEnvironmentContract
import com.andmx.ui.workbench.codexEnvironmentContractText
import com.andmx.ui.workbench.buildCodexToolCapabilityMap
import com.andmx.ui.workbench.codexToolCapabilityMapText
import com.andmx.ui.workbench.buildCodexCommandReference
import com.andmx.ui.workbench.codexCommandReferenceText
import com.andmx.ui.workbench.buildCodexAppshotCaptureGuide
import com.andmx.ui.workbench.codexAppshotCaptureGuideText
import com.andmx.ui.workbench.summaryLines
import com.andmx.ui.workbench.buildScreenshotExtractionSummary
import com.andmx.ui.workbench.screenshotExtractionText
import com.andmx.ui.workbench.buildCodexInteractionFlow
import com.andmx.ui.workbench.codexInteractionFlowText
import com.andmx.ui.workbench.buildCodexSelfImprovementPlan
import com.andmx.ui.workbench.codexSelfImprovementText
import com.andmx.ui.workbench.buildUiReferenceBoard
import com.andmx.ui.workbench.uiReferenceBoardText
import com.andmx.ui.workbench.buildScreenshotImplementationTrace
import com.andmx.ui.workbench.screenshotImplementationTraceText
import com.andmx.ui.workbench.buildUiReplicaBlueprint
import com.andmx.ui.workbench.buildUiReferenceLedger
import com.andmx.ui.workbench.nextActionText
import com.andmx.ui.workbench.uiReplicaBlueprintText
import com.andmx.ui.workbench.buildToolPolicySummary
import com.andmx.ui.workbench.toolPolicyText
import com.andmx.ui.workbench.sessionChecklistText
import com.andmx.ui.workbench.verificationEntries
import com.andmx.ui.workbench.verificationHandoffLines
import com.andmx.ui.workbench.verificationStateLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** UI-facing items rendered in the conversation. */
sealed interface ChatItem {
    val key: Long
    data class User(override val key: Long, val text: String, val sentAt: Long = 0L) : ChatItem
    data class Assistant(override val key: Long, val text: String, val done: Boolean = false, val sentAt: Long = 0L, val completedAt: Long = 0L) : ChatItem
    data class ToolUse(
        override val key: Long,
        val callId: String,
        val name: String,
        val args: String,
        val output: String? = null,
        val running: Boolean = true,
        val error: Boolean = false,
        val imageUrls: List<String> = emptyList(),
    ) : ChatItem
    data class Approval(
        override val key: Long,
        val toolName: String,
        val summary: String,
        val risk: ToolRisk = ToolRisk.EXECUTE,
        val approvalModeLabel: String = "",
        val riskDescription: String = "",
        val resolved: Boolean = false,
        val allowed: Boolean = false,
    ) : ChatItem
}

/**
 * Goal status — mirrors Codex's thread_goals.status enum exactly.
 *
 * The agent transitions between these via create_goal/update_goal tools and
 * /goal commands. The UI renders a different color/label for each.
 */
enum class GoalStatus(val label: String) {
    ACTIVE("运行中"),
    PAUSED("已暂停"),
    BLOCKED("已阻塞"),
    USAGE_LIMITED("达到用量上限"),
    BUDGET_LIMITED("达到预算上限"),
    COMPLETE("已完成"),
    EMPTY("未设置");

    /** Map to the legacy GoalPhase for backward-compatible code paths. */
    fun toPhase(): GoalPhase = when (this) {
        ACTIVE -> GoalPhase.RUNNING
        PAUSED -> GoalPhase.PAUSED
        BLOCKED -> GoalPhase.FAILED
        USAGE_LIMITED -> GoalPhase.FAILED
        BUDGET_LIMITED -> GoalPhase.FAILED
        COMPLETE -> GoalPhase.READY
        EMPTY -> GoalPhase.EMPTY
    }
}

/** Legacy phase enum — kept for compatibility; new code uses [GoalStatus]. */
enum class GoalPhase { EMPTY, RUNNING, PAUSED, READY, WAITING_APPROVAL, NEEDS_SETUP, FAILED }

data class ConversationGoal(
    val text: String = "",
    val status: GoalStatus = GoalStatus.EMPTY,
    val phase: GoalPhase = GoalPhase.EMPTY,
    val tokenBudget: Int = 0,
    val tokensUsed: Int = 0,
    val startedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val note: String = "",
) {
    val hasGoal: Boolean get() = text.isNotBlank()
    /** Remaining token budget, or 0 if no budget set. */
    val remainingBudget: Int get() = if (tokenBudget > 0) (tokenBudget - tokensUsed).coerceAtLeast(0) else 0
    /** True when a budget is set and has been exhausted. */
    val isBudgetExhausted: Boolean get() = tokenBudget > 0 && tokensUsed >= tokenBudget
}

/**
 * Drives one conversation: owns the agent engine, persists messages to Room,
 * and exposes the transcript + busy/settings state as Compose state.
 */
class ConversationController(
    private val context: Context,
    private val scope: CoroutineScope,
    /**
     * Active project working directory inside the guest. The user's chosen
     * phone directory is bind-mounted here by proot, so the agent operates on
     * real files. Defaults to /root until a project is selected.
     */
    var project: String = "/root",
) {
    private val store = SettingsStore(context)
    private val providerStore = com.andmx.settings.ProviderStore(context)
    private val repo = ConversationRepository(context)
    private val guestFs = com.andmx.exec.files.GuestFs(com.andmx.exec.proot.ProotRuntime(context))
    val projectManager = com.andmx.workspace.ProjectManager(context)

    init {
        // Re-apply the persisted project bind mount on startup so proot picks
        // it up on first command after a process restart.
        projectManager.restoreBinding()
        // If a project is bound, work in the guest mount path (/root/project).
        if (projectManager.hasProject) project = projectManager.guestMountPath
    }

    // ── New subsystem instances (Codex parity) ──
    private val updatePlanTool = com.andmx.agent.UpdatePlanTool()
    private val memorySystem = com.andmx.agent.memory.MemorySystem(guestFs)
    private val agentsMdLoader = com.andmx.workspace.AgentsMdLoader(guestFs)
    private val pluginSystem = com.andmx.agent.plugins.PluginSystem(context, guestFs)
    private val execPolicy = com.andmx.exec.policy.ExecPolicy()
    private val networkPolicy = com.andmx.exec.policy.NetworkPolicy.PERMISSIVE
    private val hookSystem = com.andmx.agent.hooks.HookSystem(context)
    private val androidContext = com.andmx.android.AndroidContextProvider(context)
    private val rolloutWriter = com.andmx.data.rollout.RolloutWriter(context)
    private val rolloutReader = com.andmx.data.rollout.RolloutReader()
    private val configLoader = com.andmx.config.ConfigLoader(context, guestFs, store)
    private val telemetrySink = com.andmx.telemetry.TelemetrySink(repo)
    private val tokenUsageTracker = com.andmx.llm.TokenUsageTracker()
    private val imageJson = Json { ignoreUnknownKeys = true }
    private val shellTool = ShellTool(context, cwdProvider = { project })

    /** Live plan state for UI binding. */
    val planState get() = updatePlanTool.state
    /** Live memory state for UI binding. */
    val memoryState get() = memorySystem.state
    /** Live plugin state for UI binding. */
    val pluginState get() = pluginSystem.state
    /** ExecPolicy for UI binding (SecurityPanel). */
    val execPolicyInstance get() = execPolicy
    /** NetworkPolicy for UI binding (SecurityPanel). */
    val networkPolicyInstance get() = networkPolicy
    /** Current approval mode for UI binding (SecurityPanel). */
    val currentApprovalMode get() = approvalMode

    /** Token usage tracker for UI binding (token stats display). */
    val tokenTracker get() = tokenUsageTracker

    /** Telemetry sink for UI binding (logs panel). */
    val telemetry get() = telemetrySink

    /** Sub-agent orchestrator — allows the main agent to fan out tasks. */
    private var _orchestrator: com.andmx.agent.multi.SubAgentOrchestrator? = null
    val orchestrator get() = _orchestrator

    /** Initialize sub-agent support (called after settings are loaded). */
    fun initSubAgents() {
        if (_orchestrator == null && isProviderReady) {
            val def = primaryProvider ?: return
            _orchestrator = com.andmx.agent.multi.SubAgentOrchestrator(
                toolsFactory = { builtInTools.filter { it.name != "spawn_agent" && it.name != "multi_agent" } },
                settings = settings,
                client = com.andmx.llm.LlmClient(def, tokenUsageTracker),
                turnProvider = { currentTurn!! },
                parentHistoryProvider = { engine.snapshotHistory() },
            )
            // Register both the convenience spawn tool and the full lifecycle
            // control tool (resume/wait/close/list), so the model can manage
            // sub-agent state, not just fire-and-forget.
            engine.addTools(listOf(_orchestrator!!.createSubAgentTool(), _orchestrator!!.createMultiAgentTool()))
            scope.launch {
                _orchestrator!!.events.collectLatest { event ->
                    updateBackgroundTask(event)
                }
            }
        }
        // Register plugin-declared tools once, after the engine is ready.
        if (!_pluginToolsLoaded) {
            _pluginToolsLoaded = true
            scope.launch {
                val tools = runCatching { pluginSystem.loadPluginTools(context) }.getOrDefault(emptyList())
                if (tools.isNotEmpty()) engine.addTools(tools)
            }
        }
    }

    private var _pluginToolsLoaded = false

    /** Clear all memories (for MemoryPanel clear button). */
    fun clearMemory() {
        scope.launch { memorySystem.clear() }
    }

    /** Consolidate raw memories into MEMORY.md (for MemoryPanel consolidate button). */
    fun consolidateMemory() {
        scope.launch { memorySystem.consolidate() }
    }

    /** Toggle plugin enabled state (for PluginPanel). */
    fun togglePlugin(name: String, enabled: Boolean) {
        scope.launch { pluginSystem.setEnabled(name, enabled) }
    }

    /** Goal tool state — shared between agent tools and the UI. */
    private val goalToolState = com.andmx.agent.GoalToolState()
    /** Set by /goal edit to request the UI to open the goal overlay. */
    var showGoalCommand by mutableStateOf(false)

    private val builtInTools = listOf(
        shellTool,
        ReadFileTool(context),
        WriteFileTool(context),
        EditFileTool(context),
        ApplyPatchTool(context),
        GitTool(context, cwdProvider = { project }),
        BrowseTool(networkPolicy, onBrowseUrl = { url -> browseMirrorUrl = url }),
        ListDirTool(context),
        WebSearchTool(networkPolicy),
        updatePlanTool,
        // Codex-style goal management: the agent can create, update, and query
        // the current objective + token budget autonomously.
        com.andmx.agent.CreateGoalTool(goalToolState),
        com.andmx.agent.UpdateGoalTool(goalToolState),
        com.andmx.agent.GetGoalTool(goalToolState),
        ComputerUseTool(context),
    )

    /** The currently-bound provider; drives LlmClient + TurnContext. */
    private var primaryProvider: com.andmx.llm.provider.ProviderDefinition? = null

    /** The engine is rebuilt when the primary provider changes so the client binds the right endpoint/key/protocol. */
    private var engine = AgentEngine(
        tools = builtInTools,
        // Placeholder provider; replaced as soon as the primary flow emits.
        client = com.andmx.llm.LlmClient(
            com.andmx.llm.provider.ProviderDefinition(
                id = "placeholder", name = "", baseUrl = "",
                apiKey = "", apiKeyRequired = false,
            )
        ),
        approve = ::approveGate,
    )
    private var seq = 0L
    private var streamingIndex: Int? = null
    private var conversationId: Long? = null
    private var pendingApproval: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    private var pendingApprovalIndex: Int? = null
    private var turnJob: kotlinx.coroutines.Job? = null
    private var turnStopped = false
    private val toolArgsByCallId = mutableMapOf<String, String>()
    private val turnToolOutputs = mutableListOf<Pair<String, String>>()
    private val mcpManager = com.andmx.mcp.McpManager(context)
    private var mcpConnected = false

    val items = mutableStateListOf<ChatItem>()
    var executionSessionState by mutableStateOf(ExecutionSessionState(project = project))
        private set
    var busy by mutableStateOf(false)
        private set
    var settings by mutableStateOf(ProviderSettings())
        private set

    /** All configured providers (primary first) for the settings UI. */
    var providers by mutableStateOf<List<com.andmx.llm.provider.ProviderDefinition>>(emptyList())
        private set

    /**
     * The most recent URL the agent browsed via the `browse` tool. The UI watches
     * this to mirror the agent's browsing in the in-app browser pane (Codex
     * parity: agent browses → user sees the same page preview).
     */
    var browseMirrorUrl by mutableStateOf<String?>(null)
        private set
    var activeId by mutableStateOf<Long?>(null)
        private set
    var goal by mutableStateOf(ConversationGoal())
        private set
    val executionState get() = executionSessionState

    /** Set by the host UI so `/model` can open the settings sheet. */
    var onOpenSettings: () -> Unit = {}

    private fun handleSlash(
        text: String,
        cmd: com.andmx.agent.SlashResult,
        attachments: List<Attachment> = emptyList(),
    ) {
        val commandText = text.trim()
        val userText = Attachments.displayText(commandText, attachments)
        items += ChatItem.User(seq++, userText)
        when (cmd) {
            is com.andmx.agent.SlashResult.Clear -> newConversation()
            is com.andmx.agent.SlashResult.Mode -> {
                saveSettings(settings.copy(approvalMode = cmd.mode.name.lowercase()))
                val msg = "授权模式已设为 **${cmd.mode.label}**"
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.OpenModel -> {
                onOpenSettings()
                val msg = "已打开设置。"
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Status -> {
                val msg = statusText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Context -> {
                val msg = contextText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Plan -> {
                val msg = planText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Verify -> {
                val msg = verifyText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Changes -> {
                val msg = changeSummaryText(com.andmx.workspace.ChangeTracker.changes.value)
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Activity -> {
                val msg = activitySummaryText(items)
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Checklist -> {
                val msg = checklistText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Next -> {
                val msg = nextText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Evidence -> {
                val msg = evidenceText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.References -> {
                val msg = referencesText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Blueprint -> {
                val msg = blueprintText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Policy -> {
                val msg = policyText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Tools -> {
                val msg = toolsText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Parity -> {
                val msg = parityText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Report -> {
                val msg = reportText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Architecture -> {
                val msg = architectureText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Surfaces -> {
                val msg = surfacesText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.VisualCheck -> {
                val msg = visualCheckText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.DesignSystem -> {
                val msg = designSystemText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.ScreenshotExtract -> {
                val msg = screenshotExtractText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Appshots -> {
                val msg = appshotsText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Trace -> {
                val msg = traceText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.SelfModel -> {
                val msg = selfModelText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Flow -> {
                val msg = flowText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Method -> {
                val msg = methodText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Improve -> {
                val msg = improveText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Instructions -> {
                val msg = instructionsText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Commands -> {
                val msg = commandsText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Goal -> {
                val msg = goalCommandText(cmd)
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Handoff -> {
                val msg = handoffText()
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Diag -> {
                val pending = "正在运行执行环境诊断…"
                items += ChatItem.Assistant(seq++, pending)
                val idx = items.lastIndex
                scope.launch {
                    val id = ensureConversationForLocalCommand(userText)
                    repo.addMessage(id, "assistant", pending)
                    val summary = runtimeEnvironmentSummary()
                    val exec = com.andmx.exec.ExecProbe(context).run()
                    val proot = com.andmx.exec.proot.ProotProbe(context).run()
                    val result = buildString {
                        appendLine("## 执行环境摘要")
                        appendLine(runtimeEnvironmentStatusText(summary))
                        appendLine()
                        appendLine("## 完整探针")
                        appendLine("```")
                        appendLine(exec.text)
                        appendLine()
                        appendLine(proot)
                        appendLine("```")
                    }.trimEnd()
                    items[idx] = ChatItem.Assistant(seq++, result)
                    repo.addMessage(id, "assistant", result)
                }
            }
            is com.andmx.agent.SlashResult.Export -> {
                scope.launch {
                    val id = ensureConversationForLocalCommand(userText)
                    val md = buildExportMarkdown()
                    val path = "/root/andmx-export-${System.currentTimeMillis()}.md"
                    val ok = runCatching { guestFs.writeText(path, md) }.isSuccess
                    val msg = if (ok) "已导出对话到 `$path`(${items.size} 条)。" else "导出失败。"
                    items += ChatItem.Assistant(seq++, msg)
                    repo.addMessage(id, "assistant", msg)
                }
            }
            is com.andmx.agent.SlashResult.Help -> {
                val msg = "可用命令:\n" + com.andmx.agent.SlashCommands.list.joinToString("\n") { "- `${it.name}` ${it.desc}" }
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.Unknown -> {
                val msg = "未知命令: ${cmd.name}(试试 `/help`)"
                items += ChatItem.Assistant(seq++, msg)
                persistLocalTurn(userText, msg)
            }
            is com.andmx.agent.SlashResult.NotCommand -> Unit
        }
    }

    private fun persistLocalTurn(userText: String, assistantText: String) {
        scope.launch {
            val id = ensureConversationForLocalCommand(userText)
            repo.addMessage(id, "assistant", assistantText)
        }
    }

    private suspend fun ensureConversationForLocalCommand(userText: String): Long {
        if (conversationId == null) {
            conversationId = repo.createConversation(project, userText.take(30).ifBlank { "本地命令" })
            activeId = conversationId
            if (goal.hasGoal) persistGoal()
        }
        return conversationId!!.also { repo.addMessage(it, "user", userText) }
    }

    private fun statusText(): String = buildString {
        val runtime = runtimeEnvironmentSummary()
        val tokens = com.andmx.agent.TokenEstimate.estimateAll(
            items.mapNotNull {
                when (it) {
                    is ChatItem.User -> it.text
                    is ChatItem.Assistant -> it.text
                    is ChatItem.ToolUse -> it.output
                    else -> null
                }
            },
        )
        appendLine("## 会话状态")
        appendLine("- 模型: `${settings.model}`")
        appendLine("- 端点: `$endpointLabel`")
        appendLine("- 授权: **${approvalMode.label}**")
        appendLine("- 内置工具: ${engine.listTools().size}")
        appendLine("- MCP 服务器: ${mcpManager.connected.size}")
        appendLine("- 语气: ${settings.persona}")
        appendLine("- 推理档: ${settings.reasoningEffort}")
        appendLine("- 消息数: ${items.size}")
        appendLine("- 估算上下文: ~$tokens tokens")
        appendLine("- 项目: `$project`")
        if (goal.hasGoal) {
            appendLine("- 当前目标: ${goal.text}")
            appendLine("- 目标状态: ${goal.phase.label}")
        }
        appendLine()
        appendLine("### 执行环境")
        appendLine(runtimeEnvironmentStatusText(runtime))
        appendLine()
        append("- 工作边界: 文件/终端/diff 会尽量映射到同一工作面; 具体边界以当前执行环境状态为准")
    }

    private fun runtimeEnvironmentSummary(): com.andmx.ui.workbench.RuntimeEnvironmentSummary {
        val runtime = com.andmx.exec.proot.ProotRuntime(context)
        return buildRuntimeEnvironmentSummary(
            flavor = BuildConfig.FLAVOR,
            targetSdk = BuildConfig.PROBE_TARGET_SDK,
            abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            prootBundled = runtime.isBundled(),
            rootfsInstalled = com.andmx.exec.proot.RootfsInstaller(runtime).isInstalled(),
            prootBinExists = runtime.prootBin.exists(),
            loaderBinExists = runtime.loaderBin.exists(),
            rootfsPath = runtime.rootfsDir.path,
            usrPath = runtime.usrDir.path,
            tmpPath = runtime.tmpDir.path,
        )
    }

    private fun contextText(): String {
        val tokenInputs = items.mapNotNull {
            when (it) {
                is ChatItem.User -> it.text
                is ChatItem.Assistant -> it.text
                is ChatItem.ToolUse -> it.output
                is ChatItem.Approval -> it.summary
            }
        }
        val toolEvents = items.filterIsInstance<ChatItem.ToolUse>()
        val sourceLinks = toolEvents.mapNotNull { tool ->
            val path = com.andmx.agent.ToolArgs.filePath(tool.name, tool.args)
            val url = com.andmx.agent.ToolArgs.webUrl(tool.name, tool.args)
            path.ifBlank { url }.takeIf { it.isNotBlank() }
        }.distinct()
        return contextSnapshotText(
            ContextSnapshot(
                project = project,
                model = settings.model,
                tokenEstimate = com.andmx.agent.TokenEstimate.estimateAll(tokenInputs),
                messageCount = items.size,
                userMessages = items.count { it is ChatItem.User },
                assistantMessages = items.count { it is ChatItem.Assistant },
                toolEvents = toolEvents.size,
                approvalEvents = items.count { it is ChatItem.Approval },
                changedFiles = com.andmx.workspace.ChangeTracker.changes.value.size,
                sourceLinks = sourceLinks.size,
                recentActivity = runLogEntries(items).size,
            ),
        )
    }

    private fun verifyText(): String = buildString {
        val entries = verificationEntries(items, limit = 12)
        appendLine("## 验证摘要")
        if (entries.isEmpty()) {
            appendLine("- 暂无测试、构建或诊断记录")
            appendLine()
            appendLine("### 建议")
            appendLine("- 运行项目测试或构建命令后, 这里会汇总最近结果")
            appendLine("- 运行 `/diag` 可记录执行环境诊断")
            return@buildString
        }
        entries.forEach { entry ->
            val state = entry.state.verificationStateLabel()
            appendLine("- $state · `${entry.command}`")
            appendLine("  - ${entry.detail.ifBlank { "(等待输出)" }}")
        }
        appendLine()
        appendLine("### 交接")
        appendLine("- `/handoff` 会把这些验证结果写入恢复摘要")
    }

    private fun planText(): String {
        return taskPlanText(taskPlanSnapshot())
    }

    private fun checklistText(): String =
        sessionChecklistText(
            buildSessionChecklist(
                snapshot = inspectorSnapshot(),
                plan = taskPlanSnapshot(),
                verifications = verificationEntries(items, limit = 12),
                recentActivity = runLogEntries(items).size,
            ),
        )

    private fun nextText(): String = nextActionText(nextActionDecision())

    private fun evidenceText(): String = evidenceLedgerText(evidenceLedger())

    private fun referencesText(): String =
        uiReferenceBoardText(uiReferenceBoard())

    private fun blueprintText(): String =
        uiReplicaBlueprintText(uiReplicaBlueprint())

    private fun policyText(): String = toolPolicyText(toolPolicySummary())

    private fun parityText(): String =
        codexParityText(codexParityAudit())

    private fun reportText(): String =
        deliveryReportText(deliveryReport())

    private fun architectureText(): String =
        agentArchitectureText(agentArchitectureBlueprint())

    private fun surfacesText(): String =
        codexSurfaceMapText(codexSurfaceMap())

    private fun visualCheckText(): String =
        visualAcceptanceText(visualAcceptanceSummary())

    private fun designSystemText(): String =
        codexDesignSystemText(codexDesignSystemAudit())

    private fun screenshotExtractText(): String =
        screenshotExtractionText(screenshotExtractionSummary())

    private fun appshotsText(): String =
        codexAppshotCaptureGuideText(
            buildCodexAppshotCaptureGuide(
                references = uiReferenceLedger(),
                evidence = evidenceLedger(),
                snapshot = inspectorSnapshot(),
            ),
        )

    private fun traceText(): String =
        screenshotImplementationTraceText(screenshotImplementationTrace())

    private fun selfModelText(): String =
        codexSelfModelText(codexSelfModel())

    private fun flowText(): String =
        codexInteractionFlowText(codexInteractionFlow())

    private fun toolsText(): String = buildString {
        val tools = toolCapabilities()
        val toolMap = toolCapabilityMap()
        appendLine("## 能力与工具")
        appendLine("- 工作区: `Android/proot Alpine` guest rootfs")
        appendLine("- 文件引用: 在消息里使用 `@/root/file` 或 `@relative/path`")
        appendLine("- 审查方式: 写入、补丁和编辑会进入 Diff 面板")
        appendLine("- 授权模式: **${approvalMode.label}**")
        appendLine("- 内置工具: ${tools.size}")
        appendLine("- MCP 服务器: ${mcpManager.connected.size}")
        appendLine()
        appendLine(codexToolCapabilityMapText(toolMap))
        appendLine()
        appendLine("### 工具")
        tools.groupBy { it.risk }
            .toSortedMap(compareBy { riskOrder(it) })
            .forEach { (risk, group) ->
                appendLine("#### ${risk.label}")
                group.sortedBy { it.name }.forEach { tool ->
                    appendLine("- `${tool.name}`: ${tool.description}")
                }
                appendLine()
            }
        if (mcpManager.connected.isNotEmpty()) {
            appendLine("### MCP")
            mcpManager.connected.forEach { server ->
                appendLine("- ${server.name}: ${server.tools.joinToString("、").ifBlank { "(无工具)" }}")
            }
            appendLine()
        }
        appendLine("### 工作方式")
        appendLine("1. 先读取项目结构和相关文件")
        appendLine("2. 做最小必要修改")
        appendLine("3. 用命令、测试或 diff 验证事实")
        appendLine("4. 汇报改动、验证结果和剩余风险")
    }

    private fun methodText(): String = agentMethodologyText(
        run {
            val snapshot = inspectorSnapshot()
            val plan = taskPlanSnapshot()
            val verifications = verificationEntries(items, limit = 12)
            val checklist = buildSessionChecklist(
                snapshot = snapshot,
                plan = plan,
                verifications = verifications,
                recentActivity = runLogEntries(items).size,
            )
            val next = buildNextActionDecision(snapshot, checklist, verifications)
            val runtime = runtimeEnvironmentSummary()
            val instructionSummary = buildInstructionStackSummary(
                apiConfigured = apiConfigured,
                mcpConfigured = settings.mcpServers.isNotBlank(),
                customInstructions = settings.customInstructions,
                builtInTools = toolCapabilities().size,
                mcpServers = mcpManager.connected.size,
            )
            AgentMethodologyContext(
                project = project,
                model = settings.model,
                approvalModeLabel = approvalMode.label,
                builtInToolCount = builtInTools.size,
                mcpServerCount = mcpManager.connected.size,
                goalText = snapshot.goalText,
                goalPhaseLabel = snapshot.goalPhaseLabel,
                contextPressure = snapshot.contextPressure,
                tokenEstimate = snapshot.tokenEstimate,
                messageCount = snapshot.messageCount,
                toolEvents = snapshot.toolEvents,
                runningTools = snapshot.runningTools,
                failedTools = snapshot.failedTools,
                pendingApprovals = snapshot.pendingApprovals,
                changedFiles = snapshot.changedFiles,
                uiReferences = snapshot.uiReferences,
                evidenceCount = evidenceLedger().items.size,
                verificationPassed = verifications.count { it.state == com.andmx.ui.workbench.VerificationState.PASSED },
                verificationFailed = verifications.count { it.state == com.andmx.ui.workbench.VerificationState.FAILED },
                checklistMissing = checklist.missingCount,
                checklistWatch = checklist.watchCount,
                nextActionTitle = next.title,
                nextActionCommand = next.command,
                runtimeSurface = runtime.executionSurface,
                runtimeHealth = runtime.healthLabel,
                instructionLayers = instructionSummary.visibleLayers,
            )
        },
    )

    private fun improveText(): String =
        codexSelfImprovementText(codexSelfImprovementPlan())

    private fun instructionsText(): String = instructionStackText(
        InstructionStackSnapshot(
            model = settings.model,
            baseUrl = endpointLabel,
            apiConfigured = apiConfigured,
            persona = settings.persona,
            reasoningEffort = settings.reasoningEffort,
            approvalModeLabel = approvalMode.label,
            customInstructions = settings.customInstructions,
            builtInToolCount = builtInTools.size,
            mcpServerCount = mcpManager.connected.size,
            mcpConfigured = settings.mcpServers.isNotBlank(),
            environmentContractText = codexEnvironmentContractText(environmentContract()),
        ),
    )

    private fun commandsText(): String =
        codexCommandReferenceText(buildCodexCommandReference())

    private fun goalCommandText(cmd: com.andmx.agent.SlashResult.Goal): String {
        when (cmd.action) {
            GoalAction.SET -> {
                val ok = setPersistentGoal(cmd.text, note = "由 /goal 设置")
                if (!ok) return "## 当前目标\n- 未设置\n- 用法: `/goal <目标文本>`"
            }
            GoalAction.PAUSE -> pauseGoal("由 /goal 暂停")
            GoalAction.RESUME -> resumeGoal("由 /goal 恢复,等待继续推进")
            GoalAction.EDIT -> {
                if (!goal.hasGoal) return "## 当前目标\n- 未设置\n- 先创建目标: `/goal <目标文本>`"
                // Trigger the goal overlay in edit mode via the showGoal flag.
                showGoalCommand = true
            }
            GoalAction.CLEAR -> {
                clearGoal()
                return "## 当前目标\n- 已清除\n- 发送新任务或使用 `/goal <目标文本>` 可重新建立线程目标。"
            }
            GoalAction.SHOW -> Unit
        }
        return buildString {
            appendLine("## 当前目标")
            if (!goal.hasGoal) {
                appendLine("- 未设置")
                appendLine("- 用法: `/goal <目标文本>`")
                appendLine("- 控制: `/goal pause`、`/goal resume`、`/goal clear`")
                appendLine("- 提示: 也可以先用 `/plan` 梳理任务, 再把收束后的目标写入 `/goal`。")
                return@buildString
            }
            appendLine("- 目标: ${goal.text}")
            appendLine("- 状态: **${goal.phase.label}**")
            if (goal.note.isNotBlank()) appendLine("- 最近: ${goal.note}")
            appendLine("- 控制: `/goal pause`、`/goal resume`、`/goal clear`")
            appendLine("- 继续: 在目标浮层点继续推进, 或发送 `继续推进: ${goal.text}`")
        }.trimEnd()
    }

    private fun handoffText(): String {
        val changes = com.andmx.workspace.ChangeTracker.changes.value
        val tokenInputs = items.mapNotNull {
            when (it) {
                is ChatItem.User -> it.text
                is ChatItem.Assistant -> it.text
                is ChatItem.ToolUse -> it.output
                is ChatItem.Approval -> it.summary
            }
        }
        val tokenEstimate = com.andmx.agent.TokenEstimate.estimateAll(tokenInputs)
        val plan = taskPlanSnapshot()
        val changeLines = changeSummaryItems(changes).take(12).map {
            val marker = if (it.isNew) "新建" else "修改"
            "${it.path} · $marker · +${it.added} / -${it.removed}"
        }
        val recent = runLogEntries(items, limit = 8).map {
            HandoffRunLogItem(
                title = it.title,
                state = it.state.runLogStateLabel(),
                detail = it.detail,
            )
        }
        val verifications = verificationHandoffLines(verificationEntries(items, limit = 6))
        val sources = items.filterIsInstance<ChatItem.ToolUse>().asReversed().mapNotNull { tool ->
            val path = com.andmx.agent.ToolArgs.filePath(tool.name, tool.args)
            val url = com.andmx.agent.ToolArgs.webUrl(tool.name, tool.args)
            path.ifBlank { url }.takeIf { it.isNotBlank() }
        }.distinct().take(8)
        val runtime = runtimeEnvironmentSummary()
        val instructionSummary = buildInstructionStackSummary(
            apiConfigured = apiConfigured,
            mcpConfigured = settings.mcpServers.isNotBlank(),
            customInstructions = settings.customInstructions,
            builtInTools = builtInTools.size,
            mcpServers = mcpManager.connected.size,
        )
        return threadHandoffText(
            ThreadHandoffContext(
                project = project,
                model = settings.model,
                approvalModeLabel = approvalMode.label,
                goalText = goal.text,
                goalPhaseLabel = goal.phase.label,
                goalNote = goal.note,
                tokenEstimate = tokenEstimate,
                contextPressureLabel = contextPressureLabel(tokenEstimate),
                messageCount = items.size,
                toolCount = engine.listTools().size,
                mcpServerCount = mcpManager.connected.size,
                changedFiles = changeLines,
                sourceLinks = sources,
                recentActivity = recent,
                planItems = plan.items,
                runtimeEnvironment = listOf(
                    "执行环境: ${runtime.executionSurface}",
                    "环境健康: ${runtime.healthLabel}",
                    "rootfs: ${runtime.rootfsStatus}",
                    "二进制: ${runtime.binaryStatus}",
                    "ABI: ${runtime.abiStatus}",
                    "诊断入口: ${runtime.primaryCommand}",
                ),
                instructionBoundaries = instructionSummary.visibleLayers +
                    listOf(instructionSummary.apiStatus, instructionSummary.mcpStatus, instructionSummary.safetyBoundary),
                verifications = verifications,
                resumePrompt = buildResumePrompt(plan),
            ),
        )
    }

    private fun buildResumePrompt(plan: TaskPlanSnapshot): String = buildString {
        appendLine("继续这个 AndMX 线程。")
        appendLine("项目: $project")
        appendLine("目标: ${goal.text.ifBlank { "(未设置)" }}")
        appendLine("状态: ${goal.phase.label}${goal.note.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}")
        appendLine("请先阅读上方交接摘要, 然后按当前计划继续:")
        plan.items.forEachIndexed { index, item ->
            appendLine("${index + 1}. ${item.status.label} · ${item.title}: ${item.detail}")
        }
    }

    init {
        // Bridge agent goal tools → Compose state. When the agent calls
        // create_goal/update_goal, the new goal flows into the UI.
        goalToolState.onGoalChange = { newGoal ->
            goal = newGoal
            syncGoalSnapshots()
            persistGoal()
        }
        scope.launch {
            shellTool.events.collectLatest { event ->
                updateShellBinding(event)
            }
        }
        // Seed the providers table from legacy DataStore values on first run.
        scope.launch {
            providerStore.ensureSeeded(store.legacyProvider())
        }
        // Track the active provider and rebuild the engine/compactor when it changes.
        scope.launch {
            providerStore.primary.collectLatest { def ->
                if (def != null && def != primaryProvider) {
                    primaryProvider = def
                    rebuildEngine()
                }
            }
        }
        // Keep the provider list in sync for the settings UI.
        scope.launch {
            providerStore.providers.collectLatest { providers = it }
        }
        scope.launch {
            store.settings.collectLatest {
                settings = it
                engine.setCustomInstructions(it.customInstructions)
                engine.setPersona(it.persona)
                maybeConnectMcp(it.mcpServers)
            }
        }
    }

    /**
     * Rebuild the agent engine + compactor around the current [primaryProvider],
     * preserving conversation history and MCP-provided tools across the swap.
     */
    private suspend fun rebuildEngine() {
        val def = primaryProvider ?: return
        val client = com.andmx.llm.LlmClient(def, tokenUsageTracker)
        val savedHistory = engine.snapshotHistory()
        val savedTools = engine.listTools().mapNotNull { (name, _) ->
            builtInTools.firstOrNull { it.name == name }
        }
        engine = AgentEngine(
            tools = builtInTools,
            client = client,
            approve = ::approveGate,
        )
        engine.setCustomInstructions(settings.customInstructions)
        engine.setPersona(settings.persona)
        if (savedHistory.isNotEmpty()) engine.seed(savedHistory)
        // Re-add MCP tools if any were connected before the rebuild.
        val extraTools = savedTools.filter { it !in builtInTools }
        if (extraTools.isNotEmpty()) engine.addTools(extraTools)
    }

    /** The current turn context (provider + selected model), or null if not ready. */
    private val currentTurn: com.andmx.agent.TurnContext?
        get() = primaryProvider?.let { def ->
            com.andmx.agent.TurnContext(provider = def, model = settings.model)
        }

    /** Whether a usable provider is bound and a model is selected. */
    val isProviderReady: Boolean
        get() = primaryProvider?.isUsable == true && settings.model.isNotBlank()

    /** The reasoning capability of the currently-selected model, or null. Drives the UI selector. */
    val currentModelReasoning: com.andmx.llm.provider.ReasoningConfig?
        get() = primaryProvider?.models?.get(settings.model)?.reasoning

    /** Convenience for snapshot structs that previously read settings.isConfigured. */
    val apiConfigured: Boolean get() = isProviderReady

    /** Convenience for snapshot structs that previously read settings.baseUrl. */
    val endpointLabel: String get() = primaryProvider?.baseUrl.orEmpty()

    /** Convenience for snapshot structs that previously read settings.modelProvider. */
    val providerLabel: String get() = primaryProvider?.name ?: primaryProvider?.id.orEmpty()

    /** Connect MCP servers once, after settings are available. */
    private fun maybeConnectMcp(configText: String) {
        if (mcpConnected || configText.isBlank()) return
        mcpConnected = true
        scope.launch {
            val tools = mcpManager.connectAll(configText) { line ->
                android.util.Log.i("AndmxMcp", line)
            }
            if (tools.isNotEmpty()) engine.addTools(tools)
        }
    }

    fun newConversation() {
        conversationId = null
        activeId = null
        items.clear()
        executionSessionState = ExecutionSessionState(project = project)
        streamingIndex = null
        toolArgsByCallId.clear()
        turnToolOutputs.clear()
        goal = ConversationGoal()
        syncGoalSnapshots()
        updatePlanTool.clear()  // ← clear plan on new conversation
        editIndex = null
        engine.seed(emptyList())
        refreshSystemPrompt()
    }

    fun load(id: Long) {
        scope.launch {
            val msgs = repo.messages(id)
            conversationId = id
            activeId = id
            editIndex = null
            // Restore the project working directory this conversation belongs to,
            // so tools operate in the right cwd after switching conversations.
            repo.conversation(id)?.project?.takeIf { it.isNotBlank() }?.let { project = it }
            items.clear()
            streamingIndex = null
            toolArgsByCallId.clear()
            val api = mutableListOf<ApiMessage>()
            for (m in msgs) {
                when (m.role) {
                    "user" -> {
                        items += ChatItem.User(seq++, m.content, sentAt = m.createdAt)
                        api += ApiMessage("user", m.content)
                    }
                    "assistant" -> {
                        items += ChatItem.Assistant(
                            seq++,
                            m.content,
                            done = true,
                            sentAt = m.createdAt,
                            completedAt = m.createdAt,
                        )
                        api += ApiMessage("assistant", m.content)
                    }
                    "tool" -> {
                        items += ChatItem.ToolUse(
                            seq++,
                            "saved-${m.id}",
                            m.toolName ?: "tool",
                            m.toolArgs,
                            m.content,
                            running = false,
                            error = m.toolError,
                            imageUrls = decodeImageUrls(m.imageUrlsJson),
                        )
                        api += ApiMessage("assistant", "[tool ${m.toolName}] ${m.content.take(400)}")
                    }
                    "approval" -> {
                        val risk = restoredApprovalRisk(m.approvalRisk) ?: toolRiskFor(m.toolName ?: "tool")
                        items += ChatItem.Approval(
                            key = seq++,
                            toolName = m.toolName ?: "tool",
                            summary = m.content,
                            risk = risk,
                            approvalModeLabel = m.approvalModeLabel.ifBlank { ApprovalMode.ASK.label },
                            riskDescription = m.approvalRiskDescription.ifBlank { risk.description },
                            resolved = true,
                            allowed = !m.toolError,
                        )
                    }
                }
            }
            val restoredGoal = repo.conversation(id)?.toGoal()?.takeIf { it.hasGoal }
            goal = restoredGoal ?: deriveGoalFromTranscript()
            syncGoalSnapshots()
            if (restoredGoal == null && goal.hasGoal) persistGoal()
            rebuildExecutionSessionState()
            engine.seed(api)
            refreshSystemPrompt()
        }
    }

    /**
     * Inject memory, AGENTS.md, plugin skills, and device context into the
     * system prompt as suffix instructions. This mirrors Codex's layered
     * instruction stack.
     */
    private fun refreshSystemPrompt() {
        scope.launch {
            val sb = StringBuilder()

            // Memory
            runCatching { memorySystem.promptFragment() }.getOrNull()?.let {
                if (it.isNotBlank()) sb.appendLine(it)
            }

            // AGENTS.md
            runCatching { agentsMdLoader.promptFragment() }.getOrNull()?.let {
                if (it.isNotBlank()) sb.appendLine(it)
            }

            // Project context: git status + top-level layout, so the model
            // knows the working tree and branch without probing first.
            runCatching { projectContextFragment() }.getOrNull()?.let {
                if (it.isNotBlank()) sb.appendLine(it)
            }

            // Plugin skills
            runCatching { pluginSystem.skillsPromptFragment() }.getOrNull()?.let {
                if (it.isNotBlank()) sb.appendLine(it)
            }

            // Device context (Android-only advantage)
            runCatching {
                androidContext.refresh()
                androidContext.promptFragment()
            }.getOrNull()?.let {
                if (it.isNotBlank()) sb.appendLine(it)
            }

            // User's custom instructions
            if (settings.customInstructions.isNotBlank()) {
                sb.appendLine("# 用户自定义指令")
                sb.appendLine(settings.customInstructions)
            }

            engine.setCustomInstructions(sb.toString().trim())
        }
    }

    /**
     * Snapshot the guest project's git state and top-level layout, injected into
     * the system prompt so the agent starts with working-tree awareness (branch,
     * uncommitted changes, recent commits, directory skeleton). Cheap read-only
     * commands; failures are swallowed (no git → empty fragment).
     */
    private suspend fun projectContextFragment(): String {
        val runtime = com.andmx.exec.proot.ProotRuntime(context)
        if (!runtime.isBundled()) return ""
        val env = com.andmx.exec.proot.LocalProotEnvironment(context, runtime)
        suspend fun sh(cmd: String): String = runCatching {
            env.execute(com.andmx.exec.ProcessSpec(argv = listOf("/bin/sh", "-c", cmd))).stdout.trim()
        }.getOrDefault("").take(1500)

        val cwd = project
        val status = sh("cd '$cwd' && git status -sb")
        val log = sh("cd '$cwd' && git log --oneline -5")
        val dirs = sh("ls -1p '$cwd' | head -30")
        if (status.isBlank() && dirs.isBlank()) return ""
        return buildString {
            appendLine("# 项目上下文 ($cwd)")
            if (status.isNotBlank()) { appendLine("## Git"); appendLine(status) }
            if (log.isNotBlank()) { appendLine("## 近期提交"); appendLine(log) }
            if (dirs.isNotBlank()) { appendLine("## 目录概览"); appendLine(dirs) }
        }.trim()
    }

    fun send(text: String, attachments: List<Attachment> = emptyList()) {
        sendInternal(text, attachments)
    }

    private fun sendInternal(
        text: String,
        attachments: List<Attachment> = emptyList(),
        goalOverride: String? = null,
        conversationTitleOverride: String? = null,
        goalNoteOverride: String? = null,
    ) {
        if (busy) return
        // If resending an edited message, truncate the transcript back to the
        // edit point *now* (synchronously on the UI thread): drop the old user
        // message and everything after it, then re-seed the engine. The DB
        // truncation happens inside the turn job below.
        val pendingEditIdx = editIndex
        editIndex = null
        if (pendingEditIdx != null) {
            while (items.size > pendingEditIdx) items.removeAt(items.lastIndex)
            streamingIndex = null
            toolArgsByCallId.clear()
            val remaining = mutableListOf<com.andmx.llm.ApiMessage>()
            items.forEach { item ->
                when (item) {
                    is ChatItem.User -> remaining += com.andmx.llm.ApiMessage(role = "user", content = item.text)
                    is ChatItem.Assistant -> remaining += com.andmx.llm.ApiMessage(role = "assistant", content = item.text)
                    else -> {}
                }
            }
            engine.seed(remaining)
            rebuildExecutionSessionState()
        }
        val display = Attachments.displayText(text, attachments)
        when (val cmd = com.andmx.agent.SlashCommands.parse(text)) {
            is com.andmx.agent.SlashResult.NotCommand -> Unit
            else -> { handleSlash(text, cmd, attachments); return }
        }
        if (!isProviderReady) {
            items += ChatItem.User(seq++, display, sentAt = System.currentTimeMillis())
            beginExecutionTurn(display, attachments)
            val intake = Attachments.localIntakeText(attachments)
            val msg = listOf(
                "⚠️ 尚未配置 API 密钥。点击右上角设置填入 baseUrl / Key / 模型。",
                intake.takeIf { it.isNotBlank() },
            ).filterNotNull().joinToString("\n\n")
            items += ChatItem.Assistant(seq++, msg)
            updateActiveTurn {
                it.copy(
                    assistantText = msg,
                    assistantDone = true,
                    status = TurnStatus.FAILED,
                    completedAt = System.currentTimeMillis(),
                )
            }
            executionSessionState = executionSessionState.copy(isWorking = false, activeTurnId = null)
            // Don't auto-create a goal — goals are set explicitly via /goal
            // or the agent's create_goal tool.
            scope.launch {
                if (conversationId == null) {
                    conversationId = repo.createConversation(project, conversationTitleOverride ?: text.take(30).ifBlank { "需配置模型" })
                    activeId = conversationId
                    persistGoal()
                    executionSessionState = executionSessionState.copy(conversationId = conversationId, project = project)
                }
                conversationId?.let {
                    repo.addMessage(it, "user", display)
                    repo.addMessage(it, "assistant", msg)
                }
            }
            return
        }
        items += ChatItem.User(seq++, display, sentAt = System.currentTimeMillis())
        beginExecutionTurn(display, attachments)
        toolArgsByCallId.clear()
        // Goals are only set via /goal command or agent create_goal tool —
        // not automatically on every message. Update an existing goal's status
        // if one is active, but don't create one.
        if (goal.hasGoal && goal.status == GoalStatus.ACTIVE) {
            // Keep goal active during the turn.
        }
        busy = true
        turnStopped = false
        turnJob = scope.launch {
            try {
                if (conversationId == null) {
                    conversationId = repo.createConversation(project, conversationTitleOverride ?: text.take(30).ifBlank { "附件对话" })
                    activeId = conversationId
                    persistGoal()
                    executionSessionState = executionSessionState.copy(conversationId = conversationId, project = project)
                }
                // Persist the edit-revert truncation before adding the new message.
                if (pendingEditIdx != null) {
                    val id = conversationId
                    if (id != null) {
                        val dbMsgs = repo.messages(id)
                        if (pendingEditIdx < dbMsgs.size) {
                            repo.truncateFrom(id, dbMsgs[pendingEditIdx].id)
                        }
                    }
                }
                conversationId?.let { repo.addMessage(it, "user", display) }

                // Expand @file references + fold attachment contents into model context.
                var augmented = com.andmx.agent.FileRefs.augment(text) { path ->
                    runCatching { guestFs.readText(path) }.getOrNull()
                }
                augmented = Attachments.augment(augmented, attachments)
                val images = attachments.mapNotNull { it.imageDataUrl }

                // Run PRE_TOOL_USE equivalent: UserPromptSubmit hook
                if (hookSystem.hasHooksFor(com.andmx.agent.hooks.HookSystem.HookEvent.USER_PROMPT_SUBMIT)) {
                    hookSystem.runEvent(
                        com.andmx.agent.hooks.HookSystem.HookEvent.USER_PROMPT_SUBMIT,
                        com.andmx.agent.hooks.HookSystem.HookContext(userInput = augmented),
                    )
                }

                // Refresh system prompt with latest memory/agents.md/device context
                refreshSystemPrompt()

                // Start rollout session if not already started
                if (rolloutWriter.currentSessionId().isBlank()) {
                    val sid = rolloutWriter.startSession(
                        cwd = project,
                        modelProvider = providerLabel,
                        baseInstructions = engine.composedSystemPrompt(),
                        cliVersion = "${com.andmx.BuildConfig.VERSION_NAME}",
                    )
                    conversationId?.let { cid ->
                        repo.updateSessionMetadata(
                            cid, rolloutWriter.currentFilePath() ?: "",
                            sid, settings.approvalMode, settings.model,
                            settings.reasoningEffort, "enabled", text.take(200),
                        )
                    }
                }

                // Write turn context
                rolloutWriter.writeTurnContext(
                    com.andmx.data.rollout.TurnContext(
                        turnId = java.util.UUID.randomUUID().toString(),
                        cwd = project,
                        currentDate = java.time.LocalDate.now().toString(),
                        timezone = java.time.ZoneId.systemDefault().id,
                        approvalPolicy = settings.approvalMode,
                        sandboxPolicy = settings.approvalMode,
                        model = settings.model,
                        personality = settings.persona,
                    )
                )

                // Telemetry: start turn span
                val turnSpan = telemetrySink.startSpan("agent_turn", conversationId = conversationId)

                engine.runTurn(settings, currentTurn!!, augmented, images).collect { ev -> handle(ev) }

                // End turn span
                telemetrySink.endSpan(turnSpan)

                // Record token usage as event
                val usage = tokenUsageTracker.lastTurnUsage.value
                if (usage.totalTokens > 0) {
                    rolloutWriter.writeEventMsg(
                        com.andmx.data.rollout.EventMsg(
                            type = "token_usage",
                            inputTokens = usage.inputTokens,
                            cachedInputTokens = usage.cachedInputTokens,
                            outputTokens = usage.outputTokens,
                            reasoningOutputTokens = usage.reasoningOutputTokens,
                            totalTokens = usage.totalTokens,
                        )
                    )
                }
            } finally {
                if (turnStopped) {
                    turnStopped = false
                } else if (goal.phase == GoalPhase.RUNNING || goal.phase == GoalPhase.WAITING_APPROVAL) {
                    updateGoalPhase(GoalPhase.READY, "最近一轮已结束")
                }
                busy = false
                turnJob = null
            }
        }
    }

    /** Interrupt the running agent turn. */
    fun stop() {
        pendingApproval?.complete(false)
        pendingApproval = null
        turnStopped = true
        turnJob?.cancel()
        turnJob = null
        // Commit the in-progress streaming message as a completed (partial)
        // assistant item so its text isn't lost — don't just drop it.
        streamingIndex?.let { idx ->
            if (idx in items.indices) {
                val partial = items[idx] as? ChatItem.Assistant
                if (partial != null && partial.text.isNotBlank()) {
                    val now = System.currentTimeMillis()
                    items[idx] = partial.copy(done = true, completedAt = now)
                    conversationId?.let { id -> scope.launch { repo.addMessage(id, "assistant", partial.text) } }
                } else {
                    items.removeAt(idx)
                }
            }
        }
        streamingIndex = null
        busy = false
        updateGoalPhase(GoalPhase.PAUSED, "已由用户停止")
        finishExecution(TurnStatus.CANCELLED)
        val msg = "_已停止_"
        items += ChatItem.Assistant(seq++, msg)
        conversationId?.let { id -> scope.launch { repo.addMessage(id, "assistant", msg) } }
    }

    /** Regenerate the last assistant response. */
    fun retry() {
        if (busy) return
        // drop trailing assistant/tool items in the UI back to the last user message
        var i = items.lastIndex
        while (i >= 0 && items[i] !is ChatItem.User) { items.removeAt(i); i-- }
        if (items.none { it is ChatItem.User }) return
        rebuildExecutionSessionState()
        // Don't auto-create goal on retry — only if one already exists.
        if (goal.hasGoal) {
            goal = goal.copy(status = GoalStatus.ACTIVE, phase = GoalStatus.ACTIVE.toPhase(), note = "正在重新生成", updatedAt = System.currentTimeMillis())
            persistGoal()
        }
        toolArgsByCallId.clear()
        busy = true
        turnStopped = false
        turnJob = scope.launch {
            try {
                engine.regenerate(settings, currentTurn!!).collect { ev -> handle(ev) }
            } finally {
                if (turnStopped) turnStopped = false
                else if (goal.phase == GoalPhase.RUNNING || goal.phase == GoalPhase.WAITING_APPROVAL) {
                    updateGoalPhase(GoalPhase.READY, "最近一轮已结束")
                }
                busy = false
                turnJob = null
            }
        }
    }

    /** Fork a new conversation containing the transcript up to [index] (inclusive). */
    fun branchFrom(index: Int) {
        if (index !in items.indices) return
        val slice = items.take(index + 1).toList()
        scope.launch {
            val title = "(分支) " + (slice.firstOrNull { it is ChatItem.User } as? ChatItem.User)?.text?.take(24).orEmpty()
            val newId = repo.createConversation(project, title.ifBlank { "分支" })
            for (it in slice) when (it) {
                is ChatItem.User -> repo.addMessage(newId, "user", it.text)
                is ChatItem.Assistant -> repo.addMessage(newId, "assistant", it.text)
                is ChatItem.ToolUse -> repo.addMessage(
                    newId,
                    "tool",
                    it.output.orEmpty(),
                    toolName = it.name,
                    toolArgs = it.args,
                    toolError = it.error,
                    imageUrls = it.imageUrls,
                )
                is ChatItem.Approval -> if (it.resolved) {
                    repo.addMessage(
                        newId,
                        "approval",
                        it.summary,
                        toolName = it.toolName,
                        toolError = !it.allowed,
                        approvalRisk = it.risk.name,
                        approvalModeLabel = it.approvalModeLabel,
                        approvalRiskDescription = it.riskDescription,
                    )
                }
            }
            load(newId)
        }
    }

    /**
     * The index in [items] currently being edited, or null. When non-null, the
     * next [sendInternal] will truncate the conversation back to this point
     * *before* sending — so the old tail is only discarded once the user
     * actually resends. Mirrors Codex: edit loads the text into the composer,
     * nothing is destroyed until send.
     */
    var editIndex by mutableStateOf<Int?>(null)
        private set

    /**
     * Begin editing a user message — Codex style.
     *
     * Returns the message text for the composer and stashes [editIndex]; the
     * conversation transcript is **not** modified yet. The actual revert
     * happens in [sendInternal] when the user resends. Call [cancelEdit] to
     * abort (e.g. when the composer is cleared without sending).
     *
     * Returns null if the index is invalid, not a user message, or busy.
     */
    fun editFrom(index: Int): String? {
        if (busy) return null
        if (index !in items.indices) return null
        val userMsg = items[index] as? ChatItem.User ?: return null
        editIndex = index
        return userMsg.text
    }

    /** Abort a pending edit (composer cleared without sending). */
    fun cancelEdit() {
        editIndex = null
    }

    /** Start a clean conversation and continue from a handoff resume prompt. */
    fun resumeFromPrompt(prompt: String) {
        val clean = prompt.trim()
        if (clean.isBlank() || busy) return
        val snapshot = parseResumePrompt(clean)
        newConversation()
        sendInternal(
            text = clean,
            goalOverride = snapshot.goal.ifBlank { null },
            conversationTitleOverride = resumePromptTitle(clean),
            goalNoteOverride = "从交接摘要恢复${snapshot.status.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}",
        )
    }

    /** Continue this conversation from a handoff prompt without overwriting the current goal by accident. */
    fun continueFromPrompt(prompt: String) {
        val clean = prompt.trim()
        if (clean.isBlank() || busy) return
        val snapshot = parseResumePrompt(clean)
        sendInternal(
            text = clean,
            goalOverride = resumeGoalOverrideForCurrentThread(clean, goal.text),
            conversationTitleOverride = resumePromptTitle(clean),
            goalNoteOverride = "从交接摘要继续${snapshot.status.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}",
        )
    }

    fun setPersistentGoal(text: String, phase: GoalPhase = GoalPhase.READY, note: String = "目标已更新"): Boolean {
        val clean = goalText(text)
        if (clean.isBlank()) return false
        startGoal(clean, phase, note)
        return true
    }

    fun pauseGoal(note: String = "已暂停") {
        updateGoalPhase(GoalPhase.PAUSED, note)
    }

    fun resumeGoal(note: String = "已恢复,等待继续推进") {
        updateGoalPhase(GoalPhase.READY, note)
    }

    fun clearGoal() {
        goal = ConversationGoal()
        persistGoal()
    }

    private fun beginExecutionTurn(userText: String, attachments: List<Attachment>) {
        val turnId = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val jump = JumpPoint(
            id = "jump-$turnId",
            turnId = turnId,
            title = userText.lineSequence().firstOrNull()?.take(60).orEmpty(),
            subtitle = if (attachments.isEmpty()) "" else "${attachments.size} 个附件",
            createdAt = now,
        )
        executionSessionState = executionSessionState.copy(
            conversationId = conversationId,
            project = project,
            turns = executionSessionState.turns + TurnState(
                id = turnId,
                userText = userText,
                userAttachments = attachments,
                status = TurnStatus.RUNNING,
                createdAt = now,
            ),
            activeTurnId = turnId,
            latestTurnId = turnId,
            isWorking = true,
            goal = GoalState(goal),
            draft = ComposerDraftState(editingIndex = editIndex),
            focusTarget = FocusTarget.Timeline(turnId),
            sidePanelTab = SidePanelTabId.TIMELINE,
            jumpPoints = executionSessionState.jumpPoints + jump,
        )
    }

    private fun updateActiveTurn(block: (TurnState) -> TurnState) {
        val turnId = executionSessionState.activeTurnId ?: return
        executionSessionState = executionSessionState.copy(
            turns = executionSessionState.turns.map { if (it.id == turnId) block(it) else it },
        )
    }

    private fun appendArtifacts(artifacts: List<ArtifactState>) {
        if (artifacts.isEmpty()) return
        executionSessionState = executionSessionState.copy(
            artifacts = (executionSessionState.artifacts + artifacts).distinctBy { it.id },
        )
    }

    private fun buildToolArtifacts(callId: String, name: String, args: String, imageUrls: List<String>): List<ArtifactState> {
        val artifacts = mutableListOf<ArtifactState>()
        val editedPath = com.andmx.agent.ToolArgs.editedPath(name, args)
        if (editedPath.isNotBlank()) {
            artifacts += ArtifactState(
                id = "$callId-diff",
                kind = ArtifactKind.DIFF,
                title = editedPath.substringAfterLast('/'),
                subtitle = editedPath,
                path = editedPath,
                toolCallId = callId,
            )
        }
        val filePath = com.andmx.agent.ToolArgs.filePath(name, args)
        if (editedPath.isBlank() && filePath.isNotBlank()) {
            artifacts += ArtifactState(
                id = "$callId-file",
                kind = ArtifactKind.FILE,
                title = filePath.substringAfterLast('/'),
                subtitle = filePath,
                path = filePath,
                toolCallId = callId,
            )
        }
        val webUrl = com.andmx.agent.ToolArgs.webUrl(name, args)
        if (webUrl.isNotBlank()) {
            artifacts += ArtifactState(
                id = "$callId-web",
                kind = ArtifactKind.WEB,
                title = webUrl,
                url = webUrl,
                toolCallId = callId,
            )
        }
        imageUrls.forEachIndexed { index, imageUrl ->
            artifacts += ArtifactState(
                id = "$callId-image-$index",
                kind = ArtifactKind.IMAGE,
                title = "$name 图像 ${index + 1}",
                imageUrl = imageUrl,
                toolCallId = callId,
            )
        }
        return artifacts
    }

    private fun updateGoalState() {
        executionSessionState = executionSessionState.copy(
            conversationId = conversationId,
            project = project,
            goal = GoalState(goal),
        )
    }

    private fun syncGoalSnapshots() {
        goalToolState.goal = goal
        updateGoalState()
    }

    private fun decodeImageUrls(raw: String): List<String> =
        raw.takeIf { it.isNotBlank() }
            ?.let { runCatching { imageJson.decodeFromString<List<String>>(it) }.getOrDefault(emptyList()) }
            ?: emptyList()

    private fun terminalBindingTitle(command: String): String =
        command.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { "run_shell" }.take(42)

    private fun shellExitCode(output: String): Int? =
        Regex("""\[exit=(-?\d+)]\s*$""").find(output.trim())?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun updateTerminalBindings(mutator: (MutableList<TerminalBindingState>) -> Unit) {
        val updated = executionSessionState.terminalBindings.toMutableList()
        mutator(updated)
        executionSessionState = executionSessionState.copy(
            terminalBindings = updated.sortedWith(
                compareByDescending<TerminalBindingState> { it.status == ToolExecutionStatus.RUNNING }
                    .thenByDescending { it.updatedAt },
            ),
        )
    }

    private fun upsertToolTerminalBinding(
        callId: String,
        args: String,
        status: ToolExecutionStatus,
        output: String? = null,
        error: Boolean = false,
        exitCode: Int? = null,
    ) {
        val command = ToolArgs.value(args, "command")
        val now = System.currentTimeMillis()
        val sessionId = toolTerminalSessionKey(callId)
        updateTerminalBindings { sessions ->
            val index = sessions.indexOfFirst { it.id == sessionId }
            val current = sessions.getOrNull(index)
            val next = TerminalBindingState(
                id = sessionId,
                callId = callId,
                title = terminalBindingTitle(command),
                command = command,
                output = output ?: current?.output.orEmpty(),
                status = status,
                exitCode = exitCode ?: current?.exitCode,
                error = error || current?.error == true,
                createdAt = current?.createdAt ?: now,
                updatedAt = now,
            )
            if (index >= 0) sessions[index] = next else sessions += next
        }
    }

    private fun updateShellBinding(event: ShellTool.ShellEvent) {
        when (event) {
            is ShellTool.ShellEvent.Started -> {
                val now = System.currentTimeMillis()
                updateTerminalBindings { sessions ->
                    val sessionId = toolTerminalSessionKey(event.callId)
                    val index = sessions.indexOfFirst { it.id == sessionId }
                    val current = sessions.getOrNull(index)
                    val next = TerminalBindingState(
                        id = sessionId,
                        callId = event.callId,
                        title = terminalBindingTitle(event.command),
                        command = event.command,
                        output = current?.output.orEmpty(),
                        status = ToolExecutionStatus.RUNNING,
                        exitCode = null,
                        error = false,
                        createdAt = current?.createdAt ?: now,
                        updatedAt = now,
                    )
                    if (index >= 0) sessions[index] = next else sessions += next
                }
            }
            is ShellTool.ShellEvent.Delta -> {
                val now = System.currentTimeMillis()
                updateTerminalBindings { sessions ->
                    val sessionId = toolTerminalSessionKey(event.callId)
                    val index = sessions.indexOfFirst { it.id == sessionId }
                    val current = sessions.getOrNull(index) ?: TerminalBindingState(
                        id = sessionId,
                        callId = event.callId,
                        title = "run_shell",
                    )
                    val next = current.copy(
                        output = current.output + event.chunk,
                        status = ToolExecutionStatus.RUNNING,
                        updatedAt = now,
                    )
                    if (index >= 0) sessions[index] = next else sessions += next
                }
            }
            is ShellTool.ShellEvent.Finished -> {
                val now = System.currentTimeMillis()
                updateTerminalBindings { sessions ->
                    val sessionId = toolTerminalSessionKey(event.callId)
                    val index = sessions.indexOfFirst { it.id == sessionId }
                    val current = sessions.getOrNull(index) ?: TerminalBindingState(
                        id = sessionId,
                        callId = event.callId,
                        title = terminalBindingTitle(event.command),
                        command = event.command,
                    )
                    val next = current.copy(
                        status = if (event.isError) ToolExecutionStatus.FAILED else ToolExecutionStatus.SUCCEEDED,
                        exitCode = event.exitCode,
                        error = event.isError,
                        updatedAt = now,
                    )
                    if (index >= 0) sessions[index] = next else sessions += next
                }
            }
            is ShellTool.ShellEvent.Failed -> {
                val now = System.currentTimeMillis()
                updateTerminalBindings { sessions ->
                    val sessionId = toolTerminalSessionKey(event.callId)
                    val index = sessions.indexOfFirst { it.id == sessionId }
                    val current = sessions.getOrNull(index) ?: TerminalBindingState(
                        id = sessionId,
                        callId = event.callId,
                        title = terminalBindingTitle(event.command),
                        command = event.command,
                    )
                    val next = current.copy(
                        output = (current.output + event.message).trim(),
                        status = ToolExecutionStatus.FAILED,
                        error = true,
                        updatedAt = now,
                    )
                    if (index >= 0) sessions[index] = next else sessions += next
                }
            }
        }
    }

    private fun mergeAssistantSegment(committed: String, segment: String): String {
        if (segment.isBlank()) return committed
        if (committed.isBlank()) return segment
        if (committed == segment) return committed
        return "$committed\n\n$segment"
    }

    private fun rebuildExecutionSessionState() {
        val turns = mutableListOf<TurnState>()
        val artifacts = mutableListOf<ArtifactState>()
        val terminalBindings = mutableListOf<TerminalBindingState>()
        val jumps = mutableListOf<JumpPoint>()
        var currentTurn: TurnState? = null
        var lastFocusTarget: FocusTarget = FocusTarget.None
        var lastSidePanelTab: SidePanelTabId? = null
        var pendingApprovals = 0

        fun flushCurrentTurn() {
            val turn = currentTurn ?: return
            val finalized = when {
                turn.status == TurnStatus.WAITING_APPROVAL -> turn
                turn.failureMessage.isNotBlank() -> turn.copy(status = TurnStatus.FAILED)
                turn.tools.any { it.status == ToolExecutionStatus.RUNNING || it.status == ToolExecutionStatus.WAITING_APPROVAL } ->
                    turn.copy(status = TurnStatus.RUNNING)
                turn.assistantText.isBlank() && turn.tools.isEmpty() -> turn.copy(status = TurnStatus.RUNNING)
                else -> turn.copy(status = TurnStatus.SUCCEEDED, assistantDone = true)
            }
            turns += finalized
            currentTurn = null
        }

        fun ensureTurn(createdAt: Long): TurnState {
            currentTurn?.let { return it }
            val turnId = "turn-${turns.size}-${createdAt}"
            return TurnState(
                id = turnId,
                userText = "",
                status = TurnStatus.IDLE,
                createdAt = createdAt,
            ).also { currentTurn = it }
        }

        items.forEach { item ->
            when (item) {
                is ChatItem.User -> {
                    flushCurrentTurn()
                    val createdAt = item.sentAt.takeIf { it > 0L } ?: System.currentTimeMillis()
                    val turnId = "turn-${turns.size}-${item.key}"
                    currentTurn = TurnState(
                        id = turnId,
                        userText = item.text,
                        status = TurnStatus.IDLE,
                        createdAt = createdAt,
                    )
                    jumps += JumpPoint(
                        id = "jump-$turnId",
                        turnId = turnId,
                        title = item.text.lineSequence().firstOrNull()?.take(60).orEmpty(),
                        createdAt = createdAt,
                    )
                    lastFocusTarget = FocusTarget.Timeline(turnId)
                    lastSidePanelTab = SidePanelTabId.TIMELINE
                }
                is ChatItem.Assistant -> {
                    val turn = ensureTurn(item.sentAt.takeIf { it > 0L } ?: System.currentTimeMillis())
                    currentTurn = turn.copy(
                        assistantText = mergeAssistantSegment(turn.assistantText, item.text),
                        assistantDone = item.done || item.completedAt > 0L,
                        completedAt = item.completedAt,
                    )
                }
                is ChatItem.ToolUse -> {
                    val turn = ensureTurn(System.currentTimeMillis())
                    val builtArtifacts = buildToolArtifacts(item.callId, item.name, item.args, item.imageUrls)
                    val (focusTarget, sidePanelTab) = toolTarget(
                        item.name,
                        item.args,
                        item.imageUrls,
                        toolCallId = item.callId,
                    )
                    artifacts += builtArtifacts
                    currentTurn = turn.copy(
                        tools = turn.tools + ToolExecutionState(
                            callId = item.callId,
                            name = item.name,
                            args = item.args,
                            output = item.output.orEmpty(),
                            error = item.error,
                            artifacts = builtArtifacts,
                            focusTarget = focusTarget,
                            sidePanelTab = sidePanelTab,
                            status = when {
                                item.running -> ToolExecutionStatus.RUNNING
                                item.error -> ToolExecutionStatus.FAILED
                                else -> ToolExecutionStatus.SUCCEEDED
                            },
                        ),
                        artifacts = (turn.artifacts + builtArtifacts).distinctBy { it.id },
                        focusTarget = if (focusTarget != FocusTarget.None) focusTarget else turn.focusTarget,
                        sidePanelTab = sidePanelTab ?: turn.sidePanelTab,
                        status = if (item.running) TurnStatus.RUNNING else turn.status,
                    )
                    if (focusTarget != FocusTarget.None) lastFocusTarget = focusTarget
                    if (sidePanelTab != null) lastSidePanelTab = sidePanelTab
                    if (item.name == "run_shell") {
                        val command = ToolArgs.value(item.args, "command")
                        terminalBindings += TerminalBindingState(
                            id = toolTerminalSessionKey(item.callId),
                            callId = item.callId,
                            title = terminalBindingTitle(command),
                            command = command,
                            output = item.output.orEmpty(),
                            status = when {
                                item.running -> ToolExecutionStatus.RUNNING
                                item.error -> ToolExecutionStatus.FAILED
                                else -> ToolExecutionStatus.SUCCEEDED
                            },
                            exitCode = shellExitCode(item.output.orEmpty()),
                            error = item.error,
                        )
                    }
                }
                is ChatItem.Approval -> {
                    val turn = ensureTurn(System.currentTimeMillis())
                    val waiting = !item.resolved
                    if (waiting) pendingApprovals += 1
                    currentTurn = turn.copy(
                        status = if (waiting) TurnStatus.WAITING_APPROVAL else turn.status,
                    )
                }
            }
        }
        flushCurrentTurn()

        val activeTurnId = turns.lastOrNull()?.takeIf {
            it.status == TurnStatus.RUNNING || it.status == TurnStatus.WAITING_APPROVAL
        }?.id

        executionSessionState = ExecutionSessionState(
            conversationId = conversationId,
            project = project,
            turns = turns,
            activeTurnId = activeTurnId,
            latestTurnId = turns.lastOrNull()?.id,
            isWorking = activeTurnId != null,
            backgroundTasks = emptyList(),
            terminalBindings = terminalBindings.sortedWith(
                compareByDescending<TerminalBindingState> { it.status == ToolExecutionStatus.RUNNING }
                    .thenByDescending { it.updatedAt },
            ),
            artifacts = artifacts.distinctBy { it.id },
            pendingApprovals = pendingApprovals,
            goal = GoalState(goal),
            draft = executionSessionState.draft.copy(editingIndex = editIndex),
            focusTarget = lastFocusTarget,
            sidePanelTab = lastSidePanelTab,
            jumpPoints = jumps,
        )
    }

    private fun finishExecution(status: TurnStatus, keepWorking: Boolean = false) {
        val completedAt = System.currentTimeMillis()
        updateActiveTurn {
            it.copy(
                status = status,
                completedAt = completedAt,
                assistantDone = it.assistantDone || status != TurnStatus.RUNNING,
            )
        }
        executionSessionState = executionSessionState.copy(
            conversationId = conversationId,
            project = project,
            isWorking = keepWorking || executionSessionState.backgroundTasks.any {
                it.status == BackgroundTaskStatus.RUNNING || it.status == BackgroundTaskStatus.WAITING
            },
            activeTurnId = if (keepWorking) executionSessionState.activeTurnId else null,
            goal = GoalState(goal),
        )
    }

    private fun updateBackgroundTask(event: com.andmx.agent.multi.SubAgentOrchestrator.SubAgentEvent) {
        fun mutate(block: (BackgroundTaskState?) -> BackgroundTaskState?) {
            val current = executionSessionState.backgroundTasks.associateBy { it.id }.toMutableMap()
            val existing = current[event.agentId()]
            val next = block(existing)
            if (next == null) current.remove(event.agentId()) else current[event.agentId()] = next
            executionSessionState = executionSessionState.copy(
                conversationId = conversationId,
                project = project,
                backgroundTasks = current.values.sortedByDescending { it.updatedAt },
                focusTarget = next?.focusTarget ?: executionSessionState.focusTarget,
                sidePanelTab = if (next != null) SidePanelTabId.BACKGROUND else executionSessionState.sidePanelTab,
                isWorking = (executionSessionState.activeTurnId != null) || current.values.any {
                    it.status == BackgroundTaskStatus.RUNNING || it.status == BackgroundTaskStatus.WAITING
                },
            )
        }
        when (event) {
            is com.andmx.agent.multi.SubAgentOrchestrator.SubAgentEvent.Started -> mutate {
                BackgroundTaskState(
                    id = event.agentId,
                    kind = BackgroundTaskKind.SUBAGENT,
                    title = event.task.ifBlank { "子智能体" }.take(60),
                    status = BackgroundTaskStatus.RUNNING,
                    summary = event.task,
                    focusTarget = FocusTarget.BackgroundTask(event.agentId),
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )
            }
            is com.andmx.agent.multi.SubAgentOrchestrator.SubAgentEvent.Delta -> mutate { current ->
                (current ?: BackgroundTaskState(
                    id = event.agentId,
                    kind = BackgroundTaskKind.SUBAGENT,
                    title = "子智能体",
                    status = BackgroundTaskStatus.RUNNING,
                    focusTarget = FocusTarget.BackgroundTask(event.agentId),
                )).copy(
                    status = BackgroundTaskStatus.RUNNING,
                    detail = event.text,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            is com.andmx.agent.multi.SubAgentOrchestrator.SubAgentEvent.Completed -> mutate { current ->
                (current ?: BackgroundTaskState(
                    id = event.agentId,
                    kind = BackgroundTaskKind.SUBAGENT,
                    title = "子智能体",
                    status = BackgroundTaskStatus.COMPLETED,
                    focusTarget = FocusTarget.BackgroundTask(event.agentId),
                )).copy(
                    status = BackgroundTaskStatus.COMPLETED,
                    result = event.result,
                    detail = event.result.take(240),
                    updatedAt = System.currentTimeMillis(),
                )
            }
            is com.andmx.agent.multi.SubAgentOrchestrator.SubAgentEvent.Failed -> mutate { current ->
                (current ?: BackgroundTaskState(
                    id = event.agentId,
                    kind = BackgroundTaskKind.SUBAGENT,
                    title = "子智能体",
                    status = BackgroundTaskStatus.FAILED,
                    focusTarget = FocusTarget.BackgroundTask(event.agentId),
                )).copy(
                    status = BackgroundTaskStatus.FAILED,
                    detail = event.error,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            is com.andmx.agent.multi.SubAgentOrchestrator.SubAgentEvent.Resumed -> mutate { current ->
                (current ?: BackgroundTaskState(
                    id = event.agentId,
                    kind = BackgroundTaskKind.SUBAGENT,
                    title = "子智能体",
                    status = BackgroundTaskStatus.RUNNING,
                    focusTarget = FocusTarget.BackgroundTask(event.agentId),
                )).copy(
                    status = BackgroundTaskStatus.RUNNING,
                    detail = event.input,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            is com.andmx.agent.multi.SubAgentOrchestrator.SubAgentEvent.Suspended -> mutate { current ->
                (current ?: BackgroundTaskState(
                    id = event.agentId,
                    kind = BackgroundTaskKind.SUBAGENT,
                    title = "子智能体",
                    status = BackgroundTaskStatus.WAITING,
                    focusTarget = FocusTarget.BackgroundTask(event.agentId),
                )).copy(
                    status = BackgroundTaskStatus.WAITING,
                    detail = event.reason,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            is com.andmx.agent.multi.SubAgentOrchestrator.SubAgentEvent.Closed -> mutate { current ->
                (current ?: BackgroundTaskState(
                    id = event.agentId,
                    kind = BackgroundTaskKind.SUBAGENT,
                    title = "子智能体",
                    status = BackgroundTaskStatus.CLOSED,
                    focusTarget = FocusTarget.BackgroundTask(event.agentId),
                )).copy(
                    status = BackgroundTaskStatus.CLOSED,
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }
    }

    private fun com.andmx.agent.multi.SubAgentOrchestrator.SubAgentEvent.agentId(): String = when (this) {
        is com.andmx.agent.multi.SubAgentOrchestrator.SubAgentEvent.Started -> agentId
        is com.andmx.agent.multi.SubAgentOrchestrator.SubAgentEvent.Delta -> agentId
        is com.andmx.agent.multi.SubAgentOrchestrator.SubAgentEvent.Completed -> agentId
        is com.andmx.agent.multi.SubAgentOrchestrator.SubAgentEvent.Failed -> agentId
        is com.andmx.agent.multi.SubAgentOrchestrator.SubAgentEvent.Suspended -> agentId
        is com.andmx.agent.multi.SubAgentOrchestrator.SubAgentEvent.Resumed -> agentId
        is com.andmx.agent.multi.SubAgentOrchestrator.SubAgentEvent.Closed -> agentId
    }

    private fun handle(ev: AgentEvent) {
        when (ev) {
            is AgentEvent.AssistantDelta -> {
                val idx = streamingIndex
                if (idx == null) {
                    items += ChatItem.Assistant(seq++, ev.text, sentAt = System.currentTimeMillis())
                    streamingIndex = items.lastIndex
                } else {
                    val cur = items[idx] as ChatItem.Assistant
                    items[idx] = cur.copy(text = cur.text + ev.text)
                }
                updateActiveTurn {
                    it.copy(
                        assistantStreamingText = it.assistantStreamingText + ev.text,
                        status = TurnStatus.RUNNING,
                    )
                }
            }
            is AgentEvent.Assistant -> {
                val now = System.currentTimeMillis()
                val idx = streamingIndex
                if (idx != null) {
                    items[idx] = (items[idx] as ChatItem.Assistant).copy(text = ev.text, done = true, completedAt = now)
                } else {
                    items += ChatItem.Assistant(seq++, ev.text, done = true, sentAt = now, completedAt = now)
                }
                streamingIndex = null
                updateActiveTurn {
                    it.copy(
                        assistantText = mergeAssistantSegment(it.assistantText, ev.text),
                        assistantStreamingText = "",
                        assistantDone = true,
                        status = TurnStatus.RUNNING,
                    )
                }
                conversationId?.let { id -> scope.launch { repo.addMessage(id, "assistant", ev.text) } }
                scope.launch {
                    rolloutWriter.writeResponseItem(
                        com.andmx.data.rollout.ResponseItem(
                            type = "message",
                            role = "assistant",
                            content = ev.text,
                        )
                    )
                }
            }
            is AgentEvent.ToolStarted -> {
                streamingIndex = null
                toolArgsByCallId[ev.id] = ev.arguments
                items += ChatItem.ToolUse(seq++, ev.id, ev.name, ev.arguments)
                if (ev.name == "run_shell") {
                    upsertToolTerminalBinding(ev.id, ev.arguments, ToolExecutionStatus.RUNNING)
                }
                val (focusTarget, sidePanelTab) = toolTarget(ev.name, ev.arguments, toolCallId = ev.id)
                updateActiveTurn {
                    it.copy(
                        tools = it.tools + ToolExecutionState(
                            callId = ev.id,
                            name = ev.name,
                            args = ev.arguments,
                            status = ToolExecutionStatus.RUNNING,
                            focusTarget = focusTarget,
                            sidePanelTab = sidePanelTab,
                        ),
                        focusTarget = if (focusTarget != FocusTarget.None) focusTarget else it.focusTarget,
                        sidePanelTab = sidePanelTab ?: it.sidePanelTab,
                        status = TurnStatus.RUNNING,
                    )
                }
                executionSessionState = executionSessionState.copy(
                    focusTarget = if (focusTarget != FocusTarget.None) focusTarget else executionSessionState.focusTarget,
                    sidePanelTab = sidePanelTab ?: executionSessionState.sidePanelTab,
                )
                scope.launch {
                    rolloutWriter.writeResponseItem(
                        com.andmx.data.rollout.ResponseItem(
                            type = "function_call",
                            toolCallId = ev.id,
                            toolName = ev.name,
                            toolArgs = ev.arguments,
                        )
                    )
                }
            }
            is AgentEvent.ToolFinished -> {
                // Collect for memory extraction
                turnToolOutputs.add(ev.name to ev.output)
                scope.launch {
                    rolloutWriter.writeResponseItem(
                        com.andmx.data.rollout.ResponseItem(
                            type = "function_call_output",
                            toolCallId = ev.id,
                            toolName = ev.name,
                            toolOutput = ev.output.take(4096),
                            isError = ev.isError,
                        )
                    )
                }
                val idx = items.indexOfLast { it is ChatItem.ToolUse && it.callId == ev.id }
                val args = if (idx >= 0) (items[idx] as ChatItem.ToolUse).args else toolArgsByCallId[ev.id].orEmpty()
                val artifacts = buildToolArtifacts(ev.id, ev.name, args, ev.imageUrls.orEmpty())
                if (idx >= 0) {
                    val card = items[idx] as ChatItem.ToolUse
                    items[idx] = card.copy(output = ev.output, running = false, error = ev.isError, imageUrls = ev.imageUrls.orEmpty())
                }
                if (ev.name == "run_shell") {
                    upsertToolTerminalBinding(
                        ev.id,
                        args,
                        if (ev.isError) ToolExecutionStatus.FAILED else ToolExecutionStatus.SUCCEEDED,
                        output = ev.output,
                        error = ev.isError,
                        exitCode = shellExitCode(ev.output),
                    )
                }
                appendArtifacts(artifacts)
                val (focusTarget, sidePanelTab) = toolTarget(ev.name, args, ev.imageUrls.orEmpty(), ev.id)
                updateActiveTurn {
                    it.copy(
                        tools = it.tools.map { tool ->
                            if (tool.callId != ev.id) tool else tool.copy(
                                output = ev.output,
                                error = ev.isError,
                                status = if (ev.isError) ToolExecutionStatus.FAILED else ToolExecutionStatus.SUCCEEDED,
                                artifacts = artifacts,
                                focusTarget = focusTarget,
                                sidePanelTab = sidePanelTab,
                                completedAt = System.currentTimeMillis(),
                            )
                        },
                        artifacts = (it.artifacts + artifacts).distinctBy { artifact -> artifact.id },
                        focusTarget = if (focusTarget != FocusTarget.None) focusTarget else it.focusTarget,
                        sidePanelTab = sidePanelTab ?: it.sidePanelTab,
                        status = TurnStatus.RUNNING,
                    )
                }
                executionSessionState = executionSessionState.copy(
                    focusTarget = if (focusTarget != FocusTarget.None) focusTarget else executionSessionState.focusTarget,
                    sidePanelTab = sidePanelTab ?: executionSessionState.sidePanelTab,
                )
                toolArgsByCallId.remove(ev.id)
                if (ev.isError && goal.phase != GoalPhase.PAUSED) {
                    updateGoalPhase(GoalPhase.RUNNING, "${ev.name} 返回错误,等待模型处理")
                }
                conversationId?.let { id ->
                    scope.launch {
                        repo.addMessage(
                            id,
                            "tool",
                            ev.output,
                            toolName = ev.name,
                            toolArgs = args,
                            toolError = ev.isError,
                        )
                    }
                }
            }
            is AgentEvent.Failed -> {
                streamingIndex = null
                updateGoalPhase(GoalPhase.FAILED, ev.message)
                updateActiveTurn {
                    it.copy(
                        assistantStreamingText = "",
                        failureMessage = ev.message,
                    )
                }
                finishExecution(TurnStatus.FAILED)
                val msg = "⚠️ ${ev.message}"
                val now = System.currentTimeMillis()
                items += ChatItem.Assistant(seq++, msg, sentAt = now, completedAt = now)
                conversationId?.let { id -> scope.launch { repo.addMessage(id, "assistant", msg) } }
                scope.launch {
                    rolloutWriter.writeEventMsg(
                        com.andmx.data.rollout.EventMsg(type = "task_failed", errorMessage = ev.message)
                    )
                }
            }
            AgentEvent.Done -> {
                streamingIndex = null
                // ── Token budget tracking ── accumulate this turn's token usage
                // into the goal, and transition to BUDGET_LIMITED if exhausted.
                if (goal.hasGoal && goal.tokenBudget > 0) {
                    val usage = tokenUsageTracker.lastTurnUsage.value
                    if (usage.totalTokens > 0) {
                        val newUsed = goal.tokensUsed + usage.totalTokens
                        val exhausted = newUsed >= goal.tokenBudget
                        goal = goal.copy(
                            tokensUsed = newUsed,
                            status = if (exhausted) GoalStatus.BUDGET_LIMITED else goal.status,
                            phase = if (exhausted) GoalStatus.BUDGET_LIMITED.toPhase() else goal.phase,
                            updatedAt = System.currentTimeMillis(),
                        )
                        goalToolState.goal = goal
                        persistGoal()
                    }
                }
                finishExecution(TurnStatus.SUCCEEDED)
                scope.launch {
                    rolloutWriter.writeEventMsg(
                        com.andmx.data.rollout.EventMsg(type = "task_completed")
                    )
                }
                // ── Memory extraction (Phase 1) ──
                val turnTools = turnToolOutputs.toList()
                turnToolOutputs.clear()
                val lastAssistant = items.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant
                val lastUser = items.lastOrNull { it is ChatItem.User } as? ChatItem.User
                if (lastAssistant != null && lastUser != null && turnTools.isNotEmpty()) {
                    scope.launch {
                        runCatching {
                            memorySystem.extractFromTurn(
                                userMessage = lastUser.text,
                                assistantMessage = lastAssistant.text,
                                toolOutputs = turnTools,
                                sessionId = conversationId?.toString() ?: "unknown",
                            )
                        }.onFailure {
                            // Silent failure — memory extraction is best-effort
                        }
                    }
                }
            }
        }
    }

    fun saveSettings(s: ProviderSettings) {
        settings = s
        scope.launch { store.update(s) }
        // Initialize sub-agents once a provider + model are ready
        if (isProviderReady) initSubAgents()
    }

    // ── Provider CRUD (drives the settings UI) ──

    /** The currently-selected provider definition, or null. */
    val currentProvider: com.andmx.llm.provider.ProviderDefinition? get() = primaryProvider

    /** Upsert a provider and optionally mark it primary. */
    fun saveProvider(def: com.andmx.llm.provider.ProviderDefinition, makePrimary: Boolean) {
        scope.launch {
            providerStore.upsert(def)
            if (makePrimary) {
                providerStore.setPrimary(def.id)
                settings = settings.copy(activeProviderId = def.id)
                store.update(settings)
            }
        }
    }

    /** Add a fresh blank custom provider (user fills in details). */
    fun addBlankProvider() {
        scope.launch {
            val newDef = com.andmx.llm.provider.ProviderDefinition(
                id = java.util.UUID.randomUUID().toString(),
                name = "",
                kind = com.andmx.llm.provider.ProviderKind.OPENAI,
                baseUrl = "",
                source = com.andmx.llm.provider.ProviderDefinition.SOURCE_CUSTOM,
                enabled = true,
            )
            providerStore.upsert(newDef)
        }
    }

    /** Delete a provider by id. */
    fun deleteProvider(id: String) {
        scope.launch { providerStore.delete(id) }
    }

    /** Set the active provider. Clears any stale model selection. */
    fun selectProvider(id: String) {
        scope.launch {
            providerStore.setPrimary(id)
            // Clear the model selection so the user is prompted to fetch/pick
            // afresh for this provider (models are provider-specific).
            settings = settings.copy(activeProviderId = id, model = "")
            store.update(settings)
        }
    }

    /** Model-list fetch state surfaced to the settings UI. */
    var fetchingModels by mutableStateOf(false)
        private set
    var fetchModelsError by mutableStateOf<String?>(null)
        private set
    /** Last successfully-fetched model ids for the provider being edited. */
    var fetchedModels by mutableStateOf<List<String>>(emptyList())
        private set

    /** Connection-test state surfaced to the settings UI. */
    var testingConnection by mutableStateOf(false)
        private set
    var connectionResult by mutableStateOf<String?>(null)
        private set
    var connectionOk by mutableStateOf<Boolean?>(null)
        private set

    /**
     * Fetch the model list for [def] from its `GET {base}/models` endpoint,
     * dispatching to the right [com.andmx.llm.wire.WireAdapter] for the
     * provider's protocol.
     *
     * Takes the full definition (not just an id) so it uses the *form's current*
     * baseUrl/apiKey/kind — which may not have been persisted yet when the user
     * opens the model picker before saving. Persists the fetched ids into the
     * provider's `models` map and exposes them via [fetchedModels] /
     * [fetchModelsError].
     */
    fun fetchModels(def: com.andmx.llm.provider.ProviderDefinition) {
        scope.launch {
            when {
                def.id.isBlank() -> {
                    fetchModelsError = "供应商不存在"
                    fetchedModels = emptyList()
                }
                def.baseUrl.isBlank() -> {
                    fetchModelsError = "请先填写 Base URL"
                    fetchedModels = emptyList()
                }
                else -> {
                    fetchingModels = true
                    fetchModelsError = null
                    try {
                        val adapter = com.andmx.llm.wire.AdapterFactory.forKind(def.kind)
                        val ids = adapter.listModels(def)
                        if (ids.isEmpty()) {
                            fetchModelsError = "未能获取模型列表（请检查 URL/Key 是否正确，或该端点是否支持 /models）"
                            fetchedModels = emptyList()
                        } else {
                            // Persist into the provider's models map so the list sticks.
                            val updated = def.copy(models = ids.associateWith { com.andmx.llm.provider.ModelDefinition() })
                            providerStore.upsert(updated)
                            fetchedModels = ids
                        }
                    } catch (t: Throwable) {
                        fetchModelsError = t.message ?: t::class.simpleName ?: "未知错误"
                        fetchedModels = emptyList()
                    } finally {
                        fetchingModels = false
                    }
                }
            }
        }
    }

    /**
     * Test the full chat path by sending a minimal one-shot request through a
     * fresh [com.andmx.llm.LlmClient] bound to [def] and [modelId]. Verifies
     * that the URL + key + selected model all work end-to-end — not just that
     * the endpoint is reachable. Sets [connectionOk]/[connectionResult].
     */
    fun testConnection(def: com.andmx.llm.provider.ProviderDefinition, modelId: String) {
        scope.launch {
            testingConnection = true
            connectionResult = null
            connectionOk = null
            try {
                when {
                    def.baseUrl.isBlank() -> {
                        connectionOk = false
                        connectionResult = "请先填写 Base URL"
                    }
                    modelId.isBlank() -> {
                        connectionOk = false
                        connectionResult = "请先选择模型"
                    }
                    else -> {
                        val client = com.andmx.llm.LlmClient(def)
                        val request = com.andmx.llm.ChatRequest(
                            model = modelId,
                            messages = listOf(com.andmx.llm.ApiMessage(role = "user", content = "ping")),
                        )
                        val result = client.chat(request)
                        result.onSuccess {
                            connectionOk = true
                            connectionResult = "连接成功：模型 $modelId 可正常对话"
                        }.onFailure { t ->
                            connectionOk = false
                            connectionResult = t.message ?: t::class.simpleName ?: "连接失败"
                        }
                    }
                }
            } catch (t: Throwable) {
                connectionOk = false
                connectionResult = t.message ?: t::class.simpleName ?: "未知错误"
            } finally {
                testingConnection = false
            }
        }
    }

    /** Set the selected model within the current provider. */
    fun selectModel(modelId: String) {
        settings = settings.copy(model = modelId)
        scope.launch { store.update(settings) }
    }

    /**
     * One-shot provider+model switch for the quick switcher in the composer.
     * Sets the provider primary and the model in a single step — unlike
     * [selectProvider], it does *not* clear the model selection.
     */
    fun switchModel(providerId: String, modelId: String) {
        scope.launch {
            providerStore.setPrimary(providerId)
            settings = settings.copy(activeProviderId = providerId, model = modelId)
            store.update(settings)
        }
    }

    /** Append a model id to a provider's `models` map and persist it. */
    fun addModel(providerId: String, modelId: String) {
        val id = modelId.trim()
        if (id.isBlank()) return
        scope.launch {
            val def = providers.firstOrNull { it.id == providerId } ?: return@launch
            if (def.models.containsKey(id)) return@launch
            providerStore.upsert(def.copy(models = def.models + (id to com.andmx.llm.provider.ModelDefinition())))
            // Auto-select the newly-added model when there's no current
            // selection, so the provider becomes immediately usable (sends
            // were silently disabled by an empty `settings.model`).
            if (settings.model.isBlank()) {
                settings = settings.copy(activeProviderId = providerId, model = id)
                providerStore.setPrimary(providerId)
                store.update(settings)
            }
        }
    }

    /** Remove a model id from a provider's `models` map and persist it. */
    fun removeModel(providerId: String, modelId: String) {
        scope.launch {
            val def = providers.firstOrNull { it.id == providerId } ?: return@launch
            providerStore.upsert(def.copy(models = def.models - modelId))
            // If we removed the currently-selected model, clear the selection.
            if (providerId == settings.activeProviderId && settings.model == modelId) {
                settings = settings.copy(model = "")
                store.update(settings)
            }
        }
    }

    fun delete(id: Long) {
        scope.launch {
            repo.delete(id)
            if (activeId == id) {
                conversationId = null
                activeId = null
                items.clear()
                executionSessionState = ExecutionSessionState(project = project)
                streamingIndex = null
                toolArgsByCallId.clear()
                goal = ConversationGoal()
                syncGoalSnapshots()
                engine.seed(emptyList())
            }
        }
    }

    fun rename(id: Long, title: String) {
        val t = title.trim().ifBlank { return }
        scope.launch { repo.rename(id, t) }
    }

    val approvalMode: ApprovalMode get() = ApprovalMode.from(settings.approvalMode)

    /** For the plugins page: built-in + MCP tools. */
    fun toolList(): List<Pair<String, String>> = engine.listTools()

    fun toolCapabilities(): List<ToolCapability> = builtInTools.toCapabilities()

    fun taskPlanSnapshot(): TaskPlanSnapshot {
        val toolEvents = items.filterIsInstance<ChatItem.ToolUse>()
        val pendingApprovals = items.filterIsInstance<ChatItem.Approval>().count { !it.resolved }
        return inferTaskPlan(
            goalText = goal.text,
            goalPhaseName = goal.phase.name,
            goalPhaseLabel = goal.phase.label,
            goalNote = goal.note,
            hasMessages = items.isNotEmpty(),
            toolEvents = toolEvents.size,
            runningTools = toolEvents.count { it.running },
            failedTools = toolEvents.count { it.error },
            changedFiles = com.andmx.workspace.ChangeTracker.changes.value.size,
            pendingApprovals = pendingApprovals,
        )
    }

    internal fun sessionChecklistSummary(): com.andmx.ui.workbench.SessionChecklistSummary =
        buildSessionChecklist(
            snapshot = inspectorSnapshot(),
            plan = taskPlanSnapshot(),
            verifications = verificationEntries(items, limit = 12),
            recentActivity = runLogEntries(items).size,
        )

    internal fun nextActionDecision(): com.andmx.ui.workbench.NextActionDecision {
        val verifications = verificationEntries(items, limit = 12)
        val snapshot = inspectorSnapshot()
        val checklist = buildSessionChecklist(
            snapshot = snapshot,
            plan = taskPlanSnapshot(),
            verifications = verifications,
            recentActivity = runLogEntries(items).size,
        )
        return buildNextActionDecision(
            snapshot = snapshot,
            checklist = checklist,
            verifications = verifications,
        )
    }

    internal fun evidenceLedger(): com.andmx.ui.workbench.EvidenceLedger =
        buildEvidenceLedger(
            chatItems = items.toList(),
            changes = com.andmx.workspace.ChangeTracker.changes.value,
        )

    internal fun uiReferenceLedger(): com.andmx.ui.workbench.UiReferenceLedger =
        buildUiReferenceLedger(items.toList())

    internal fun uiReplicaBlueprint(): com.andmx.ui.workbench.UiReplicaBlueprint =
        buildUiReplicaBlueprint(
            references = uiReferenceLedger(),
            snapshot = inspectorSnapshot(),
            evidence = evidenceLedger(),
        )

    internal fun codexSurfaceMap(): com.andmx.ui.workbench.CodexSurfaceMap =
        buildCodexSurfaceMap(
            references = uiReferenceLedger(),
            snapshot = inspectorSnapshot(),
            blueprint = uiReplicaBlueprint(),
        )

    internal fun visualAcceptanceSummary(): com.andmx.ui.workbench.VisualAcceptanceSummary =
        buildVisualAcceptanceSummary(
            references = uiReferenceLedger(),
            blueprint = uiReplicaBlueprint(),
            surfaceMap = codexSurfaceMap(),
            snapshot = inspectorSnapshot(),
            evidence = evidenceLedger(),
            verifications = verificationEntries(items, limit = 12),
        )

    internal fun codexDesignSystemAudit(): com.andmx.ui.workbench.CodexDesignSystemAudit =
        buildCodexDesignSystemAudit(
            references = uiReferenceLedger(),
            blueprint = uiReplicaBlueprint(),
            surfaceMap = codexSurfaceMap(),
            visualAcceptance = visualAcceptanceSummary(),
            snapshot = inspectorSnapshot(),
            evidence = evidenceLedger(),
        )

    internal fun screenshotExtractionSummary(): com.andmx.ui.workbench.ScreenshotExtractionSummary =
        buildScreenshotExtractionSummary(
            references = uiReferenceLedger(),
            blueprint = uiReplicaBlueprint(),
            surfaceMap = codexSurfaceMap(),
            visualAcceptance = visualAcceptanceSummary(),
            designSystem = codexDesignSystemAudit(),
            evidence = evidenceLedger(),
            snapshot = inspectorSnapshot(),
        )

    internal fun uiReferenceBoard(): com.andmx.ui.workbench.UiReferenceBoard =
        buildUiReferenceBoard(
            references = uiReferenceLedger(),
            blueprint = uiReplicaBlueprint(),
            screenshotExtraction = screenshotExtractionSummary(),
            visualAcceptance = visualAcceptanceSummary(),
            designSystem = codexDesignSystemAudit(),
            evidence = evidenceLedger(),
            snapshot = inspectorSnapshot(),
        )

    internal fun screenshotImplementationTrace(): com.andmx.ui.workbench.ScreenshotImplementationTrace =
        buildScreenshotImplementationTrace(
            board = uiReferenceBoard(),
            surfaceMap = codexSurfaceMap(),
            changes = changeSummaryItems(com.andmx.workspace.ChangeTracker.changes.value),
            verifications = verificationEntries(items, limit = 12),
            evidence = evidenceLedger(),
        )

    internal fun codexInteractionFlow(): com.andmx.ui.workbench.CodexInteractionFlow {
        val snapshot = inspectorSnapshot()
        val plan = taskPlanSnapshot()
        val verifications = verificationEntries(items, limit = 12)
        val checklist = buildSessionChecklist(
            snapshot = snapshot,
            plan = plan,
            verifications = verifications,
            recentActivity = runLogEntries(items).size,
        )
        val next = buildNextActionDecision(snapshot, checklist, verifications)
        return buildCodexInteractionFlow(
            snapshot = snapshot,
            plan = plan,
            verifications = verifications,
            evidence = evidenceLedger(),
            checklist = checklist,
            nextAction = next,
            screenshotExtraction = screenshotExtractionSummary(),
        )
    }

    internal fun codexSelfModel(): com.andmx.ui.workbench.CodexSelfModel {
        val snapshot = inspectorSnapshot()
        val verifications = verificationEntries(items, limit = 12)
        val checklist = buildSessionChecklist(
            snapshot = snapshot,
            plan = taskPlanSnapshot(),
            verifications = verifications,
            recentActivity = runLogEntries(items).size,
        )
        val runtime = runtimeEnvironmentSummary()
        val policy = toolPolicySummary()
        val evidence = evidenceLedger()
        val designSystem = codexDesignSystemAudit()
        val extraction = screenshotExtractionSummary()
        val parity = buildCodexParityAudit(
            snapshot = snapshot,
            runtime = runtime,
            policy = policy,
            evidence = evidence,
            checklist = checklist,
            designSystem = designSystem,
            screenshotExtraction = extraction,
            interactionFlow = codexInteractionFlow(),
            selfModel = null,
        )
        val architecture = buildAgentArchitectureBlueprint(
            snapshot = snapshot,
            runtime = runtime,
            evidence = evidence,
            checklist = checklist,
            parity = parity,
        )
        val instructionSummary = buildInstructionStackSummary(
            apiConfigured = apiConfigured,
            mcpConfigured = settings.mcpServers.isNotBlank(),
            customInstructions = settings.customInstructions,
            builtInTools = toolCapabilities().size,
            mcpServers = mcpManager.connected.size,
        )
        val environmentContract = buildCodexEnvironmentContract(
            instructionSummary = instructionSummary,
            runtime = runtime,
            policy = policy,
            snapshot = snapshot,
            evidence = evidence,
        )
        return buildCodexSelfModel(
            snapshot = snapshot,
            architecture = architecture,
            surfaceMap = codexSurfaceMap(),
            designSystem = designSystem,
            screenshotExtraction = extraction,
            interactionFlow = codexInteractionFlow(),
            policy = policy,
            evidence = evidence,
            checklist = checklist,
            runtime = runtime,
            instructionSummary = instructionSummary,
            environmentContract = environmentContract,
            toolCapabilityMap = toolCapabilityMap(),
        )
    }

    internal fun codexSelfImprovementPlan(): com.andmx.ui.workbench.CodexSelfImprovementPlan {
        val snapshot = inspectorSnapshot()
        val verifications = verificationEntries(items, limit = 12)
        val checklist = buildSessionChecklist(
            snapshot = snapshot,
            plan = taskPlanSnapshot(),
            verifications = verifications,
            recentActivity = runLogEntries(items).size,
        )
        val next = buildNextActionDecision(snapshot, checklist, verifications)
        return buildCodexSelfImprovementPlan(
            snapshot = snapshot,
            selfModel = codexSelfModel(),
            interactionFlow = codexInteractionFlow(),
            toolCapabilityMap = toolCapabilityMap(),
            visualAcceptance = visualAcceptanceSummary(),
            evidence = evidenceLedger(),
            checklist = checklist,
            nextAction = next,
        )
    }

    internal fun toolPolicySummary(): com.andmx.ui.workbench.ToolPolicySummary =
        buildToolPolicySummary(
            mode = approvalMode,
            tools = toolCapabilities(),
        )

    internal fun codexParityAudit(): com.andmx.ui.workbench.CodexParityAudit {
        val snapshot = inspectorSnapshot()
        val checklist = buildSessionChecklist(
            snapshot = snapshot,
            plan = taskPlanSnapshot(),
            verifications = verificationEntries(items, limit = 12),
            recentActivity = runLogEntries(items).size,
        )
        return buildCodexParityAudit(
            snapshot = snapshot,
            runtime = runtimeEnvironmentSummary(),
            policy = toolPolicySummary(),
            evidence = evidenceLedger(),
            checklist = checklist,
            designSystem = codexDesignSystemAudit(),
            screenshotExtraction = screenshotExtractionSummary(),
            interactionFlow = codexInteractionFlow(),
            selfModel = codexSelfModel(),
        )
    }

    internal fun deliveryReport(): com.andmx.ui.workbench.DeliveryReport {
        val snapshot = inspectorSnapshot()
        val verifications = verificationEntries(items, limit = 12)
        val checklist = buildSessionChecklist(
            snapshot = snapshot,
            plan = taskPlanSnapshot(),
            verifications = verifications,
            recentActivity = runLogEntries(items).size,
        )
        val next = buildNextActionDecision(snapshot, checklist, verifications)
        return buildDeliveryReport(
            snapshot = snapshot,
            changes = changeSummaryItems(com.andmx.workspace.ChangeTracker.changes.value),
            verifications = verifications,
            evidence = evidenceLedger(),
            checklist = checklist,
            nextAction = next,
            parity = codexParityAudit(),
            blueprint = uiReplicaBlueprint(),
            visualAcceptance = visualAcceptanceSummary(),
            referenceBoard = uiReferenceBoard(),
            screenshotTrace = screenshotImplementationTrace(),
            designSystem = codexDesignSystemAudit(),
            screenshotExtraction = screenshotExtractionSummary(),
            interactionFlow = codexInteractionFlow(),
            selfModel = codexSelfModel(),
            methodologySummary = codexMethodologySummary(),
            environmentContractSummary = codexEnvironmentContractSummary(environmentContract()),
            toolCapabilitySummary = toolCapabilityMap().summaryLines(),
        )
    }

    private fun codexMethodologySummary(): List<String> = listOf(
        "观察: 项目、截图、工具输出和 UI 状态进入证据链",
        "定向: 目标、计划、下一步和验收项进入 `/plan`、`/next`、`/checklist`",
        "执行: 文件、终端、Diff、Browser、MCP 和授权策略按风险推进",
        "审计: 变更、来源、授权、验证和截图追踪进入 `/evidence`、`/trace`、`/report`",
        "收束: 通过 `/verify`、`/report` 或 `/handoff` 保持可交付与可恢复",
    )

    internal fun environmentContract(): com.andmx.ui.workbench.CodexEnvironmentContract =
        buildCodexEnvironmentContract(
            instructionSummary = buildInstructionStackSummary(
                apiConfigured = apiConfigured,
                mcpConfigured = settings.mcpServers.isNotBlank(),
                customInstructions = settings.customInstructions,
                builtInTools = toolCapabilities().size,
                mcpServers = mcpManager.connected.size,
            ),
            runtime = runtimeEnvironmentSummary(),
            policy = toolPolicySummary(),
            snapshot = inspectorSnapshot(),
            evidence = evidenceLedger(),
        )

    internal fun toolCapabilityMap(): com.andmx.ui.workbench.CodexToolCapabilityMap =
        buildCodexToolCapabilityMap(
            tools = toolCapabilities(),
            mcpServerCount = mcpManager.connected.size,
        )

    private fun codexEnvironmentContractSummary(
        contract: com.andmx.ui.workbench.CodexEnvironmentContract,
    ): List<String> = listOf(
        "状态: ${contract.title}",
        "契约层: 已具备 ${contract.readyCount} · 注意 ${contract.watchCount} · 缺口 ${contract.gapCount}",
        "入口: `${contract.primaryCommand}`",
    )

    internal fun agentArchitectureBlueprint(): com.andmx.ui.workbench.AgentArchitectureBlueprint {
        val snapshot = inspectorSnapshot()
        val checklist = buildSessionChecklist(
            snapshot = snapshot,
            plan = taskPlanSnapshot(),
            verifications = verificationEntries(items, limit = 12),
            recentActivity = runLogEntries(items).size,
        )
        val parity = buildCodexParityAudit(
            snapshot = snapshot,
            runtime = runtimeEnvironmentSummary(),
            policy = toolPolicySummary(),
            evidence = evidenceLedger(),
            checklist = checklist,
            designSystem = codexDesignSystemAudit(),
            screenshotExtraction = screenshotExtractionSummary(),
            interactionFlow = codexInteractionFlow(),
            selfModel = codexSelfModel(),
        )
        return buildAgentArchitectureBlueprint(
            snapshot = snapshot,
            runtime = runtimeEnvironmentSummary(),
            evidence = evidenceLedger(),
            checklist = checklist,
            parity = parity,
        )
    }

    private fun inspectorSnapshot(): com.andmx.ui.workbench.AgentInspectorSnapshot =
        buildAgentInspectorSnapshot(
            project = project,
            model = settings.model,
            baseUrl = endpointLabel,
            apiConfigured = apiConfigured,
            approvalModeLabel = approvalMode.label,
            goalText = goal.text,
            goalPhaseLabel = goal.phase.label,
            goalNote = goal.note,
            busy = busy,
            reasoningEffort = settings.reasoningEffort,
            persona = settings.persona,
            items = items.toList(),
            changedFiles = com.andmx.workspace.ChangeTracker.changes.value.size,
            builtInTools = toolCapabilities().size,
            totalTools = toolList().size,
            mcpServers = mcpServers().size,
        )

    /** For the plugins page: connected MCP servers and their tool names. */
    fun mcpServers(): List<com.andmx.mcp.McpManager.Connected> = mcpManager.connected

    fun cycleApprovalMode() {
        val next = ApprovalMode.cycle(approvalMode)
        setApprovalMode(next)
    }

    fun setApprovalMode(mode: ApprovalMode) {
        saveSettings(settings.copy(approvalMode = mode.name.lowercase()))
    }

    /** Gate consulted by the agent before each tool call. */
    private suspend fun approveGate(tool: Tool, args: kotlinx.serialization.json.JsonObject): Boolean {
        // ── Guardian risk assessment (Codex parity) ──
        val guardianAssessment = when (tool.name) {
            "run_shell" -> {
                val cmd = args["command"]?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                } ?: ""
                com.andmx.agent.Guardian.assessShell(cmd, approvalMode)
            }
            "write_file", "edit_file", "apply_patch" -> {
                val path = args["path"]?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "/root"
                } ?: "/root"
                com.andmx.agent.Guardian.assessFileWrite(path, approvalMode)
            }
            else -> null
        }

        // Auto-deny CRITICAL risk
        if (guardianAssessment != null && guardianAssessment.decision == com.andmx.agent.Guardian.Decision.DENY) {
            val msg = "⛔ Guardian 已阻止 ${tool.name}: ${guardianAssessment.rationale}"
            items += ChatItem.Assistant(seq++, msg)
            conversationId?.let { id -> scope.launch { repo.addMessage(id, "assistant", msg) } }
            return false
        }

        // ── ExecPolicy check (Codex parity) ──
        if (tool.name == "run_shell") {
            val cmd = args["command"]?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            } ?: ""
            val policyDecision = execPolicy.check(cmd)
            if (policyDecision.isDenied) {
                val msg = "⛔ ExecPolicy 已阻止命令: ${policyDecision.reason ?: "匹配拒绝规则"}"
                items += ChatItem.Assistant(seq++, msg)
                conversationId?.let { id -> scope.launch { repo.addMessage(id, "assistant", msg) } }
                return false
            }
        }

        // ── Pre-tool-use hook (Codex parity) ──
        if (hookSystem.hasHooksFor(com.andmx.agent.hooks.HookSystem.HookEvent.PRE_TOOL_USE)) {
            val hookResult = hookSystem.runEvent(
                com.andmx.agent.hooks.HookSystem.HookEvent.PRE_TOOL_USE,
                com.andmx.agent.hooks.HookSystem.HookContext(
                    toolName = tool.name,
                    toolArgs = args.toString(),
                ),
            )
            if (hookResult.decision == com.andmx.agent.hooks.HookSystem.HookDecision.BLOCK) {
                val msg = "⛔ Hook 已阻止 ${tool.name}: ${hookResult.message ?: "被钩子拦截"}"
                items += ChatItem.Assistant(seq++, msg)
                conversationId?.let { id -> scope.launch { repo.addMessage(id, "assistant", msg) } }
                return false
            }
        }

        return when (ApprovalPolicy.decide(approvalMode, tool.risk)) {
            Decision.AUTO -> true
            Decision.DENY -> {
                val msg = "⛔ 只读模式下已阻止 ${tool.name}"
                items += ChatItem.Assistant(seq++, msg)
                conversationId?.let { id -> scope.launch { repo.addMessage(id, "assistant", msg) } }
                false
            }
            Decision.PROMPT -> {
                // Use Guardian assessment for richer approval UI if available
                val riskDesc = guardianAssessment?.rationale ?: tool.risk.description
                val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                pendingApproval = deferred
                items += ChatItem.Approval(
                    key = seq++,
                    toolName = tool.name,
                    summary = describe(args),
                    risk = tool.risk,
                    approvalModeLabel = approvalMode.label,
                    riskDescription = riskDesc,
                )
                pendingApprovalIndex = items.lastIndex
                updateGoalPhase(GoalPhase.WAITING_APPROVAL, "等待授权: ${tool.name}")
                executionSessionState = executionSessionState.copy(
                    focusTarget = executionSessionState.focusTarget,
                    sidePanelTab = executionSessionState.sidePanelTab ?: SidePanelTabId.TIMELINE,
                    pendingApprovals = items.count { it is ChatItem.Approval && !it.resolved },
                )
                updateActiveTurn {
                    it.copy(status = TurnStatus.WAITING_APPROVAL)
                }
                deferred.await()
            }
        }
    }

    fun resolveApproval(allow: Boolean) {
        val idx = pendingApprovalIndex
        var resolved: ChatItem.Approval? = null
        if (idx != null && idx in items.indices) {
            (items[idx] as? ChatItem.Approval)?.let {
                val updated = it.copy(resolved = true, allowed = allow)
                resolved = updated
                items[idx] = updated
            }
        }
        pendingApprovalIndex = null
        pendingApproval?.complete(allow)
        pendingApproval = null
        executionSessionState = executionSessionState.copy(
            pendingApprovals = items.count { it is ChatItem.Approval && !it.resolved },
        )

        // ── ExecPolicy amendment (Codex parity) ──
        // When user approves a shell command, auto-add a rule so similar
        // commands won't prompt again in this session.
        if (allow) {
            resolved?.let { approval ->
                if (approval.toolName == "run_shell") {
                    // Extract command from the approval summary
                    val cmd = approval.summary.lineSequence().firstOrNull()?.trim() ?: ""
                    if (cmd.isNotBlank()) {
                        execPolicy.proposeAmendment(cmd)
                    }
                }
            }
        }

        resolved?.let { approval ->
            conversationId?.let { id ->
                scope.launch {
                    repo.addMessage(
                        id,
                        "approval",
                        approval.summary,
                        toolName = approval.toolName,
                        toolError = !approval.allowed,
                        approvalRisk = approval.risk.name,
                        approvalModeLabel = approval.approvalModeLabel,
                        approvalRiskDescription = approval.riskDescription,
                    )
                }
            }
        }
        updateGoalPhase(
            if (allow) GoalPhase.RUNNING else GoalPhase.PAUSED,
            if (allow) "授权已允许,继续执行" else "授权被拒绝",
        )
        updateActiveTurn {
            it.copy(status = TurnStatus.RUNNING)
        }
    }

    private fun describe(args: kotlinx.serialization.json.JsonObject): String =
        args.entries.joinToString("  ") { (k, v) -> "$k=" + v.toString().trim('"').take(100) }
            .ifBlank { "(无参数)" }

    private fun toolRiskFor(name: String): ToolRisk =
        builtInTools.firstOrNull { it.name == name }?.risk ?: ToolRisk.EXECUTE

    private fun buildExportMarkdown(): String = buildString {
        appendLine("# AndMX 对话导出")
        appendLine("项目: $project · 模型: ${settings.model}")
        appendLine()
        for (it in items) when (it) {
            is ChatItem.User -> { appendLine("## 用户"); appendLine(it.text); appendLine() }
            is ChatItem.Assistant -> { appendLine("## 助手"); appendLine(it.text); appendLine() }
            is ChatItem.ToolUse -> {
                appendLine("### 工具 ${it.name}")
                appendLine("```"); appendLine(it.output.orEmpty()); appendLine("```"); appendLine()
            }
            is ChatItem.Approval -> {
                appendLine("### 授权 ${it.toolName}")
                appendLine(if (it.resolved) if (it.allowed) "已允许" else "已拒绝" else "等待授权")
                appendLine(it.summary)
                appendLine()
            }
        }
    }

    private fun startGoal(text: String, phase: GoalPhase, note: String) {
        val clean = goalText(text)
        if (clean.isBlank()) return
        val now = System.currentTimeMillis()
        val started = if (goal.text == clean && goal.startedAt > 0L) goal.startedAt else now
        val status = when (phase) {
            GoalPhase.RUNNING -> GoalStatus.ACTIVE
            GoalPhase.PAUSED -> GoalStatus.PAUSED
            GoalPhase.FAILED -> GoalStatus.BLOCKED
            GoalPhase.NEEDS_SETUP -> GoalStatus.BLOCKED
            else -> GoalStatus.ACTIVE
        }
        goal = ConversationGoal(text = clean, status = status, phase = phase, startedAt = started, updatedAt = now, note = note)
        persistGoal()
    }

    private fun updateGoalPhase(phase: GoalPhase, note: String = "") {
        if (!goal.hasGoal) return
        val status = when (phase) {
            GoalPhase.RUNNING -> GoalStatus.ACTIVE
            GoalPhase.PAUSED -> GoalStatus.PAUSED
            GoalPhase.FAILED -> GoalStatus.BLOCKED
            GoalPhase.NEEDS_SETUP -> GoalStatus.BLOCKED
            GoalPhase.READY -> GoalStatus.COMPLETE
            else -> goal.status
        }
        goal = goal.copy(phase = phase, status = status, note = note, updatedAt = System.currentTimeMillis())
        persistGoal()
    }

    private fun deriveGoalFromTranscript(): ConversationGoal {
        val firstUser = items.firstOrNull { it is ChatItem.User } as? ChatItem.User
        return firstUser?.let {
            val text = goalText(it.text)
            if (text.isBlank()) ConversationGoal()
            else ConversationGoal(
                text = text,
                phase = GoalPhase.READY,
                startedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                note = "从历史会话恢复",
            )
        } ?: ConversationGoal()
    }

    private fun persistGoal() {
        val snapshot = goal
        syncGoalSnapshots()
        val id = conversationId ?: return
        scope.launch {
            repo.updateGoal(
                conversationId = id,
                text = snapshot.text,
                phase = snapshot.phase.name,
                startedAt = snapshot.startedAt,
                updatedAt = snapshot.updatedAt,
                note = snapshot.note,
            )
        }
    }

    private fun goalText(text: String): String =
        text.trim().lineSequence().firstOrNull()?.trim().orEmpty()
            .removePrefix("继续推进:")
            .trim()
            .take(160)
}

private fun com.andmx.data.ConversationEntity.toGoal(): ConversationGoal {
    val text = goalText.trim()
    if (text.isBlank()) return ConversationGoal()
    // RUNNING / WAITING_APPROVAL are transient runtime states — they must not
    // survive a reload. Codex treats the sidebar as a passive index; a session
    // that was mid-run when the app closed simply resumes as "ready to continue".
    val rawPhase = runCatching { GoalPhase.valueOf(goalPhase) }.getOrDefault(GoalPhase.READY)
    val phase = when (rawPhase) {
        GoalPhase.RUNNING, GoalPhase.WAITING_APPROVAL -> GoalPhase.READY
        else -> rawPhase
    }
    // Map phase to Codex-style status.
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
        text = goalText,
        status = status,
        phase = phase,
        startedAt = goalStartedAt.takeIf { it > 0L } ?: now,
        updatedAt = goalUpdatedAt.takeIf { it > 0L } ?: now,
        note = goalNote,
    )
}

internal fun restoredApprovalRisk(value: String): ToolRisk? =
    ToolRisk.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }

val GoalPhase.label: String
    get() = when (this) {
        GoalPhase.EMPTY -> "未开始"
        GoalPhase.RUNNING -> "运行中"
        GoalPhase.PAUSED -> "已暂停"
        GoalPhase.READY -> "待继续"
        GoalPhase.WAITING_APPROVAL -> "等待授权"
        GoalPhase.NEEDS_SETUP -> "需要设置"
        GoalPhase.FAILED -> "失败"
    }
