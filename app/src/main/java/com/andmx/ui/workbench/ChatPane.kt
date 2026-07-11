package com.andmx.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.agent.SlashCommands
import com.andmx.ui.conversation.ConversationController
import com.andmx.ui.conversation.ConversationGoal
import com.andmx.ui.conversation.GoalPhase
import com.andmx.ui.conversation.label
import com.andmx.ui.conversation.MessageList
import com.andmx.ui.components.IconActionButton
import com.andmx.ui.components.IconVariant
import com.andmx.ui.components.InfoRow
import com.andmx.ui.rememberScreenHeightDp
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Elevation
import com.andmx.ui.theme.Radii
import com.andmx.ui.theme.Spacing

@Composable
fun ChatPane(
    controller: ConversationController,
    projectName: String,
    onOpenSettings: () -> Unit = {},
    onOpenDiff: (String?) -> Unit = {},
    onOpenFile: (String) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    onOpenReference: (String) -> Unit = {},
    onOpenTerminal: (String?) -> Unit = {},
    showGoal: Boolean = false,
    onShowGoalChange: (Boolean) -> Unit = {},
    initialDraft: String = "",
    /** When false, the composer is omitted (the caller renders its own). */
    showComposer: Boolean = true,
    /** When false, the AgentContextStrip (model/project chips) is omitted. */
    showContextStrip: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = AndmxTheme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val hasMessages = controller.items.isNotEmpty()
    val latestTurn = remember(controller.executionState, controller.items.toList(), controller.busy) {
        buildLatestTurnSummary(controller.executionState, controller.items, controller.busy)
    }
    var draft by remember { mutableStateOf(initialDraft) }
    val attachments = remember { androidx.compose.runtime.mutableStateListOf<com.andmx.ui.conversation.Attachment>() }
    val guestFs = remember { com.andmx.exec.files.GuestFs(com.andmx.exec.proot.ProotRuntime(context)) }

    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) com.andmx.ui.conversation.Attachments.fromUri(context, uri)?.let { attachments.add(it) }
    }

    fun addUri(uri: android.net.Uri) {
        com.andmx.ui.conversation.Attachments.fromUri(context, uri)?.let { attachments.add(it) }
    }

    fun submit(text: String) {
        controller.send(text, attachments.toList())
        draft = ""
        attachments.clear()
    }

    fun openFocusTarget(target: WorkbenchFocusTarget) {
        when (target) {
            is WorkbenchFocusTarget.Files -> target.path?.let(onOpenFile)
            is WorkbenchFocusTarget.Terminal -> onOpenTerminal(target.sessionKey)
            is WorkbenchFocusTarget.Diff -> onOpenDiff(target.path)
            is WorkbenchFocusTarget.Browser -> target.url?.let(onOpenUrl)
            is WorkbenchFocusTarget.Reference -> target.assetPath?.let(onOpenReference)
            WorkbenchFocusTarget.Plugins -> onOpenSettings()
        }
    }

    LaunchedEffect(controller.showGoalCommand) {
        if (controller.showGoalCommand) {
            onShowGoalChange(true)
            controller.showGoalCommand = false
        }
    }

    Box(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        androidx.compose.animation.Crossfade(
            targetState = hasMessages,
            animationSpec = androidx.compose.animation.core.tween(com.andmx.ui.theme.Motion.DUR_SLOW, easing = com.andmx.ui.theme.Motion.EASE_OUT),
            label = "chatEmptyState",
        ) { empty ->
        if (!empty) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center).fillMaxWidth()
                    .padding(horizontal = Spacing.xxl),
            ) {
                if (showContextStrip) AgentContextStrip(
                    controller = controller,
                    projectName = projectName,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.xl))
                Text(
                    text = "我们应该在 $projectName 中构建什么?",
                    style = AndmxTheme.typography.headlineLarge,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Spacing.xxl))
                if (showComposer) {
                    MentionMenu(draft, guestFs) { draft = it }
                    SlashMenu(draft) { draft = it }
                    ComposerBlock(
                        controller, draft, { draft = it }, ::submit, onOpenSettings,
                        attachments, { picker.launch(arrayOf("*/*")) }, { attachments.remove(it) },
                        { draft += it }, ::addUri, { onShowGoalChange(true) }, withChips = projectName,
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                if (showContextStrip) AgentContextStrip(
                    controller = controller,
                    projectName = projectName,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = Spacing.xl)
                        .padding(top = Spacing.lg, bottom = Spacing.sm),
                )
                latestTurn?.let { summary ->
                    LatestTurnStrip(
                        summary = summary,
                        onOpenTarget = ::openFocusTarget,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl)
                            .padding(bottom = Spacing.sm),
                    )
                }
                MessageList(
                    items = controller.items,
                    onApprove = controller::resolveApproval,
                    onRetry = controller::retry,
                    onBranch = controller::branchFrom,
                    onContinuePrompt = controller::continueFromPrompt,
                    onResumePrompt = controller::resumeFromPrompt,
                    onOpenDiff = onOpenDiff,
                    onOpenFile = onOpenFile,
                    onOpenUrl = onOpenUrl,
                    onOpenReference = onOpenReference,
                    onOpenTerminal = onOpenTerminal,
                    onRunCommand = controller::send,
                    onEdit = { idx ->
                        controller.editFrom(idx)?.let { revertedText ->
                            draft = revertedText
                        }
                    },
                    editingIndex = controller.editIndex,
                    onCancelEdit = {
                        controller.cancelEdit()
                        draft = ""
                    },
                    modifier = Modifier.weight(1f),
                )
                if (showComposer) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .imePadding()
                            .navigationBarsPadding()
                            .padding(horizontal = Spacing.xl)
                            .padding(bottom = Spacing.lg),
                    ) {
                        // Composer fills the full chat width — no narrow cap, so it
                        // aligns edge-to-edge with the message list above it.
                        Column(Modifier.fillMaxWidth()) {
                            MentionMenu(draft, guestFs) { draft = it }
                            SlashMenu(draft) { draft = it }
                            ComposerBlock(
                                controller, draft, { draft = it }, ::submit, onOpenSettings,
                                attachments, { picker.launch(arrayOf("*/*")) }, { attachments.remove(it) },
                                { draft += it }, ::addUri, { onShowGoalChange(true) }, withChips = null,
                            )
                        }
                    }
                }
            }
        }
        } // end Crossfade
        androidx.compose.animation.AnimatedVisibility(
            visible = showGoal,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(com.andmx.ui.theme.Motion.DUR_STD)) + androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(com.andmx.ui.theme.Motion.DUR_STD, easing = com.andmx.ui.theme.Motion.EASE_OUT)) { it / 2 },
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(com.andmx.ui.theme.Motion.DUR_FAST)) + androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(com.andmx.ui.theme.Motion.DUR_FAST)) { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            GoalOverlay(
                controller = controller,
                projectName = projectName,
                onDismiss = { onShowGoalChange(false) },
                onContinueGoal = {
                    val goalText = controller.goal.text
                    if (goalText.isNotBlank()) {
                        submit("继续推进: $goalText")
                        onShowGoalChange(false)
                    }
                },
                // Lift above the composer: its surface + tool row averages ~116dp.
                modifier = Modifier.padding(bottom = 116.dp),
            )
        }
    }
}

/**
 * Build the reasoning tag appended to the model label in the composer pill.
 * Reflects the actual wire value (minimal/low/medium/high for EFFORT, enabled
 * for THINKING) rather than a fabricated "低中高".
 */
private fun reasoningSuffix(controller: ConversationController): String {
    val effort = controller.settings.reasoningEffort
    if (effort.isBlank() || effort == "off") return ""
    val style = controller.currentModelReasoning?.style ?: return ""
    return when (style) {
        com.andmx.llm.provider.ReasoningStyle.EFFORT -> " · $effort"
        com.andmx.llm.provider.ReasoningStyle.THINKING -> " · thinking"
        com.andmx.llm.provider.ReasoningStyle.NONE -> ""
    }
}

@Composable
private fun ComposerBlock(
    controller: ConversationController,
    draft: String,
    onDraft: (String) -> Unit,
    onSend: (String) -> Unit,
    onOpenSettings: () -> Unit,
    attachments: List<com.andmx.ui.conversation.Attachment>,
    onAttachClick: () -> Unit,
    onRemoveAttachment: (com.andmx.ui.conversation.Attachment) -> Unit,
    onDropText: (String) -> Unit,
    onDropUri: (android.net.Uri) -> Unit,
    onGoalClick: () -> Unit,
    withChips: String?,
) {
    Composer(
        value = draft,
        onValueChange = onDraft,
        modifier = Modifier.fillMaxWidth(),
        placeholder = if (controller.busy) "执行中…" else if (withChips != null) "随心输入" else "要求后续变更",
        enabled = !controller.busy,
        modelLabel = controller.settings.model + reasoningSuffix(controller),
        accessLabel = controller.approvalMode.label,
        busy = controller.busy,
        onStop = controller::stop,
        attachments = attachments,
        onAttachClick = onAttachClick,
        onRemoveAttachment = onRemoveAttachment,
        onDropText = onDropText,
        onDropUri = onDropUri,
        accessMode = controller.approvalMode,
        onAccessModeSelected = controller::setApprovalMode,
        onModelClick = onOpenSettings,
        reasoningEffort = controller.settings.reasoningEffort,
        reasoning = controller.currentModelReasoning,
        onReasoningEffortSelected = { effort ->
            controller.saveSettings(controller.settings.copy(reasoningEffort = effort))
        },
        providers = controller.providers,
        activeProviderId = controller.currentProvider?.id.orEmpty(),
        selectedModel = controller.settings.model,
        onSwitchModel = controller::switchModel,
        onAddModel = controller::addModel,
        onConfigureProvider = onOpenSettings,
        onConfigureModels = onOpenSettings,
        goalLabel = goalPillLabel(controller),
        onGoalClick = onGoalClick,
        onSend = onSend,
        contextChips = if (withChips != null) {
            {
                ContextChip(withChips)
                ContextChip("Android/proot")
                ContextChip("${controller.toolList().size} tools")
            }
        } else null,
    )
}

@Composable
private fun GoalOverlay(
    controller: ConversationController,
    projectName: String,
    onDismiss: () -> Unit,
    onContinueGoal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AndmxTheme.colors
    val goal = controller.goal
    val hasGoal = goal.hasGoal
    val actions = goalOverlayActionState(goal, controller.busy)
    var editing by remember(goal.text, hasGoal) { mutableStateOf(!hasGoal) }
    var draftGoal by remember(goal.text) { mutableStateOf(goal.text) }

    fun saveDraftGoal() {
        if (controller.setPersistentGoal(draftGoal, note = "由目标浮层设置")) {
            editing = false
        }
    }

    // Cap height so the overlay never runs past the top bar in landscape; content
    // scrolls if it exceeds the cap (e.g. the 84dp edit field + meta + actions).
    val maxHeight = (rememberScreenHeightDp() * 0.7f).dp
    Column(
        modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xxl)
            .clip(Radii.lg)
            .background(colors.surfaceElevated)
            .border(1.dp, colors.borderStrong, Radii.lg)
            .padding(Spacing.xl),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.TrackChanges, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("当前目标", style = AndmxTheme.typography.titleMedium, color = colors.textPrimary, modifier = Modifier.weight(1f))
            if (!editing && actions.canEdit) {
                GoalIconAction(
                    icon = Icons.Outlined.Edit,
                    contentDescription = "编辑目标",
                    onClick = {
                        draftGoal = goal.text
                        editing = true
                    },
                )
                Spacer(Modifier.width(Spacing.xs))
            }
            Text(
                "关闭",
                style = AndmxTheme.typography.labelMedium,
                color = colors.textTertiary,
                modifier = Modifier.clip(Radii.sm).clickable(onClick = onDismiss).padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            )
        }
        Spacer(Modifier.height(Spacing.md))
        if (editing) {
            GoalEditField(
                value = draftGoal,
                onValueChange = { draftGoal = it.lineSequence().take(3).joinToString("\n").take(160) },
                placeholder = "输入当前线程目标",
            )
        } else {
            Text(
                if (!hasGoal) {
                    "还没有开始目标。发送第一条要求后，AndMX 会围绕这个项目、沙箱和工具链推进。"
                } else {
                    goal.text
                },
                style = if (hasGoal) AndmxTheme.typography.titleSmall else AndmxTheme.typography.bodyMedium,
                color = if (hasGoal) colors.textPrimary else colors.textSecondary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(Spacing.md))
        if (hasGoal) {
            GoalMetaRow("状态", goal.phase.label)
            if (goal.note.isNotBlank()) GoalMetaRow("最近", goal.note)
            GoalMetaRow("项目", projectName)
            Spacer(Modifier.height(Spacing.md))
        }
        Text(
            "权限 ${controller.approvalMode.label} · 模型 ${controller.settings.model} · 工具 ${controller.toolList().size}",
            style = AndmxTheme.typography.labelMedium,
            color = colors.textTertiary,
        )
        if (!controller.busy) {
            Spacer(Modifier.height(Spacing.md))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (actions.primaryAction) {
                    GoalOverlayPrimaryAction.CONTINUE -> {
                        GoalIconAction(Icons.Outlined.PlayCircle, "继续推进", primary = true, onClick = onContinueGoal)
                        Spacer(Modifier.width(Spacing.xs))
                    }
                    GoalOverlayPrimaryAction.OPEN_SETTINGS -> {
                        GoalIconAction(Icons.Outlined.Settings, "打开设置", primary = true, warning = true, onClick = controller.onOpenSettings)
                        Spacer(Modifier.width(Spacing.xs))
                    }
                    GoalOverlayPrimaryAction.RESUME -> {
                        GoalIconAction(
                            Icons.Outlined.PlayCircle,
                            "恢复目标",
                            primary = true,
                            onClick = { controller.resumeGoal("由目标浮层恢复,等待继续推进") },
                        )
                        Spacer(Modifier.width(Spacing.xs))
                    }
                    GoalOverlayPrimaryAction.NONE -> Unit
                }
                if (editing) {
                    GoalIconAction(Icons.Outlined.Check, "保存目标", enabled = draftGoal.isNotBlank(), onClick = ::saveDraftGoal)
                    Spacer(Modifier.width(Spacing.xs))
                    GoalIconAction(
                        Icons.Outlined.Close,
                        "取消编辑",
                        onClick = {
                            draftGoal = goal.text
                            editing = false
                        },
                    )
                    Spacer(Modifier.width(Spacing.xs))
                }
                if (actions.canPause) {
                    GoalIconAction(
                        Icons.Outlined.Pause,
                        "暂停目标",
                        onClick = { controller.pauseGoal("由目标浮层暂停") },
                    )
                    Spacer(Modifier.width(Spacing.xs))
                }
                if (actions.canClear) {
                    GoalIconAction(
                        Icons.Outlined.Delete,
                        "清除目标",
                        warning = true,
                        onClick = {
                            controller.clearGoal()
                            draftGoal = ""
                            editing = true
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GoalEditField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    val colors = AndmxTheme.colors
    Box(
        Modifier.fillMaxWidth().heightIn(min = 84.dp)
            .clip(Radii.sm)
            .background(colors.sunken)
            .border(1.dp, colors.border, Radii.sm)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        if (value.isBlank()) {
            Text(
                placeholder,
                style = AndmxTheme.typography.bodyMedium,
                color = colors.textTertiary,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = AndmxTheme.typography.bodyMedium.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun GoalIconAction(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    primary: Boolean = false,
    warning: Boolean = false,
    onClick: () -> Unit,
) {
    val variant = when {
        primary -> IconVariant.PRIMARY
        warning -> IconVariant.WARNING
        else -> IconVariant.PLAIN
    }
    IconActionButton(icon = icon, onClick = onClick, contentDescription = contentDescription, enabled = enabled, variant = variant, size = 34.dp)
}

@Composable
private fun GoalMetaRow(label: String, value: String) = InfoRow(label = label, value = value, labelWidth = 48.dp)

internal enum class GoalOverlayPrimaryAction {
    NONE,
    CONTINUE,
    OPEN_SETTINGS,
    RESUME,
}

internal data class GoalOverlayActionState(
    val primaryAction: GoalOverlayPrimaryAction,
    val canEdit: Boolean,
    val canPause: Boolean,
    val canClear: Boolean,
)

internal fun goalOverlayActionState(goal: ConversationGoal, busy: Boolean): GoalOverlayActionState {
    val hasGoal = goal.hasGoal
    return GoalOverlayActionState(
        primaryAction = when {
            !hasGoal || busy -> GoalOverlayPrimaryAction.NONE
            goal.phase == GoalPhase.NEEDS_SETUP -> GoalOverlayPrimaryAction.OPEN_SETTINGS
            goal.phase == GoalPhase.PAUSED -> GoalOverlayPrimaryAction.RESUME
            else -> GoalOverlayPrimaryAction.CONTINUE
        },
        canEdit = !busy,
        canPause = hasGoal && !busy && goal.phase != GoalPhase.PAUSED,
        canClear = hasGoal && !busy,
    )
}

private fun goalPillLabel(controller: ConversationController): String {
    val goal = controller.goal
    if (!goal.hasGoal) return ""
    return when (goal.phase) {
        GoalPhase.RUNNING -> "运行中"
        GoalPhase.PAUSED -> "已暂停"
        GoalPhase.WAITING_APPROVAL -> "待授权"
        GoalPhase.NEEDS_SETUP -> "需设置"
        GoalPhase.FAILED -> "失败"
        GoalPhase.READY -> "待继续"
        GoalPhase.EMPTY -> ""
    }
}

@Composable
private fun AgentContextStrip(
    controller: ConversationController,
    projectName: String,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AndmxTheme.colors
    val scroll = rememberScrollState()
    val modelLabel = if (controller.apiConfigured) {
        controller.settings.model.ifBlank { "模型已配置" }
    } else {
        "模型未配置"
    }
    val mcpCount = controller.mcpServers().size
    Row(
        modifier = modifier
            .horizontalScroll(scroll)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContextStatusChip(Icons.Outlined.Folder, projectName)
        ContextStatusChip(Icons.Outlined.Terminal, "Android/proot")
        ContextStatusChip(
            icon = if (controller.apiConfigured) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff,
            label = modelLabel,
            warning = !controller.apiConfigured,
            onClick = onOpenSettings,
        )
        ContextStatusChip(
            icon = Icons.Outlined.Bolt,
            label = controller.approvalMode.label,
            warning = true,
            onClick = controller::cycleApprovalMode,
        )
        ContextStatusChip(Icons.Outlined.Extension, "${controller.toolList().size} 工具")
        if (controller.goal.hasGoal) ContextStatusChip(Icons.Outlined.TrackChanges, controller.goal.phase.label)
        if (mcpCount > 0) ContextStatusChip(Icons.Outlined.Extension, "$mcpCount MCP")
        Text(
            text = if (controller.busy) "运行中" else "就绪",
            style = AndmxTheme.typography.labelMedium,
            color = if (controller.busy) colors.accent else colors.textTertiary,
            modifier = Modifier.padding(start = Spacing.sm),
        )
    }
}

@Composable
private fun ContextStatusChip(
    icon: ImageVector,
    label: String,
    warning: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = AndmxTheme.colors
    var chipModifier = Modifier
        .padding(end = Spacing.sm)
        .clip(Radii.pill)
        .background(if (warning) colors.warningSoft else colors.sunken)
        .border(1.dp, if (warning) colors.warning.copy(alpha = 0.35f) else colors.border, Radii.pill)
        .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
    if (onClick != null) chipModifier = chipModifier.clickable(onClick = onClick)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = chipModifier) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (warning) colors.warning else colors.textSecondary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = label,
            style = AndmxTheme.typography.labelMedium,
            color = if (warning) colors.warning else colors.textSecondary,
            maxLines = 1,
        )
    }
}

/** File-mention autocomplete: lists rootfs candidates for an in-progress `@path`. */
@Composable
private fun MentionMenu(draft: String, guestFs: com.andmx.exec.files.GuestFs, onComplete: (String) -> Unit) {
    val colors = AndmxTheme.colors
    val query = remember(draft) { com.andmx.agent.MentionResolver.parse(draft) } ?: return
    val candidates = remember(query.listDir, query.prefix) {
        runCatching { guestFs.list(query.listDir) }.getOrDefault(emptyList())
            .filter { it.startsWith(query.prefix, ignoreCase = true) }
            .take(8)
    }
    if (candidates.isEmpty()) return

    Column(
        Modifier.fillMaxWidth().padding(bottom = Spacing.sm)
            .clip(Radii.md).background(colors.surfaceElevated)
            .border(1.dp, colors.border, Radii.md).padding(Spacing.xs),
    ) {
        candidates.forEach { name ->
            Row(
                Modifier.fillMaxWidth().clip(Radii.sm)
                    .clickable { onComplete(com.andmx.agent.MentionResolver.complete(draft, query, name)) }
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, style = AndmxTheme.typography.bodyMedium, color = colors.textPrimary)
            }
        }
    }
}

/** Floating command list shown when the draft starts with '/'. */
@Composable
private fun SlashMenu(draft: String, onPick: (String) -> Unit) {
    val colors = AndmxTheme.colors
    val matches = SlashCommands.suggestions(draft)
    if (matches.isEmpty()) return

    Column(
        Modifier.fillMaxWidth().padding(bottom = Spacing.sm)
            .clip(Radii.md).background(colors.surfaceElevated)
            .border(1.dp, colors.border, Radii.md).padding(Spacing.xs),
    ) {
        matches.forEach { spec ->
            Row(
                Modifier.fillMaxWidth().clip(Radii.sm).clickable { onPick(SlashCommands.complete(spec)) }
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(spec.name, style = AndmxTheme.typography.labelLarge, color = colors.textPrimary)
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(spec.desc, style = AndmxTheme.typography.bodySmall, color = colors.textTertiary)
                    if (spec.aliases.isNotEmpty()) {
                        Text(
                            spec.aliases.joinToString(" "),
                            style = AndmxTheme.typography.labelSmall,
                            color = colors.textTertiary,
                        )
                    }
                }
            }
        }
    }
}
