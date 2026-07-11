package com.andmx.ui2.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private data class ThemeOption(val value: String, val label: String, val swatch: List<Color>)

@Composable
fun ThemeModeSelector(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = listOf(
        ThemeOption("light", "浅色", listOf(Color(0xFFFFFFFF), Color(0xFFF1F1EF), Color(0xFF1A1A1F))),
        ThemeOption("dark", "深色", listOf(Color(0xFF1A1A1F), Color(0xFF2A2A33), Color(0xFFECECEE))),
        ThemeOption("system", "系统", listOf(Color(0xFFFFFFFF), Color(0xFF1A1A1F), Color(0xFF9A9AA2)))
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        options.forEach { option ->
            ThemeSwatchCard(
                option = option,
                selected = selected == option.value,
                onClick = { onSelect(option.value) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ThemeSwatchCard(
    option: ThemeOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant,
        label = "themeBorder"
    )
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(if (selected) 2.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(option.swatch[0])
                .padding(6.dp),
            contentAlignment = Alignment.BottomStart
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(
                    Modifier
                        .size(width = 28.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(option.swatch[2])
                )
                Box(
                    Modifier
                        .size(width = 18.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(option.swatch[1])
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                option.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (selected) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = "已选",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
