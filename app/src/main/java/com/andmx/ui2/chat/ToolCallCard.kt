package com.andmx.ui2.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andmx.agent.ToolArgs
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import com.andmx.diff.DiffLine
import com.andmx.ui2.markdown.CodeTheme
import com.andmx.ui2.markdown.CodeHighlight
import com.andmx.ui2.icons.FileTypeIcons
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val prettyJson = Json { prettyPrint = true }

@Composable
fun ToolCallCard(toolCall: ToolCall) {
    val isEditTool = ToolEditDiff.isEditTool(toolCall.name)
    val autoExpand = toolCall.isRunning || toolCall.isError || isEditTool
    var expanded by remember(toolCall.id) {
        mutableStateOf(toolCall.isRunning || toolCall.isError || isEditTool)
    }
    var userToggled by remember(toolCall.id) { mutableStateOf(false) }
    var wasRunning by remember(toolCall.id) { mutableStateOf(toolCall.isRunning) }
    var contentMounted by remember(toolCall.id) { mutableStateOf(expanded) }

    LaunchedEffect(toolCall.isRunning, toolCall.isError, isEditTool) {
        if (userToggled) {
            wasRunning = toolCall.isRunning
            return@LaunchedEffect
        }
        if (toolCall.isRunning || toolCall.isError) {
            expanded = true
        } else if (wasRunning) {
            // ZCode keeps edit diffs open after completion so users can review +N/-N.
            if (!isEditTool) {
                delay(300)
                if (!userToggled) expanded = false
            }
        }
        wasRunning = toolCall.isRunning
    }

    LaunchedEffect(expanded) {
        if (expanded) {
            contentMounted = true
        } else {
            delay(220)
            if (!expanded) contentMounted = false
        }
    }

    val editPreview = remember(toolCall.id, toolCall.name, toolCall.args) {
        if (ToolEditDiff.isEditTool(toolCall.name)) ToolEditDiff.preview(toolCall.name, toolCall.args) else null
    }
    val family = toolFamily(toolCall.name)
    val kindLabel = if (editPreview != null) {
        editKindLabel(editPreview.operation, toolCall.isRunning, toolCall.isError)
    } else {
        toolKindLabel(toolCall)
    }
    val summary = if (editPreview != null) {
        fileNameOf(editPreview.path).ifBlank { toolSummary(toolCall) }
    } else {
        toolSummary(toolCall)
    }
    val secondary = if (editPreview != null) {
        pathDirHint(editPreview.path)
    } else {
        toolSecondary(toolCall)
    }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(180),
        label = "toolChevron",
    )

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 1.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    userToggled = true
                    expanded = !expanded
                }
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                family.icon,
                null,
                Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            )
            Spacer(Modifier.width(8.dp))
            if (toolCall.isRunning) {
                GradientRunningLabel(kindLabel)
            } else {
                Text(
                    text = kindLabel,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = if (toolCall.isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (editPreview != null && summary.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                FileNameChip(
                    path = editPreview.path,
                    name = summary,
                    onClick = {
                        val p = editPreview.path
                        if (p.isNotBlank()) ChatActionBus.openFile(p)
                    },
                    modifier = Modifier.weight(1f, fill = false).widthIn(max = 220.dp),
                )
            } else if (summary.isNotBlank()) {
                Text(
                    text = " · ",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).widthIn(max = 240.dp),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (!secondary.isNullOrBlank() && editPreview == null && !expanded) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp).widthIn(max = 96.dp),
                )
            }
            if (editPreview != null && (editPreview.stats.added > 0 || editPreview.stats.removed > 0)) {
                AnimatedDiffCounts(
                    added = editPreview.stats.added,
                    removed = editPreview.stats.removed,
                    active = toolCall.isRunning,
                    animationKey = "${toolCall.id}:stats",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            // ZCode ToolLayout: no spinner on edit write summary; animated label is enough
            if (toolCall.isRunning && editPreview == null) {
                CircularProgressIndicator(
                    Modifier
                        .padding(start = 8.dp)
                        .size(12.dp),
                    strokeWidth = 1.4.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                null,
                Modifier
                    .padding(start = 4.dp)
                    .size(16.dp)
                    .rotate(chevronRotation)
                    .alpha(if (expanded || toolCall.isRunning || toolCall.isError) 0.85f else 0.45f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }

        val actions = remember(toolCall.id, toolCall.name, toolCall.args) {
            toolActions(toolCall)
        }
        if (actions.isNotEmpty() && (expanded || toolCall.isRunning)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 23.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                actions.forEach { action ->
                    TextButton(
                        onClick = {
                            when (action) {
                                is ToolUiAction.OpenFile -> ChatActionBus.openFile(action.path)
                                is ToolUiAction.OpenTerminal -> ChatActionBus.openTerminal()
                                is ToolUiAction.OpenUrl -> ChatActionBus.openUrl(action.url)
                            }
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp,
                            vertical = 0.dp,
                        ),
                    ) {
                        Text(
                            action.label,
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = expanded && contentMounted,
            enter = expandVertically(animationSpec = tween(180)) + fadeIn(tween(140)),
            exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(120)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 23.dp, top = 2.dp, bottom = 6.dp),
            ) {
                if (editPreview != null && (editPreview.lines.isNotEmpty() || toolCall.isRunning)) {
                    InlineEditDiffPreview(
                        preview = editPreview,
                        isRunning = toolCall.isRunning,
                        onOpen = {
                            val p = editPreview.path
                            if (p.isNotBlank()) ChatActionBus.openFile(p)
                        },
                    )
                    if (!toolCall.output.isNullOrBlank() && toolCall.isError) {
                        Spacer(Modifier.height(8.dp))
                        MetaBlock(
                            title = "错误",
                            body = toolCall.output.take(4000),
                            mono = true,
                            emphasize = true,
                        )
                    }
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest.copy(
                                    alpha = if (toolCall.isError) 0.55f else 0.38f,
                                ),
                            )
                            .padding(10.dp),
                    ) {
                        if (toolCall.args.isNotBlank() && toolCall.args != "{}") {
                            MetaBlock(
                                title = "参数",
                                body = prettyArgs(toolCall.args),
                            )
                            if (!toolCall.output.isNullOrBlank() || toolCall.isRunning) {
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        when {
                            !toolCall.output.isNullOrBlank() -> {
                                MetaBlock(
                                    title = if (toolCall.isRunning) "输出" else "结果",
                                    body = toolCall.output.take(12000),
                                    mono = true,
                                    emphasize = toolCall.isError,
                                    stickToBottom = toolCall.isRunning,
                                )
                            }
                            toolCall.isRunning -> {
                                Text(
                                    "执行中…",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                                )
                            }
                            toolCall.isError -> {
                                Text(
                                    "无输出",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaBlock(
    title: String,
    body: String,
    mono: Boolean = false,
    emphasize: Boolean = false,
    stickToBottom: Boolean = false,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(body, stickToBottom) {
        if (stickToBottom && body.isNotEmpty()) {
            scroll.animateScrollTo(scroll.maxValue)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = if (emphasize) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
            color = if (emphasize) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f)
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(scroll),
        )
    }
}

@Composable
private fun GradientRunningLabel(text: String) {
    val infinite = rememberInfiniteTransition(label = "tool-run")
    val shift by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "tool-shift",
    )
    val c1 = MaterialTheme.colorScheme.primary
    val c2 = MaterialTheme.colorScheme.tertiary
    val c3 = MaterialTheme.colorScheme.secondary
    val brush = Brush.linearGradient(
        colors = listOf(c1, c2, c3, c1),
        start = Offset(shift * 220f, 0f),
        end = Offset(shift * 220f + 160f, 28f),
    )
    Text(
        text = text,
        style = TextStyle(
            brush = brush,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private data class ToolFamily(
    val label: String,
    val icon: ImageVector,
)

private fun toolFamily(name: String): ToolFamily = when (name) {
    "Bash", "run_shell", "git" -> ToolFamily("Shell", Icons.Outlined.Terminal)
    "Read", "read_file", "list_dir", "Glob", "glob" -> ToolFamily("读取", Icons.Outlined.Description)
    "Write", "write_file", "Edit", "edit_file", "apply_patch", "ApplyPatch" ->
        ToolFamily("写入", Icons.Outlined.Edit)
    "Grep", "grep" -> ToolFamily("搜索", Icons.Outlined.Search)
    "WebFetch", "browse", "WebSearch", "web_search" -> ToolFamily("网络", Icons.Outlined.Language)
    "TodoWrite", "TodoRead", "update_plan", "EnterPlanMode", "ExitPlanMode" ->
        ToolFamily("计划", Icons.Outlined.Checklist)
    "Agent", "spawn_agent", "multi_agent", "SendMessage", "TaskStop" ->
        ToolFamily("子代理", Icons.Outlined.Build)
    "Skill" -> ToolFamily("技能", Icons.Outlined.Build)
    else -> when {
        name.startsWith("plugin_") || name.startsWith("mcp__") -> ToolFamily("MCP", Icons.Outlined.Code)
        name.contains("__") -> ToolFamily("MCP", Icons.Outlined.Code)
        else -> ToolFamily(name, Icons.Outlined.Build)
    }
}

private fun toolKindLabel(toolCall: ToolCall): String {
    if (toolCall.isError) return "失败"
    return when (toolCall.name) {
        "Bash", "run_shell", "git" -> if (toolCall.isRunning) "执行中" else "已执行"
        "Read", "read_file" -> if (toolCall.isRunning) "读取中" else "已读取"
        "list_dir", "Glob", "glob" -> if (toolCall.isRunning) "浏览中" else "已浏览"
        "Write", "write_file" -> if (toolCall.isRunning) "写入中" else "已写入"
        "Edit", "edit_file", "apply_patch", "ApplyPatch" ->
            if (toolCall.isRunning) "编辑中" else "已编辑"
        "Grep", "grep" -> if (toolCall.isRunning) "搜索中" else "已搜索"
        "WebFetch", "browse", "WebSearch", "web_search" ->
            if (toolCall.isRunning) "检索中" else "已检索"
        "TodoWrite", "update_plan" -> if (toolCall.isRunning) "更新待办" else "已更新待办"
        "Agent", "spawn_agent", "multi_agent" ->
            if (toolCall.isRunning) "子代理运行中" else "子代理完成"
        "Skill" -> if (toolCall.isRunning) "运行技能" else "已运行技能"
        else -> if (toolCall.isRunning) "运行中" else toolFamily(toolCall.name).label
    }
}

private fun toolSummary(toolCall: ToolCall): String {
    val preview = ToolArgs.preview(toolCall.name, toolCall.args).ifBlank {
        toolCall.args.take(120)
    }.trim()
    return preview
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .take(140)
}

private fun toolSecondary(toolCall: ToolCall): String? {
    if (toolCall.isRunning) return null
    if (toolCall.isError) return "失败"
    val out = toolCall.output ?: return null
    val lines = out.lineSequence().count { it.isNotBlank() }
    return when {
        lines <= 0 -> null
        lines == 1 -> "1 行"
        else -> "$lines 行"
    }
}

private fun prettyArgs(raw: String): String {
    val t = raw.trim()
    if (t.length < 2) return t
    return try {
        val el = prettyJson.parseToJsonElement(t)
        prettyJson.encodeToString(JsonElement.serializer(), el)
    } catch (_: Throwable) {
        t
    }
}

private sealed class ToolUiAction(val label: String) {
    class OpenFile(val path: String) : ToolUiAction("打开文件")
    data object OpenTerminal : ToolUiAction("打开终端")
    class OpenUrl(val url: String) : ToolUiAction("打开链接")
}

private fun toolActions(toolCall: ToolCall): List<ToolUiAction> {
    val out = mutableListOf<ToolUiAction>()
    when (toolCall.name) {
        "Bash", "run_shell", "git" -> out += ToolUiAction.OpenTerminal
        "Read", "read_file", "Write", "write_file", "Edit", "edit_file",
        "apply_patch", "ApplyPatch", "list_dir" -> {
            val path = ToolArgs.filePath(toolCall.name, toolCall.args)
                .ifBlank { ToolArgs.pathOf(toolCall.args) }
            if (path.isNotBlank()) out += ToolUiAction.OpenFile(path)
        }
        "WebFetch", "browse", "WebSearch", "web_search" -> {
            val url = ToolArgs.webUrl(toolCall.name, toolCall.args)
            if (url.isNotBlank()) out += ToolUiAction.OpenUrl(url)
        }
        "Grep", "grep", "Glob", "glob" -> {
            val path = ToolArgs.pathOf(toolCall.args)
            if (path.isNotBlank()) out += ToolUiAction.OpenFile(path)
        }
    }
    return out
}



private val DiffAddColor = Color(0xFF1E8A3E)
private val DiffDelColor = Color(0xFFE03131)
private val DiffAddColorDark = Color(0xFF46BF72)
private val DiffDelColorDark = Color(0xFFFF5C5C)

@Composable
private fun diffAddColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) DiffAddColorDark else DiffAddColor

@Composable
private fun diffDelColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) DiffDelColorDark else DiffDelColor

private fun Color.luminance(): Float {
    val r = red
    val g = green
    val b = blue
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

private fun fileNameOf(path: String): String =
    path.trimEnd('/').substringAfterLast('/').ifBlank { path }

private fun pathDirHint(path: String): String? {
    val p = path.trimEnd('/')
    val slash = p.lastIndexOf('/')
    if (slash <= 0) return null
    val dir = p.substring(0, slash)
    return if (dir.length > 28) "…${dir.takeLast(26)}" else dir
}

private fun editKindLabel(
    op: ToolEditPreview.Operation,
    running: Boolean,
    error: Boolean,
): String {
    if (error) return "失败"
    return when (op) {
        ToolEditPreview.Operation.WRITE -> if (running) "写入中" else "已写入"
        ToolEditPreview.Operation.DELETE -> if (running) "删除中" else "已删除"
        ToolEditPreview.Operation.EDIT -> if (running) "编辑中" else "已编辑"
    }
}

@Composable
private fun AnimatedDiffCounts(
    added: Int,
    removed: Int,
    active: Boolean,
    animationKey: String,
    modifier: Modifier = Modifier,
) {
    if (added <= 0 && removed <= 0) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (added > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "+",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = diffAddColor(),
                )
                AnimatedMetricNumber(
                    value = added,
                    active = active,
                    animationKey = "$animationKey:added",
                    color = diffAddColor(),
                )
            }
        }
        if (removed > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "-",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = diffDelColor(),
                )
                AnimatedMetricNumber(
                    value = removed,
                    active = active,
                    animationKey = "$animationKey:removed",
                    color = diffDelColor(),
                )
            }
        }
    }
}

@Composable
private fun AnimatedMetricNumber(
    value: Int,
    active: Boolean,
    animationKey: String,
    color: Color,
) {
    var display by remember(animationKey) { mutableIntStateOf(value) }
    LaunchedEffect(value, active, animationKey) {
        if (!active) {
            display = value
            return@LaunchedEffect
        }
        val start = display
        if (start == value) return@LaunchedEffect
        val target = value
        val delta = target - start
        val durationMs = 900
        val begin = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            val t = ((now - begin).toFloat() / durationMs).coerceIn(0f, 1f)
            // ease-out approx
            val eased = 1f - (1f - t) * (1f - t)
            display = start + (delta * eased).toInt()
            if (t >= 1f) {
                display = target
                break
            }
        }
    }
    Text(
        text = display.toString(),
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        ),
        color = color,
    )
}

@Composable
private fun FileNameChip(
    path: String,
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 2.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            FileTypeIcons.iconFor(name.ifBlank { path }),
            null,
            Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = name.ifBlank { fileNameOf(path) },
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InlineEditDiffPreview(
    preview: ToolEditPreview,
    isRunning: Boolean,
    onOpen: () -> Unit,
) {
    val lines = remember(preview.lines) { ToolEditDiff.focusedPreview(preview.lines, limit = 56) }
    val scroll = rememberScrollState()
    LaunchedEffect(preview.lines.size, isRunning) {
        if (isRunning && preview.lines.isNotEmpty()) {
            scroll.animateScrollTo(scroll.maxValue)
        }
    }
    val theme = codeTheme()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpen,
            )
            .heightIn(max = 240.dp)
            .verticalScroll(scroll),
    ) {
        if (lines.isEmpty()) {
            Text(
                text = if (isRunning) "写入中…" else "无预览",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        } else {
            lines.forEachIndexed { index, line ->
                ZCodeDiffLine(
                    line = line,
                    displayLineNo = index + 1,
                    theme = theme,
                )
            }
            if (preview.lines.size > lines.size) {
                Text(
                    text = "… 另有 ${preview.lines.size - lines.size} 行",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun codeTheme(): CodeTheme {
    val scheme = MaterialTheme.colorScheme
    val isDark = (scheme.surface.red * 0.299f + scheme.surface.green * 0.587f + scheme.surface.blue * 0.114f) < 0.5f
    return if (isDark) {
        CodeTheme(
            id = "andmx-diff-dark",
            name = "AndMX Diff Dark",
            dark = true,
            background = scheme.surface,
            foreground = Color(0xFFEEFFFF),
            keyword = Color(0xFFC792EA),
            string = Color(0xFFC3E88D),
            comment = Color(0xFF546E7A),
            number = Color(0xFFF78C6C),
            function = Color(0xFF82AAFF),
        )
    } else {
        CodeTheme(
            id = "andmx-diff-light",
            name = "AndMX Diff Light",
            dark = false,
            background = scheme.surface,
            foreground = Color(0xFF111827),
            keyword = Color(0xFF7C3AED),
            string = Color(0xFF059669),
            comment = Color(0xFF6B7280),
            number = Color(0xFFD97706),
            function = Color(0xFF2563EB),
        )
    }
}

@Composable
private fun ZCodeDiffLine(
    line: DiffLine,
    displayLineNo: Int,
    theme: CodeTheme,
) {
    val add = diffAddColor()
    val del = diffDelColor()
    val bg = when (line.kind) {
        DiffLine.Kind.ADD -> add.copy(alpha = 0.14f)
        DiffLine.Kind.REMOVE -> del.copy(alpha = 0.14f)
        DiffLine.Kind.CONTEXT -> Color.Transparent
    }
    val gutterBg = when (line.kind) {
        DiffLine.Kind.ADD -> Color(
            red = add.red * 0.18f + MaterialTheme.colorScheme.surface.red * 0.82f,
            green = add.green * 0.18f + MaterialTheme.colorScheme.surface.green * 0.82f,
            blue = add.blue * 0.18f + MaterialTheme.colorScheme.surface.blue * 0.82f,
            alpha = 1f,
        )
        DiffLine.Kind.REMOVE -> Color(
            red = del.red * 0.18f + MaterialTheme.colorScheme.surface.red * 0.82f,
            green = del.green * 0.18f + MaterialTheme.colorScheme.surface.green * 0.82f,
            blue = del.blue * 0.18f + MaterialTheme.colorScheme.surface.blue * 0.82f,
            alpha = 1f,
        )
        DiffLine.Kind.CONTEXT -> MaterialTheme.colorScheme.surface
    }
    val numColor = when (line.kind) {
        DiffLine.Kind.ADD -> add
        DiffLine.Kind.REMOVE -> del
        DiffLine.Kind.CONTEXT -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    }
    val bar = when (line.kind) {
        DiffLine.Kind.ADD -> add
        DiffLine.Kind.REMOVE -> del
        DiffLine.Kind.CONTEXT -> Color.Transparent
    }
    val highlighted = remember(line.text, theme) {
        CodeHighlight.highlight(line.text.ifEmpty { " " }, theme)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(18.dp)
                .background(bar),
        )
        Text(
            text = displayLineNo.toString(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            ),
            color = numColor,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(40.dp)
                .background(gutterBg)
                .padding(end = 8.dp),
        )
        Text(
            text = highlighted,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = theme.foreground,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp, end = 10.dp, top = 1.dp, bottom = 1.dp),
        )
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Composable
fun ToolGroupCard(tools: List<ToolCall>) {
    val key = tools.map { it.id }.joinToString()
    val running = tools.any { it.isRunning }
    val failed = tools.count { it.isError }
    var expanded by remember(key) { mutableStateOf(running || failed > 0) }
    var userToggled by remember(key) { mutableStateOf(false) }
    var wasRunning by remember(key) { mutableStateOf(running) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(180),
        label = "groupChevron",
    )

    LaunchedEffect(running, failed) {
        if (userToggled) {
            wasRunning = running
            return@LaunchedEffect
        }
        if (running || failed > 0) {
            expanded = true
        } else if (wasRunning) {
            delay(300)
            if (!userToggled) expanded = false
        }
        wasRunning = running
    }

    val labels = tools.map { toolFamily(it.name).label }.distinct().take(3)
    val title = buildString {
        append(tools.size)
        append(" 步")
        if (labels.isNotEmpty()) {
            append(" · ")
            append(labels.joinToString(" / "))
        }
        if (failed > 0) {
            append(" · ")
            append(failed)
            append(" 失败")
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 1.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    userToggled = true
                    expanded = !expanded
                }
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Build,
                null,
                Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            )
            Spacer(Modifier.width(8.dp))
            if (running) {
                GradientRunningLabel(title)
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                null,
                Modifier
                    .size(16.dp)
                    .rotate(chevronRotation)
                    .alpha(if (expanded || running) 0.85f else 0.45f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(180)) + fadeIn(tween(140)),
            exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(120)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                tools.forEach { tool ->
                    CompactToolProcessRow(tool)
                }
            }
        }
    }
}

@Composable
private fun CompactToolProcessRow(tool: ToolCall) {
    val family = toolFamily(tool.name)
    val summary = toolSummary(tool)
    val label = toolKindLabel(tool)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            family.icon,
            null,
            Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
            color = when {
                tool.isError -> MaterialTheme.colorScheme.error
                tool.isRunning -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
            },
        )
        if (summary.isNotBlank()) {
            Text(
                text = " · $summary",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun WorkingIndicator() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            Modifier.size(12.dp),
            strokeWidth = 1.4.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
        )
        GradientRunningLabel("思考中…")
    }
}
