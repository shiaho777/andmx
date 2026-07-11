package com.andmx.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.term.TerminalSession
import com.andmx.ui.theme.AndmxCodeTextStyle
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Radii
import com.andmx.ui.theme.Spacing

private sealed interface TerminalSurface {
    val key: String
    val title: String
    val subtitle: String
    data class Live(val session: TerminalSession) : TerminalSurface {
        override val key: String = liveTerminalSessionKey(session.sessionId)
        override val title: String = "shell"
        override val subtitle: String = "交互会话"
    }
    data class Tool(val binding: TerminalBindingState) : TerminalSurface {
        override val key: String = binding.id
        override val title: String = binding.title
        override val subtitle: String = when (binding.status) {
            ToolExecutionStatus.RUNNING -> "运行中"
            ToolExecutionStatus.WAITING_APPROVAL -> "等待授权"
            ToolExecutionStatus.FAILED -> "失败"
            ToolExecutionStatus.CANCELLED -> "已取消"
            ToolExecutionStatus.SUCCEEDED -> binding.exitCode?.let { "exit=$it" } ?: "完成"
            ToolExecutionStatus.QUEUED -> "排队中"
        }
    }
}

@Composable
fun TerminalHost(
    liveSessions: androidx.compose.runtime.snapshots.SnapshotStateList<TerminalSession>,
    toolSessions: List<TerminalBindingState>,
    selectedKey: String,
    onSelectedKeyChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = AndmxTheme.colors
    val context = LocalContext.current
    val surfaces = remember(liveSessions.toList(), toolSessions) {
        buildList {
            liveSessions.forEach { add(TerminalSurface.Live(it)) }
            toolSessions.forEach { add(TerminalSurface.Tool(it)) }
        }
    }
    val resolved = surfaces.firstOrNull { it.key == selectedKey } ?: surfaces.firstOrNull()

    LaunchedEffect(resolved?.key) {
        resolved?.key?.takeIf { it != selectedKey }?.let(onSelectedKeyChange)
    }

    fun ensureLiveSelection() {
        liveSessions.firstOrNull()?.let { onSelectedKeyChange(liveTerminalSessionKey(it.sessionId)) }
    }

    fun closeLive(surface: TerminalSurface.Live) {
        val index = liveSessions.indexOfFirst { it.sessionId == surface.session.sessionId }
        if (index !in liveSessions.indices) return
        liveSessions[index].destroy()
        liveSessions.removeAt(index)
        if (liveSessions.isEmpty()) {
            liveSessions.add(TerminalSession(context))
        }
        ensureLiveSelection()
    }

    Column(modifier.fillMaxSize().background(colors.canvas)) {
        if (!compact) {
            Row(
                Modifier.fillMaxWidth().height(36.dp).background(colors.sunken),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LazyRow(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    items(surfaces, key = { it.key }) { surface ->
                        val live = surface as? TerminalSurface.Live
                        TermTab(
                            label = surface.title,
                            subtitle = surface.subtitle,
                            selected = resolved?.key == surface.key,
                            onClick = { onSelectedKeyChange(surface.key) },
                            onClose = live?.let { { closeLive(it) } },
                        )
                    }
                }
                Box(
                    Modifier.size(36.dp).clickable {
                        val session = TerminalSession(context)
                        liveSessions.add(session)
                        onSelectedKeyChange(liveTerminalSessionKey(session.sessionId))
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "新建终端", tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        }

        when (val surface = resolved) {
            is TerminalSurface.Live -> androidx.compose.runtime.key(surface.session.sessionId) {
                TerminalPane(surface.session, Modifier.fillMaxSize(), compact = compact)
            }
            is TerminalSurface.Tool -> ToolTerminalPane(surface.binding, Modifier.fillMaxSize(), compact = compact)
            null -> Box(Modifier.fillMaxSize().background(colors.sunken), contentAlignment = Alignment.Center) {
                Text("暂无终端会话", style = AndmxTheme.typography.bodySmall, color = colors.textTertiary)
            }
        }
    }
}

@Composable
private fun ToolTerminalPane(
    binding: TerminalBindingState,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = AndmxTheme.colors
    val scroll = rememberScrollState()
    val output = binding.output.ifBlank {
        when (binding.status) {
            ToolExecutionStatus.RUNNING -> "等待输出..."
            ToolExecutionStatus.WAITING_APPROVAL -> "等待授权..."
            ToolExecutionStatus.CANCELLED -> "已取消"
            else -> "(无输出)"
        }
    }

    LaunchedEffect(binding.output, binding.updatedAt) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    Column(modifier.fillMaxSize().background(colors.sunken)) {
        if (!compact) {
            Row(
                Modifier.fillMaxWidth().height(36.dp).padding(horizontal = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Terminal, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    binding.command.ifBlank { binding.title },
                    style = AndmxTheme.typography.labelMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(bindingStatusLabel(binding), style = AndmxTheme.typography.labelSmall, color = if (binding.error) colors.warning else colors.textTertiary)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        }

        Box(
            Modifier.fillMaxSize()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                .verticalScroll(scroll),
        ) {
            Text(output, style = AndmxCodeTextStyle, color = colors.textPrimary)
        }
    }
}

@Composable
private fun TermTab(
    label: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    onClose: (() -> Unit)?,
) {
    val colors = AndmxTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = Spacing.xxs)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.canvas else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = Spacing.md, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
    ) {
        Column {
            Text(label, style = AndmxTheme.typography.labelMedium, color = if (selected) colors.textPrimary else colors.textSecondary)
            Text(subtitle, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 1)
        }
        onClose?.let {
            Spacer(Modifier.width(Spacing.xs))
            Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = it), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = colors.textTertiary, modifier = Modifier.size(12.dp))
            }
        }
    }
}

private fun bindingStatusLabel(binding: TerminalBindingState): String = when (binding.status) {
    ToolExecutionStatus.QUEUED -> "排队中"
    ToolExecutionStatus.RUNNING -> "运行中"
    ToolExecutionStatus.WAITING_APPROVAL -> "等待授权"
    ToolExecutionStatus.SUCCEEDED -> binding.exitCode?.let { "exit=$it" } ?: "完成"
    ToolExecutionStatus.FAILED -> binding.exitCode?.let { "exit=$it" } ?: "失败"
    ToolExecutionStatus.CANCELLED -> "已取消"
}
