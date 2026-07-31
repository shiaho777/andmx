package com.andmx.ui2.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andmx.diff.DiffStats
import java.io.File

data class TurnFileChange(
    val path: String,
    val stats: DiffStats,
    val operation: ToolEditPreview.Operation,
)

object TurnChangeSummary {
    fun collect(tools: List<ToolCall>): List<TurnFileChange> {
        val byPath = LinkedHashMap<String, TurnFileChange>()
        for (tool in tools) {
            if (tool.isRunning || tool.isError) continue
            if (!ToolEditDiff.isEditTool(tool.name)) continue
            val preview = ToolEditDiff.preview(tool.name, tool.args) ?: continue
            if (preview.path.isBlank()) continue
            val key = preview.path.replace('\\', '/')
            val prev = byPath[key]
            if (prev == null) {
                byPath[key] = TurnFileChange(preview.path, preview.stats, preview.operation)
            } else {
                byPath[key] = prev.copy(
                    stats = DiffStats(
                        added = prev.stats.added + preview.stats.added,
                        removed = prev.stats.removed + preview.stats.removed,
                    ),
                )
            }
        }
        return byPath.values.toList()
    }
}

@Composable
fun ChangeSummaryBar(
    changes: List<TurnFileChange>,
    onOpenFile: (String) -> Unit,
    onRewind: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (changes.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val totalAdded = changes.sumOf { it.stats.added }
    val totalRemoved = changes.sumOf { it.stats.removed }
    val count = changes.size
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.42f))
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$count 个文件已更改",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (totalAdded > 0) {
                Text(
                    "+$totalAdded",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = Color(0xFF3DDC84),
                )
            }
            if (totalRemoved > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "-$totalRemoved",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = Color(0xFFFF6B6B),
                )
            }
            if (onRewind != null) {
                Spacer(Modifier.width(10.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onRewind)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Undo,
                        null,
                        Modifier.padding(end = 2.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                    Text(
                        "撤销",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    )
                }
            }
        }
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                changes.forEach { change ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onOpenFile(change.path) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            File(change.path).name.ifBlank { change.path },
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (change.stats.added > 0) {
                            Text(
                                "+${change.stats.added}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                ),
                                color = Color(0xFF3DDC84),
                            )
                        }
                        if (change.stats.removed > 0) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "-${change.stats.removed}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                ),
                                color = Color(0xFFFF6B6B),
                            )
                        }
                    }
                }
            }
        }
    }
}
