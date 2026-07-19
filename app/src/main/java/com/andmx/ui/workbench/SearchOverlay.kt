package com.andmx.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.andmx.data.ConversationEntity
import com.andmx.data.ConversationRepository
import com.andmx.ui.rememberScreenHeightDp
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Radii
import com.andmx.ui.theme.Spacing

/** Codex-style command palette: actions first, conversation search second. */
@Composable
fun SearchOverlay(
    onDismiss: () -> Unit,
    onOpen: (Long) -> Unit,
    onCommand: (CommandId) -> Unit,
    recentCommandIds: List<CommandId> = emptyList(),
) {
    val colors = AndmxTheme.colors
    val context = LocalContext.current
    val repo = remember { ConversationRepository(context) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ConversationEntity>>(emptyList()) }
    val sections = remember(query, recentCommandIds) { paletteCommandSections(query, recentCommandIds) }
    val commandEntries = remember(sections) { sections.recent + sections.commands }
    val entries = remember(commandEntries, results) { paletteEntries(commandEntries, results.map { it.id }) }
    var selectedIndex by remember { mutableStateOf(0) }
    val selectedEntry = entries.getOrNull(selectedIndex)
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    fun run(entry: PaletteEntry?) {
        when (entry) {
            is PaletteEntry.Command -> onCommand(entry.item.id)
            is PaletteEntry.Conversation -> onOpen(entry.id)
            null -> return
        }
        onDismiss()
    }

    LaunchedEffect(query) {
        results = if (query.isBlank()) emptyList() else repo.search(query)
    }
    LaunchedEffect(entries.size) {
        selectedIndex = clampPaletteSelection(selectedIndex, entries.size)
    }
    LaunchedEffect(selectedIndex, sections.recent.size, sections.commands.size, query.isNotBlank()) {
        val listRow = listRowForSelection(
            selectedIndex = selectedIndex,
            recentCount = sections.recent.size,
            commandCount = sections.commands.size,
            showConversations = query.isNotBlank(),
        )
        if (listRow >= 0) listState.animateScrollToItem(listRow)
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(Radii.lg).background(colors.surfaceElevated)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            selectedIndex = movePaletteSelection(selectedIndex, entries.size, 1)
                            true
                        }
                        Key.DirectionUp -> {
                            selectedIndex = movePaletteSelection(selectedIndex, entries.size, -1)
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            run(selectedEntry)
                            true
                        }
                        Key.Escape -> {
                            onDismiss()
                            true
                        }
                        else -> false
                    }
                }
                .padding(Spacing.lg),
        ) {
            Row(Modifier.fillMaxWidth().clip(Radii.sm).background(colors.sunken).padding(horizontal = Spacing.md, vertical = Spacing.md)) {
                if (query.isEmpty()) {
                    Text("搜索或运行命令…", style = AndmxTheme.typography.bodyLarge, color = colors.textTertiary)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = AndmxTheme.typography.bodyLarge.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
            }
            Spacer(Modifier.height(Spacing.md))
            // Cap list height to ~55% of the screen so the overlay never overflows
            // in landscape (where a fixed 380dp would exceed the short height).
            val listMaxHeight = (rememberScreenHeightDp() * 0.55f).dp
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = listMaxHeight)) {
                if (sections.recent.isNotEmpty()) {
                    item(key = "recent-header") { SectionHeader("最近") }
                    itemsIndexed(sections.recent, key = { _, item -> "recent-${item.id}" }) { entryIndex, command ->
                        CommandRow(command, selected = selectedEntry == PaletteEntry.Command(command)) {
                            selectedIndex = entryIndex
                            run(PaletteEntry.Command(command))
                        }
                    }
                }
                if (sections.commands.isNotEmpty()) {
                    item(key = "commands-header") { SectionHeader("命令") }
                    itemsIndexed(sections.commands, key = { _, item -> "cmd-${item.id}" }) { commandIndex, command ->
                        val entryIndex = sections.recent.size + commandIndex
                        CommandRow(command, selected = selectedEntry == PaletteEntry.Command(command)) {
                            selectedIndex = entryIndex
                            run(PaletteEntry.Command(command))
                        }
                    }
                }
                if (query.isNotBlank()) {
                    item(key = "threads-header") { SectionHeader("对话") }
                    if (results.isEmpty()) {
                        item(key = "threads-empty") {
                            Text(
                                "无匹配结果",
                                style = AndmxTheme.typography.bodySmall,
                                color = colors.textTertiary,
                                modifier = Modifier.padding(Spacing.sm),
                            )
                        }
                    }
                    itemsIndexed(results, key = { _, item -> "conv-${item.id}" }) { conversationIndex, c ->
                        val entryIndex = commandEntries.size + conversationIndex
                        ConversationResultRow(c, selected = selectedEntry == PaletteEntry.Conversation(c.id)) {
                            selectedIndex = entryIndex
                            run(PaletteEntry.Conversation(c.id))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    val colors = AndmxTheme.colors
    Text(
        label,
        style = AndmxTheme.typography.labelSmall,
        color = colors.textTertiary,
        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    )
}

@Composable
private fun CommandRow(command: CommandPaletteItem, selected: Boolean, onClick: () -> Unit) {
    val colors = AndmxTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(Radii.sm)
            .background(if (selected) colors.selected else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            commandIcon(command.id),
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                command.title,
                style = AndmxTheme.typography.bodyMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                command.subtitle,
                style = AndmxTheme.typography.labelSmall,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ConversationResultRow(c: ConversationEntity, selected: Boolean, onClick: () -> Unit) {
    val colors = AndmxTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(Radii.sm)
            .background(if (selected) colors.selected else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(Spacing.md))
        Text(
            c.title.ifBlank { "未命名对话" },
            style = AndmxTheme.typography.bodyMedium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(c.project, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
    }
}

private fun commandIcon(id: CommandId): ImageVector = when (id) {
    CommandId.NEW_CHAT -> Icons.Outlined.ChatBubbleOutline
    CommandId.SHOW_PROGRESS -> Icons.AutoMirrored.Outlined.FormatListBulleted
    CommandId.SHOW_GOAL -> Icons.Outlined.TrackChanges
    CommandId.STATUS -> Icons.Outlined.Info
    CommandId.CONTEXT -> Icons.Outlined.Info
    CommandId.PLAN -> Icons.AutoMirrored.Outlined.FormatListBulleted
    CommandId.VERIFY -> Icons.Outlined.Info
    CommandId.CHANGES -> Icons.Outlined.Difference
    CommandId.ACTIVITY -> Icons.Outlined.History
    CommandId.CHECKLIST -> Icons.AutoMirrored.Outlined.FormatListBulleted
    CommandId.NEXT -> Icons.Outlined.TrackChanges
    CommandId.EVIDENCE -> Icons.Outlined.Info
    CommandId.REFERENCES -> Icons.Outlined.Info
    CommandId.BLUEPRINT -> Icons.Outlined.TrackChanges
    CommandId.POLICY -> Icons.Outlined.Security
    CommandId.TOOLS -> Icons.Outlined.Extension
    CommandId.PARITY -> Icons.Outlined.TrackChanges
    CommandId.REPORT -> Icons.AutoMirrored.Outlined.FormatListBulleted
    CommandId.ARCHITECTURE -> Icons.Outlined.Psychology
    CommandId.SURFACES -> Icons.Outlined.TrackChanges
    CommandId.VISUAL_CHECK -> Icons.Outlined.Info
    CommandId.DESIGN_SYSTEM -> Icons.Outlined.TrackChanges
    CommandId.SCREENSHOT_EXTRACT -> Icons.Outlined.Info
    CommandId.APPSHOTS -> Icons.Outlined.Info
    CommandId.TRACE -> Icons.Outlined.TrackChanges
    CommandId.SELF_MODEL -> Icons.Outlined.Psychology
    CommandId.FLOW -> Icons.Outlined.TrackChanges
    CommandId.METHOD -> Icons.Outlined.Psychology
    CommandId.IMPROVE -> Icons.Outlined.AutoAwesome
    CommandId.INSTRUCTIONS -> Icons.Outlined.Settings
    CommandId.COMMANDS -> Icons.Outlined.Search
    CommandId.HANDOFF -> Icons.AutoMirrored.Outlined.FormatListBulleted
    CommandId.SET_FULL_ACCESS -> Icons.Outlined.Security
    CommandId.SET_ASK_APPROVAL -> Icons.Outlined.Security
    CommandId.SET_READ_ONLY -> Icons.Outlined.Security
    CommandId.DIAG -> Icons.Outlined.Terminal
    CommandId.EXPORT -> Icons.Outlined.FileDownload
    CommandId.SETTINGS -> Icons.Outlined.Settings
    CommandId.PLUGINS -> Icons.Outlined.Extension
    CommandId.TOGGLE_THEME -> Icons.Outlined.Settings
    CommandId.TOGGLE_WORK_PANE -> Icons.AutoMirrored.Outlined.ViewSidebar
    CommandId.TOGGLE_TERMINAL_DOCK -> Icons.Outlined.Terminal
    CommandId.OPEN_FILES -> Icons.Outlined.FolderOpen
    CommandId.OPEN_TERMINAL -> Icons.Outlined.Terminal
    CommandId.OPEN_DIFF -> Icons.Outlined.Difference
    CommandId.OPEN_BROWSER -> Icons.Outlined.Language
}

private fun listRowForSelection(
    selectedIndex: Int,
    recentCount: Int,
    commandCount: Int,
    showConversations: Boolean,
): Int {
    val commandEntryCount = recentCount + commandCount
    if (selectedIndex < 0) return -1
    if (recentCount > 0 && selectedIndex < recentCount) return 1 + selectedIndex
    if (commandCount > 0 && selectedIndex < commandEntryCount) {
        val recentRows = if (recentCount > 0) 1 + recentCount else 0
        val commandIndex = selectedIndex - recentCount
        return recentRows + 1 + commandIndex
    }
    if (!showConversations) return -1
    val conversationIndex = selectedIndex - commandEntryCount
    if (conversationIndex < 0) return -1
    val recentRows = if (recentCount > 0) 1 + recentCount else 0
    val commandRows = if (commandCount > 0) 1 + commandCount else 0
    return recentRows + commandRows + 1 + conversationIndex
}

private fun listRowForSelection(index: Int, commandCount: Int, showConversations: Boolean): Int {
    if (index < 0) return -1
    return if (index < commandCount) {
        1 + index
    } else if (showConversations) {
        val conversationIndex = index - commandCount
        1 + commandCount + 1 + conversationIndex
    } else {
        -1
    }
}
