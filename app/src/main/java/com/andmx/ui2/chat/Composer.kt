package com.andmx.ui2.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.andmx.agent.SlashCommands

data class Attachment(val name: String, val uri: String)

@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    isLoading: Boolean,
    onStop: () -> Unit,
    attachments: List<Attachment> = emptyList(),
    onAddAttachment: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val slashSuggestions = remember(value) {
        if (value.startsWith("/") && !value.contains(' ')) SlashCommands.suggestions(value, 5)
        else emptyList()
    }

    Column(modifier = modifier) {
        AnimatedVisibility(visible = slashSuggestions.isNotEmpty()) {
            SlashSuggestionList(
                suggestions = slashSuggestions,
                onPick = { onValueChange(SlashCommands.complete(it)) }
            )
        }

        Surface(
            tonalElevation = 3.dp,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(8.dp)) {
                if (attachments.isNotEmpty()) {
                    AttachmentRow(attachments, onRemoveAttachment)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    IconButton0(
                        icon = Icons.Outlined.Add,
                        desc = "添加附件",
                        onClick = onAddAttachment
                    )
                    ComposerInput(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.weight(1f)
                    )
                    SendStopButton(
                        isLoading = isLoading,
                        canSend = value.isNotBlank() || attachments.isNotEmpty(),
                        onSend = onSend,
                        onStop = onStop
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
        if (value.isEmpty()) {
            Text(
                "输入消息，/ 触发命令…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            maxLines = 6,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 24.dp, max = 140.dp)
        )
    }
}

@Composable
private fun SendStopButton(
    isLoading: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    val bg = if (isLoading || canSend) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant
    val tint = if (isLoading || canSend) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .padding(4.dp)
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(enabled = isLoading || canSend) {
                if (isLoading) onStop() else onSend()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (isLoading) Icons.Outlined.Stop else Icons.AutoMirrored.Filled.Send,
            contentDescription = if (isLoading) "停止生成" else "发送",
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun IconButton0(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(4.dp)
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desc, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun AttachmentRow(attachments: List<Attachment>, onRemove: (Int) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(attachments.size) { i ->
            val att = attachments[i]
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    att.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(
                    Modifier.padding(start = 4.dp).size(18.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { onRemove(i) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Close, "移除", Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun SlashSuggestionList(
    suggestions: List<SlashCommands.Spec>,
    onPick: (SlashCommands.Spec) -> Unit
) {
    Surface(
        tonalElevation = 4.dp,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            suggestions.forEach { spec ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(spec) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        spec.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                    Text(
                        spec.desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        }
    }
}
