package com.andmx.ui2.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

object CodeHighlight {
    private val keywords = setOf(
        "fun", "val", "var", "if", "else", "when", "for", "while", "class", "object", "interface",
        "return", "import", "package", "public", "private", "protected", "override", "suspend",
        "def", "async", "await", "function", "const", "let", "this", "super", "new",
        "true", "false", "null", "None", "True", "False", "in", "is", "as", "try", "catch", "throw"
    )

    fun highlight(code: String, theme: CodeTheme): AnnotatedString = buildAnnotatedString {
        append(code)

        val commentRegex = Regex("//.*|#.*|/\\*[\\s\\S]*?\\*/")
        commentRegex.findAll(code).forEach { m ->
            addStyle(SpanStyle(color = theme.comment), m.range.first, m.range.last + 1)
        }

        val stringRegex = Regex("\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'|`([^`\\\\]|\\\\.)*`")
        stringRegex.findAll(code).forEach { m ->
            addStyle(SpanStyle(color = theme.string), m.range.first, m.range.last + 1)
        }

        Regex("\\b\\w+\\b").findAll(code).forEach { m ->
            if (m.value in keywords) {
                addStyle(SpanStyle(color = theme.keyword), m.range.first, m.range.last + 1)
            }
        }

        Regex("\\b(\\w+)\\s*\\(").findAll(code).forEach { m ->
            val name = m.groupValues[1]
            if (name !in keywords) {
                addStyle(SpanStyle(color = theme.function), m.range.first, m.range.first + name.length)
            }
        }

        Regex("\\b\\d+(\\.\\d+)?\\b").findAll(code).forEach { m ->
            addStyle(SpanStyle(color = theme.number), m.range.first, m.range.last + 1)
        }
    }
}
