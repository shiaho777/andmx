package com.andmx.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * AndMX color palette — distilled from the Codex desktop app design language.
 *
 * Philosophy: near-monochrome canvas, a single cool accent (Codex blue),
 * and one reserved semantic warning hue (amber) that is used *only* for
 * permission / risk affordances. Everything else is grayscale + whitespace.
 */
object AndmxPalette {

    // ---- Codex Blue (primary accent) ----
    val Blue = Color(0xFF339CFF)
    val BluePressed = Color(0xFF2563EB)
    val BlueSoft = Color(0x14339CFF) // 8% tint for selected backgrounds

    // ---- Amber (permission / risk semantic only) ----
    val Amber = Color(0xFFE8730E)
    val AmberSoft = Color(0x14E8730E)

    // ---- Neutrals ----
    val White = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1A1A1F)

    // Light scheme neutrals
    val LightCanvas = Color(0xFFFFFFFF)
    val LightSidebar = Color(0xFFF6F6F4)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceElevated = Color(0xFFFFFFFF)
    val LightSunken = Color(0xFFF1F1EF)
    val LightBorder = Color(0x14000000)        // ~8% black hairline
    val LightBorderStrong = Color(0x24000000)
    val LightTextPrimary = Color(0xFF1A1A1F)
    val LightTextSecondary = Color(0xFF8A8A8E)
    val LightTextTertiary = Color(0xFFB4B4B8)
    val LightCodeBg = Color(0xFFF1F1EF)
    val LightHover = Color(0x0A000000)         // 4% black row-hover/press
    val LightSelected = Color(0x12000000)

    // Dark scheme neutrals
    val DarkCanvas = Color(0xFF1A1A1F)
    val DarkSidebar = Color(0xFF202027)
    val DarkSurface = Color(0xFF22222A)
    val DarkSurfaceElevated = Color(0xFF2A2A33)
    val DarkSunken = Color(0xFF16161A)
    val DarkBorder = Color(0x1FFFFFFF)
    val DarkBorderStrong = Color(0x33FFFFFF)
    val DarkTextPrimary = Color(0xFFECECEE)
    val DarkTextSecondary = Color(0xFF9A9AA2)
    val DarkTextTertiary = Color(0xFF66666E)
    val DarkCodeBg = Color(0xFF16161A)
    val DarkHover = Color(0x0FFFFFFF)
    val DarkSelected = Color(0x1AFFFFFF)
}

/**
 * Full set of semantic color roles used across AndMX surfaces.
 * Exposed via [LocalAndmxColors] so any composable can read the active scheme.
 */
data class AndmxColors(
    val isDark: Boolean,
    // surfaces
    val canvas: Color,
    val sidebar: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val sunken: Color,
    val codeBackground: Color,
    // lines
    val border: Color,
    val borderStrong: Color,
    // text
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    // interaction
    val hover: Color,
    val selected: Color,
    // accent / semantic
    val accent: Color,
    val accentPressed: Color,
    val accentSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    // send button (gray when idle, ink-filled when actionable)
    val sendIdle: Color,
    val sendActive: Color,
    val onAccent: Color,
)

fun lightAndmxColors(accent: Color = AndmxPalette.Blue): AndmxColors = AndmxColors(
    isDark = false,
    canvas = AndmxPalette.LightCanvas,
    sidebar = AndmxPalette.LightSidebar,
    surface = AndmxPalette.LightSurface,
    surfaceElevated = AndmxPalette.LightSurfaceElevated,
    sunken = AndmxPalette.LightSunken,
    codeBackground = AndmxPalette.LightCodeBg,
    border = AndmxPalette.LightBorder,
    borderStrong = AndmxPalette.LightBorderStrong,
    textPrimary = AndmxPalette.LightTextPrimary,
    textSecondary = AndmxPalette.LightTextSecondary,
    textTertiary = AndmxPalette.LightTextTertiary,
    hover = AndmxPalette.LightHover,
    selected = AndmxPalette.LightSelected,
    accent = accent,
    accentPressed = AndmxPalette.BluePressed,
    accentSoft = AndmxPalette.BlueSoft,
    warning = AndmxPalette.Amber,
    warningSoft = AndmxPalette.AmberSoft,
    sendIdle = AndmxPalette.LightTextTertiary,
    sendActive = AndmxPalette.Ink,
    onAccent = AndmxPalette.White,
)

fun darkAndmxColors(accent: Color = AndmxPalette.Blue): AndmxColors = AndmxColors(
    isDark = true,
    canvas = AndmxPalette.DarkCanvas,
    sidebar = AndmxPalette.DarkSidebar,
    surface = AndmxPalette.DarkSurface,
    surfaceElevated = AndmxPalette.DarkSurfaceElevated,
    sunken = AndmxPalette.DarkSunken,
    codeBackground = AndmxPalette.DarkCodeBg,
    border = AndmxPalette.DarkBorder,
    borderStrong = AndmxPalette.DarkBorderStrong,
    textPrimary = AndmxPalette.DarkTextPrimary,
    textSecondary = AndmxPalette.DarkTextSecondary,
    textTertiary = AndmxPalette.DarkTextTertiary,
    hover = AndmxPalette.DarkHover,
    selected = AndmxPalette.DarkSelected,
    accent = accent,
    accentPressed = AndmxPalette.BluePressed,
    accentSoft = AndmxPalette.BlueSoft,
    warning = AndmxPalette.Amber,
    warningSoft = AndmxPalette.AmberSoft,
    sendIdle = AndmxPalette.DarkTextTertiary,
    sendActive = AndmxPalette.DarkTextPrimary,
    onAccent = AndmxPalette.White,
)
