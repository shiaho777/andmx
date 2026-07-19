package com.andmx.ui2.chat

import com.andmx.agent.SlashCommands
import com.andmx.agent.SlashResult
import com.andmx.agent.ApprovalMode

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andmx.agent.plugins.SkillInstaller
import com.andmx.agent.plugins.PluginSystem
import com.andmx.agent.plugins.BuiltinPluginSeeder
import com.andmx.data.ConversationRepository
import com.andmx.exec.files.GuestFs
import com.andmx.exec.proot.ProotRuntime
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.provider.ReasoningConfig
import com.andmx.settings.ProviderSettings
import com.andmx.settings.ProviderStore
import com.andmx.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val controller = ChatController(context)
    private val settingsStore = SettingsStore(context)
    private val providerStore = ProviderStore(context)
    private val repo = ConversationRepository(context)
    private val gitBaseline = com.andmx.workspace.GitBaseline(context)
    private val projectManager = com.andmx.workspace.ProjectManager(context)
    private val commitMessageGenerator = com.andmx.workspace.CommitMessageGenerator()
    private val remoteStore = com.andmx.workspace.RemoteWorkspaceStore(context)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _toolCalls = MutableStateFlow<List<ToolCall>>(emptyList())
    val toolCalls: StateFlow<List<ToolCall>> = _toolCalls.asStateFlow()

    private val _reasonings = MutableStateFlow<List<ReasoningItem>>(emptyList())
    val reasonings: StateFlow<List<ReasoningItem>> = _reasonings.asStateFlow()
    private var currentReasoningId: String? = null
    private var currentReasoningText = ""
    private var lastReasoningUiAt = 0L

    private val _approvals = MutableStateFlow<List<ApprovalItem>>(emptyList())
    val approvals: StateFlow<List<ApprovalItem>> = _approvals.asStateFlow()

    val subAgents: StateFlow<List<ChatController.SubAgentUi>> = controller.subAgents
    val mcpStatus: StateFlow<List<com.andmx.mcp.McpManager.Connected>> = controller.mcpStatus
    val tokenUsage: StateFlow<ChatController.TokenUsageUi> = controller.tokenUsage
    val goal: StateFlow<com.andmx.ui.conversation.ConversationGoal> = controller.goal
    val ambient = controller.ambient


    private val _contextTokens = MutableStateFlow(0)
    val contextTokens: StateFlow<Int> = _contextTokens.asStateFlow()
    private val _contextWindow = MutableStateFlow(128_000)
    val contextWindow: StateFlow<Int> = _contextWindow.asStateFlow()

    private val _subAgentItems = MutableStateFlow<List<SubAgentItem>>(emptyList())
    val subAgentItems: StateFlow<List<SubAgentItem>> = _subAgentItems.asStateFlow()


    val pendingApproval: StateFlow<ChatController.ApprovalRequest?> = controller.pendingApproval
    val planSteps: StateFlow<List<com.andmx.agent.UpdatePlanTool.PlanStep>> = controller.planSteps


    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 工作区名（项目目录名），跟随 hostPath 变化。 */
    val projectName: StateFlow<String> =
        projectManager.hostPath.map { projectManager.projectName }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), projectManager.projectName)
    val hostPath: StateFlow<String?> = projectManager.hostPath
    val workspaceKind: StateFlow<com.andmx.workspace.WorkspaceKind> = projectManager.workspaceKind
    val isRemoteWorkspace: Boolean get() = projectManager.isRemote
    val remoteProfiles: StateFlow<List<com.andmx.workspace.RemoteWorkspaceSpec>> = remoteStore.profiles
    val activeRemoteId: StateFlow<String?> = remoteStore.activeRemoteId

    /** 当前工作区的 git 信息（分支、变更等）；非 git 仓库时为 null。 */
    private val _gitInfo = MutableStateFlow<com.andmx.workspace.GitBaseline.GitInfo?>(null)
    val gitInfo: StateFlow<com.andmx.workspace.GitBaseline.GitInfo?> = _gitInfo.asStateFlow()

    private val _queue = MutableStateFlow<List<String>>(emptyList())
    val queue: StateFlow<List<String>> = _queue.asStateFlow()

    /** 输入区上下文 chips（@ / # 等）。 */
    private val _contextChips = MutableStateFlow<List<ContextChip>>(emptyList())
    val contextChips: StateFlow<List<ContextChip>> = _contextChips.asStateFlow()

    /** 供 # 联想的最近会话。 */
    val recentConversations: StateFlow<List<ConversationPick>> =
        repo.observeConversations()
            .map { list ->
                list.take(30).map { c ->
                    ConversationPick(
                        id = c.id,
                        title = c.title.ifBlank { "未命名会话" },
                        subtitle = c.project.takeIf { it.isNotBlank() }.orEmpty(),
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 供 $ 联想的已安装技能（读取 guest 文件系统）。 */
    private val _skills = MutableStateFlow<List<SkillInstaller.InstalledSkill>>(emptyList())
    val skills: StateFlow<List<SkillInstaller.InstalledSkill>> = _skills.asStateFlow()

    private val _pluginSlashSpecs = MutableStateFlow<List<SlashCommands.Spec>>(emptyList())
    val pluginSlashSpecs: StateFlow<List<SlashCommands.Spec>> = _pluginSlashSpecs.asStateFlow()
    private val skillInstaller = SkillInstaller(GuestFs(ProotRuntime(context)))

    /** 供应商列表 + 当前选中设置（Composer 配置链）。 */
    data class ComposerConfig(
        val settings: ProviderSettings = ProviderSettings(),
        val providers: List<ProviderDefinition> = emptyList(),
        val primary: ProviderDefinition? = null,
    ) {
        val modelLabel: String
            get() {
                val mid = settings.model
                if (mid.isBlank()) return ""
                val p = primary ?: providers.firstOrNull { it.id == settings.activeProviderId }
                val display = p?.models?.get(mid)?.displayName?.takeIf { it.isNotBlank() }
                return display ?: mid
            }

        val reasoning: ReasoningConfig?
            get() {
                val mid = settings.model
                val p = primary
                    ?: providers.firstOrNull { it.id == settings.activeProviderId }
                    ?: providers.firstOrNull { it.models.containsKey(mid) }
                return p?.models?.get(mid)?.reasoning
            }

        val execMode: ExecMode get() = ExecMode.from(settings.approvalMode)
    }

    val composerConfig: StateFlow<ComposerConfig> =
        combine(
            settingsStore.settings,
            providerStore.providers,
            providerStore.primary,
        ) { settings, providers, primary ->
            ComposerConfig(settings = settings, providers = providers, primary = primary)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ComposerConfig())

    /** 当前会话 id，UI 据此高亮侧边栏选中项。0 表示尚未落库。 */
    private val _currentConversationId = MutableStateFlow(0L)
    val currentConversationId: StateFlow<Long> = _currentConversationId.asStateFlow()

    private var lastStreamUiAt = 0L
    private var processSortCursor = 0L
    private var streamingMessageId = 0L
    private var contextUsageJob: Job? = null
    private var currentAssistantText = ""
    private var pendingEditMessageId: Long? = null
    private val _editingMessageId = MutableStateFlow<Long?>(null)
    val editingMessageId: StateFlow<Long?> = _editingMessageId.asStateFlow()
    private var turnJob: Job? = null

    /** 切换到指定会话：更新当前 id + 加载历史消息到 messages。 */
    fun switchToConversation(id: Long) {
        if (id == _currentConversationId.value) return
        turnJob?.cancel()
        turnJob = null
        controller.resolveApproval(false)
        _currentConversationId.value = id
        currentAssistantText = ""
        _isLoading.value = false
        pendingEditMessageId = null
        _editingMessageId.value = null
        _toolCalls.value = emptyList()
        _reasonings.value = emptyList()
        currentReasoningId = null
        currentReasoningText = ""
        _approvals.value = emptyList()
        _subAgentItems.value = emptyList()
        _error.value = null
        viewModelScope.launch {
            val history = runCatching { repo.messages(id) }.getOrDefault(emptyList())
            val msgs = mutableListOf<ChatMessage>()
            val tools = mutableListOf<ToolCall>()
            val subItems = mutableListOf<SubAgentItem>()
            for (msg in history) {
                when (msg.role) {
                    "user" -> msgs += ChatMessage(
                        id = msg.id,
                        role = "user",
                        content = msg.content,
                        sortKey = msg.createdAt,
                        createdAt = msg.createdAt,
                    )
                    "assistant" -> msgs += ChatMessage(
                        id = msg.id,
                        role = "assistant",
                        content = msg.content,
                        sortKey = msg.createdAt,
                        isProcess = false,
                        createdAt = msg.createdAt,
                        completedAt = msg.createdAt,
                    )
                    "tool" -> {
                        tools += ToolCall(
                            id = "hist-${msg.id}",
                            name = msg.toolName ?: "tool",
                            args = msg.toolArgs,
                            output = msg.content,
                            isRunning = false,
                            isError = msg.toolError,
                            sortKey = msg.createdAt,
                        )
                        if (msg.toolName == "spawn_agent" || msg.toolName == "multi_agent") {
                            val task = runCatching {
                                val el = kotlinx.serialization.json.Json.parseToJsonElement(msg.toolArgs)
                                (el as? kotlinx.serialization.json.JsonObject)
                                    ?.get("task")
                                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                            }.getOrNull().orEmpty().ifBlank { msg.toolName ?: "子代理" }
                            subItems += SubAgentItem(
                                id = "hist-sub-${msg.id}",
                                task = task,
                                state = if (msg.toolError) "FAILED" else "COMPLETED",
                                result = msg.content,
                                sortKey = msg.createdAt,
                            )
                        }
                    }
                }
            }
            val annotated = annotateProcessMessages(msgs, tools)
            _messages.value = annotated
            _toolCalls.value = tools
            _subAgentItems.value = subItems
            processSortCursor = (annotated.maxOfOrNull { it.sortKey } ?: 0L)
                .coerceAtLeast(tools.maxOfOrNull { it.sortKey } ?: 0L)
            runCatching { controller.focusConversation(id) }
            refreshContextUsage()
        }
    }

    /** 新建会话并切换过去。返回新会话 id。 */
    fun createConversation() {
        viewModelScope.launch {
            val project = projectManager.hostPath.value
                ?: projectManager.guestMountPath
            val id = repo.createConversation(project = project, title = "新任务")
            switchToConversation(id)
        }
    }

    private suspend fun ensureConversationReady(): Long {
        val current = _currentConversationId.value
        if (current > 0L) {
            val existing = runCatching { repo.conversation(current) }.getOrNull()
            if (existing != null) return current
        }
        val project = projectManager.hostPath.value
            ?: projectManager.guestMountPath
        val id = repo.createConversation(project = project, title = "新任务")
        _currentConversationId.value = id
        return id
    }

    init {
        viewModelScope.launch {
            controller.pendingApproval.collect { req ->
                if (req == null) return@collect
                val existing = _approvals.value
                if (existing.any { it.id == req.id }) return@collect
                _approvals.value = existing + ApprovalItem(
                    id = req.id,
                    toolName = req.toolName,
                    summary = req.summary,
                    modeLabel = req.modeLabel,
                    status = "pending",
                    sortKey = req.createdAt,
                )
            }
        }
        viewModelScope.launch {
            _currentConversationId
                .flatMapLatest { id ->
                    if (id <= 0L) kotlinx.coroutines.flow.emptyFlow()
                    else controller.sideEvents(id)
                }
                .collect { event -> handleSideEvent(event) }
        }

        viewModelScope.launch {
            // 确保旧 DataStore provider 已迁移到 Room，Composer 能列出模型
            runCatching {
                providerStore.ensureSeeded(settingsStore.legacyProvider())
            }
            // 拉取已安装技能，供 $ 联想；proot 未就绪则保持空列表
            runCatching {
                val fs = GuestFs(ProotRuntime(context))
                BuiltinPluginSeeder.ensureSeeded(context, fs)
                val system = PluginSystem(context, fs)
                val local = runCatching { skillInstaller.listInstalled() }.getOrDefault(emptyList())
                val pluginSkills = runCatching { system.listPluginSkills(includeDisabled = false) }
                    .getOrDefault(emptyList())
                    .map {
                        SkillInstaller.InstalledSkill(
                            name = it.name,
                            path = it.path,
                            hasSkillMd = true,
                            description = it.description,
                        )
                    }
                val merged = LinkedHashMap<String, SkillInstaller.InstalledSkill>()
                local.forEach { merged[it.name.lowercase()] = it }
                pluginSkills.forEach { merged.putIfAbsent(it.name.lowercase(), it) }
                _skills.value = merged.values.toList()
                val cmds = system.listPluginCommands()
                _pluginSlashSpecs.value = cmds.map { c ->
                    SlashCommands.Spec(
                        name = "/${c.name}",
                        desc = c.description.ifBlank { "插件命令 · ${c.pluginName}" },
                        keywords = listOf(c.pluginName, "plugin", "command"),
                    )
                }
            }.onFailure {
                _skills.value = runCatching { skillInstaller.listInstalled() }.getOrDefault(emptyList())
            }
            // ZCode 对齐：模型支持推理时，首次默认选「最高」
            applyDefaultReasoningIfNeeded()
        }
        // 工作区变化时刷新 git 信息（分支指示器）
        viewModelScope.launch {
            projectManager.hostPath.collect {
                _gitInfo.value = null
                refreshGitInfo()
            }
        }
    }

    /** 刷新当前工作区的 git 信息（分支、变更）。proot 失败时回退到主机 .git 探测。 */
    fun refreshGitInfo() {
        viewModelScope.launch {
            val execPath = workspaceExecPath()
            val host = projectManager.hostPath.value
            if (host == null && execPath == null) {
                _gitInfo.value = null
                return@launch
            }
            var info: com.andmx.workspace.GitBaseline.GitInfo? = null
            if (host != null && !projectManager.isRemote) {
                info = runCatching { gitBaseline.collectHostGitInfo(host) }
                    .getOrNull()
                    ?.takeIf { it.isRepo && it.branch.isNotBlank() }
            }
            if (execPath != null) {
                val guest = runCatching { gitBaseline.collectGitInfo(execPath) }
                    .getOrNull()
                    ?.takeIf { it.isRepo && it.branch.isNotBlank() }
                if (guest != null) {
                    _gitInfo.value = guest
                    return@launch
                }
            }
            _gitInfo.value = info
        }
    }

    /** 列出本地分支（供分支切换 UI）。 */
    suspend fun listBranches(): List<com.andmx.workspace.GitBaseline.BranchInfo> {
        val execPath = workspaceExecPath()
        if (execPath != null) {
            val fromGuest = runCatching { gitBaseline.listBranches(execPath) }.getOrDefault(emptyList())
            if (fromGuest.isNotEmpty()) return fromGuest
        }
        val host = projectManager.hostPath.value
        if (host != null && !projectManager.isRemote) {
            return runCatching { gitBaseline.listHostBranches(host) }.getOrDefault(emptyList())
        }
        return emptyList()
    }

    /** 切换或新建分支，完成后刷新 git 信息。 */
    fun checkoutBranch(name: String, create: Boolean, onDone: (Boolean, String) -> Unit) {
        switchBranch(name, create) { onDone(it.ok, it.message) }
    }

    fun switchBranch(
        name: String,
        create: Boolean,
        onDone: (com.andmx.workspace.GitBaseline.SwitchResult) -> Unit,
    ) {
        viewModelScope.launch {
            val path = workspaceExecPath()
            if (path == null) {
                onDone(
                    com.andmx.workspace.GitBaseline.SwitchResult(
                        ok = false,
                        message = "未选择工作区",
                        issue = com.andmx.workspace.GitBaseline.SwitchIssue.UNKNOWN,
                    )
                )
                return@launch
            }
            val result = runCatching { gitBaseline.switchBranch(path, name, create) }
                .getOrElse {
                    com.andmx.workspace.GitBaseline.SwitchResult(
                        ok = false,
                        message = it.message ?: "执行失败",
                        issue = com.andmx.workspace.GitBaseline.SwitchIssue.UNKNOWN,
                    )
                }
            if (result.ok) refreshGitInfo()
            else result.gitInfo?.let { _gitInfo.value = it }
            onDone(result)
        }
    }

    fun commitAndSwitchBranch(
        name: String,
        create: Boolean,
        message: String,
        onDone: (com.andmx.workspace.GitBaseline.SwitchResult) -> Unit,
    ) {
        viewModelScope.launch {
            val path = workspaceExecPath()
            if (path == null) {
                onDone(
                    com.andmx.workspace.GitBaseline.SwitchResult(
                        ok = false,
                        message = "未选择工作区",
                        issue = com.andmx.workspace.GitBaseline.SwitchIssue.UNKNOWN,
                    )
                )
                return@launch
            }
            val result = runCatching { gitBaseline.commitAndSwitch(path, name, create, message) }
                .getOrElse {
                    com.andmx.workspace.GitBaseline.SwitchResult(
                        ok = false,
                        message = it.message ?: "执行失败",
                        issue = com.andmx.workspace.GitBaseline.SwitchIssue.UNKNOWN,
                    )
                }
            if (result.ok) refreshGitInfo()
            else result.gitInfo?.let { _gitInfo.value = it }
            onDone(result)
        }
    }

    /** 选择工作区目录（侧边栏工作区选择器调用）。 */
    fun workspaceExecPath(): String? {
        if (projectManager.hostPath.value == null) return null
        return if (projectManager.isRemote) {
            projectManager.currentRemoteSpec()?.remotePath?.ifBlank { null }
                ?: projectManager.hostPath.value
        } else {
            projectManager.guestMountPath
        }
    }

    fun selectProject(path: String) {

        if (path.startsWith("ssh://")) {
            val spec = projectManager.currentRemoteSpec()?.takeIf { it.workspaceUri == path }
                ?: remoteStore.profiles.value.firstOrNull { it.workspaceUri == path }
            if (spec != null) {
                projectManager.selectRemoteProject(spec)
                remoteStore.setActive(spec.id)
                return
            }
        }
        remoteStore.clearActive()
        projectManager.selectProject(path)
    }

    /** 建议的工作区根目录（供选择器快捷入口）。 */
    fun suggestedRoots(): List<String> = projectManager.suggestedRoots()

    /** 空状态工作区菜单候选：最近 + 建议根目录。 */
    fun workspaceCandidates(): List<String> = projectManager.workspaceCandidates()

    suspend fun listChangedFiles(): List<com.andmx.workspace.GitBaseline.ChangedFile> {
        val path = workspaceExecPath() ?: return emptyList()
        return runCatching { gitBaseline.listChangedFiles(path) }.getOrDefault(emptyList())
    }

    fun generateCommitMessage(
        targetBranch: String? = null,
        onDone: (Result<String>) -> Unit,
    ) {
        viewModelScope.launch {
            val path = workspaceExecPath()
            if (path == null) {
                onDone(Result.failure(IllegalStateException("未选择工作区")))
                return@launch
            }
            val settings = settingsStore.settings.firstOrNull()
            val providers = providerStore.providers.firstOrNull().orEmpty()
            val provider = providers.firstOrNull { it.id == settings?.activeProviderId && it.enabled }
                ?: providerStore.primary.firstOrNull()
            if (provider == null || settings == null || !settings.hasSelection) {
                onDone(Result.failure(IllegalStateException("请先选择模型")))
                return@launch
            }
            val files = runCatching { gitBaseline.listChangedFiles(path) }.getOrDefault(emptyList())
            val stat = runCatching { gitBaseline.diffStat(path) }.getOrDefault("")
            val branch = _gitInfo.value?.branch.orEmpty()
            val result = commitMessageGenerator.generate(
                provider = provider,
                model = settings.model,
                branch = branch,
                files = files,
                diffStat = stat,
                targetBranch = targetBranch,
            )
            onDone(result)
        }
    }

    fun pushBranch(onDone: (com.andmx.workspace.GitBaseline.PushResult) -> Unit) {
        viewModelScope.launch {
            val path = workspaceExecPath()
            if (path == null) {
                onDone(
                    com.andmx.workspace.GitBaseline.PushResult(
                        ok = false,
                        message = "未选择工作区",
                    )
                )
                return@launch
            }
            val result = runCatching { gitBaseline.push(path) }
                .getOrElse {
                    com.andmx.workspace.GitBaseline.PushResult(false, it.message ?: "推送失败")
                }
            result.gitInfo?.let { _gitInfo.value = it.takeIf { g -> g.isRepo } }
            if (result.ok) refreshGitInfo()
            onDone(result)
        }
    }

    fun commitWorkspace(
        message: String,
        onDone: (com.andmx.workspace.GitBaseline.CommitResult) -> Unit,
    ) {
        viewModelScope.launch {
            val path = workspaceExecPath()
            if (path == null) {
                onDone(com.andmx.workspace.GitBaseline.CommitResult(false, "未选择工作区"))
                return@launch
            }
            val result = runCatching { gitBaseline.commitWorkspace(path, message) }
                .getOrElse { com.andmx.workspace.GitBaseline.CommitResult(false, it.message ?: "提交失败") }
            result.gitInfo?.let { _gitInfo.value = it.takeIf { g -> g.isRepo } }
            if (result.ok) refreshGitInfo()
            onDone(result)
        }
    }

    fun commitAndPush(
        message: String,
        onDone: (com.andmx.workspace.GitBaseline.CommitResult, com.andmx.workspace.GitBaseline.PushResult?) -> Unit,
    ) {
        viewModelScope.launch {
            val path = workspaceExecPath()
            if (path == null) {
                onDone(com.andmx.workspace.GitBaseline.CommitResult(false, "未选择工作区"), null)
                return@launch
            }
            val pair = runCatching { gitBaseline.commitAndPush(path, message) }
                .getOrElse {
                    com.andmx.workspace.GitBaseline.CommitResult(false, it.message ?: "提交失败") to null
                }
            pair.first.gitInfo?.let { _gitInfo.value = it.takeIf { g -> g.isRepo } }
            pair.second?.gitInfo?.let { _gitInfo.value = it.takeIf { g -> g.isRepo } }
            if (pair.first.ok) refreshGitInfo()
            onDone(pair.first, pair.second)
        }
    }

    fun remoteProfilesSnapshot(): List<com.andmx.workspace.RemoteWorkspaceSpec> = remoteStore.profiles.value

    fun saveRemoteProfile(spec: com.andmx.workspace.RemoteWorkspaceSpec) {
        remoteStore.upsert(spec)
    }

    fun deleteRemoteProfile(id: String) {
        remoteStore.delete(id)
    }

    fun connectRemote(
        spec: com.andmx.workspace.RemoteWorkspaceSpec,
        onDone: (com.andmx.workspace.RemoteConnectResult) -> Unit,
    ) {
        viewModelScope.launch {
            val result = remoteStore.connect(spec)
            onDone(result)
        }
    }

    fun listRemoteDir(
        spec: com.andmx.workspace.RemoteWorkspaceSpec,
        path: String,
        onDone: (List<com.andmx.exec.remote.RemoteDirEntry>) -> Unit,
    ) {
        viewModelScope.launch {
            val entries = runCatching { remoteStore.listRemoteDir(spec, path) }
                .getOrDefault(emptyList())
            onDone(entries)
        }
    }

    fun openRemoteWorkspace(
        spec: com.andmx.workspace.RemoteWorkspaceSpec,
        remotePath: String,
        onDone: (com.andmx.workspace.RemoteConnectResult) -> Unit,
    ) {
        viewModelScope.launch {
            val result = remoteStore.openRemoteWorkspace(projectManager, spec, remotePath)
            if (result.ok) refreshGitInfo()
            onDone(result)
        }
    }

    fun probeRemote(
        spec: com.andmx.workspace.RemoteWorkspaceSpec,
        onDone: (com.andmx.workspace.RemoteProbeResult) -> Unit,
    ) {
        viewModelScope.launch {
            val result = remoteStore.probe(spec)
            onDone(result)
        }
    }

    fun clearActiveRemote() {
        remoteStore.clearActive()
    }

    fun setActiveRemote(id: String?) {
        remoteStore.setActive(id)
    }

    fun currentRemoteSpec(): com.andmx.workspace.RemoteWorkspaceSpec? =
        projectManager.currentRemoteSpec() ?: remoteStore.activeRemote


    /**
     * 若用户从未设置过 reasoningEffort（仍是旧默认 "off"）且当前模型支持推理，
     * 自动切到该模型的最高可用档，对齐 ZCode 默认「最高」。
     */
    private suspend fun applyDefaultReasoningIfNeeded() {
        val settings = settingsStore.settings.firstOrNull() ?: return
        if (settings.reasoningEffort.isNotBlank() && settings.reasoningEffort != "off") return
        val cfg = composerConfig.firstOrNull() ?: return
        val reasoning = cfg.reasoning ?: return
        val target = com.andmx.ui2.chat.defaultEffortFor(reasoning)
        if (target != "off" && target != settings.reasoningEffort) {
            settingsStore.update(settings.copy(reasoningEffort = target))
        }
    }

    fun sendMessage(text: String, attachments: List<Attachment> = emptyList()) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && _contextChips.value.isEmpty() && attachments.isEmpty()) return
        val withContext = buildMessageWithContext(trimmed, attachments)
        if (attachments.isEmpty()) {
            val slashText = when {
                trimmed.startsWith("/") -> trimmed
                trimmed.isEmpty() &&
                    _contextChips.value.size == 1 &&
                    _contextChips.value.first().kind == ContextChipKind.COMMAND ->
                    withContext.trim()
                else -> null
            }
            if (slashText != null && handleSlash(slashText)) {
                _contextChips.value = emptyList()
                return
            }
        }
        val images = attachments.mapNotNull { resolveImageDataUrl(it) }
        if (_isLoading.value) {
            _queue.value = _queue.value + withContext
            return
        }
        _contextChips.value = emptyList()
        val editId = pendingEditMessageId
        pendingEditMessageId = null
        _editingMessageId.value = null
        runTurn(withContext, images, editMessageId = editId)
    }

    private fun resolveImageDataUrl(attachment: Attachment): String? {
        val uri = attachment.uri
        if (uri.startsWith("data:image")) return uri
        return runCatching {
            val parsed = android.net.Uri.parse(uri)
            val att = com.andmx.ui.conversation.Attachments.fromUri(context, parsed)
            att?.imageDataUrl
        }.getOrNull()
    }

    private fun handleSlash(text: String): Boolean {
        when (val cmd = SlashCommands.parse(text)) {
            SlashResult.NotCommand -> return false
            SlashResult.Clear -> {
                createConversation()
                return true
            }
            SlashResult.Stop -> {
                stop()
                return true
            }
            SlashResult.Regenerate -> {
                regenerate()
                return true
            }
            SlashResult.Compact -> {
                viewModelScope.launch {
                    val id = ensureConversationReady()
                    val msg = controller.compactConversation(id)
                    appendLocalAssistant(msg)
                    refreshContextUsage()
                }
                return true
            }
            SlashResult.Checkpoint -> {
                viewModelScope.launch {
                    val id = ensureConversationReady()
                    appendLocalAssistant(controller.checkpointConversation(id))
                    refreshContextUsage()
                }
                return true
            }
            SlashResult.Status -> {
                viewModelScope.launch {
                    val id = ensureConversationReady()
                    appendLocalAssistant(controller.statusText(id))
                    refreshContextUsage()
                }
                return true
            }
            SlashResult.Tools -> {
                viewModelScope.launch {
                    val mcp = controller.mcpStatus.value
                    val body = buildString {
                        appendLine("内置工具：Read/Write/Edit/Bash/Grep/Glob/WebFetch/WebSearch/Todo/Plan/AskUserQuestion/Skill/Agent …")
                        appendLine("移动开发：android_*（andmx-android-dev）；存储清理：storage_*（andmx-storage-cleanup）；HTML影片：html_video_*（andmx-html-video）；工程环境：forge_*（andmx-dev-forge）")
                        if (mcp.isNotEmpty()) {
                            appendLine("MCP：")
                            mcp.forEach { appendLine("- ${it.name} (${it.transport}) · ${it.tools.size} tools") }
                        } else {
                            appendLine("MCP：未连接")
                        }
                    }.trim()
                    appendLocalAssistant(body)
                }
                return true
            }
            SlashResult.Help -> {
                val body = SlashCommands.list.joinToString("\n") { "${it.name} — ${it.desc}" }
                appendLocalAssistant("可用命令：\n" + body)
                return true
            }
            is SlashResult.Mode -> {
                val mode = when (cmd.mode) {
                    ApprovalMode.FULL -> ExecMode.FULL
                    ApprovalMode.ASK -> ExecMode.CONFIRM
                    ApprovalMode.READ_ONLY -> ExecMode.PLAN
                }
                setExecMode(mode)
                appendLocalAssistant("已切换执行模式：${mode.label}")
                return true
            }
            SlashResult.OpenModel -> {
                ChatActionBus.openSettings()
                return true
            }
            is SlashResult.PluginSlash -> {
                val args = cmd.args
                val prompt = buildString {
                    append("执行插件斜杠命令 /")
                    append(cmd.name)
                    if (args.isNotBlank()) {
                        append(" ")
                        append(args)
                    }
                    append("。优先使用对应 Skill（Skill tool），并按技能工作流调用 android_* 工具。")
                }
                viewModelScope.launch {
                    sendMessage(prompt)
                }
                return true
            }
            is SlashResult.Unknown -> {
                val name = cmd.name.removePrefix("/")
                val hit = _pluginSlashSpecs.value.any { it.name.removePrefix("/") == name }
                if (hit) {
                    val args = text.substringAfter(' ', missingDelimiterValue = "").trim()
                    // re-enter as plugin slash
                    val prompt = buildString {
                        append("执行插件斜杠命令 /")
                        append(name)
                        if (args.isNotBlank()) {
                            append(" ")
                            append(args)
                        }
                        append("。优先使用对应 Skill（Skill tool），并按技能工作流调用 android_* 工具。")
                    }
                    viewModelScope.launch { sendMessage(prompt) }
                    return true
                }
                appendLocalAssistant("未知命令：${cmd.name}。输入 /help 查看可用命令。")
                return true
            }
            SlashResult.Handoff -> {
                viewModelScope.launch {
                    val id = ensureConversationReady()
                    appendLocalAssistant(controller.handoffText(id))
                }
                return true
            }
            SlashResult.Export -> {
                viewModelScope.launch {
                    val id = ensureConversationReady()
                    appendLocalAssistant(controller.exportConversationMarkdown(id))
                }
                return true
            }
            is SlashResult.Goal -> {
                viewModelScope.launch {
                    val id = ensureConversationReady()
                    when (cmd.action) {
                        com.andmx.agent.GoalAction.SHOW -> {
                            val g = controller.goal.value
                            appendLocalAssistant(
                                if (!g.hasGoal) "当前没有目标。用法：/goal <目标文本>"
                                else "目标：${g.text}\n状态：${g.status.label}\n预算：${g.tokensUsed}/${g.tokenBudget}",
                            )
                        }
                        com.andmx.agent.GoalAction.SET, com.andmx.agent.GoalAction.EDIT -> {
                            controller.setGoalText(id, cmd.text)
                            appendLocalAssistant("已设置目标：${cmd.text}")
                        }
                        com.andmx.agent.GoalAction.CLEAR -> {
                            controller.setGoalText(id, "")
                            appendLocalAssistant("已清除目标")
                        }
                        com.andmx.agent.GoalAction.PAUSE -> {
                            val g = controller.goal.value
                            if (!g.hasGoal) {
                                appendLocalAssistant("当前没有目标可暂停。")
                            } else {
                                controller.setGoalStatus(id, com.andmx.ui.conversation.GoalStatus.PAUSED, "由 /goal 暂停")
                                appendLocalAssistant("已暂停目标：${g.text}")
                            }
                        }
                        com.andmx.agent.GoalAction.RESUME -> {
                            val g = controller.goal.value
                            if (!g.hasGoal) {
                                appendLocalAssistant("当前没有目标可恢复。")
                            } else {
                                controller.setGoalStatus(id, com.andmx.ui.conversation.GoalStatus.ACTIVE, "由 /goal 恢复")
                                appendLocalAssistant("已恢复目标：${g.text}")
                            }
                        }
                    }
                }
                return true
            }
            else -> {
                appendLocalAssistant("未知命令。输入 /help 查看可用命令。")
                return true
            }
        }
    }

    private fun pruneSubAgentItems(list: List<SubAgentItem>): List<SubAgentItem> {
        if (list.size <= 24) return list
        val live = list.filter { it.state == "RUNNING" || it.state == "WAITING" }
        val done = list.filterNot { it.state == "RUNNING" || it.state == "WAITING" }.takeLast(16)
        return live + done
    }

    private fun appendLocalAssistant(text: String) {
        if (text.isBlank()) return
        val key = nextProcessSortKey()
        val now = System.currentTimeMillis()
        _messages.value = _messages.value + ChatMessage(
            id = key,
            role = "assistant",
            content = text,
            sortKey = key,
            isProcess = false,
            createdAt = now,
            completedAt = now,
        )
    }


    fun copyMessage(content: String) {
        copyTextToClipboard(content, toast = "已复制")
    }

    fun beginEditUserMessage(messageId: Long): String? {
        if (_isLoading.value) {
            _error.value = "请等待当前回复完成后再编辑"
            return null
        }
        if (pendingEditMessageId == messageId) {
            cancelEditUserMessage()
            return ""
        }
        val msg = _messages.value.firstOrNull { it.id == messageId && it.role == "user" }
            ?: return null
        pendingEditMessageId = messageId
        _editingMessageId.value = messageId
        return msg.content
    }

    fun cancelEditUserMessage() {
        pendingEditMessageId = null
        _editingMessageId.value = null
    }

    fun copyTurnLog(messageId: Long) {
        val log = buildTurnLog(messageId)
        if (log.isBlank()) {
            android.widget.Toast.makeText(context, "没有可复制的内容", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        copyTextToClipboard(log, toast = "已复制完整日志")
    }

    private fun copyTextToClipboard(text: String, toast: String) {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("andmx-log", text))
        android.widget.Toast.makeText(context, toast, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun buildTurnLog(messageId: Long): String {
        val messages = _messages.value
        val targetIdx = messages.indexOfFirst { it.id == messageId }
        if (targetIdx < 0) return ""
        val target = messages[targetIdx]
        val endKey = target.sortKey
        var startIdx = targetIdx
        while (startIdx > 0 && messages[startIdx].role != "user") {
            startIdx--
        }
        if (messages.getOrNull(startIdx)?.role != "user") {
            startIdx = (0..targetIdx).lastOrNull { messages[it].role == "user" } ?: 0
        }
        val startKey = messages.getOrNull(startIdx)?.sortKey ?: 0L

        val inRangeMessages = messages.filter { m ->
            m.sortKey in startKey..endKey && m.content.isNotBlank()
        }
        val inRangeReasonings = _reasonings.value
            .filter { it.sortKey in startKey..endKey && it.content.isNotBlank() }
        val inRangeTools = _toolCalls.value.filter { t ->
            val k = t.sortKey
            k in startKey..endKey || (k == 0L && t.id.isNotBlank() && t.sortKey <= endKey)
        }
        val inRangeApprovals = _approvals.value.filter { it.sortKey in startKey..endKey }
        val inRangeSubs = _subAgentItems.value.filter { it.sortKey in startKey..endKey }

        data class Line(val key: Long, val order: Int, val text: String)
        val lines = ArrayList<Line>()
        var order = 0
        fun add(key: Long, text: String) {
            lines += Line(key, order++, text)
        }

        inRangeMessages.forEach { m ->
            val title = when {
                m.role == "user" -> "用户"
                m.isProcess -> "过程"
                else -> "助手"
            }
            add(
                m.sortKey,
                buildString {
                    appendLine("## $title")
                    append(m.content.trimEnd())
                },
            )
        }
        inRangeReasonings.forEach { r ->
            add(
                r.sortKey,
                buildString {
                    appendLine("## 思考")
                    append(r.content.trimEnd())
                },
            )
        }
        inRangeTools.forEach { t ->
            val status = when {
                t.isRunning -> "运行中"
                t.isError -> "失败"
                else -> "完成"
            }
            add(
                t.sortKey.takeIf { it > 0L } ?: endKey,
                buildString {
                    appendLine("## 工具 · ${t.name} · $status")
                    if (t.args.isNotBlank()) {
                        appendLine("参数:")
                        appendLine(t.args.trimEnd())
                    }
                    val out = t.output?.trimEnd().orEmpty()
                    if (out.isNotBlank()) {
                        appendLine("输出:")
                        append(out)
                    } else if (!t.isRunning) {
                        append("(无输出)")
                    }
                },
            )
        }
        inRangeApprovals.forEach { a ->
            add(
                a.sortKey,
                buildString {
                    appendLine("## 审批 · ${a.toolName} · ${a.status}")
                    append(a.summary.trimEnd())
                },
            )
        }
        inRangeSubs.forEach { s ->
            add(
                s.sortKey,
                buildString {
                    appendLine("## 子代理 · ${s.state}")
                    appendLine("任务: ${s.task.trimEnd()}")
                    if (s.result.isNotBlank()) {
                        appendLine("结果:")
                        append(s.result.trimEnd())
                    }
                },
            )
        }

        if (lines.isEmpty()) return target.content.trim()
        return lines
            .sortedWith(compareBy<Line> { it.key }.thenBy { it.order })
            .joinToString("\n\n") { it.text }
            .trim() + "\n"
    }

    fun branchFromMessage(messageId: Long) {
        if (_isLoading.value) {
            _error.value = "请等待当前回复完成后再分支"
            return
        }
        viewModelScope.launch {
            val parentId = _currentConversationId.value
            if (parentId <= 0L) {
                _error.value = "当前没有可分支的会话"
                return@launch
            }
            val live = _messages.value
            val liveIdx = live.indexOfFirst { it.id == messageId }
            val history = runCatching { repo.messages(parentId) }.getOrDefault(emptyList())
            val slice: List<com.andmx.data.MessageEntity> = when {
                history.isNotEmpty() -> {
                    val byId = history.indexOfFirst { it.id == messageId }
                    val idx = if (byId >= 0) byId else {
                        val liveMsg = live.getOrNull(liveIdx)
                        if (liveMsg == null) -1
                        else history.indexOfLast {
                            it.role == liveMsg.role && it.content == liveMsg.content
                        }
                    }
                    if (idx < 0) emptyList() else history.take(idx + 1)
                }
                liveIdx >= 0 -> {
                    live.take(liveIdx + 1).map { m ->
                        com.andmx.data.MessageEntity(
                            id = 0,
                            conversationId = parentId,
                            role = m.role,
                            content = m.content,
                            createdAt = m.createdAt.takeIf { it > 0L } ?: m.sortKey,
                        )
                    }
                }
                else -> emptyList()
            }
            if (slice.isEmpty()) {
                _error.value = "无法定位分支点"
                return@launch
            }
            val parent = runCatching { repo.conversation(parentId) }.getOrNull()
            val project = parent?.project
                ?: projectManager.hostPath.value
                ?: projectManager.guestMountPath
            val titleSeed = slice.firstOrNull { it.role == "user" }?.content?.take(24).orEmpty()
            val title = "(分支) " + titleSeed.ifBlank { "会话" }
            val childId = repo.createConversation(project = project, title = title)
            for (m in slice) {
                repo.addMessage(
                    conversationId = childId,
                    role = m.role,
                    content = m.content,
                    toolName = m.toolName,
                    toolArgs = m.toolArgs,
                    toolError = m.toolError,
                    approvalRisk = m.approvalRisk,
                    approvalModeLabel = m.approvalModeLabel,
                    approvalRiskDescription = m.approvalRiskDescription,
                )
            }
            runCatching { repo.recordSpawnEdge(parentId, childId, status = "branched") }
            switchToConversation(childId)
            android.widget.Toast.makeText(context, "已创建分支会话", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun regenerate() {
        if (_isLoading.value) return
        val hasUser = _messages.value.any { it.role == "user" }
        if (!hasUser) {
            _error.value = "没有可重新生成的用户消息"
            return
        }
        val msgs = _messages.value.toMutableList()
        while (msgs.isNotEmpty() && msgs.last().role == "assistant") {
            msgs.removeAt(msgs.lastIndex)
        }
        _messages.value = msgs
        val keepAfter = msgs.lastOrNull()?.sortKey ?: 0L
        _reasonings.value = _reasonings.value.filter { it.sortKey <= keepAfter }
        _toolCalls.value = _toolCalls.value.filter { it.sortKey <= keepAfter }
        _approvals.value = _approvals.value.filter { it.sortKey <= keepAfter }
        _subAgentItems.value = _subAgentItems.value.filter { it.sortKey <= keepAfter }
        currentReasoningId = null
        currentReasoningText = ""
        lastReasoningUiAt = 0L
        currentAssistantText = ""
        streamingMessageId = 0L
        lastStreamUiAt = 0L
        turnJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val conversationId = ensureConversationReady()
                controller.regenerateTurn(conversationId).collect { event ->
                    handleEvent(event)
                }
            } finally {
                finalizeReasoning()
                _isLoading.value = false
                drainQueue()
            }
        }
    }

    private fun buildMessageWithContext(text: String, attachments: List<Attachment> = emptyList()): String {
        val chips = _contextChips.value
        if (chips.isEmpty() && attachments.isEmpty()) return text
        val mentions = buildString {
            chips.forEach { chip ->
                when (chip.kind) {
                    ContextChipKind.SKILL -> {
                        val name = chip.label.removePrefix("$").ifBlank {
                            chip.payload.substringAfterLast('/').ifBlank { chip.id.removePrefix("skill:") }
                        }
                        val path = chip.payload
                        if (path.isNotBlank() && path != name && path.contains('/')) {
                            append("[$" + name + "](" + path + ") ")
                        } else {
                            append("$" + name + " ")
                        }
                    }
                    ContextChipKind.COMMAND -> {
                        val cmd = chip.payload.ifBlank { "/${chip.label}" }
                        append(if (cmd.startsWith("/")) "$cmd " else "/$cmd ")
                    }
                    ContextChipKind.FILE -> append("@${chip.payload} ")
                    ContextChipKind.CONVERSATION -> append("${chip.label} ")
                    ContextChipKind.ATTACHMENT -> append("${chip.label} ")
                }
            }
        }.trim()
        val extra = buildString {
            val files = chips.filter { it.kind == ContextChipKind.FILE }
            val convs = chips.filter { it.kind == ContextChipKind.CONVERSATION }
            if (files.isNotEmpty() || convs.isNotEmpty() || attachments.isNotEmpty()) {
                appendLine()
                appendLine("[上下文]")
                files.forEach { appendLine("- 文件: ${it.payload}") }
                convs.forEach { appendLine("- 关联会话: ${it.label} (id=${it.payload})") }
                attachments.forEach { appendLine("- 附件: ${it.name}") }
            }
        }
        return buildString {
            if (mentions.isNotEmpty()) append(mentions)
            if (text.isNotBlank()) {
                if (mentions.isNotEmpty()) append(' ')
                append(text)
            }
            append(extra)
        }.trim()
    }

    private suspend fun truncateFromUserMessage(conversationId: Long, messageId: Long) {
        val msgs = _messages.value
        val idx = msgs.indexOfFirst { it.id == messageId && it.role == "user" }
        if (idx < 0) return
        val cut = msgs[idx]
        val cutKey = cut.sortKey

        _messages.value = msgs.take(idx)
        _toolCalls.value = _toolCalls.value.filter { it.sortKey < cutKey }
        _reasonings.value = _reasonings.value.filter { it.sortKey < cutKey }
        _approvals.value = _approvals.value.filter { it.sortKey < cutKey }
        _subAgentItems.value = _subAgentItems.value.filter { it.sortKey < cutKey }
        currentReasoningId = null
        currentReasoningText = ""
        lastReasoningUiAt = 0L
        currentAssistantText = ""
        streamingMessageId = 0L
        lastStreamUiAt = 0L
        processSortCursor = maxOf(
            _messages.value.maxOfOrNull { it.sortKey } ?: 0L,
            _toolCalls.value.maxOfOrNull { it.sortKey } ?: 0L,
            _reasonings.value.maxOfOrNull { it.sortKey } ?: 0L,
            _approvals.value.maxOfOrNull { it.sortKey } ?: 0L,
            _subAgentItems.value.maxOfOrNull { it.sortKey } ?: 0L,
        )

        val dbMsgs = runCatching { repo.messages(conversationId) }.getOrDefault(emptyList())
        val userOrdinal = msgs.take(idx + 1).count { it.role == "user" } - 1
        val dbId = when {
            dbMsgs.any { it.id == messageId } -> messageId
            else -> {
                val exact = dbMsgs.firstOrNull {
                    it.role == "user" &&
                        it.content == cut.content &&
                        (it.createdAt == cut.createdAt || it.createdAt == cutKey)
                }?.id
                exact
                    ?: dbMsgs.filter { it.role == "user" && it.content == cut.content }
                        .minByOrNull { kotlin.math.abs(it.createdAt - cutKey) }
                        ?.id
                    ?: dbMsgs.firstOrNull { it.createdAt >= cutKey }?.id
                    ?: dbMsgs.filter { it.role == "user" }.getOrNull(userOrdinal)?.id
            }
        }
        if (dbId != null && dbId > 0L) {
            runCatching { repo.truncateFrom(conversationId, dbId) }
        }
        runCatching { controller.reseedFromDb(conversationId) }
    }

    private fun runTurn(text: String, images: List<String> = emptyList(), editMessageId: Long? = null) {
        turnJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val conversationId = ensureConversationReady()
                if (editMessageId != null) {
                    truncateFromUserMessage(conversationId, editMessageId)
                }
                controller.sendMessage(conversationId, text, images = images).collect { event ->
                    handleEvent(event)
                }
            } finally {
                _isLoading.value = false
                drainQueue()
            }
        }
    }

    fun stop() {
        turnJob?.cancel()
        turnJob = null
        finalizeReasoning()
        val id = _currentConversationId.value
        if (id > 0L) controller.stopTurn(id)
        else controller.resolveApproval(false)
        _isLoading.value = false
        val running = _toolCalls.value.map {
            if (it.isRunning) it.copy(isRunning = false, isError = true, output = it.output ?: "已停止") else it
        }
        _toolCalls.value = running
        _subAgentItems.value = _subAgentItems.value.map {
            if (it.state == "RUNNING" || it.state == "WAITING") {
                it.copy(state = "CLOSED", result = it.result.ifBlank { "已停止" })
            } else it
        }
        val current = _messages.value.toMutableList()
        val idx = current.indexOfLast { it.role == "assistant" && it.isStreaming }
        if (idx >= 0) current[idx] = current[idx].copy(isStreaming = false)
        _messages.value = current
        currentAssistantText = ""
        refreshContextUsage()
    }

    fun refreshContextUsage() {
        contextUsageJob?.cancel()
        contextUsageJob = viewModelScope.launch {
            kotlinx.coroutines.delay(120)
            val id = _currentConversationId.value
            val estimated = if (id > 0L) {
                controller.estimateContextTokens(id)
            } else {
                val texts = _messages.value.map { it.content } + _toolCalls.value.map { it.args + (it.output ?: "") }
                com.andmx.agent.TokenEstimate.estimateAll(texts)
            }
            val real = controller.tokenUsage.value.total
            _contextTokens.value = if (real > 0) maxOf(estimated, real) else estimated
            val settings = settingsStore.settings.firstOrNull()
            val providers = providerStore.providers.firstOrNull().orEmpty()
            val provider = providers.firstOrNull { it.id == settings?.activeProviderId }
            val window = provider?.models?.get(settings?.model.orEmpty())?.contextWindow?.takeIf { it > 0 } ?: 128_000
            _contextWindow.value = window
        }
    }

    private fun drainQueue() {
        val next = _queue.value.firstOrNull() ?: return
        _queue.value = _queue.value.drop(1)
        runTurn(next)
    }

    fun removeFromQueue(index: Int) {
        _queue.value = _queue.value.filterIndexed { i, _ -> i != index }
    }

    fun sendQueuedNow(index: Int) {
        val item = _queue.value.getOrNull(index) ?: return
        if (_isLoading.value) return
        _queue.value = _queue.value.filterIndexed { i, _ -> i != index }
        runTurn(item)
    }

    // ── 配置链写入 ─────────────────────────────────────────────────────────

    fun switchModel(providerId: String, modelId: String) {
        viewModelScope.launch {
            val cur = settingsStore.settings.firstOrNull() ?: ProviderSettings()
            // 先切换 primary provider，再写 model 选择
            if (providerId.isNotBlank()) {
                providerStore.setPrimary(providerId)
            }
            settingsStore.update(
                cur.copy(
                    activeProviderId = providerId,
                    model = modelId,
                ),
            )
        }
    }

    fun setReasoningEffort(effort: String) {
        viewModelScope.launch {
            val cur = settingsStore.settings.firstOrNull() ?: ProviderSettings()
            settingsStore.update(cur.copy(reasoningEffort = effort))
        }
    }

    fun setExecMode(mode: ExecMode) {
        viewModelScope.launch {
            val cur = settingsStore.settings.firstOrNull() ?: ProviderSettings()
            settingsStore.update(cur.copy(approvalMode = mode.id))
        }
    }

    // ── 上下文 chips ───────────────────────────────────────────────────────

    fun addContextChip(chip: ContextChip) {
        if (_contextChips.value.any { it.id == chip.id }) return
        _contextChips.value = _contextChips.value + chip
    }

    fun removeContextChip(id: String) {
        _contextChips.value = _contextChips.value.filterNot { it.id == id }
    }

    fun addFileContext(path: String) {
        val label = path.substringAfterLast('/').ifBlank { path }
        addContextChip(
            ContextChip(
                id = "file:$path",
                kind = ContextChipKind.FILE,
                label = "@$label",
                payload = path,
            ),
        )
    }

    fun addConversationContext(pick: ConversationPick) {
        addContextChip(
            ContextChip(
                id = "conv:${pick.id}",
                kind = ContextChipKind.CONVERSATION,
                label = "#${pick.title}",
                payload = pick.id.toString(),
            ),
        )
    }

    fun addSkillContext(skill: SkillInstaller.InstalledSkill) {
        addSkillByName(skill.name, skill.path)
    }

    fun addSkillByName(name: String, path: String) {
        val bare = name.trim().removePrefix("$").removePrefix("/")
        if (bare.isBlank()) return
        addContextChip(
            ContextChip(
                id = "skill:$bare",
                kind = ContextChipKind.SKILL,
                label = bare,
                payload = path.ifBlank { bare },
            ),
        )
    }

    fun addCommandByName(name: String, payload: String = "") {
        val raw = name.trim().let { if (it.startsWith("/")) it else "/$it" }
        val bare = raw.removePrefix("/")
        if (bare.isBlank()) return
        val skill = _skills.value.firstOrNull { it.name.equals(bare, ignoreCase = true) }
        if (skill != null) {
            addSkillByName(skill.name, skill.path)
            return
        }
        addContextChip(
            ContextChip(
                id = "cmd:$bare",
                kind = ContextChipKind.COMMAND,
                label = bare,
                payload = payload.ifBlank { raw },
            ),
        )
    }


    private fun annotateProcessMessages(
        messages: List<ChatMessage>,
        tools: List<ToolCall>,
    ): List<ChatMessage> {
        if (messages.isEmpty()) return messages
        val toolKeys = tools.map { it.sortKey }.sorted()
        if (toolKeys.isEmpty()) return messages.map { if (it.role == "assistant") it.copy(isProcess = false) else it }
        val lastTool = toolKeys.last()
        return messages.map { m ->
            if (m.role != "assistant") m
            else {
                val followedByTool = toolKeys.any { it > m.sortKey }
                m.copy(isProcess = followedByTool || m.sortKey < lastTool)
            }
        }.let { list ->
            // last assistant after last tool is final
            val lastA = list.indexOfLast { it.role == "assistant" }
            if (lastA >= 0 && list[lastA].sortKey >= lastTool) {
                list.toMutableList().also { it[lastA] = it[lastA].copy(isProcess = false) }
            } else list
        }
    }

    private fun finalizeReasoning() {
        val id = currentReasoningId ?: return
        val text = currentReasoningText
        currentReasoningId = null
        currentReasoningText = ""
        lastReasoningUiAt = 0L
        if (text.isBlank()) {
            _reasonings.value = _reasonings.value.filterNot { it.id == id }
            return
        }
        _reasonings.value = _reasonings.value.map {
            if (it.id == id) it.copy(content = text, isStreaming = false) else it
        }
    }

    private fun nextProcessSortKey(): Long {
        val now = System.currentTimeMillis()
        processSortCursor = maxOf(processSortCursor + 1L, now)
        return processSortCursor
    }

    private fun handleEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.UserMessage -> {
                val key = nextProcessSortKey()
                _messages.value += ChatMessage(
                    role = "user",
                    content = event.text,
                    sortKey = key,
                    createdAt = key,
                )
            }
            is ChatEvent.ReasoningChunk -> {
                val first = currentReasoningId == null
                if (first) {
                    val key = nextProcessSortKey()
                    currentReasoningId = "think-$key"
                    currentReasoningText = ""
                    _reasonings.value = _reasonings.value + ReasoningItem(
                        id = currentReasoningId!!,
                        content = "",
                        isStreaming = true,
                        sortKey = key,
                    )
                }
                currentReasoningText += event.text
                val now = System.currentTimeMillis()
                val shouldPublish = first ||
                    lastReasoningUiAt == 0L ||
                    now - lastReasoningUiAt >= 32L ||
                    event.text.length > 24 ||
                    currentReasoningText.length <= 48
                if (!shouldPublish) return
                lastReasoningUiAt = now
                val id = currentReasoningId ?: return
                _reasonings.value = _reasonings.value.map {
                    if (it.id == id) it.copy(content = currentReasoningText, isStreaming = true) else it
                }
            }
            is ChatEvent.ReasoningDone -> {
                finalizeReasoning()
            }
            is ChatEvent.AssistantChunk -> {
                finalizeReasoning()
                currentAssistantText += event.text
                val now = System.currentTimeMillis()
                val current = _messages.value
                val lastIndex = current.indexOfLast { it.role == "assistant" && it.isStreaming }
                val shouldPublish = lastIndex < 0 ||
                    lastStreamUiAt == 0L ||
                    now - lastStreamUiAt >= 32L ||
                    event.text.length > 48 ||
                    currentAssistantText.length <= 64
                if (!shouldPublish) return
                lastStreamUiAt = now
                val next = current.toMutableList()
                if (lastIndex >= 0) {
                    next[lastIndex] = next[lastIndex].copy(content = currentAssistantText)
                } else {
                    val key = nextProcessSortKey()
                    streamingMessageId = key
                    next.add(
                        ChatMessage(
                            id = key,
                            role = "assistant",
                            content = currentAssistantText,
                            isStreaming = true,
                            sortKey = key,
                            isProcess = true,
                            createdAt = key,
                        ),
                    )
                }
                _messages.value = next
            }
            is ChatEvent.AssistantComplete -> {
                lastStreamUiAt = 0L
                val text = event.fullText
                currentAssistantText = ""
                if (text.isBlank()) {
                    // drop empty streaming placeholder
                    val current = _messages.value.toMutableList()
                    val lastIndex = current.indexOfLast { it.role == "assistant" && it.isStreaming }
                    if (lastIndex >= 0) {
                        current.removeAt(lastIndex)
                        _messages.value = current
                    }
                    return
                }
                val current = _messages.value.toMutableList()
                val streamIndex = current.indexOfLast { it.role == "assistant" && it.isStreaming }
                // Intermediate process narration stays as its own item (ZCode agent_message).
                // Only mark final answer when turn ends (Done) — for now each complete is process
                // if tools continue; Done will promote the last one.
                val isProcess = true
                val finishedAt = System.currentTimeMillis()
                if (streamIndex >= 0) {
                    val prev = current[streamIndex]
                    current[streamIndex] = prev.copy(
                        content = text,
                        isStreaming = false,
                        isProcess = isProcess,
                        createdAt = if (prev.createdAt > 0L) prev.createdAt else prev.sortKey,
                        completedAt = finishedAt,
                    )
                } else {
                    val key = nextProcessSortKey()
                    current.add(
                        ChatMessage(
                            id = key,
                            role = "assistant",
                            content = text,
                            isStreaming = false,
                            sortKey = key,
                            isProcess = isProcess,
                            createdAt = key,
                            completedAt = finishedAt,
                        ),
                    )
                }
                streamingMessageId = 0L
                _messages.value = current
            }
            is ChatEvent.ToolCallArgsDelta -> {
                val current = _toolCalls.value.toMutableList()
                val byId = event.id?.let { id -> current.indexOfFirst { it.id == id } } ?: -1
                val byNameRunning = if (byId < 0 && !event.name.isNullOrBlank()) {
                    current.indexOfFirst { it.isRunning && it.name == event.name }
                } else -1
                val idx = when {
                    byId >= 0 -> byId
                    byNameRunning >= 0 -> byNameRunning
                    else -> -1
                }
                if (idx >= 0) {
                    val prev = current[idx]
                    current[idx] = prev.copy(
                        id = event.id ?: prev.id,
                        name = event.name ?: prev.name,
                        args = event.args,
                        isRunning = true,
                    )
                    _toolCalls.value = current
                } else if (!event.name.isNullOrBlank() || event.args.isNotBlank()) {
                    _toolCalls.value = current + ToolCall(
                        id = event.id ?: "stream-tool-${event.index}",
                        name = event.name ?: "tool",
                        args = event.args,
                        isRunning = true,
                        sortKey = nextProcessSortKey(),
                    )
                }
            }
            is ChatEvent.ToolCallStarted -> {
                finalizeReasoning()
                val msgs = _messages.value.toMutableList()
                val streamIndex = msgs.indexOfLast { it.role == "assistant" && it.isStreaming }
                if (streamIndex >= 0) {
                    msgs[streamIndex] = msgs[streamIndex].copy(
                        content = currentAssistantText.ifBlank { msgs[streamIndex].content },
                        isStreaming = false,
                        isProcess = true,
                    )
                    currentAssistantText = ""
                    lastStreamUiAt = 0L
                    _messages.value = msgs
                }
                val tools = _toolCalls.value.toMutableList()
                val existing = tools.indexOfFirst {
                    it.id == event.id ||
                        (it.isRunning && it.name == event.name && (it.id.startsWith("stream-tool-") || it.args == event.args.take(it.args.length)))
                }
                if (existing >= 0) {
                    val prev = tools[existing]
                    tools[existing] = prev.copy(
                        id = event.id,
                        name = event.name,
                        args = event.args.ifBlank { prev.args },
                        isRunning = true,
                    )
                    _toolCalls.value = tools
                } else {
                    _toolCalls.value = tools + ToolCall(
                        id = event.id,
                        name = event.name,
                        args = event.args,
                        isRunning = true,
                        sortKey = nextProcessSortKey(),
                    )
                }
            }
            is ChatEvent.ToolCallFinished -> {
                val current = _toolCalls.value.toMutableList()
                val index = current.indexOfFirst { it.id == event.id }
                if (index >= 0) {
                    current[index] = current[index].copy(
                        output = event.output, isRunning = false, isError = event.isError,
                    )
                }
                _toolCalls.value = current
                refreshContextUsage()
            }
            is ChatEvent.PlanUpdated -> {
            }
            is ChatEvent.ApprovalRequested -> {
                if (_approvals.value.none { it.id == event.id }) {
                    _approvals.value = _approvals.value + ApprovalItem(
                        id = event.id,
                        toolName = event.toolName,
                        summary = event.summary,
                        modeLabel = event.modeLabel,
                        status = "pending",
                    )
                }
            }
            is ChatEvent.ApprovalResolved -> {
                _approvals.value = _approvals.value.map {
                    if (it.id == event.id) it.copy(status = if (event.allowed) "allowed" else "denied") else it
                }
            }
            is ChatEvent.SubAgentStarted,
            is ChatEvent.SubAgentDelta,
            is ChatEvent.SubAgentCompleted,
            is ChatEvent.SubAgentFailed -> handleSideEvent(event)
            is ChatEvent.Error -> _error.value = event.message
            is ChatEvent.Done -> {
                finalizeReasoning()
                // Last assistant item without trailing tools becomes final answer (non-process).
                val msgs = _messages.value.toMutableList()
                val tools = _toolCalls.value
                val lastToolKey = tools.maxOfOrNull { it.sortKey } ?: -1L
                val lastAssistantIndex = msgs.indexOfLast { it.role == "assistant" && !it.isStreaming }
                val now = System.currentTimeMillis()
                if (lastAssistantIndex >= 0) {
                    val m = msgs[lastAssistantIndex]
                    if (m.sortKey >= lastToolKey) {
                        msgs[lastAssistantIndex] = m.copy(
                            isProcess = false,
                            completedAt = if (m.completedAt > 0L) m.completedAt else now,
                            createdAt = if (m.createdAt > 0L) m.createdAt else m.sortKey,
                        )
                        _messages.value = msgs
                    }
                }
                // close dangling stream
                val streamIdx = msgs.indexOfLast { it.isStreaming }
                if (streamIdx >= 0) {
                    val m = msgs[streamIdx]
                    msgs[streamIdx] = m.copy(
                        isStreaming = false,
                        isProcess = false,
                        completedAt = now,
                        createdAt = if (m.createdAt > 0L) m.createdAt else m.sortKey,
                    )
                    _messages.value = msgs
                }
                currentAssistantText = ""
                lastStreamUiAt = 0L
                refreshContextUsage()
            }
        }
    }

    private fun handleSideEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.SubAgentStarted -> {
                val list = _subAgentItems.value.toMutableList()
                val idx = list.indexOfFirst { it.id == event.agentId }
                val item = SubAgentItem(
                    id = event.agentId,
                    task = event.task,
                    state = "RUNNING",
                    result = "",
                )
                if (idx >= 0) list[idx] = item else list += item
                _subAgentItems.value = list
            }
            is ChatEvent.SubAgentDelta -> {
                val list = _subAgentItems.value.toMutableList()
                val idx = list.indexOfFirst { it.id == event.agentId }
                if (idx >= 0) {
                    val cur = list[idx]
                    list[idx] = cur.copy(result = (cur.result + event.text).takeLast(4000), state = "RUNNING")
                    _subAgentItems.value = list
                }
            }
            is ChatEvent.SubAgentCompleted -> {
                val list = _subAgentItems.value.toMutableList()
                val idx = list.indexOfFirst { it.id == event.agentId }
                if (idx >= 0) {
                    list[idx] = list[idx].copy(state = "COMPLETED", result = event.result.take(2000))
                } else {
                    list += SubAgentItem(id = event.agentId, task = event.agentId, state = "COMPLETED", result = event.result.take(2000))
                }
                _subAgentItems.value = pruneSubAgentItems(list)
            }
            is ChatEvent.SubAgentFailed -> {
                val list = _subAgentItems.value.toMutableList()
                val idx = list.indexOfFirst { it.id == event.agentId }
                if (idx >= 0) {
                    list[idx] = list[idx].copy(state = "FAILED", result = event.error.take(1000))
                } else {
                    list += SubAgentItem(id = event.agentId, task = event.agentId, state = "FAILED", result = event.error.take(1000))
                }
                _subAgentItems.value = pruneSubAgentItems(list)
            }
            else -> handleEvent(event)
        }
    }

    fun resolveApproval(allow: Boolean) {
        val req = controller.pendingApproval.value
        if (req != null) {
            _approvals.value = _approvals.value.map {
                if (it.id == req.id) it.copy(status = if (allow) "allowed" else "denied") else it
            }
        }
        controller.resolveApproval(allow)
    }

    fun resolveUserQuestion(answersJson: String) {
        val req = controller.pendingApproval.value
        if (req != null) {
            _approvals.value = _approvals.value.map {
                if (it.id == req.id) it.copy(status = "allowed", summary = answersJson.take(200)) else it
            }
        }
        controller.resolveUserQuestion(answersJson)
    }

    fun clearError() {
        _error.value = null
    }

    fun dismissAmbient(id: String) {
        controller.dismissAmbient(id)
    }

}
