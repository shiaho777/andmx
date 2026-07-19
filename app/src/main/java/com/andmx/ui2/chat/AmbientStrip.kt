package com.andmx.ui2.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.agent.suggestions.AmbientSuggestions

@Composable
fun AmbientStrip(
    suggestions: List<AmbientSuggestions.Suggestion>,
    onDismiss: (String) -> Unit,
    onPick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val active = suggestions.filter { !it.dismissed }.take(3)
    if (active.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (item in active) {
            AmbientSuggestionCard(
                title = item.title,
                detail = item.detail,
                action = item.action,
                onDismiss = { onDismiss(item.id) },
                onPick = { onPick(item.action) },
            )
        }
    }
}

@Composable
private fun AmbientSuggestionCard(
    title: String,
    detail: String,
    action: String,
    onDismiss: () -> Unit,
    onPick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail.isNotBlank()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(onClick = onDismiss) {
                Text("忽略")
            }
            if (action.isNotBlank()) {
                TextButton(onClick = onPick) {
                    Text("采用")
                }
            }
        }
    }
}
