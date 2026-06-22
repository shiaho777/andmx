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

/** UI-facing items rendered in the conversation. */
sealed interface ChatItem {
    val key: Long
    data class User(override val key: Long, val text: String) : ChatItem
    data class Assistant(override val key: Long, val text: String, val done: Boolean = false) : ChatItem
    data class ToolUse(
        override val key: Long,
        val callId: String,
        val name: String,
        val args: String,
        val output: String? = null,
        val running: Boolean = true,
        val error: Boolean = false,
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

enum class GoalPhase { EMPTY, RUNNING, PAUSED, READY, WAITING_APPROVAL, NEEDS_SETUP, FAILED }

data class ConversationGoal(
    val text: String = "",
    val phase: GoalPhase = GoalPhase.EMPTY,
    val startedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val note: String = "",
) {
    val hasGoal: Boolean get() = text.isNotBlank()
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

    private val builtInTools = listOf(
        ShellTool(context, cwdProvider = { project }),
        ReadFileTool(context),
        WriteFileTool(context),
        EditFileTool(context),
        ApplyPatchTool(context),
        GitTool(context, cwdProvider = { project }),
        BrowseTool(networkPolicy, onBrowseUrl = { url -> browseMirrorUrl = url }),   // ← with network policy + UI mirror
        ListDirTool(context),
        WebSearchTool(networkPolicy), // ← with network policy
        updatePlanTool,  // ← model can track its plan
        // Computer Use: pure-visual screen operation (screenshot→action→screenshot).
        // The tool self-checks grants and returns guidance if not authorized, so it's
        // safe to always expose — the model won't crash on it when ungranted.
        ComputerUseTool(context),
    )

    /** The currently-bound provider; drives LlmClient + TurnContext. */
    private var primaryProvider: com.andmx.llm.provider.ProviderDefinition? = null

    /** The engine is rebuilt when the primary provider changes so the client binds the right endpoint/key/protocol. */
    private var engine = AgentEngine(
        tools = builtInTools,
        client = com.andmx.llm.LlmClient(com.andmx.llm.provider.ProviderDefinition.BUILTIN_PROVIDERS.first()),
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
        streamingIndex = null
        toolArgsByCallId.clear()
        turnToolOutputs.clear()
        goal = ConversationGoal()
        updatePlanTool.clear()  // ← clear plan on new conversation
        engine.seed(emptyList())
        refreshSystemPrompt()
    }

    fun load(id: Long) {
        scope.launch {
            val msgs = repo.messages(id)
            conversationId = id
            activeId = id
            // Restore the project working directory this conversation belongs to,
            // so tools operate in the right cwd after switching conversations.
            repo.conversation(id)?.project?.takeIf { it.isNotBlank() }?.let { project = it }
            items.clear()
            streamingIndex = null
            toolArgsByCallId.clear()
            val api = mutableListOf<ApiMessage>()
            for (m in msgs) {
                when (m.role) {
                    "user" -> { items += ChatItem.User(seq++, m.content); api += ApiMessage("user", m.content) }
                    "assistant" -> { items += ChatItem.Assistant(seq++, m.content); api += ApiMessage("assistant", m.content) }
                    "tool" -> {
                        items += ChatItem.ToolUse(
                            seq++,
                            "saved-${m.id}",
                            m.toolName ?: "tool",
                            m.toolArgs,
                            m.content,
                            running = false,
                            error = m.toolError,
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
            if (restoredGoal == null && goal.hasGoal) persistGoal()
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
        val display = Attachments.displayText(text, attachments)
        when (val cmd = com.andmx.agent.SlashCommands.parse(text)) {
            is com.andmx.agent.SlashResult.NotCommand -> Unit
            else -> { handleSlash(text, cmd, attachments); return }
        }
        if (!isProviderReady) {
            items += ChatItem.User(seq++, display)
            val intake = Attachments.localIntakeText(attachments)
            val msg = listOf(
                "⚠️ 尚未配置 API 密钥。点击右上角设置填入 baseUrl / Key / 模型。",
                intake.takeIf { it.isNotBlank() },
            ).filterNotNull().joinToString("\n\n")
            items += ChatItem.Assistant(seq++, msg)
            startGoal(goalOverride ?: text, GoalPhase.NEEDS_SETUP, goalNoteOverride ?: "需要先配置模型")
            scope.launch {
                if (conversationId == null) {
                    conversationId = repo.createConversation(project, conversationTitleOverride ?: text.take(30).ifBlank { "需配置模型" })
                    activeId = conversationId
                    persistGoal()
                }
                conversationId?.let {
                    repo.addMessage(it, "user", display)
                    repo.addMessage(it, "assistant", msg)
                }
            }
            return
        }
        items += ChatItem.User(seq++, display)
        toolArgsByCallId.clear()
        startGoal(goalOverride ?: text, GoalPhase.RUNNING, goalNoteOverride ?: "正在执行")
        busy = true
        turnStopped = false
        turnJob = scope.launch {
            try {
                if (conversationId == null) {
                    conversationId = repo.createConversation(project, conversationTitleOverride ?: text.take(30).ifBlank { "附件对话" })
                    activeId = conversationId
                    persistGoal()
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
        streamingIndex = null
        busy = false
        updateGoalPhase(GoalPhase.PAUSED, "已由用户停止")
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
        val lastUser = items.lastOrNull { it is ChatItem.User } as? ChatItem.User
        lastUser?.let { startGoal(it.text, GoalPhase.RUNNING, "正在重新生成") }
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

    private fun handle(ev: AgentEvent) {
        when (ev) {
            is AgentEvent.AssistantDelta -> {
                val idx = streamingIndex
                if (idx == null) {
                    items += ChatItem.Assistant(seq++, ev.text)
                    streamingIndex = items.lastIndex
                } else {
                    val cur = items[idx] as ChatItem.Assistant
                    items[idx] = cur.copy(text = cur.text + ev.text)
                }
            }
            is AgentEvent.Assistant -> {
                val idx = streamingIndex
                if (idx != null) {
                    items[idx] = (items[idx] as ChatItem.Assistant).copy(text = ev.text, done = true)
                } else {
                    items += ChatItem.Assistant(seq++, ev.text, done = true)
                }
                streamingIndex = null
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
                if (idx >= 0) {
                    val card = items[idx] as ChatItem.ToolUse
                    items[idx] = card.copy(output = ev.output, running = false, error = ev.isError)
                }
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
                val msg = "⚠️ ${ev.message}"
                items += ChatItem.Assistant(seq++, msg)
                conversationId?.let { id -> scope.launch { repo.addMessage(id, "assistant", msg) } }
                scope.launch {
                    rolloutWriter.writeEventMsg(
                        com.andmx.data.rollout.EventMsg(type = "task_failed", errorMessage = ev.message)
                    )
                }
            }
            AgentEvent.Done -> {
                streamingIndex = null
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
                settings = settings.copy(activeProviderId = def.id, model = def.models.keys.firstOrNull() ?: settings.model)
                store.update(settings)
            }
        }
    }

    /** Add a new provider cloned from a built-in preset id, returns the new id. */
    fun addProviderFromPreset(presetId: String) {
        val preset = com.andmx.llm.provider.ProviderDefinition.builtin(presetId) ?: return
        scope.launch {
            val newDef = preset.copy(
                id = java.util.UUID.randomUUID().toString(),
                source = com.andmx.llm.provider.ProviderDefinition.SOURCE_CUSTOM,
                enabled = true,
            )
            providerStore.upsert(newDef)
        }
    }

    /** Add a fresh blank custom provider (user fills in details). */
    fun addBlankProvider() {
        scope.launch {
            val newDef = com.andmx.llm.provider.ProviderDefinition(
                id = java.util.UUID.randomUUID().toString(),
                name = "新供应商",
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

    /** Set the active provider and pick its first model. */
    fun selectProvider(id: String) {
        scope.launch {
            providerStore.setPrimary(id)
            val def = providers.firstOrNull { it.id == id }
            val model = def?.models?.keys?.firstOrNull().orEmpty()
            settings = settings.copy(activeProviderId = id, model = model)
            store.update(settings)
        }
    }

    /** Set the selected model within the current provider. */
    fun selectModel(modelId: String) {
        settings = settings.copy(model = modelId)
        scope.launch { store.update(settings) }
    }

    fun delete(id: Long) {
        scope.launch {
            repo.delete(id)
            if (activeId == id) {
                conversationId = null
                activeId = null
                items.clear()
                streamingIndex = null
                toolArgsByCallId.clear()
                goal = ConversationGoal()
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
        goal = ConversationGoal(clean, phase, startedAt = started, updatedAt = now, note = note)
        persistGoal()
    }

    private fun updateGoalPhase(phase: GoalPhase, note: String = "") {
        if (!goal.hasGoal) return
        goal = goal.copy(phase = phase, note = note, updatedAt = System.currentTimeMillis())
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
        val id = conversationId ?: return
        val snapshot = goal
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
    val phase = runCatching { GoalPhase.valueOf(goalPhase) }.getOrDefault(GoalPhase.READY)
    val now = System.currentTimeMillis()
    return ConversationGoal(
        text = text,
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
