package com.andmx.ui2.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.ui2.settings.tr

/**
 * 0 是最新一条用户消息。贴在底部时固定为 0；离开底部后，取视口顶部那条用户消息。
 * 反向列表里索引越大越靠上。
 */
internal fun navigatorRank(
    anchors: List<Int>,
    topVisibleIndex: Int,
    atBottom: Boolean,
): Int {
    if (anchors.isEmpty() || atBottom) return 0
    val rank = anchors.indexOfLast { it <= topVisibleIndex }
    return if (rank < 0) 0 else rank
}

@Composable
fun TurnNavigator(
    turn: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    turns: List<TurnEntry> = emptyList(),
    onJumpToTurn: (Int) -> Unit = {},
) {
    if (total <= 1) return
    var expanded by remember { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.End) {
        if (expanded && turns.isNotEmpty()) {
            Column(
                Modifier
                    .padding(bottom = 6.dp)
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f))
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
            ) {
                turns.asReversed().forEach { entry ->
                    val current = entry.number == turn
                    Row(
                        Modifier
                            .clickable {
                                expanded = false
                                onJumpToTurn(entry.number)
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${entry.number}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (current) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Column(Modifier.weight(1f, fill = false)) {
                            Text(
                                entry.summary.ifBlank { tr("（空消息）") },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (current) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (entry.toolCalls > 0) {
                                Text(
                                    tr("${entry.toolCalls} 步工具"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.KeyboardArrowUp,
                tr("上一回合"),
                Modifier.size(18.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onPrev).padding(1.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (turn > 1) 0.9f else 0.3f),
            )
            Text(
                tr("回合") + " $turn/$total",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = turns.isNotEmpty()) { expanded = !expanded }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
            Icon(
                Icons.Outlined.KeyboardArrowDown,
                tr("下一回合"),
                Modifier.size(18.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onNext).padding(1.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (turn < total) 0.9f else 0.3f),
            )
        }
    }
}