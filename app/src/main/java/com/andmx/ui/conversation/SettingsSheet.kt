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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.runtime.LaunchedEffect
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
    onFetchModels: (ProviderDefinition) -> Unit,
    fetchingModels: Boolean,
    fetchModelsError: String?,
    fetchedModels: List<String>,
    onAddModel: (providerId: String, modelId: String) -> Unit,
    onRemoveModel: (providerId: String, modelId: String) -> Unit,
    onTestConnection: (ProviderDefinition, String) -> Unit,
    testingConnection: Boolean,
    connectionOk: Boolean?,
    connectionResult: String?,
    // Memory management (Codex-style: configurable + viewable).
    memoryEnabled: Boolean,
    onMemoryEnabledChange: (Boolean) -> Unit,
    memoryContent: String,
    onClearMemory: () -> Unit,
    onConsolidateMemory: () -> Unit,
    /** Initial tab: 0 = 模型, 1 = 通用. */
    initialTab: Int = 0,
) {
    val colors = AndmxTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableIntStateOf(initialTab) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.canvas) {
        TabRow(
            selectedTabIndex = tab,
            containerColor = colors.canvas,
            contentColor = colors.accent,
        ) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("模型") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("通用") })
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
                    onFetchModels = onFetchModels,
                    fetchingModels = fetchingModels,
                    fetchModelsError = fetchModelsError,
                    fetchedModels = fetchedModels,
                    onAddModel = onAddModel,
                    onRemoveModel = onRemoveModel,
                    onTestConnection = onTestConnection,
                    testingConnection = testingConnection,
                    connectionOk = connectionOk,
                    connectionResult = connectionResult,
                )
                1 -> PreferencesTab(
                    current = current,
                    onSave = onSavePreferences,
                    onDismiss = onDismiss,
                    memoryEnabled = memoryEnabled,
                    onMemoryEnabledChange = onMemoryEnabledChange,
                    memoryContent = memoryContent,
                    onClearMemory = onClearMemory,
                    onConsolidateMemory = onConsolidateMemory,
                )
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
    onFetchModels: (ProviderDefinition) -> Unit,
    fetchingModels: Boolean,
    fetchModelsError: String?,
    fetchedModels: List<String>,
    onAddModel: (providerId: String, modelId: String) -> Unit,
    onRemoveModel: (providerId: String, modelId: String) -> Unit,
    onTestConnection: (ProviderDefinition, String) -> Unit,
    testingConnection: Boolean,
    connectionOk: Boolean?,
    connectionResult: String?,
) {
    val colors = AndmxTheme.colors

    var editingId by remember { mutableStateOf(activeProvider?.id ?: providers.firstOrNull()?.id ?: "") }
    val editing = providers.firstOrNull { it.id == editingId }

    var pName by remember(editingId) { mutableStateOf(editing?.name.orEmpty()) }
    var pKind by remember(editingId) { mutableStateOf(editing?.kind ?: ProviderKind.OPENAI) }
    var pBaseUrl by remember(editingId) { mutableStateOf(editing?.baseUrl.orEmpty()) }
    var pApiKey by remember(editingId) { mutableStateOf(editing?.apiKey.orEmpty()) }
    var selectedModel by remember { mutableStateOf(current.model) }

    // Track which provider a fetch was triggered for, so the fetching/error
    // status is only surfaced for the provider being edited.
    var fetchedForId by remember { mutableStateOf<String?>(null) }

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
                    label = def.name.ifBlank { "未命名" },
                    selected = def.id == editingId,
                    isPrimary = def.id == activeProvider?.id,
                ) { editingId = def.id }
            }
            IconChip(Icons.Outlined.Add, "新建") { onAddBlankProvider() }
        }
        Spacer(Modifier.height(Spacing.lg))

        Field("名称", pName, onChange = { pName = it }, placeholder = "为该供应商命名")
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

        // Model management: list of models already added to this provider,
        // each removable; an inline "add model" input; and a batch "fetch from
        // API" affordance. The selected model is highlighted.
        Text("模型", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary)
        Spacer(Modifier.height(Spacing.xs))
        ModelListManager(
            providerId = editingId,
            models = (editing?.models?.keys?.toList() ?: emptyList()),
            selectedModel = selectedModel,
            fetching = fetchingModels && fetchedForId == editingId,
            fetchError = if (fetchedForId == editingId) fetchModelsError else null,
            onSelect = { id ->
                selectedModel = id
                onSelectModel(id)
            },
            onAdd = { id -> onAddModel(editingId, id) },
            onRemove = { id -> onRemoveModel(editingId, id) },
            onFetch = {
                fetchedForId = editingId
                onFetchModels(
                    (editing ?: ProviderDefinition(
                        id = editingId,
                        name = pName,
                        kind = pKind,
                        baseUrl = pBaseUrl,
                    )).copy(
                        name = pName,
                        kind = pKind,
                        baseUrl = pBaseUrl.trim(),
                        apiKey = pApiKey.trim(),
                    )
                )
            },
        )
        Spacer(Modifier.height(Spacing.sm))

        // Test connection: sends a minimal chat request through a fresh client
        // bound to the form's current URL/key + the selected model, verifying
        // the full chat path works end-to-end. Requires a model to be selected.
        ConnectionTestRow(
            testing = testingConnection,
            ok = connectionOk,
            result = connectionResult,
            enabled = !testingConnection && editingId.isNotBlank() && selectedModel.isNotBlank(),
            onTest = {
                onTestConnection(
                    (editing ?: ProviderDefinition(
                        id = editingId,
                        name = pName,
                        kind = pKind,
                        baseUrl = pBaseUrl,
                    )).copy(
                        name = pName,
                        kind = pKind,
                        baseUrl = pBaseUrl.trim(),
                        apiKey = pApiKey.trim(),
                    ),
                    selectedModel,
                )
            },
        )
        Spacer(Modifier.height(Spacing.sm))

        // Reasoning selector — driven by the selected model's capability.
        val modelReasoning = editing?.models?.get(selectedModel)?.reasoning
        reasoningSection(modelReasoning, current.reasoningEffort) { /* surfaced in preferences tab */ }
        Spacer(Modifier.height(Spacing.lg))

        // Transient "saved" confirmation so the user knows the save landed.
        var savedTick by remember { mutableStateOf(false) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            ActionText("保存供应商", colors.sendActive, colors.onAccent, Modifier.weight(1f)) {
                val saved = (editing ?: ProviderDefinition(
                    id = java.util.UUID.randomUUID().toString(),
                    name = pName,
                    kind = pKind,
                    baseUrl = pBaseUrl,
                )).copy(
                    name = pName,
                    kind = pKind,
                    baseUrl = pBaseUrl.trim(),
                    apiKey = pApiKey.trim(),
                )
                onSaveProvider(saved, editing?.id != activeProvider?.id || activeProvider == null)
                savedTick = true
            }
            if (editing != null) {
                ActionText(
                    "删除", colors.surface, colors.warning,
                    Modifier.weight(1f), leading = Icons.Outlined.Delete,
                ) { onDeleteProvider(editing.id) }
            }
        }
        if (savedTick) {
            Spacer(Modifier.height(Spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text("已保存", style = AndmxTheme.typography.labelSmall, color = colors.textSecondary)
            }
            LaunchedEffect(savedTick) {
                kotlinx.coroutines.delay(1500)
                savedTick = false
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
    memoryEnabled: Boolean = false,
    onMemoryEnabledChange: (Boolean) -> Unit = {},
    memoryContent: String = "",
    onClearMemory: () -> Unit = {},
    onConsolidateMemory: () -> Unit = {},
) {
    val colors = AndmxTheme.colors
    var memEnabled by remember { mutableStateOf(memoryEnabled) }

    var instructions by remember { mutableStateOf(current.customInstructions) }
    var themeMode by remember { mutableStateOf(current.themeMode) }
    var accent by remember { mutableStateOf(current.accent) }
    var persona by remember { mutableStateOf(current.persona) }
    var reasoning by remember { mutableStateOf(current.reasoningEffort) }
    var approvalMode by remember { mutableStateOf(current.approvalMode) }
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

        // ── 外观 (Appearance) ──
        SettingsSection("外观", "使用浅色、深色或跟随系统")
        Text("主题", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary)
        Spacer(Modifier.height(Spacing.xs))
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
        Spacer(Modifier.height(Spacing.lg))

        // ── 个性化 (Personalization) ──
        SettingsSection("个性化", "为所有任务设定默认行为")
        Text("人格", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary)
        Spacer(Modifier.height(Spacing.xs))
        Row {
            listOf("务实" to "务实", "友好" to "友好", "简洁" to "简洁", "幽默" to "幽默").forEach { (v, label) ->
                SegChip(label, persona == v) { persona = v }
                Spacer(Modifier.width(Spacing.xs))
            }
        }
        Spacer(Modifier.height(Spacing.md))
        Text("自定义指令 (AGENTS.md)", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary)
        Spacer(Modifier.height(Spacing.xs))
        Field("", instructions, onChange = { instructions = it }, singleLine = false)
        Spacer(Modifier.height(Spacing.md))
        Text("推理强度", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary)
        Spacer(Modifier.height(Spacing.xs))
        Row {
            listOf("off" to "关闭", "low" to "低", "medium" to "中", "high" to "高").forEach { (v, label) ->
                SegChip(label, reasoning == v) { reasoning = v }
                Spacer(Modifier.width(Spacing.xs))
            }
        }
        Spacer(Modifier.height(Spacing.lg))

        // ── Agent (审批策略) ──
        SettingsSection("Agent", "控制工具执行和授权策略")
        Text("审批策略", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary)
        Spacer(Modifier.height(Spacing.xs))
        Text("选择 Agent 执行工具时的授权方式", style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
        Spacer(Modifier.height(Spacing.xs))
        Row {
            listOf("ask" to "询问", "auto" to "自动", "yolo" to "全自动").forEach { (v, label) ->
                SegChip(label, approvalMode == v) { approvalMode = v }
                Spacer(Modifier.width(Spacing.xs))
            }
        }
        Spacer(Modifier.height(Spacing.lg))

        // ── 记忆 (Memory) ──
        SettingsSection("记忆 (实验性)", "配置 Codex 如何收集、保留和整合记忆")
        Row(verticalAlignment = Alignment.CenterVertically) {
            SegChip(if (memEnabled) "已启用" else "已关闭", memEnabled) {
                memEnabled = !memEnabled
                onMemoryEnabledChange(memEnabled)
            }
            Spacer(Modifier.width(Spacing.xs))
            Text(
                "自动从对话中提取记忆并注入后续会话",
                style = AndmxTheme.typography.labelSmall, color = colors.textTertiary,
            )
        }
        if (memoryContent.isNotBlank()) {
            Spacer(Modifier.height(Spacing.sm))
            Box(
                Modifier.fillMaxWidth().heightIn(max = 160.dp)
                    .clip(Radii.sm).border(1.dp, colors.border, Radii.sm)
                    .background(colors.sunken, Radii.sm)
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.md),
            ) {
                Text(memoryContent, style = AndmxTheme.typography.bodySmall, color = colors.textSecondary)
            }
            Spacer(Modifier.height(Spacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ActionText("整理记忆", colors.sunken, colors.textPrimary) { onConsolidateMemory() }
                ActionText("清除全部", colors.surface, colors.warning) { onClearMemory() }
            }
        }
        Spacer(Modifier.height(Spacing.lg))

        // ── MCP 服务器 ──
        SettingsSection("MCP 服务器", "添加外部工具服务器，每行一个：名称|启动命令")
        Field("", mcp, onChange = { mcp = it }, singleLine = false)
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
                        approvalMode = approvalMode,
                        mcpServers = mcp.trim(),
                    ),
                )
                onDismiss()
            }
        }
    }
}

// ── Settings section header (Codex-style title + description) ──────────────

@Composable
private fun SettingsSection(title: String, description: String) {
    val colors = AndmxTheme.colors
    Text(title, style = AndmxTheme.typography.titleSmall, color = colors.textPrimary)
    Spacer(Modifier.height(Spacing.xxs))
    Text(description, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
    Spacer(Modifier.height(Spacing.sm))
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

/**
 * "Test connection" row: sends a minimal one-shot chat request ("ping") through
 * the provider with the selected model to verify the full chat path works.
 * Success/failure is conveyed through text tone, not color blocks — keeping
 * with the design system's restrained palette (only `warning` carries semantic
 * meaning).
 */
@Composable
private fun ConnectionTestRow(
    testing: Boolean,
    ok: Boolean?,
    result: String?,
    enabled: Boolean,
    onTest: () -> Unit,
) {
    val colors = AndmxTheme.colors
    Column {
        Text(
            if (testing) "测试中…" else "测试连接",
            style = AndmxTheme.typography.labelMedium,
            color = if (enabled || testing) colors.textPrimary else colors.textTertiary,
            modifier = Modifier
                .clip(Radii.sm)
                .clickable(enabled = enabled) { onTest() }
                .padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
        )
        if (result != null && !testing) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                result,
                style = AndmxTheme.typography.labelSmall,
                color = if (ok == true) colors.textSecondary else colors.warning,
            )
        }
    }
}

// ── Reusable controls ──────────────────────────────────────────────────────

/**
 * Manages the list of models already added to a provider.
 *
 * Renders each model as a selectable row (highlighting the active one) with a
 * remove affordance, an inline "add model" input, and a batch "fetch from API"
 * button that pulls `GET {base}/models` and appends the result. This is the
 * model catalogue editor — the composer's quick switcher reads whatever ends
 * up in [models].
 */
@Composable
private fun ModelListManager(
    providerId: String,
    models: List<String>,
    selectedModel: String,
    fetching: Boolean,
    fetchError: String?,
    onSelect: (String) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onFetch: () -> Unit,
) {
    val colors = AndmxTheme.colors
    var newModel by remember(providerId) { mutableStateOf("") }
    var query by remember(providerId) { mutableStateOf("") }
    val filtered = if (query.isBlank()) models else models.filter { it.contains(query, ignoreCase = true) }

    Column(Modifier.fillMaxWidth().clip(Radii.sm).border(1.dp, colors.border, Radii.sm)) {
        // Search box at the top — filters the model list.
        Box(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = AndmxTheme.typography.bodyMedium.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text("搜索模型…", style = AndmxTheme.typography.bodyMedium, color = colors.textTertiary)
                    }
                    inner()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radii.sm)
                    .border(1.dp, colors.border, Radii.sm)
                    .background(colors.surface, Radii.sm)
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            )
        }

        // Scrollable model list — capped so hundreds of models don't blow up.
        Column(
            Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
        ) {
            if (filtered.isNotEmpty()) {
                filtered.forEachIndexed { index, id ->
                    val isSelected = id == selectedModel
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isSelected) colors.accentSoft else Color.Transparent)
                            .clickable { onSelect(id) }
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    ) {
                        Text(
                            id,
                            style = AndmxTheme.typography.bodyMedium,
                            color = if (isSelected) colors.textPrimary else colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "移除模型",
                            tint = colors.textTertiary,
                            modifier = Modifier
                                .size(16.dp)
                                .clip(Radii.sm)
                                .clickable { onRemove(id) }
                                .padding(Spacing.xxs),
                        )
                    }
                    if (index < filtered.lastIndex) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
                    }
                }
            } else {
                Text(
                    if (models.isEmpty()) "该供应商还没有添加模型"
                    else "无匹配模型",
                    style = AndmxTheme.typography.labelSmall, color = colors.textTertiary,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md),
                )
            }
        }

        // Inline add-model input.
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
        ) {
            BasicTextField(
                value = newModel,
                onValueChange = { newModel = it },
                singleLine = true,
                textStyle = AndmxTheme.typography.bodyMedium.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                decorationBox = { inner ->
                    if (newModel.isEmpty()) {
                        Text("输入模型 ID 添加", style = AndmxTheme.typography.bodyMedium, color = colors.textTertiary)
                    }
                    inner()
                },
                modifier = Modifier
                    .weight(1f)
                    .clip(Radii.sm)
                    .border(1.dp, colors.border, Radii.sm)
                    .background(colors.surface, Radii.sm)
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            )
            Spacer(Modifier.width(Spacing.xs))
            ActionText("添加", colors.sunken, colors.textPrimary) {
                if (newModel.isNotBlank()) {
                    onAdd(newModel.trim())
                    newModel = ""
                }
            }
        }

        // Batch fetch from API.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !fetching) { onFetch() }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        ) {
            Text(
                if (fetching) "获取中…" else "从 API 获取模型列表",
                style = AndmxTheme.typography.labelMedium,
                color = if (fetching) colors.textTertiary else colors.textSecondary,
            )
            if (fetchError != null) {
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    fetchError, style = AndmxTheme.typography.labelSmall, color = colors.warning,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
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
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    password: Boolean = false,
    singleLine: Boolean = true,
    placeholder: String? = null,
) =
    FormField(label = label, value = value, onChange = onChange, password = password, singleLine = singleLine, placeholder = placeholder)

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
