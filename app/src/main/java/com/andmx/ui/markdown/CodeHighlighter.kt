package com.andmx.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * Lightweight, language-agnostic syntax highlighter: comments, strings,
 * numbers, and a set of common keywords. Pure / testable. Not a real lexer —
 * just enough to make code blocks readable.
 */
object CodeHighlighter {

    private val keywords = setOf(
        "fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while",
        "return", "import", "package", "private", "public", "internal", "override", "suspend",
        "def", "lambda", "elif", "try", "except", "finally", "with", "as", "from", "in", "is", "not",
        "function", "const", "let", "new", "typeof", "await", "async", "export", "default",
        "true", "false", "null", "none", "nil", "void", "int", "float", "double", "string", "bool",
        "public", "static", "final", "this", "super", "struct", "enum", "switch", "case", "break", "continue",
        "echo", "then", "fi", "do", "done", "elif",
    )

    private val keyColor = Color(0xFFAD7FA8)
    private val strColor = Color(0xFF4E9A06)
    private val numColor = Color(0xFF3465A4)
    private val commentColor = Color(0xFF8A8A8E)

    fun highlight(code: String, base: Color): AnnotatedString = buildAnnotatedString {
        var i = 0
        val n = code.length
        while (i < n) {
            val c = code[i]
            when {
                // line comments
                (c == '/' && i + 1 < n && code[i + 1] == '/') || c == '#' -> {
                    val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                    withStyle(SpanStyle(color = commentColor)) { append(code.substring(i, end)) }
                    i = end
                }
                // strings
                c == '"' || c == '\'' || c == '`' -> {
                    val quote = c
                    var j = i + 1
                    while (j < n && code[j] != quote) { if (code[j] == '\\') j++; j++ }
                    val end = (j + 1).coerceAtMost(n)
                    withStyle(SpanStyle(color = strColor)) { append(code.substring(i, end)) }
                    i = end
                }
                c.isDigit() -> {
                    var j = i
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '.')) j++
                    withStyle(SpanStyle(color = numColor)) { append(code.substring(i, j)) }
                    i = j
                }
                c.isLetter() || c == '_' -> {
                    var j = i
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                    val word = code.substring(i, j)
                    if (word.lowercase() in keywords) withStyle(SpanStyle(color = keyColor)) { append(word) }
                    else withStyle(SpanStyle(color = base)) { append(word) }
                    i = j
                }
                else -> { withStyle(SpanStyle(color = base)) { append(c) }; i++ }
            }
        }
    }
}
