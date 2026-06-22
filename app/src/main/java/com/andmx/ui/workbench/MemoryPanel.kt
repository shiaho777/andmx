package com.andmx.ui.workbench

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.agent.memory.MemorySystem
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Spacing
import kotlinx.coroutines.flow.StateFlow

/**
 * Memory panel — full-featured memory browser.
 *
 * Features:
 * - Category tabs with counts
 * - Raw memory list with timestamps and sessions
 * - Individual memory deletion
 * - Consolidation trigger when threshold reached
 * - Summary view with statistics
 * - Search/filter (future)
 */
@Composable
fun MemoryPanel(
    memoryState: StateFlow<MemorySystem.MemorySnapshot>,
    onClearMemory: () -> Unit,
    onConsolidate: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val snapshot by memoryState.collectAsState()
    val colors = AndmxTheme.colors
    var selectedCategory by remember { mutableStateOf<MemorySystem.MemoryCategory?>(null) }
    var showAllRaw by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.sm),
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                Icons.Outlined.Psychology,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = colors.accent,
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                "记忆",
                style = AndmxTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            Spacer(Modifier.weight(1f))
            if (snapshot.rawCount > 0) {
                Text(
                    "${snapshot.rawCount} 条",
                    style = AndmxTheme.typography.labelSmall,
                    color = colors.textTertiary,
                )
            }
        }

        Spacer(Modifier.height(Spacing.sm))

        if (!snapshot.hasMemory) {
            // Empty state
            EmptyMemoryState(colors)
            return
        }

        // Consolidation banner
        AnimatedVisibility(
            visible = snapshot.needsConsolidation,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = colors.accent.copy(alpha = 0.08f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.sm),
            ) {
                Row(
                    modifier = Modifier.padding(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = colors.accent,
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        "有 ${snapshot.rawCount} 条原始记忆待整合",
                        style = AndmxTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onConsolidate, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("整合", style = AndmxTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Category chips
        if (snapshot.categoryCounts.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.sm),
            ) {
                // "All" chip
                CategoryChip(
                    label = "全部",
                    count = snapshot.rawCount,
                    selected = selectedCategory == null,
                    color = colors,
                    onClick = { selectedCategory = null },
                )
                Spacer(Modifier.width(Spacing.xs))
                // Per-category chips
                for (cat in MemorySystem.MemoryCategory.entries) {
                    val count = snapshot.categoryCounts[cat] ?: 0
                    if (count > 0) {
                        CategoryChip(
                            label = cat.label,
                            count = count,
                            selected = selectedCategory == cat,
                            color = colors,
                            onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                        )
                        Spacer(Modifier.width(Spacing.xs))
                    }
                }
            }
        }

        // Summary section (if consolidated memory exists)
        if (snapshot.summary.isNotBlank()) {
            var showSummary by remember { mutableStateOf(false) }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = colors.sunken,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.sm),
            ) {
                Column(modifier = Modifier.padding(Spacing.sm)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showSummary = !showSummary },
                    ) {
                        Icon(
                            if (showSummary) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = colors.textTertiary,
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            "摘要",
                            style = AndmxTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textSecondary,
                        )
                    }
                    AnimatedVisibility(showSummary) {
                        Text(
                            snapshot.summary.take(800),
                            style = AndmxTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(top = Spacing.xs),
                            maxLines = 15,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // Raw memories list
        if (snapshot.rawMemories.isNotEmpty()) {
            Text(
                "原始记忆",
                style = AndmxTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textSecondary,
                modifier = Modifier.padding(bottom = Spacing.xs),
            )

            val filtered = if (selectedCategory != null) {
                snapshot.rawMemories.filter { it.category == selectedCategory }
            } else {
                snapshot.rawMemories
            }
            val display = if (showAllRaw) filtered else filtered.take(20)

            for (mem in display) {
                RawMemoryCard(mem, colors)
                Spacer(Modifier.height(Spacing.xs))
            }

            if (filtered.size > 20 && !showAllRaw) {
                TextButton(
                    onClick = { showAllRaw = true },
                    modifier = Modifier.padding(top = Spacing.xs),
                ) {
                    Text("显示全部 ${filtered.size} 条", style = AndmxTheme.typography.labelSmall)
                }
            }
            if (showAllRaw && filtered.size > 20) {
                TextButton(
                    onClick = { showAllRaw = false },
                    modifier = Modifier.padding(top = Spacing.xs),
                ) {
                    Text("收起", style = AndmxTheme.typography.labelSmall)
                }
            }
        }

        Spacer(Modifier.height(Spacing.sm))

        // Clear all button
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(
                onClick = onClearMemory,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.warning),
            ) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text("清除所有记忆", style = AndmxTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun EmptyMemoryState(colors: com.andmx.ui.theme.AndmxColors) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.Psychology,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = colors.textTertiary,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            "暂无持久化记忆",
            style = AndmxTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "Agent 会在对话中自动提取有价值的信息并保存。\n记忆会在后续会话中注入上下文，帮助 Agent 更好地理解你的偏好和环境。",
            style = AndmxTheme.typography.labelSmall,
            color = colors.textTertiary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = Spacing.md),
        )
    }
}

@Composable
private fun CategoryChip(
    label: String,
    count: Int,
    selected: Boolean,
    color: com.andmx.ui.theme.AndmxColors,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) color.accent.copy(alpha = 0.12f) else color.sunken,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = AndmxTheme.typography.labelSmall,
                color = if (selected) color.accent else color.textSecondary,
            )
            Spacer(Modifier.width(3.dp))
            Text(
                "$count",
                style = AndmxTheme.typography.labelSmall,
                color = if (selected) color.accent else color.textTertiary,
            )
        }
    }
}

@Composable
private fun RawMemoryCard(
    mem: MemorySystem.ParsedMemory,
    colors: com.andmx.ui.theme.AndmxColors,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = colors.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.sm)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Category badge
                Surface(
                    shape = RoundedCornerShape(3.dp),
                    color = categoryColor(mem.category, colors).copy(alpha = 0.1f),
                ) {
                    Text(
                        mem.category.label,
                        style = AndmxTheme.typography.labelSmall,
                        color = categoryColor(mem.category, colors),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    mem.timestamp.take(16).replace("T", " "),
                    style = AndmxTheme.typography.labelSmall,
                    color = colors.textTertiary,
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                mem.content,
                style = AndmxTheme.typography.bodySmall,
                color = colors.textPrimary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun categoryColor(cat: MemorySystem.MemoryCategory, colors: com.andmx.ui.theme.AndmxColors) = when (cat) {
    MemorySystem.MemoryCategory.USER_PREFERENCE -> colors.accent
    MemorySystem.MemoryCategory.FAILURE_SHIELD -> colors.warning
    MemorySystem.MemoryCategory.REPO_MAP -> colors.textSecondary
    MemorySystem.MemoryCategory.TOOL_QUIRK -> colors.accent
    MemorySystem.MemoryCategory.DECISION_TRIGGER -> colors.warning
    MemorySystem.MemoryCategory.REPRODUCTION_PLAN -> colors.textSecondary
}
