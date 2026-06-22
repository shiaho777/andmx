package com.andmx.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andmx.ui.theme.AndmxCodeTextStyle
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Radii
import com.andmx.ui.theme.Spacing

/** Renders Markdown text in the Codex style: headings, lists, code blocks,
 *  blockquotes, and inline bold/italic/code/links. */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val colors = AndmxTheme.colors
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }

    Column(modifier.fillMaxWidth()) {
        for ((idx, block) in blocks.withIndex()) {
            if (idx > 0) Spacer(Modifier.height(Spacing.sm))
            when (block) {
                is MdBlock.Heading -> Text(
                    text = inline(block.text),
                    style = when (block.level) {
                        1 -> AndmxTheme.typography.titleLarge
                        2 -> AndmxTheme.typography.titleMedium
                        else -> AndmxTheme.typography.titleSmall
                    }.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                )
                is MdBlock.Paragraph -> Text(inline(block.text), style = AndmxTheme.typography.bodyLarge, color = colors.textPrimary)
                is MdBlock.Bullet -> Column {
                    block.items.forEach { ListRow("•  ", it) }
                }
                is MdBlock.Ordered -> Column {
                    block.items.forEachIndexed { i, it -> ListRow("${i + 1}. ", it) }
                }
                is MdBlock.Quote -> Row {
                    Box(Modifier.width(3.dp).height(20.dp).background(colors.border))
                    Spacer(Modifier.width(Spacing.sm))
                    Text(inline(block.text), style = AndmxTheme.typography.bodyLarge, color = colors.textSecondary)
                }
                is MdBlock.Code -> CodeBlock(block)
            }
        }
    }
}

@Composable
private fun ListRow(marker: String, text: String) {
    val colors = AndmxTheme.colors
    Row(Modifier.padding(vertical = 1.dp)) {
        Text(marker, style = AndmxTheme.typography.bodyLarge, color = colors.textSecondary)
        Text(inline(text), style = AndmxTheme.typography.bodyLarge, color = colors.textPrimary)
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code) {
    val colors = AndmxTheme.colors
    val h = rememberScrollState()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val highlighted = remember(block.code) { CodeHighlighter.highlight(block.code, colors.textPrimary) }
    Column(
        Modifier.fillMaxWidth().background(colors.codeBackground, Radii.sm).padding(Spacing.md),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(block.lang ?: "code", style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
            Spacer(Modifier.weight(1f))
            Text(
                "复制",
                style = AndmxTheme.typography.labelSmall,
                color = colors.textTertiary,
                modifier = Modifier.clip(Radii.sm)
                    .clickable { clipboard.setText(androidx.compose.ui.text.AnnotatedString(block.code)) }
                    .padding(horizontal = Spacing.sm, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(Spacing.xs))
        Box(Modifier.fillMaxWidth().horizontalScroll(h)) {
            Text(highlighted, style = AndmxCodeTextStyle)
        }
    }
}

@Composable
private fun inline(text: String) = MarkdownInline.parse(
    text,
    codeBg = AndmxTheme.colors.codeBackground,
    codeFg = AndmxTheme.colors.textPrimary,
    linkColor = AndmxTheme.colors.accent,
)
