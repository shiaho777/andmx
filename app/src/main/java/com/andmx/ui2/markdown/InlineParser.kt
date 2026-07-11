package com.andmx.ui2.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

object InlineParser {
    fun parse(text: String, defaultColor: Color): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        val boldText = text.substring(i + 2, end)
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        append(boldText)
                        pop()
                        i = end + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }
                
                text.startsWith("*", i) && !text.startsWith("**", i) -> {
                    val end = text.indexOf("*", i + 1)
                    if (end != -1 && !text.startsWith("*", end + 1)) {
                        val italicText = text.substring(i + 1, end)
                        pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        append(italicText)
                        pop()
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                
                text.startsWith("`", i) -> {
                    val end = text.indexOf("`", i + 1)
                    if (end != -1) {
                        val codeText = text.substring(i + 1, end)
                        pushStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color(0x14000000)
                        ))
                        append(codeText)
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
