package com.andmx.ui2.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.andmx.agent.SlashCommands
import com.andmx.ui2.drawer.CommandCenterSheet
import com.andmx.ui2.drawer.ConversationDrawer
import com.andmx.settings.ProviderSettings
import com.andmx.settings.SettingsStore
import com.andmx.ui2.settings.TaskAutoArchive
import com.andmx.ui2.drawer.defaultCommandCenterItems
import com.andmx.ui2.settings.SettingsPage
import com.andmx.ui2.files.FilesScreen
import com.andmx.ui2.settings.SettingsScreen
import com.andmx.ui2.terminal.TerminalScreen
import com.andmx.ui2.terminal.rememberTerminalColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsState()
    val toolCalls by viewModel.toolCalls.collectAsState()
    val reasonings by viewModel.reasonings.collectAsState()
    val approvals by viewModel.approvals.collectAsState()
    val subAgents by viewModel.subAgents.collectAsState()
    val subAgentItems by viewModel.subAgentItems.collectAsState()
    val mcpStatus by viewModel.mcpStatus.collectAsState()
    val contextTokens by viewModel.contextTokens.collectAsState()
    val contextWindow by viewModel.contextWindow.collectAsState()
    val tokenUsage by viewModel.tokenUsage.collectAsState()
    val goal by viewModel.goal.collectAsState()
    val error by viewModel.error.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val editingMessageId by viewModel.editingMessageId.collectAsState()
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val appSettings by settingsStore.settings.collectAsState(initial = ProviderSettings())
    val visibleReasonings = remember(reasonings, appSettings.showReasoning) {
        if (appSettings.showReasoning) reasonings else emptyList()
    }
    val visibleTools = remember(toolCalls, appSettings.showTodos) {
        if (appSettings.showTodos) toolCalls
        else toolCalls.filterNot { tool ->
            val n = tool.name.lowercase()
            n.contains("todo") || n == "update_plan"
        }
    }
    val timeline = remember(messages, visibleTools, approvals, subAgentItems, visibleReasonings, isLoading) {
        val hasLiveStream = messages.any { it.isStreaming } || visibleReasonings.any { it.isStreaming }
        val hasRunningTool = visibleTools.any { it.isRunning }
        buildTimeline(
            messages = messages,
            tools = visibleTools,
            approvals = approvals,
            subAgents = subAgentItems,
            reasonings = visibleReasonings,
            showWorking = isLoading && !hasLiveStream && !hasRunningTool && visibleReasonings.none { it.isStreaming },
        )
    }
    val timelineReversed = remember(timeline) { timeline.asReversed() }
    val lastAssistantStableId = remember(timeline) {
        timeline.lastOrNull {
            it is TimelineItem.Message &&
                it.message.role == "assistant" &&
                !it.message.isStreaming &&
                !it.message.isProcess
        }?.stableId
    }
    val pendingApproval by viewModel.pendingApproval.collectAsState()
    val planSteps by viewModel.planSteps.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val config by viewModel.composerConfig.collectAsState()
    val contextChips by viewModel.contextChips.collectAsState()
    val recentConversations by viewModel.recentConversations.collectAsState()
    val skills by viewModel.skills.collectAsState()
    val pluginSlashSpecs by viewModel.pluginSlashSpecs.collectAsState()

    // ZCode 对齐：对话为唯一主屏，终端/文件/设置均为浮层
    val projectName by viewModel.projectName.collectAsState()
    val gitInfo by viewModel.gitInfo.collectAsState()
    val hostPath by viewModel.hostPath.collectAsState()
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    val branch = gitInfo?.branch.orEmpty()

    LaunchedEffect(hostPath) {
        viewModel.refreshGitInfo()
    }

    var showTerminal by remember { mutableStateOf(false) }
    var showFiles by remember { mutableStateOf(false) }
    var filesInitialPath by remember { mutableStateOf<String?>(null) }
    var fileTreeRequestKey by remember { mutableStateOf(0) }
    var fileTreeRequestPath by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showCommandCenter by remember { mutableStateOf(false) }
    var settingsInitialPage by remember { mutableStateOf(SettingsPage.HOME) }
    var showBranchDialog by remember { mutableStateOf(false) }
    var showWorkspacePicker by remember { mutableStateOf(false) }
    var showGitActions by remember { mutableStateOf(false) }
    var showRemoteDialog by remember { mutableStateOf(false) }
    var dirtyFilesCache by remember { mutableStateOf<List<com.andmx.workspace.GitBaseline.ChangedFile>>(emptyList()) }

    // 浮层打开时拦截返回键：先关浮层，再走系统返回
    BackHandler(enabled = showTerminal || showFiles || showSettings) {
        when {
            showTerminal -> showTerminal = false
            showFiles -> {
                showFiles = false
                filesInitialPath = null
            }
            showSettings -> showSettings = false
        }
    }

    var drawerOpen by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }

    LaunchedEffect(appSettings.taskAutoArchive, appSettings.taskAutoArchiveDays) {
        TaskAutoArchive.runIfEnabled(context, appSettings)
    }

LaunchedEffect(Unit) {
        ChatActionBus.actions.collect { action ->
            when (action) {
                is ChatActionBus.Action.OpenFile -> {
                    val path = action.path
                    viewModel.addFileContext(path)
                    filesInitialPath = path
                    showFiles = true
                }
                ChatActionBus.Action.OpenTerminal -> showTerminal = true
                is ChatActionBus.Action.OpenUrl -> {
                    runCatching {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(action.url),
                        )
                        context.startActivity(intent)
                    }
                }
                ChatActionBus.Action.OpenSettings -> {
                    settingsInitialPage = SettingsPage.HOME
                    showSettings = true
                }
                ChatActionBus.Action.OpenSkillsSettings -> {
                    settingsInitialPage = SettingsPage.SKILLS
                    showSettings = true
                }
                ChatActionBus.Action.OpenSearch -> showCommandCenter = true
            }
        }
    }

    LaunchedEffect(Unit) {
        ChatComposerBus.inserts.collect { insert ->
            when (insert) {
                is ChatComposerBus.Insert.File -> viewModel.addFileContext(insert.path)
                is ChatComposerBus.Insert.Skill -> viewModel.addSkillByName(insert.name, insert.path)
                is ChatComposerBus.Insert.Command -> viewModel.addCommandByName(insert.name, insert.payload)
                is ChatComposerBus.Insert.Text -> {
                    val snippet = insert.text
                    inputText = if (inputText.isBlank()) snippet else "$inputText\n$snippet"
                }
            }
        }
    }

    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "图片"
            attachments = attachments + Attachment(name = name, uri = uri.toString())
        }
    }

    // SAF 目录选择器：选择工作区文件夹
    val workspacePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val base = android.os.Environment.getExternalStorageDirectory()?.absolutePath ?: "/sdcard"
            val docId = runCatching {
                android.provider.DocumentsContract.getTreeDocumentId(uri)
            }.getOrNull()
            val raw = docId ?: uri.lastPathSegment.orEmpty()
            val decoded = android.net.Uri.decode(raw)
            val path = when {
                decoded == "primary:" || decoded == "primary" -> base
                decoded.startsWith("primary:") -> "$base/${decoded.removePrefix("primary:")}"
                decoded.startsWith("/") -> decoded
                else -> decoded.substringAfter(':', missingDelimiterValue = decoded)
                    .takeIf { it.isNotBlank() }
                    ?.let { if (it.startsWith("/")) it else "$base/$it" }
                    .orEmpty()
            }
            if (path.isNotBlank()) viewModel.selectProject(path)
        }
    }

    // ── 输入触发：/ 与 # 建议 ──
    val slashSuggestions by remember(inputText, pluginSlashSpecs) {
        derivedStateOf {
            val t = inputText
            if (t.startsWith("/") && !t.contains(' ')) {
                SlashCommands.suggestions(t, 8, extras = pluginSlashSpecs)
            } else emptyList()
        }
    }
    val conversationSuggestions by remember {
        derivedStateOf {
            val t = inputText
            // 行尾 #query 触发会话联想
            val match = Regex("""(?:^|\s)#([^\s#]*)$""").find(t) ?: return@derivedStateOf emptyList()
            val q = match.groupValues[1]
            recentConversations
                .filter {
                    q.isBlank() ||
                        it.title.contains(q, ignoreCase = true) ||
                        it.subtitle.contains(q, ignoreCase = true)
                }
                .take(8)
        }
    }
    val skillSuggestions by remember {
        derivedStateOf {
            val t = inputText
            // 行尾 $query 触发技能联想
            val match = Regex("""(?:^|\s)\$([^\s$]*)$""").find(t) ?: return@derivedStateOf emptyList()
            val q = match.groupValues[1]
            skills
                .filter { q.isBlank() || it.name.contains(q, ignoreCase = true) }
                .take(8)
                .map { SkillSuggestion(name = it.name, path = it.path) }
        }
    }

    fun insertAtCursor(snippet: String, replaceTrigger: Regex? = null) {
        val cur = inputText
        inputText = if (replaceTrigger != null) {
            replaceTrigger.replace(cur) { mr ->
                val prefix = mr.value.takeWhile { it.isWhitespace() }
                "$prefix$snippet"
            }
        } else {
            if (cur.isBlank()) snippet
            else if (cur.endsWith(" ") || cur.endsWith("\n")) cur + snippet
            else "$cur $snippet"
        }
    }

    val streamingConversationIds = remember(isLoading, currentConversationId) {
        if (isLoading && currentConversationId > 0L) setOf(currentConversationId) else emptySet()
    }

    ConversationDrawer(
        open = drawerOpen,
        onDismiss = { drawerOpen = false },
        onSelectConversation = { id ->
            viewModel.switchToConversation(id)
            drawerOpen = false
        },
        onNewConversation = {
            viewModel.createConversation()
            drawerOpen = false
        },
        onOpenFiles = { projectPath ->
            fileTreeRequestPath = projectPath
            fileTreeRequestKey += 1
            drawerOpen = true
        },
        onOpenSettings = {
            drawerOpen = false
            settingsInitialPage = SettingsPage.HOME
            showSettings = true
        },
        onOpenSearch = {
            drawerOpen = false
            showCommandCenter = true
        },
        onOpenSkills = {
            drawerOpen = false
            settingsInitialPage = SettingsPage.SKILLS
            showSettings = true
        },
        fileTreeRequestPath = fileTreeRequestPath,
        fileTreeRequestKey = fileTreeRequestKey,
        currentConversationId = currentConversationId,
        streamingConversationIds = streamingConversationIds,
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .background(MaterialTheme.colorScheme.background)
            ) {

            // Composer 复用：空状态和对话状态共用同一套调用（局部闭包）
            @Composable
            fun ComposerBlock(flat: Boolean = false) {
                Composer(
                    value = inputText,
                    onValueChange = { inputText = it },
                    onSend = {
                        if (inputText.isNotBlank() || attachments.isNotEmpty() || contextChips.isNotEmpty()) {
                            viewModel.sendMessage(inputText, attachments = attachments)
                            inputText = ""
                            attachments = emptyList()
                        }
                    },
                    isLoading = isLoading,
                    onStop = { viewModel.stop() },
                    modelLabel = config.modelLabel,
                    selectedModel = config.settings.model,
                    activeProviderId = config.settings.activeProviderId
                        .ifBlank { config.primary?.id.orEmpty() },
                    providers = config.providers,
                    onSwitchModel = { pid, mid -> viewModel.switchModel(pid, mid) },
                    reasoningEffort = config.settings.reasoningEffort,
                    reasoning = config.reasoning,
                    onReasoningSelected = { viewModel.setReasoningEffort(it) },
                    execMode = config.execMode,
                    onExecModeSelected = { viewModel.setExecMode(it) },
                    contextChips = contextChips,
                    onRemoveContextChip = { viewModel.removeContextChip(it) },
                    attachments = attachments,
                    onRemoveAttachment = { i ->
                        attachments = attachments.filterIndexed { idx, _ -> idx != i }
                    },
                    onAddAttachment = { imagePicker.launch("image/*") },
                    onInsertMention = {
                        fileTreeRequestPath = null
                        fileTreeRequestKey += 1
                        drawerOpen = true
                    },
                    onInsertConversation = { insertAtCursor("#") },
                    onInsertCommand = { insertAtCursor("/") },
                    onInsertSkill = { insertAtCursor("\$") },
                    slashSuggestions = slashSuggestions,
                    onPickSlash = { spec ->
                        val raw = spec.name.trim()
                        val bare = raw.removePrefix("/")
                        inputText = Regex("""^/\S*$""").replace(inputText, "").trimEnd()
                        val skill = skills.firstOrNull { it.name.equals(bare, ignoreCase = true) }
                        if (skill != null) {
                            viewModel.addSkillByName(skill.name, skill.path)
                        } else {
                            viewModel.addCommandByName(raw)
                        }
                    },
                    conversationSuggestions = conversationSuggestions,
                    onPickConversation = { pick ->
                        inputText = Regex("""(?:^|\s)#[^\s#]*$""").replace(inputText) { mr ->
                            mr.value.takeWhile { it.isWhitespace() }
                        }.trimEnd()
                        viewModel.addConversationContext(pick)
                    },
                    skillSuggestions = skillSuggestions,
                    onPickSkill = { skill ->
                        inputText = Regex("""(?:^|\s)\$[^\s$]*$""").replace(inputText) { mr ->
                            mr.value.takeWhile { it.isWhitespace() }
                        }.trimEnd()
                        viewModel.addSkillByName(skill.name, skill.path)
                    },
                    flat = flat,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (flat) Modifier else Modifier.padding(horizontal = 10.dp, vertical = 8.dp)),
                )
            }

            TopAppBar(
                title = {
                    Text(
                        if (timeline.isEmpty()) "新任务"
                        else projectName.ifBlank { "AndMX" },
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { drawerOpen = !drawerOpen }) {
                        Icon(Icons.Outlined.Menu, "切换侧边栏")
                    }
                },
                // ZCode：终端从对话头部右上角唤起（再点收起）
                actions = {
                    IconButton(onClick = { showCommandCenter = true }) {
                        Icon(Icons.Outlined.Search, "搜索")
                    }
                    IconButton(onClick = {
                        fileTreeRequestPath = null
                        fileTreeRequestKey += 1
                        drawerOpen = true
                    }) {
                        Icon(Icons.Outlined.FolderOpen, "查看文件")
                    }
                    IconButton(onClick = { showTerminal = !showTerminal }) {
                        Icon(
                            Icons.Outlined.Terminal,
                            if (showTerminal) "关闭终端" else "终端",
                            tint = if (showTerminal) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )

            // ZCode 对齐：无消息时 Composer 居中 + 欢迎语；有消息时 Composer 在底部
            val isEmpty = timeline.isEmpty()

            if (isEmpty) {
                EmptyConversationState(
                    hasWorkspace = hostPath != null,
                    projectName = projectName,
                    hostPath = hostPath,
                    branch = branch,
                    isGitRepo = gitInfo?.isRepo == true,
                    hasChanges = gitInfo?.hasChanges == true,
                    dirtyFileCount = gitInfo?.dirtyFileCount ?: 0,
                    ahead = gitInfo?.ahead ?: 0,
                    candidates = remember(hostPath) { viewModel.workspaceCandidates() },
                    onSelectWorkspace = { viewModel.selectProject(it) },
                    onOpenFolder = { workspacePicker.launch(null) },
                    onOpenRemote = { showRemoteDialog = true },
                    onPickBranch = { showBranchDialog = true },
                    onGitActions = {
                        dirtyFilesCache = emptyList()
                        viewModel.refreshGitInfo()
                        showGitActions = true
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    ComposerBlock(flat = true)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    reverseLayout = true,
                ) {
                    items(
                        items = timelineReversed,
                        key = { it.stableId },
                    ) { item ->
                        when (item) {
                            is TimelineItem.Message -> {
                                val isLastAssistant = item.message.role == "assistant" &&
                                    !item.message.isStreaming &&
                                    item.stableId == lastAssistantStableId
                                MessageBubble(
                                    message = item.message,
                                    onCopy = if (!item.message.isStreaming && item.message.content.isNotBlank()) {
                                        {
                                            if (item.message.role == "user") {
                                                viewModel.copyMessage(item.message.content)
                                            } else {
                                                viewModel.copyTurnLog(item.message.id)
                                            }
                                        }
                                    } else null,
                                    onBranch = if (
                                        item.message.role == "assistant" &&
                                        !item.message.isStreaming &&
                                        !item.message.isProcess &&
                                        !isLoading
                                    ) {
                                        { viewModel.branchFromMessage(item.message.id) }
                                    } else null,
                                    onRegenerate = if (isLastAssistant && !isLoading) {
                                        { viewModel.regenerate() }
                                    } else null,
                                    onEdit = if (
                                        item.message.role == "user" &&
                                        !isLoading &&
                                        item.message.content.isNotBlank()
                                    ) {
                                        {
                                            val text = viewModel.beginEditUserMessage(item.message.id)
                                            if (text != null) inputText = text
                                        }
                                    } else null,
                                    isEditing = item.message.id == editingMessageId,
                                )
                            }
                            is TimelineItem.Tool -> ToolCallCard(item.tool)
                            is TimelineItem.ToolGroup -> ToolGroupCard(item.tools)
                            is TimelineItem.Reasoning -> ReasoningCard(item.item)
                            is TimelineItem.Working -> WorkingIndicator()
                            is TimelineItem.Approval -> ApprovalTimelineCard(
                                item = item.item,
                                onAllow = if (item.item.status == "pending") {
                                    { viewModel.resolveApproval(true) }
                                } else null,
                                onDeny = if (item.item.status == "pending") {
                                    { viewModel.resolveApproval(false) }
                                } else null,
                            )
                            is TimelineItem.SubAgent -> SubAgentTimelineCard(item = item.agent)
                        }
                    }
                }

                AnimatedVisibility(
                    visible = error != null,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    error?.let {
                        Snackbar(
                            modifier = Modifier.padding(8.dp),
                            action = {
                                androidx.compose.material3.TextButton(
                                    onClick = { viewModel.clearError() },
                                ) {
                                    Text("关闭")
                                }
                            },
                        ) {
                            Text(it)
                        }
                    }
                }

                if (goal.hasGoal) {
                    GoalStrip(goal = goal)
                }
                if (planSteps.isNotEmpty()) {
                    PlanStrip(steps = planSteps)
                }
                if (subAgents.isNotEmpty()) {
                    SubAgentStrip(agents = subAgents)
                }
                if (mcpStatus.isNotEmpty()) {
                    McpStatusStrip(servers = mcpStatus)
                }
                ContextUsageBar(tokens = contextTokens, window = contextWindow, lastTurnTokens = tokenUsage.lastTotal)
                pendingApproval?.let { req ->
                    when (req.kind) {
                        "ask_user" -> AskUserQuestionPanel(
                            request = req,
                            onSubmit = { viewModel.resolveUserQuestion(it) },
                            onCancel = { viewModel.resolveApproval(false) },
                        )
                        "exit_plan" -> ExitPlanApprovalPanel(
                            request = req,
                            onAllow = { viewModel.resolveApproval(true) },
                            onDeny = { viewModel.resolveApproval(false) },
                        )
                        else -> ApprovalBanner(
                            request = req,
                            onAllow = { viewModel.resolveApproval(true) },
                            onDeny = { viewModel.resolveApproval(false) },
                        )
                    }
                }

                if (queue.isNotEmpty()) {
                    QueueStrip(
                        queue = queue,
                        onRemove = { viewModel.removeFromQueue(it) },
                        onSendNow = { viewModel.sendQueuedNow(it) },
                        canSendNow = !isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                    )
                }

                ComposerBlock()
            }
        } // end Column

            // ── 浮层：文件 / 设置（全屏）；终端用底部 sheet，见下方 ──
            if (showFiles) {
                FilesScreen(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    initialPath = filesInitialPath,
                )
            }
            if (showSettings) {
                SettingsScreen(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    onClose = {
                        showSettings = false
                        settingsInitialPage = SettingsPage.HOME
                    },
                    initialPage = settingsInitialPage,
                )
            }

            CommandCenterSheet(
                open = showCommandCenter,
                onDismiss = { showCommandCenter = false },
                items = defaultCommandCenterItems(
                    onNewTask = {
                        showCommandCenter = false
                        viewModel.createConversation()
                    },
                    onOpenFiles = {
                        showCommandCenter = false
                        fileTreeRequestPath = null
                        fileTreeRequestKey += 1
                        drawerOpen = true
                    },
                    onOpenSettings = {
                        showCommandCenter = false
                        settingsInitialPage = SettingsPage.HOME
                        showSettings = true
                    },
                    onOpenSkills = {
                        showCommandCenter = false
                        settingsInitialPage = SettingsPage.SKILLS
                        showSettings = true
                    },
                    onOpenTerminal = {
                        showCommandCenter = false
                        showTerminal = !showTerminal
                    },
                    onToggleSidebar = {
                        showCommandCenter = false
                        drawerOpen = !drawerOpen
                    }
                ),
            )
        } // end Box

        if (showTerminal) {
            val terminalColors = rememberTerminalColors()
            val terminalSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val terminalHeight = LocalConfiguration.current.screenHeightDp.dp * 0.72f
            ModalBottomSheet(
                onDismissRequest = { showTerminal = false },
                sheetState = terminalSheetState,
                containerColor = terminalColors.background,
                contentColor = terminalColors.foreground,
                dragHandle = null,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            ) {
                TerminalScreen(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(terminalHeight),
                    onClose = { showTerminal = false },
                    colors = terminalColors,
                )
            }
        }

        // ── 分支切换对话框（ZCode：分支作为工作区上下文）──
        if (showBranchDialog) {
            BranchSwitchDialog(
                currentBranch = branch,
                dirtyFileCount = gitInfo?.dirtyFileCount ?: 0,
                hasIdentity = gitInfo?.hasIdentity != false,
                onListBranches = { viewModel.listBranches() },
                onSwitchBranch = { name, create, cb -> viewModel.switchBranch(name, create, cb) },
                onCommitAndSwitch = { name, create, msg, cb ->
                    viewModel.commitAndSwitchBranch(name, create, msg, cb)
                },
                onGenerateCommitMessage = { target, cb ->
                    viewModel.generateCommitMessage(targetBranch = target, onDone = cb)
                },
                onDismiss = { showBranchDialog = false },
            )
        }

        if (showGitActions && gitInfo != null) {
            GitActionDialog(
                gitInfo = gitInfo!!,
                dirtyFiles = dirtyFilesCache,
                onListDirty = {
                    val files = viewModel.listChangedFiles()
                    dirtyFilesCache = files
                    files
                },
                onGenerateCommitMessage = { cb ->
                    viewModel.generateCommitMessage(onDone = cb)
                },
                onCommit = { message, cb -> viewModel.commitWorkspace(message, cb) },
                onPush = { cb -> viewModel.pushBranch(cb) },
                onCommitAndPush = { message, cb -> viewModel.commitAndPush(message, cb) },
                onDismiss = { showGitActions = false },
            )
        }

        if (showRemoteDialog) {
            val remoteProfiles by viewModel.remoteProfiles.collectAsState()
            val activeRemoteId by viewModel.activeRemoteId.collectAsState()
            RemoteWorkspaceDialog(
                profiles = remoteProfiles,
                activeRemoteId = activeRemoteId,
                onSave = { viewModel.saveRemoteProfile(it) },
                onDelete = { viewModel.deleteRemoteProfile(it) },
                onConnect = { spec, cb -> viewModel.connectRemote(spec, cb) },
                onListDir = { spec, path, cb -> viewModel.listRemoteDir(spec, path, cb) },
                onOpenWorkspace = { spec, path, cb -> viewModel.openRemoteWorkspace(spec, path, cb) },
                onDismiss = { showRemoteDialog = false },
            )
        }
    }
}

/**
 * ZCode 对齐空状态：问候语 + 与 Composer 直接相连的工作区/分支条（左项目、右分支）。
 */
@Composable
private fun EmptyConversationState(
    hasWorkspace: Boolean,
    projectName: String,
    hostPath: String?,
    branch: String,
    isGitRepo: Boolean,
    hasChanges: Boolean,
    dirtyFileCount: Int,
    ahead: Int = 0,
    candidates: List<String>,
    onSelectWorkspace: (String) -> Unit,
    onOpenFolder: () -> Unit,
    onOpenRemote: () -> Unit = {},
    onPickBranch: () -> Unit,
    onGitActions: () -> Unit = {},
    modifier: Modifier = Modifier,
    composer: @Composable () -> Unit,
) {
    // 问候语 + 输入卡片作为同一主体居中。
    // 键盘只依赖父级连续 imePadding，不做可见性分支/布局切换，避免收起时回弹闪一下。
    Box(
        modifier = modifier
            .fillMaxSize()
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                emptyGreeting(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (hasWorkspace) {
                Text(
                    buildString {
                        append("开始在 ")
                        append(projectName)
                        append(" 项目新建任务")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 20.dp),
                )
            } else {
                Text(
                    "选择一个工作区后开始新任务",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 20.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                EmptyWorkspaceHeader(
                    hasWorkspace = hasWorkspace,
                    projectName = projectName,
                    hostPath = hostPath,
                    branch = branch,
                    isGitRepo = isGitRepo,
                    hasChanges = hasChanges,
                    dirtyFileCount = dirtyFileCount,
                    ahead = ahead,
                    candidates = candidates,
                    onSelectWorkspace = onSelectWorkspace,
                    onOpenFolder = onOpenFolder,
                    onOpenRemote = onOpenRemote,
                    onPickBranch = onPickBranch,
                    onGitActions = onGitActions,
                    modifier = Modifier.fillMaxWidth(),
                )
                composer()
            }
        }
    }
}

private fun emptyGreeting(): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..8 -> "早上好呀，新的一天开始啦"
        in 9..11 -> "上午好呀，有什么想让我帮忙的吗"
        in 12..13 -> "中午好呀，要不要先休息一下"
        in 14..17 -> "下午好呀，继续加油，好不好"
        in 18..22 -> "晚上好呀，今天辛苦啦"
        else -> "夜深啦，困了也要照顾好自己哦"
    }
}

