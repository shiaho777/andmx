package com.andmx.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * [LocalAndmxColors] gives every composable access to the full semantic
 * palette (sidebar, borders, accent-soft, warning, etc.) that Material 3's
 * [androidx.compose.material3.ColorScheme] alone cannot express.
 */
val LocalAndmxColors: ProvidableCompositionLocal<AndmxColors> =
    staticCompositionLocalOf { lightAndmxColors() }

/** Convenience accessors so call sites read `AndmxTheme.colors` / `.typography`. */
object AndmxTheme {
    val colors: AndmxColors
        @Composable get() = LocalAndmxColors.current
    val typography: Typography
        @Composable get() = MaterialTheme.typography
}

@Composable
fun AndmxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: Color = AndmxPalette.Blue,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) darkAndmxColors(accent) else lightAndmxColors(accent)

    // Bridge our palette into a Material3 ColorScheme so M3 components inherit it.
    val m3 = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.canvas,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.sunken,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.borderStrong,
            outlineVariant = colors.border,
            error = colors.warning,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.canvas,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.sunken,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.borderStrong,
            outlineVariant = colors.border,
            error = colors.warning,
        )
    }

    CompositionLocalProvider(
        LocalAndmxColors provides colors,
        LocalContentColor provides colors.textPrimary,
    ) {
        MaterialTheme(
            colorScheme = m3,
            typography = AndmxTypography,
            content = content,
        )
    }
}
