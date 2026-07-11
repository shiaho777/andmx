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
fun MarkdownView(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { MarkdownEngine.parse(markdown) }
    val isDark = isSystemInDarkTheme()
    
    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            
            when (block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineLarge
                        2 -> MaterialTheme.typography.headlineMedium
                        3 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    }
                    Text(
                        text = InlineParser.parse(block.text, MaterialTheme.colorScheme.onSurface),
                        style = style.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                is MdBlock.Paragraph -> {
                    Text(
                        text = InlineParser.parse(block.text, MaterialTheme.colorScheme.onSurface),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                is MdBlock.Code -> {
                    CodeBlock(
                        code = block.code,
                        language = block.lang,
                        isDark = isDark
                    )
                }
                
                is MdBlock.List -> {
                    Column {
                        block.items.forEachIndexed { i, item ->
                            Row(Modifier.padding(vertical = 2.dp)) {
                                Text(
                                    text = if (block.ordered) "${i + 1}. " else "• ",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = InlineParser.parse(item, MaterialTheme.colorScheme.onSurface),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
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
                                .background(MaterialTheme.colorScheme.outline)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = InlineParser.parse(block.text, MaterialTheme.colorScheme.onSurface),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CodeBlock(code: String, language: String, isDark: Boolean) {
    val config = LocalCodePreviewConfig.current
    val theme = config.themeFor(isDark)
    CodeBlockThemed(code, theme, config.showLineNumbers, config.wrapLongLines, config.fontSize)
}

@Composable
fun CodeBlockThemed(
    code: String,
    theme: CodeTheme,
    showLineNumbers: Boolean,
    wrapLongLines: Boolean,
    fontSize: Int
) {
    val highlighted = remember(code, theme) { CodeHighlight.highlight(code, theme) }
    val lineCount = remember(code) { code.count { it == '\n' } + 1 }
    val gutterWidth = (lineCount.toString().length * fontSize * 0.62f).dp + 12.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(theme.background)
            .padding(vertical = 10.dp)
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
                            color = theme.comment
                        ),
                        modifier = Modifier.width(gutterWidth)
                    )
                }
                Text(
                    text = highlighted,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.5f).sp,
                        color = theme.foreground
                    )
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
