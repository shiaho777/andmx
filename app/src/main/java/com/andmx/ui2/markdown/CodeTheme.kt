package com.andmx.ui2.markdown

import androidx.compose.ui.graphics.Color

data class CodeTheme(
    val id: String,
    val name: String,
    val dark: Boolean,
    val background: Color,
    val foreground: Color,
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val function: Color
)

object CodeThemes {
    val GithubDark = CodeTheme(
        id = "github-dark", name = "GitHub Dark", dark = true,
        background = Color(0xFF24292E), foreground = Color(0xFFE1E4E8),
        keyword = Color(0xFFF97583), string = Color(0xFF9ECBFF),
        comment = Color(0xFF6A737D), number = Color(0xFF79B8FF),
        function = Color(0xFFB392F0)
    )
    val GithubLight = CodeTheme(
        id = "github-light", name = "GitHub Light", dark = false,
        background = Color(0xFFFFFFFF), foreground = Color(0xFF1F2328),
        keyword = Color(0xFFCF222E), string = Color(0xFF0A3069),
        comment = Color(0xFF6E7781), number = Color(0xFF0550AE),
        function = Color(0xFF8250DF)
    )
    val OneDarkPro = CodeTheme(
        id = "one-dark-pro", name = "One Dark Pro", dark = true,
        background = Color(0xFF282C34), foreground = Color(0xFFABB2BF),
        keyword = Color(0xFFC678DD), string = Color(0xFF98C379),
        comment = Color(0xFF5C6370), number = Color(0xFFD19A66),
        function = Color(0xFF61AFEF)
    )
    val Dracula = CodeTheme(
        id = "dracula", name = "Dracula", dark = true,
        background = Color(0xFF282A36), foreground = Color(0xFFF8F8F2),
        keyword = Color(0xFFFF79C6), string = Color(0xFFF1FA8C),
        comment = Color(0xFF6272A4), number = Color(0xFFBD93F9),
        function = Color(0xFF50FA7B)
    )
    val Monokai = CodeTheme(
        id = "monokai", name = "Monokai", dark = true,
        background = Color(0xFF272822), foreground = Color(0xFFF8F8F2),
        keyword = Color(0xFFF92672), string = Color(0xFFE6DB74),
        comment = Color(0xFF75715E), number = Color(0xFFAE81FF),
        function = Color(0xFFA6E22E)
    )
    val Nord = CodeTheme(
        id = "nord", name = "Nord", dark = true,
        background = Color(0xFF2E3440), foreground = Color(0xFFD8DEE9),
        keyword = Color(0xFF81A1C1), string = Color(0xFFA3BE8C),
        comment = Color(0xFF616E88), number = Color(0xFFB48EAD),
        function = Color(0xFF88C0D0)
    )
    val SolarizedLight = CodeTheme(
        id = "solarized-light", name = "Solarized Light", dark = false,
        background = Color(0xFFFDF6E3), foreground = Color(0xFF657B83),
        keyword = Color(0xFF859900), string = Color(0xFF2AA198),
        comment = Color(0xFF93A1A1), number = Color(0xFFD33682),
        function = Color(0xFF268BD2)
    )
    val VitesseLight = CodeTheme(
        id = "vitesse-light", name = "Vitesse Light", dark = false,
        background = Color(0xFFFFFFFF), foreground = Color(0xFF393A34),
        keyword = Color(0xFFAB5959), string = Color(0xFFB56959),
        comment = Color(0xFFA0ADA0), number = Color(0xFF2F798A),
        function = Color(0xFF59873A)
    )

    val darkThemes = listOf(OneDarkPro, GithubDark, Dracula, Monokai, Nord)
    val lightThemes = listOf(GithubLight, VitesseLight, SolarizedLight)
    val all = darkThemes + lightThemes

    fun byId(id: String): CodeTheme = all.firstOrNull { it.id == id } ?: OneDarkPro
}
