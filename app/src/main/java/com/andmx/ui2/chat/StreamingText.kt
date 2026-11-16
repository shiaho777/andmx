package com.andmx.ui2.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andmx.ui2.markdown.MarkdownView
import com.andmx.ui2.theme.LocalMotion

object StreamingTextChunks {
    fun split(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var start = 0
        var inFence = false
        var i = 0
        while (i < text.length) {
            if (text[i] == '`' && (i == 0 || text[i - 1] == '\n') && text.startsWith("```", i)) {
                inFence = !inFence
                i += 3
                continue
            }
            if (!inFence && text[i] == '\n') {
                var end = i + 1
                while (end < text.length && text[end] == '\n') end++
                if (end - i >= 2 && end < text.length) {
                    out += text.substring(start, end)
                    start = end
                }
                i = end
            } else {
                i++
            }
        }
        if (start < text.length) out += text.substring(start)
        return out
    }
}

@Composable
fun StreamingText(
    text: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
) {
    val contentColor = if (muted) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val bodySizeSp = if (muted) 13.5f else 15f

    if (!isStreaming) {
        MarkdownView(
            markdown = text,
            modifier = modifier,
            streaming = false,
            contentColor = contentColor,
            bodySizeSp = bodySizeSp,
        )
        return
    }

    val motion = LocalMotion.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (text.isBlank()) {
            StreamingShimmer(Modifier.weight(1f))
        } else {
            val chunks = remember(text) { StreamingTextChunks.split(text) }
            Column(Modifier.weight(1f)) {
                chunks.forEachIndexed { index, chunk ->
                    val alpha = remember(index) { Animatable(0f) }
                    LaunchedEffect(index) {
                        alpha.animateTo(1f, motion.enter)
                    }
                    MarkdownView(
                        markdown = chunk,
                        modifier = Modifier.alpha(alpha.value),
                        streaming = true,
                        contentColor = contentColor,
                        bodySizeSp = bodySizeSp,
                    )
                }
            }
        }

        val infiniteTransition = rememberInfiniteTransition(label = "cursor")
        val cursorAlpha by infiniteTransition.animateFloat(
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
                    .alpha(cursorAlpha),
            )
        }
    }
}

@Composable
private fun StreamingShimmer(modifier: Modifier = Modifier) {
    val motion = LocalMotion.current
    val transition = rememberInfiniteTransition(label = "streamShimmer")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = motion.shimmerSweep,
        label = "streamShimmerSweep",
    )
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val glow = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val brush = Brush.linearGradient(
        colors = listOf(base, glow, base),
        start = Offset(sweep * 900f - 300f, 0f),
        end = Offset(sweep * 900f, 24f),
    )
    Column(
        modifier = modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(0.92f, 0.7f, 0.45f).forEach { fraction ->
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(brush),
            )
        }
    }
}