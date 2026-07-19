package com.andmx.ui2.settings.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.andmx.agent.multi.SubagentCatalog
import com.andmx.agent.multi.SubagentModelCatalog
import com.andmx.agent.multi.SubagentModelOption
import com.andmx.settings.CustomSubAgent
import com.andmx.ui2.settings.SegmentedRow
import com.andmx.ui2.settings.SettingsGroup
import com.andmx.ui2.settings.StackedSettingRow
import com.andmx.ui2.settings.SwitchRow
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubAgentEditPage(
    initial: CustomSubAgent?,
    builtIn: Boolean = false,
    modelOptions: List<SubagentModelOption> = emptyList(),
    onBack: () -> Unit,
    onSave: (CustomSubAgent) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var systemPrompt by remember { mutableStateOf(initial?.systemPrompt.orEmpty()) }
    var model by remember {
        mutableStateOf(
            if (SubagentModelCatalog.isInherit(initial?.model)) SubagentModelCatalog.INHERIT
            else initial?.model.orEmpty()
        )
    }
    var permissionMode by remember { mutableStateOf(initial?.permissionMode ?: "default") }
    var color by remember { mutableStateOf(initial?.color ?: "blue") }
    var background by remember { mutableStateOf(initial?.background ?: false) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var toolsAll by remember { mutableStateOf(SubagentCatalog.isAllTools(initial?.tools)) }
    var selectedTools by remember {
        mutableStateOf(
            (initial?.tools ?: emptyList())
                .filter { it != "*" && it in SubagentCatalog.CATALOG_TOOLS }
                .ifEmpty { SubagentCatalog.CATALOG_TOOLS }
        )
    }
    var maxTurnsText by remember {
        mutableStateOf(initial?.maxTurns?.toString().orEmpty())
    }
    var error by remember { mutableStateOf<String?>(null) }

    val nameError = when {
        builtIn -> null
        name.trim().length !in 3..50 -> "名称需 3–50 个字符"
        !Regex("^[a-zA-Z0-9-]+$").matches(name.trim()) -> "仅字母、数字、连字符"
        name.trim() in SubagentCatalog.BUILTIN_NAMES -> "名称被内置代理占用"
        else -> null
    }
    val canSave = if (builtIn) {
        true
    } else {
        nameError == null && description.isNotBlank() && systemPrompt.isNotBlank()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            builtIn -> "内置 · ${initial?.name.orEmpty()}"
                            initial == null -> "新建子智能体"
                            else -> "编辑子智能体"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            try {
                                val maxTurns = maxTurnsText.trim().toIntOrNull()
                                val tools = if (toolsAll || builtIn && SubagentCatalog.isAllTools(initial?.tools)) {
                                    if (builtIn && initial != null) initial.tools else listOf("*")
                                } else {
                                    selectedTools.ifEmpty { listOf("*") }
                                }
                                val agent = CustomSubAgent(
                                    id = initial?.id ?: UUID.randomUUID().toString(),
                                    name = if (builtIn) initial?.name.orEmpty() else name.trim(),
                                    description = if (builtIn) initial?.description.orEmpty() else description.trim(),
                                    systemPrompt = if (builtIn) initial?.systemPrompt.orEmpty() else systemPrompt.trim(),
                                    model = model,
                                    permissionMode = if (builtIn) initial?.permissionMode ?: "default" else permissionMode,
                                    color = if (builtIn) initial?.color ?: "blue" else color,
                                    background = if (builtIn) initial?.background ?: false else background,
                                    enabled = enabled,
                                    tools = if (builtIn) initial?.tools ?: listOf("*") else tools,
                                    disallowedTools = initial?.disallowedTools.orEmpty(),
                                    skills = initial?.skills.orEmpty(),
                                    maxTurns = if (builtIn) initial?.maxTurns else maxTurns,
                                    mcpServers = initial?.mcpServers.orEmpty(),
                                    scope = if (builtIn) "built-in" else "user",
                                    source = if (builtIn) "built-in" else "user",
                                    path = initial?.path.orEmpty(),
                                    readOnly = builtIn,
                                )
                                if (!builtIn) SubagentCatalog.validateUserAgent(agent)
                                error = null
                                onSave(agent)
                            } catch (t: Throwable) {
                                error = t.message ?: "保存失败"
                            }
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
            if (error != null) {
                Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (builtIn) {
                SettingsGroup("内置代理") {
                    StackedSettingRow(
                        title = "名称",
                        description = initial?.name.orEmpty()
                    ) {}
                    StackedSettingRow(
                        title = "描述",
                        description = initial?.description.orEmpty()
                    ) {}
                    StackedSettingRow(
                        title = "工具",
                        description = if (SubagentCatalog.isAllTools(initial?.tools)) {
                            "全部工具"
                        } else {
                            (initial?.tools ?: emptyList()).joinToString(", ")
                        }
                    ) {}
                }
                SettingsGroup("模型覆盖") {
                    StackedSettingRow(
                        title = "模型",
                        description = "继承默认使用当前会话模型；也可指定已配置供应商下的模型。",
                    ) {
                        SubagentModelSelectField(
                            value = model,
                            options = modelOptions,
                            onSelect = { model = it },
                        )
                    }
                }
            } else {
                SettingsGroup("基本信息") {
                    StackedSettingRow(
                        title = "名称",
                        description = nameError ?: "运行时通过该名称调度（subagent_type）"
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
                    StackedSettingRow(
                        title = "模型",
                        description = "继承默认使用当前会话模型；也可指定已配置供应商下的模型。",
                    ) {
                        SubagentModelSelectField(
                            value = model,
                            options = modelOptions,
                            onSelect = { model = it },
                        )
                    }
                    StackedSettingRow(title = "权限模式") {
                        SegmentedRow(
                            options = listOf(
                                "default" to "默认",
                                "acceptEdits" to "接受编辑",
                                "plan" to "计划",
                            ),
                            selected = if (permissionMode in listOf("default", "acceptEdits", "plan")) {
                                permissionMode
                            } else {
                                "default"
                            },
                            onSelect = { permissionMode = it },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    StackedSettingRow(title = "更多权限") {
                        SegmentedRow(
                            options = listOf(
                                "auto" to "自动",
                                "bypassPermissions" to "绕过",
                                "dontAsk" to "不询问",
                            ),
                            selected = if (permissionMode in listOf("auto", "bypassPermissions", "dontAsk")) {
                                permissionMode
                            } else {
                                "auto"
                            },
                            onSelect = { permissionMode = it },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    StackedSettingRow(title = "最大轮次", description = "留空使用默认") {
                        OutlinedTextField(
                            value = maxTurnsText,
                            onValueChange = { maxTurnsText = it.filter { ch -> ch.isDigit() }.take(3) },
                            placeholder = { Text("25") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }

                SettingsGroup("颜色标记") {
                    ColorPicker(selected = color, onSelect = { color = it })
                }

                SettingsGroup("工具") {
                    SegmentedRow(
                        options = listOf(
                            "all" to "全部工具",
                            "custom" to "自定义",
                        ),
                        selected = if (toolsAll) "all" else "custom",
                        onSelect = {
                            toolsAll = it == "all"
                            if (!toolsAll && selectedTools.isEmpty()) {
                                selectedTools = SubagentCatalog.CATALOG_TOOLS
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (!toolsAll) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                                .padding(8.dp)
                        ) {
                            SubagentCatalog.CATALOG_TOOLS.forEach { tool ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedTools = if (tool in selectedTools) {
                                                selectedTools - tool
                                            } else {
                                                selectedTools + tool
                                            }
                                        }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = tool in selectedTools,
                                        onCheckedChange = { checked ->
                                            selectedTools = if (checked) {
                                                selectedTools + tool
                                            } else {
                                                selectedTools - tool
                                            }
                                        }
                                    )
                                    Text(tool, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
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
            }

            if (onDelete != null && !builtIn) {
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
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
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SubagentCatalog.COLORS.forEach { id ->
            val isSel = id == selected
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(agentColor(id))
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
