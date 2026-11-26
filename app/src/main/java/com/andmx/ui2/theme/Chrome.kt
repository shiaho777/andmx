package com.andmx.ui2.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

object Chrome {
    val gutter = 16.dp
    val maxReadingWidth = 720.dp
    val tile = RoundedCornerShape(6.dp)
    val control = RoundedCornerShape(8.dp)
    val inset = RoundedCornerShape(10.dp)
    val panel = RoundedCornerShape(16.dp)
    val bubble = RoundedCornerShape(16.dp)
    val sheet = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
}

@Composable
fun Modifier.blockSurface(
    color: Color = MaterialTheme.colorScheme.surface,
    shape: Shape = Chrome.panel,
    outline: Color = MaterialTheme.colorScheme.outlineVariant,
): Modifier = clip(shape).background(color).border(1.dp, outline, shape)
