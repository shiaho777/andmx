package com.andmx.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Non-color design tokens: spacing scale, corner radii and elevations.
 * Kept deliberately small and consistent — the Codex look is mostly
 * whitespace + gentle rounding + barely-there shadows.
 */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}

object Radii {
    val sm = RoundedCornerShape(8.dp)
    val md = RoundedCornerShape(12.dp)
    val lg = RoundedCornerShape(16.dp)
    val xl = RoundedCornerShape(20.dp)
    val pill = RoundedCornerShape(999.dp)
}

object Elevation {
    val card = 1.dp
    val composer = 6.dp
    val popover = 10.dp
    val modal = 16.dp
}
