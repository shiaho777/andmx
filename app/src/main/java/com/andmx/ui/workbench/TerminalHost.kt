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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.andmx.term.TerminalSession
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Spacing

/**
 * Hosts multiple parallel shell sessions with a tab strip — like Codex's
 * terminal. Each tab is an independent proot shell; non-active tabs keep
 * running in the background.
 */
@Composable
fun TerminalHost(
    sessions: androidx.compose.runtime.snapshots.SnapshotStateList<TerminalSession>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AndmxTheme.colors
    val context = LocalContext.current

    fun close(i: Int) {
        if (i !in sessions.indices) return
        sessions[i].destroy()
        sessions.removeAt(i)
        if (sessions.isEmpty()) sessions.add(TerminalSession(context))
        onSelectedIndexChange(selectedIndex.coerceIn(0, sessions.size - 1))
    }

    Column(modifier.fillMaxSize().background(colors.canvas)) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).background(colors.sunken),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                itemsIndexed(sessions) { i, _ ->
                    TermTab("shell ${i + 1}", selectedIndex == i, { onSelectedIndexChange(i) }, { close(i) })
                }
            }
            Box(
                Modifier.size(36.dp).clickable {
                    sessions.add(TerminalSession(context))
                    onSelectedIndexChange(sessions.lastIndex)
                },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "新建终端", tint = colors.textSecondary, modifier = Modifier.size(16.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))

        if (sessions.isNotEmpty()) {
            val s = sessions[selectedIndex.coerceIn(0, sessions.size - 1)]
            // key on identity so each tab keeps its own pane state
            androidx.compose.runtime.key(s) {
                TerminalPane(s, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun TermTab(label: String, selected: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    val colors = AndmxTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = Spacing.xxs)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.canvas else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = Spacing.md, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
    ) {
        Text(label, style = AndmxTheme.typography.labelMedium, color = if (selected) colors.textPrimary else colors.textSecondary)
        Spacer(Modifier.width(Spacing.xs))
        Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = colors.textTertiary, modifier = Modifier.size(12.dp))
        }
    }
}
