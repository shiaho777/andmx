package com.andmx.ui2.terminal

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

@Immutable
data class TerminalColors(
    val background: Color,
    val foreground: Color,
    val chrome: Color,
    val chromeContent: Color,
    val chromeMuted: Color,
    val keyBackground: Color,
    val keyContent: Color,
    val inputBackground: Color,
    val cursor: Color,
    val palette: IntArray,
) {
    fun colorArgb(index: Int): Int =
        if (index in palette.indices) palette[index] else palette[0]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TerminalColors) return false
        return background == other.background &&
            foreground == other.foreground &&
            chrome == other.chrome &&
            chromeContent == other.chromeContent &&
            chromeMuted == other.chromeMuted &&
            keyBackground == other.keyBackground &&
            keyContent == other.keyContent &&
            inputBackground == other.inputBackground &&
            cursor == other.cursor &&
            palette.contentEquals(other.palette)
    }

    override fun hashCode(): Int {
        var result = background.hashCode()
        result = 31 * result + foreground.hashCode()
        result = 31 * result + chrome.hashCode()
        result = 31 * result + chromeContent.hashCode()
        result = 31 * result + chromeMuted.hashCode()
        result = 31 * result + keyBackground.hashCode()
        result = 31 * result + keyContent.hashCode()
        result = 31 * result + inputBackground.hashCode()
        result = 31 * result + cursor.hashCode()
        result = 31 * result + palette.contentHashCode()
        return result
    }
}

private val DarkPalette = intArrayOf(
    0xFFD4D4D4.toInt(), 0xFFEF5350.toInt(), 0xFF66BB6A.toInt(), 0xFFFFEE58.toInt(),
    0xFF42A5F5.toInt(), 0xFFAB47BC.toInt(), 0xFF26C6DA.toInt(), 0xFFECEFF1.toInt(),
    0xFFB0BEC5.toInt(), 0xFFEF9A9A.toInt(), 0xFFA5D6A7.toInt(), 0xFFFFF59D.toInt(),
    0xFF90CAF9.toInt(), 0xFFCE93D8.toInt(), 0xFF80DEEA.toInt(), 0xFFFFFFFF.toInt(),
)

private val LightPalette = intArrayOf(
    0xFF1F2328.toInt(), 0xFFCF222E.toInt(), 0xFF1A7F37.toInt(), 0xFF9A6700.toInt(),
    0xFF0969DA.toInt(), 0xFF8250DF.toInt(), 0xFF1B7C83.toInt(), 0xFF1F2328.toInt(),
    0xFF656D76.toInt(), 0xFFA40E26.toInt(), 0xFF116329.toInt(), 0xFF7D4E00.toInt(),
    0xFF0550AE.toInt(), 0xFF6639BA.toInt(), 0xFF117A88.toInt(), 0xFF0D1117.toInt(),
)

fun terminalColors(isDark: Boolean): TerminalColors =
    if (isDark) {
        TerminalColors(
            background = Color(0xFF171717),
            foreground = Color(0xFFE5E5E5),
            chrome = Color(0xFF1F1F1F),
            chromeContent = Color(0xFFE5E5E5),
            chromeMuted = Color(0xFFA3A3A3),
            keyBackground = Color(0xFF2A2A2A),
            keyContent = Color(0xFFD4D4D4),
            inputBackground = Color(0xFF262626),
            cursor = Color(0xFFE5E5E5),
            palette = DarkPalette,
        )
    } else {
        TerminalColors(
            background = Color(0xFFFAFAFA),
            foreground = Color(0xFF1F2328),
            chrome = Color(0xFFF3F4F6),
            chromeContent = Color(0xFF1F2328),
            chromeMuted = Color(0xFF6B7280),
            keyBackground = Color(0xFFE5E7EB),
            keyContent = Color(0xFF374151),
            inputBackground = Color(0xFFEEF0F3),
            cursor = Color(0xFF0969DA),
            palette = LightPalette,
        )
    }

@Composable
fun rememberTerminalColors(): TerminalColors {
    val surface = MaterialTheme.colorScheme.surface
    val isDark = surface.luminance() < 0.5f
    return remember(isDark) { terminalColors(isDark) }
}
