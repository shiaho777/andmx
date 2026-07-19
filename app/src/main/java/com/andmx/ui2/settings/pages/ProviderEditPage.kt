package com.andmx.ui2.settings.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.andmx.llm.provider.ModelDefinition
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.provider.ProviderKind
import com.andmx.llm.wire.AdapterFactory
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
            models = modelMap
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

    val canSave = name.isNotBlank() && baseUrl.isNotBlank()
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
                    Spacer(Modifier.height(10.dp))
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
                            Text("添加")
                        }
                    }
                }
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
                                result.exceptionOrNull()?.message?.take(80) ?: "连接失败"
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
                    Text(
                        id,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
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
private fun TestResultText(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(top = 8.dp)
    )
}
