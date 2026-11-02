package com.andmx.ui2.settings.pages

import com.andmx.ui2.settings.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andmx.agent.memory.MemorySystem
import com.andmx.exec.files.GuestFs
import com.andmx.exec.proot.ProotRuntime
import com.andmx.settings.ProviderSettings
import com.andmx.settings.SettingsStore
import com.andmx.ui2.settings.SwitchRow
import com.andmx.ui2.settings.backAppBar
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class MemoryFileEntry(
    val name: String,
    val path: String,
    val description: String,
)

private val MemoryFiles = listOf(
    MemoryFileEntry(
        "MEMORY.md",
        "/root/.andmx/memory/MEMORY.md",
        "已巩固的长期记忆（Phase 2 产物）",
    ),
    MemoryFileEntry(
        "raw_memories.md",
        "/root/.andmx/memory/raw_memories.md",
        "逐轮提取的原始记忆（Phase 1 产物）",
    ),
    MemoryFileEntry(
        "memory_summary.md",
        "/root/.andmx/memory/memory_summary.md",
        "记忆概览摘要",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryViewerPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val fs = remember { GuestFs(ProotRuntime(context)) }
    val memorySystem = remember { MemorySystem(fs) }
    var selected by remember { mutableStateOf<MemoryFileEntry?>(null) }
    var query by remember { mutableStateOf("") }

    val snapshot by produceState(MemorySystem.MemorySnapshot(false, "", "", 0, emptyList(), emptyMap(), false)) {
        value = withContext(Dispatchers.IO) {
            runCatching { memorySystem.load() }.getOrNull()
                ?: MemorySystem.MemorySnapshot(false, "", "", 0, emptyList(), emptyMap(), false)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = { backAppBar("记忆", onBack) },
    ) { padding ->
        val sel = selected
        if (sel != null) {
            val content by produceState("" to false, sel) {
                value = withContext(Dispatchers.IO) {
                    runCatching { fs.readText(sel.path, limit = 5 * 1024 * 1024) to false }
                        .getOrElse { (it.message ?: "无法读取") to (it.message?.contains("过大") == true) }
                }
            }
            val tooLarge = content.second
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                Text(
                    sel.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    Text(
                        when {
                            tooLarge -> "该记忆文件超过 5 MiB 预览上限。"
                            content.first.isBlank() -> "（文件不存在或为空）"
                            else -> content.first
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 17.sp),
                        modifier = Modifier.padding(bottom = 32.dp),
                    )
                }
            }
            return@Scaffold
        }
        val store = remember { SettingsStore(context) }
        val settings by store.settings.collectAsState(initial = ProviderSettings())
        val scope = rememberCoroutineScope()
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            SwitchRow(
                title = "工作区记忆",
                description = "在工作区中保存并复用长期上下文，新会话生效。开启后可能增加模型调用和 Token 成本。",
                checked = settings.memoryEnabled,
                onCheckedChange = { enabled ->
                    scope.launch { store.update(settings.copy(memoryEnabled = enabled)) }
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("原始记忆 ${snapshot.rawCount} 条"),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (snapshot.categoryCounts.isNotEmpty()) {
                    Text(
                        snapshot.categoryCounts.entries.joinToString(" · ") { "${it.key.label} ${it.value}" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(tr("搜索记忆")) },
            )
            Spacer(Modifier.height(8.dp))
            val q = query.trim()
            val visible = if (q.isBlank()) {
                MemoryFiles
            } else {
                MemoryFiles.filter { entry ->
                    entry.name.contains(q, ignoreCase = true) ||
                        entry.description.contains(q, ignoreCase = true) ||
                        snapshot.rawMemories.any { it.content.contains(q, ignoreCase = true) }
                }
            }
            visible.forEach { entry ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { selected = entry }
                        .padding(14.dp),
                ) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    val modified = runCatching { fs.resolve(entry.path).lastModified() }.getOrDefault(0L)
                    Text(
                        listOf(entry.description, relativeTime(modified)).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            if (q.isNotBlank()) {
                snapshot.rawMemories.filter { it.content.contains(q, ignoreCase = true) }.take(20).forEach { item ->
                    Text(
                        listOf(item.category.label, relativeInstant(item.timestamp)).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(item.content, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun relativeTime(ms: Long): String {
    if (ms <= 0L) return ""
    return relativeFromEpoch(ms)
}

private fun relativeInstant(raw: String): String {
    val ms = runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull() ?: return raw
    return relativeFromEpoch(ms)
}

private fun relativeFromEpoch(ms: Long): String {
    val minutes = (System.currentTimeMillis() - ms) / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)} 天前"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))
    }
}
