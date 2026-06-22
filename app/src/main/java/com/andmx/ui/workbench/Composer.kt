package com.andmx.ui.workbench

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.agent.ApprovalMode
import com.andmx.ui.conversation.AttachmentPreflightSummary
import com.andmx.ui.conversation.Attachments
import com.andmx.ui.conversation.composerKindLabel
import com.andmx.ui.conversation.composerMetaLabel
import com.andmx.ui.components.IconActionButton
import com.andmx.ui.components.IconVariant
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Elevation
import com.andmx.ui.theme.Radii
import com.andmx.ui.theme.Spacing

/**
 * The floating composer card — Codex's central affordance.
 * Placeholder text, a `+` attach button, an amber permission toggle,
 * a model / reasoning selector, and a send button that turns ink-filled
 * once there is something to send.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "随心输入",
    enabled: Boolean = true,
    modelLabel: String = "",
    accessLabel: String = "完全访问",
    busy: Boolean = false,
    onStop: () -> Unit = {},
    attachments: List<com.andmx.ui.conversation.Attachment> = emptyList(),
    onAttachClick: () -> Unit = {},
    onRemoveAttachment: (com.andmx.ui.conversation.Attachment) -> Unit = {},
    onDropText: (String) -> Unit = {},
    onDropUri: (android.net.Uri) -> Unit = {},
    onAccessClick: () -> Unit = {},
    accessMode: ApprovalMode = ApprovalMode.ASK,
    onAccessModeSelected: (ApprovalMode) -> Unit = {},
    onModelClick: () -> Unit = {},
    reasoningEffort: String = "off",
    reasoning: com.andmx.llm.provider.ReasoningConfig? = null,
    onReasoningEffortSelected: (String) -> Unit = {},
    goalLabel: String = "",
    onGoalClick: () -> Unit = {},
    onSend: (String) -> Unit = {},
    contextChips: @Composable (() -> Unit)? = null,
) {
    val colors = AndmxTheme.colors
    val canSend = (value.isNotBlank() || attachments.isNotEmpty()) && enabled
    var dragOver by remember { mutableStateOf(false) }

    fun submit() {
        if (!canSend) return
        onSend(value.trim())
    }

    val dndTarget = remember(onDropText, onDropUri) {
        object : androidx.compose.ui.draganddrop.DragAndDropTarget {
            override fun onDrop(event: androidx.compose.ui.draganddrop.DragAndDropEvent): Boolean {
                val clip = event.toAndroidDragEvent().clipData ?: return false
                var handled = false
                for (i in 0 until clip.itemCount) {
                    val item = clip.getItemAt(i)
                    item.uri?.let { onDropUri(it); handled = true }
                    item.text?.let { onDropText(it.toString()); handled = true }
                }
                return handled
            }
            override fun onEntered(event: androidx.compose.ui.draganddrop.DragAndDropEvent) { dragOver = true }
            override fun onExited(event: androidx.compose.ui.draganddrop.DragAndDropEvent) { dragOver = false }
            override fun onEnded(event: androidx.compose.ui.draganddrop.DragAndDropEvent) { dragOver = false }
        }
    }

    Column(modifier = modifier) {
        Surface(
            shape = Radii.md,
            color = colors.surface,
            border = BorderStroke(1.dp, if (dragOver) colors.accent else colors.border),
            shadowElevation = Elevation.composer,
            modifier = Modifier.fillMaxWidth().dragAndDropTarget(
                shouldStartDragAndDrop = { true },
                target = dndTarget,
            ),
        ) {
            Column(Modifier.padding(Spacing.md).animateContentSize(tween(com.andmx.ui.theme.Motion.DUR_STD, easing = com.andmx.ui.theme.Motion.EASE_OUT))) {
                AnimatedVisibility(
                    visible = attachments.isNotEmpty(),
                    enter = fadeIn(tween(com.andmx.ui.theme.Motion.DUR_STD)) + expandVertically(tween(com.andmx.ui.theme.Motion.DUR_STD)),
                    exit = fadeOut(tween(com.andmx.ui.theme.Motion.DUR_FAST)) + shrinkVertically(tween(com.andmx.ui.theme.Motion.DUR_FAST)),
                ) {
                FlowRow(Modifier.fillMaxWidth().padding(bottom = Spacing.sm)) {
                    attachments.forEach { att -> AttachmentChip(att) { onRemoveAttachment(att) } }
                }
                val preflight = Attachments.preflightSummary(attachments)
                if (preflight.hasVisualReferences) {
                    AttachmentPreflight(preflight, Modifier.padding(bottom = Spacing.sm))
                }
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.xs)) {
                    if (value.isEmpty()) {
                        Text(
                            text = if (dragOver) "松手以添加文件" else placeholder,
                            style = AndmxTheme.typography.bodyLarge,
                            color = colors.textTertiary,
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        textStyle = AndmxTheme.typography.bodyLarge.copy(color = colors.textPrimary),
                        cursorBrush = SolidColor(colors.accent),
                        // Multi-line so long prompts are editable on a phone, with a
                        // sensible cap so the composer doesn't eat the whole screen.
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp, max = 160.dp),
                    )
                }

                Spacer(Modifier.size(Spacing.sm))

                // FlowRow so the pills wrap to a second line instead of squashing
                // each other when the model name is long on a narrow screen. The
                // model selector + send button are bundled into one Row so they
                // never split across lines.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CircleIconButton(Icons.Outlined.Add, onAttachClick)
                    PermissionPill(accessLabel, accessMode, onAccessClick, onAccessModeSelected)
                    GoalPill(goalLabel, onGoalClick)
                    Spacer(Modifier.weight(1f, fill = true))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        ModelSelector(modelLabel, reasoningEffort, reasoning, onModelClick, onReasoningEffortSelected)
                        // Crossfade between Send and Stop so the swap doesn't pop.
                        AnimatedContent(
                            targetState = busy,
                            transitionSpec = {
                                (fadeIn(tween(com.andmx.ui.theme.Motion.DUR_FAST)) togetherWith
                                    fadeOut(tween(com.andmx.ui.theme.Motion.DUR_FAST)))
                                    .using(SizeTransform(clip = false))
                            },
                            label = "sendStop",
                        ) { isBusy ->
                            if (isBusy) StopButton(onStop) else SendButton(active = canSend, onClick = { submit() })
                        }
                    }
                }
            }
        }
        if (contextChips != null) {
            Spacer(Modifier.size(Spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) { contextChips() }
        }
    }
}

@Composable
private fun AttachmentPreflight(summary: AttachmentPreflightSummary, modifier: Modifier = Modifier) {
    val colors = AndmxTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.sm)
            .background(colors.accentSoft.copy(alpha = 0.62f))
            .border(1.dp, colors.accent.copy(alpha = 0.18f), Radii.sm)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Spacing.xs))
        Column(Modifier.weight(1f)) {
            Text(
                summary.title,
                style = AndmxTheme.typography.labelMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.detail.isNotBlank()) {
                Text(
                    summary.detail,
                    style = AndmxTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        summary.routeCommands.take(2).forEach { command ->
            Spacer(Modifier.width(Spacing.xs))
            RouteHint(command)
        }
    }
}

@Composable
private fun RouteHint(command: String) {
    val colors = AndmxTheme.colors
    Text(
        text = command,
        style = AndmxTheme.typography.labelSmall,
        color = colors.accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 92.dp)
            .clip(Radii.pill)
            .background(colors.surface)
            .border(1.dp, colors.border, Radii.pill)
            .padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
    )
}

@Composable
private fun GoalPill(label: String, onClick: () -> Unit) {
    val colors = AndmxTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(Radii.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
    ) {
        Icon(Icons.Outlined.TrackChanges, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(
            label.ifBlank { "目标" },
            style = AndmxTheme.typography.labelMedium,
            color = if (label.isBlank()) colors.textTertiary else colors.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun AttachmentChip(att: com.andmx.ui.conversation.Attachment, onRemove: () -> Unit) {
    val colors = AndmxTheme.colors
    val accent = att.isImage
    val icon = if (accent) Icons.Outlined.Info else Icons.AutoMirrored.Outlined.InsertDriveFile
    val tint = if (att.isUiReference) colors.accent else colors.textSecondary
    val meta = att.composerMetaLabel
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = Spacing.sm, bottom = Spacing.xs)
            .clip(Radii.sm)
            .background(if (att.isUiReference) colors.accentSoft else colors.sunken)
            .border(1.dp, if (att.isUiReference) colors.accent.copy(alpha = 0.28f) else colors.border, Radii.sm)
            .padding(start = Spacing.sm, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(Spacing.xs))
        Column(Modifier.widthIn(max = 220.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    att.composerKindLabel,
                    style = AndmxTheme.typography.labelSmall,
                    color = tint,
                    maxLines = 1,
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    att.name,
                    style = AndmxTheme.typography.labelMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = AndmxTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(Spacing.xs))
        Box(Modifier.size(15.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Close, contentDescription = "移除", tint = colors.textTertiary, modifier = Modifier.size(11.dp))
        }
    }
}

@Composable
private fun CircleIconButton(icon: ImageVector, onClick: () -> Unit) =
    IconActionButton(icon = icon, onClick = onClick, size = 28.dp)

@Composable
private fun PermissionPill(
    label: String,
    mode: ApprovalMode,
    onClick: () -> Unit,
    onSelect: (ApprovalMode) -> Unit,
) {
    val colors = AndmxTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(Radii.pill)
                .clickable {
                    expanded = true
                    onClick()
                }
                .padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
        ) {
            Icon(Icons.Outlined.Bolt, contentDescription = null, tint = colors.warning, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text(label, style = AndmxTheme.typography.labelMedium, color = colors.warning)
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = colors.warning, modifier = Modifier.size(15.dp))
        }
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ApprovalMode.entries.forEach { option ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label, style = AndmxTheme.typography.bodyMedium, color = colors.textPrimary)
                            Text(option.description, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
                        }
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Bolt,
                            contentDescription = null,
                            tint = if (option == mode) colors.warning else colors.textTertiary,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

private val ApprovalMode.description: String
    get() = when (this) {
        ApprovalMode.FULL -> "读写和命令自动执行"
        ApprovalMode.ASK -> "写入和高风险命令前请求授权"
        ApprovalMode.READ_ONLY -> "只允许读取和低风险操作"
    }

@Composable
private fun ModelSelector(
    modelLabel: String,
    reasoningEffort: String,
    reasoning: com.andmx.llm.provider.ReasoningConfig?,
    onClick: () -> Unit,
    onReasoningEffortSelected: (String) -> Unit,
) {
    val colors = AndmxTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(Radii.pill)
                .clickable { expanded = true }
                .padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
        ) {
            Text(
                modelLabel.ifBlank { "选择模型" },
                style = AndmxTheme.typography.labelMedium,
                color = if (modelLabel.isBlank()) colors.textTertiary else colors.textPrimary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
            )
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(15.dp))
        }
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = {
                    Column {
                        Text(modelLabel.ifBlank { "未选择模型" }, style = AndmxTheme.typography.bodyMedium, color = colors.textPrimary)
                        Text("当前模型", style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
                    }
                },
                onClick = {
                    expanded = false
                    onClick()
                },
            )
            // Reasoning options are generated from the model's declared capability:
            // NONE → nothing (e.g. gpt-4o, deepseek-reasoner); EFFORT → the model's
            // accepted levels; THINKING → a single on/off toggle.
            reasoningOptionsFor(reasoning).forEach { (value, label, description) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Column {
                            Text(label, style = AndmxTheme.typography.bodyMedium, color = colors.textPrimary)
                            Text(description, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
                        }
                    },
                    leadingIcon = {
                        if (reasoningEffort == value) {
                            Icon(Icons.Outlined.TrackChanges, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
                        }
                    },
                    onClick = {
                        expanded = false
                        onReasoningEffortSelected(value)
                    },
                )
            }
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("模型设置…", style = AndmxTheme.typography.bodyMedium, color = colors.textPrimary) },
                onClick = {
                    expanded = false
                    onClick()
                },
            )
        }
    }
}

/**
 * Build the reasoning menu from the model's [ReasoningConfig]. Returns empty for
 * models with no adjustable reasoning, so the selector simply hides that section.
 */
private fun reasoningOptionsFor(reasoning: com.andmx.llm.provider.ReasoningConfig?): List<Triple<String, String, String>> = when {
    reasoning == null || reasoning.style == com.andmx.llm.provider.ReasoningStyle.NONE -> emptyList()
    reasoning.style == com.andmx.llm.provider.ReasoningStyle.EFFORT -> buildList {
        add(Triple("off", "推理: 关闭", "不发送 reasoning_effort"))
        reasoning.effortLevels.forEach { level ->
            val desc = when (level) {
                "minimal" -> "最快,极少推理"
                "low" -> "快,轻度推理"
                "medium" -> "平衡速度与质量"
                "high" -> "深度推理,适合复杂任务"
                else -> level
            }
            add(Triple(level, "推理: $level", desc))
        }
    }
    reasoning.style == com.andmx.llm.provider.ReasoningStyle.THINKING -> listOf(
        Triple("off", "思考: 关闭", "不启用 extended thinking"),
        Triple("enabled", "思考: 开启", "extended thinking，预算 ${reasoning.defaultBudgetTokens} tokens"),
    )
    else -> emptyList()
}

@Composable
private fun SendButton(active: Boolean, onClick: () -> Unit) {
    val colors = AndmxTheme.colors
    val bg by animateColorAsState(if (active) colors.sendActive else colors.sunken, label = "sendBg")
    val tint by animateColorAsState(if (active) colors.onAccent else colors.sendIdle, label = "sendTint")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(enabled = active, onClick = onClick),
    ) {
        Icon(
            Icons.Outlined.ArrowUpward,
            contentDescription = "发送",
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    val colors = AndmxTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(colors.sendActive)
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(colors.onAccent),
        )
    }
}

@Composable
fun ContextChip(label: String) {
    val colors = AndmxTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(end = Spacing.sm)
            .clip(Radii.sm)
            .border(1.dp, colors.border, Radii.sm)
            .clickable { }
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    ) {
        Text(label, style = AndmxTheme.typography.labelMedium, color = colors.textSecondary)
        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(14.dp))
    }
}
