package com.andmx.ui2.settings.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.andmx.agent.plugins.PluginSystem
import com.andmx.agent.plugins.mobile.MobilePluginConfig
import com.andmx.ui2.settings.SettingsGroup
import com.andmx.ui2.settings.backAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginDetailPage(
    plugin: PluginSystem.Plugin,
    onBack: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onUninstall: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val m = plugin.manifest
    var confirmUninstall by remember { mutableStateOf(false) }
    val configFields = remember(m.name) { mobileConfigFields(m.name) }
    val drafts = remember(m.name) {
        mutableStateMapOf<String, String>().apply {
            configFields.forEach { (key, _) ->
                this[key] = readMobileConfig(context, m.name, key)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = { backAppBar(m.name, onBack) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            SettingsGroup("状态") {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (plugin.enabled) "已启用" else "已停用",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "v${m.version}" + if (m.author.isNotBlank()) " · ${m.author}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = plugin.enabled, onCheckedChange = onToggleEnabled)
                }
                AssistChip(
                    onClick = {}, enabled = false,
                    label = { Text(sourceLabel(plugin.source)) },
                )
            }

            if (m.description.isNotBlank()) {
                SettingsGroup("描述") {
                    Text(
                        m.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            SettingsGroup("组件") {
                CapabilitySection("工具", Icons.Outlined.Terminal, m.tools)
                CapabilitySection("技能", Icons.Outlined.Bolt, m.skills)
                CapabilitySection("命令", Icons.Outlined.Terminal, m.commands)
                CapabilitySection("MCP 服务器", Icons.Outlined.Cloud, m.mcpServers)
                CapabilitySection(
                    "Hooks",
                    Icons.Outlined.Link,
                    m.hooks.map { it.name.ifBlank { "${it.event} → ${it.command}" } },
                )
                if (m.tools.isEmpty() && m.skills.isEmpty() && m.hooks.isEmpty() && m.commands.isEmpty() && m.mcpServers.isEmpty()) {
                    Text(
                        "该插件未声明可展示的组件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            if (configFields.isNotEmpty()) {
                SettingsGroup("用户配置") {
                    configFields.forEach { (key, desc) ->
                        OutlinedTextField(
                            value = drafts[key].orEmpty(),
                            onValueChange = {
                                drafts[key] = it
                                writeMobileConfig(context, m.name, key, it)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            label = { Text(key) },
                            supportingText = { Text(desc) },
                            singleLine = true,
                        )
                    }
                    Text(
                        "配置会立即生效，供 android_* 工具与 preflight 使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                }
            }

            SettingsGroup("安装信息") {
                InfoLine("目录", plugin.dir)
                InfoLine("版本", m.version)
                if (m.author.isNotBlank()) InfoLine("作者", m.author)
                if (plugin.installSource.isNotBlank()) InfoLine("来源", plugin.installSource)
            }

            if (onUninstall != null) {
                SettingsGroup("危险操作") {
                    TextButton(onClick = { confirmUninstall = true }) {
                        Icon(Icons.Outlined.Delete, null)
                        Text(" 卸载插件", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (confirmUninstall && onUninstall != null) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text("卸载 ${m.name}？") },
            text = { Text("将删除 ${plugin.dir}，此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUninstall = false
                        onUninstall()
                    },
                ) { Text("卸载") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CapabilitySection(title: String, icon: ImageVector, items: List<String>) {
    if (items.isEmpty()) return
    Text(
        "$title · ${items.size} 项",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
    items.forEach { item ->
        ListItem(
            headlineContent = { Text(item) },
            leadingContent = { Icon(icon, null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
        )
    }
}

private fun sourceLabel(source: PluginSystem.PluginSource): String = when (source) {
    PluginSystem.PluginSource.BUILTIN -> "内置"
    PluginSystem.PluginSource.MARKETPLACE -> "市场安装"
    PluginSystem.PluginSource.LOCAL -> "本地"
}

private fun mobileConfigFields(pluginName: String): List<Pair<String, String>> = when (pluginName) {
    "andmx-android-dev" -> listOf(
        "sdk_path" to "Android SDK 根目录，空则自动探测",
        "default_avd" to "默认 AVD 名称",
        "api_level" to "默认 API level",
        "build_tools_version" to "build-tools 版本",
        "system_image_variant" to "default / google_apis / google_apis_playstore",
        "system_image_abi" to "ABI，空则自动",
        "jdk_major" to "JDK major",
    )
    else -> emptyList()
}

private fun readMobileConfig(context: android.content.Context, plugin: String, key: String): String =
    when (plugin) {
        "andmx-android-dev" -> MobilePluginConfig.allAndroid(context)[key].orEmpty()
        else -> ""
    }

private fun writeMobileConfig(context: android.content.Context, plugin: String, key: String, value: String) {
    when (plugin) {
        "andmx-android-dev" -> MobilePluginConfig.setAndroid(context, key, value)
    }
}
