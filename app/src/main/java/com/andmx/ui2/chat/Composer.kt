package com.andmx.ui2.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.andmx.agent.SlashCommands
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.provider.ReasoningConfig

/**
 * ZCode 对齐的对话输入区。
 *
 * 底栏：`+` | 执行模式 | spacer | 模型 | 思考级别 | 发送/停止
 *
 * `+` 菜单（对标 ZCode Adding Context）：
 * 添加附件 / 插入 @ / 插入 # / 插入 /
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    isLoading: Boolean,
    onStop: () -> Unit,
    modelLabel: String,
    selectedModel: String,
    activeProviderId: String,
    providers: List<ProviderDefinition>,
    onSwitchModel: (providerId: String, modelId: String) -> Unit,
    reasoningEffort: String,
    reasoning: ReasoningConfig?,
    onReasoningSelected: (String) -> Unit,
    execMode: ExecMode,
    onExecModeSelected: (ExecMode) -> Unit,
    contextChips: List<ContextChip> = emptyList(),
    onRemoveContextChip: (String) -> Unit = {},
    attachments: List<Attachment> = emptyList(),
    onRemoveAttachment: (Int) -> Unit = {},
    onAddAttachment: () -> Unit = {},
    onInsertMention: () -> Unit = {},
    onInsertConversation: () -> Unit = {},
    onInsertCommand: () -> Unit = {},
    onInsertSkill: () -> Unit = {},
    slashSuggestions: List<SlashCommands.Spec> = emptyList(),
    onPickSlash: (SlashCommands.Spec) -> Unit = {},
    conversationSuggestions: List<ConversationPick> = emptyList(),
    onPickConversation: (ConversationPick) -> Unit = {},
    skillSuggestions: List<SkillSuggestion> = emptyList(),
    onPickSkill: (SkillSuggestion) -> Unit = {},
    placeholder: String = DEFAULT_PLACEHOLDER,
    flat: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val canSend = value.isNotBlank() || attachments.isNotEmpty() || contextChips.isNotEmpty()
    val shape = RoundedCornerShape(20.dp)
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val cardBg = MaterialTheme.colorScheme.surfaceContainerHigh

    // ZCode 快捷键：Shift+Tab 循环切换执行模式
    fun cycleExecMode() {
        val next = ExecMode.entries.let { all ->
            val idx = all.indexOf(execMode)
            all[(idx + 1) % all.size]
        }
        onExecModeSelected(next)
    }

    Column(
        modifier
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    event.key == Key.Tab && event.isShiftPressed
                ) {
                    cycleExecMode()
                    true
                } else {
                    false
                }
            },
    ) {
        AnimatedVisibility(visible = slashSuggestions.isNotEmpty()) {
            SuggestionPanel {
                slashSuggestions.forEach { spec ->
                    SuggestionRow(
                        leading = {
                            Text(
                                spec.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontFamily = FontFamily.Monospace,
                            )
                        },
                        trailing = spec.desc,
                        onClick = { onPickSlash(spec) },
                    )
                }
            }
        }
        AnimatedVisibility(visible = conversationSuggestions.isNotEmpty() && slashSuggestions.isEmpty()) {
            SuggestionPanel {
                conversationSuggestions.forEach { pick ->
                    SuggestionRow(
                        leading = {
                            Icon(
                                Icons.AutoMirrored.Outlined.Chat,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "#${pick.title}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailing = pick.subtitle,
                        onClick = { onPickConversation(pick) },
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = skillSuggestions.isNotEmpty() && slashSuggestions.isEmpty() && conversationSuggestions.isEmpty(),
        ) {
            SuggestionPanel {
                skillSuggestions.forEach { skill ->
                    SuggestionRow(
                        leading = {
                            Icon(
                                Icons.Outlined.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "\$${skill.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailing = "",
                        onClick = { onPickSkill(skill) },
                    )
                }
            }
        }

        val cardModifier = if (flat) {
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 2.dp)
        } else {
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, borderColor, shape)
                .background(cardBg)
                .padding(horizontal = 10.dp, vertical = 10.dp)
        }
        Box(cardModifier) {
            Column {
                if (attachments.isNotEmpty()) {
                    FlowRow(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        attachments.forEachIndexed { i, att ->
                            ContextChipView(
                                icon = Icons.Outlined.AttachFile,
                                label = att.name,
                                onRemove = { onRemoveAttachment(i) },
                            )
                        }
                    }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    if (contextChips.isNotEmpty()) {
                        FlowRow(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            contextChips.forEach { chip ->
                                MentionChipView(
                                    kind = chip.kind,
                                    label = chip.label,
                                    onRemove = { onRemoveContextChip(chip.id) },
                                )
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth()) {
                        if (value.isEmpty() && contextChips.isEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 8,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 28.dp, max = 168.dp),
                        )
                    }
                }

                Spacer(Modifier.size(8.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PlusMenu(
                        onAddAttachment = onAddAttachment,
                        onInsertMention = onInsertMention,
                        onInsertConversation = onInsertConversation,
                        onInsertCommand = onInsertCommand,
                        onInsertSkill = onInsertSkill,
                    )
                    ExecModePill(mode = execMode, onSelect = onExecModeSelected)
                    Spacer(Modifier.weight(1f))
                    ModelPill(
                        modelLabel = modelLabel,
                        selectedModel = selectedModel,
                        activeProviderId = activeProviderId,
                        providers = providers,
                        onSwitchModel = onSwitchModel,
                    )
                    ThoughtPill(
                        effort = reasoningEffort,
                        reasoning = reasoning,
                        onSelect = onReasoningSelected,
                    )
                    SendStopButton(
                        isLoading = isLoading,
                        canSend = canSend,
                        onSend = onSend,
                        onStop = onStop,
                    )
                }
            }
        }
    }
}

// ── + 菜单 ────────────────────────────────────────────────────────────────

@Composable
private fun PlusMenu(
    onAddAttachment: () -> Unit,
    onInsertMention: () -> Unit,
    onInsertConversation: () -> Unit,
    onInsertCommand: () -> Unit,
    onInsertSkill: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        CircleIcon(Icons.Outlined.Add, "添加上下文") { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PlusMenuItem(Icons.Outlined.AttachFile, "添加附件", "上传截图、文档作为上下文") {
                expanded = false
                onAddAttachment()
            }
            PlusMenuItem(Icons.AutoMirrored.Outlined.InsertDriveFile, "插入 @ 引用", "引用工作区文件") {
                expanded = false
                onInsertMention()
            }
            PlusMenuItem(Icons.AutoMirrored.Outlined.Chat, "插入 # 会话", "关联历史对话上下文") {
                expanded = false
                onInsertConversation()
            }
            PlusMenuItem(Icons.Outlined.Terminal, "插入 / 命令", "调用已保存的命令或子智能体") {
                expanded = false
                onInsertCommand()
            }
            PlusMenuItem(Icons.Outlined.Bolt, "插入 \$ 技能", "调用已安装的技能") {
                expanded = false
                onInsertSkill()
            }
        }
    }
}

@Composable
private fun PlusMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        leadingIcon = {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        },
        onClick = onClick,
    )
}

// ── 执行模式 ──────────────────────────────────────────────────────────────

@Composable
private fun ExecModePill(
    mode: ExecMode,
    onSelect: (ExecMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            // pill 图标跟随当前模式（ZCode：每档不同图标）
            Icon(
                mode.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                mode.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
            )
            Icon(
                Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(14.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ExecMode.entries.forEach { option ->
                val selected = option == mode
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                option.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    // 每档显示各自图标；选中时用 primary 高亮
                    leadingIcon = {
                        Icon(
                            option.icon(),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingIcon = {
                        if (selected) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
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

// ── 模型选择 ──────────────────────────────────────────────────────────────

@Composable
private fun ModelPill(
    modelLabel: String,
    selectedModel: String,
    activeProviderId: String,
    providers: List<ProviderDefinition>,
    onSwitchModel: (providerId: String, modelId: String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var focusProviderId by remember(activeProviderId, providers) {
        mutableStateOf(activeProviderId.ifBlank { providers.firstOrNull()?.id.orEmpty() })
    }
    var query by remember { mutableStateOf("") }
    val panelShape = RoundedCornerShape(16.dp)

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .clickable {
                    expanded = true
                    focusProviderId = activeProviderId.ifBlank { providers.firstOrNull()?.id.orEmpty() }
                    query = ""
                }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                modelLabel.ifBlank { "选择模型" },
                style = MaterialTheme.typography.labelMedium,
                color = if (modelLabel.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
            Icon(
                Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }

        if (expanded) {
            Popup(
                alignment = Alignment.BottomEnd,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                Box(
                    Modifier
                        .widthIn(min = 280.dp, max = 340.dp)
                        .padding(bottom = 4.dp)
                        .clip(panelShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, panelShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(Modifier.heightIn(max = 320.dp)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            if (query.isEmpty()) {
                                Text(
                                    "搜索模型…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        HorizontalDivider()
                        Row(Modifier.heightIn(max = 260.dp)) {
                            Column(
                                Modifier
                                    .widthIn(min = 100.dp, max = 112.dp)
                                    .verticalScroll(rememberScrollState())
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                            ) {
                                if (providers.isEmpty()) {
                                    Text(
                                        "暂无供应商",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(12.dp),
                                    )
                                }
                                providers.filter { it.enabled }.forEach { p ->
                                    val selected = p.id == focusProviderId
                                    val active = p.id == activeProviderId
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                if (selected) {
                                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceContainerLow
                                                },
                                            )
                                            .clickable {
                                                focusProviderId = p.id
                                                query = ""
                                            }
                                            .padding(horizontal = 10.dp, vertical = 10.dp),
                                    ) {
                                        if (active) {
                                            Box(
                                                Modifier
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primary),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                        } else {
                                            Spacer(Modifier.width(12.dp))
                                        }
                                        Text(
                                            p.name.ifBlank { "未命名" },
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            val focus = providers.firstOrNull { it.id == focusProviderId }
                            val models = (focus?.models?.keys?.toList() ?: emptyList())
                                .filter { query.isBlank() || it.contains(query, ignoreCase = true) }
                            Column(
                                Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                if (models.isEmpty()) {
                                    Text(
                                        if (focus == null) "选择供应商" else "该供应商暂无模型",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(12.dp),
                                    )
                                }
                                models.forEach { mid ->
                                    val isSelected =
                                        focus?.id == activeProviderId && mid == selectedModel
                                    val display = focus?.models?.get(mid)?.displayName
                                        ?.takeIf { it.isNotBlank() } ?: mid
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                if (isSelected) {
                                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                                } else {
                                                    MaterialTheme.colorScheme.surface
                                                },
                                            )
                                            .clickable {
                                                if (focus != null) {
                                                    onSwitchModel(focus.id, mid)
                                                    expanded = false
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                Icons.Outlined.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        Text(
                                            display,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── 思考级别 ──────────────────────────────────────────────────────────────

@Composable
private fun ThoughtPill(
    effort: String,
    reasoning: ReasoningConfig?,
    onSelect: (String) -> Unit,
) {
    val options = remember(reasoning) { thoughtOptionsFor(reasoning) }
    if (options.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val label = thoughtLabel(effort, options)

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .clickable { expanded = true }
                .padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            Icon(
                Icons.Outlined.Psychology,
                contentDescription = "思考级别",
                tint = if (effort.equals("off", true) || effort.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                val selected = opt.wireValue.equals(effort, ignoreCase = true) ||
                    (effort.isBlank() && opt.wireValue == "off")
                DropdownMenuItem(
                    text = { Text(opt.label) },
                    leadingIcon = {
                        if (selected) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Spacer(Modifier.size(16.dp))
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(opt.wireValue)
                    },
                )
            }
        }
    }
}

// ── 发送 / 停止 ───────────────────────────────────────────────────────────

@Composable
private fun SendStopButton(
    isLoading: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val active = isLoading || canSend
    val bg = if (active) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val tint = if (active) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant

    AnimatedContent(
        targetState = isLoading,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "sendStop",
    ) { busy ->
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(bg)
                .clickable(enabled = busy || canSend) {
                    if (busy) onStop() else onSend()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (busy) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward,
                contentDescription = if (busy) "停止生成" else "发送",
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ── 共用小件 ──────────────────────────────────────────────────────────────

@Composable
private fun CircleIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = desc,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ContextChipView(
    icon: ImageVector,
    label: String,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp),
        )
        Box(
            Modifier
                .padding(start = 2.dp)
                .size(18.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Close, "移除", Modifier.size(12.dp))
        }
    }
}

@Composable
private fun MentionChipView(
    kind: ContextChipKind,
    label: String,
    onRemove: () -> Unit,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val (fg, bg) = when (kind) {
        ContextChipKind.SKILL -> {
            val c = if (isDark) Color(0xFFE8E8E8) else Color(0xFF1A1A1A)
            val bg = if (isDark) Color(0xFF2B2B2B) else Color(0xFF161616).copy(alpha = 0.08f)
            c to bg
        }
        ContextChipKind.COMMAND -> {
            val c = if (isDark) Color(0xFFCAD5E2) else Color(0xFF475569)
            c to c.copy(alpha = if (isDark) 0.16f else 0.10f)
        }
        ContextChipKind.FILE -> {
            val c = if (isDark) Color(0xFF8FC5EF) else Color(0xFF1A70B8)
            c to c.copy(alpha = if (isDark) 0.16f else 0.10f)
        }
        ContextChipKind.CONVERSATION -> {
            val c = if (isDark) Color(0xFF7DD3FC) else Color(0xFF0284C7)
            c to c.copy(alpha = if (isDark) 0.16f else 0.10f)
        }
        ContextChipKind.ATTACHMENT -> {
            val c = MaterialTheme.colorScheme.onSurfaceVariant
            c to MaterialTheme.colorScheme.surfaceContainerHighest
        }
    }
    val display = when (kind) {
        ContextChipKind.SKILL -> label.removePrefix("$")
        ContextChipKind.COMMAND -> label.removePrefix("/")
        else -> label
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(start = 6.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
    ) {
        Icon(
            kind.icon(),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = fg,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            display,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )
        Box(
            Modifier
                .padding(start = 2.dp)
                .size(18.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Close, "移除", Modifier.size(12.dp), tint = fg.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun SuggestionPanel(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            Modifier
                .padding(vertical = 4.dp)
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            content()
        }
    }
}

@Composable
private fun SuggestionRow(
    leading: @Composable () -> Unit,
    trailing: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) { leading() }
        if (trailing.isNotBlank()) {
            Text(
                trailing,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .widthIn(max = 140.dp),
            )
        }
    }
}

private fun ContextChipKind.icon(): ImageVector = when (this) {
    ContextChipKind.FILE -> Icons.AutoMirrored.Outlined.InsertDriveFile
    ContextChipKind.CONVERSATION -> Icons.AutoMirrored.Outlined.Chat
    ContextChipKind.COMMAND -> Icons.Outlined.Terminal
    ContextChipKind.SKILL -> Icons.Outlined.AutoAwesome
    ContextChipKind.ATTACHMENT -> Icons.Outlined.AttachFile
}

/**
 * 执行模式图标（逆向自 ZCode 执行模式菜单截图：每档不同图标）。
 * - 改前确认：手掌「停/等」
 * - 自动编辑：盾牌（文件改动自动放行）
 * - 计划模式：清单文档
 * - 完全访问：火箭（全速放行）
 */
private fun ExecMode.icon(): ImageVector = when (this) {
    ExecMode.CONFIRM -> Icons.Outlined.PanTool
    ExecMode.AUTO_EDIT -> Icons.Outlined.Security
    ExecMode.PLAN -> Icons.Outlined.ListAlt
    ExecMode.FULL -> Icons.Outlined.RocketLaunch
}

data class ConversationPick(
    val id: Long,
    val title: String,
    val subtitle: String = "",
)

data class Attachment(val name: String, val uri: String)

/** $ 技能联想项（解耦自 agent 层 SkillInstaller，Composer 只认这个）。 */
data class SkillSuggestion(
    val name: String,
    val path: String,
)

/** ZCode 对齐占位符。 */
private const val DEFAULT_PLACEHOLDER =
    "向 AndMX 提问，@ 提及文件，/ 使用命令或子智能体，\$ 使用技能，# 关联对话"
