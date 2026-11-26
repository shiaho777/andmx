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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Commit
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.andmx.ui2.ext.ExtensionSlotHost
import com.andmx.ui2.ext.ExtensionSlotIds
import com.andmx.ui2.files.FilesScreen
import com.andmx.ui2.settings.SettingsScreen
import com.andmx.ui2.terminal.TerminalScreen
import com.andmx.ui2.terminal.rememberTerminalColors
import androidx.compose.runtime.snapshotFlow
import com.andmx.llm.VideoAttachments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    val pendingApprovals by viewModel.pendingApprovals.collectAsState()
    val pendingApproval = pendingApprovals[currentConversationId]
    val visibleTools = remember(toolCalls, appSettings.showTodos, pendingApproval) {
        val waiting = pendingApproval?.takeIf { it.kind == "tool" }?.let { request ->
            toolCalls.lastOrNull { it.isRunning && it.name == request.toolName && it.args == request.arguments }?.id
                ?: toolCalls.singleOrNull { it.isRunning && it.name == request.toolName }?.id
        }
        val tools = if (appSettings.showTodos) toolCalls else toolCalls.filterNot { tool ->
            val n = tool.name.lowercase()
            n.contains("todo") || n == "update_plan"
        }
        if (waiting == null) tools else tools.map { if (it.id == waiting) it.copy(awaitingApproval = true) else it }
    }
    val pendingYoloConfirmation by viewModel.pendingYoloConfirmation.collectAsState()
    val resolvedApprovals = remember(approvals) { approvals.filterNot { it.status == "pending" } }
    val timeline = remember(
        messages, visibleTools, resolvedApprovals, subAgentItems, visibleReasonings, goalVerifications,
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
            approvals = resolvedApprovals,
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
    val planSteps by viewModel.planSteps.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val worktree by viewModel.activeWorktree.collectAsState()
    val queuePaused by viewModel.queuePaused.collectAsState()
    val steeringMessage by viewModel.steeringMessage.collectAsState()
    val config by viewModel.composerConfig.collectAsState()
    val planOverlay by viewModel.planOverlayActive.collectAsState()
    val fileChanges by viewModel.fileChanges.collectAsState()
    val changedPaths = remember(fileChanges) { fileChanges.map { it.path } }
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
    val restoringConversation by viewModel.restoringConversation.collectAsState()
    val branch = gitInfo?.branch.orEmpty()

    LaunchedEffect(hostPath) {
        viewModel.refreshGitInfo()
    }

    var showTerminal by remember { mutableStateOf(false) }
    val lastBrowserUrl by viewModel.lastBrowserUrl.collectAsState()
    var browserTarget by remember(currentConversationId) { mutableStateOf<String?>(null) }
    var showActionsMenu by remember { mutableStateOf(false) }
    var showBackgroundTasks by remember { mutableStateOf(false) }
    var selectionError by remember { mutableStateOf<String?>(null) }
    var sideChatExcerpt by remember { mutableStateOf<String?>(null) }
    var showFiles by remember { mutableStateOf(false) }
    var filesInitialPath by remember { mutableStateOf<String?>(null) }
    var filesLastPath by remember { mutableStateOf<String?>(null) }
    var fileReview by remember { mutableStateOf<com.andmx.ui2.files.FileReviewAnchor?>(null) }
    var changedFileReviewPath by remember(currentConversationId) { mutableStateOf<String?>(null) }
    var fileTreeRequestKey by remember { mutableStateOf(0) }
    var fileTreeRequestPath by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showCommandCenter by remember { mutableStateOf(false) }
    var findOpen by remember(currentConversationId) { mutableStateOf(false) }
    var findQuery by remember(currentConversationId) { mutableStateOf("") }
    var findSelection by remember(currentConversationId, findQuery) { mutableStateOf<Pair<Long, Int>?>(null) }
    var findTick by remember(currentConversationId) { mutableIntStateOf(0) }
    var focusFindField by remember(currentConversationId) { mutableStateOf(false) }
    var pendingFind by remember { mutableStateOf<ConversationFindRequest?>(null) }
    val findTargets = remember(timeline, findOpen) {
        if (!findOpen) emptyList()
        else timeline.asReversed().mapNotNull { (it as? TimelineItem.Message)?.message?.takeUnless { m -> m.role == "compact" } }
    }
    val findHits = remember(findTargets, findQuery) { ChatFind.find(findTargets, findQuery) }
    val findMessageIndexById = remember(findTargets) {
        findTargets.mapIndexed { index, message -> message.id to index }.toMap()
    }
    val activeFindIndex = findSelection?.let { (messageId, offset) ->
        findHits.indexOfFirst { it.start == offset && findTargets[it.messageIndex].id == messageId }
    }?.coerceAtLeast(0) ?: 0
    val findMessageId = findHits.getOrNull(activeFindIndex)?.let { findTargets[it.messageIndex].id }
    fun selectFindHit(index: Int) {
        val hit = findHits.getOrNull(index) ?: return
        findSelection = findTargets[hit.messageIndex].id to hit.start
    }
    LaunchedEffect(findQuery, findMessageId) {
        if (findSelection == null) selectFindHit(activeFindIndex)
    }
    LaunchedEffect(pendingFind, currentConversationId, restoringConversation, findTargets, findQuery) {
        val request = pendingFind ?: return@LaunchedEffect
        if (request.conversationId != currentConversationId || restoringConversation) return@LaunchedEffect
        if (!findOpen || focusFindField || findQuery != request.query) {
            focusFindField = false
            findQuery = request.query
            findOpen = true
            return@LaunchedEffect
        }
        val match = findHits.indexOfFirst { findTargets[it.messageIndex].id == request.messageId }
        if (findTargets.isNotEmpty()) {
            selectFindHit(match.coerceAtLeast(0))
            findTick++
            pendingFind = null
        }
    }
    var manuallyExpandedFolds by remember(currentConversationId) { mutableStateOf(setOf<Long>()) }
    val searchFoldByItem = remember(timeline, turnFolds, findOpen) {
        if (findOpen) TurnProcessFolding.coveringFolds(timeline, turnFolds) else emptyMap()
    }
    var suppressSearchFold by remember(findQuery, findMessageId, findTick) { mutableStateOf(false) }
    val searchFold = if (suppressSearchFold || findMessageId == null) null else searchFoldByItem["m-$findMessageId"]
    val expandedFolds = searchFold?.let { manuallyExpandedFolds + it } ?: manuallyExpandedFolds
    val timelineReversed = remember(timeline, turnFolds, expandedFolds) {
        TurnProcessFolding.visibleTimeline(timeline, turnFolds, expandedFolds).asReversed()
    }
    fun openFind() {
        focusFindField = true
        findOpen = true
        pendingFind = null
    }
    fun closeFind() {
        findOpen = false
        findQuery = ""
        findSelection = null
        focusFindField = false
        pendingFind = null
    }
    var turnDelta by remember { mutableIntStateOf(0) }
    var modelMenuToken by remember { mutableIntStateOf(0) }
    var settingsInitialPage by remember { mutableStateOf(SettingsPage.HOME) }
    var showBranchDialog by remember { mutableStateOf(false) }
    var showWorkspacePicker by remember { mutableStateOf(false) }
    var showGitActions by remember { mutableStateOf(false) }
    var showConflictResolver by remember { mutableStateOf(false) }
    var showRemoteDialog by remember { mutableStateOf(false) }
    var dirtyFilesCache by remember { mutableStateOf<List<com.andmx.workspace.GitBaseline.ChangedFile>>(emptyList()) }

    // 浮层打开时拦截返回键：先关浮层，再走系统返回
    BackHandler(enabled = showTerminal || showFiles || showSettings) {
        when {
            showTerminal -> showTerminal = false
            showFiles -> {
                showFiles = false
                filesLastPath = filesInitialPath ?: filesLastPath
                filesInitialPath = null
                fileReview = null
            }
            showSettings -> showSettings = false
        }
    }
    BackHandler(enabled = findOpen && !showTerminal && !showFiles && !showSettings) {
        closeFind()
    }

    var drawerOpen by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var draftOwner by remember { mutableStateOf(0L) }
    val videoImportScope = rememberCoroutineScope()
    var importingVideo by remember { mutableStateOf(false) }
    var filePickerOwner by remember { mutableStateOf(0L) }

    LaunchedEffect(currentConversationId) {
        if (draftOwner != 0L && draftOwner != currentConversationId) {
            draftStore.save(draftOwner, inputText)
        }
        if (draftOwner != currentConversationId) attachments = attachments.filter { it.video == null }
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

    val sessionFilePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importPortableConversation(uri)
    }

    LaunchedEffect(Unit) {
        ChatActionBus.actions.collect { action ->
            when (action) {
                is ChatActionBus.Action.OpenFile -> {
                    val path = action.path
                    if (action.addContext) viewModel.addFileContext(path)
                    filesInitialPath = path
                    filesLastPath = path
                    fileReview = action.review
                    showFiles = true
                }
                is ChatActionBus.Action.ReviewFileChange -> {
                    changedFileReviewPath = action.path
                    viewModel.openFileRewind()
                }
                ChatActionBus.Action.OpenTerminal -> showTerminal = true
                ChatActionBus.Action.OpenSessionImport -> sessionFilePicker.launch(arrayOf("application/json", "application/octet-stream"))
                is ChatActionBus.Action.OpenUrl -> {
                    browserUrl(action.url)?.let { browserTarget = it }
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

    fun addPickedFile(uri: android.net.Uri, videoOnly: Boolean = false) {
        val owner = filePickerOwner
        if (viewModel.currentConversationId.value != owner) return
        val name = attachmentDisplayName(context, uri)
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (!videoOnly && !VideoAttachments.isVideo(name, mime)) {
            attachments = attachments + Attachment(name, uri.toString())
            return
        }
        if (!viewModel.composerConfig.value.supportsVideo) {
            selectionError = tr("当前模型或接口不支持视频附件。请选择支持视频的模型。")
            return
        }
        val videoMime = VideoAttachments.mimeFor(name, mime)
        if (videoMime == null) {
            selectionError = tr("不支持此视频格式。")
            return
        }
        if (importingVideo) return
        importingVideo = true
        videoImportScope.launch {
            try {
                val video = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { VideoAttachments.save(context, name, videoMime, it) }
                        ?: throw java.io.IOException("Cannot open video")
                }
                if (viewModel.currentConversationId.value != owner) {
                    VideoAttachments.discard(video)
                    return@launch
                }
                val error = viewModel.composerConfig.value.videoError(attachments.mapNotNull { it.video } + video)
                if (error == null) {
                    attachments = attachments + Attachment(name, uri.toString(), video)
                    selectionError = null
                } else {
                    VideoAttachments.discard(video)
                    selectionError = error
                }
            } catch (_: VideoAttachments.TooLarge) {
                if (viewModel.currentConversationId.value == owner) selectionError = tr("每条消息最多 20 MiB 视频")
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (viewModel.currentConversationId.value == owner) selectionError = tr("无法读取附件，请检查文件类型和大小。")
            } finally {
                importingVideo = false
            }
        }
    }

    val documentPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) addPickedFile(uri)
    }
    val videoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) addPickedFile(uri, videoOnly = true)
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
                    ArgSuggestion("system", "system", tr("跟随系统")),
                    ArgSuggestion("zh-CN", "zh-CN", tr("简体中文")),
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
                        label = tr("浏览文件…"),
                        subtitle = tr("打开项目文件树"),
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
            pendingFind = null
            viewModel.switchToConversation(id)
            drawerOpen = false
        },
        onSelectSearchMatch = { id, messageId, query ->
            pendingFind = ConversationFindRequest(id, messageId, query)
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
        approvalConversationIds = pendingApprovals.keys,
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
                            filesInitialPath = filesLastPath
                            showFiles = true
                            true
                        }
                        SessionShortcuts.FIND_IN_TASK -> {
                            openFind()
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
            var pendingHistoryJump by remember(currentConversationId) { mutableStateOf<Long?>(null) }
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
            val isEmpty = timeline.isEmpty() && !restoringConversation
            val inputFocus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            @Composable
            fun ComposerBlock() {
                Composer(
                    contextHeader = if (isEmpty) {
                        {
                            EmptyWorkspaceHeader(
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
                            )
                        }
                    } else null,
                    onManageModels = {
                        settingsInitialPage = SettingsPage.MODEL
                        showSettings = true
                    },
                    modelMenuToken = modelMenuToken,
                    inputModifier = Modifier.focusRequester(inputFocus),
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
                        if (!restoringConversation &&
                            (importingVideo || inputText.isNotBlank() || attachments.isNotEmpty() || contextChips.isNotEmpty())
                        ) {
                            val videoError = if (importingVideo) tr("正在添加视频，请稍后发送。")
                                else config.videoError(attachments.mapNotNull { it.video })
                            if (videoError == null) {
                                viewModel.sendMessage(inputText, attachments = attachments)
                                inputText = ""
                                attachments = emptyList()
                            } else selectionError = videoError
                        }
                    },
                    guideFollowUps = appSettings.interactionBehavior == "guide" && attachments.isEmpty(),
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
                    onAddDocument = {
                        filePickerOwner = currentConversationId
                        documentPicker.launch("*/*")
                    },
                    onAddVideo = if (config.supportsVideo) {
                        {
                            filePickerOwner = currentConversationId
                            videoPicker.launch("video/*")
                        }
                    } else null,
                    onPasteClipboardImage = { uri ->
                        val name = uri.lastPathSegment?.substringAfterLast('/') ?: tr("剪贴板图片")
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
                            flat = isEmpty,
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
                    modifier = Modifier
                        .widthIn(max = Chrome.maxReadingWidth)
                        .fillMaxWidth()
                        .padding(horizontal = Chrome.gutter, vertical = 8.dp)
                        .then(composerBoundsModifier),
                )
            }

            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (isEmpty) "AndMX"
                            else conversationTitle.ifBlank { projectName.ifBlank { "AndMX" } },
                            style = MaterialTheme.typography.titleMedium,
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
                        Icon(Icons.Outlined.Menu, tr("切换侧边栏"))
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
                        Icon(Icons.Outlined.Search, tr("搜索"))
                    }
                    if (isEmpty) {
                        IconButton(onClick = {
                            settingsInitialPage = SettingsPage.HOME
                            showSettings = true
                        }) {
                            Icon(Icons.Outlined.Settings, tr("设置"))
                        }
                    } else {
                        IconButton(onClick = {
                            fileTreeRequestPath = null
                            fileTreeRequestKey += 1
                            drawerOpen = true
                        }) {
                            Icon(Icons.Outlined.FolderOpen, tr("查看文件"))
                        }
                    }
                    Box {
                        IconButton(onClick = { showActionsMenu = true }) {
                            Icon(Icons.Outlined.MoreVert, tr("更多操作"))
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = showActionsMenu,
                            onDismissRequest = { showActionsMenu = false },
                        ) {
                            if (!isEmpty && !restoringConversation) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(tr("在任务中查找")) },
                                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                    onClick = {
                                        showActionsMenu = false
                                        openFind()
                                    },
                                )
                            }
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(tr("浏览器")) },
                                onClick = {
                                    showActionsMenu = false
                                    browserTarget = lastBrowserUrl
                                },
                            )
                            if (gitInfo?.isRepo == true) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(tr("Git 操作")) },
                                    leadingIcon = { Icon(Icons.Outlined.Commit, null) },
                                    onClick = {
                                        showActionsMenu = false
                                        dirtyFilesCache = emptyList()
                                        viewModel.refreshGitInfo()
                                        showGitActions = true
                                    },
                                )
                            }
                            if (currentConversationId > 0L) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(tr("分享对话")) },
                                    onClick = {
                                        showActionsMenu = false
                                        viewModel.shareConversation()
                                    },
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(tr("分享对话文本文件")) },
                                    onClick = {
                                        showActionsMenu = false
                                        viewModel.sharePortableConversation()
                                    },
                                )
                            }
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(tr("从文件导入对话并继续")) },
                                onClick = {
                                    showActionsMenu = false
                                    sessionFilePicker.launch(arrayOf("application/json", "application/octet-stream"))
                                },
                            )
                        }
                    }
                    if (!isEmpty) {
                        IconButton(onClick = { showTerminal = !showTerminal }) {
                            Icon(
                                Icons.Outlined.Terminal,
                                if (showTerminal) tr("关闭终端") else tr("终端"),
                                tint = if (showTerminal) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ExtensionSlotHost(ExtensionSlotIds.NAV_PRIMARY, Modifier.fillMaxWidth())

            if (restoringConversation) {
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else if (isEmpty) {
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Text(
                        tr("新任务"),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        modifier = Modifier.padding(bottom = 14.dp),
                    )
                }
            } else {
                val viewport = rememberConversationViewport(
                    currentConversationId, timelineReversed, restoringConversation, scrollMemory,
                )
                val listState = viewport.list
                LaunchedEffect(pendingHistoryJump, viewport.restored) {
                    val id = pendingHistoryJump ?: return@LaunchedEffect
                    if (!viewport.restored) return@LaunchedEffect
                    val index = timelineReversed.indexOfFirst {
                        it is TimelineItem.Message && it.message.role == "user" && it.message.id == id
                    }
                    if (index >= 0) {
                        viewport.following = false
                        listState.scrollAnchorToTop(index)
                    }
                    pendingHistoryJump = null
                }
                val userAnchorIndices = remember(timelineReversed) {
                    timelineReversed.mapIndexedNotNull { i, it ->
                        if (it is TimelineItem.Message && it.message.role == "user") i else null
                    }
                }
                val findFocusRequester = remember { FocusRequester() }
                fun moveFindHit(direction: Int) {
                    if (findHits.isEmpty()) return
                    selectFindHit(ChatFind.wrap(activeFindIndex + direction, findHits.size))
                    findTick++
                }
                LaunchedEffect(findOpen, focusFindField) {
                    if (findOpen && focusFindField) findFocusRequester.requestFocus()
                }
                LaunchedEffect(findMessageId, findTick, findQuery, viewport.restored) {
                    if (findMessageId == null || !viewport.restored) return@LaunchedEffect
                    val index = timelineReversed.indexOfFirst { it.stableId == "m-$findMessageId" }
                    if (index >= 0) {
                        viewport.following = false
                        listState.animateScrollToItem(index)
                    }
                }
                if (findOpen) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = findQuery,
                            onValueChange = { findQuery = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            decorationBox = { inner ->
                                Box {
                                    if (findQuery.isEmpty()) {
                                        Text(tr("在任务中查找"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    inner()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(findFocusRequester)
                                .semantics { contentDescription = tr("在任务中查找") }
                                .onPreviewKeyEvent { event ->
                                    if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                                        event.key == androidx.compose.ui.input.key.Key.Enter
                                    ) {
                                        moveFindHit(if (event.nativeKeyEvent.isShiftPressed) -1 else 1)
                                        true
                                    } else false
                                },
                        )
                        Text(
                            text = if (findHits.isEmpty()) "0/0" else "${activeFindIndex + 1}/${findHits.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        TextButton(
                            onClick = { moveFindHit(-1) },
                            enabled = findHits.isNotEmpty(),
                        ) { Text(tr("上一个")) }
                        TextButton(
                            onClick = { moveFindHit(1) },
                            enabled = findHits.isNotEmpty(),
                        ) { Text(tr("下一个")) }
                        TextButton(onClick = ::closeFind) { Text(tr("关闭")) }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .nestedScroll(viewport.scrollConnection)
                        .align(Alignment.TopCenter)
                        .widthIn(max = Chrome.maxReadingWidth)
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
                                    val findMi = findMessageIndexById[item.message.id]
                                    val bubbleHighlights = if (findOpen && findMi != null) {
                                        ChatFind.highlightsFor(findHits, findMi, activeFindIndex)
                                    } else emptyList()
                                    MessageBubble(
                                        message = item.message,
                                        fileExists = previewFileExists,
                                        mergedCodeComments = turnCodeComments[item.message.id],
                                        highlightRanges = bubbleHighlights,
                                        selectionActions = if (!item.message.isStreaming && item.message.content.isNotBlank()) {
                                            SelectionActions(
                                                onCopy = { viewModel.copyMessage(it) },
                                                onQuote = { text ->
                                                    val quote = ChatFind.quote(text)
                                                    if (quote.isNotEmpty()) {
                                                        inputText = if (inputText.isBlank()) quote else inputText.trimEnd() + "\n" + quote
                                                    }
                                                },
                                                onAnnotate = { annotated ->
                                                    if (annotated.isNotEmpty()) {
                                                        inputText = if (inputText.isBlank()) annotated else inputText.trimEnd() + "\n" + annotated
                                                    }
                                                },
                                                onSideChat = if (
                                                    item.message.role == "assistant" &&
                                                    !item.message.isProcess &&
                                                    !isLoading
                                                ) {
                                                    { sideChatExcerpt = it }
                                                } else null,
                                            )
                                        } else null,
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
                                    changedPaths = changedPaths,
                                )
                                is TimelineItem.ToolGroup -> ToolGroupCard(
                                    item.tools,
                                    workflowStatus = { viewModel.workflowStatusLine(it) },
                                    changedPaths = changedPaths,
                                )
                                is TimelineItem.Reasoning -> ReasoningCard(item.item)
                                is TimelineItem.Working -> WorkingIndicator(retryStatus)
                                is TimelineItem.TurnProcess -> TurnProcessRow(
                                    fold = item.fold,
                                    expanded = item.fold.sortKey in expandedFolds,
                                    onToggle = {
                                        val key = item.fold.sortKey
                                        if (key in expandedFolds) {
                                            manuallyExpandedFolds -= key
                                            if (key == searchFold) suppressSearchFold = true
                                        } else {
                                            manuallyExpandedFolds += key
                                        }
                                    },
                                )
                                is TimelineItem.Approval -> ApprovalTimelineCard(item.item)
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
                                    viewport.latest()
                                } else {
                                    viewport.following = false
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
                    val turnEntries = remember(timelineReversed) {
                        val raw = TurnIndex.from(timelineReversed)
                        raw.map { it.copy(number = raw.size - it.number + 1) }
                    }
                    TurnNavigator(
                        turn = userAnchorIndices.size - rank,
                        total = userAnchorIndices.size,
                        onPrev = { jumpTo(rank + 1) },
                        onNext = { jumpTo(rank - 1) },
                        turns = turnEntries,
                        onJumpToTurn = { number ->
                            jumpTo(userAnchorIndices.size - number)
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 14.dp, bottom = 6.dp),
                    )
                }
                // ZCode ConversationBackToBottomButton：滚离底部后显示回底按钮。
                val showBackToBottom by remember(viewport) {
                    derivedStateOf { viewport.restored && !viewport.following && listState.canScrollBackward }
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
                                timelineScope.launch { viewport.latest() }
                            }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.KeyboardArrowDown,
                            tr("回到底部"),
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

                if (compactNotice?.running == true) {
                    CompactMarkerRow(compactNotice?.text ?: tr("正在压缩上下文"))
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
                    items = recoverableQueue.map { it.text },
                    onRecover = { viewModel.recoverPendingQueue() },
                    onDiscard = { viewModel.discardPendingQueue() },
                )
                RewindBar(
                    changes = fileChanges,
                    rewindResult = rewindResult,
                    onOpen = {
                        changedFileReviewPath = null
                        viewModel.openFileRewind()
                    },
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
                if (fileRewindOpen) {
                    FileRewindDialog(
                        changes = fileChanges,
                        onRevertOne = { viewModel.revertSingleFile(it) },
                        onRevertAll = {
                            viewModel.rewindFiles()
                            viewModel.dismissFileRewind()
                        },
                        onDismiss = { viewModel.dismissFileRewind() },
                        initialPath = changedFileReviewPath,
                        canRevert = !isLoading,
                        onOpenFile = {
                            viewModel.dismissFileRewind()
                            ChatActionBus.openFile(it, addContext = false)
                        },
                    )
                }
                if (rewindPickerOpen) {
                    RewindPickerDialog(
                        checkpoints = rewindCheckpoints,
                        onPick = { viewModel.rewindToCheckpoint(it) },
                        onDismiss = { viewModel.dismissRewindPicker() },
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
                            onSubmit = { viewModel.resolveUserQuestion(req, it) },
                            onCancel = { viewModel.resolveApproval(req, false) },
                            onSnooze = { viewModel.snoozeUserQuestion(req) },
                        )
                        "exit_plan" -> ExitPlanApprovalPanel(
                            request = req,
                            onAllow = { viewModel.resolveApproval(req, true) },
                            onDeny = { viewModel.resolveApproval(req, false) },
                        )
                        else -> ApprovalBanner(
                            request = req,
                            onAllow = { viewModel.resolveApproval(req, true) },
                            onDeny = { viewModel.resolveApproval(req, false) },
                            onScope = { viewModel.resolveApproval(req, true, it) },
                            suggestions = req.suggestions,
                            onAcceptSuggestion = { viewModel.resolveApproval(req, true, suggestion = it) },
                        )
                    }
                }

                if (queue.isNotEmpty()) {
                    QueueStrip(
                        queue = queue.map { it.text },
                        paused = queuePaused,
                        onResume = { viewModel.resumeQueue() },
                        onRemove = { viewModel.removeFromQueue(it) },
                        onSendNow = { viewModel.sendQueuedNow(it) },
                        onEdit = { i ->
                            viewModel.takeQueuedForEdit(i)?.let { item ->
                                inputText = item.text
                                attachments = item.images.map { Attachment(tr("图片"), it) } +
                                    item.videos.map { Attachment(it.name, it.id, video = it) }
                            }
                        },
                        onMove = { index, delta -> viewModel.moveQueued(index, delta) },
                        canSendNow = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                    )
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

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ComposerBlock()
            }
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
                WorkbenchHome(
                    suggestions = starters,
                    onPickSuggestion = { prompt ->
                        inputText = if (inputText.isBlank()) prompt else inputText.trimEnd() + "\n\n" + prompt
                        inputFocus.requestFocus()
                        keyboard?.show()
                    },
                    onFiles = {
                        filesInitialPath = filesLastPath
                        showFiles = true
                    },
                    onTerminal = { showTerminal = true },
                    onBrowser = { browserTarget = lastBrowserUrl },
                    onWorkflows = { viewModel.openWorkflows() },
                    modifier = Modifier.weight(1.2f),
                )
            }
        } // end Column

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

            if (pendingYoloConfirmation) {
                YoloModeConfirmDialog(
                    onConfirm = { viewModel.confirmYoloMode() },
                    onDismiss = { viewModel.cancelYoloMode() },
                )
            }

            // ── 浮层：文件 / 设置（全屏）；终端用底部 sheet，见下方 ──
            val overlayMotion = LocalMotion.current
            androidx.compose.animation.AnimatedVisibility(
                visible = showFiles,
                enter = overlayMotion.overlayEnter(),
                exit = overlayMotion.overlayExit(),
            ) {
                FilesScreen(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    initialPath = filesInitialPath,
                    review = fileReview,
                    conversationId = currentConversationId,
                    worktree = worktree,
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = showSettings,
                enter = overlayMotion.overlayEnter(),
                exit = overlayMotion.overlayExit(),
            ) {
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

        browserTarget?.let { url ->
            val browserConversation = currentConversationId
            BrowserDialog(
                initialUrl = url,
                onClose = { browserTarget = null },
                onPage = { viewModel.rememberBrowserUrl(browserConversation, it) },
                onElement = { element ->
                    inputText = listOf(inputText.trimEnd(), element).filter { it.isNotEmpty() }.joinToString("\n")
                    browserTarget = null
                },
            )
        }

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
                    worktree = worktree,
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
                onResolveConflicts = {
                    showBranchDialog = false
                    showConflictResolver = true
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
                onLoadCommitGraph = { cb -> viewModel.loadCommitGraph(cb) },
                onLoadCommitLog = { cb -> viewModel.loadCommitLog(cb) },
                onResolveConflicts = {
                    showGitActions = false
                    showConflictResolver = true
                },
                onCheckConflicts = { cb -> viewModel.hasGitConflicts(cb) },
                onDismiss = { showGitActions = false },
            )
        }

        if (showConflictResolver) {
            com.andmx.ui2.files.ConflictResolverDialog(
                workspacePath = viewModel.workspaceExecPath().orEmpty(),
                ops = viewModel.conflictOps(),
                onDismiss = { showConflictResolver = false },
                onAllResolved = { viewModel.refreshGitInfo() },
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
                    if (overComposer) tr("松开以引用此文件或目录")
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
        ?: tr("附件")
}

