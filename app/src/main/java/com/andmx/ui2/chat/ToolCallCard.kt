package com.andmx.ui2.chat

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import com.andmx.ui2.theme.LocalMotion
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.text.style.TextAlign
import com.andmx.agent.ToolArgs
import com.andmx.diff.DiffLine
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val prettyJson = Json { prettyPrint = true }

@Composable
fun ToolCallCard(
    toolCall: ToolCall,
    workflowStatus: (suspend (String) -> String?)? = null,
) {
    val isEditTool = ToolPresentation.isEditTool(toolCall.name)
    val collapsible = ToolPresentation.isCollapsible(toolCall)
    var expanded by remember(toolCall.id) {
        mutableStateOf(ToolPresentation.defaultExpanded(toolCall))
    }
    var userToggled by remember(toolCall.id) { mutableStateOf(false) }
    var wasRunning by remember(toolCall.id) { mutableStateOf(toolCall.isRunning) }
    var contentMounted by remember(toolCall.id) { mutableStateOf(expanded) }

    LaunchedEffect(toolCall.isRunning, toolCall.isError, isEditTool) {
        if (userToggled || !collapsible) {
            wasRunning = toolCall.isRunning
            return@LaunchedEffect
        }
        if (toolCall.isRunning || toolCall.isError) {
            expanded = true
        } else if (wasRunning) {
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
        if (isEditTool) ToolEditDiff.preview(toolCall.name, toolCall.args) else null
    }
    val todoItems = remember(toolCall.id, toolCall.args) { ToolPresentation.todoItems(toolCall) }
    val family = ToolPresentation.family(toolCall.name)
    val canonicalName = ToolArgs.canonical(toolCall.name)
    val kindLabel = if (editPreview != null) {
        editKindLabel(editPreview.operation, toolCall.isRunning, toolCall.isError)
    } else {
        ToolPresentation.kindLabel(toolCall)
    }
    val summary = if (editPreview != null) {
        fileNameOf(editPreview.path).ifBlank { ToolPresentation.summary(toolCall) }
    } else {
        ToolPresentation.summary(toolCall)
    }
    val secondary = if (editPreview != null) {
        pathDirHint(editPreview.path)
    } else {
        ToolPresentation.secondary(toolCall)
    }
    val motion = LocalMotion.current
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = motion.defaultEffects,
        label = "toolChevron",
    )

    val headerInteraction = remember { MutableInteractionSource() }
    val pressed by headerInteraction.collectIsPressedAsState()
    val headerBg = if (pressed) {
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)
    } else {
        Color.Transparent
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 1.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(headerBg)
                .clickable(enabled = collapsible, interactionSource = headerInteraction, indication = null) {
                    userToggled = true
                    expanded = !expanded
                }
                .padding(vertical = 5.dp, horizontal = if (collapsible) 2.dp else 0.dp),
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
                if (toolCall.isRunning) {
                    // ZCode QueuedSummaryContent：运行中摘要变化时滚动切换。
                    AnimatedContent(
                        targetState = summary,
                        transitionSpec = {
                            (slideInVertically { it / 2 } + fadeIn())
                                .togetherWith(slideOutVertically { -it / 2 } + fadeOut())
                        },
                        label = "summaryRoll",
                        modifier = Modifier.weight(1f, fill = false).widthIn(max = 240.dp),
                    ) { s ->
                        Text(
                            text = s,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).widthIn(max = 240.dp),
                    )
                }
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
            if (collapsible) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    null,
                    Modifier
                        .padding(start = 4.dp)
                        .size(16.dp)
                        .rotate(chevronRotation)
                        .alpha(if (expanded || toolCall.isRunning || toolCall.isError) 0.85f else 0.55f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }

        val actions = remember(toolCall.id, toolCall.name, toolCall.args, toolCall.output) {
            ToolPresentation.actions(toolCall)
        }
        if (actions.isNotEmpty() && (expanded || toolCall.isRunning)) {
            val clipboard = LocalClipboardManager.current
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
                                is ToolPresentation.Action.OpenFile -> ChatActionBus.openFile(action.path)
                                is ToolPresentation.Action.OpenTerminal -> ChatActionBus.openTerminal()
                                is ToolPresentation.Action.OpenUrl -> ChatActionBus.openUrl(action.url)
                                is ToolPresentation.Action.Copy -> clipboard.setText(AnnotatedString(action.text))
                            }
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp,
                            vertical = 0.dp,
                        ),
                    ) {
                        Icon(
                            when (action) {
                                is ToolPresentation.Action.OpenFile -> Icons.Outlined.Description
                                is ToolPresentation.Action.OpenTerminal -> Icons.Outlined.Terminal
                                is ToolPresentation.Action.OpenUrl -> Icons.AutoMirrored.Outlined.OpenInNew
                                is ToolPresentation.Action.Copy -> Icons.Outlined.ContentCopy
                            },
                            null,
                            Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(4.dp))
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
            enter = expandVertically(animationSpec = motion.defaultExpand) + fadeIn(motion.defaultEffects),
            exit = shrinkVertically(animationSpec = motion.defaultExpand) + fadeOut(motion.defaultEffects),
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
                } else if (todoItems != null) {
                    // todo 专属卡：args.todos 直接渲染为清单，不暴露原始 JSON。
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.38f),
                            )
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        todoItems.forEach { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    when (item.status.lowercase()) {
                                        "completed" -> Icons.Outlined.CheckCircle
                                        "in_progress" -> Icons.Outlined.Schedule
                                        else -> Icons.Outlined.RadioButtonUnchecked
                                    },
                                    null,
                                    Modifier.size(13.dp),
                                    tint = when (item.status.lowercase()) {
                                        "completed" -> MaterialTheme.colorScheme.primary
                                        "in_progress" -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    },
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    item.content,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = if (item.status.lowercase() == "completed") {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                        if (!toolCall.output.isNullOrBlank() && toolCall.isError) {
                            MetaBlock(title = "错误", body = toolCall.output.take(4000), mono = true, emphasize = true)
                        }
                    }
                } else if (canonicalName == "shell" || canonicalName == "git") {
                    ExecuteToolCard(toolCall)
                } else if (canonicalName == "ask") {
                    AskQuestionsCard(toolCall)
                } else if (canonicalName == "workflow" || canonicalName == "cron" ||
                    canonicalName == "webfetch" || canonicalName == "search"
                ) {
                    StructuredToolCard(toolCall, canonicalName, workflowStatus)
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
                if (!toolCall.imageUrls.isNullOrEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    ScreenshotStrip(toolCall.imageUrls.orEmpty())
                }
            }
        }
    }
}

/** 专属卡：workflow/cron/webfetch/search 工具的结构化字段行 + 输出。 */
@Composable
private fun StructuredToolCard(
    toolCall: ToolCall,
    canonical: String,
    workflowStatus: (suspend (String) -> String?)? = null,
) {
    val fieldKeys = when (canonical) {
        "workflow" -> listOf("name", "spec", "runId", "run_id", "task", "phase", "nodeId")
        "cron" -> listOf("name", "task", "schedule", "cron", "automationId", "id", "prompt")
        else -> listOf("url", "query", "prompt")
    }
    val rows = remember(toolCall.id, toolCall.args) {
        argsFieldRows(toolCall.args, fieldKeys)
    }
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
        if (rows.isNotEmpty()) {
            rows.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        k,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(72.dp),
                    )
                    Text(
                        v.take(160),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (!toolCall.output.isNullOrBlank() || toolCall.isRunning) {
                Spacer(Modifier.height(8.dp))
            }
        } else if (toolCall.args.isNotBlank() && toolCall.args != "{}") {
            MetaBlock(title = "参数", body = prettyArgs(toolCall.args))
            if (!toolCall.output.isNullOrBlank() || toolCall.isRunning) {
                Spacer(Modifier.height(8.dp))
            }
        }
        if (canonical == "workflow" && workflowStatus != null) {
            val runId = remember(toolCall.id, toolCall.args, toolCall.output) {
                extractWorkflowRunId(toolCall.args) ?: extractWorkflowRunId(toolCall.output.orEmpty())
            }
            if (runId != null) {
                var statusLine by remember(toolCall.id) { mutableStateOf<String?>(null) }
                LaunchedEffect(runId, toolCall.isRunning) {
                    while (true) {
                        statusLine = runCatching { workflowStatus(runId) }.getOrNull()
                        if (!toolCall.isRunning && statusLine != null) break
                        kotlinx.coroutines.delay(2_000)
                    }
                }
                statusLine?.let {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "run $runId · $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if ((canonical == "webfetch" || canonical == "search") && !toolCall.output.isNullOrBlank()) {
            val links = remember(toolCall.id, toolCall.output) { extractSourceUrls(toolCall.output) }
            if (links.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "来源",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    links.forEach { url ->
                        Text(
                            url.substringAfter("://").substringBefore("/").ifBlank { url }.take(28),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                .clickable { ChatActionBus.openUrl(url) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        when {
            !toolCall.output.isNullOrBlank() -> MetaBlock(
                title = if (toolCall.isRunning) "输出" else "结果",
                body = toolCall.output.take(12000),
                mono = true,
                emphasize = toolCall.isError,
                stickToBottom = toolCall.isRunning,
            )
            toolCall.isRunning -> Text(
                "执行中…",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
            )
            toolCall.isError -> Text(
                "无输出",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * shell/git 专属展开体（ZCode execute.tsx 对齐）：
 * `$` + 命令行（mono）+ 输出视口（约 5 行高、流式吸底、上滚冻结、回底恢复）。
 * 参数不再以 pretty JSON 形式展示。
 */
@Composable
private fun ExecuteToolCard(toolCall: ToolCall) {
    val command = remember(toolCall.id, toolCall.name, toolCall.args) {
        ToolArgs.shellCommand(toolCall.name, toolCall.args)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                RoundedCornerShape(10.dp),
            )
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (command.isNotBlank()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    "$",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                Text(
                    command,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.95f),
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (!toolCall.output.isNullOrBlank() || toolCall.isRunning) {
                Spacer(Modifier.height(6.dp))
            }
        }
        when {
            !toolCall.output.isNullOrBlank() -> FollowableOutputText(
                text = stripAnsi(toolCall.output.orEmpty()).take(12000),
                following = toolCall.isRunning,
                emphasize = toolCall.isError,
                maxHeight = 96.dp,
                fadeColor = MaterialTheme.colorScheme.surface,
            )
            toolCall.isRunning -> Text(
                "执行中…",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
            )
            toolCall.isError -> Text(
                "无输出",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** ask（AskUserQuestion）完成态展开体：逐题列出问题与用户回答（ZCode ask-question 对齐）。 */
@Composable
private fun AskQuestionsCard(toolCall: ToolCall) {
    val questions = remember(toolCall.id, toolCall.args) {
        val obj = runCatching {
            prettyJson.parseToJsonElement(toolCall.args)
                as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return@remember emptyList()
        com.andmx.agent.zcode.AskUserQuestionParser.parse(obj)
    }
    val output = toolCall.output.orEmpty()
    val answers = remember(toolCall.id, output) { parseAskAnswers(output) }
    val autoSkipped = output.contains("did not provide")
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        questions.forEach { q ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        q.header,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        q.question,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(2.dp))
                val answer = answers[q.question]
                Text(
                    when {
                        answer != null -> "→ $answer"
                        autoSkipped -> "— 自动继续，未作答"
                        else -> "— 未作答"
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = if (answer != null) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                )
            }
        }
        if (questions.isEmpty() && output.isNotBlank()) {
            MetaBlock(
                title = "结果",
                body = output.take(4000),
                mono = true,
                emphasize = toolCall.isError,
            )
        }
        if (toolCall.isError && output.isBlank()) {
            Text(
                "无输出",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private val ASK_ANSWER_RE = Regex("\"([^\"\n]+)\"=\"([^\"\n]*)\"")

private fun parseAskAnswers(output: String): Map<String, String> =
    ASK_ANSWER_RE.findAll(output)
        .associate { it.groupValues[1] to it.groupValues[2] }

private const val FOLLOW_BOTTOM_TOLERANCE_PX = 24

/**
 * 可跟随输出视口（ZCode ExecuteOutput 对齐）：流式期间吸底跟随；
 * 用户上滚即冻结当前文本快照，回到底部恢复跟随；结束/非流式时总是解冻。
 * 顶部渐隐遮罩提示上方还有内容。
 */
@Composable
private fun FollowableOutputText(
    text: String,
    following: Boolean,
    modifier: Modifier = Modifier,
    mono: Boolean = true,
    emphasize: Boolean = false,
    maxHeight: Dp = 260.dp,
    fadeColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    val scroll = rememberScrollState()
    var frozen by remember { mutableStateOf<String?>(null) }
    val latestText by rememberUpdatedState(text)
    val display = frozen ?: text
    var prevScroll by remember { mutableIntStateOf(0) }

    LaunchedEffect(display, following, frozen == null) {
        if (following && frozen == null && display.isNotEmpty()) {
            scroll.scrollTo(scroll.maxValue)
            prevScroll = scroll.value
        }
    }
    LaunchedEffect(following) {
        if (!following) {
            frozen = null
            return@LaunchedEffect
        }
        prevScroll = scroll.value
        // 程序吸底只会增大 scroll.value；值减小即用户在上滚。
        snapshotFlow { scroll.value to scroll.maxValue }.collect { (v, max) ->
            val atBottom = max - v <= FOLLOW_BOTTOM_TOLERANCE_PX
            if (frozen == null) {
                if (v < prevScroll && !atBottom) frozen = latestText
            } else if (atBottom) {
                frozen = null
            }
            prevScroll = v
        }
    }
    Box(modifier.fillMaxWidth()) {
        Text(
            text = display,
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
                .heightIn(max = maxHeight)
                .verticalScroll(scroll),
        )
        if (scroll.value > 8) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(fadeColor.copy(alpha = 0.92f), Color.Transparent),
                        ),
                    ),
            )
        }
    }
}

private val SOURCE_URL_RE = Regex("""https?://[^\s"'()\[\]<>]+""")

private fun extractSourceUrls(output: String): List<String> =
    SOURCE_URL_RE.findAll(output).map { it.value.trimEnd('.', ',', ';', ':', ')') }
        .distinct().take(8).toList()

private val ANSI_RE = Regex(
    "\\u001B\\[[0-9;?]*[a-zA-Z]|\\u001B\\][^\\u0007]*[\\u0007]|\\u001B[()][0-9A-B]",
)

private fun stripAnsi(text: String): String =
    if (text.indexOf('\u001B') < 0) text else ANSI_RE.replace(text, "")

private val RUN_ID_RE = Regex("""\b(?:run[_-]?id|runId)["'\s:=]+([A-Za-z0-9][A-Za-z0-9_-]{5,})""")

private fun extractWorkflowRunId(text: String): String? {
    val m = RUN_ID_RE.find(text) ?: return null
    return m.groupValues[1]
}

private data class TestSummary(val passed: Int, val failed: Int, val skipped: Int)

private val TEST_COUNT_RE = Regex("""(\d+)\s+(passed|failed|skipped|xpassed|xfailed)""")

private fun detectTestSummary(output: String): TestSummary? {
    if (output.indexOf('') >= 0 && !output.contains("pass") && !output.contains("fail")) return null
    val tail = output.takeLast(4000)
    var passed = 0; var failed = 0; var skipped = 0
    var matched = false
    TEST_COUNT_RE.findAll(tail).forEach { m ->
        val n = m.groupValues[1].toIntOrNull() ?: return@forEach
        when (m.groupValues[2]) {
            "passed" -> { passed = n; matched = true }
            "failed" -> { failed = n; matched = true }
            "skipped" -> { skipped = n; matched = true }
        }
    }
    return if (matched && passed + failed + skipped > 0) TestSummary(passed, failed, skipped) else null
}

private fun argsFieldRows(raw: String, keys: List<String>): List<Pair<String, String>> {
    val el = runCatching {
        prettyJson.parseToJsonElement(raw.trim()) as? kotlinx.serialization.json.JsonObject
    }.getOrNull() ?: return emptyList()
    return keys.mapNotNull { k ->
        el[k]?.let { v ->
            val text = if (v is kotlinx.serialization.json.JsonPrimitive) v.content else v.toString()
            if (text.isBlank()) null else k to text
        }
    }
}

@Composable
private fun ScreenshotStrip(urls: List<String>) {
    var zoomIndex by remember { mutableStateOf<Int?>(null) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        urls.forEachIndexed { idx, url ->
            val bmp = remember(url) { decodeDataImage(url) } ?: return@forEachIndexed
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier
                    .height(96.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { zoomIndex = idx },
                contentScale = ContentScale.Fit,
            )
        }
    }
    zoomIndex?.let { start ->
        val bitmaps = remember(urls) { urls.mapNotNull { decodeDataImage(it) } }
        val pagerState = androidx.compose.foundation.pager.rememberPagerState(
            initialPage = start.coerceIn(0, (bitmaps.size - 1).coerceAtLeast(0)),
        ) { bitmaps.size }
        AlertDialog(
            onDismissRequest = { zoomIndex = null },
            confirmButton = {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (bitmaps.size > 1) {
                        Text(
                            "${pagerState.currentPage + 1}/${bitmaps.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                    TextButton(onClick = { zoomIndex = null }) { Text("关闭") }
                }
            },
            text = {
                androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth(),
                ) { page ->
                    Image(
                        bitmap = bitmaps[page],
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit,
                    )
                }
            },
        )
    }
}

private fun decodeDataImage(url: String): ImageBitmap? = runCatching {
    val b64 = url.substringAfter("base64,", url)
    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

@Composable
private fun MetaBlock(
    title: String,
    body: String,
    mono: Boolean = false,
    emphasize: Boolean = false,
    stickToBottom: Boolean = false,
) {
    val scroll = rememberScrollState()
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
        if (stickToBottom) {
            FollowableOutputText(
                text = body,
                following = true,
                mono = mono,
                emphasize = emphasize,
                maxHeight = 260.dp,
            )
        } else {
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
    val motion = LocalMotion.current
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = motion.defaultEffects,
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

    val kind = ToolPresentation.groupKind(tools.first().name)
    val title = buildString {
        // ZCode 分组语义：改动按去重文件数、执行按命令数、只读按步数。
        when (kind) {
            ToolPresentation.GroupKind.CHANGE -> {
                val files = tools.mapNotNull {
                    ToolArgs.filePath(it.name, it.args).takeIf(String::isNotBlank)
                }.distinct()
                append(if (files.isNotEmpty()) "改动 ${files.size} 个文件" else "${tools.size} 处改动")
            }
            ToolPresentation.GroupKind.EXECUTE -> append("执行 ${tools.size} 条命令")
            else -> {
                append(tools.size)
                append(" 步")
                val labels = tools.map { ToolPresentation.family(it.name).label }.distinct().take(3)
                if (labels.isNotEmpty()) {
                    append(" · ")
                    append(labels.joinToString(" / "))
                }
            }
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
            enter = expandVertically(animationSpec = motion.defaultExpand) + fadeIn(motion.defaultEffects),
            exit = shrinkVertically(animationSpec = motion.defaultExpand) + fadeOut(motion.defaultEffects),
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
    val family = ToolPresentation.family(tool.name)
    val summary = ToolPresentation.summary(tool)
    val label = ToolPresentation.kindLabel(tool)
    // ZCode 分组的子项是完整 ToolCallBlock；这里保持单行但允许点开看输出。
    val expandable = !tool.output.isNullOrBlank()
    var open by remember(tool.id) { mutableStateOf(false) }
    val motion = LocalMotion.current
    val chevronRotation by animateFloatAsState(
        targetValue = if (open) 90f else 0f,
        animationSpec = motion.defaultEffects,
        label = "groupChildChevron",
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = expandable) { open = !open }
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
            if (expandable) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    null,
                    Modifier
                        .padding(start = 2.dp)
                        .size(13.dp)
                        .rotate(chevronRotation)
                        .alpha(if (open) 0.85f else 0.4f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(animationSpec = motion.defaultExpand) + fadeIn(motion.defaultEffects),
            exit = shrinkVertically(animationSpec = motion.defaultExpand) + fadeOut(motion.defaultEffects),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 21.dp, top = 1.dp, bottom = 3.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f),
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stripAnsi(tool.output.orEmpty()).take(3000),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    ),
                    color = if (tool.isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
fun WorkingIndicator(status: String? = null) {
    var elapsed by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        elapsed = 0
        while (true) {
            kotlinx.coroutines.delay(1000L)
            elapsed += 1
        }
    }
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
        GradientRunningLabel(status ?: "思考中…")
        Spacer(Modifier.weight(1f))
        Text(
            text = formatElapsed(elapsed),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

private fun formatDurationMs(ms: Long): String = when {
    ms < 1_000 -> "${ms}ms"
    ms < 60_000 -> "%.1fs".format(ms / 1000.0)
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
}

private fun formatElapsed(seconds: Int): String {
    val s = seconds % 60
    return if (seconds < 60) "${s}s" else "${seconds / 60}m ${s.toString().padStart(2, '0')}s"
}
