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
            if (summary.isNotBlank()) {
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
            if (editPreview != null) {
                AnimatedDiffCounts(
                    added = editPreview.stats.added,
                    removed = editPreview.stats.removed,
                    active = toolCall.isRunning,
                    animationKey = "${toolCall.id}:stats",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (toolCall.isRunning) {
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
                if (editPreview != null && editPreview.lines.isNotEmpty()) {
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

private fun toolFamily(name: String): ToolFamily = when {
    name == "run_shell" || name == "git" -> ToolFamily("Shell", Icons.Outlined.Terminal)
    name in setOf("read_file", "list_dir", "glob") -> ToolFamily("读取", Icons.Outlined.Description)
    name in setOf("write_file", "edit_file", "apply_patch") -> ToolFamily("写入", Icons.Outlined.Edit)
    name == "grep" -> ToolFamily("搜索", Icons.Outlined.Search)
    name in setOf("browse", "web_search") -> ToolFamily("网络", Icons.Outlined.Language)
    name == "update_plan" -> ToolFamily("计划", Icons.Outlined.Checklist)
    name in setOf("spawn_agent", "multi_agent") -> ToolFamily("子代理", Icons.Outlined.Build)
    name.startsWith("plugin_") -> ToolFamily("插件", Icons.Outlined.Build)
    name.contains("__") || (name.contains("_") && name.count { it == '_' } >= 2) ->
        ToolFamily("MCP", Icons.Outlined.Code)
    else -> ToolFamily(name, Icons.Outlined.Build)
}

private fun toolKindLabel(toolCall: ToolCall): String {
    if (toolCall.isError) return "失败"
    return when (toolCall.name) {
        "run_shell", "git" -> if (toolCall.isRunning) "执行中" else "已执行"
        "read_file" -> if (toolCall.isRunning) "读取中" else "已读取"
        "list_dir", "glob" -> if (toolCall.isRunning) "浏览中" else "已浏览"
        "write_file" -> if (toolCall.isRunning) "写入中" else "已写入"
        "edit_file", "apply_patch" -> if (toolCall.isRunning) "编辑中" else "已编辑"
        "grep" -> if (toolCall.isRunning) "搜索中" else "已搜索"
        "browse", "web_search" -> if (toolCall.isRunning) "检索中" else "已检索"
        "update_plan" -> if (toolCall.isRunning) "更新计划" else "计划已更新"
        "spawn_agent", "multi_agent" -> if (toolCall.isRunning) "子代理运行中" else "子代理完成"
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
        "run_shell", "git" -> out += ToolUiAction.OpenTerminal
        "read_file", "write_file", "edit_file", "apply_patch", "list_dir" -> {
            val path = ToolArgs.filePath(toolCall.name, toolCall.args)
                .ifBlank { ToolArgs.value(toolCall.args, "path") }
            if (path.isNotBlank()) out += ToolUiAction.OpenFile(path)
        }
        "browse", "web_search" -> {
            val url = ToolArgs.webUrl(toolCall.name, toolCall.args)
            if (url.isNotBlank()) out += ToolUiAction.OpenUrl(url)
        }
        "grep", "glob" -> {
            val path = ToolArgs.value(toolCall.args, "path")
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
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpen,
            ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Edit,
                null,
                Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = fileNameOf(preview.path).ifBlank { "变更" },
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            AnimatedDiffCounts(
                added = preview.stats.added,
                removed = preview.stats.removed,
                active = isRunning,
                animationKey = "preview:${preview.path}:${preview.stats.added}:${preview.stats.removed}",
            )
            if (preview.path.isNotBlank()) {
                Text(
                    "打开",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(scroll),
        ) {
            lines.forEach { line ->
                DiffPreviewLine(line)
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
private fun DiffPreviewLine(line: DiffLine) {
    val add = diffAddColor()
    val del = diffDelColor()
    val (bg, fg, gutter, sign) = when (line.kind) {
        DiffLine.Kind.ADD -> Quad(
            add.copy(alpha = 0.14f),
            add,
            add.copy(alpha = 0.18f),
            "+",
        )
        DiffLine.Kind.REMOVE -> Quad(
            del.copy(alpha = 0.14f),
            del,
            del.copy(alpha = 0.18f),
            "-",
        )
        DiffLine.Kind.CONTEXT -> Quad(
            Color.Transparent,
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
            MaterialTheme.colorScheme.surface,
            " ",
        )
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
                .background(if (line.kind == DiffLine.Kind.CONTEXT) Color.Transparent else fg),
        )
        Text(
            text = (line.oldNo?.toString() ?: "").padStart(3),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            ),
            color = if (line.kind == DiffLine.Kind.REMOVE) fg else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(28.dp)
                .background(gutter)
                .padding(end = 4.dp),
        )
        Text(
            text = (line.newNo?.toString() ?: "").padStart(3),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            ),
            color = if (line.kind == DiffLine.Kind.ADD) fg else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(28.dp)
                .background(gutter)
                .padding(end = 4.dp),
        )
        Text(
            text = sign,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            ),
            color = fg,
            modifier = Modifier.padding(start = 4.dp),
        )
        Text(
            text = line.text.ifEmpty { " " },
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 15.sp,
            ),
            color = if (line.kind == DiffLine.Kind.CONTEXT) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f)
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.95f)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, end = 8.dp, top = 1.dp, bottom = 1.dp),
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
