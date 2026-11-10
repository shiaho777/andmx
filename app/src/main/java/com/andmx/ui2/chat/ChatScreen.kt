package com.andmx.ui2.chat

import com.andmx.ui2.settings.UiLang
import com.andmx.ui2.settings.tr

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import com.andmx.ui2.theme.Chrome
import com.andmx.ui2.theme.LocalMotion
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Commit
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
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
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

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
    val goalVerifications by viewModel.goalVerifications.collectAsState()
    val mcpStatus by viewModel.mcpStatus.collectAsState()
    val backgroundTasks by viewModel.backgroundTasks.collectAsState()
    val contextTokens by viewModel.contextTokens.collectAsState()
    val contextWindow by viewModel.contextWindow.collectAsState()
    val contextBreakdown by viewModel.contextBreakdown.collectAsState()
    val tokenUsage by viewModel.tokenUsage.collectAsState()
    val goal by viewModel.goal.collectAsState()
    val lastTurnMetrics by viewModel.lastTurnMetrics.collectAsState()
    val compactNotice by viewModel.compactNotice.collectAsState()
    val error by viewModel.error.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val editingMessageId by viewModel.editingMessageId.collectAsState()
    val context = LocalContext.current
    val draftStore = remember { DraftStore(context) }
    val scrollMemory = remember { ChatScrollMemory(context) }
    val previewFileExists = remember(viewModel) { viewModel::previewFileExists }
    val motion = LocalMotion.current
    // 移动端惯例：冷启动不弹权限；首个任务发出（可能后台跑完）时才请求通知权限。
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(isLoading) {
        if (isLoading && android.os.Build.VERSION.SDK_INT >= 33 &&
            !com.andmx.ui2.chat.TurnNotifier.notifPermissionAsked &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            com.andmx.ui2.chat.TurnNotifier.notifPermissionAsked = true
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val settingsStore = remember { SettingsStore(context) }
    val appSettings by settingsStore.settings.collectAsState(initial = ProviderSettings())
    val visibleReasonings = remember(messages, reasonings, appSettings.showReasoning) {
        if (appSettings.showReasoning) reasonings
        else firstReasoningPerTurn(messages, reasonings)
    }
    val turnCodeComments = remember(messages) { assistantCodeCommentsByTail(messages) }
    val visibleTools = remember(toolCalls, appSettings.showTodos) {
        if (appSettings.showTodos) toolCalls
        else toolCalls.filterNot { tool ->
            val n = tool.name.lowercase()
            n.contains("todo") || n == "update_plan"
        }
    }
    val pendingApproval by viewModel.pendingApproval.collectAsState()
    val pendingYoloConfirmation by viewModel.pendingYoloConfirmation.collectAsState()
    val timeline = remember(
        messages, visibleTools, approvals, subAgentItems, visibleReasonings, goalVerifications,
        isLoading, pendingApproval, appSettings.toolGroupingExplore, appSettings.toolGroupingTerminal,
        appSettings.toolGroupingChanges,
    ) {
        val hasLiveStream = messages.any { it.isStreaming } || visibleReasonings.any { it.isStreaming }
        val hasRunningTool = visibleTools.any { it.isRunning }
        val hasVerifying = goalVerifications.any { it.passed == null }
        // ZCode shouldShowTurnChatLoading：审批/问答挂起时抑制底部 loading。
        val hasPendingInteraction = pendingApproval != null
        buildTimeline(
            messages = messages,
            tools = visibleTools,
            approvals = approvals,
            subAgents = subAgentItems,
            reasonings = visibleReasonings,
            goalVerifications = goalVerifications,
            showWorking = isLoading && !hasLiveStream && !hasRunningTool && !hasVerifying &&
                !hasPendingInteraction && visibleReasonings.none { it.isStreaming },
            groupExplore = appSettings.toolGroupingExplore,
            groupTerminal = appSettings.toolGroupingTerminal,
            groupChanges = appSettings.toolGroupingChanges,
        )
    }
    // Turn 过程折叠（dsh web turn-process 对齐）：已关闭 Turn 的过程行默认
    // 收起为摘要行，手动展开的 Turn 记录在本会话状态里。
    val turnFolds = remember(timeline) { TurnProcessFolding.folds(timeline) }
    val foldConversationId by viewModel.currentConversationId.collectAsState()
    var manuallyExpandedFolds by remember(foldConversationId) { mutableStateOf(setOf<Long>()) }
    val visibleTimeline = remember(timeline, turnFolds, manuallyExpandedFolds) {
        TurnProcessFolding.visibleTimeline(timeline, turnFolds, manuallyExpandedFolds)
    }
    val timelineReversed = remember(visibleTimeline) { visibleTimeline.asReversed() }
    val planSteps by viewModel.planSteps.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val queuePaused by viewModel.queuePaused.collectAsState()
    val steeringMessage by viewModel.steeringMessage.collectAsState()
    val config by viewModel.composerConfig.collectAsState()
    val planOverlay by viewModel.planOverlayActive.collectAsState()
    val fileChanges by viewModel.fileChanges.collectAsState()
    val rewindResult by viewModel.rewindResult.collectAsState()
    val rewindPickerOpen by viewModel.rewindPickerOpen.collectAsState()
    val fileRewindOpen by viewModel.fileRewindOpen.collectAsState()
    val recoverableQueue by viewModel.recoverableQueue.collectAsState()
    val rewindCheckpoints by viewModel.rewindCheckpoints.collectAsState()
    val workflowsOpen by viewModel.workflowsOpen.collectAsState()
    val workflowDefs by viewModel.workflowDefs.collectAsState()
    val retryStatus by viewModel.retryStatus.collectAsState()
    val workflowRuns by viewModel.workflowRuns.collectAsState()
    val workflowDetail by viewModel.workflowDetail.collectAsState()
    val workflowDetailEvents by viewModel.workflowDetailEvents.collectAsState()
    val workflowOpenHoles by viewModel.workflowOpenHoles.collectAsState()
    val workflowArtifactView by viewModel.workflowArtifactView.collectAsState()
    val modelSwitchGuard by viewModel.modelSwitchGuard.collectAsState()
    val contextChips by viewModel.contextChips.collectAsState()
    val recentConversations by viewModel.recentConversations.collectAsState()
    val skills by viewModel.skills.collectAsState()
    val pluginSlashSpecs by viewModel.pluginSlashSpecs.collectAsState()
    val customSlashSpecs by viewModel.customSlashSpecs.collectAsState()
    val subAgentCatalog by viewModel.subAgentCatalog.collectAsState()

    // ZCode 对齐：对话为唯一主屏，终端/文件/设置均为浮层
    val projectName by viewModel.projectName.collectAsState()
    val conversationTitle by viewModel.conversationTitle.collectAsState()
    val gitInfo by viewModel.gitInfo.collectAsState()
    val hostPath by viewModel.hostPath.collectAsState()
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    val branch = gitInfo?.branch.orEmpty()

    LaunchedEffect(hostPath) {
        viewModel.refreshGitInfo()
    }

    var showTerminal by remember { mutableStateOf(false) }
    var showBackgroundTasks by remember { mutableStateOf(false) }
    var selectionError by remember { mutableStateOf<String?>(null) }
    var sideChatExcerpt by remember { mutableStateOf<String?>(null) }
    var showFiles by remember { mutableStateOf(false) }
    var filesInitialPath by remember { mutableStateOf<String?>(null) }
    var fileReview by remember { mutableStateOf<com.andmx.ui2.files.FileReviewAnchor?>(null) }
    var fileTreeRequestKey by remember { mutableStateOf(0) }
    var fileTreeRequestPath by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showCommandCenter by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findTick by remember { mutableIntStateOf(0) }
    var turnDelta by remember { mutableIntStateOf(0) }
    var modelMenuToken by remember { mutableIntStateOf(0) }
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
                fileReview = null
            }
            showSettings -> showSettings = false
        }
    }

    var drawerOpen by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var draftOwner by remember { mutableStateOf(0L) }

    LaunchedEffect(currentConversationId) {
        if (draftOwner != 0L && draftOwner != currentConversationId) {
            draftStore.save(draftOwner, inputText)
        }
        if (currentConversationId > 0L) {
            inputText = draftStore.load(currentConversationId)
        }
        draftOwner = currentConversationId
    }
    LaunchedEffect(inputText, currentConversationId) {
        if (currentConversationId <= 0L || draftOwner != currentConversationId) return@LaunchedEffect
        delay(400)
        draftStore.save(currentConversationId, inputText)
    }

    LaunchedEffect(Unit) {
        InboundBus.events.collect { event ->
            when (event) {
                is InboundBus.Event.Text -> {
                    inputText = if (inputText.isBlank()) event.text else "$inputText\n${event.text}"
                }
                is InboundBus.Event.Image -> {
                    attachments = attachments + Attachment(event.name, event.uri)
                }
                is InboundBus.Event.OpenConversation -> {
                    drawerOpen = false
                    showCommandCenter = false
                    showSettings = false
                    showFiles = false
                    showTerminal = false
                    viewModel.switchToConversation(event.id)
                }
            }
        }
    }

    // 文件树长按拖拽 → Composer 引用（ZCode 对齐）
    val fileDragState by com.andmx.ui2.files.FileDragBus.state.collectAsState()
    var composerBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    LaunchedEffect(fileDragState, composerBounds) {
        val s = fileDragState
        if (s.dragging) {
            val pos = s.position ?: return@LaunchedEffect
            val inside = composerBounds?.contains(pos) == true
            if (inside != s.overComposer) {
                com.andmx.ui2.files.FileDragBus.update(position = pos, overComposer = inside)
            }
        }
    }
    val dragPayloadNow = fileDragState.payload
    if (dragPayloadNow != null) {
        FileDragOverlay(
            payload = dragPayloadNow,
            overComposer = fileDragState.overComposer,
            onRelease = { released ->
                if (released) {
                    val s = fileDragState
                    if (s.overComposer) {
                        s.payload?.let { p ->
                            viewModel.addFileContext(p.relativePath.ifBlank { p.path })
                        }
                    }
                    com.andmx.ui2.files.FileDragBus.cancel()
                }
            },
        )
    }

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
                    fileReview = action.review
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
                ChatActionBus.Action.OpenPluginsSettings -> {
                    settingsInitialPage = SettingsPage.PLUGIN
                    showSettings = true
                }
                ChatActionBus.Action.OpenSearch -> showCommandCenter = true
                ChatActionBus.Action.OpenDrawer -> drawerOpen = true
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
            attachments = attachments + Attachment(name = attachmentDisplayName(context, uri), uri = uri.toString())
        }
    }
    val documentPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            attachments = attachments + Attachment(name = attachmentDisplayName(context, uri), uri = uri.toString())
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
    val slashSuggestions by remember(inputText, pluginSlashSpecs, customSlashSpecs) {
        derivedStateOf {
            val t = inputText
            // ZCode 对齐：中文 IME 的 、/／ 归一为 / 触发斜杠命令联想。
            val normalized = when {
                t.startsWith("/") -> t
                t.startsWith("、") || t.startsWith("／") -> "/" + t.drop(1)
                else -> null
            }
            if (normalized != null && !normalized.contains(' ')) {
                SlashCommands.suggestions(normalized, 8, extras = pluginSlashSpecs + customSlashSpecs)
            } else emptyList()
        }
    }
    // 上游 mode/model/effort-suggestion-panel：/<cmd> <partial> 的参数补全。
    val argSuggestions by remember(inputText, config, skills, workflowDefs) {
        derivedStateOf {
            val t = inputText
            val m = Regex("""^[/、／](\S+)\s+(\S*)$""").find(t) ?: return@derivedStateOf emptyList()
            val cmd = m.groupValues[1].lowercase()
            val q = m.groupValues[2].lowercase()
            val all: List<ArgSuggestion> = when (cmd) {
                "mode" -> ExecMode.entries.map {
                    ArgSuggestion(it.id, "${it.id} · ${it.label}", it.description)
                }
                "model" -> {
                    val p = config.primary
                        ?: config.providers.firstOrNull { it.id == config.settings.activeProviderId }
                        ?: config.providers.firstOrNull()
                    p?.models?.map { (id, def) ->
                        ArgSuggestion(id, def.displayName?.ifBlank { id } ?: id, p.name)
                    }.orEmpty()
                }
                "effort", "variant" -> {
                    val cfg = config.reasoning
                    val levels = when {
                        cfg == null -> emptyList()
                        cfg.levels.isNotEmpty() -> cfg.levels.map { it.id }
                        cfg.effortLevels.isNotEmpty() -> cfg.effortLevels
                        cfg.style == com.andmx.llm.provider.ReasoningStyle.EFFORT ->
                            listOf("minimal", "low", "medium", "high")
                        cfg.style == com.andmx.llm.provider.ReasoningStyle.THINKING ->
                            listOf("low", "medium", "high")
                        else -> emptyList()
                    }
                    val options = thoughtOptionsFor(cfg).ifEmpty {
                        (listOf("off") + levels).distinct().map { ThoughtOption(it, effortLabel(it)) }
                    }
                    val cur = config.settings.reasoningEffort.ifBlank { "off" }
                    options.map { opt ->
                        val current = opt.wireValue.equals(cur, ignoreCase = true) ||
                            (isThoughtOff(opt.wireValue) && isThoughtOff(cur))
                        ArgSuggestion(opt.wireValue, opt.label, if (current) "current" else "")
                    }
                }
                "locale", "language" -> listOf(
                    ArgSuggestion("system", "system", "跟随系统"),
                    ArgSuggestion("zh-CN", "zh-CN", "简体中文"),
                    ArgSuggestion("en-US", "en-US", "English"),
                )
                "skill" -> skills.map { ArgSuggestion(it.name, it.name, it.path.substringAfterLast('/')) }
                "expert" -> workflowDefs.map {
                    ArgSuggestion(it.definitionId, it.title.ifBlank { it.definitionId }, it.description.orEmpty())
                }
                else -> emptyList()
            }
            all.filter { q.isEmpty() || it.value.lowercase().contains(q) || it.label.lowercase().contains(q) }
                .take(8)
        }
    }
    LaunchedEffect(inputText) {
        if (Regex("""^/expert\s+\S*$""").containsMatchIn(inputText)) {
            viewModel.ensureWorkflowDefs()
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
    // ZCode 对齐：行尾 @query → 子代理 / 会话 / 插件命令 / 文件浏览 混合面板。
    // 上游 file-mention-panel 等价：索引文件参与 @ 行内补全。
    var indexedFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(hostPath) {
        indexedFiles = hostPath?.let {
            runCatching {
                com.andmx.workspace.WorkspaceIndex.get(context).listIndexedFiles(it)
            }.getOrNull()
        } ?: emptyList()
    }
    val mentionSuggestions by remember {
        derivedStateOf {
            val t = inputText
            val match = Regex("""(?:^|\s)@([^\s@]*)$""").find(t) ?: return@derivedStateOf emptyList()
            val q = match.groupValues[1].lowercase()
            buildList {
                subAgentCatalog
                    .filter {
                        it.enabled &&
                            (q.isBlank() || it.name.lowercase().contains(q) ||
                                it.description.lowercase().contains(q))
                    }
                    .take(4)
                    .forEach {
                        add(
                            MentionSuggestion(
                                kind = MentionKind.AGENT,
                                label = it.name,
                                subtitle = it.description.take(40),
                                payload = it.name,
                            ),
                        )
                    }
                recentConversations
                    .filter {
                        q.isBlank() || it.title.lowercase().contains(q) ||
                            it.subtitle.lowercase().contains(q)
                    }
                    .take(3)
                    .forEach {
                        add(
                            MentionSuggestion(
                                kind = MentionKind.SESSION,
                                label = it.title,
                                subtitle = it.subtitle,
                                conversationId = it.id,
                            ),
                        )
                    }
                pluginSlashSpecs
                    .filter {
                        q.isBlank() || it.name.lowercase().contains(q) ||
                            it.desc.lowercase().contains(q)
                    }
                    .take(3)
                    .forEach {
                        add(
                            MentionSuggestion(
                                kind = MentionKind.PLUGIN,
                                label = it.name,
                                subtitle = it.desc.take(40),
                                payload = it.name,
                            ),
                        )
                    }
                indexedFiles.asSequence()
                    .filter { q.isNotBlank() && it.lowercase().contains(q) }
                    .sortedBy { it.length }
                    .take(4)
                    .forEach { f ->
                        add(
                            MentionSuggestion(
                                kind = MentionKind.FILE,
                                label = f,
                                subtitle = f.substringBeforeLast('/', ""),
                                payload = f,
                            ),
                        )
                    }
                add(
                    MentionSuggestion(
                        kind = MentionKind.FILE_BROWSER,
                        label = "浏览文件…",
                        subtitle = "打开项目文件树",
                    ),
                )
            }.take(9)
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

    val runningConversationIds by viewModel.runningConversationIds.collectAsState()
    val streamingConversationIds = runningConversationIds

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
                .onPreviewKeyEvent { event ->
                    SessionShortcuts.applyStored(config.settings.shortcutBindings)
                    when (SessionShortcuts.screenAction(event)) {
                        SessionShortcuts.NEW_TASK -> {
                            viewModel.createConversation()
                            true
                        }
                        SessionShortcuts.TOGGLE_SIDEBAR -> {
                            drawerOpen = !drawerOpen
                            true
                        }
                        SessionShortcuts.OPEN_SETTINGS -> {
                            settingsInitialPage = com.andmx.ui2.settings.SettingsPage.HOME
                            showSettings = true
                            true
                        }
                        SessionShortcuts.OPEN_COMMAND_CENTER -> {
                            showCommandCenter = true
                            true
                        }
                        SessionShortcuts.TOGGLE_TERMINAL -> {
                            showTerminal = !showTerminal
                            true
                        }
                        SessionShortcuts.SWITCH_THEME -> {
                            viewModel.cycleTheme()
                            true
                        }
                        SessionShortcuts.OPEN_FILES -> {
                            showFiles = true
                            true
                        }
                        SessionShortcuts.FIND_IN_TASK -> {
                            findOpen = true
                            findTick++
                            true
                        }
                        SessionShortcuts.PREVIOUS_CONVERSATION -> {
                            viewModel.cycleConversation(1)
                            true
                        }
                        SessionShortcuts.NEXT_CONVERSATION -> {
                            viewModel.cycleConversation(-1)
                            true
                        }
                        SessionShortcuts.NAVIGATE_BACK -> {
                            turnDelta = 1
                            true
                        }
                        SessionShortcuts.NAVIGATE_FORWARD -> {
                            turnDelta = -1
                            true
                        }
                        SessionShortcuts.OPEN_MODEL_MENU -> {
                            modelMenuToken++
                            true
                        }
                        SessionShortcuts.ZOOM_IN -> {
                            viewModel.adjustUiFont(1)
                            true
                        }
                        SessionShortcuts.ZOOM_OUT -> {
                            viewModel.adjustUiFont(-1)
                            true
                        }
                        SessionShortcuts.ZOOM_RESET -> {
                            viewModel.adjustUiFont(null)
                            true
                        }
                        SessionShortcuts.CYCLE_MODE -> {
                            val order = listOf("confirm", "auto_edit", "plan", "full", "auto")
                            val cur = config.settings.approvalMode.ifBlank { "confirm" }
                            val idx = order.indexOf(cur).let { if (it < 0) 0 else it }
                            viewModel.setExecMode(ExecMode.from(order[(idx + 1) % order.size]))
                            true
                        }
                        SessionShortcuts.CYCLE_THOUGHT -> {
                            val options = thoughtOptionsFor(config.reasoning)
                            if (options.isEmpty()) return@onPreviewKeyEvent false
                            val cur = config.settings.reasoningEffort.ifBlank { "off" }
                            val idx = options.indexOfFirst {
                                it.wireValue.equals(cur, ignoreCase = true) ||
                                    (isThoughtOff(it.wireValue) && isThoughtOff(cur))
                            }
                            val next = options[(if (idx < 0) 0 else idx + 1) % options.size]
                            viewModel.setReasoningEffort(next.wireValue)
                            true
                        }
                        else -> false
                    }
                }
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .background(MaterialTheme.colorScheme.background)
            ) {

            // Composer 复用：空状态和对话状态共用同一套调用（局部闭包）
            val composerBoundsModifier = Modifier.onGloballyPositioned { coords ->
                composerBounds = coords.boundsInRoot()
            }
            var pendingHistoryJump by remember { mutableStateOf<Long?>(null) }
            val inputHistory = remember(messages) {
                messages.filter { it.role == "user" && it.content.isNotBlank() }
                    .takeLast(50)
                    .asReversed()
                    .map {
                        InputHistoryEntry(
                            messageId = it.id,
                            text = it.content.trim(),
                            sentAt = it.createdAt.takeIf { at -> at > 0L } ?: it.sortKey,
                        )
                    }
            }
            @Composable
            fun ComposerBlock(flat: Boolean = false, extraModifier: Modifier = Modifier) {
                val outerModifier = if (flat) composerBoundsModifier else extraModifier
                Composer(
                    modelMenuToken = modelMenuToken,
                    value = inputText,
                    onValueChange = { newValue ->
                        // ZCode 对齐：粘贴长文本转附件 chip，不进输入框。
                        val pasted = extractPastedSegment(inputText, newValue)
                        if (pasted != null) {
                            viewModel.addPastedText(pasted.text)
                            inputText = newValue.removeRange(pasted.start, pasted.endExclusive)
                        } else {
                            inputText = newValue
                        }
                    },
                    onSend = {
                        if (inputText.isNotBlank() || attachments.isNotEmpty() || contextChips.isNotEmpty()) {
                            viewModel.sendMessage(inputText, attachments = attachments)
                            inputText = ""
                            attachments = emptyList()
                        }
                    },
                    onSteer = if (isLoading) {
                        { text ->
                            viewModel.steerCurrentTurn(text)
                            inputText = ""
                        }
                    } else null,
                    steeringPending = steeringMessage != null,
                    onCancelSteer = { viewModel.cancelSteer() },
                    isLoading = isLoading,
                    onStop = { viewModel.stop() },
                    modelLabel = config.modelLabel,
                    selectedModel = config.settings.model,
                    activeProviderId = config.settings.activeProviderId
                        .ifBlank { config.primary?.id.orEmpty() },
                    providers = config.providers,
                    onSwitchModel = { pid, mid -> viewModel.requestSwitchModel(pid, mid) },
                    reasoningEffort = config.settings.reasoningEffort,
                    reasoning = config.reasoning,
                    onReasoningSelected = { viewModel.setReasoningEffort(it) },
                    execMode = if (planOverlay) ExecMode.PLAN else config.execMode,
                    onExecModeSelected = { viewModel.setExecMode(it) },
                    planOverlayActive = planOverlay,
                    onExitPlanMode = { viewModel.exitPlanMode() },
                    contextChips = contextChips,
                    onRemoveContextChip = { viewModel.removeContextChip(it) },
                    attachments = attachments,
                    onRemoveAttachment = { i ->
                        attachments = attachments.filterIndexed { idx, _ -> idx != i }
                    },
                    onAddAttachment = { imagePicker.launch("image/*") },
                    onAddDocument = { documentPicker.launch("*/*") },
                    onPasteClipboardImage = { uri ->
                        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "剪贴板图片"
                        attachments = attachments + Attachment(name = name, uri = uri.toString())
                    },
                    onInsertMention = {
                        fileTreeRequestPath = null
                        fileTreeRequestKey += 1
                        drawerOpen = true
                    },
                    onInsertConversation = { insertAtCursor("#") },
                    onInsertCommand = { insertAtCursor("/") },
                    onInsertSkill = { insertAtCursor("\$") },
                    slashSuggestions = slashSuggestions,
                    argSuggestions = argSuggestions,
                    onPickArg = { sug ->
                        inputText = Regex("""^[/、／](\S+)\s+\S*$""").replace(inputText) { mr ->
                            "/${mr.groupValues[1]} ${sug.value}"
                        }
                    },
                    onPickSlash = { spec ->
                        val raw = spec.name.trim()
                        val bare = raw.removePrefix("/")
                        inputText = Regex("""^[/、／]\S*$""").replace(inputText, "").trimEnd()
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
                    inputHistory = inputHistory,
                    placeholder = tr(
                        composerPlaceholder(
                            flat = flat,
                            isLoading = isLoading,
                            guide = appSettings.interactionBehavior == "guide",
                        ),
                    ),
                    onPickHistory = { pendingHistoryJump = it.messageId },
                    mentionSuggestions = mentionSuggestions,
                    onPickMention = { m ->
                        inputText = Regex("""(?:^|\s)@[^\s@]*$""").replace(inputText) { mr ->
                            mr.value.takeWhile { it.isWhitespace() }
                        }.trimEnd()
                        when (m.kind) {
                            MentionKind.AGENT -> viewModel.addAgentContext(m.payload)
                            MentionKind.SESSION -> viewModel.addConversationContext(
                                ConversationPick(id = m.conversationId, title = m.label),
                            )
                            MentionKind.PLUGIN -> viewModel.addCommandByName(m.payload)
                            MentionKind.FILE -> viewModel.addFileContext(m.payload)
                            MentionKind.FILE_BROWSER -> {
                                fileTreeRequestPath = null
                                fileTreeRequestKey += 1
                                drawerOpen = true
                            }
                        }
                    },
                    flat = flat,
                    modifier = outerModifier
                        .fillMaxWidth()
                        .then(if (flat) Modifier else Modifier.padding(horizontal = Chrome.gutter, vertical = 8.dp)),
                )
            }

            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (timeline.isEmpty()) "新任务"
                            else conversationTitle.ifBlank { projectName.ifBlank { "AndMX" } },
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        if (timeline.isNotEmpty() && projectName.isNotBlank()) {
                            Text(
                                projectName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { drawerOpen = !drawerOpen }) {
                        Icon(Icons.Outlined.Menu, "切换侧边栏")
                    }
                },
                // ZCode：终端从对话头部右上角唤起（再点收起）
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
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
                    IconButton(onClick = { viewModel.shareConversation() }) {
                        Icon(Icons.Outlined.Share, "分享对话")
                    }
                    if (gitInfo?.isRepo == true) {
                        IconButton(onClick = {
                            dirtyFilesCache = emptyList()
                            viewModel.refreshGitInfo()
                            showGitActions = true
                        }) {
                            Icon(Icons.Outlined.Commit, "Git 操作")
                        }
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
                val starters = remember(
                    hostPath,
                    projectName,
                    gitInfo?.isRepo,
                    gitInfo?.hasChanges,
                    gitInfo?.dirtyFileCount,
                    gitInfo?.ahead,
                    UiLang.tag,
                ) {
                    starterSuggestions(
                        hasWorkspace = hostPath != null,
                        projectName = projectName,
                        isGitRepo = gitInfo?.isRepo == true,
                        hasChanges = gitInfo?.hasChanges == true,
                        dirtyFileCount = gitInfo?.dirtyFileCount ?: 0,
                        ahead = gitInfo?.ahead ?: 0,
                    )
                }
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
                    suggestions = starters,
                    onPickSuggestion = { inputText = it },
                    modifier = Modifier.weight(1f),
                ) {
                    ComposerBlock(flat = true)
                }
            } else {
                val listState = rememberLazyListState()
                var scrollRestoredFor by remember { mutableStateOf(0L) }
                LaunchedEffect(currentConversationId, timeline.size) {
                    if (scrollRestoredFor == currentConversationId) return@LaunchedEffect
                    if (timeline.isEmpty() && currentConversationId > 0L) return@LaunchedEffect
                    val saved = scrollMemory.load(currentConversationId)
                    runCatching {
                        if (saved != null) listState.scrollToItem(saved.index, saved.offset)
                        else listState.scrollToItem(0)
                    }
                    scrollRestoredFor = currentConversationId
                }
                LaunchedEffect(currentConversationId, listState) {
                    snapshotFlow {
                        listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                    }.collectLatest { (index, offset) ->
                        delay(400)
                        scrollMemory.save(currentConversationId, index, offset)
                    }
                }
                LaunchedEffect(pendingHistoryJump, timelineReversed) {
                    val id = pendingHistoryJump ?: return@LaunchedEffect
                    val index = timelineReversed.indexOfFirst {
                        it is TimelineItem.Message && it.message.role == "user" && it.message.id == id
                    }
                    if (index >= 0) listState.scrollAnchorToTop(index)
                    pendingHistoryJump = null
                }
                val userAnchorIndices = remember(timelineReversed) {
                    timelineReversed.mapIndexedNotNull { i, it ->
                        if (it is TimelineItem.Message && it.message.role == "user") i else null
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                if (findOpen) {
                    Row(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = findQuery,
                            onValueChange = { findQuery = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { findTick++ }) { Text(tr("下一个")) }
                        TextButton(onClick = { findOpen = false; findQuery = "" }) { Text(tr("关闭")) }
                    }
                }
                LaunchedEffect(findTick) {
                    if (!findOpen || findQuery.isBlank()) return@LaunchedEffect
                    val q = findQuery.trim()
                    val index = timelineReversed.indexOfFirst {
                        it is TimelineItem.Message && it.message.content.contains(q, ignoreCase = true)
                    }
                    if (index >= 0) listState.scrollAnchorToTop(index)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth(),
                    reverseLayout = true,
                ) {
                    items(
                        items = timelineReversed,
                        key = { it.stableId },
                    ) { item ->
                        Box(Modifier.animateItem()) {
                            when (item) {
                                is TimelineItem.Message -> {
                                    if (item.message.role == "compact") {
                                        CompactMarkerRow(item.message.content)
                                        return@Box
                                    }
                                    MessageBubble(
                                        message = item.message,
                                        fileExists = previewFileExists,
                                        mergedCodeComments = turnCodeComments[item.message.id],
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
                                        onRegenerate = if (
                                            item.message.role == "assistant" &&
                                            !item.message.isStreaming &&
                                            !item.message.isProcess &&
                                            !isLoading
                                        ) {
                                            { viewModel.retryFromAssistant(item.message.id) }
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
                                        onQuote = if (
                                            !item.message.isStreaming &&
                                            !item.message.isProcess &&
                                            item.message.content.isNotBlank()
                                        ) {
                                            {
                                                selectionError = viewModel.addMessageSelection(
                                                    item.message.id,
                                                    item.message.role,
                                                    item.message.content,
                                                )
                                            }
                                        } else null,
                                        onSideChat = if (
                                            item.message.role == "assistant" &&
                                            !item.message.isStreaming &&
                                            !item.message.isProcess &&
                                            item.message.content.isNotBlank() &&
                                            !isLoading
                                        ) {
                                            { sideChatExcerpt = item.message.content }
                                        } else null,
                                        onFeedback = if (
                                            item.message.role == "assistant" &&
                                            !item.message.isStreaming &&
                                            !item.message.isProcess
                                        ) {
                                            { v -> viewModel.setMessageFeedback(item.message.id, v) }
                                        } else null,
                                    )
                                }
                                is TimelineItem.Tool -> ToolCallCard(
                                    item.tool,
                                    workflowStatus = { viewModel.workflowStatusLine(it) },
                                )
                                is TimelineItem.ToolGroup -> ToolGroupCard(item.tools)
                                is TimelineItem.Reasoning -> ReasoningCard(item.item)
                                is TimelineItem.Working -> WorkingIndicator(retryStatus)
                                is TimelineItem.TurnProcess -> TurnProcessRow(
                                    fold = item.fold,
                                    expanded = item.fold.sortKey in manuallyExpandedFolds,
                                    onToggle = {
                                        manuallyExpandedFolds = if (item.fold.sortKey in manuallyExpandedFolds) {
                                            manuallyExpandedFolds - item.fold.sortKey
                                        } else {
                                            manuallyExpandedFolds + item.fold.sortKey
                                        }
                                    },
                                )
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
                                is TimelineItem.GoalVerify -> GoalVerifyRow(item = item.item)
                            }
                        }
                    }
                }
                val timelineScope = rememberCoroutineScope()
                if (userAnchorIndices.size > 1) {
                    var rank by remember(userAnchorIndices) { mutableIntStateOf(0) }
                    var jumping by remember(userAnchorIndices) { mutableStateOf(false) }
                    LaunchedEffect(listState, userAnchorIndices) {
                        snapshotFlow {
                            val visible = listState.layoutInfo.visibleItemsInfo
                            navigatorRank(
                                anchors = userAnchorIndices,
                                topVisibleIndex = visible.maxOfOrNull { it.index } ?: 0,
                                atBottom = listState.firstVisibleItemIndex == 0 &&
                                    listState.firstVisibleItemScrollOffset == 0,
                            )
                        }.collect { derived ->
                            if (!jumping) rank = derived
                        }
                    }
                    fun jumpTo(next: Int) {
                        val clamped = next.coerceIn(0, userAnchorIndices.lastIndex)
                        if (clamped == rank && clamped != 0) return
                        rank = clamped
                        jumping = true
                        timelineScope.launch {
                            try {
                                if (clamped == 0) {
                                    listState.animateScrollToItem(0)
                                } else {
                                    listState.scrollAnchorToTop(userAnchorIndices[clamped])
                                }
                            } finally {
                                jumping = false
                            }
                        }
                    }
                    LaunchedEffect(turnDelta) {
                        if (turnDelta == 0) return@LaunchedEffect
                        val dir = turnDelta
                        turnDelta = 0
                        jumpTo(rank + dir)
                    }
                    TurnNavigator(
                        turn = userAnchorIndices.size - rank,
                        total = userAnchorIndices.size,
                        onPrev = { jumpTo(rank + 1) },
                        onNext = { jumpTo(rank - 1) },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 14.dp, bottom = 6.dp),
                    )
                }
                // ZCode ConversationBackToBottomButton：滚离底部后显示回底按钮。
                val showBackToBottom by remember {
                    derivedStateOf {
                        listState.firstVisibleItemIndex > 0 ||
                            listState.firstVisibleItemScrollOffset > 200
                    }
                }
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp),
                ) {
                AnimatedVisibility(
                    visible = showBackToBottom,
                    enter = fadeIn(motion.enter) + slideInVertically(motion.enterOffset) { it / 3 },
                    exit = fadeOut(motion.enter) + slideOutVertically(motion.enterOffset) { it / 3 },
                ) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(16.dp),
                            )
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                            )
                            .clickable {
                                timelineScope.launch { listState.animateScrollToItem(0) }
                            }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.KeyboardArrowDown,
                            "回到底部",
                            Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        )
                        Text(tr("回到底部"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 2.dp),
                        )
                    }
                }
                }
                }

                AnimatedVisibility(
                    visible = error != null,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    enter = fadeIn(motion.enter),
                    exit = fadeOut(motion.enter),
                ) {
                    error?.let {
                        Snackbar(
                            modifier = Modifier.padding(8.dp),
                            action = {
                                androidx.compose.material3.TextButton(
                                    onClick = { viewModel.clearError() },
                                ) {
                                    Text(tr("关闭"))
                                }
                            },
                        ) {
                            Text(it)
                        }
                    }
                }

                AnimatedVisibility(
                    visible = selectionError != null,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    enter = fadeIn(motion.enter),
                    exit = fadeOut(motion.enter),
                ) {
                    selectionError?.let {
                        Snackbar(
                            modifier = Modifier.padding(8.dp),
                            action = {
                                androidx.compose.material3.TextButton(
                                    onClick = { selectionError = null },
                                ) {
                                    Text(tr("知道了"))
                                }
                            },
                        ) {
                            Text(it)
                        }
                    }
                }

                if (compactNotice?.running == true) {
                    CompactMarkerRow(compactNotice?.text ?: "正在压缩上下文")
                }
                LaunchedEffect(currentConversationId, isLoading) {
                    viewModel.refreshWorkflowRuns()
                }
                StatusCapsule(
                    goal = goal,
                    planSteps = planSteps,
                    workflows = workflowRuns,
                    onOpenWorkflows = { viewModel.openWorkflows() },
                    subAgents = subAgentItems,
                    gitInfo = gitInfo,
                    contextTokens = contextTokens,
                    contextWindow = contextWindow,
                    breakdown = contextBreakdown,
                    displayMode = appSettings.summaryPanelDisplayMode,
                    onCompress = { viewModel.compressContext() },
                    turnMetrics = lastTurnMetrics,
                    compactRunning = compactNotice?.running == true,
                    compactResult = compactNotice?.takeIf { !it.running }?.text,
                )
                PendingCommandRecoveryBanner(
                    items = recoverableQueue,
                    onRecover = { viewModel.recoverPendingQueue() },
                    onDiscard = { viewModel.discardPendingQueue() },
                )
                RewindBar(
                    changes = fileChanges,
                    rewindResult = rewindResult,
                    onOpen = { viewModel.openFileRewind() },
                    onDismissResult = { viewModel.clearRewindResult() },
                )
                sideChatExcerpt?.let { excerpt ->
                    SideChatDialog(
                        excerpt = excerpt,
                        onShare = { viewModel.sharePlainText(tr("AndMX 选段"), it) },
                        onSubmit = { q ->
                            sideChatExcerpt = null
                            viewModel.startSideChat(excerpt, q)
                        },
                        onDismiss = { sideChatExcerpt = null },
                    )
                }
                if (pendingYoloConfirmation) {
                    YoloModeConfirmDialog(
                        onConfirm = { viewModel.confirmYoloMode() },
                        onDismiss = { viewModel.cancelYoloMode() },
                    )
                }
                if (fileRewindOpen) {
                    FileRewindDialog(
                        changes = fileChanges,
                        onRevertOne = { viewModel.revertSingleFile(it) },
                        onRevertAll = {
                            viewModel.rewindFiles()
                            viewModel.dismissFileRewind()
                        },
                        onDismiss = { viewModel.dismissFileRewind() },
                    )
                }
                if (rewindPickerOpen) {
                    RewindPickerDialog(
                        checkpoints = rewindCheckpoints,
                        onPick = { viewModel.rewindToCheckpoint(it) },
                        onDismiss = { viewModel.dismissRewindPicker() },
                    )
                }
                if (workflowsOpen) {
                    WorkflowsDialog(
                        defs = workflowDefs,
                        runs = workflowRuns,
                        onPickRun = { viewModel.openWorkflowDetail(it) },
                        onDismiss = { viewModel.dismissWorkflows() },
                    )
                }
                workflowDetail?.let { snap ->
                    WorkflowRunDetailDialog(
                        snapshot = snap,
                        events = workflowDetailEvents,
                        openHoles = workflowOpenHoles,
                        onRefresh = { viewModel.openWorkflowDetail(snap.runId) },
                        onOpenArtifact = { label, path -> viewModel.openWorkflowArtifact(label, path) },
                        onCancel = { viewModel.cancelWorkflowRun(it) },
                        onResume = { viewModel.resumeWorkflowRun(it) },
                        onDismiss = { viewModel.dismissWorkflowDetail() },
                    )
                }
                workflowArtifactView?.let { artifact ->
                    WorkflowArtifactDialog(
                        artifact = artifact,
                        onDismiss = { viewModel.dismissWorkflowArtifact() },
                    )
                }
                modelSwitchGuard?.let { g ->
                    ModelSwitchGuardDialog(
                        guard = g,
                        onCompressAndSwitch = { viewModel.confirmSwitchModel(compressFirst = true) },
                        onForceSwitch = { viewModel.confirmSwitchModel(compressFirst = false) },
                        onCancel = { viewModel.cancelSwitchModel() },
                    )
                }
                if (mcpStatus.isNotEmpty()) {
                    McpStatusStrip(servers = mcpStatus)
                }
                pendingApproval?.let { req ->
                    when (req.kind) {
                        "ask_user" -> AskUserQuestionPanel(
                            request = req,
                            onSubmit = { viewModel.resolveUserQuestion(it) },
                            onCancel = { viewModel.resolveApproval(false) },
                            onSnooze = { viewModel.snoozeUserQuestion() },
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
                            onScope = { viewModel.resolveApprovalScoped(true, it) },
                        )
                    }
                }

                if (queue.isNotEmpty()) {
                    QueueStrip(
                        queue = queue,
                        paused = queuePaused,
                        onResume = { viewModel.resumeQueue() },
                        onRemove = { viewModel.removeFromQueue(it) },
                        onSendNow = { viewModel.sendQueuedNow(it) },
                        onEdit = { i ->
                            viewModel.takeQueuedForEdit(i)?.let { inputText = it }
                        },
                        onMove = { index, delta -> viewModel.moveQueued(index, delta) },
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
                    review = fileReview,
                    conversationId = currentConversationId,
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
                tasks = recentConversations.map {
                    com.andmx.ui2.drawer.CommandCenterTask(
                        id = it.id,
                        title = it.title,
                        subtitle = it.subtitle,
                    )
                },
                files = indexedFiles,
                onOpenTask = { id ->
                    showCommandCenter = false
                    viewModel.switchToConversation(id)
                },
                onInsertFile = { path ->
                    showCommandCenter = false
                    viewModel.addFileContext(path)
                },
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
                    },
                    onOpenBackgroundTasks = {
                        showCommandCenter = false
                        showBackgroundTasks = true
                    },
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
                shape = Chrome.sheet,
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

        if (showBackgroundTasks) {
            BackgroundTasksDialog(
                tasks = backgroundTasks,
                onDismiss = { showBackgroundTasks = false },
                onStop = { viewModel.stopBackgroundTask(it) },
                loadOutput = { viewModel.backgroundTaskOutput(it) },
            )
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
                onFetch = { cb -> viewModel.fetchRemote(cb) },
                onPull = { cb -> viewModel.pullRemote(cb) },
                onSetIdentity = { name, email, cb -> viewModel.setGitIdentity(name, email, cb) },
                onFileDiff = { path, cb -> viewModel.fileDiff(path, cb) },
                onPickBranch = { showBranchDialog = true },
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
    suggestions: List<StarterSuggestion> = emptyList(),
    onPickSuggestion: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    composer: @Composable () -> Unit,
) {
    // 问候语 + 输入卡片作为同一主体居中。
    // 键盘只依赖父级连续 safeDrawing，不做可见性分支/布局切换，避免收起时回弹闪一下。
    val markColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.055f)
    Box(
        modifier = modifier
            .fillMaxSize()
            .fillMaxWidth()
            .padding(horizontal = Chrome.gutter),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .drawBehind { drawEmptyStateMark(markColor) },
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
                    tr(
                        buildString {
                            append("开始在 ")
                            append(projectName)
                            append(" 项目新建任务")
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 20.dp),
                )
            } else {
                Text(tr("选择一个工作区后开始新任务"),
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
                if (suggestions.isNotEmpty()) {
                    StarterSuggestions(
                        suggestions = suggestions,
                        onPick = onPickSuggestion,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                composer()
            }
        }
    }
}

private fun DrawScope.drawEmptyStateMark(color: Color) {
    val stroke = 2.dp.toPx()
    val radius = 120.dp.toPx()
    val c = Offset(size.width / 2f, 30.dp.toPx())
    val brush = Brush.verticalGradient(
        colors = listOf(color, color.copy(alpha = 0f)),
        startY = c.y - radius,
        endY = c.y + radius * 0.6f,
    )
    drawCircle(brush = brush, radius = radius, center = c, style = Stroke(width = stroke))
    val arm = radius * 0.42f
    drawLine(
        brush = brush,
        start = Offset(c.x - arm, c.y),
        end = Offset(c.x + arm, c.y),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        brush = brush,
        start = Offset(c.x, c.y - arm),
        end = Offset(c.x, c.y + arm),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}

private fun emptyGreeting(): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return tr(
        when (hour) {
            in 5..8 -> "早上好呀，新的一天开始啦"
            in 9..11 -> "上午好呀，有什么想让我帮忙的吗"
            in 12..13 -> "中午好呀，要不要先休息一下"
            in 14..17 -> "下午好呀，接下来交给我吧"
            in 18..22 -> "晚上好呀，今天辛苦啦"
            else -> "夜深啦，别忘了照顾好自己哦"
        },
    )
}

/**
 * 文件拖拽的全屏遮罩 + 胶囊提示（ZCode 对齐）。
 *
 * ZCode 桌面端文案：`chat.composer.workspaceFileDragHint` = "松开以引用此文件或目录"。
 * 遮罩只观察不消费指针事件；手指松开（无按压）时回调 [onRelease]。
 */
@Composable
private fun FileDragOverlay(
    payload: com.andmx.ui2.files.FileDragBus.Payload,
    overComposer: Boolean,
    onRelease: (released: Boolean) -> Unit,
) {
    val motion = com.andmx.ui2.theme.LocalMotion.current
    val position by com.andmx.ui2.files.FileDragBus.state.collectAsState()
    androidx.compose.animation.AnimatedVisibility(
        visible = true,
        enter = fadeIn(motion.fastEffects),
        exit = fadeOut(motion.fastEffects),
    ) {
        val pos = position.position
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                            val pressed = event.changes.any { it.pressed }
                            if (!pressed) {
                                onRelease(true)
                                break
                            }
                        }
                    }
                }
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (overComposer) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                    .border(
                        1.dp,
                        if (overComposer) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(999.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (overComposer) Icons.Outlined.Check
                    else Icons.Outlined.DriveFolderUpload,
                    null,
                    Modifier.size(16.dp),
                    tint = if (overComposer) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (overComposer) "松开以引用此文件或目录"
                    else payload.relativePath.ifBlank { payload.name },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 260.dp),
                )
            }
        }
        if (pos != null) {
            Box(
                Modifier
                    .offset { IntOffset((pos.x - 44.dp.toPx()).roundToInt(), (pos.y - 20.dp.toPx()).roundToInt()) }
                    .size(width = 88.dp, height = 40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
            )
        }
    }
}

private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollAnchorToTop(index: Int) {
    val viewport = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(1)
    animateScrollToItem(index, scrollOffset = -(viewport - 12))
}

private fun attachmentDisplayName(context: android.content.Context, uri: android.net.Uri): String {
    val fromResolver = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
    return fromResolver?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "附件"
}

