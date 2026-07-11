package com.andmx.ui2.settings.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.andmx.settings.CustomSubAgent
import com.andmx.ui2.settings.SegmentedRow
import com.andmx.ui2.settings.SettingsGroup
import com.andmx.ui2.settings.StackedSettingRow
import com.andmx.ui2.settings.SwitchRow
import java.util.UUID

private val AGENT_NAME_REGEX = Regex("^[A-Za-z0-9-]+$")

internal val agentColors = listOf(
    "blue" to Color(0xFF3B82F6), "cyan" to Color(0xFF06B6D4),
    "green" to Color(0xFF22C55E), "orange" to Color(0xFFF97316),
    "pink" to Color(0xFFEC4899), "purple" to Color(0xFF8B5CF6),
    "red" to Color(0xFFEF4444), "yellow" to Color(0xFFEAB308)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubAgentEditPage(
    initial: CustomSubAgent?,
    onBack: () -> Unit,
    onSave: (CustomSubAgent) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var systemPrompt by remember { mutableStateOf(initial?.systemPrompt ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "inherit") }
    var permissionMode by remember { mutableStateOf(initial?.permissionMode ?: "default") }
    var color by remember { mutableStateOf(initial?.color ?: "blue") }
    var background by remember { mutableStateOf(initial?.background ?: false) }

    val nameOk = name.length in 1..48 && AGENT_NAME_REGEX.matches(name)
    val canSave = nameOk && description.isNotBlank() && systemPrompt.isNotBlank()

    val nameError = when {
        name.isNotEmpty() && name.length !in 1..48 -> "长度必须在 1 到 48 个字符之间"
        name.isNotEmpty() && !AGENT_NAME_REGEX.matches(name) -> "仅允许使用字母、数字和连字符"
        else -> null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "新建子智能体" else "编辑子智能体") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            onSave(
                                CustomSubAgent(
                                    id = initial?.id ?: UUID.randomUUID().toString(),
                                    name = name.trim(),
                                    description = description.trim(),
                                    systemPrompt = systemPrompt.trim(),
                                    model = model,
                                    permissionMode = permissionMode,
                                    color = color,
                                    background = background,
                                    enabled = initial?.enabled ?: true
                                )
                            )
                        },
                        enabled = canSave
                    ) { Icon(Icons.Outlined.Check, "保存") }
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
                StackedSettingRow(
                    title = "名称",
                    description = nameError ?: "运行时通过该名称调度"
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("code-reviewer") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = nameError != null
                    )
                }
                StackedSettingRow(title = "描述", description = "展示给模型的简短说明") {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        placeholder = { Text("例如：专注代码审查的子智能体") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = description.isEmpty()
                    )
                }
            }

            SettingsGroup("模型与权限") {
                StackedSettingRow(title = "模型") {
                    SegmentedRow(
                        options = listOf("inherit" to "继承默认", "main" to "主模型", "lite" to "轻量"),
                        selected = model,
                        onSelect = { model = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                StackedSettingRow(title = "权限模式") {
                    SegmentedRow(
                        options = listOf(
                            "default" to "默认", "acceptEdits" to "接受编辑",
                            "plan" to "计划"
                        ),
                        selected = if (permissionMode in listOf("default", "acceptEdits", "plan")) permissionMode else "default",
                        onSelect = { permissionMode = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            SettingsGroup("颜色标记") {
                ColorPicker(selected = color, onSelect = { color = it })
            }

            SettingsGroup("运行") {
                SwitchRow(
                    title = "允许后台运行",
                    description = "模型请求时允许该子智能体作为后台任务运行。",
                    checked = background,
                    onCheckedChange = { background = it }
                )
            }

            SettingsGroup("系统提示词") {
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    placeholder = { Text("描述这个子智能体的角色、边界和规则...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    isError = systemPrompt.isEmpty()
                )
            }

            if (onDelete != null) {
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp)
                ) {
                    Text("删除子智能体", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ColorPicker(selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        agentColors.forEach { (id, c) ->
            val isSel = id == selected
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(
                            if (isSel) 3.dp else 0.dp,
                            MaterialTheme.colorScheme.onSurface,
                            CircleShape
                        )
                        .clickable { onSelect(id) }
                )
            }
        }
    }
}
