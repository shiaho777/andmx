package com.andmx.ui2.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andmx.agent.plugins.SkillInstaller
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _toolCalls = MutableStateFlow<List<ToolCall>>(emptyList())
    val toolCalls: StateFlow<List<ToolCall>> = _toolCalls.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 工作区名（项目目录名），跟随 hostPath 变化。 */
    val projectName: StateFlow<String> =
        projectManager.hostPath.map { projectManager.projectName }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), projectManager.projectName)
    val hostPath: StateFlow<String?> = projectManager.hostPath

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

    /** 当前会话 id，UI 据此高亮侧边栏选中项。 */
    private val _currentConversationId = MutableStateFlow(1L)
    val currentConversationId: StateFlow<Long> = _currentConversationId.asStateFlow()

    private var currentAssistantText = ""
    private var turnJob: Job? = null

    /** 切换到指定会话：更新当前 id + 加载历史消息到 messages。 */
    fun switchToConversation(id: Long) {
        if (id == _currentConversationId.value) return
        turnJob?.cancel()
        _currentConversationId.value = id
        currentAssistantText = ""
        _isLoading.value = false
        viewModelScope.launch {
            val history = runCatching { repo.messages(id) }.getOrDefault(emptyList())
            _messages.value = history.mapNotNull { msg ->
                when (msg.role) {
                    "user" -> ChatMessage(id = msg.id, role = "user", content = msg.content)
                    "assistant" -> ChatMessage(id = msg.id, role = "assistant", content = msg.content)
                    else -> null // tool 消息历史暂不回放为气泡
                }
            }
            _toolCalls.value = emptyList()
        }
    }

    /** 新建会话并切换过去。返回新会话 id。 */
    fun createConversation() {
        viewModelScope.launch {
            val id = repo.createConversation(project = "/root", title = "新任务")
            switchToConversation(id)
        }
    }

    init {
        viewModelScope.launch {
            // 确保旧 DataStore provider 已迁移到 Room，Composer 能列出模型
            runCatching {
                providerStore.ensureSeeded(settingsStore.legacyProvider())
            }
            // 拉取已安装技能，供 $ 联想；proot 未就绪则保持空列表
            _skills.value = runCatching { skillInstaller.listInstalled() }
                .getOrDefault(emptyList())
            // ZCode 对齐：模型支持推理时，首次默认选「最高」
            applyDefaultReasoningIfNeeded()
        }
        // 工作区变化时刷新 git 信息（分支指示器）
        viewModelScope.launch {
            projectManager.hostPath.collect { refreshGitInfo() }
        }
    }

    /** 刷新当前工作区的 git 信息（分支、变更）。proot 未就绪时静默忽略。 */
    fun refreshGitInfo() {
        viewModelScope.launch {
            val path = projectManager.hostPath.value ?: return@launch
            _gitInfo.value = runCatching { gitBaseline.collectGitInfo(path) }
                .getOrNull()?.takeIf { it.isRepo }
        }
    }

    /** 列出本地分支（供分支切换 UI）。 */
    suspend fun listBranches(): List<com.andmx.workspace.GitBaseline.BranchInfo> {
        val path = projectManager.hostPath.value ?: return emptyList()
        return runCatching { gitBaseline.listBranches(path) }.getOrDefault(emptyList())
    }

    /** 切换或新建分支，完成后刷新 git 信息。 */
    fun checkoutBranch(name: String, create: Boolean, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val path = projectManager.hostPath.value
            if (path == null) { onDone(false, "未选择工作区"); return@launch }
            val result = runCatching { gitBaseline.checkout(path, name, create) }
                .getOrDefault(com.andmx.workspace.GitBaseline.BaselineResult(false, "执行失败"))
            onDone(result.ok, result.message)
            refreshGitInfo()
        }
    }

    /** 选择工作区目录（侧边栏工作区选择器调用）。 */
    fun selectProject(path: String) {
        projectManager.selectProject(path)
    }

    /** 建议的工作区根目录（供选择器快捷入口）。 */
    fun suggestedRoots(): List<String> = projectManager.suggestedRoots()

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

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && _contextChips.value.isEmpty()) return
        // 把上下文 chips 拼进用户消息前缀，模型能看到引用
        val withContext = buildMessageWithContext(trimmed)
        if (_isLoading.value) {
            _queue.value = _queue.value + withContext
            return
        }
        _contextChips.value = emptyList()
        runTurn(withContext)
    }

    private fun buildMessageWithContext(text: String): String {
        val chips = _contextChips.value
        if (chips.isEmpty()) return text
        val prefix = buildString {
            appendLine("[上下文]")
            chips.forEach { chip ->
                when (chip.kind) {
                    ContextChipKind.FILE -> appendLine("- 文件: ${chip.payload}")
                    ContextChipKind.CONVERSATION -> appendLine("- 关联会话: ${chip.label} (id=${chip.payload})")
                    ContextChipKind.COMMAND -> appendLine("- 命令: ${chip.payload}")
                    ContextChipKind.SKILL -> appendLine("- Skill: ${chip.payload}")
                    ContextChipKind.ATTACHMENT -> appendLine("- 附件: ${chip.label}")
                }
            }
            appendLine()
        }
        return if (text.isBlank()) prefix.trim() else prefix + text
    }

    private fun runTurn(text: String) {
        turnJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                controller.sendMessage(_currentConversationId.value, text).collect { event ->
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
        _isLoading.value = false
        val current = _messages.value.toMutableList()
        val idx = current.indexOfLast { it.role == "assistant" && it.isStreaming }
        if (idx >= 0) current[idx] = current[idx].copy(isStreaming = false)
        _messages.value = current
        currentAssistantText = ""
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

    /** Composer 的 $ 联想选中后调用（解耦自 InstalledSkill 类型）。 */
    fun addSkillByName(name: String, path: String) {
        addContextChip(
            ContextChip(
                id = "skill:$name",
                kind = ContextChipKind.SKILL,
                label = "\$$name",
                payload = path,
            ),
        )
    }

    private fun handleEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.UserMessage -> {
                _messages.value += ChatMessage(role = "user", content = event.text)
            }
            is ChatEvent.AssistantChunk -> {
                currentAssistantText += event.text
                val current = _messages.value.toMutableList()
                val lastIndex = current.indexOfLast { it.role == "assistant" && it.isStreaming }
                if (lastIndex >= 0) {
                    current[lastIndex] = current[lastIndex].copy(content = currentAssistantText)
                } else {
                    current.add(ChatMessage(role = "assistant", content = currentAssistantText, isStreaming = true))
                }
                _messages.value = current
            }
            is ChatEvent.AssistantComplete -> {
                currentAssistantText = ""
                val current = _messages.value.toMutableList()
                val lastIndex = current.indexOfLast { it.role == "assistant" }
                if (lastIndex >= 0) {
                    current[lastIndex] = current[lastIndex].copy(content = event.fullText, isStreaming = false)
                } else {
                    current.add(ChatMessage(role = "assistant", content = event.fullText, isStreaming = false))
                }
                _messages.value = current
            }
            is ChatEvent.ToolCallStarted -> {
                _toolCalls.value += ToolCall(id = event.id, name = event.name, args = event.args, isRunning = true)
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
            }
            is ChatEvent.Error -> _error.value = event.message
            is ChatEvent.Done -> {}
        }
    }

    fun clearError() {
        _error.value = null
    }
}
