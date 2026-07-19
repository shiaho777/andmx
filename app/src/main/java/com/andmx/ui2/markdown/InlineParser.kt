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
    fun parse(text: String, defaultColor: Color): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = defaultColor))
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

                text.startsWith("*", i) && !text.startsWith("**", i) -> {
                    val end = text.indexOf("*", i + 1)
                    if (end != -1 && (end + 1 >= text.length || text[end + 1] != '*')) {
                        pushStyle(SpanStyle(fontStyle = FontStyle.Italic, color = defaultColor))
                        append(text.substring(i + 1, end))
                        pop()
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }

                text.startsWith("[", i) -> {
                    val mid = text.indexOf("](", i + 1)
                    val end = if (mid != -1) text.indexOf(")", mid + 2) else -1
                    if (mid != -1 && end != -1) {
                        val label = text.substring(i + 1, mid)
                        pushStyle(
                            SpanStyle(
                                color = defaultColor.copy(alpha = 0.95f),
                                textDecoration = TextDecoration.Underline,
                            ),
                        )
                        append(label)
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
}
