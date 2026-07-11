package com.andmx.ui.conversation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.agent.ToolArgs
import com.andmx.agent.ToolRisk
import com.andmx.agent.label
import com.andmx.diff.DiffLine
import com.andmx.ui.components.DiffLineRow
import com.andmx.ui.theme.AndmxCodeTextStyle
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Motion
import com.andmx.ui.theme.Radii
import com.andmx.ui.theme.Spacing
import com.andmx.ui.workbench.toolTerminalSessionKey
import com.andmx.workspace.GuestPaths

// Diff colors unified in com.andmx.ui.components (DiffAddFg etc.); alias for local stats text.
private val InlineAddFg get() = com.andmx.ui.components.DiffAddFg
private val InlineDelFg get() = com.andmx.ui.components.DiffDelFg

@Composable
fun MessageList(
    items: List<ChatItem>,
    onApprove: (Boolean) -> Unit,
    onRetry: () -> Unit = {},
    onBranch: (Int) -> Unit = {},
    onContinuePrompt: (String) -> Unit = {},
    onResumePrompt: (String) -> Unit = {},
    onOpenDiff: (String?) -> Unit = {},
    onOpenFile: (String) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    onOpenReference: (String) -> Unit = {},
    onRunCommand: (String) -> Unit = {},
    onOpenTerminal: (String?) -> Unit = {},
    /** Re-edit a user message: revert to it and pre-fill the composer. */
    onEdit: (Int) -> Unit = {},
    /** Index of the user message currently being edited, or null. */
    editingIndex: Int? = null,
    /** Cancel the pending edit (clears the composer + editingIndex). */
    onCancelEdit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val rows = remember(items.toList()) { groupTimeline(items) }
    val changes by com.andmx.workspace.ChangeTracker.changes.collectAsState()
    val isAtBottom by androidx.compose.runtime.derivedStateOf {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
        last >= rows.lastIndex - 1
    }
    val lastLen = (items.lastOrNull() as? ChatItem.Assistant)?.text?.length ?: 0
    androidx.compose.runtime.LaunchedEffect(rows.size, isAtBottom) {
        if (rows.isNotEmpty() && (listState.layoutInfo.totalItemsCount == 0 || isAtBottom)) {
            listState.animateScrollToItem(rows.lastIndex)
        }
    }
    androidx.compose.runtime.LaunchedEffect(lastLen, isAtBottom, rows.size) {
        if (rows.isNotEmpty() && isAtBottom) {
            listState.animateScrollToItem(rows.lastIndex)
        }
    }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = Spacing.xl, vertical = Spacing.lg),
        ) {
            itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
                androidx.compose.foundation.layout.Box(
                    Modifier.animateItem(
                        fadeInSpec = androidx.compose.animation.core.tween(Motion.DUR_STD, easing = Motion.EASE_OUT),
                        fadeOutSpec = null,
                        placementSpec = androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
                    ),
                ) {
                when (row) {
                    is TimelineRow.Single -> when (val item = row.item) {
                    is ChatItem.User -> Column {
                        UserBubble(item.text)
                        val references = remember(item.text) { messageReferences(item.text) }
                        MessageReferenceRow(references, onOpenFile, onOpenUrl, onOpenReference, onRunCommand)
                        // Right-aligned actions: copy + edit/cancel-edit.
                        val itemIndex = items.indexOf(item)
                        val isEditing = itemIndex == editingIndex
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                        ) {
                            MsgActions {
                                ActionText("复制") { clipboard.setText(androidx.compose.ui.text.AnnotatedString(item.text)) }
                                if (isEditing) {
                                    ActionText("取消编辑") { onCancelEdit() }
                                } else {
                                    ActionText("编辑") { onEdit(itemIndex) }
                                }
                            }
                        }
                    }
                    is ChatItem.Assistant -> Column {
                        AssistantBlock(item.text)
                            val itemIndex = indexOfItem(items, item.key)
                            val isLast = itemIndex == items.lastIndex
                            val resumePrompt = remember(item.text) { extractHandoffResumePrompt(item.text) }
                            val resumeActions = remember(item.text) { handoffResumeActionLabels(item.text) }
                            val references = remember(item.text, item.done) { if (item.done) messageReferences(item.text) else emptyList() }
                            MessageReferenceRow(references, onOpenFile, onOpenUrl, onOpenReference, onRunCommand)
                        MsgActions {
                            ActionText("复制") { clipboard.setText(androidx.compose.ui.text.AnnotatedString(item.text)) }
                            if (resumePrompt != null) {
                                ActionText(resumeActions.getOrElse(0) { "当前线程继续" }) { onContinuePrompt(resumePrompt) }
                                ActionText(resumeActions.getOrElse(1) { "新线程继续" }) { onResumePrompt(resumePrompt) }
                                ActionText(resumeActions.getOrElse(2) { "复制恢复提示" }) {
                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(resumePrompt))
                                }
                            }
                            if (isLast) ActionText("重试") { onRetry() }
                                ActionText("分支") { onBranch(itemIndex) }
                            // Timestamps: sent time + completed time (compact HH:mm).
                            if (item.completedAt > 0L) {
                                Spacer(Modifier.width(Spacing.sm))
                                Text(
                                    formatTime(item.sentAt) + " → " + formatTime(item.completedAt),
                                    style = AndmxTheme.typography.labelSmall,
                                    color = AndmxTheme.colors.textTertiary,
                                )
                            }
                        }
                    }
                    is ChatItem.ToolUse -> ToolCard(item, changes, onOpenDiff, onOpenFile, onOpenUrl, onOpenReference, onOpenTerminal)
                    is ChatItem.Approval -> ApprovalCard(item, onApprove)
                }
                    is TimelineRow.ToolGroup -> ToolGroupCard(row.tools, changes, onOpenDiff, onOpenFile, onOpenUrl)
                }
                Spacer(Modifier.height(Spacing.lg))
                } // end animateItem Box
            }
        }
    }
}

@Composable
private fun MessageReferenceRow(
    references: List<MessageReference>,
    onOpenFile: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenReference: (String) -> Unit,
    onRunCommand: (String) -> Unit,
) {
    if (references.isEmpty()) return
    val colors = AndmxTheme.colors
    Spacer(Modifier.height(Spacing.sm))
    Column(Modifier.fillMaxWidth()) {
        references.forEach { ref ->
            val icon = when (ref.kind) {
                MessageReferenceKind.FILE -> Icons.AutoMirrored.Outlined.InsertDriveFile
                MessageReferenceKind.WEB -> Icons.Outlined.Language
                MessageReferenceKind.UI_REFERENCE -> Icons.Outlined.Info
            }
            val action = when (ref.kind) {
                MessageReferenceKind.FILE -> "打开文件"
                MessageReferenceKind.WEB -> "打开网页"
                MessageReferenceKind.UI_REFERENCE -> "查看参考"
            }
            Row(
                Modifier.fillMaxWidth().clip(Radii.sm).background(colors.sunken)
                    .border(1.dp, colors.border, Radii.pill)
                    .clickable {
                        when (ref.kind) {
                            MessageReferenceKind.FILE -> onOpenFile(ref.target)
                            MessageReferenceKind.WEB -> onOpenUrl(ref.target)
                            MessageReferenceKind.UI_REFERENCE -> {
                                if (ref.assetPath.isNotBlank()) onOpenReference(ref.assetPath)
                                else onRunCommand(ref.command)
                            }
                        }
                    }
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    listOf(ref.label.ifBlank { action }, ref.meta.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · "),
                    style = AndmxTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(action, style = AndmxTheme.typography.labelSmall, color = colors.accent)
            }
            Spacer(Modifier.height(Spacing.xs))
        }
    }
}

private sealed interface TimelineRow {
    val key: Long
    data class Single(val item: ChatItem) : TimelineRow {
        override val key: Long = item.key
    }
    data class ToolGroup(val tools: List<ChatItem.ToolUse>) : TimelineRow {
        override val key: Long = tools.first().key
    }
}

private fun groupTimeline(items: List<ChatItem>): List<TimelineRow> {
    val rows = mutableListOf<TimelineRow>()
    var pending = mutableListOf<ChatItem.ToolUse>()
    fun flush() {
        if (pending.isNotEmpty()) {
            rows += if (pending.size == 1) TimelineRow.Single(pending.first()) else TimelineRow.ToolGroup(pending.toList())
            pending = mutableListOf()
        }
    }
    for (item in items) {
        if (item is ChatItem.ToolUse && shouldGroupTool(item)) {
            pending += item
        } else {
            flush()
            rows += TimelineRow.Single(item)
        }
    }
    flush()
    return rows
}

private fun shouldGroupTool(item: ChatItem.ToolUse): Boolean {
    val artifacts = toolArtifactSummary(item)
    return !item.running &&
        !item.error &&
        artifacts.images.isEmpty() &&
        ToolArgs.editedPath(item.name, item.args).isBlank() &&
        ToolArgs.filePath(item.name, item.args).isBlank() &&
        ToolArgs.webUrl(item.name, item.args).isBlank()
}

private fun indexOfItem(items: List<ChatItem>, key: Long): Int =
    items.indexOfFirst { it.key == key }.coerceAtLeast(0)

/** Format a timestamp (ms) as compact HH:mm, or "" if 0/unset. */
private fun formatTime(ms: Long): String {
    if (ms <= 0L) return ""
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val m = cal.get(java.util.Calendar.MINUTE)
    return "%02d:%02d".format(h, m)
}

@Composable
private fun MsgActions(content: @Composable () -> Unit) {
    Spacer(Modifier.height(Spacing.xs))
    Row(verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
private fun ActionText(label: String, onClick: () -> Unit) {
    val colors = AndmxTheme.colors
    Text(
        label,
        style = AndmxTheme.typography.labelSmall,
        color = colors.textTertiary,
        modifier = Modifier.clip(Radii.sm).clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = 2.dp),
    )
}

@Composable
private fun ApprovalCard(item: ChatItem.Approval, onApprove: (Boolean) -> Unit) {
    val colors = AndmxTheme.colors
    val kind = toolKind(item.toolName)
    Column(
        Modifier.fillMaxWidth().clip(Radii.md)
            .border(1.dp, colors.warning.copy(alpha = 0.5f), Radii.md)
            .background(colors.warningSoft).padding(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(kind.icon, contentDescription = null, tint = colors.warning, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(
                "需要授权 · ${item.risk.label} · ${item.toolName}",
                style = AndmxTheme.typography.titleSmall,
                color = colors.warning,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(Spacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ApprovalMetaChip("模式 ${item.approvalModeLabel.ifBlank { "按需" }}")
            Spacer(Modifier.width(Spacing.xs))
            ApprovalMetaChip(item.riskDescription.ifBlank { riskFallbackDescription(item.risk) })
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(item.summary, style = AndmxCodeTextStyle, color = colors.textSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(Spacing.md))
        AnimatedContent(
            targetState = item.resolved,
            transitionSpec = {
                (fadeIn(androidx.compose.animation.core.tween(Motion.DUR_STD)) togetherWith
                    fadeOut(androidx.compose.animation.core.tween(Motion.DUR_FAST)))
            },
            label = "approvalResolved",
        ) { resolved ->
            if (resolved) {
                Text(if (item.allowed) "已允许" else "已拒绝", style = AndmxTheme.typography.labelMedium, color = colors.textTertiary)
            } else {
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "拒绝", style = AndmxTheme.typography.labelLarge, color = colors.textSecondary,
                        modifier = Modifier.clip(Radii.sm).clickable { onApprove(false) }.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        "允许", style = AndmxTheme.typography.labelLarge, color = colors.onAccent,
                        modifier = Modifier.clip(Radii.sm).background(colors.sendActive).clickable { onApprove(true) }
                            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    )
                }
            }
        }
    }
}

@Composable
private fun ApprovalMetaChip(text: String) {
    val colors = AndmxTheme.colors
    Text(
        text,
        style = AndmxTheme.typography.labelSmall,
        color = colors.warning,
        maxLines = 1,
        modifier = Modifier.clip(Radii.pill).background(colors.surface)
            .border(1.dp, colors.warning.copy(alpha = 0.25f), Radii.pill)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    )
}

private fun riskFallbackDescription(risk: ToolRisk): String = when (risk) {
    ToolRisk.READ -> "读取本地或项目内容"
    ToolRisk.WRITE -> "可能修改工作区"
    ToolRisk.EXECUTE -> "可能执行命令"
    ToolRisk.NETWORK -> "可能访问网络"
}

@Composable
private fun UserBubble(text: String) {
    val colors = AndmxTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
        Box(
            Modifier.widthIn(max = 520.dp).clip(Radii.lg).background(colors.sunken)
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        ) {
            Text(text, style = AndmxTheme.typography.bodyLarge, color = colors.textPrimary)
        }
    }
}

@Composable
private fun AssistantBlock(text: String) {
    com.andmx.ui.markdown.MarkdownText(markdown = text, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ToolCard(
    item: ChatItem.ToolUse,
    changes: List<com.andmx.workspace.FileChange>,
    onOpenDiff: (String?) -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenReference: (String) -> Unit,
    onOpenTerminal: (String?) -> Unit = {},
) {
    val colors = AndmxTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val kind = remember(item.name) { toolKind(item.name) }
    val preview = remember(item.name, item.args) { toolPreview(item.name, item.args) }
    val editedPath = remember(item.name, item.args) { ToolArgs.editedPath(item.name, item.args) }
    val openablePath = remember(item.name, item.args) { ToolArgs.filePath(item.name, item.args) }
    val openableUrl = remember(item.name, item.args) { ToolArgs.webUrl(item.name, item.args) }
    val artifacts = remember(item.callId, item.output) { toolArtifactSummary(item) }

    Column(
        Modifier.fillMaxWidth().clip(Radii.md).border(1.dp, colors.border, Radii.md).background(colors.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(kind.icon, contentDescription = null, tint = kind.tint(colors), modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(kind.label, style = AndmxTheme.typography.labelLarge, color = kind.tint(colors))
            Spacer(Modifier.width(Spacing.xs))
            Text(item.name, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
            Spacer(Modifier.width(Spacing.sm))
            Text(
                preview,
                style = AndmxCodeTextStyle,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val status = when {
                item.running -> "运行中"
                item.error -> "失败"
                else -> "完成"
            }
            Text(status, style = AndmxTheme.typography.labelSmall, color = if (item.error) colors.warning else colors.textTertiary)
            if (artifacts.images.isNotEmpty()) {
                Spacer(Modifier.width(Spacing.sm))
                Text("${artifacts.images.size} 张图", style = AndmxTheme.typography.labelSmall, color = colors.accent)
            }
            // Shell commands: tap to jump into the terminal tab and see live output.
            if (item.name == "run_shell") {
                Spacer(Modifier.width(Spacing.xs))
                Icon(
                    Icons.Outlined.Terminal,
                    contentDescription = "在终端中查看",
                    tint = colors.accent,
                    modifier = Modifier.size(15.dp).clip(Radii.sm).clickable { onOpenTerminal(toolTerminalSessionKey(item.callId)) }.padding(2.dp),
                )
            }
            Spacer(Modifier.width(Spacing.xs))
            val arrowRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "toolArrow")
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(15.dp).rotate(arrowRotation))
        }
        AnimatedVisibility(
            visible = expanded && item.output != null,
            enter = fadeIn(androidx.compose.animation.core.tween(Motion.DUR_STD)) + expandVertically(androidx.compose.animation.core.tween(Motion.DUR_STD)),
            exit = fadeOut(androidx.compose.animation.core.tween(Motion.DUR_FAST)) + shrinkVertically(androidx.compose.animation.core.tween(Motion.DUR_FAST)),
        ) {
            val rawOutput = item.output.orEmpty()
            var showFullOutput by remember(item.callId) { mutableStateOf(false) }
            val maxPreviewLines = 12
            val lineCount = rawOutput.count { it == '\n' } + 1
            val displayOutput = if (showFullOutput || lineCount <= maxPreviewLines) rawOutput
                else rawOutput.lineSequence().take(maxPreviewLines).joinToString("\n")
            Column(Modifier.fillMaxWidth().background(colors.codeBackground).padding(Spacing.md)) {
                Text(
                    displayOutput,
                    style = AndmxCodeTextStyle,
                    color = colors.textSecondary,
                )
                if (lineCount > maxPreviewLines) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        if (showFullOutput) "收起" else "显示全部 ($lineCount 行)",
                        style = AndmxTheme.typography.labelSmall,
                        color = colors.textTertiary,
                        modifier = Modifier.clip(Radii.sm).clickable { showFullOutput = !showFullOutput }
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
                    )
                }
            }
        }
        if (artifacts.images.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth()
                    .padding(horizontal = Spacing.md)
                    .padding(bottom = Spacing.sm),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.xs),
            ) {
                Text("图像产物", style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
                ToolArtifactGallery(
                    artifacts = artifacts.images,
                    onOpenReference = onOpenReference,
                )
            }
        }
        if (editedPath.isNotBlank()) {
            val stats = remember(changes, editedPath) { diffStatsFor(changes, editedPath) }
            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md).padding(bottom = Spacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(editedPath, style = AndmxCodeTextStyle, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (stats != null) {
                        Text("+${stats.added}", style = AndmxTheme.typography.labelSmall, color = colors.accent)
                        Spacer(Modifier.width(Spacing.xs))
                        Text("-${stats.removed}", style = AndmxTheme.typography.labelSmall, color = colors.warning)
                        Spacer(Modifier.width(Spacing.sm))
                    }
                    Text(
                        "查看差异",
                        style = AndmxTheme.typography.labelMedium,
                        color = colors.accent,
                        modifier = Modifier.clip(Radii.sm).clickable { onOpenDiff(editedPath) }
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    )
                }
                InlineDiffPreview(changes = changes, path = editedPath, onOpenFile = onOpenFile)
            }
        } else if (openablePath.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.md).padding(bottom = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(openablePath, style = AndmxCodeTextStyle, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(
                    "打开",
                    style = AndmxTheme.typography.labelMedium,
                    color = colors.accent,
                    modifier = Modifier.clip(Radii.sm).clickable { onOpenFile(openablePath) }
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
        } else if (openableUrl.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.md).padding(bottom = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(openableUrl, style = AndmxCodeTextStyle, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(
                    "打开网页",
                    style = AndmxTheme.typography.labelMedium,
                    color = colors.accent,
                    modifier = Modifier.clip(Radii.sm).clickable { onOpenUrl(openableUrl) }
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
        }
    }
}

@Composable
private fun ToolGroupCard(
    tools: List<ChatItem.ToolUse>,
    changes: List<com.andmx.workspace.FileChange>,
    onOpenDiff: (String?) -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val colors = AndmxTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val summary = remember(tools) { summarizeTools(tools) }
    val editedPaths = remember(tools) { editedPaths(tools) }
    val urls = remember(tools) { tools.mapNotNull { ToolArgs.webUrl(it.name, it.args).takeIf { url -> url.isNotBlank() } }.distinct() }
    Column(
        Modifier.fillMaxWidth().clip(Radii.md).border(1.dp, colors.border, Radii.md).background(colors.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(summary.icon, contentDescription = null, tint = summary.tint(colors), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(summary.title, style = AndmxTheme.typography.labelLarge, color = colors.textSecondary)
            Spacer(Modifier.width(Spacing.sm))
            Text(summary.detail, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary, modifier = Modifier.weight(1f))
            Text("${tools.count { it.running }} 运行中".takeIf { tools.any { t -> t.running } } ?: "完成", style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
            Spacer(Modifier.width(Spacing.xs))
            val groupArrowRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "groupArrow")
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(15.dp).rotate(groupArrowRotation))
        }
        if (editedPaths.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md).padding(bottom = Spacing.sm)) {
                editedPaths.take(3).forEach { path ->
                    val stats = diffStatsFor(changes, path)
                    Row(
                        Modifier.fillMaxWidth().clip(Radii.sm).background(colors.codeBackground)
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Difference, contentDescription = null, tint = colors.warning, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            path,
                            style = AndmxCodeTextStyle,
                            color = colors.textSecondary,
                            maxLines = 1,
                            modifier = Modifier.weight(1f).clickable { onOpenFile(path) },
                        )
                        if (stats != null) {
                            Text("+${stats.added}", style = AndmxTheme.typography.labelSmall, color = colors.accent)
                            Spacer(Modifier.width(Spacing.xs))
                            Text("-${stats.removed}", style = AndmxTheme.typography.labelSmall, color = colors.warning)
                        }
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    InlineDiffPreview(changes = changes, path = path, onOpenFile = onOpenFile)
                    Spacer(Modifier.height(Spacing.xs))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "查看差异",
                        style = AndmxTheme.typography.labelMedium,
                        color = colors.accent,
                        modifier = Modifier.clip(Radii.sm).clickable { onOpenDiff(editedPaths.firstOrNull()) }
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    )
                    if (editedPaths.size > 3) {
                        Text("另有 ${editedPaths.size - 3} 个文件", style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
                    }
                }
            }
        }
        if (editedPaths.isEmpty() && urls.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md).padding(bottom = Spacing.sm)) {
                urls.take(3).forEach { url ->
                    Row(
                        Modifier.fillMaxWidth().clip(Radii.sm).background(colors.codeBackground)
                            .clickable { onOpenUrl(url) }
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Language, contentDescription = null, tint = colors.accent, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(Spacing.sm))
                        Text(url, style = AndmxCodeTextStyle, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("打开", style = AndmxTheme.typography.labelSmall, color = colors.accent)
                    }
                    Spacer(Modifier.height(Spacing.xs))
                }
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(androidx.compose.animation.core.tween(Motion.DUR_STD)) + expandVertically(androidx.compose.animation.core.tween(Motion.DUR_STD)),
            exit = fadeOut(androidx.compose.animation.core.tween(Motion.DUR_FAST)) + shrinkVertically(androidx.compose.animation.core.tween(Motion.DUR_FAST)),
        ) {
            Column(Modifier.fillMaxWidth().background(colors.codeBackground).padding(Spacing.sm)) {
                tools.forEach { tool ->
                    CompactToolRow(tool)
                }
            }
        }
    }
}

private fun editedPaths(tools: List<ChatItem.ToolUse>): List<String> =
    tools.mapNotNull { ToolArgs.editedPath(it.name, it.args).takeIf { path -> path.isNotBlank() } }
        .distinct()

private fun diffStatsFor(
    changes: List<com.andmx.workspace.FileChange>,
    path: String,
): com.andmx.diff.DiffStats? {
    val match = changes.firstOrNull { GuestPaths.same(it.path, path) } ?: return null
    return com.andmx.diff.DiffEngine.stats(com.andmx.diff.DiffEngine.diff(match.oldContent, match.newContent))
}

@Composable
private fun InlineDiffPreview(
    changes: List<com.andmx.workspace.FileChange>,
    path: String,
    onOpenFile: (String) -> Unit,
) {
    val colors = AndmxTheme.colors
    val change = remember(changes, path) { changes.firstOrNull { GuestPaths.same(it.path, path) } } ?: return
    val lines = remember(change.oldContent, change.newContent) {
        com.andmx.diff.DiffEngine.diff(change.oldContent, change.newContent)
    }
    val stats = remember(lines) { com.andmx.diff.DiffEngine.stats(lines) }
    val preview = remember(lines) {
        previewDiffLines(lines)
    }
    if (preview.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().clip(Radii.sm).background(colors.codeBackground)
            .border(1.dp, colors.border, Radii.sm)
            .clickable { onOpenFile(path) },
    ) {
        Row(
            Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Difference, contentDescription = null, tint = colors.warning, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text(
                filenameFor(path),
                style = AndmxTheme.typography.labelMedium,
                color = colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text("+${stats.added}", style = AndmxTheme.typography.labelSmall, color = InlineAddFg)
            Spacer(Modifier.width(Spacing.xs))
            Text("-${stats.removed}", style = AndmxTheme.typography.labelSmall, color = InlineDelFg)
            Spacer(Modifier.width(Spacing.sm))
            Text("打开", style = AndmxTheme.typography.labelSmall, color = colors.accent)
        }
        preview.forEach { line ->
            InlineDiffRow(line)
        }
    }
}

@Composable
private fun InlineDiffRow(line: com.andmx.diff.DiffLine) = DiffLineRow(line)

private fun previewDiffLines(lines: List<com.andmx.diff.DiffLine>, limit: Int = 7): List<com.andmx.diff.DiffLine> {
    val firstChange = lines.indexOfFirst { it.kind != com.andmx.diff.DiffLine.Kind.CONTEXT }
    if (firstChange < 0) return emptyList()
    val start = (firstChange - 2).coerceAtLeast(0)
    val end = (start + limit).coerceAtMost(lines.size)
    return lines.subList(start, end)
}

private fun filenameFor(path: String): String =
    path.trimEnd('/').substringAfterLast('/').ifBlank { path }

@Composable
private fun CompactToolRow(item: ChatItem.ToolUse) {
    val colors = AndmxTheme.colors
    val kind = remember(item.name) { toolKind(item.name) }
    val preview = remember(item.name, item.args) { toolPreview(item.name, item.args) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(kind.icon, contentDescription = null, tint = kind.tint(colors), modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(kind.label, style = AndmxTheme.typography.labelSmall, color = kind.tint(colors), maxLines = 1, modifier = Modifier.width(40.dp))
        Text(preview, style = AndmxCodeTextStyle, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(
            when {
                item.running -> "运行中"
                item.error -> "失败"
                else -> "完成"
            },
            style = AndmxTheme.typography.labelSmall,
            color = if (item.error) colors.warning else colors.textTertiary,
        )
    }
}

private data class ToolGroupSummary(
    val title: String,
    val detail: String,
    val icon: ImageVector,
    val tint: (com.andmx.ui.theme.AndmxColors) -> Color,
)

private fun summarizeTools(tools: List<ChatItem.ToolUse>): ToolGroupSummary {
    val writePaths = editedPaths(tools)
    val writeCount = tools.count { it.name in setOf("write_file", "edit_file", "apply_patch") }
    val runCount = tools.count { it.name == "run_shell" }
    val searchCount = tools.count { it.name in setOf("browse", "web_search") }
    val readPaths = tools.filter { it.name in setOf("read_file", "list_dir") }
        .mapNotNull { ToolArgs.filePath(it.name, it.args).takeIf { path -> path.isNotBlank() } }
        .distinct()
    val readCount = tools.count { it.name in setOf("read_file", "list_dir") }
    val title = when {
        writeCount > 0 -> if (writePaths.isNotEmpty()) "已编辑 ${writePaths.size} 个文件" else "已编辑 $writeCount 个操作"
        runCount > 0 -> "已运行 $runCount 个命令"
        searchCount > 0 -> "已检索 $searchCount 次"
        readCount > 0 -> if (readPaths.isNotEmpty()) "已探索 ${readPaths.size} 个路径" else "已探索 $readCount 次"
        else -> "已调用 ${tools.size} 个工具"
    }
    val detail = tools.map { toolPreview(it.name, it.args) }.filter { it.isNotBlank() }.take(3).joinToString(" · ")
    return when {
        writeCount > 0 -> ToolGroupSummary(title, detail, Icons.Outlined.Difference) { it.warning }
        runCount > 0 -> ToolGroupSummary(title, detail, Icons.Outlined.Terminal) { it.warning }
        searchCount > 0 -> ToolGroupSummary(title, detail, Icons.Outlined.Search) { it.accent }
        readCount > 0 -> ToolGroupSummary(title, detail, Icons.Outlined.Folder) { it.textSecondary }
        else -> ToolGroupSummary(title, detail, Icons.Outlined.Extension) { it.textSecondary }
    }
}

private data class ToolKind(
    val label: String,
    val icon: ImageVector,
    val tint: (com.andmx.ui.theme.AndmxColors) -> Color,
)

private fun toolKind(name: String): ToolKind = when (name) {
    "run_shell" -> ToolKind("执行", Icons.Outlined.Terminal) { it.warning }
    "read_file" -> ToolKind("读取", Icons.AutoMirrored.Outlined.InsertDriveFile) { it.textSecondary }
    "write_file", "edit_file" -> ToolKind("写入", Icons.AutoMirrored.Outlined.InsertDriveFile) { it.warning }
    "apply_patch" -> ToolKind("补丁", Icons.Outlined.Difference) { it.warning }
    "list_dir" -> ToolKind("目录", Icons.Outlined.Folder) { it.textSecondary }
    "browse" -> ToolKind("浏览", Icons.Outlined.Language) { it.accent }
    "web_search" -> ToolKind("搜索", Icons.Outlined.Search) { it.accent }
    "git" -> ToolKind("Git", Icons.Outlined.Difference) { it.warning }
    else -> ToolKind("工具", Icons.Outlined.Extension) { it.textSecondary }
}

private fun toolPreview(name: String, args: String): String = ToolArgs.preview(name, args)
