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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.andmx.settings.CustomCommand
import com.andmx.ui2.settings.SettingsGroup
import com.andmx.ui2.settings.StackedSettingRow
import java.util.UUID

private val NAME_REGEX = Regex("^[A-Za-z0-9_-]+$")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandEditPage(
    initial: CustomCommand?,
    onBack: () -> Unit,
    onSave: (CustomCommand) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var argumentHint by remember { mutableStateOf(initial?.argumentHint ?: "") }
    var prompt by remember { mutableStateOf(initial?.prompt ?: "") }

    val nameLengthOk = name.length in 1..48
    val nameCharsOk = name.isEmpty() || NAME_REGEX.matches(name)
    val promptOk = prompt.isNotBlank()
    val canSave = nameLengthOk && nameCharsOk && promptOk

    val nameError = when {
        name.isNotEmpty() && !nameLengthOk -> "长度必须在 1 到 48 个字符之间"
        !nameCharsOk -> "仅允许使用字母、数字、连字符和下划线"
        else -> null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "新建命令" else "编辑命令") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            onSave(
                                CustomCommand(
                                    id = initial?.id ?: UUID.randomUUID().toString(),
                                    name = name.trim(),
                                    description = description.trim(),
                                    argumentHint = argumentHint.trim(),
                                    prompt = prompt.trim()
                                )
                            )
                        },
                        enabled = canSave
                    ) {
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
            SettingsGroup("命令") {
                StackedSettingRow(
                    title = "名称",
                    description = nameError ?: "调用方式：/${name.ifBlank { "my-command" }}"
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("my-command") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = nameError != null,
                        prefix = { Text("/") }
                    )
                }
                StackedSettingRow(title = "描述（可选）") {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        placeholder = { Text("在命令选择器中显示的简短描述") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                StackedSettingRow(title = "参数提示（可选）") {
                    OutlinedTextField(
                        value = argumentHint,
                        onValueChange = { argumentHint = it },
                        placeholder = { Text("例如 <file-path>") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            SettingsGroup("提示词") {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    placeholder = { Text("填写调用该命令时发送的提示词...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    isError = prompt.isNotEmpty() && !promptOk
                )
            }

            if (onDelete != null) {
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                ) {
                    Text("删除命令", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
