package com.andmx.ui2.settings.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import com.andmx.llm.LlmClient
import com.andmx.llm.ChatRequest
import com.andmx.llm.ApiMessage
import com.andmx.llm.provider.ModelDefinition
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.provider.ProviderKind
import com.andmx.ui2.settings.SegmentedRow
import com.andmx.ui2.settings.SettingsGroup
import com.andmx.ui2.settings.StackedSettingRow
import kotlinx.coroutines.launch
import java.util.UUID

private sealed class TestState {
    data object Idle : TestState()
    data object Testing : TestState()
    data object Success : TestState()
    data class Failed(val reason: String) : TestState()
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
    var models by remember {
        mutableStateOf(initial?.models?.keys?.joinToString("\n") ?: "")
    }
    var testState by remember { mutableStateOf<TestState>(TestState.Idle) }

    fun build(): ProviderDefinition {
        val modelMap = models.split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .associateWith { id ->
                initial?.models?.get(id) ?: ModelDefinition()
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

    val canSave = name.isNotBlank() && baseUrl.isNotBlank()

    Scaffold(
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
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            SettingsGroup("基本信息") {
                StackedSettingRow(title = "名称", description = "在聊天时用于识别该供应商") {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("如：智谱 GLM") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                StackedSettingRow(
                    title = "API 格式",
                    description = "接口路径：${kind.endpointPath}"
                ) {
                    SegmentedRow(
                        options = listOf(
                            ProviderKind.OPENAI.name to "Chat",
                            ProviderKind.OPENAI_RESPONSES.name to "Responses",
                            ProviderKind.ANTHROPIC.name to "Anthropic"
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
                        singleLine = true
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
                        visualTransformation = if (showKey) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        trailingIcon = {
                            androidx.compose.material3.TextButton(onClick = { showKey = !showKey }) {
                                Text(if (showKey) "隐藏" else "显示")
                            }
                        }
                    )
                }
            }

            SettingsGroup("模型列表") {
                StackedSettingRow(
                    title = "模型",
                    description = "每行一个模型 ID，聊天时可选择使用"
                ) {
                    OutlinedTextField(
                        value = models,
                        onValueChange = { models = it },
                        placeholder = { Text("gpt-4o\ngpt-4o-mini") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                }
            }

            SettingsGroup("连接测试") {
                val firstModel = models.lineSequence().map { it.trim() }
                    .firstOrNull { it.isNotBlank() }
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
                        Spacer(Modifier.height(0.dp))
                        Text("  测试中...")
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
private fun TestResultText(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(top = 8.dp)
    )
}
