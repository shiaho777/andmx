package com.andmx.ui2.settings.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.andmx.settings.ProviderSettings
import com.andmx.settings.SettingsStore
import com.andmx.ui2.settings.SettingsGroup
import com.andmx.ui2.settings.SwitchRow
import com.andmx.ui2.settings.backAppBar
import com.andmx.workspace.ProjectManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndexPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val s by store.settings.collectAsState(initial = ProviderSettings())
    val scope = rememberCoroutineScope()
    val projectManager = remember { ProjectManager(context) }
    val hostPath by projectManager.hostPath.collectAsState()

    fun save(u: ProviderSettings) { scope.launch { store.update(u) } }

    val fileCount by produceState(initialValue = -1, key1 = hostPath) {
        val path = hostPath
        value = if (path == null) -2
        else withContext(Dispatchers.IO) {
            runCatching { countFiles(File(path), s.indexFileLimit) }.getOrDefault(-1)
        }
    }

    Scaffold(topBar = { backAppBar("索引库", onBack) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            SettingsGroup("当前项目") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            projectManager.projectName.ifBlank { "未选择项目" },
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (hostPath != null) {
                            Text(
                                hostPath!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                when (fileCount) {
                                    -1 -> "文件统计中…"
                                    else -> {
                                        val over = fileCount >= s.indexFileLimit
                                        "约 $fileCount 个文件" +
                                            if (over) "（已达上限，超出部分不索引）" else ""
                                    }
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        } else {
                            Text(
                                "在「文件」中打开一个项目后，可在此配置索引。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            SettingsGroup("代码库") {
                SwitchRow(
                    title = "索引新文件夹",
                    description = "自动索引文件数少于 ${s.indexFileLimit} 的新文件夹。",
                    checked = s.indexNewFolders,
                    onCheckedChange = { save(s.copy(indexNewFolders = it)) }
                )
                HorizontalDivider()
                SwitchRow(
                    title = "即时搜索索引（测试版）",
                    description = "自动对仓库建立索引以加快搜索速度。所有数据均存储在本地。",
                    checked = s.instantGrep,
                    onCheckedChange = { save(s.copy(instantGrep = it)) }
                )
            }

            Text(
                "索引偏好已保存并对代理生效；语义索引引擎将在后续版本接入，届时这些开关会驱动实际索引构建。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

private fun countFiles(root: File, limit: Int): Int {
    if (!root.exists()) return 0
    var count = 0
    val stack = ArrayDeque<File>()
    stack.add(root)
    while (stack.isNotEmpty() && count < limit) {
        val f = stack.removeLast()
        val children = f.listFiles() ?: continue
        for (c in children) {
            if (c.name.startsWith(".")) continue
            if (c.isDirectory) stack.add(c) else count++
            if (count >= limit) break
        }
    }
    return count
}
