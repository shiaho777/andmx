package com.andmx.ui2.chat

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andmx.ui2.markdown.MarkdownView

@Composable
fun StreamingText(
    text: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
    ) {
        MarkdownView(
            markdown = text,
            modifier = Modifier.weight(1f, fill = text.isNotBlank()),
            streaming = isStreaming,
            contentColor = if (muted) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            bodySizeSp = if (muted) 13.5f else 15f,
        )

        if (isStreaming) {
            val motion = com.andmx.ui2.theme.LocalMotion.current
            val infiniteTransition = rememberInfiniteTransition(label = "cursor")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.18f,
                targetValue = 1f,
                animationSpec = motion.cursorPulse,
                label = "cursorAlpha",
            )
            DisableSelection {
                Text(
                    text = "▍",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = if (muted) {
                            com.andmx.ui2.theme.AndMXType.chatBodyMuted.fontSize
                        } else {
                            com.andmx.ui2.theme.AndMXType.chatBody.fontSize
                        },
                        lineHeight = if (muted) {
                            com.andmx.ui2.theme.AndMXType.chatBodyMuted.lineHeight
                        } else {
                            com.andmx.ui2.theme.AndMXType.chatBody.lineHeight
                        },
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 1.dp, bottom = 1.dp)
                        .alpha(alpha),
                )
            }
        }
    }
}
