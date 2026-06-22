package com.andmx.ui.conversation

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.BasicTextField
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.ui.components.FormField
import com.andmx.ui.components.SelectableChip
import com.andmx.llm.provider.ProviderKind
import com.andmx.llm.provider.ReasoningConfig
import com.andmx.llm.provider.ReasoningStyle
import com.andmx.settings.ProviderSettings
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Radii
import com.andmx.ui.theme.Spacing

/**
 * Settings sheet — mobile-first, split into two tabs so the two distinct save
 * actions (provider connection vs. user preferences) no longer share a single
 * over-long scroll. IME and the navigation bar are both avoided so the save
 * button is always reachable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    current: ProviderSettings,
    providers: List<ProviderDefinition>,
    activeProvider: ProviderDefinition?,
    onDismiss: () -> Unit,
    onSavePreferences: (ProviderSettings) -> Unit,
    onSaveProvider: (ProviderDefinition, makePrimary: Boolean) -> Unit,
    onAddBlankProvider: () -> Unit,
    onDeleteProvider: (String) -> Unit,
    onSelectProvider: (String) -> Unit,
) {
    val colors = AndmxTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableIntStateOf(0) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.canvas) {
        TabRow(
            selectedTabIndex = tab,
            containerColor = colors.canvas,
            contentColor = colors.accent,
        ) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("供应商") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("偏好") })
        }
        // AnimatedContent slides between the two tabs so the form swap reads as a
        // deliberate transition rather than a hard replace.
        androidx.compose.animation.AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val direction = if (targetState > initialState) 1 else -1
                (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(com.andmx.ui.theme.Motion.DUR_STD)) { full -> full * direction } +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(com.andmx.ui.theme.Motion.DUR_STD))) togetherWith
                    (androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(com.andmx.ui.theme.Motion.DUR_STD)) { full -> -full * direction } +
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(com.andmx.ui.theme.Motion.DUR_FAST)))
            },
            label = "settingsTab",
        ) { target ->
            when (target) {
                0 -> ProviderTab(
                    current = current,
                    providers = providers,
                    activeProvider = activeProvider,
                    onSaveProvider = onSaveProvider,
                    onAddBlankProvider = onAddBlankProvider,
                    onDeleteProvider = onDeleteProvider,
                    onSelectProvider = onSelectProvider,
                    onSelectModel = { modelId ->
                        onSavePreferences(current.copy(model = modelId))
                    },
                )
                1 -> PreferencesTab(current = current, onSave = onSavePreferences, onDismiss = onDismiss)
            }
        }
    }
}

// ── Tab 1: provider connection ─────────────────────────────────────────────

@Composable
private fun ProviderTab(
    current: ProviderSettings,
    providers: List<ProviderDefinition>,
    activeProvider: ProviderDefinition?,
    onSaveProvider: (ProviderDefinition, makePrimary: Boolean) -> Unit,
    onAddBlankProvider: () -> Unit,
    onDeleteProvider: (String) -> Unit,
    onSelectProvider: (String) -> Unit,
    onSelectModel: (String) -> Unit,
) {
    val colors = AndmxTheme.colors

    var editingId by remember { mutableStateOf(activeProvider?.id ?: providers.firstOrNull()?.id ?: "") }
    val editing = providers.firstOrNull { it.id == editingId }

    var pName by remember(editingId) { mutableStateOf(editing?.name.orEmpty()) }
    var pKind by remember(editingId) { mutableStateOf(editing?.kind ?: ProviderKind.OPENAI) }
    var pBaseUrl by remember(editingId) { mutableStateOf(editing?.baseUrl.orEmpty()) }
    var pApiKey by remember(editingId) { mutableStateOf(editing?.apiKey.orEmpty()) }
    var selectedModel by remember { mutableStateOf(current.model) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xxl),
    ) {
        Spacer(Modifier.height(Spacing.md))

        // Provider chips (horizontal scroll is fine for a short list).
        Text("模型供应商", style = AndmxTheme.typography.titleLarge, color = colors.textPrimary)
        Spacer(Modifier.height(Spacing.xs))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            providers.forEach { def ->
                ProviderChip(
                    label = def.name.ifBlank { def.id },
                    selected = def.id == editingId,
                    isPrimary = def.id == activeProvider?.id,
                ) { editingId = def.id }
            }
            IconChip(Icons.Outlined.Add, "新建") { onAddBlankProvider() }
        }
        Spacer(Modifier.height(Spacing.lg))

        Field("名称", pName, onChange = { pName = it })
        Spacer(Modifier.height(Spacing.sm))

        Text("协议类型", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary)
        Spacer(Modifier.height(Spacing.xs))
        Row {
            listOf(
                ProviderKind.OPENAI to "OpenAI",
                ProviderKind.OPENAI_RESPONSES to "Responses",
                ProviderKind.ANTHROPIC to "Anthropic",
            ).forEach { (kind, label) ->
                SegChip(label, pKind == kind) { pKind = kind }
                Spacer(Modifier.width(Spacing.xs))
            }
        }
        Spacer(Modifier.height(Spacing.sm))

        Field("Base URL", pBaseUrl, onChange = { pBaseUrl = it })
        Spacer(Modifier.height(Spacing.sm))
        Field("API Key", pApiKey, onChange = { pApiKey = it }, password = true)
        Spacer(Modifier.height(Spacing.sm))

        // Model picker: dropdown when the provider has a catalogue, free text otherwise.
        val modelOptions = editing?.models?.keys?.toList() ?: emptyList()
        Text("模型", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary)
        Spacer(Modifier.height(Spacing.xs))
        if (modelOptions.isNotEmpty()) {
            ModelDropdown(selectedModel, modelOptions) { id ->
                selectedModel = id
                onSelectModel(id)
            }
        } else {
            Field("模型 ID（手输）", selectedModel, onChange = {
                selectedModel = it
                onSelectModel(it)
            })
        }
        Spacer(Modifier.height(Spacing.sm))

        // Reasoning selector — driven by the selected model's capability.
        val modelReasoning = editing?.models?.get(selectedModel)?.reasoning
        reasoningSection(modelReasoning, current.reasoningEffort) { /* surfaced in preferences tab */ }
        Spacer(Modifier.height(Spacing.lg))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            ActionText("保存供应商", colors.sendActive, colors.onAccent, Modifier.weight(1f)) {
                val saved = (editing ?: ProviderDefinition(
                    id = java.util.UUID.randomUUID().toString(),
                    name = pName.ifBlank { "供应商" },
                    kind = pKind,
                    baseUrl = pBaseUrl,
                )).copy(
                    name = pName.ifBlank { "供应商" },
                    kind = pKind,
                    baseUrl = pBaseUrl.trim(),
                    apiKey = pApiKey.trim(),
                )
                onSaveProvider(saved, editing?.id != activeProvider?.id || activeProvider == null)
            }
            if (editing != null) {
                ActionText(
                    "删除", colors.surface, colors.warning,
                    Modifier.weight(1f), leading = Icons.Outlined.Delete,
                ) { onDeleteProvider(editing.id) }
            }
        }
    }
}

// ── Tab 2: user preferences ────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PreferencesTab(
    current: ProviderSettings,
    onSave: (ProviderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AndmxTheme.colors

    var instructions by remember { mutableStateOf(current.customInstructions) }
    var themeMode by remember { mutableStateOf(current.themeMode) }
    var accent by remember { mutableStateOf(current.accent) }
    var persona by remember { mutableStateOf(current.persona) }
    var reasoning by remember { mutableStateOf(current.reasoningEffort) }
    var mcp by remember { mutableStateOf(current.mcpServers) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xxl),
    ) {
        Spacer(Modifier.height(Spacing.md))

        Field("自定义指令(附加到系统提示)", instructions, onChange = { instructions = it }, singleLine = false)
        Spacer(Modifier.height(Spacing.sm))
        Field("MCP 服务器(每行 名称|启动命令)", mcp, onChange = { mcp = it }, singleLine = false)
        Spacer(Modifier.height(Spacing.lg))

        Text("外观", style = AndmxTheme.typography.titleSmall, color = colors.textSecondary)
        Spacer(Modifier.height(Spacing.sm))
        Row {
            listOf("system" to "系统", "light" to "浅色", "dark" to "深色").forEach { (v, label) ->
                SegChip(label, themeMode == v) { themeMode = v }
                Spacer(Modifier.width(Spacing.xs))
            }
        }
        Spacer(Modifier.height(Spacing.md))
        Text("强调色", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary)
        Spacer(Modifier.height(Spacing.xs))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            listOf("#339CFF", "#E8730E", "#2EA043", "#AD7FA8", "#F85149", "#1A1A1F").forEach { hex ->
                Swatch(hex, accent.equals(hex, true)) { accent = hex }
            }
        }
        Spacer(Modifier.height(Spacing.md))
        Text("语气", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary)
        Spacer(Modifier.height(Spacing.xs))
        Row {
            listOf("务实", "友好", "简洁", "幽默").forEach { p ->
                SegChip(p, persona == p) { persona = p }
                Spacer(Modifier.width(Spacing.xs))
            }
        }
        Spacer(Modifier.height(Spacing.lg))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ActionText("保存偏好", colors.sendActive, colors.onAccent) {
                onSave(
                    current.copy(
                        customInstructions = instructions.trim(),
                        themeMode = themeMode,
                        accent = accent,
                        persona = persona,
                        reasoningEffort = reasoning,
                        mcpServers = mcp.trim(),
                    ),
                )
                onDismiss()
            }
        }
    }
}

// ── Reasoning section (renders only when the model exposes reasoning) ───────

@Composable
private fun reasoningSection(reasoning: ReasoningConfig?, current: String, onSelect: (String) -> Unit) {
    if (reasoning == null || reasoning.style == ReasoningStyle.NONE) return
    val colors = AndmxTheme.colors
    Text(
        when (reasoning.style) {
            ReasoningStyle.EFFORT -> "推理强度 (reasoning_effort)"
            ReasoningStyle.THINKING -> "扩展思考 (extended thinking)"
            ReasoningStyle.NONE -> return
        },
        style = AndmxTheme.typography.labelMedium, color = colors.textSecondary,
    )
    Spacer(Modifier.height(Spacing.xs))
    Row {
        SegChip("关", current == "off" || current.isBlank()) { onSelect("off") }
        Spacer(Modifier.width(Spacing.xs))
        when (reasoning.style) {
            ReasoningStyle.EFFORT -> reasoning.effortLevels.forEach { level ->
                SegChip(level, current == level) { onSelect(level) }
                Spacer(Modifier.width(Spacing.xs))
            }
            ReasoningStyle.THINKING -> SegChip("开", current != "off" && current.isNotBlank()) { onSelect("enabled") }
            ReasoningStyle.NONE -> {}
        }
    }
    if (reasoning.style == ReasoningStyle.THINKING) {
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "默认预算 ${reasoning.defaultBudgetTokens} tokens（可在请求里用数字覆盖）",
            style = AndmxTheme.typography.labelSmall, color = colors.textTertiary,
        )
    }
}

// ── Reusable controls ──────────────────────────────────────────────────────

@Composable
private fun ModelDropdown(selected: String, options: List<String>, onSelect: (String) -> Unit) {
    val colors = AndmxTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(Radii.sm)
                .border(1.dp, colors.border, Radii.sm)
                .background(colors.surface, Radii.sm)
                .clickable { expanded = true }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selected.ifBlank { "选择模型" },
                style = AndmxTheme.typography.bodyMedium,
                color = if (selected.isBlank()) colors.textTertiary else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { id ->
                DropdownMenuItem(
                    text = { Text(id, style = AndmxTheme.typography.bodyMedium) },
                    onClick = { expanded = false; onSelect(id) },
                )
            }
        }
    }
}

@Composable
private fun ProviderChip(label: String, selected: Boolean, isPrimary: Boolean, onClick: () -> Unit) =
    SelectableChip(label = label + if (isPrimary) " ·" else "", selected = selected, onClick = onClick)

@Composable
private fun IconChip(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    val colors = AndmxTheme.colors
    Row(
        Modifier.clip(Radii.sm)
            .border(1.dp, colors.border, Radii.sm)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ActionText(
    label: String,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .clip(Radii.sm)
            .background(background, Radii.sm)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = foreground, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(label, style = AndmxTheme.typography.labelLarge, color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, password: Boolean = false, singleLine: Boolean = true) =
    FormField(label = label, value = value, onChange = onChange, password = password, singleLine = singleLine)

@Composable
private fun SegChip(label: String, selected: Boolean, onClick: () -> Unit) =
    SelectableChip(label = label, selected = selected, onClick = onClick)

@Composable
private fun Swatch(hex: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AndmxTheme.colors
    val c = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(colors.accent)
    val bd by androidx.compose.animation.animateColorAsState(if (selected) colors.textPrimary else colors.border, label = "swatchBd")
    Box(
        Modifier.size(26.dp).clip(Radii.pill).background(c)
            .border(2.dp, bd, Radii.pill)
            .clickable(onClick = onClick),
    )
}
