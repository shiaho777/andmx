package com.andmx.ui2.settings.pages

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.llm.ApiMessage
import com.andmx.llm.ChatRequest
import com.andmx.llm.LlmClient
import com.andmx.llm.classifyConnectionFailure
import com.andmx.llm.connectionFailureText
import com.andmx.llm.provider.ClaudeModelMapping
import com.andmx.llm.provider.ModelDefinition
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.provider.ProviderKind
import com.andmx.llm.provider.ReasoningConfig
import com.andmx.llm.provider.ReasoningStyle
import com.andmx.llm.provider.claudeMappingWithSlot
import com.andmx.llm.provider.claudeSlotLabel
import com.andmx.llm.provider.claudeSlotValue
import com.andmx.llm.provider.normalizeClaudeMapping
import com.andmx.llm.wire.AdapterFactory
import com.andmx.llm.wire.AnthropicMessagesAdapter
import com.andmx.ui2.chat.effortLabel
import com.andmx.ui2.settings.SegmentedRow
import com.andmx.ui2.settings.SettingsGroup
import com.andmx.ui2.settings.StackedSettingRow
import com.andmx.ui2.settings.rememberClearFocusScrollConnection
import com.andmx.ui2.settings.clearFocusOnScroll
import com.andmx.ui2.settings.clearFocusOnBlankTap
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import kotlinx.coroutines.launch
import java.util.UUID

private sealed class TestState {
    data object Idle : TestState()
    data object Testing : TestState()
    data object Success : TestState()
    data class Failed(val reason: String) : TestState()
}

private sealed class FetchState {
    data object Idle : FetchState()
    data object Loading : FetchState()
    data class Ready(val models: List<String>) : FetchState()
    data class Failed(val reason: String) : FetchState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditPage(
    initial: ProviderDefinition?,
    onBack: () -> Unit,
    onSave: (ProviderDefinition) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var kind by remember { mutableStateOf(initial?.kind ?: ProviderKind.OPENAI) }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var apiKey by remember { mutableStateOf(initial?.apiKey ?: "") }
    var showKey by remember { mutableStateOf(false) }
    var selectedModels by remember {
        mutableStateOf(initial?.models?.keys?.toList()?.sorted() ?: emptyList())
    }
    var modelMeta by remember {
        mutableStateOf(initial?.models ?: emptyMap())
    }
    var manualModel by remember { mutableStateOf("") }
    var modelQuery by remember { mutableStateOf("") }
    var fetchState by remember { mutableStateOf<FetchState>(FetchState.Idle) }
    var testState by remember { mutableStateOf<TestState>(TestState.Idle) }
    var claudeMapping by remember { mutableStateOf(initial?.claudeMapping) }
    var showAddModelDialog by remember { mutableStateOf(false) }

    fun build(): ProviderDefinition {
        val modelMap = selectedModels
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .associateWith { id ->
                modelMeta[id] ?: initial?.models?.get(id) ?: ModelDefinition()
            }
        return ProviderDefinition(
            id = initial?.id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            kind = kind,
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            enabled = initial?.enabled ?: true,
            httpHeaders = initial?.httpHeaders ?: emptyMap(),
            models = modelMap,
            claudeMapping = normalizeClaudeMapping(claudeMapping),
        )
    }

    fun toggleModel(id: String) {
        selectedModels = if (id in selectedModels) {
            modelMeta = modelMeta - id
            selectedModels - id
        } else {
            if (id !in modelMeta) {
                modelMeta = modelMeta + (id to (initial?.models?.get(id) ?: ModelDefinition(contextWindow = 128_000)))
            }
            (selectedModels + id).sorted()
        }
    }

    fun addManualModel() {
        val id = manualModel.trim()
        if (id.isBlank()) return
        if (id !in selectedModels) {
            selectedModels = (selectedModels + id).sorted()
        }
        if (id !in modelMeta) {
            modelMeta = modelMeta + (id to ModelDefinition(contextWindow = 128_000))
        }
        manualModel = ""
    }

    fun removeModel(id: String) {
        selectedModels = selectedModels.filterNot { it == id }
        modelMeta = modelMeta - id
    }

    fun patchModel(id: String, transform: (ModelDefinition) -> ModelDefinition) {
        val prev = modelMeta[id] ?: initial?.models?.get(id) ?: ModelDefinition()
        modelMeta = modelMeta + (id to transform(prev))
    }

    fun fetchModels() {
        if (baseUrl.isBlank()) {
            fetchState = FetchState.Failed("请先填写 Base URL")
            return
        }
        fetchState = FetchState.Loading
        scope.launch {
            val def = build()
            val result = runCatching {
                AdapterFactory.forKind(def.kind).listModels(def)
            }
            fetchState = result.fold(
                onSuccess = { ids ->
                    val sorted = ids.map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted()
                    if (sorted.isEmpty()) {
                        FetchState.Failed("未能获取模型列表（请检查 URL/Key，或端点是否支持 /models）")
                    } else {
                        FetchState.Ready(sorted)
                    }
                },
                onFailure = { t ->
                    FetchState.Failed(t.message?.take(120) ?: "获取失败")
                }
            )
        }
    }

    val metadataProblem = selectedModels.firstNotNullOfOrNull { id ->
        val def = modelMeta[id] ?: initial?.models?.get(id) ?: ModelDefinition()
        validateModelMetadata(
            ModelMetadataDraft(
                id = id,
                contextWindowText = metadataTokenText(def.contextWindow),
                maxOutputTokensText = metadataTokenText(def.maxOutputTokens),
                inputModalities = def.inputModalities,
            )
        )
    }
    val canSave = name.isNotBlank() && baseUrl.isNotBlank() && metadataProblem == null
    val catalogue = when (val fs = fetchState) {
        is FetchState.Ready -> fs.models
        else -> emptyList()
    }
    val filteredCatalogue = if (modelQuery.isBlank()) {
        catalogue
    } else {
        catalogue.filter { it.contains(modelQuery, ignoreCase = true) }
    }
    val filteredSelected = if (modelQuery.isBlank()) {
        selectedModels
    } else {
        selectedModels.filter { it.contains(modelQuery, ignoreCase = true) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "添加供应商" else "编辑供应商") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { onSave(build()) }, enabled = canSave) {
                        Icon(Icons.Outlined.Check, "保存")
                    }
                }
            )
        }
    ) { padding ->
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val scrollState = rememberScrollState()
        val clearFocusScroll = rememberClearFocusScrollConnection()
        LaunchedEffect(scrollState.isScrollInProgress) {
            if (scrollState.isScrollInProgress) {
                focusManager.clearFocus(force = true)
                keyboard?.hide()
            }
        }
        val doneActions = KeyboardActions(
            onDone = {
                focusManager.clearFocus(force = true)
                keyboard?.hide()
            },
        )
        val doneOptions = KeyboardOptions(imeAction = ImeAction.Done)
        Column(
            Modifier
                .padding(padding)
                .clearFocusOnScroll(clearFocusScroll)
                .clearFocusOnBlankTap()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            SettingsGroup("基本信息") {
                StackedSettingRow(title = "名称", description = "在聊天时用于识别该供应商") {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("例如 OpenAI / DeepSeek") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = doneOptions,
                        keyboardActions = doneActions,
                    )
                }
                StackedSettingRow(
                    title = "协议类型",
                    description = "决定请求路径与鉴权方式"
                ) {
                    SegmentedRow(
                        options = listOf(
                            ProviderKind.OPENAI.name to "OpenAI",
                            ProviderKind.OPENAI_RESPONSES.name to "Responses",
                            ProviderKind.ANTHROPIC.name to "Anthropic",
                        ),
                        selected = kind.name,
                        onSelect = { kind = ProviderKind.valueOf(it) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            SettingsGroup("连接") {
                StackedSettingRow(title = "Base URL") {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        placeholder = { Text("https://api.example.com/v1") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = doneOptions,
                        keyboardActions = doneActions,
                    )
                }
                StackedSettingRow(
                    title = "API Key",
                    description = "设置 API Key 后即可启用。"
                ) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        placeholder = { Text("输入 API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = doneOptions,
                        keyboardActions = doneActions,
                        visualTransformation = if (showKey) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showKey = !showKey }) {
                                Text(if (showKey) "隐藏" else "显示")
                            }
                        }
                    )
                }
            }

            SettingsGroup("模型列表") {
                StackedSettingRow(
                    title = "从 API 获取",
                    description = "拉取供应商 /models 目录，搜索后勾选即可添加，无需手填 ID"
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { fetchModels() },
                            enabled = fetchState !is FetchState.Loading && baseUrl.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (fetchState is FetchState.Loading) {
                                CircularProgressIndicator(
                                    Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("获取中…")
                            } else {
                                Icon(
                                    Icons.Outlined.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(if (catalogue.isEmpty()) "获取模型列表" else "重新获取")
                            }
                        }
                        if (catalogue.isNotEmpty()) {
                            Text(
                                "${catalogue.size} 个",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    when (val fs = fetchState) {
                        is FetchState.Failed -> Text(
                            fs.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        is FetchState.Ready -> Text(
                            "已加载 ${fs.models.size} 个模型，可搜索并勾选添加",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        else -> {}
                    }
                }

                if (catalogue.isNotEmpty() || selectedModels.isNotEmpty()) {
                    StackedSettingRow(title = "搜索过滤") {
                        OutlinedTextField(
                            value = modelQuery,
                            onValueChange = { modelQuery = it },
                            placeholder = { Text("按模型 ID 过滤…") },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailingIcon = {
                                if (modelQuery.isNotEmpty()) {
                                    IconButton(onClick = { modelQuery = "" }) {
                                        Icon(Icons.Outlined.Close, "清除")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }

                if (catalogue.isNotEmpty()) {
                    StackedSettingRow(
                        title = "可选模型",
                        description = if (modelQuery.isBlank()) {
                            "勾选后加入下方已选列表"
                        } else {
                            "匹配 ${filteredCatalogue.size} / ${catalogue.size}"
                        }
                    ) {
                        ModelPickList(
                            ids = filteredCatalogue,
                            selected = selectedModels.toSet(),
                            emptyText = if (modelQuery.isBlank()) "暂无模型" else "无匹配模型",
                            onToggle = { toggleModel(it) },
                            maxHeight = 280.dp
                        )
                        if (filteredCatalogue.isNotEmpty()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = {
                                        val toAdd = filteredCatalogue.filter { it !in selectedModels }
                                        if (toAdd.isNotEmpty()) {
                                            val seeded = toAdd.associateWith { mid ->
                                                modelMeta[mid] ?: ModelDefinition(contextWindow = 128_000)
                                            }
                                            modelMeta = modelMeta + seeded
                                            selectedModels = (selectedModels + toAdd).sorted()
                                        }
                                    }
                                ) {
                                    Text("全选当前结果")
                                }
                                TextButton(
                                    onClick = {
                                        val drop = selectedModels.filter { it in filteredCatalogue }.toSet()
                                        selectedModels = selectedModels.filterNot { it in drop }
                                        modelMeta = modelMeta.filterKeys { it !in drop }
                                    }
                                ) {
                                    Text("取消当前结果")
                                }
                            }
                        }
                    }
                }

                StackedSettingRow(
                    title = "已选模型",
                    description = if (selectedModels.isEmpty()) {
                        "聊天时将从这里选择可用模型；可为每个模型设置上下文长度"
                    } else {
                        "已选 ${selectedModels.size} 个 · 点开可设置上下文长度"
                    }
                ) {
                    if (filteredSelected.isEmpty()) {
                        Text(
                            if (selectedModels.isEmpty()) "尚未添加模型，可拉取列表勾选，或手动输入 ID"
                            else "无匹配已选模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        SelectedModelsEditor(
                            ids = filteredSelected,
                            meta = modelMeta,
                            onRemove = { removeModel(it) },
                            onPatch = { id, transform -> patchModel(id, transform) },
                        )
                    }
                    metadataProblem?.let { problem ->
                        Text(
                            modelMetadataProblemText(problem),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { showAddModelDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("添加模型")
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = manualModel,
                            onValueChange = { manualModel = it },
                            placeholder = { Text("手动输入模型 ID") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedButton(
                            onClick = { addManualModel() },
                            enabled = manualModel.isNotBlank()
                        ) {
                            Text("快速添加")
                        }
                    }
                }
            }

            SettingsGroup("Claude 模型映射") {
                Text(
                    "为 Claude 的各模型槽位选择对应的模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                ClaudeMappingEditor(
                    mapping = claudeMapping,
                    candidates = selectedModels,
                    onPick = { slot, modelId ->
                        claudeMapping = claudeMappingWithSlot(claudeMapping, slot, modelId)
                    },
                )
            }

            SettingsGroup("连接测试") {
                val firstModel = selectedModels.firstOrNull()
                Button(
                    onClick = {
                        val def = build()
                        val model = firstModel ?: return@Button
                        testState = TestState.Testing
                        scope.launch {
                            val result = runCatching {
                                LlmClient(def).chat(
                                    ChatRequest(
                                        model = model,
                                        messages = listOf(ApiMessage(role = "user", content = "ping"))
                                    )
                                )
                            }.getOrElse { Result.failure(it) }
                            testState = if (result.isSuccess) TestState.Success
                            else TestState.Failed(
                                connectionFailureText(
                                    classifyConnectionFailure(result.exceptionOrNull())
                                )
                            )
                        }
                    },
                    enabled = testState != TestState.Testing && baseUrl.isNotBlank()
                        && apiKey.isNotBlank() && firstModel != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (testState == TestState.Testing) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("测试中…")
                    } else {
                        Text("测试连接")
                    }
                }
                when (val ts = testState) {
                    is TestState.Success -> TestResultText("连接成功！", MaterialTheme.colorScheme.primary)
                    is TestState.Failed -> TestResultText("连接失败：${ts.reason}", MaterialTheme.colorScheme.error)
                    else -> {}
                }
            }

            if (onDelete != null) {
                Spacer(Modifier.height(24.dp))
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("删除供应商", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showAddModelDialog) {
        AddModelDialog(
            existingIds = selectedModels.toSet(),
            onDismiss = { showAddModelDialog = false },
            onConfirm = { id, def ->
                if (id !in selectedModels) {
                    selectedModels = (selectedModels + id).sorted()
                }
                modelMeta = modelMeta + (id to def)
                showAddModelDialog = false
            },
        )
    }
}

@Composable
private fun SelectedModelsEditor(
    ids: List<String>,
    meta: Map<String, ModelDefinition>,
    onRemove: (String) -> Unit,
    onPatch: (String, (ModelDefinition) -> ModelDefinition) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val border = MaterialTheme.colorScheme.outlineVariant
    val presets = listOf(
        32_768 to "32K",
        65_536 to "64K",
        128_000 to "128K",
        200_000 to "200K",
        256_000 to "256K",
        1_000_000 to "1M",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, border, shape)
    ) {
        ids.forEachIndexed { index, id ->
            val def = meta[id] ?: ModelDefinition()
            val windowText = if (def.contextWindow > 0) def.contextWindow.toString() else ""
            val maxOutText = if (def.maxOutputTokens > 0) def.maxOutputTokens.toString() else ""
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val alias = def.displayName?.takeIf { it.isNotBlank() }
                    Column(Modifier.weight(1f)) {
                        Text(
                            alias ?: id,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (alias != null) {
                            Text(
                                id,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(onClick = { onRemove(id) }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "移除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Text(
                    "显示名称",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                OutlinedTextField(
                    value = def.displayName.orEmpty(),
                    onValueChange = { raw ->
                        onPatch(id) { it.copy(displayName = raw.ifBlank { null }) }
                    },
                    placeholder = { Text("留空则显示模型 ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text(
                    "上下文长度",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                )
                OutlinedTextField(
                    value = windowText,
                    onValueChange = { raw ->
                        val digits = raw.filter { it.isDigit() }.take(9)
                        val value = digits.toIntOrNull() ?: 0
                        onPatch(id) { it.copy(contextWindow = value) }
                    },
                    placeholder = { Text("默认 128000") },
                    suffix = { Text("tokens", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    presets.forEach { (tokens, label) ->
                        val selected = def.contextWindow == tokens
                        AssistChip(
                            onClick = { onPatch(id) { it.copy(contextWindow = tokens) } },
                            label = {
                                Text(
                                    label,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                            colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                                containerColor = if (selected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            ),
                        )
                    }
                }
                Text(
                    "最大输出",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
                OutlinedTextField(
                    value = maxOutText,
                    onValueChange = { raw ->
                        val digits = raw.filter { it.isDigit() }.take(8)
                        val value = digits.toIntOrNull() ?: 0
                        onPatch(id) { it.copy(maxOutputTokens = value) }
                    },
                    placeholder = { Text("可选，0 表示不限制") },
                    suffix = { Text("tokens", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                )
                Text(
                    "输入类型",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
                val picked = splitInputModalities(def.inputModalities)
                ChipRow(
                    options = MODEL_INPUT_MODALITIES,
                    selected = picked,
                    locked = setOf("text"),
                    onToggle = { value, on ->
                        val updated = if (on) picked + value else picked - value
                        onPatch(id) { it.copy(inputModalities = newModelModalities(updated)) }
                    },
                )
                ReasoningEditor(
                    config = def.reasoning,
                    onChange = { next -> onPatch(id) { it.copy(reasoning = next) } },
                )
            }
            if (index < ids.lastIndex) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(border.copy(alpha = 0.5f)),
                )
            }
        }
    }
}

@Composable
private fun ModelPickList(
    ids: List<String>,
    selected: Set<String>,
    emptyText: String,
    onToggle: (String) -> Unit,
    maxHeight: androidx.compose.ui.unit.Dp,
    showAsSelectedOnly: Boolean = false
) {
    val shape = RoundedCornerShape(12.dp)
    val border = MaterialTheme.colorScheme.outlineVariant
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .clip(shape)
            .border(1.dp, border, shape)
            .verticalScroll(rememberScrollState())
    ) {
        if (ids.isEmpty()) {
            if (emptyText.isNotBlank()) {
                Text(
                    emptyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp)
                )
            }
        } else {
            ids.forEachIndexed { index, id ->
                val isOn = id in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(id) }
                        .background(
                            if (isOn && !showAsSelectedOnly) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            } else {
                                Color.Transparent
                            }
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showAsSelectedOnly) {
                        IconButton(onClick = { onToggle(id) }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "移除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        Checkbox(
                            checked = isOn,
                            onCheckedChange = { onToggle(id) }
                        )
                    }
                    Text(
                        id,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (index < ids.lastIndex) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(border.copy(alpha = 0.5f))
                    )
                }
            }
        }
    }
}

@Composable
private fun ClaudeMappingEditor(
    mapping: ClaudeModelMapping?,
    candidates: List<String>,
    onPick: (String, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ClaudeModelMapping.SLOT_ORDER.forEach { slot ->
            var open by remember { mutableStateOf(false) }
            val value = claudeSlotValue(mapping, slot)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    claudeSlotLabel(slot),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    OutlinedButton(onClick = { open = true }) {
                        Text(
                            value.ifBlank { "未设置" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp),
                        )
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(
                            text = { Text("未设置") },
                            onClick = {
                                onPick(slot, "")
                                open = false
                            },
                        )
                        candidates.forEach { id ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        id,
                                        color = if (id == value) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                },
                                onClick = {
                                    onPick(slot, id)
                                    open = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TestResultText(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(top = 8.dp)
    )
}

internal enum class AddModelProblem { BLANK_ID, DUPLICATE_ID, INVALID_CONTEXT }

internal fun validateNewModel(
    id: String,
    existingIds: Set<String>,
    contextWindowText: String,
): AddModelProblem? {
    val trimmed = id.trim()
    if (trimmed.isBlank()) return AddModelProblem.BLANK_ID
    if (trimmed in existingIds) return AddModelProblem.DUPLICATE_ID
    if ((contextWindowText.toIntOrNull() ?: 0) <= 0) return AddModelProblem.INVALID_CONTEXT
    return null
}

/** ZCode 的规范模态序（renderer bundle 的 zMt）；归一化与界面选项都以此为准。 */
internal val MODALITY_ORDER = listOf("text", "image", "video", "audio", "pdf")

/**
 * ZCode 的 o5：去重、丢弃未知值、按规范序重排。
 * 刻意不强制补 text —— 缺 text 是校验要报的错，不是归一化要悄悄修的事。
 */
internal fun normalizeInputModalities(selected: Iterable<String>): List<String> {
    val picked = selected.toSet()
    return MODALITY_ORDER.filter { it in picked }
}

/** 界面态 → 存储态。text 恒在（界面把它锁为必选，ZCode 的 WMt 亦如此）。 */
internal fun newModelModalities(selected: Iterable<String>): List<String> =
    normalizeInputModalities(selected + "text")

/** 存储态 → 界面态。归一化为集合，未知值与重复项在此被吸收。 */
internal fun splitInputModalities(modalities: Iterable<String>): Set<String> =
    (normalizeInputModalities(modalities) + "text").toSet()

/**
 * ZCode HMt 的校验级联：命中的第一个字段即返回，顺序即优先级。
 * 缺 kinds 一项 —— ZCode 一个模型可配多种 API 格式，AndMX 一个供应商只有一种。
 */
internal enum class ModelMetadataProblem { ID, CONTEXT_WINDOW, MAX_OUTPUT_TOKENS, INPUT_MODALITIES }

internal data class ModelMetadataDraft(
    val id: String,
    val contextWindowText: String,
    val maxOutputTokensText: String,
    val inputModalities: List<String>,
)

internal fun validateModelMetadata(draft: ModelMetadataDraft): ModelMetadataProblem? {
    if (draft.id.trim().isEmpty()) return ModelMetadataProblem.ID
    if (!isValidTokenCount(draft.contextWindowText)) return ModelMetadataProblem.CONTEXT_WINDOW
    if (!isValidTokenCount(draft.maxOutputTokensText)) return ModelMetadataProblem.MAX_OUTPUT_TOKENS
    if ("text" !in normalizeInputModalities(draft.inputModalities)) {
        return ModelMetadataProblem.INPUT_MODALITIES
    }
    return null
}

/**
 * ZCode 的 VMt 要求正整数（trim → 空则 null → Number → 必须整数且 > 0）。
 * AndMX 用空白与 0 表示「未指定」，沿用该语义，故这两者算通过。
 */
internal fun isValidTokenCount(raw: String): Boolean {
    val t = raw.trim()
    if (t.isEmpty() || t == "0") return true
    val n = t.toDoubleOrNull() ?: return false
    if (!n.isFinite()) return false
    val asLong = n.toLong()
    return n == asLong.toDouble() && asLong > 0 && asLong <= Int.MAX_VALUE
}

/** 0 表示未指定，落回空串交给 [isValidTokenCount] 放过。 */
internal fun metadataTokenText(value: Int): String = if (value > 0) value.toString() else ""

internal fun modelMetadataProblemText(problem: ModelMetadataProblem): String = when (problem) {
    ModelMetadataProblem.ID -> "模型 ID 不能为空"
    ModelMetadataProblem.CONTEXT_WINDOW -> "上下文窗口必须是正整数"
    ModelMetadataProblem.MAX_OUTPUT_TOKENS -> "最大输出 Token 必须是正整数"
    ModelMetadataProblem.INPUT_MODALITIES -> "输入类型必须包含文本"
}

/**
 * 模型对外暴露推理控制的方式，供 [SelectedModelsEditor] 的推理小节选择。
 * NONE 对应 [ModelDefinition.reasoning] 为 null；到 [ReasoningStyle] 的映射
 * 集中写在 [buildReasoningConfig] / [splitReasoningConfig] 的 when 里。
 */
internal enum class ReasoningChoice(val label: String) {
    NONE("不支持"),
    EFFORT("强度档位"),
    THINKING("思考预算"),
}

/** OpenAI 系约定俗成的强度阶梯；目录里出现阶梯外的档位时保留在末尾，不丢数据。 */
internal val EFFORT_LADDER = listOf("minimal", "low", "medium", "high")

/** 派生自目录默认值，避免两处各写一份 16000 后漂移。 */
internal val DEFAULT_THINKING_BUDGET_TEXT: String
    get() = ReasoningConfig.ANTHROPIC_THINKING.defaultBudgetTokens.toString()

/** 推理小节的界面态。[buildReasoningConfig] 与 [splitReasoningConfig] 互为逆运算。 */
internal data class ReasoningDraft(
    val choice: ReasoningChoice,
    /** EFFORT：该模型实际接受的档位，按 [EFFORT_LADDER] 规范排序。 */
    val levels: List<String>,
    val defaultLevel: String,
    /** THINKING：budget_tokens 原文，非法时保留用户输入以便报错。 */
    val budgetText: String,
)

/** 已知档位按阶梯序，阶梯外的未知档位去重后排在末尾。 */
internal fun orderEffortLevels(levels: List<String>): List<String> {
    val known = EFFORT_LADDER.filter { it in levels }
    val extra = levels.filter { it !in EFFORT_LADDER }.distinct().sorted()
    return known + extra
}

/** Anthropic 规范要求 budget_tokens ≥ 1024。 */
internal fun validateThinkingBudget(text: String): Boolean =
    (text.toIntOrNull() ?: -1) >= AnthropicMessagesAdapter.MIN_THINKING_BUDGET

/**
 * 界面态 → 存储态。选「不支持」时返回 null，让 [ModelDefinition.reasoning] 落回 null，
 * 而不是存一个空壳对象。
 */
internal fun buildReasoningConfig(draft: ReasoningDraft): ReasoningConfig? = when (draft.choice) {
    ReasoningChoice.NONE -> null
    ReasoningChoice.EFFORT -> {
        val levels = orderEffortLevels(draft.levels)
        ReasoningConfig(
            style = ReasoningStyle.EFFORT,
            effortLevels = levels,
            defaultEffort = draft.defaultLevel.takeIf { it in levels }
                ?: levels.firstOrNull()
                ?: "",
        )
    }
    ReasoningChoice.THINKING -> ReasoningConfig(
        style = ReasoningStyle.THINKING,
        defaultBudgetTokens = draft.budgetText
            .toIntOrNull()
            ?.coerceAtLeast(AnthropicMessagesAdapter.MIN_THINKING_BUDGET)
            ?: ReasoningConfig.ANTHROPIC_THINKING.defaultBudgetTokens,
    )
}

/** 存储态 → 界面态。未知档位原样带回，避免编辑目录模型时把自定义档位抹掉。 */
internal fun splitReasoningConfig(config: ReasoningConfig?): ReasoningDraft = when (config?.style) {
    ReasoningStyle.EFFORT -> {
        val levels = orderEffortLevels(config.effortLevels)
        ReasoningDraft(
            choice = ReasoningChoice.EFFORT,
            levels = levels,
            defaultLevel = config.defaultEffort.takeIf { it in levels }.orEmpty(),
            budgetText = DEFAULT_THINKING_BUDGET_TEXT,
        )
    }
    ReasoningStyle.THINKING -> ReasoningDraft(
        choice = ReasoningChoice.THINKING,
        levels = emptyList(),
        defaultLevel = "",
        budgetText = config.defaultBudgetTokens
            .takeIf { it > 0 }
            ?.toString()
            ?: DEFAULT_THINKING_BUDGET_TEXT,
    )
    else -> ReasoningDraft(ReasoningChoice.NONE, emptyList(), "", DEFAULT_THINKING_BUDGET_TEXT)
}

private val MODEL_INPUT_MODALITIES = listOf(
    "text" to "文本",
    "image" to "图片",
    "video" to "视频",
    "audio" to "音频",
    "pdf" to "PDF",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    options: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    locked: Set<String> = emptySet(),
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val isLocked = value in locked
            val isOn = value in selected
            AssistChip(
                onClick = { onToggle(value, !isOn) },
                enabled = !isLocked,
                label = {
                    Text(
                        label,
                        color = if (isOn) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                },
                leadingIcon = if (isLocked) {
                    {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = "必选",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else null,
                colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                    containerColor = if (isOn) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                )
            )
        }
    }
}

/**
 * 模型级推理声明。自定义模型默认 [ModelDefinition.reasoning] 为 null，
 * 作曲栏的档位选择器不会亮起；这里让每个模型自己声明推理方式。
 *
 * 界面态由 [splitReasoningConfig] 从存储态推导，回写走 [buildReasoningConfig]，
 * 两者互为逆运算，避免档位在反复编辑中静默翻转。
 */
@Composable
private fun ReasoningEditor(
    config: ReasoningConfig?,
    onChange: (ReasoningConfig?) -> Unit,
) {
    val draft = remember(config) { splitReasoningConfig(config) }
    var choice by remember(config) { mutableStateOf(draft.choice) }
    var levels by remember(config) { mutableStateOf(draft.levels) }
    var defaultLevel by remember(config) { mutableStateOf(draft.defaultLevel) }
    var budgetText by remember(config) { mutableStateOf(draft.budgetText) }

    fun push(
        nextChoice: ReasoningChoice = choice,
        nextLevels: List<String> = levels,
        nextDefault: String = defaultLevel,
        nextBudget: String = budgetText,
    ) {
        onChange(
            buildReasoningConfig(
                ReasoningDraft(nextChoice, nextLevels, nextDefault, nextBudget)
            )
        )
    }

    Text(
        "推理",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
    ChipRow(
        options = ReasoningChoice.entries.map { it.name to it.label },
        selected = setOf(choice.name),
        onToggle = { value, on ->
            if (on) {
                val next = ReasoningChoice.valueOf(value)
                choice = next
                push(nextChoice = next)
            }
        },
    )

    when (choice) {
        ReasoningChoice.NONE -> {}

        ReasoningChoice.EFFORT -> {
            Spacer(Modifier.height(8.dp))
            Text(
                "可用档位",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            ChipRow(
                options = (EFFORT_LADDER + levels.filter { it !in EFFORT_LADDER })
                    .distinct()
                    .map { it to effortLabel(it) },
                selected = levels.toSet(),
                onToggle = { value, on ->
                    val next = orderEffortLevels(if (on) levels + value else levels - value)
                    levels = next
                    val nextDefault = defaultLevel.takeIf { it in next }.orEmpty()
                    defaultLevel = nextDefault
                    push(nextLevels = next, nextDefault = nextDefault)
                },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "默认档位",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            if (levels.isEmpty()) {
                Text(
                    "先选择至少一个可用档位",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ChipRow(
                    options = levels.map { it to effortLabel(it) },
                    selected = setOf(defaultLevel),
                    onToggle = { value, on ->
                        if (on) {
                            defaultLevel = value
                            push(nextDefault = value)
                        }
                    },
                )
            }
        }

        ReasoningChoice.THINKING -> {
            val budgetOk = validateThinkingBudget(budgetText)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = budgetText,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(8)
                    budgetText = digits
                    if (validateThinkingBudget(digits)) push(nextBudget = digits)
                },
                label = { Text("默认思考预算") },
                placeholder = { Text(DEFAULT_THINKING_BUDGET_TEXT) },
                suffix = { Text("tokens", style = MaterialTheme.typography.labelSmall) },
                supportingText = {
                    Text("不少于 ${AnthropicMessagesAdapter.MIN_THINKING_BUDGET}")
                },
                isError = !budgetOk,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
            )
        }
    }
}

@Composable
private fun AddModelDialog(
    existingIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (String, ModelDefinition) -> Unit,
) {
    var modelId by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var contextWindow by remember { mutableStateOf("128000") }
    var maxOutput by remember { mutableStateOf("") }
    var inputModalities by remember { mutableStateOf(setOf("text")) }

    val trimmedId = modelId.trim()
    val problem = validateNewModel(modelId, existingIds, contextWindow)
    val isDuplicate = problem == AddModelProblem.DUPLICATE_ID
    val contextValue = contextWindow.toIntOrNull() ?: 0
    val canSave = problem == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加模型") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("模型 ID") },
                    placeholder = { Text("例如 glm-4.6") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = isDuplicate,
                    supportingText = if (isDuplicate) {
                        { Text("该模型已在列表中", color = MaterialTheme.colorScheme.error) }
                    } else null
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("显示名称") },
                    placeholder = { Text("可选，留空则显示模型 ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = contextWindow,
                    onValueChange = { raw -> contextWindow = raw.filter { it.isDigit() }.take(9) },
                    label = { Text("上下文窗口") },
                    suffix = { Text("tokens", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = problem == AddModelProblem.INVALID_CONTEXT,
                    supportingText = if (problem == AddModelProblem.INVALID_CONTEXT) {
                        { Text("请输入大于 0 的整数", color = MaterialTheme.colorScheme.error) }
                    } else null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    )
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = maxOutput,
                    onValueChange = { raw -> maxOutput = raw.filter { it.isDigit() }.take(8) },
                    label = { Text("最大输出 Token") },
                    placeholder = { Text("可选，0 表示不限制") },
                    suffix = { Text("tokens", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    )
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "输入类型",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                ChipRow(
                    options = MODEL_INPUT_MODALITIES,
                    selected = inputModalities,
                    locked = setOf("text"),
                    onToggle = { value, on ->
                        inputModalities = splitInputModalities(
                            if (on) inputModalities + value else inputModalities - value
                        )
                    }
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "输出类型",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                ChipRow(
                    options = listOf("text" to "文本"),
                    selected = setOf("text"),
                    locked = setOf("text"),
                    onToggle = { _, _ -> }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        trimmedId,
                        ModelDefinition(
                            displayName = displayName.trim().ifBlank { null },
                            contextWindow = contextValue,
                            maxOutputTokens = maxOutput.toIntOrNull() ?: 0,
                            inputModalities = newModelModalities(inputModalities),
                            outputModalities = listOf("text")
                        )
                    )
                },
                enabled = canSave
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
