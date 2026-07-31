package com.andmx.ui2.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andmx.agent.ToolArgs
import java.io.File

data class ChatPreviewCard(
    val id: String,
    val title: String,
    val subtitle: String,
    val path: String,
    val kind: Kind,
) {
    enum class Kind { HTML_WEBSITE, MARKDOWN, FILE }
}

object ChatPreviewCards {
    fun isHtmlPath(path: String): Boolean =
        path.trim().lowercase().let { it.endsWith(".html") || it.endsWith(".htm") }

    fun isMarkdownPath(path: String): Boolean =
        path.trim().lowercase().let { it.endsWith(".md") || it.endsWith(".markdown") }

    fun fromTools(tools: List<ToolCall>): List<ChatPreviewCard> {
        val out = ArrayList<ChatPreviewCard>()
        val seen = LinkedHashSet<String>()
        for (tool in tools) {
            if (tool.isRunning || tool.isError) continue
            if (!ToolArgs.isWriteTool(tool.name) && tool.name !in setOf("Read", "read_file")) continue
            val path = ToolArgs.editedPath(tool.name, tool.args)
                .ifBlank { ToolArgs.filePath(tool.name, tool.args) }
                .ifBlank { ToolArgs.pathOf(tool.args) }
            if (path.isBlank()) continue
            val key = path.replace('\\', '/')
            if (!seen.add(key)) continue
            val name = File(path).name.ifBlank { path }
            when {
                isHtmlPath(path) -> out += ChatPreviewCard(
                    id = "html:$key",
                    title = name,
                    subtitle = "网站 · HTML",
                    path = path,
                    kind = ChatPreviewCard.Kind.HTML_WEBSITE,
                )
                isMarkdownPath(path) -> out += ChatPreviewCard(
                    id = "md:$key",
                    title = name,
                    subtitle = "文档 · MD",
                    path = path,
                    kind = ChatPreviewCard.Kind.MARKDOWN,
                )
            }
        }
        return out
    }

    fun fromAssistantText(text: String, tools: List<ToolCall>): List<ChatPreviewCard> {
        val base = fromTools(tools).toMutableList()
        val seen = base.map { it.path.replace('\\', '/') }.toMutableSet()
        val re = Regex("""[`'"]([^`'"]+\.html?)[`'"]""", RegexOption.IGNORE_CASE)
        for (m in re.findAll(text)) {
            val name = m.groupValues[1].trim()
            if (name.isBlank()) continue
            val path = tools.asSequence()
                .map { ToolArgs.pathOf(it.args) }
                .firstOrNull { it.endsWith(name, ignoreCase = true) || it.endsWith("/$name", ignoreCase = true) }
                ?: name
            val key = path.replace('\\', '/')
            if (!seen.add(key)) continue
            if (!isHtmlPath(path) && !isHtmlPath(name)) continue
            base += ChatPreviewCard(
                id = "html:$key",
                title = File(path).name.ifBlank { name },
                subtitle = "网站 · HTML",
                path = path,
                kind = ChatPreviewCard.Kind.HTML_WEBSITE,
            )
        }
        return base
    }
}

@Composable
fun PreviewCardsRow(
    cards: List<ChatPreviewCard>,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cards.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        cards.forEach { card ->
            PreviewCardItem(card = card, onOpenFile = onOpenFile)
        }
    }
}

@Composable
private fun PreviewCardItem(
    card: ChatPreviewCard,
    onOpenFile: (String) -> Unit,
) {
    var menuOpen by remember(card.id) { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f))
            .clickable { onOpenFile(card.path) }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (card.kind == ChatPreviewCard.Kind.HTML_WEBSITE) Icons.Outlined.Language else Icons.Outlined.Description,
                null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                card.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                card.subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            Text(
                "打开",
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { menuOpen = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("在文件中打开") },
                    onClick = {
                        menuOpen = false
                        onOpenFile(card.path)
                    },
                )
            }
        }
    }
}
