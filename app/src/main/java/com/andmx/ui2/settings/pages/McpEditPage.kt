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
import com.andmx.ui2.settings.SegmentedRow
import com.andmx.ui2.settings.SettingsGroup
import com.andmx.ui2.settings.StackedSettingRow

data class McpEntry(val name: String, val target: String) {
    val isRemote: Boolean get() = target.startsWith("http") || target.startsWith("ws")
    fun toLine(): String = "$name|$target"

    companion object {
        fun parse(text: String): List<McpEntry> = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('|') }
            .map { McpEntry(it.substringBefore('|').trim(), it.substringAfter('|').trim()) }
            .filter { it.name.isNotEmpty() && it.target.isNotEmpty() }
            .toList()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpEditPage(
    initial: McpEntry?,
    onBack: () -> Unit,
    onSave: (McpEntry) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var remote by remember { mutableStateOf(initial?.isRemote ?: true) }
    var target by remember { mutableStateOf(initial?.target ?: "") }

    val canSave = name.isNotBlank() && target.isNotBlank() && !name.contains('|')

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "新建 MCP 服务器" else "编辑 MCP 服务器") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { onSave(McpEntry(name.trim(), target.trim())) },
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
                StackedSettingRow(title = "名称", description = "工具名前缀，如 filesystem") {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("filesystem") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                StackedSettingRow(
                    title = "传输类型",
                    description = if (remote) "远程 HTTP/SSE 端点，适合移动端"
                        else "本地命令，需 proot 环境支持"
                ) {
                    SegmentedRow(
                        options = listOf("remote" to "HTTP / SSE", "stdio" to "本地命令"),
                        selected = if (remote) "remote" else "stdio",
                        onSelect = { remote = it == "remote" },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            SettingsGroup(if (remote) "端点" else "命令") {
                StackedSettingRow(
                    title = if (remote) "URL" else "启动命令",
                    description = if (remote) "服务器 HTTP/SSE 地址"
                        else "在 proot 沙箱中执行的完整命令"
                ) {
                    OutlinedTextField(
                        value = target,
                        onValueChange = { target = it },
                        placeholder = {
                            Text(
                                if (remote) "https://mcp.example.com/sse"
                                else "npx -y @modelcontextprotocol/server-filesystem /root"
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        minLines = if (remote) 1 else 2
                    )
                }
            }

            if (onDelete != null) {
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                ) {
                    Text("删除服务器", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
