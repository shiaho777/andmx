package com.andmx.ui2.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

object InlineParser {
    const val UrlAnnotationTag = "url"

    private val escapable = setOf('\\', '`', '*', '_', '{', '}', '[', ']', '(', ')', '#', '+', '-', '.', '!', '|', '~')

    fun parse(text: String, defaultColor: Color, linkColor: Color = defaultColor): AnnotatedString =
        buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                when {
                    text[i] == '\\' && i + 1 < text.length && text[i + 1] in escapable -> {
                        append(text[i + 1])
                        i += 2
                    }

                    text.startsWith("~~", i) -> {
                        val end = text.indexOf("~~", i + 2)
                        if (end != -1 && end > i + 2) {
                            pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = defaultColor))
                            append(text.substring(i + 2, end))
                            pop()
                            i = end + 2
                        } else {
                            append(text.substring(i))
                            break
                        }
                    }

                    text.startsWith("**", i) || text.startsWith("__", i) -> {
                        val marker = text.substring(i, i + 2)
                        val end = text.indexOf(marker, i + 2)
                        if (end != -1 && end > i + 2) {
                            pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = defaultColor))
                            append(text.substring(i + 2, end))
                            pop()
                            i = end + 2
                        } else {
                            append(text.substring(i))
                            break
                        }
                    }

                    text.startsWith("``", i) -> {
                        val end = text.indexOf("``", i + 2)
                        if (end != -1) {
                            pushStyle(
                                SpanStyle(
                                    fontFamily = FontFamily.Monospace,
                                    background = Color(0x14000000),
                                    color = defaultColor,
                                ),
                            )
                            append(text.substring(i + 2, end))
                            pop()
                            i = end + 2
                        } else {
                            append(text.substring(i))
                            break
                        }
                    }

                    text.startsWith("`", i) -> {
                        val end = text.indexOf("`", i + 1)
                        if (end != -1) {
                            pushStyle(
                                SpanStyle(
                                    fontFamily = FontFamily.Monospace,
                                    background = Color(0x14000000),
                                    color = defaultColor,
                                ),
                            )
                            append(text.substring(i + 1, end))
                            pop()
                            i = end + 1
                        } else {
                            append(text.substring(i))
                            break
                        }
                    }

                    text.startsWith("![", i) -> {
                        val mid = text.indexOf("](", i + 2)
                        val end = if (mid != -1) matchingParen(text, mid + 2) else -1
                        if (mid != -1 && end != -1) {
                            val alt = text.substring(i + 2, mid).ifBlank { "图片" }
                            val url = text.substring(mid + 2, end)
                            pushStringAnnotation(UrlAnnotationTag, url)
                            pushStyle(
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            )
                            append(alt)
                            pop()
                            pop()
                            i = end + 1
                        } else {
                            append(text[i])
                            i++
                        }
                    }

                    text.startsWith("[", i) -> {
                        val mid = text.indexOf("](", i + 1)
                        val end = if (mid != -1) matchingParen(text, mid + 2) else -1
                        if (mid != -1 && end != -1) {
                            val label = text.substring(i + 1, mid)
                            val url = text.substring(mid + 2, end)
                            pushStringAnnotation(UrlAnnotationTag, url)
                            pushStyle(
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            )
                            append(label)
                            pop()
                            pop()
                            i = end + 1
                        } else {
                            append(text[i])
                            i++
                        }
                    }

                    text[i] == '<' -> {
                        val end = text.indexOf('>', i + 1)
                        val candidate = if (end != -1) text.substring(i + 1, end) else ""
                        if (end != -1 && (candidate.startsWith("http://") || candidate.startsWith("https://")) && !candidate.contains(' ')) {
                            pushStringAnnotation(UrlAnnotationTag, candidate)
                            pushStyle(
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            )
                            append(candidate)
                            pop()
                            pop()
                            i = end + 1
                        } else {
                            append(text[i])
                            i++
                        }
                    }

                    text.startsWith("http://", i) || text.startsWith("https://", i) -> {
                        var end = i
                        while (end < text.length && !text[end].isWhitespace() && text[end] != '<') end++
                        var url = text.substring(i, end)
                        while (url.isNotEmpty() && url.last() in setOf('.', ',', ';', ':', '!', '?', ')', ']', '\'')) {
                            url = url.dropLast(1)
                            end--
                        }
                        if (url.contains('.')) {
                            pushStringAnnotation(UrlAnnotationTag, url)
                            pushStyle(
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            )
                            append(url)
                            pop()
                            pop()
                            i = end
                        } else {
                            append(text[i])
                            i++
                        }
                    }

                    text.startsWith("*", i) && !text.startsWith("**", i) -> {
                        val end = text.indexOf("*", i + 1)
                        if (end != -1 && end > i + 1 && (end + 1 >= text.length || text[end + 1] != '*')) {
                            pushStyle(SpanStyle(fontStyle = FontStyle.Italic, color = defaultColor))
                            append(text.substring(i + 1, end))
                            pop()
                            i = end + 1
                        } else {
                            append(text[i])
                            i++
                        }
                    }

                    text.startsWith("_", i) && !text.startsWith("__", i) -> {
                        val end = text.indexOf("_", i + 1)
                        val prevIsWord = i > 0 && (text[i - 1].isLetterOrDigit())
                        val nextIsWord = end != -1 && end + 1 < text.length && text[end + 1].isLetterOrDigit()
                        if (end != -1 && end > i + 1 && !(prevIsWord && nextIsWord)) {
                            pushStyle(SpanStyle(fontStyle = FontStyle.Italic, color = defaultColor))
                            append(text.substring(i + 1, end))
                            pop()
                            i = end + 1
                        } else {
                            append(text[i])
                            i++
                        }
                    }

                    else -> {
                        append(text[i])
                        i++
                    }
                }
            }
        }

    private fun matchingParen(text: String, start: Int): Int {
        var depth = 1
        var i = start
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }
}
