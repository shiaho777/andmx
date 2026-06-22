package com.andmx.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.ScreenShare
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import com.andmx.agent.PlanItemStatus
import com.andmx.agent.ToolArgs
import com.andmx.ui.conversation.ConversationController
import com.andmx.ui.conversation.GoalPhase
import com.andmx.ui.conversation.SettingsSheet
import com.andmx.ui.conversation.label
import com.andmx.term.TerminalSession
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Radii
import com.andmx.ui.theme.Spacing
import com.andmx.ui.rememberViewportClass
import com.andmx.ui.ViewportClass
import com.andmx.ui.isCompact
import com.andmx.ui.isExpanded
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * The landscape three-pane workbench shell:
 *
 *   [ Sidebar | Chat | Work pane ]
 *
 * This is the structural backbone of AndMX. Pane widths are fixed for the
 * first cut; a draggable splitter and adaptive collapse (phone portrait ->
 * single pane) come later.
 */
@Composable
fun WorkbenchScreen(
    isDark: Boolean,
    sharedText: String? = null,
    onSharedTextConsumed: () -> Unit = {},
    /** Launch the system MediaProjection consent dialog (Computer Use). */
    onRequestScreenCapture: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = AndmxTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Project defaults to /root until the user creates/selects a project dir
    // via the ProjectPicker — no hardcoded project name (Codex parity).
    val controller = remember { ConversationController(context, scope, project = "/root") }

    // ── Deep sharing: auto-fill shared text into composer ──
    var composerText by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) {
            composerText = sharedText
            onSharedTextConsumed()
        }
    }
    val repo = remember { com.andmx.data.ConversationRepository(context) }
    val conversations by repo.observeConversations()
        .collectAsState(initial = emptyList())
    var showSettings by remember { mutableStateOf(false) }

    val groups = remember(conversations) { groupConversations(conversations) }
    var renameTarget by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var showPlugins by remember { mutableStateOf(false) }
    var showAutomations by remember { mutableStateOf(false) }
    var showProgress by remember { mutableStateOf(false) }
    var showGoal by remember { mutableStateOf(false) }
    var showComputerUse by remember { mutableStateOf(false) }
    var showProjectPicker by remember { mutableStateOf(false) }
    var recentCommandIds by remember { mutableStateOf<List<CommandId>>(emptyList()) }
    var showWorkPane by remember { mutableStateOf(true) }
    var showTerminalDock by remember { mutableStateOf(false) }
    /** Sidebar drawer open state, used only in COMPACT (phone portrait). */
    var drawerOpen by remember { mutableStateOf(false) }
    val viewport = rememberViewportClass()
    var terminalDockTall by remember { mutableStateOf(false) }
    var localTitleOverride by remember { mutableStateOf<String?>(null) }
    var workPaneTab by remember { mutableStateOf(WorkPaneTab.TERMINAL) }
    var selectedFilePath by remember { mutableStateOf<String?>(null) }
    var selectedDiffPath by remember { mutableStateOf<String?>(null) }
    var browserUrl by remember { mutableStateOf<String?>(null) }
    val fileState = rememberFilePaneState()
    val browserState = rememberBrowserPaneState()

    // Mirror the agent's browsing: when the `browse` tool fetches a URL, open
    // the in-app browser pane on the same page so the user can preview it
    // (Codex parity — agent browses → user sees the same page).
    val mirroredUrl = controller.browseMirrorUrl
    androidx.compose.runtime.LaunchedEffect(mirroredUrl) {
        if (!mirroredUrl.isNullOrBlank()) {
            browserUrl = mirroredUrl
            browserState.load(mirroredUrl)
            showWorkPane = true
            workPaneTab = WorkPaneTab.BROWSER
        }
    }
    var restoredWorkbenchConversationId by remember { mutableStateOf<Long?>(null) }
    val terminalSessions = remember { mutableStateListOf<TerminalSession>() }
    var selectedTerminalIndex by remember { mutableIntStateOf(0) }
    val changes by com.andmx.workspace.ChangeTracker.changes.collectAsState()
    val activeTitle = remember(conversations, controller.activeId, controller.items.size, localTitleOverride) {
        localTitleOverride
            ?: conversations.firstOrNull { it.id == controller.activeId }?.title
            ?: (controller.items.firstOrNull { it is com.andmx.ui.conversation.ChatItem.User }
                as? com.andmx.ui.conversation.ChatItem.User)?.text?.lineSequence()?.firstOrNull()?.take(28)
            ?: "新对话"
    }
    fun cycleTheme() {
        val next = when (controller.settings.themeMode) {
            "system" -> "light"; "light" -> "dark"; else -> "system"
        }
        controller.saveSettings(controller.settings.copy(themeMode = next))
    }
    fun openWorkPane(tab: WorkPaneTab) {
        showWorkPane = true
        workPaneTab = tab
    }
    // ── Unified navigation callbacks (replaces 4× duplicated inline lambdas) ──
    fun openDiff(path: String) { selectedDiffPath = path; openWorkPane(WorkPaneTab.DIFF) }
    fun openFile(path: String) { selectedFilePath = path; openWorkPane(WorkPaneTab.FILES) }
    fun openUrl(url: String) { browserUrl = url; browserState.load(url); openWorkPane(WorkPaneTab.BROWSER) }
    fun runCommand(command: CommandId) {
        recentCommandIds = updatedRecentCommands(command, recentCommandIds)
        when (command) {
            CommandId.NEW_CHAT -> {
                localTitleOverride = null
                controller.newConversation()
            }
            CommandId.SHOW_PROGRESS -> showProgress = true
            CommandId.SHOW_GOAL -> showGoal = true
            CommandId.STATUS -> controller.send("/status")
            CommandId.CONTEXT -> controller.send("/context")
            CommandId.PLAN -> controller.send("/plan")
            CommandId.VERIFY -> controller.send("/verify")
            CommandId.CHANGES -> controller.send("/changes")
            CommandId.ACTIVITY -> controller.send("/activity")
            CommandId.CHECKLIST -> controller.send("/checklist")
            CommandId.NEXT -> controller.send("/next")
            CommandId.EVIDENCE -> controller.send("/evidence")
            CommandId.REFERENCES -> controller.send("/references")
            CommandId.BLUEPRINT -> controller.send("/blueprint")
            CommandId.POLICY -> controller.send("/policy")
            CommandId.TOOLS -> controller.send("/tools")
            CommandId.PARITY -> controller.send("/parity")
            CommandId.REPORT -> controller.send("/report")
            CommandId.ARCHITECTURE -> controller.send("/architecture")
            CommandId.SURFACES -> controller.send("/surfaces")
            CommandId.VISUAL_CHECK -> controller.send("/visual-check")
            CommandId.DESIGN_SYSTEM -> controller.send("/design-system")
            CommandId.SCREENSHOT_EXTRACT -> controller.send("/screenshot-extract")
            CommandId.APPSHOTS -> controller.send("/appshots")
            CommandId.TRACE -> controller.send("/trace")
            CommandId.SELF_MODEL -> controller.send("/self-model")
            CommandId.FLOW -> controller.send("/flow")
            CommandId.METHOD -> controller.send("/method")
            CommandId.IMPROVE -> controller.send("/improve")
            CommandId.INSTRUCTIONS -> controller.send("/instructions")
            CommandId.COMMANDS -> controller.send("/commands")
            CommandId.HANDOFF -> controller.send("/handoff")
            CommandId.SET_FULL_ACCESS -> controller.send("/full")
            CommandId.SET_ASK_APPROVAL -> controller.send("/ask")
            CommandId.SET_READ_ONLY -> controller.send("/readonly")
            CommandId.DIAG -> controller.send("/diag")
            CommandId.EXPORT -> controller.send("/export")
            CommandId.SETTINGS -> showSettings = true
            CommandId.PLUGINS -> showPlugins = true
            CommandId.AUTOMATIONS -> showAutomations = true
            CommandId.TOGGLE_THEME -> cycleTheme()
            CommandId.TOGGLE_WORK_PANE -> showWorkPane = !showWorkPane
            CommandId.TOGGLE_TERMINAL_DOCK -> showTerminalDock = !showTerminalDock
            CommandId.OPEN_INSPECTOR -> openWorkPane(WorkPaneTab.INSPECTOR)
            CommandId.OPEN_FILES -> openWorkPane(WorkPaneTab.FILES)
            CommandId.OPEN_TERMINAL -> openWorkPane(WorkPaneTab.TERMINAL)
            CommandId.OPEN_DIFF -> openWorkPane(WorkPaneTab.DIFF)
            CommandId.OPEN_BROWSER -> openWorkPane(WorkPaneTab.BROWSER)
        }
    }
    androidx.compose.runtime.SideEffect { controller.onOpenSettings = { showSettings = true } }
    DisposableEffect(Unit) {
        if (terminalSessions.isEmpty()) terminalSessions.add(TerminalSession(context))
        onDispose { terminalSessions.forEach { it.destroy() } }
    }
    LaunchedEffect(controller.activeId, conversations) {
        val id = controller.activeId
        val conversation = conversations.firstOrNull { it.id == id }
        if (id == null) {
            if (restoredWorkbenchConversationId != null) {
                restoredWorkbenchConversationId = null
                showWorkPane = true
                showTerminalDock = false
                terminalDockTall = false
                workPaneTab = WorkPaneTab.TERMINAL
                selectedFilePath = null
                selectedDiffPath = null
                browserUrl = null
                fileState.currentGuestPath = "/"
                fileState.viewingGuestPath = null
            }
            return@LaunchedEffect
        }
        if (conversation == null || restoredWorkbenchConversationId == id) return@LaunchedEffect
        val restored = conversation.toWorkbenchState()
        showWorkPane = restored.workPaneVisible
        showTerminalDock = restored.terminalDockVisible
        terminalDockTall = restored.terminalDockTall
        workPaneTab = restored.workPaneTab
        selectedFilePath = restored.selectedFilePath.ifBlank { null }
        selectedDiffPath = restored.selectedDiffPath.ifBlank { null }
        browserUrl = restored.browserUrl.ifBlank { null }
        fileState.currentGuestPath = restored.fileCurrentGuestPath.ifBlank { "/" }
        fileState.viewingGuestPath = restored.fileViewingGuestPath.ifBlank { null }
        restored.browserUrl.takeIf { it.isNotBlank() }?.let { browserState.restore(it) }
        restoredWorkbenchConversationId = id
    }
    LaunchedEffect(controller.activeId, restoredWorkbenchConversationId) {
        val id = controller.activeId ?: return@LaunchedEffect
        if (restoredWorkbenchConversationId != id) return@LaunchedEffect
        snapshotFlow {
            WorkbenchStateSnapshot(
                workPaneTab = workPaneTab,
                workPaneVisible = showWorkPane,
                terminalDockVisible = showTerminalDock,
                terminalDockTall = terminalDockTall,
                selectedFilePath = selectedFilePath.orEmpty(),
                selectedDiffPath = selectedDiffPath.orEmpty(),
                browserUrl = when {
                    workPaneTab == WorkPaneTab.BROWSER -> browserState.persistableUrl()
                    !browserUrl.isNullOrBlank() -> browserState.persistableUrl().ifBlank { browserUrl.orEmpty() }
                    else -> ""
                },
                fileCurrentGuestPath = fileState.currentGuestPath.ifBlank { "/" },
                fileViewingGuestPath = fileState.viewingGuestPath.orEmpty(),
            )
        }
            .drop(1)
            .distinctUntilChanged()
            .collectLatest { snapshot ->
                delay(300)
                repo.updateWorkbenchState(
                    conversationId = id,
                    workPaneTab = snapshot.workPaneTab.name,
                    workPaneVisible = snapshot.workPaneVisible,
                    terminalDockVisible = snapshot.terminalDockVisible,
                    terminalDockTall = snapshot.terminalDockTall,
                    selectedFilePath = snapshot.selectedFilePath,
                    selectedDiffPath = snapshot.selectedDiffPath,
                    browserUrl = snapshot.browserUrl,
                    fileCurrentGuestPath = snapshot.fileCurrentGuestPath,
                    fileViewingGuestPath = snapshot.fileViewingGuestPath,
                )
            }
    }

    Box(
        modifier = modifier.background(colors.canvas)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || showSearch) return@onPreviewKeyEvent false
                val keyName = when (event.key) {
                    Key.K -> "k"
                    Key.P -> "p"
                    else -> return@onPreviewKeyEvent false
                }
                if (isCommandPaletteShortcut(keyName, event.isCtrlPressed, event.isMetaPressed, event.isShiftPressed)) {
                    showSearch = true
                    true
                } else {
                    false
                }
            },
    ) {
        // ── Sidebar construction, shared by every viewport class ──
        val sidebar: @Composable (Modifier) -> Unit = { mod ->
            Sidebar(
                groups = groups,
                activeId = controller.activeId,
                onNewChat = { controller.newConversation() },
                onSearch = { showSearch = true },
                onPlugins = { showPlugins = true },
                onAutomations = { showAutomations = true },
                onSettings = { showSettings = true },
                onSelectConversation = {
                    controller.load(it)
                    // Close the drawer on phone once a conversation is chosen.
                    if (viewport.isCompact) drawerOpen = false
                },
                onDeleteConversation = { controller.delete(it) },
                onRenameConversation = { id, title -> renameTarget = id to title },
                onProjectHeaderClick = { showProjectPicker = true },
                modifier = mod,
            )
        }

        // ── Main column: top bar + plan + chat (+ work pane inline on wide screens) ──
        // `workPaneInline` controls whether WorkPane is laid out beside ChatPane
        // (EXPANDED/MEDIUM) or shown as a full-screen overlay (COMPACT).
        // The caller supplies the layout modifier (weight on wide Row, fillMaxSize on phone Box).
        val mainColumn: @Composable (Boolean, Modifier) -> Unit = { workPaneInline, mod ->
            Column(Modifier.fillMaxHeight().then(mod)) {
                ThreadTopBar(
                    title = activeTitle,
                    progressSelected = showProgress,
                    workPaneVisible = showWorkPane,
                    terminalVisible = showTerminalDock,
                    changeCount = changes.size,
                    onProgressClick = { showProgress = !showProgress },
                    onToggleWorkPane = { showWorkPane = !showWorkPane },
                    onToggleTerminalDock = { showTerminalDock = !showTerminalDock },
                    onNewChat = {
                        localTitleOverride = null
                        controller.newConversation()
                    },
                    onRename = { renameTarget = (controller.activeId ?: -1L) to activeTitle },
                    onShowGoal = { showGoal = true },
                    onStatus = { controller.send("/status") },
                    onDiag = { controller.send("/diag") },
                    onExport = { controller.send("/export") },
                    onSettings = { showSettings = true },
                    onToggleTheme = ::cycleTheme,
                    isDark = isDark,
                    // Phone-only hamburger to open the sidebar drawer.
                    onOpenDrawer = if (viewport.isCompact) ({ drawerOpen = true }) else null,
                    // Hide low-frequency chrome buttons on phone to keep the title visible.
                    compact = !viewport.isExpanded,
                    onComputerUse = { showComputerUse = true },
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))

                // ── Live plan panel (Codex update_plan UI) ──
                PlanPanel(
                    planState = controller.planState,
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )

                Row(Modifier.weight(1f).fillMaxWidth()) {
                    ChatPane(
                        controller = controller,
                        projectName = controller.projectManager.projectName,
                        initialDraft = composerText,
                        onOpenSettings = { showSettings = true },
                        onOpenDiff = { it?.let { openDiff(it) } },
                        onOpenFile = { it?.let { openFile(it) } },
                        onOpenUrl = { it?.let { openUrl(it) } },
                        showGoal = showGoal,
                        onShowGoalChange = { showGoal = it },
                        modifier = if (workPaneInline && showWorkPane) Modifier.weight(1f) else Modifier.fillMaxWidth(),
                    )
                    if (workPaneInline && showWorkPane) {
                        val workPaneAlpha by androidx.compose.animation.core.animateFloatAsState(
                            targetValue = 1f,
                            animationSpec = androidx.compose.animation.core.tween(com.andmx.ui.theme.Motion.DUR_STD, easing = com.andmx.ui.theme.Motion.EASE_OUT),
                            label = "workPaneAlpha",
                        )
                        VerticalHairline()
                        WorkPane(
                            selected = workPaneTab,
                            onSelect = { workPaneTab = it },
                            controller = controller,
                            selectedFilePath = selectedFilePath,
                            selectedDiffPath = selectedDiffPath,
                            browserUrl = browserUrl,
                            fileState = fileState,
                            browserState = browserState,
                            terminalSessions = terminalSessions,
                            selectedTerminalIndex = selectedTerminalIndex,
                            onSelectedTerminalIndexChange = { selectedTerminalIndex = it },
                            onOpenDiff = { it?.let { openDiff(it) } },
                            onOpenFile = { it?.let { openFile(it) } },
                            onOpenUrl = { it?.let { openUrl(it) } },
                            onOpenProgress = { showProgress = true },
                            onOpenSettings = { showSettings = true },
                            onRunCommand = { command -> controller.send(command) },
                            onClose = { showWorkPane = false },
                            modifier = Modifier.weight(1.15f).graphicsLayerAlpha(workPaneAlpha),
                        )
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = showTerminalDock,
                    enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(com.andmx.ui.theme.Motion.DUR_STD, easing = com.andmx.ui.theme.Motion.EASE_OUT)) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(com.andmx.ui.theme.Motion.DUR_STD)),
                    exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(com.andmx.ui.theme.Motion.DUR_FAST)) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(com.andmx.ui.theme.Motion.DUR_FAST)),
                ) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
                    // Dock height scales with viewport; animate the tall/short toggle.
                    val targetHeight = when {
                        viewport == ViewportClass.EXPANDED -> if (terminalDockTall) 372.dp else 238.dp
                        else -> if (terminalDockTall) 220.dp else 140.dp
                    }
                    val dockHeight by androidx.compose.animation.core.animateDpAsState(
                        targetValue = targetHeight,
                        animationSpec = androidx.compose.animation.core.spring(dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
                        label = "dockHeight",
                    )
                    BottomTerminalDock(
                        onClose = { showTerminalDock = false },
                        expanded = terminalDockTall,
                        onToggleExpanded = { terminalDockTall = !terminalDockTall },
                        terminalSessions = terminalSessions,
                        selectedTerminalIndex = selectedTerminalIndex,
                        onSelectedTerminalIndexChange = { selectedTerminalIndex = it },
                        modifier = Modifier.fillMaxWidth().height(dockHeight),
                    )
                }
            }
        }

        // ── Work pane construction, shared between inline (wide) and overlay (phone) ──
        val workPane: @Composable (Modifier) -> Unit = { mod ->
            WorkPane(
                selected = workPaneTab,
                onSelect = { workPaneTab = it },
                controller = controller,
                selectedFilePath = selectedFilePath,
                selectedDiffPath = selectedDiffPath,
                browserUrl = browserUrl,
                fileState = fileState,
                browserState = browserState,
                terminalSessions = terminalSessions,
                selectedTerminalIndex = selectedTerminalIndex,
                onSelectedTerminalIndexChange = { selectedTerminalIndex = it },
                onOpenDiff = { it?.let { openDiff(it) } },
                onOpenFile = { it?.let { openFile(it) } },
                onOpenUrl = { it?.let { openUrl(it) } },
                onOpenProgress = { showProgress = true },
                onOpenSettings = { showSettings = true },
                onRunCommand = { command -> controller.send(command) },
                onClose = { showWorkPane = false },
                modifier = mod,
            )
        }

        if (viewport == ViewportClass.EXPANDED) {
            // ── Tablet / desktop (≥840dp): three-pane row, sidebar always visible ──
            Row(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars),
            ) {
                sidebar(Modifier.width(252.dp))
                VerticalHairline()
                mainColumn(true, Modifier.weight(1f))
            }
        } else {
            // ── Phone portrait (<600dp) and landscape/small tablet (600-839dp) ──
            // Sidebar is a drawer on both, since it'd cramp the panes if docked.
            // The work pane differs: portrait overlays full-screen (not enough width
            // for two columns), landscape sits beside chat (double-pane).
            val workPaneInline = viewport == ViewportClass.MEDIUM
            val drawerState = androidx.compose.material3.rememberDrawerState(
                initialValue = androidx.compose.material3.DrawerValue.Closed,
            )
            androidx.compose.runtime.LaunchedEffect(drawerOpen) {
                if (drawerOpen) drawerState.open() else drawerState.close()
            }
            androidx.compose.runtime.LaunchedEffect(drawerState.currentValue) {
                if (drawerState.currentValue == androidx.compose.material3.DrawerValue.Closed) drawerOpen = false
            }
            androidx.compose.material3.ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    sidebar(Modifier.fillMaxHeight().fillMaxWidth(0.82f).widthIn(max = 300.dp).navigationBarsPadding())
                },
            ) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                    mainColumn(workPaneInline, Modifier.fillMaxSize())
                    // Portrait: work pane slides in as a full-screen overlay.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !workPaneInline && showWorkPane,
                        enter = androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(com.andmx.ui.theme.Motion.DUR_SLOW, easing = com.andmx.ui.theme.Motion.EASE_OUT)) { it } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(com.andmx.ui.theme.Motion.DUR_STD)),
                        exit = androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(com.andmx.ui.theme.Motion.DUR_STD)) { it } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(com.andmx.ui.theme.Motion.DUR_FAST)),
                    ) {
                        Box(Modifier.fillMaxSize().background(colors.canvas)) {
                            workPane(Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }

        if (showProgress) {
            ProgressPopover(
                controller = controller,
                changes = changes,
                onOpenDiff = { p -> showProgress = false; p?.let { openDiff(it) } },
                onOpenFile = { p -> showProgress = false; p?.let { openFile(it) } },
                onOpenUrl = { u -> showProgress = false; u?.let { openUrl(it) } },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(top = 56.dp, end = Spacing.xxl),
            )
        }
    }

    if (showSettings) {
        SettingsSheet(
            current = controller.settings,
            providers = controller.providers,
            activeProvider = controller.currentProvider,
            onDismiss = { showSettings = false },
            onSavePreferences = controller::saveSettings,
            onSaveProvider = controller::saveProvider,
            onAddBlankProvider = controller::addBlankProvider,
            onDeleteProvider = controller::deleteProvider,
            onSelectProvider = controller::selectProvider,
        )
    }

    if (showComputerUse) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showComputerUse = false },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showComputerUse = false }) {
                    Text("完成", color = AndmxTheme.colors.accent)
                }
            },
            title = { Text("Computer Use 屏幕操作", style = AndmxTheme.typography.titleLarge, color = AndmxTheme.colors.textPrimary) },
            text = {
                com.andmx.ui.computeruse.PermissionGate(onRequestScreenCapture = onRequestScreenCapture)
            },
            containerColor = AndmxTheme.colors.canvas,
        )
    }

    if (showProjectPicker) {
        ProjectPickerDialog(
            manager = controller.projectManager,
            onDismiss = { showProjectPicker = false },
            onSelected = { hostPath ->
                // The host path is bound by proot to /root/project in the guest;
                // agent tools should operate in the guest mount path.
                controller.project = controller.projectManager.guestMountPath
            },
        )
    }

    renameTarget?.let { (id, title) ->
        RenameDialog(
            initial = title,
            onDismiss = { renameTarget = null },
            onConfirm = { newTitle ->
                if (id >= 0) controller.rename(id, newTitle)
                localTitleOverride = newTitle.trim().ifBlank { null }
                renameTarget = null
            },
        )
    }

    if (showSearch) {
        SearchOverlay(
            onDismiss = { showSearch = false },
            onOpen = { controller.load(it) },
            onCommand = ::runCommand,
            recentCommandIds = recentCommandIds,
        )
    }

    if (showPlugins) {
        PluginsOverlay(
            controller = controller,
            onConfigure = { showSettings = true },
            onDismiss = { showPlugins = false },
        )
    }

    if (showAutomations) {
        AutomationsOverlay(
            onRun = { controller.send(it) },
            onDismiss = { showAutomations = false },
        )
    }
}

@Composable
private fun ThreadTopBar(
    title: String,
    progressSelected: Boolean,
    workPaneVisible: Boolean,
    terminalVisible: Boolean,
    changeCount: Int,
    onProgressClick: () -> Unit,
    onToggleWorkPane: () -> Unit,
    onToggleTerminalDock: () -> Unit,
    onNewChat: () -> Unit,
    onRename: () -> Unit,
    onShowGoal: () -> Unit,
    onStatus: () -> Unit,
    onDiag: () -> Unit,
    onExport: () -> Unit,
    onSettings: () -> Unit,
    onToggleTheme: () -> Unit,
    isDark: Boolean,
    /** Shown on phone portrait to open the sidebar drawer; null hides it on wide screens. */
    onOpenDrawer: (() -> Unit)? = null,
    /** When true (phone), low-frequency chrome buttons move into the overflow menu. */
    compact: Boolean = false,
    /** Open the Computer Use permission gate (screen capture + accessibility). */
    onComputerUse: () -> Unit = {},
) {
    val colors = AndmxTheme.colors
    var showMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(46.dp).padding(horizontal = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onOpenDrawer != null) {
            Icon(
                Icons.Outlined.Menu,
                contentDescription = "会话列表",
                tint = colors.textPrimary,
                modifier = Modifier.size(22.dp).clip(Radii.sm).clickable(onClick = onOpenDrawer).padding(2.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(
            title.ifBlank { "新对话" },
            style = AndmxTheme.typography.titleLarge,
            color = colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Outlined.MoreHoriz,
            contentDescription = "更多",
            tint = colors.textSecondary,
            modifier = Modifier.size(18.dp).clip(Radii.sm).clickable { showMenu = true }.padding(1.dp),
        )
        androidx.compose.material3.DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            ThreadMenuItem("新对话", Icons.Outlined.PostAdd) { showMenu = false; onNewChat() }
            ThreadMenuItem("重命名", Icons.Outlined.MoreHoriz) { showMenu = false; onRename() }
            ThreadMenuItem("查看目标", Icons.Outlined.TrackChanges) { showMenu = false; onShowGoal() }
            ThreadMenuItem("状态", Icons.Outlined.Info) { showMenu = false; onStatus() }
            ThreadMenuItem("诊断环境", Icons.Outlined.Terminal) { showMenu = false; onDiag() }
            ThreadMenuItem("导出 Markdown", Icons.Outlined.FileDownload) { showMenu = false; onExport() }
            ThreadMenuItem("Computer Use 权限", Icons.Outlined.ScreenShare) { showMenu = false; onComputerUse() }
            // On phone, low-frequency chrome actions move into this overflow menu
            // so the title bar isn't crowded off-screen.
            if (compact) {
                ThreadMenuItem("进度", Icons.AutoMirrored.Outlined.FormatListBulleted) { showMenu = false; onProgressClick() }
                ThreadMenuItem("设置", Icons.Outlined.Settings) { showMenu = false; onSettings() }
                ThreadMenuItem("切换主题", if (isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode) { showMenu = false; onToggleTheme() }
                ThreadMenuItem(if (terminalVisible) "隐藏终端" else "显示终端", Icons.Outlined.Terminal) { showMenu = false; onToggleTerminalDock() }
            }
        }
        Spacer(Modifier.weight(1f))
        if (!compact) {
            ChromeButton(
                Icons.AutoMirrored.Outlined.FormatListBulleted,
                "进度",
                selected = progressSelected,
                badge = changeCount,
                onClick = onProgressClick,
            )
            Spacer(Modifier.width(Spacing.sm))
            ChromeButton(Icons.Outlined.Settings, "设置", onClick = onSettings)
            Spacer(Modifier.width(Spacing.sm))
            ChromeButton(
                if (isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                "切换主题",
                onClick = onToggleTheme,
            )
            Spacer(Modifier.width(Spacing.sm))
            ChromeButton(
                Icons.Outlined.Terminal,
                if (terminalVisible) "隐藏终端" else "显示终端",
                selected = terminalVisible,
                onClick = onToggleTerminalDock,
            )
            Spacer(Modifier.width(Spacing.sm))
        }
        // Always-visible: the work-pane toggle is the phone's key view switch.
        ChromeButton(
            Icons.AutoMirrored.Outlined.ViewSidebar,
            if (workPaneVisible) "隐藏工作区" else "显示工作区",
            selected = workPaneVisible,
            onClick = onToggleWorkPane,
        )
    }
}

@Composable
private fun ThreadMenuItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    val colors = AndmxTheme.colors
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(label, style = AndmxTheme.typography.bodyMedium, color = colors.textPrimary) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp)) },
        onClick = onClick,
    )
}

@Composable
private fun BottomTerminalDock(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onClose: () -> Unit,
    terminalSessions: androidx.compose.runtime.snapshots.SnapshotStateList<TerminalSession>,
    selectedTerminalIndex: Int,
    onSelectedTerminalIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AndmxTheme.colors
    Column(modifier.background(colors.sunken)) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Terminal, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("Terminal", style = AndmxTheme.typography.labelLarge, color = colors.textPrimary)
            Spacer(Modifier.width(Spacing.sm))
            Text("Android/proot shell", style = AndmxTheme.typography.labelSmall, color = colors.textTertiary, modifier = Modifier.weight(1f))
            ChromeButton(
                if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp,
                if (expanded) "收起终端" else "展开终端",
                selected = false,
                onClick = onToggleExpanded,
            )
            Spacer(Modifier.width(Spacing.xs))
            Box(
                Modifier.size(28.dp).clip(Radii.pill).background(colors.surface)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭终端", tint = colors.textTertiary, modifier = Modifier.size(15.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        TerminalHost(
            sessions = terminalSessions,
            selectedIndex = selectedTerminalIndex,
            onSelectedIndexChange = onSelectedTerminalIndexChange,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ProgressPopover(
    controller: ConversationController,
    changes: List<com.andmx.workspace.FileChange>,
    onOpenDiff: (String?) -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AndmxTheme.colors
    val hasMessages = controller.items.isNotEmpty()
    val toolEvents = controller.items.filterIsInstance<com.andmx.ui.conversation.ChatItem.ToolUse>()
    val hasToolEvents = toolEvents.isNotEmpty()
    val runningTools = toolEvents.count { it.running }
    val failedTools = toolEvents.count { it.error }
    val completedTools = toolEvents.count { !it.running && !it.error }
    val goal = controller.goal
    val sourceLinks = remember(toolEvents) { progressSourceLinks(toolEvents) }
    val runLog = remember(controller.items.toList()) { runLogEntries(controller.items) }
    val verifications = remember(controller.items.toList()) { verificationEntries(controller.items) }
    val evidence = remember(controller.items.toList(), changes) { controller.evidenceLedger() }
    val blueprint = remember(controller.items.toList(), changes.size, controller.settings, controller.goal, controller.busy) {
        controller.uiReplicaBlueprint()
    }
    val visualAcceptance = remember(controller.items.toList(), changes.size, controller.settings, controller.goal, controller.busy) {
        controller.visualAcceptanceSummary()
    }
    val designSystem = remember(controller.items.toList(), changes.size, controller.settings, controller.goal, controller.busy) {
        controller.codexDesignSystemAudit()
    }
    val screenshotExtraction = remember(controller.items.toList(), changes.size, controller.settings, controller.goal, controller.busy, designSystem) {
        controller.screenshotExtractionSummary()
    }
    val referenceBoard = remember(controller.items.toList(), changes.size, controller.settings, controller.goal, controller.busy, screenshotExtraction, designSystem) {
        controller.uiReferenceBoard()
    }
    val screenshotTrace = remember(controller.items.toList(), changes.size, controller.settings, controller.goal, controller.busy, referenceBoard) {
        controller.screenshotImplementationTrace()
    }
    val policy = remember(controller.approvalMode, controller.toolCapabilities()) { controller.toolPolicySummary() }
    val checklist = remember(controller.items.toList(), changes.size, controller.settings, controller.goal, controller.busy) {
        controller.sessionChecklistSummary()
    }
    val nextAction = remember(controller.items.toList(), changes.size, controller.settings, controller.goal, controller.busy) {
        controller.nextActionDecision()
    }
    val interactionFlow = remember(controller.items.toList(), changes.size, controller.settings, controller.goal, controller.busy, screenshotExtraction, checklist, nextAction) {
        controller.codexInteractionFlow()
    }
    val selfModel = remember(controller.items.toList(), changes.size, controller.settings, controller.goal, controller.busy, designSystem, screenshotExtraction, interactionFlow) {
        controller.codexSelfModel()
    }
    val report = remember(controller.items.toList(), changes.size, controller.settings, controller.goal, controller.busy, interactionFlow) {
        controller.deliveryReport()
    }
    val inspectorSnapshot = remember(controller.items.toList(), changes.size, controller.settings, controller.goal, controller.busy) {
        buildAgentInspectorSnapshot(
            project = controller.project,
            model = controller.settings.model,
            baseUrl = controller.endpointLabel,
            apiConfigured = controller.apiConfigured,
            approvalModeLabel = controller.approvalMode.label,
            goalText = controller.goal.text,
            goalPhaseLabel = controller.goal.phase.label,
            goalNote = controller.goal.note,
            busy = controller.busy,
            reasoningEffort = controller.settings.reasoningEffort,
            persona = controller.settings.persona,
            items = controller.items.toList(),
            changedFiles = changes.size,
            builtInTools = controller.toolCapabilities().size,
            totalTools = controller.toolList().size,
            mcpServers = controller.mcpServers().size,
        )
    }
    val handoffAdvice = remember(inspectorSnapshot) { contextHandoffAdvice(inspectorSnapshot) }
    val parity = remember(inspectorSnapshot, evidence, policy, checklist, designSystem, screenshotExtraction, interactionFlow, selfModel) { controller.codexParityAudit() }
    var section by remember { mutableStateOf(ProgressSection.CHECKLIST) }

    Column(
        modifier.fillMaxWidth(0.92f).widthIn(max = 420.dp).clip(Radii.lg)
            .background(colors.surfaceElevated)
            .border(1.dp, colors.borderStrong, Radii.lg)
            .padding(Spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("任务面板", style = AndmxTheme.typography.titleSmall, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Text(
                when {
                    controller.busy -> "运行中"
                    goal.phase == GoalPhase.WAITING_APPROVAL -> "待授权"
                    goal.phase == GoalPhase.PAUSED -> "已暂停"
                    goal.phase == GoalPhase.NEEDS_SETUP -> "需设置"
                    goal.phase == GoalPhase.FAILED -> "失败"
                    failedTools > 0 -> "$failedTools 个失败"
                    goal.hasGoal -> goal.phase.label
                    completedTools > 0 -> "已完成"
                    else -> "待命"
                },
                style = AndmxTheme.typography.labelSmall,
                color = when {
                    controller.busy -> colors.accent
                    goal.phase == GoalPhase.PAUSED -> colors.textTertiary
                    goal.phase == GoalPhase.WAITING_APPROVAL -> colors.warning
                    goal.phase == GoalPhase.NEEDS_SETUP -> colors.warning
                    goal.phase == GoalPhase.FAILED -> colors.warning
                    failedTools > 0 -> colors.warning
                    else -> colors.textTertiary
                },
                modifier = Modifier.clip(Radii.pill).background(colors.sunken)
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            )
        }
        Spacer(Modifier.height(Spacing.md))
        ProgressTabs(
            selected = section,
            checklistBadge = checklist.missingCount + checklist.watchCount,
            outputBadge = changes.size,
            verificationBadge = verifications.size,
            sourceBadge = progressSourceBadge(
                toolEvents = toolEvents.size,
                visualAcceptanceWaiting = visualAcceptance.waitingCount,
                referenceBoardOpen = referenceBoard.openCount,
                designSystemOpen = designSystem.watchCount + designSystem.gapCount,
                screenshotExtractionWaiting = screenshotExtraction.waitingCount,
                interactionFlowOpen = interactionFlow.openCount,
                selfModelOpen = selfModel.watchCount + selfModel.gapCount,
            ),
            logBadge = runLog.size,
            onSelect = { section = it },
        )
        Spacer(Modifier.height(Spacing.md))

        when (section) {
            ProgressSection.CHECKLIST -> {
                ProgressNextActionCard(nextAction) { command ->
                    controller.send(command)
                }
                Spacer(Modifier.height(Spacing.md))
                Text(checklist.title, style = AndmxTheme.typography.titleSmall, color = checklistTint(checklist, colors))
                Spacer(Modifier.height(Spacing.xs))
                Text(checklist.detail, style = AndmxTheme.typography.bodySmall, color = colors.textTertiary)
                Spacer(Modifier.height(Spacing.md))
                checklist.items.forEach { item ->
                    ProgressChecklistRow(item) { command ->
                        controller.send(command)
                    }
                }
            }
            ProgressSection.STEPS -> {
                val plan = controller.taskPlanSnapshot()
                plan.items.forEach { item ->
                    ProgressPlanStep(item)
                }
            }
            ProgressSection.OUTPUT -> {
                if (changes.isEmpty()) {
                    EmptyProgressText("暂无产物")
                } else {
                    changes.take(5).forEach { change ->
                        ProgressOutputRow(change, onOpenDiff, onOpenFile)
                    }
                    if (changes.size > 5) {
                        Text("另有 ${changes.size - 5} 个文件", style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "查看全部差异",
                        style = AndmxTheme.typography.labelMedium,
                        color = colors.accent,
                        modifier = Modifier.clip(Radii.sm).clickable { onOpenDiff(null) }
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    )
                }
            }
            ProgressSection.VERIFY -> {
                if (verifications.isEmpty()) {
                    EmptyProgressText("暂无测试、构建或诊断记录")
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "运行 /verify 查看摘要格式",
                        style = AndmxTheme.typography.labelMedium,
                        color = colors.accent,
                        modifier = Modifier.clip(Radii.sm).clickable { controller.send("/verify") }
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    )
                } else {
                    verifications.forEach { entry ->
                        ProgressVerificationRow(entry)
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "生成验证摘要",
                        style = AndmxTheme.typography.labelMedium,
                        color = colors.accent,
                        modifier = Modifier.clip(Radii.sm).clickable { controller.send("/verify") }
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    )
                }
            }
            ProgressSection.SOURCES -> {
                ProgressHandoffAdviceRow(handoffAdvice) { controller.send(it) }
                Spacer(Modifier.height(Spacing.md))
                ProgressEvidenceSummary(evidence) { controller.send("/evidence") }
                Spacer(Modifier.height(Spacing.md))
                ProgressReferenceBoardSummary(referenceBoard) { controller.send("/references") }
                Spacer(Modifier.height(Spacing.md))
                ProgressBlueprintSummary(blueprint) { controller.send("/blueprint") }
                Spacer(Modifier.height(Spacing.md))
                ProgressVisualAcceptanceSummary(visualAcceptance) { controller.send("/visual-check") }
                Spacer(Modifier.height(Spacing.md))
                ProgressDesignSystemSummary(designSystem) { controller.send("/design-system") }
                Spacer(Modifier.height(Spacing.md))
                ProgressScreenshotExtractionSummary(screenshotExtraction) { controller.send("/screenshot-extract") }
                Spacer(Modifier.height(Spacing.md))
                ProgressScreenshotTraceSummary(screenshotTrace) { controller.send("/trace") }
                Spacer(Modifier.height(Spacing.md))
                ProgressInteractionFlowSummary(interactionFlow) { controller.send("/flow") }
                Spacer(Modifier.height(Spacing.md))
                ProgressSelfModelSummary(selfModel) { controller.send("/self-model") }
                Spacer(Modifier.height(Spacing.md))
                ProgressPolicySummary(policy) { controller.send("/policy") }
                Spacer(Modifier.height(Spacing.md))
                ProgressParitySummary(parity) { controller.send("/parity") }
                Spacer(Modifier.height(Spacing.md))
                ProgressDeliveryReportSummary(report) { controller.send("/report") }
                Spacer(Modifier.height(Spacing.md))
                ProgressSourceRow("内置工具", "${controller.toolList().size}")
                ProgressSourceRow("MCP 服务器", "${controller.mcpServers().size}")
                ProgressSourceRow("工具事件", "${toolEvents.size}")
                ProgressSourceRow("消息", "${controller.items.size}")
                Spacer(Modifier.height(Spacing.md))
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
                Spacer(Modifier.height(Spacing.md))
                if (sourceLinks.isNotEmpty()) {
                    Text("来源", style = AndmxTheme.typography.titleSmall, color = colors.textTertiary)
                    Spacer(Modifier.height(Spacing.sm))
                    sourceLinks.take(5).forEach { link ->
                        ProgressSourceLinkRow(
                            link = link,
                            onOpenFile = onOpenFile,
                            onOpenUrl = onOpenUrl,
                        )
                    }
                    if (sourceLinks.size > 5) {
                        Text("另有 ${sourceLinks.size - 5} 个来源", style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
                    }
                    Spacer(Modifier.height(Spacing.md))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
                    Spacer(Modifier.height(Spacing.md))
                }
                Text("最近活动", style = AndmxTheme.typography.titleSmall, color = colors.textTertiary)
                Spacer(Modifier.height(Spacing.sm))
                if (runLog.isEmpty()) {
                    EmptyProgressText("尚无活动")
                } else {
                    runLog.take(5).forEach { entry ->
                        ProgressRunLogRow(entry, onOpenFile, onOpenUrl)
                    }
                }
            }
            ProgressSection.LOG -> {
                if (runLog.isEmpty()) {
                    EmptyProgressText("尚无运行记录")
                } else {
                    runLog.forEach { entry ->
                        ProgressRunLogRow(entry, onOpenFile, onOpenUrl)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressTabs(
    selected: ProgressSection,
    checklistBadge: Int,
    outputBadge: Int,
    verificationBadge: Int,
    sourceBadge: Int,
    logBadge: Int,
    onSelect: (ProgressSection) -> Unit,
) {
    Row(Modifier.fillMaxWidth().clip(Radii.sm).background(AndmxTheme.colors.sunken).padding(Spacing.xxs)) {
        progressTabSpecs(checklistBadge, outputBadge, verificationBadge, sourceBadge, logBadge).forEach { tab ->
            ProgressTab(tab.label, selected == tab.section, tab.badge, Modifier.weight(1f)) { onSelect(tab.section) }
        }
    }
}

@Composable
private fun ProgressTab(label: String, selected: Boolean, badge: Int, modifier: Modifier, onClick: () -> Unit) {
    val colors = AndmxTheme.colors
    val bg by androidx.compose.animation.animateColorAsState(if (selected) colors.surface else androidx.compose.ui.graphics.Color.Transparent, label = "progTabBg")
    val fg by androidx.compose.animation.animateColorAsState(if (selected) colors.textPrimary else colors.textSecondary, label = "progTabFg")
    Row(
        modifier.clip(Radii.sm).background(bg)
            .clickable(onClick = onClick).padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = AndmxTheme.typography.labelMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (badge > 0) {
            Spacer(Modifier.width(Spacing.xs))
            Text("$badge", style = AndmxTheme.typography.labelSmall, color = if (selected) colors.accent else colors.textTertiary)
        }
    }
}

@Composable
private fun ProgressOutputRow(
    change: com.andmx.workspace.FileChange,
    onOpenDiff: (String?) -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val colors = AndmxTheme.colors
    val stats = remember(change.path, change.oldContent, change.newContent) {
        com.andmx.diff.DiffEngine.stats(com.andmx.diff.DiffEngine.diff(change.oldContent, change.newContent))
    }
    Row(
        Modifier.fillMaxWidth().clip(Radii.sm).clickable { onOpenFile(change.path) }
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Difference, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(change.path, style = AndmxTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, modifier = Modifier.weight(1f))
        Text("+${stats.added}", style = AndmxTheme.typography.labelSmall, color = com.andmx.ui.theme.AndmxPalette.Blue)
        Spacer(Modifier.width(Spacing.xs))
        Text("-${stats.removed}", style = AndmxTheme.typography.labelSmall, color = colors.warning)
        Spacer(Modifier.width(Spacing.sm))
        Text(
            "差异",
            style = AndmxTheme.typography.labelSmall,
            color = colors.accent,
            modifier = Modifier.clip(Radii.sm).clickable { onOpenDiff(change.path) }
                .padding(horizontal = Spacing.xs, vertical = 2.dp),
        )
    }
}

@Composable
private fun ProgressSourceRow(label: String, value: String) {
    val colors = AndmxTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AndmxTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = AndmxTheme.typography.labelMedium, color = colors.textTertiary)
    }
}

@Composable
private fun ProgressNextActionCard(decision: NextActionDecision, onRunCommand: (String) -> Unit) {
    val colors = AndmxTheme.colors
    val tint = nextActionTint(decision.priority, colors)
    Column(
        Modifier.fillMaxWidth().clip(Radii.sm).background(colors.sunken)
            .border(1.dp, if (decision.priority == NextActionPriority.BLOCKED) colors.warning else colors.border, Radii.sm)
            .padding(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.TrackChanges, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(decision.title, style = AndmxTheme.typography.labelMedium, color = tint, maxLines = 1, modifier = Modifier.weight(1f))
            Text(decision.priority.label, style = AndmxTheme.typography.labelSmall, color = tint)
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(decision.reason, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 2)
        Spacer(Modifier.height(Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                decision.command,
                style = AndmxTheme.typography.labelMedium,
                color = colors.accent,
                modifier = Modifier.clip(Radii.sm).clickable { onRunCommand(decision.command) }
                    .padding(horizontal = Spacing.xs, vertical = 2.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                "/next",
                style = AndmxTheme.typography.labelSmall,
                color = colors.accent,
                modifier = Modifier.clip(Radii.sm).clickable { onRunCommand("/next") }
                    .padding(horizontal = Spacing.xs, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun ProgressChecklistRow(item: SessionChecklistItem, onRunCommand: (String) -> Unit) {
    val colors = AndmxTheme.colors
    val tint = checklistStateTint(item.state, colors)
    Row(
        Modifier.fillMaxWidth().clip(Radii.sm).background(colors.sunken)
            .clickable(enabled = item.command.isNotBlank()) { onRunCommand(item.command) }
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.FormatListBulleted,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text("${item.title} · ${item.state.label}", style = AndmxTheme.typography.labelMedium, color = tint, maxLines = 1)
            Text(item.detail, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 1)
        }
        if (item.command.isNotBlank()) {
            Text(item.command, style = AndmxTheme.typography.labelSmall, color = colors.accent, maxLines = 1)
        }
    }
    Spacer(Modifier.height(Spacing.xs))
}

@Composable
private fun ProgressVerificationRow(entry: VerificationEntry) {
    val colors = AndmxTheme.colors
    val tint = when (entry.state) {
        VerificationState.PASSED -> colors.accent
        VerificationState.FAILED -> colors.warning
        VerificationState.RUNNING -> colors.textSecondary
    }
    Row(
        Modifier.fillMaxWidth().clip(Radii.sm).background(colors.sunken)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(entry.command, style = AndmxTheme.typography.labelMedium, color = colors.textSecondary, maxLines = 1)
            Text(entry.detail.ifBlank { "(等待输出)" }, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 1)
        }
        Text(entry.state.verificationStateLabel(), style = AndmxTheme.typography.labelSmall, color = tint)
    }
    Spacer(Modifier.height(Spacing.xs))
}

@Composable
private fun ProgressHandoffAdviceRow(advice: ContextHandoffAdvice, onRunCommand: (String) -> Unit) {
    val colors = AndmxTheme.colors
    val tint = when (advice.level) {
        HandoffAdviceLevel.OK -> colors.accent
        HandoffAdviceLevel.WATCH -> colors.textSecondary
        HandoffAdviceLevel.RECOMMENDED -> colors.warning
        HandoffAdviceLevel.REQUIRED -> colors.warning
    }
    Row(
        Modifier.fillMaxWidth().clip(Radii.sm)
            .background(if (advice.level == HandoffAdviceLevel.OK) colors.sunken else colors.warningSoft)
            .border(1.dp, if (advice.level == HandoffAdviceLevel.OK) colors.border else colors.warning, Radii.sm)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.History, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(advice.title, style = AndmxTheme.typography.labelMedium, color = tint, maxLines = 1)
            Text(advice.detail, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 1)
        }
        Text(
            advice.primaryCommand,
            style = AndmxTheme.typography.labelSmall,
            color = colors.accent,
            modifier = Modifier.clip(Radii.sm).clickable { onRunCommand(advice.primaryCommand) }
                .padding(horizontal = Spacing.xs, vertical = 2.dp),
        )
    }
}

@Composable
private fun ProgressParitySummary(audit: CodexParityAudit, onOpenParity: () -> Unit) {
    val colors = AndmxTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(Radii.sm).background(colors.sunken)
            .clickable(onClick = onOpenParity)
            .padding(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.TrackChanges, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("Codex 对标", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary, modifier = Modifier.weight(1f))
            Text("/parity", style = AndmxTheme.typography.labelSmall, color = colors.accent)
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            EvidenceCount("已具备", audit.readyCount, Modifier.weight(1f))
            EvidenceCount("注意", audit.watchCount, Modifier.weight(1f))
            EvidenceCount("缺口", audit.gapCount, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProgressDeliveryReportSummary(report: DeliveryReport, onOpenReport: () -> Unit) {
    val colors = AndmxTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(Radii.sm).background(colors.sunken)
            .clickable(onClick = onOpenReport)
            .padding(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.FormatListBulleted, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("交付报告", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary, modifier = Modifier.weight(1f))
            Text("/report", style = AndmxTheme.typography.labelSmall, color = colors.accent)
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            EvidenceCount("变更", report.changedFiles.size, Modifier.weight(1f))
            EvidenceCount("验证", report.verifications.size, Modifier.weight(1f))
            EvidenceCount(report.state.label, report.evidence.items.size, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProgressBlueprintSummary(blueprint: UiReplicaBlueprint, onOpenBlueprint: () -> Unit) {
    val colors = AndmxTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(Radii.sm).background(colors.sunken)
            .clickable(onClick = onOpenBlueprint)
            .padding(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.TrackChanges, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("UI 复刻蓝图", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary, modifier = Modifier.weight(1f))
            Text("/blueprint", style = AndmxTheme.typography.labelSmall, color = colors.accent)
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            EvidenceCount("参考", blueprint.referenceCount, Modifier.weight(1f))
            EvidenceCount("提取", blueprint.extractionTasks.size, Modifier.weight(1f))
            EvidenceCount("验收", blueprint.acceptanceChecks.size, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProgressReferenceBoardSummary(board: UiReferenceBoard, onOpenReferences: () -> Unit) {
    val colors = AndmxTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(Radii.sm).background(colors.sunken)
            .clickable(onClick = onOpenReferences)
            .padding(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("截图参考板", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary, modifier = Modifier.weight(1f))
            Text("/references", style = AndmxTheme.typography.labelSmall, color = colors.accent)
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            EvidenceCount("参考", board.referenceCount, Modifier.weight(1f))
            EvidenceCount("Codex", board.codexCount, Modifier.weight(1f))
            EvidenceCount("闭环", board.readyCount, Modifier.weight(1f))
            EvidenceCount("待处理", board.openCount, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProgressVisualAcceptanceSummary(summary: VisualAcceptanceSummary, onOpenVisualCheck: () -> Unit) {
    val colors = AndmxTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(Radii.sm).background(colors.sunken)
            .clickable(onClick = onOpenVisualCheck)
            .padding(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("视觉验收", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary, modifier = Modifier.weight(1f))
            Text("/visual-check", style = AndmxTheme.typography.labelSmall, color = colors.accent)
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            EvidenceCount("参考", summary.referenceCount, Modifier.weight(1f))
            EvidenceCount("就绪", summary.readyCount, Modifier.weight(1f))
            EvidenceCount("待处理", summary.waitingCount, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProgressDesignSystemSummary(audit: CodexDesignSystemAudit, onOpenDesignSystem: () -> Unit) {
    val colors = AndmxTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(Radii.sm).background(colors.sunken)
            .clickable(onClick = onOpenDesignSystem)
            .padding(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.TrackChanges, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("设计系统", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary, modifier = Modifier.weight(1f))
            Text("/design-system", style = AndmxTheme.typography.labelSmall, color = colors.accent)
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            EvidenceCount("已对齐", audit.readyCount, Modifier.weight(1f))
            EvidenceCount("注意", audit.watchCount, Modifier.weight(1f))
            EvidenceCount("缺口", audit.gapCount, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProgressScreenshotExtractionSummary(summary: ScreenshotExtractionSummary, onOpenScreenshotExtraction: () -> Unit) {
    val colors = AndmxTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(Radii.sm).background(colors.sunken)
            .clickable(onClick = onOpenScreenshotExtraction)
            .padding(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("截图解析", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary, modifier = Modifier.weight(1f))
            Text("/screenshot-extract", style = AndmxTheme.typography.labelSmall, color = colors.accent)
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            EvidenceCount("参考", summary.referenceCount, Modifier.weight(1f))
            EvidenceCount("已闭环", summary.readyCount, Modifier.weight(1f))
            EvidenceCount("待处理", summary.waitingCount, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProgressScreenshotTraceSummary(trace: ScreenshotImplementationTrace, onOpenTrace: () -> Unit) {
    val colors = AndmxTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(Radii.sm).background(colors.sunken)
            .clickable(onClick = onOpenTrace)
            .padding(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.TrackChanges, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("截图实现追踪", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary, modifier = Modifier.weight(1f))
            Text("/trace", style = AndmxTheme.typography.labelSmall, color = colors.accent)
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            EvidenceCount("参考", trace.referenceCount, Modifier.weight(1f))
            EvidenceCount("闭环", trace.readyCount, Modifier.weight(1f))
            EvidenceCount("待处理", trace.waitingCount, Modifier.weight(1f))
            EvidenceCount("变更", trace.changedFileCount, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProgressSelfModelSummary(model: CodexSelfModel, onOpenSelfModel: () -> Unit) {
    val colors = AndmxTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(Radii.sm).background(colors.sunken)
            .clickable(onClick = onOpenSelfModel)
            .padding(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Psychology, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("自我模型", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary, modifier = Modifier.weight(1f))
            Text("/self-model", style = AndmxTheme.typography.labelSmall, color = colors.accent)
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            EvidenceCount("已建模", model.readyCount, Modifier.weight(1f))
            EvidenceCount("关注", model.watchCount, Modifier.weight(1f))
            EvidenceCount("缺口", model.gapCount, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProgressInteractionFlowSummary(flow: CodexInteractionFlow, onOpenFlow: () -> Unit) {
    val colors = AndmxTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(Radii.sm).background(colors.sunken)
            .clickable(onClick = onOpenFlow)
            .padding(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.TrackChanges, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("交互流程", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary, modifier = Modifier.weight(1f))
            Text("/flow", style = AndmxTheme.typography.labelSmall, color = colors.accent)
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            EvidenceCount("就绪", flow.readyCount, Modifier.weight(1f))
            EvidenceCount("进行中", flow.activeCount, Modifier.weight(1f))
            EvidenceCount("注意", flow.watchCount, Modifier.weight(1f))
            EvidenceCount("阻塞", flow.blockedCount, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProgressEvidenceSummary(ledger: EvidenceLedger, onOpenEvidence: () -> Unit) {
    val colors = AndmxTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(Radii.sm).background(colors.sunken)
            .clickable(onClick = onOpenEvidence)
            .padding(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("证据账本", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary, modifier = Modifier.weight(1f))
            Text("/evidence", style = AndmxTheme.typography.labelSmall, color = colors.accent)
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            EvidenceCount("文件", ledger.fileCount, Modifier.weight(1f))
            EvidenceCount("网页", ledger.webCount, Modifier.weight(1f))
            EvidenceCount("UI", ledger.uiReferenceCount, Modifier.weight(1f))
            EvidenceCount("验证", ledger.verificationCount, Modifier.weight(1f))
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            EvidenceCount("变更", ledger.changeCount, Modifier.weight(1f))
            EvidenceCount("授权", ledger.approvalCount, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProgressPolicySummary(summary: ToolPolicySummary, onOpenPolicy: () -> Unit) {
    val colors = AndmxTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(Radii.sm).background(colors.sunken)
            .clickable(onClick = onOpenPolicy)
            .padding(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Security, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("授权策略", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary, modifier = Modifier.weight(1f))
            Text("/policy", style = AndmxTheme.typography.labelSmall, color = colors.accent)
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            EvidenceCount("自动", summary.autoCount, Modifier.weight(1f))
            EvidenceCount("询问", summary.promptCount, Modifier.weight(1f))
            EvidenceCount("阻止", summary.denyCount, Modifier.weight(1f))
            EvidenceCount(summary.mode.label, summary.rows.sumOf { it.toolCount }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            EvidenceCount("安全边界", summary.boundaryRows.size, Modifier.weight(1f))
            EvidenceCount("需确认", summary.boundaryPromptCount, Modifier.weight(1f))
            EvidenceCount("交给用户", summary.boundaryHandoffCount, Modifier.weight(1f))
        }
    }
}

@Composable
private fun EvidenceCount(label: String, count: Int, modifier: Modifier = Modifier) {
    val colors = AndmxTheme.colors
    Column(modifier.clip(Radii.sm).background(colors.surface).padding(horizontal = Spacing.sm, vertical = Spacing.xs)) {
        Text("$count", style = AndmxTheme.typography.labelMedium, color = colors.textPrimary, maxLines = 1)
        Text(label, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 1)
    }
}

private enum class ProgressSourceKind { FILE, WEB }

private data class ProgressSourceLink(
    val kind: ProgressSourceKind,
    val label: String,
    val target: String,
    val toolName: String,
)

@Composable
private fun ProgressSourceLinkRow(
    link: ProgressSourceLink,
    onOpenFile: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val colors = AndmxTheme.colors
    val icon = when (link.kind) {
        ProgressSourceKind.FILE -> Icons.Outlined.Folder
        ProgressSourceKind.WEB -> Icons.Outlined.Search
    }
    Row(
        Modifier.fillMaxWidth().clip(Radii.sm)
            .clickable {
                when (link.kind) {
                    ProgressSourceKind.FILE -> onOpenFile(link.target)
                    ProgressSourceKind.WEB -> onOpenUrl(link.target)
                }
            }
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (link.kind == ProgressSourceKind.WEB) colors.accent else colors.textSecondary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(link.label, style = AndmxTheme.typography.labelMedium, color = colors.textSecondary, maxLines = 1)
            Text(link.toolName, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 1)
        }
        Text("打开", style = AndmxTheme.typography.labelSmall, color = colors.accent)
    }
}

@Composable
private fun ProgressRunLogRow(
    entry: RunLogEntry,
    onOpenFile: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val colors = AndmxTheme.colors
    val icon = runLogIcon(entry)
    val canOpen = entry.targetKind != RunLogTargetKind.NONE
    Row(
        Modifier.fillMaxWidth()
            .clip(Radii.sm)
            .clickable(enabled = canOpen) {
                when (entry.targetKind) {
                    RunLogTargetKind.FILE -> onOpenFile(entry.targetPath)
                    RunLogTargetKind.WEB -> onOpenUrl(entry.targetUrl)
                    RunLogTargetKind.NONE -> Unit
                }
            }
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = runLogTint(entry, colors), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = AndmxTheme.typography.labelMedium, color = colors.textSecondary, maxLines = 1)
            Text(entry.detail, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 1)
        }
        Text(
            when {
                canOpen && entry.state == RunLogState.DONE -> "打开"
                else -> entry.state.runLogStateLabel()
            },
            style = AndmxTheme.typography.labelSmall,
            color = when {
                entry.state in setOf(RunLogState.FAILED, RunLogState.DENIED) -> colors.warning
                entry.state == RunLogState.RUNNING -> colors.accent
                canOpen && entry.state == RunLogState.DONE -> colors.accent
                entry.state == RunLogState.WAITING -> colors.warning
                else -> colors.textTertiary
            },
        )
    }
}

private fun runLogIcon(entry: RunLogEntry): androidx.compose.ui.graphics.vector.ImageVector = when (entry.kind) {
    RunLogKind.USER -> Icons.Outlined.TrackChanges
    RunLogKind.ASSISTANT -> Icons.Outlined.Info
    RunLogKind.TOOL -> Icons.Outlined.Extension
    RunLogKind.APPROVAL -> Icons.Outlined.History
}

private fun runLogTint(
    entry: RunLogEntry,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when {
    entry.state in setOf(RunLogState.FAILED, RunLogState.DENIED, RunLogState.WAITING) -> colors.warning
    entry.state == RunLogState.RUNNING -> colors.accent
    entry.kind == RunLogKind.USER -> colors.accent
    else -> colors.textSecondary
}

private fun checklistTint(
    summary: SessionChecklistSummary,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when {
    summary.missingCount > 0 -> colors.warning
    summary.watchCount > 0 -> colors.textSecondary
    else -> colors.accent
}

private fun checklistStateTint(
    state: ChecklistState,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when (state) {
    ChecklistState.READY -> colors.accent
    ChecklistState.WATCH -> colors.textSecondary
    ChecklistState.MISSING -> colors.warning
}

private fun nextActionTint(
    priority: NextActionPriority,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when (priority) {
    NextActionPriority.BLOCKED -> colors.warning
    NextActionPriority.ACTIVE -> colors.accent
    NextActionPriority.REVIEW -> colors.textSecondary
    NextActionPriority.VERIFY -> colors.warning
    NextActionPriority.HANDOFF -> colors.textSecondary
    NextActionPriority.CONTINUE -> colors.accent
}

@Composable
private fun EmptyProgressText(text: String) {
    Text(text, style = AndmxTheme.typography.bodySmall, color = AndmxTheme.colors.textSecondary)
}

@Composable
private fun ProgressPlanStep(item: com.andmx.agent.TaskPlanItem) {
    val colors = AndmxTheme.colors
    val done = item.status == PlanItemStatus.DONE
    val active = item.status == PlanItemStatus.ACTIVE
    val blocked = item.status == PlanItemStatus.BLOCKED
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.md)) {
        Box(
            Modifier.size(18.dp).clip(Radii.pill).background(
                when {
                    blocked -> colors.warning
                    done -> colors.accent
                    active -> colors.textPrimary
                    else -> colors.sunken
                },
            ),
            contentAlignment = Alignment.Center,
        ) {
            if (!done && !active && !blocked) {
                Box(Modifier.size(8.dp).clip(Radii.pill).background(colors.textTertiary))
            }
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                "${item.title} · ${item.status.label}",
                style = AndmxTheme.typography.bodyMedium,
                color = when {
                    blocked -> colors.warning
                    done || active -> colors.textPrimary
                    else -> colors.textSecondary
                },
            )
            Spacer(Modifier.height(2.dp))
            Text(item.detail, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 1)
        }
    }
}

private data class ProgressToolKind(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tint: (com.andmx.ui.theme.AndmxColors) -> androidx.compose.ui.graphics.Color,
)

private fun progressToolKind(name: String): ProgressToolKind = when (name) {
    "run_shell" -> ProgressToolKind("执行", Icons.Outlined.Terminal) { it.warning }
    "read_file" -> ProgressToolKind("读取", Icons.AutoMirrored.Outlined.FormatListBulleted) { it.textSecondary }
    "write_file", "edit_file", "apply_patch" -> ProgressToolKind("编辑", Icons.Outlined.Difference) { it.warning }
    "list_dir" -> ProgressToolKind("目录", Icons.Outlined.Folder) { it.textSecondary }
    "browse" -> ProgressToolKind("浏览", Icons.Outlined.PlayCircle) { it.accent }
    "web_search" -> ProgressToolKind("搜索", Icons.Outlined.Search) { it.accent }
    else -> ProgressToolKind("工具", Icons.Outlined.Extension) { it.textSecondary }
}

private fun progressToolPreview(name: String, args: String): String = ToolArgs.preview(name, args, limit = 80)

private fun progressToolUrl(name: String, args: String): String = ToolArgs.webUrl(name, args)

private fun progressSourceLinks(
    tools: List<com.andmx.ui.conversation.ChatItem.ToolUse>,
): List<ProgressSourceLink> {
    val seen = linkedSetOf<String>()
    val links = mutableListOf<ProgressSourceLink>()
    tools.asReversed().forEach { tool ->
        val filePath = ToolArgs.filePath(tool.name, tool.args)
        if (filePath.isNotBlank() && seen.add("file:$filePath")) {
            links += ProgressSourceLink(
                kind = ProgressSourceKind.FILE,
                label = filePath,
                target = filePath,
                toolName = tool.name,
            )
        }
        val url = progressToolUrl(tool.name, tool.args)
        if (url.isNotBlank() && seen.add("web:$url")) {
            links += ProgressSourceLink(
                kind = ProgressSourceKind.WEB,
                label = progressToolPreview(tool.name, tool.args),
                target = url,
                toolName = tool.name,
            )
        }
    }
    return links
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val colors = AndmxTheme.colors
    var value by remember { mutableStateOf(initial) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(com.andmx.ui.theme.Radii.lg).background(colors.surface).padding(Spacing.xl).widthIn(max = 360.dp).fillMaxWidth(),
        ) {
            androidx.compose.material3.Text("重命名对话", style = AndmxTheme.typography.titleMedium, color = colors.textPrimary)
            Spacer(Modifier.height(Spacing.md))
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = AndmxTheme.typography.bodyLarge.copy(color = colors.textPrimary),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, colors.border, com.andmx.ui.theme.Radii.sm)
                    .padding(horizontal = Spacing.md, vertical = Spacing.md),
            )
            Spacer(Modifier.height(Spacing.lg))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                androidx.compose.material3.Text(
                    "取消", style = AndmxTheme.typography.labelLarge, color = colors.textSecondary,
                    modifier = Modifier.clip(com.andmx.ui.theme.Radii.sm).clickable(onClick = onDismiss)
                        .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
                Spacer(Modifier.width(Spacing.sm))
                androidx.compose.material3.Text(
                    "保存", style = AndmxTheme.typography.labelLarge, color = colors.onAccent,
                    modifier = Modifier.clip(com.andmx.ui.theme.Radii.sm).background(colors.sendActive)
                        .clickable { onConfirm(value) }.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun ChromeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    selected: Boolean = false,
    badge: Int = 0,
    onClick: () -> Unit,
) {
    val colors = AndmxTheme.colors
    val bg by androidx.compose.animation.animateColorAsState(if (selected) colors.selected else colors.sunken, label = "chromeBg")
    Box(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
        if (badge > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).size(7.dp).clip(RoundedCornerShape(999.dp))
                    .background(colors.accent),
            )
        }
    }
}

@Composable
private fun VerticalHairline() {
    val colors = AndmxTheme.colors
    Box(
        Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(colors.border),
    )
}

private fun groupConversations(
    rows: List<com.andmx.data.ConversationEntity>,
): List<com.andmx.ui.workbench.ProjectGroup> =
    rows.groupBy { it.project }.map { (project, convs) ->
        com.andmx.ui.workbench.ProjectGroup(
            name = project,
            conversations = convs.map {
                com.andmx.ui.workbench.ConversationItem(
                    id = it.id,
                    title = it.title.ifBlank { "未命名对话" },
                    time = relativeTime(it.updatedAt),
                    goalText = it.goalText,
                    goalPhase = parseGoalPhase(it.goalPhase),
                    goalNote = it.goalNote,
                )
            },
        )
    }

private fun parseGoalPhase(value: String): GoalPhase =
    runCatching { GoalPhase.valueOf(value) }.getOrDefault(GoalPhase.EMPTY)

internal data class WorkbenchStateSnapshot(
    val workPaneTab: WorkPaneTab = WorkPaneTab.TERMINAL,
    val workPaneVisible: Boolean = true,
    val terminalDockVisible: Boolean = false,
    val terminalDockTall: Boolean = false,
    val selectedFilePath: String = "",
    val selectedDiffPath: String = "",
    val browserUrl: String = "",
    val fileCurrentGuestPath: String = "/",
    val fileViewingGuestPath: String = "",
)

internal fun com.andmx.data.ConversationEntity.toWorkbenchState(): WorkbenchStateSnapshot =
    WorkbenchStateSnapshot(
        workPaneTab = parseWorkPaneTab(workPaneTab),
        workPaneVisible = workPaneVisible,
        terminalDockVisible = terminalDockVisible,
        terminalDockTall = terminalDockTall,
        selectedFilePath = selectedFilePath,
        selectedDiffPath = selectedDiffPath,
        browserUrl = browserUrl,
        fileCurrentGuestPath = fileCurrentGuestPath.ifBlank { "/" },
        fileViewingGuestPath = fileViewingGuestPath,
    )

internal fun parseWorkPaneTab(value: String): WorkPaneTab =
    runCatching { WorkPaneTab.valueOf(value) }.getOrDefault(WorkPaneTab.TERMINAL)

private fun relativeTime(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    val min = diff / 60_000
    val hr = diff / 3_600_000
    val day = diff / 86_400_000
    return when {
        min < 1 -> "刚刚"
        min < 60 -> "$min 分"
        hr < 24 -> "$hr 小时"
        day < 30 -> "$day 天"
        else -> "${day / 30} 个月"
    }
}

/** Apply an alpha via graphicsLayer (used for the inline work-pane fade-in). */
private fun Modifier.graphicsLayerAlpha(alpha: Float): Modifier =
    this.then(Modifier.graphicsLayer { this.alpha = alpha })
