package com.andmx.ui2.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MarkdownView(
    markdown: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    contentColor: Color = Color.Unspecified,
    bodySizeSp: Float = 0f,
) {
    val blocks = remember(markdown) { MarkdownEngine.parse(markdown) }
    val isDark = isSystemInDarkTheme()
    val textColor = if (contentColor == Color.Unspecified) {
        MaterialTheme.colorScheme.onSurface
    } else {
        contentColor
    }
    val mutedColor = textColor.copy(alpha = 0.82f)
    val bodyStyle = if (bodySizeSp > 0f) {
        MaterialTheme.typography.bodyLarge.copy(
            fontSize = bodySizeSp.sp,
            lineHeight = (bodySizeSp + 7f).sp,
        )
    } else {
        MaterialTheme.typography.bodyLarge
    }
    val gap = if (streaming) 5.dp else 8.dp

    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(modifier.height(gap))

            when (block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineLarge
                        2 -> MaterialTheme.typography.headlineMedium
                        3 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    }
                    Text(
                        text = InlineParser.parse(block.text, textColor),
                        style = style.copy(fontWeight = FontWeight.Bold),
                        color = textColor,
                    )
                }

                is MdBlock.Paragraph -> {
                    Text(
                        text = InlineParser.parse(block.text, textColor),
                        style = bodyStyle,
                        color = textColor,
                    )
                }

                is MdBlock.Code -> {
                    CodeBlock(
                        code = block.code,
                        language = block.lang,
                        isDark = isDark,
                        lightweight = streaming,
                    )
                }

                is MdBlock.List -> {
                    Column {
                        block.items.forEachIndexed { i, item ->
                            Row(Modifier.padding(vertical = 1.dp)) {
                                Text(
                                    text = if (block.ordered) "${i + 1}. " else "• ",
                                    style = bodyStyle,
                                    color = mutedColor,
                                )
                                Text(
                                    text = InlineParser.parse(item, textColor),
                                    style = bodyStyle,
                                    color = textColor,
                                )
                            }
                        }
                    }
                }

                is MdBlock.Quote -> {
                    Row {
                        Box(
                            Modifier
                                .width(3.dp)
                                .height(20.dp)
                                .background(MaterialTheme.colorScheme.outline),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = InlineParser.parse(block.text, textColor),
                            style = bodyStyle,
                            color = mutedColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CodeBlock(
    code: String,
    language: String,
    isDark: Boolean,
    lightweight: Boolean = false,
) {
    val config = LocalCodePreviewConfig.current
    val theme = config.themeFor(isDark)
    CodeBlockThemed(
        code = code,
        theme = theme,
        showLineNumbers = config.showLineNumbers && !lightweight,
        wrapLongLines = config.wrapLongLines || lightweight,
        fontSize = config.fontSize,
        lightweight = lightweight,
    )
}

@Composable
fun CodeBlockThemed(
    code: String,
    theme: CodeTheme,
    showLineNumbers: Boolean,
    wrapLongLines: Boolean,
    fontSize: Int,
    lightweight: Boolean = false,
) {
    val highlighted = remember(code, theme, lightweight) {
        if (lightweight) {
            androidx.compose.ui.text.AnnotatedString(code)
        } else {
            CodeHighlight.highlight(code, theme)
        }
    }
    val lineCount = remember(code) { code.count { it == '\n' } + 1 }
    val gutterWidth = (lineCount.toString().length * fontSize * 0.62f).dp + 12.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(theme.background)
            .padding(vertical = 10.dp),
    ) {
        val inner = @Composable {
            Row(Modifier.padding(horizontal = 12.dp)) {
                if (showLineNumbers) {
                    Text(
                        text = (1..lineCount).joinToString("\n"),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.5f).sp,
                            color = theme.comment,
                        ),
                        modifier = Modifier.width(gutterWidth),
                    )
                }
                Text(
                    text = highlighted,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.5f).sp,
                        color = theme.foreground,
                    ),
                )
            }
        }
        if (wrapLongLines) {
            inner()
        } else {
            Box(Modifier.horizontalScroll(rememberScrollState())) { inner() }
        }
    }
}
