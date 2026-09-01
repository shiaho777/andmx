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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.AnnotatedString
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
    // 流式期间走增量解析（dsh incremental 对齐）：冻结块跨 chunk 复用，
    // 只有尾部重解析；块以源偏移为 key，跨冻结边界不重挂载。
    val parser = remember { IncrementalMarkdown.Parser() }
    var incremental by remember { mutableStateOf<IncrementalMarkdown.Result?>(null) }
    if (streaming) incremental = parser.update(markdown)
    val positioned: List<IncrementalMarkdown.PositionedBlock> = if (streaming && incremental != null) {
        incremental!!.frozen + incremental!!.tail
    } else {
        remember(markdown) { MarkdownEngine.parseWithOffsets(markdown) }
            .map { IncrementalMarkdown.PositionedBlock(it.block, it.startOffset) }
    }
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
        positioned.forEachIndexed { index, positionedBlock ->
            val block = positionedBlock.node
            if (index > 0) Spacer(modifier.height(gap))
            androidx.compose.runtime.key(positionedBlock.key) {
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

                is MdBlock.Table -> MarkdownTable(block, bodyStyle, textColor, mutedColor)
            }
            }
        }
    }
}

/** GFM 表格渲染（ZCode markdownTable 对齐）：行列对齐、横向滚动容错。 */
@Composable
private fun MarkdownTable(
    block: MdBlock.Table,
    bodyStyle: androidx.compose.ui.text.TextStyle,
    textColor: Color,
    mutedColor: Color,
) {
    val columns = block.header.size
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f)),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(2.dp)) {
            repeat(columns) { col ->
                Column(
                    Modifier
                        .widthIn(min = 88.dp, max = 260.dp)
                        .padding(horizontal = 8.dp),
                ) {                    Text(
                        text = InlineParser.parse(block.header.getOrElse(col) { "" }, textColor),
                        style = bodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = (bodyStyle.fontSize.value * 0.92f).sp),
                        color = textColor,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
                    )
                    block.rows.forEach { row ->
                        Text(
                            text = InlineParser.parse(row.getOrElse(col) { "" }, textColor),
                            style = bodyStyle.copy(fontSize = (bodyStyle.fontSize.value * 0.92f).sp),
                            color = textColor,
                            modifier = Modifier.padding(vertical = 5.dp),
                        )
                    }
                }
                if (col < columns - 1) {
                    Box(
                        Modifier
                            .width(1.dp)
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
    // ZCode codeBlock.copyCode / wrapLines 对齐：非流式代码块带复制与换行开关。
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
        if (!lightweight) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(theme.comment.copy(alpha = 0.10f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (code.isNotBlank()) "代码" else "",
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
