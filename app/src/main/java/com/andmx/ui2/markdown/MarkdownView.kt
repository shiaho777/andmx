package com.andmx.ui2.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.WrapText
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
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
    val parser = remember { IncrementalMarkdown.Parser() }
    val incremental = if (streaming) {
        remember(markdown) { parser.update(markdown) }
    } else {
        null
    }
    val rawPositioned: List<IncrementalMarkdown.PositionedBlock> = if (streaming && incremental != null) {
        incremental.frozen + incremental.tail
    } else {
        remember(markdown) { MarkdownEngine.parseWithOffsets(markdown) }
            .map { IncrementalMarkdown.PositionedBlock(it.block, it.startOffset) }
    }
    val positioned = mergeAdjacentQuotes(rawPositioned)
    val isDark = isSystemInDarkTheme()
    val textColor = if (contentColor == Color.Unspecified) {
        MaterialTheme.colorScheme.onSurface
    } else {
        contentColor
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val mutedColor = textColor.copy(alpha = 0.82f)
    val bodyStyle = if (bodySizeSp > 0f) {
        MaterialTheme.typography.bodyLarge.copy(
            fontSize = bodySizeSp.sp,
            lineHeight = (bodySizeSp + 7f).sp,
        )
    } else {
        MaterialTheme.typography.bodyLarge
    }

    SelectionContainer {
        Column(modifier = modifier.fillMaxWidth()) {
            positioned.forEachIndexed { index, positionedBlock ->
                val block = positionedBlock.node
                if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.runtime.key(positionedBlock.key) {
                    when (block) {
                        is MdBlock.Heading -> {
                            val style = when (block.level) {
                                1 -> MaterialTheme.typography.headlineLarge
                                2 -> MaterialTheme.typography.headlineMedium
                                3 -> MaterialTheme.typography.titleLarge
                                else -> MaterialTheme.typography.titleMedium
                            }
                            MarkdownText(
                                raw = block.text,
                                style = style.copy(fontWeight = FontWeight.Bold),
                                color = textColor,
                                linkColor = linkColor,
                            )
                        }

                        is MdBlock.Paragraph -> {
                            MarkdownText(
                                raw = block.text,
                                style = bodyStyle,
                                color = textColor,
                                linkColor = linkColor,
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
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 1.dp),
                                    ) {
                                        Text(
                                            text = if (block.ordered) "${i + 1}. " else "• ",
                                            style = bodyStyle,
                                            color = mutedColor,
                                        )
                                        MarkdownText(
                                            raw = item,
                                            style = bodyStyle,
                                            color = textColor,
                                            linkColor = linkColor,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }

                        is MdBlock.Quote -> {
                            Row(Modifier.height(IntrinsicSize.Min)) {
                                Box(
                                    Modifier
                                        .width(3.dp)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                                )
                                Spacer(Modifier.width(8.dp))
                                MarkdownText(
                                    raw = block.text,
                                    style = bodyStyle,
                                    color = mutedColor,
                                    linkColor = linkColor,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        is MdBlock.Table -> MarkdownTable(block, bodyStyle, textColor, linkColor)
                    }
                }
            }
        }
    }
}

private fun mergeAdjacentQuotes(
    blocks: List<IncrementalMarkdown.PositionedBlock>,
): List<IncrementalMarkdown.PositionedBlock> {
    if (blocks.size < 2) return blocks
    val out = ArrayList<IncrementalMarkdown.PositionedBlock>(blocks.size)
    for (b in blocks) {
        val quote = b.node as? MdBlock.Quote
        val lastQuote = out.lastOrNull()?.node as? MdBlock.Quote
        if (quote != null && lastQuote != null) {
            out[out.lastIndex] = IncrementalMarkdown.PositionedBlock(
                MdBlock.Quote(lastQuote.text + "\n" + quote.text),
                out.last().key,
            )
        } else {
            out.add(b)
        }
    }
    return out
}

@Suppress("DEPRECATION")
@Composable
fun MarkdownText(
    raw: String,
    style: TextStyle,
    color: Color,
    linkColor: Color,
    modifier: Modifier = Modifier,
) {
    val annotated = remember(raw, color, linkColor) { InlineParser.parse(raw, color, linkColor) }
    val hasLink = remember(annotated) {
        annotated.getStringAnnotations(InlineParser.UrlAnnotationTag, 0, annotated.length).any()
    }
    if (hasLink) {
        val uriHandler = LocalUriHandler.current
        ClickableText(
            text = annotated,
            style = style.copy(color = color),
            modifier = modifier,
            onClick = { offset ->
                annotated.getStringAnnotations(InlineParser.UrlAnnotationTag, offset, offset)
                    .firstOrNull()?.let { runCatching { uriHandler.openUri(it.item) } }
            },
        )
    } else {
        Text(
            text = annotated,
            style = style,
            color = color,
            modifier = modifier,
        )
    }
}

@Composable
private fun MarkdownTable(
    block: MdBlock.Table,
    bodyStyle: TextStyle,
    textColor: Color,
    linkColor: Color,
) {
    val columns = block.header.size
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f)),
    ) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .height(IntrinsicSize.Min)
                .padding(2.dp),
        ) {
            repeat(columns) { col ->
                Column(
                    Modifier
                        .widthIn(min = 88.dp, max = 260.dp)
                        .padding(horizontal = 8.dp),
                ) {
                    MarkdownText(
                        raw = block.header.getOrElse(col) { "" },
                        style = bodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = (bodyStyle.fontSize.value * 0.92f).sp),
                        color = textColor,
                        linkColor = linkColor,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
                    )
                    block.rows.forEach { row ->
                        MarkdownText(
                            raw = row.getOrElse(col) { "" },
                            style = bodyStyle.copy(fontSize = (bodyStyle.fontSize.value * 0.92f).sp),
                            color = textColor,
                            linkColor = linkColor,
                            modifier = Modifier.padding(vertical = 5.dp),
                        )
                    }
                }
                if (col < columns - 1) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                    )
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
        language = language,
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
    language: String = "",
) {
    var wrapOverride by remember(code) { mutableStateOf<Boolean?>(null) }
    val wrap = wrapOverride ?: wrapLongLines
    val clipboard = LocalClipboardManager.current
    val highlighted = remember(code, theme, lightweight) {
        if (lightweight) {
            AnnotatedString(code)
        } else {
            CodeHighlight.highlight(code, theme)
        }
    }
    val lineCount = remember(code) { code.count { it == '\n' } + 1 }
    val gutterWidth = (lineCount.toString().length * fontSize * 0.62f).dp + 12.dp

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(theme.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(theme.comment.copy(alpha = 0.10f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (code.isBlank()) "" else language.ifBlank { "代码" },
                style = MaterialTheme.typography.labelSmall,
                color = theme.comment,
            )
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.IconButton(
                onClick = { wrapOverride = !wrap },
                modifier = Modifier.size(26.dp),
            ) {
                Icon(
                    Icons.Outlined.WrapText,
                    "自动换行",
                    Modifier.size(14.dp),
                    tint = if (wrap) theme.foreground else theme.comment,
                )
            }
            androidx.compose.material3.IconButton(
                onClick = { clipboard.setText(AnnotatedString(code)) },
                modifier = Modifier.size(26.dp),
            ) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    "复制代码",
                    Modifier.size(14.dp),
                    tint = theme.comment,
                )
            }
        }
        Box(Modifier.padding(vertical = 10.dp)) {
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
            if (wrap) {
                inner()
            } else {
                Box(Modifier.horizontalScroll(rememberScrollState())) { inner() }
            }
        }
    }
}
