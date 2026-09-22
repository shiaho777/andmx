package com.andmx.ui2.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** ZCode TurnNavigator 对齐：在回合锚点（用户消息）之间跳转。 */
@Composable
fun TurnNavigator(
    turn: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (total <= 1) return
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.KeyboardArrowUp,
            "上一回合",
            Modifier.size(18.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onPrev).padding(1.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (turn > 1) 0.9f else 0.3f),
        )
        Text(
            "$turn/$total",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Icon(
            Icons.Outlined.KeyboardArrowDown,
            "下一回合",
            Modifier.size(18.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onNext).padding(1.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (turn < total) 0.9f else 0.3f),
        )
    }
}
