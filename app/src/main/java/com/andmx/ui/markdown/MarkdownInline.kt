package com.andmx.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Parses inline Markdown (bold, italic, code, links) into an AnnotatedString.
 * Pure and unit-testable.
 */
object MarkdownInline {

    fun parse(
        text: String,
        codeBg: Color = Color(0x14000000),
        codeFg: Color = Color(0xFF1A1A1F),
        linkColor: Color = Color(0xFF339CFF),
    ): AnnotatedString = buildInline(text, codeBg, codeFg, linkColor)

    private fun buildInline(text: String, codeBg: Color, codeFg: Color, linkColor: Color): AnnotatedString =
        AnnotatedString.Builder().apply {
            var i = 0
            val n = text.length
            while (i < n) {
                when {
                    text[i] == '`' -> {
                        val close = text.indexOf('`', i + 1)
                        if (close > i) {
                            pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, color = codeFg))
                            append(text.substring(i + 1, close)); pop(); i = close + 1
                        } else { append(text[i]); i++ }
                    }
                    text.startsWith("**", i) -> {
                        val close = text.indexOf("**", i + 2)
                        if (close > i) {
                            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                            append(text.substring(i + 2, close)); pop(); i = close + 2
                        } else { append(text[i]); i++ }
                    }
                    (text[i] == '*' || text[i] == '_') -> {
                        val close = text.indexOf(text[i], i + 1)
                        if (close > i) {
                            pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                            append(text.substring(i + 1, close)); pop(); i = close + 1
                        } else { append(text[i]); i++ }
                    }
                    text[i] == '[' -> {
                        val mid = text.indexOf("](", i)
                        val end = if (mid > i) text.indexOf(')', mid + 2) else -1
                        if (mid > i && end > mid) {
                            val label = text.substring(i + 1, mid)
                            pushStyle(SpanStyle(color = linkColor))
                            append(label); pop(); i = end + 1
                        } else { append(text[i]); i++ }
                    }
                    else -> { append(text[i]); i++ }
                }
            }
        }.toAnnotatedString()
}
