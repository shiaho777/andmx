package com.andmx.ui2.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val ZCodeSuccess = Color(0xFF46BF72)
val ZCodeWarning = Color(0xFFFF8A30)
val ZCodeDestructive = Color(0xFFFF5C5C)

private val WorkspaceInk = Color(0xFF0D0D0D)
private val WorkspaceSelection = Color(0xFFEAEAEA)
private val WorkspaceAccent = Color(0xFF0066DD)
private val WorkspaceCanvasLight = Color(0xFFF8F8F8)
private val WorkspaceCanvasDark = Color(0xFF161616)
private val WorkspaceCardDark = Color(0xFF2B2B2B)

private val LightColors = lightColorScheme(
    primary = WorkspaceInk,
    onPrimary = Color.White,
    primaryContainer = WorkspaceSelection,
    onPrimaryContainer = WorkspaceInk,
    secondary = Color(0xFF28704D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4F1E9),
    onSecondaryContainer = Color(0xFF153F2A),
    tertiary = Color(0xFF79551F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF8EEE0),
    onTertiaryContainer = Color(0xFF513713),
    background = WorkspaceCanvasLight,
    onBackground = WorkspaceInk,
    surface = Color.White,
    onSurface = WorkspaceInk,
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF555555),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = WorkspaceCanvasLight,
    surfaceContainer = Color(0xFFF0F0F0),
    surfaceContainerHigh = Color(0xFFECECEE),
    surfaceContainerHighest = Color(0xFFE5E5E5),
    outline = Color(0xFF767676),
    outlineVariant = Color(0xFFDEDEDE),
    surfaceTint = WorkspaceInk,
    inverseSurface = Color(0xFF262626),
    inverseOnSurface = Color(0xFFF8F8F8),
    inversePrimary = Color(0xFFF2F2F2),
    error = Color(0xFFAC2E35),
    onError = Color.White,
    errorContainer = Color(0xFFF8E5E6),
    onErrorContainer = Color(0xFF771B24),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF2F2F2),
    onPrimary = WorkspaceInk,
    primaryContainer = Color(0xFF343434),
    onPrimaryContainer = Color(0xFFF2F2F2),
    secondary = Color(0xFF9AD0AC),
    onSecondary = Color(0xFF143621),
    secondaryContainer = Color(0xFF263D2D),
    onSecondaryContainer = Color(0xFFC4E7CE),
    tertiary = Color(0xFFDCBD8D),
    onTertiary = Color(0xFF493416),
    tertiaryContainer = Color(0xFF413429),
    onTertiaryContainer = Color(0xFFF4D9AE),
    background = WorkspaceCanvasDark,
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF202020),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = WorkspaceCardDark,
    onSurfaceVariant = Color(0xFFB5B5B5),
    surfaceContainerLowest = Color(0xFF121212),
    surfaceContainerLow = WorkspaceCanvasDark,
    surfaceContainer = Color(0xFF202020),
    surfaceContainerHigh = WorkspaceCardDark,
    surfaceContainerHighest = Color(0xFF363636),
    outline = Color(0xFF909090),
    outlineVariant = Color(0xFF3C3C3C),
    surfaceTint = Color(0xFFF2F2F2),
    inverseSurface = Color(0xFFEAEAEA),
    inverseOnSurface = WorkspaceInk,
    inversePrimary = WorkspaceInk,
    error = Color(0xFFF4ADB0),
    onError = Color(0xFF54171C),
    errorContainer = Color(0xFF4E292C),
    onErrorContainer = Color(0xFFFBD9DC),
)

@Composable
internal fun workspaceLinkColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF8BC1FF) else WorkspaceAccent

private val WorkspaceShapes = Shapes(
    extraSmall = Chrome.tile,
    small = Chrome.control,
    medium = Chrome.inset,
    large = Chrome.panel,
    extraLarge = Chrome.sheet,
)

private fun ColorScheme.withWallpaper(wallpaper: ColorScheme): ColorScheme = copy(
    background = lerp(background, wallpaper.background, 0.24f),
    surface = lerp(surface, wallpaper.surface, 0.24f),
    surfaceContainerLow = lerp(surfaceContainerLow, wallpaper.surfaceContainerLow, 0.24f),
    surfaceContainer = lerp(surfaceContainer, wallpaper.surfaceContainer, 0.24f),
    surfaceContainerHigh = lerp(surfaceContainerHigh, wallpaper.surfaceContainerHigh, 0.24f),
    surfaceContainerHighest = lerp(surfaceContainerHighest, wallpaper.surfaceContainerHighest, 0.24f),
)

@Composable
fun AndMX2Theme(
    themeMode: String = "system",
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val base = if (darkTheme) DarkColors else LightColors
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        base.withWallpaper(if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context))
    } else {
        base
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalMotion provides rememberMotion()) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AndMXTypography,
            shapes = WorkspaceShapes,
            content = content,
        )
    }
}
