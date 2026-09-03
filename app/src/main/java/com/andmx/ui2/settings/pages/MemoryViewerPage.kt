package com.andmx.ui2.settings.pages

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
import com.andmx.ui2.settings.backAppBar
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

    val snapshot by produceState(MemorySystem.MemorySnapshot(false, "", "", 0, emptyList(), emptyMap(), false)) {
        value = withContext(Dispatchers.IO) {
            runCatching { memorySystem.load() }.getOrNull()
                ?: MemorySystem.MemorySnapshot(false, "", "", 0, emptyList(), emptyMap(), false)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = { backAppBar("工作区记忆", onBack) },
    ) { padding ->
        val sel = selected
        if (sel != null) {
            val content by produceState("", sel) {
                value = withContext(Dispatchers.IO) {
                    runCatching { fs.readText(sel.path, limit = 5 * 1024 * 1024) }.getOrDefault("")
                }
            }
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
                        content.ifBlank { "（文件不存在或为空）" },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 17.sp),
                        modifier = Modifier.padding(bottom = 32.dp),
                    )
                }
            }
            return@Scaffold
        }
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "原始记忆 ${snapshot.rawCount} 条",
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
            MemoryFiles.forEach { entry ->
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
                    Text(
                        entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
