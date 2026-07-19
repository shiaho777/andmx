package com.andmx.ui2.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val ZCodeBrand = Color(0xFF0EA5E9)
val ZCodeBrandSoft = Color(0xFFE0F2FE)
val ZCodeSuccess = Color(0xFF46BF72)
val ZCodeWarning = Color(0xFFFF8A30)
val ZCodeDestructive = Color(0xFFFF5C5C)

private val ZCodeBgLight = Color(0xFFF8F8F8)
private val ZCodeSidebarLight = Color(0xFFF0F0F0)
private val ZCodeWinAltLight = Color(0xFFECECEE)
private val ZCodeFgLight = Color(0xFF262626)
private val ZCodeFgSubtleLight = Color(0x99262626)
private val ZCodeBorderLight = Color(0x1A0D0D0D)

private val ZCodeBgDark = Color(0xFF161616)
private val ZCodeHeaderDark = Color(0xFF202020)
private val ZCodeCardDark = Color(0xFF2B2B2B)
private val ZCodeSecondaryDark = Color(0xFF363636)
private val ZCodeFgDark = Color(0xFFD4D4D4)
private val ZCodeFgSubtleDark = Color(0x99D4D4D4)
private val ZCodeBorderDark = Color(0x1AFFFFFF)
private val ZCodeSurfaceDark = Color(0x0DFFFFFF)
private val ZCodeSurfaceHoverDark = Color(0x1AFFFFFF)

private val LightColors = lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE5E5E5),
    onPrimaryContainer = Color(0xFF0D0D0D),
    secondary = Color(0xFF525252),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0x0D0D0D0D),
    onSecondaryContainer = Color(0xFF171717),
    tertiary = Color(0xFF404040),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = ZCodeWinAltLight,
    onTertiaryContainer = Color(0xFF171717),
    background = ZCodeBgLight,
    onBackground = ZCodeFgLight,
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0D0D0D),
    surfaceVariant = ZCodeSidebarLight,
    onSurfaceVariant = Color(0xFF525252),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = ZCodeBgLight,
    surfaceContainer = ZCodeSidebarLight,
    surfaceContainerHigh = ZCodeWinAltLight,
    surfaceContainerHighest = Color(0xFFE5E5E5),
    outline = ZCodeBorderLight,
    outlineVariant = Color(0x140D0D0D),
    inverseSurface = Color(0xFF171717),
    inverseOnSurface = Color(0xFFF8F8F8),
    inversePrimary = Color(0xFFFFFFFF),
    error = Color(0xFFE03131),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    primaryContainer = ZCodeSecondaryDark,
    onPrimaryContainer = Color(0xFFF8F8F8),
    secondary = Color(0xFFADADAD),
    onSecondary = Color(0xFF161616),
    secondaryContainer = ZCodeSurfaceHoverDark,
    onSecondaryContainer = Color(0xFFE5E5E5),
    tertiary = Color(0xFFD4D4D4),
    onTertiary = Color(0xFF161616),
    tertiaryContainer = ZCodeHeaderDark,
    onTertiaryContainer = Color(0xFFE5E5E5),
    background = ZCodeBgDark,
    onBackground = ZCodeFgDark,
    surface = ZCodeHeaderDark,
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = ZCodeCardDark,
    onSurfaceVariant = ZCodeFgSubtleDark,
    surfaceContainerLowest = Color(0xFF121212),
    surfaceContainerLow = ZCodeBgDark,
    surfaceContainer = ZCodeHeaderDark,
    surfaceContainerHigh = ZCodeCardDark,
    surfaceContainerHighest = ZCodeSecondaryDark,
    outline = ZCodeBorderDark,
    outlineVariant = Color(0x14FFFFFF),
    inverseSurface = Color(0xFFE5E5E5),
    inverseOnSurface = Color(0xFF161616),
    inversePrimary = Color(0xFF0D0D0D),
    error = ZCodeDestructive,
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
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
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
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

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
