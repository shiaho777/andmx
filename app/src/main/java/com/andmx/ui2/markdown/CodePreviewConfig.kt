package com.andmx.ui2.markdown

import androidx.compose.runtime.staticCompositionLocalOf

data class CodePreviewConfig(
    val lightTheme: String = "github-light",
    val darkTheme: String = "one-dark-pro",
    val showLineNumbers: Boolean = false,
    val wrapLongLines: Boolean = false,
    val fontSize: Int = 13
) {
    fun themeFor(dark: Boolean): CodeTheme =
        CodeThemes.byId(if (dark) darkTheme else lightTheme)
}

val LocalCodePreviewConfig = staticCompositionLocalOf { CodePreviewConfig() }
