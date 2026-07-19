package com.andmx.ui2.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun ReasoningCard(item: ReasoningItem) {
    var expanded by remember(item.id) { mutableStateOf(item.isStreaming) }
    var userToggled by remember(item.id) { mutableStateOf(false) }
    var startedAt by remember(item.id) { mutableLongStateOf(0L) }
    var durationSec by remember(item.id) { mutableStateOf<Int?>(null) }
    var contentMounted by remember(item.id) { mutableStateOf(item.isStreaming) }

    LaunchedEffect(item.isStreaming) {
        if (item.isStreaming) {
            if (startedAt == 0L) startedAt = System.currentTimeMillis()
            durationSec = null
            if (!userToggled) expanded = true
        } else {
            if (startedAt > 0L) {
                durationSec = ((System.currentTimeMillis() - startedAt) / 1000L)
                    .toInt()
                    .coerceAtLeast(1)
            } else {
                durationSec = durationSec ?: 1
            }
            if (!userToggled) {
                delay(260)
                expanded = false
            }
        }
    }

    LaunchedEffect(expanded) {
        if (expanded) {
            contentMounted = true
        } else {
            delay(200)
            if (!expanded) contentMounted = false
        }
    }

    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(180),
        label = "thinkChevron",
    )

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 1.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    userToggled = true
                    expanded = !expanded
                }
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Psychology,
                null,
                Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            )
            Spacer(Modifier.width(8.dp))
            if (item.isStreaming) {
                ThinkingLabel()
            } else {
                val sec = durationSec
                Text(
                    text = when {
                        sec == null -> "已思考"
                        sec < 2 -> "已思考 · 片刻"
                        else -> "已思考 · ${sec}s"
                    },
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                null,
                Modifier
                    .padding(start = 6.dp)
                    .size(16.dp)
                    .rotate(chevronRotation)
                    .alpha(if (expanded || item.isStreaming) 0.9f else 0.4f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }

        AnimatedVisibility(
            visible = expanded && contentMounted,
            enter = expandVertically(animationSpec = tween(180)) + fadeIn(tween(140)),
            exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(120)),
        ) {
            val scroll = rememberScrollState()
            LaunchedEffect(item.content, item.isStreaming) {
                if (item.isStreaming && item.content.isNotEmpty()) {
                    scroll.animateScrollTo(scroll.maxValue)
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 23.dp, top = 1.dp, bottom = 4.dp)
                    .heightIn(max = 280.dp)
                    .verticalScroll(scroll),
            ) {
                StreamingText(
                    text = item.content.ifBlank { if (item.isStreaming) "…" else "" },
                    isStreaming = item.isStreaming,
                    muted = true,
                )
            }
        }
    }
}

@Composable
private fun ThinkingLabel() {
    val infinite = rememberInfiniteTransition(label = "think-grad")
    val shift by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "think-shift",
    )
    val c1 = MaterialTheme.colorScheme.primary
    val c2 = MaterialTheme.colorScheme.tertiary
    val c3 = MaterialTheme.colorScheme.secondary
    val brush = Brush.linearGradient(
        colors = listOf(c1, c2, c3, c1),
        start = Offset(shift * 240f, 0f),
        end = Offset(shift * 240f + 180f, 40f),
    )
    Text(
        text = "思考中",
        style = TextStyle(
            brush = brush,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        ),
    )
}
