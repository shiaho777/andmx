package com.andmx.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.andmx.term.TerminalSession
import com.andmx.ui.components.SelectableChip
import com.andmx.ui.conversation.ConversationController
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Spacing

internal data class WorkTab(val label: String, val icon: ImageVector)

enum class WorkPaneTab { FILES, TERMINAL, DIFF, BROWSER, REFERENCE, PLUGINS }

internal val workTabs = listOf(
    WorkPaneTab.FILES to WorkTab("文件", Icons.Outlined.FolderOpen),
    WorkPaneTab.TERMINAL to WorkTab("终端", Icons.Outlined.Terminal),
    WorkPaneTab.DIFF to WorkTab("差异", Icons.Outlined.Difference),
    WorkPaneTab.BROWSER to WorkTab("浏览器", Icons.Outlined.Language),
    WorkPaneTab.REFERENCE to WorkTab("参考", Icons.Outlined.Info),
    WorkPaneTab.PLUGINS to WorkTab("MCP", Icons.Outlined.Extension),
)

/**
 * The right-hand work pane — Codex's IDE surface. A tab strip switches between
 * the agent's "hands": file tree, terminal, diff/review and browser.
 * Content is placeholder for now; each tab will be wired to the execution
 * environment in later milestones.
 */
@Composable
internal fun WorkPane(
    selected: WorkPaneTab,
    onSelect: (WorkPaneTab) -> Unit,
    controller: ConversationController,
    focusTarget: WorkbenchFocusTarget? = null,
    selectedFilePath: String? = null,
    selectedDiffPath: String? = null,
    browserUrl: String? = null,
    selectedReferencePath: String? = null,
    fileState: FilePaneState,
    browserState: BrowserPaneState,
    terminalSessions: androidx.compose.runtime.snapshots.SnapshotStateList<TerminalSession>,
    toolTerminalSessions: List<TerminalBindingState>,
    selectedTerminalKey: String,
    onSelectedTerminalKeyChange: (String) -> Unit,
    onOpenDiff: (String?) -> Unit = {},
    onOpenFile: (String) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    onOpenProgress: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onRunCommand: (String) -> Unit = {},
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** When false, the tab strip is omitted (the caller renders its own). */
    showTabStrip: Boolean = true,
) {
    val colors = AndmxTheme.colors
    val changes by com.andmx.workspace.ChangeTracker.changes.collectAsState()
    val changeCount = changes.size
    val resolvedTab = focusTarget?.tab ?: selected
    val resolvedFilePath = (focusTarget as? WorkbenchFocusTarget.Files)?.path ?: selectedFilePath
    val resolvedDiffPath = (focusTarget as? WorkbenchFocusTarget.Diff)?.path ?: selectedDiffPath
    val resolvedBrowserUrl = (focusTarget as? WorkbenchFocusTarget.Browser)?.url ?: browserUrl
    val resolvedReferencePath = (focusTarget as? WorkbenchFocusTarget.Reference)?.assetPath ?: selectedReferencePath
    val subtitle = remember(
        resolvedTab,
        resolvedFilePath,
        resolvedDiffPath,
        resolvedBrowserUrl,
        resolvedReferencePath,
        fileState.currentGuestPath,
        fileState.viewingGuestPath,
        changeCount,
        selectedTerminalKey,
        toolTerminalSessions,
    ) {
        when (resolvedTab) {
            WorkPaneTab.FILES -> fileState.viewingGuestPath
                ?: fileState.currentGuestPath.takeIf { it.isNotBlank() }
                ?: resolvedFilePath?.takeIf { it.isNotBlank() }
                ?: "浏览 Linux rootfs"
            WorkPaneTab.TERMINAL -> toolTerminalSessions.firstOrNull { it.id == selectedTerminalKey }
                ?.command?.takeIf { it.isNotBlank() }
                ?: "Android/proot shell"
            WorkPaneTab.DIFF -> resolvedDiffPath?.takeIf { it.isNotBlank() }
                ?: if (changeCount == 0) "暂无待审变更" else "$changeCount 个 agent 变更"
            WorkPaneTab.BROWSER -> resolvedBrowserUrl?.takeIf { it.isNotBlank() } ?: "页面预览"
            WorkPaneTab.REFERENCE -> resolvedReferencePath?.takeIf { it.isNotBlank() } ?: "截图与 UI 参考"
            WorkPaneTab.PLUGINS -> "插件与扩展能力"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        // Tab strip — omitted when the caller renders its own (compact layout).
        if (showTabStrip) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                workTabs.forEach { (tabId, tab) ->
                    val badge = if (tabId == WorkPaneTab.DIFF) changeCount else 0
                    WorkTabChip(tab, resolvedTab == tabId, badge) { onSelect(tabId) }
                    Spacer(Modifier.width(Spacing.xs))
                }
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    subtitle,
                    style = AndmxTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(999.dp))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭工作区", tint = colors.textTertiary, modifier = Modifier.size(15.dp))
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        }

        // Body — wired tabs render real surfaces; others stay placeholders.
        // Crossfade between tabs so the panel content swaps gracefully.
        androidx.compose.animation.Crossfade(
            targetState = resolvedTab,
            animationSpec = androidx.compose.animation.core.tween(com.andmx.ui.theme.Motion.DUR_STD, easing = com.andmx.ui.theme.Motion.EASE_OUT),
            label = "workPane",
        ) { tab ->
        when (tab) {
            WorkPaneTab.FILES -> FilePane(
                state = fileState,
                selectedGuestPath = resolvedFilePath,
                onOpenDiff = onOpenDiff,
                modifier = Modifier.fillMaxSize(),
            )
            WorkPaneTab.TERMINAL -> TerminalHost(
                liveSessions = terminalSessions,
                toolSessions = toolTerminalSessions,
                selectedKey = selectedTerminalKey,
                onSelectedKeyChange = onSelectedTerminalKeyChange,
                modifier = Modifier.fillMaxSize(),
                compact = !showTabStrip,
            )
            WorkPaneTab.DIFF -> DiffPane(
                selectedPath = resolvedDiffPath,
                onOpenFile = onOpenFile,
                modifier = Modifier.fillMaxSize(),
            )
            WorkPaneTab.BROWSER -> BrowserPane(
                state = browserState,
                initialUrl = resolvedBrowserUrl,
                modifier = Modifier.fillMaxSize(),
            )
            WorkPaneTab.REFERENCE -> UiReferencePreviewPane(
                assetPath = resolvedReferencePath,
                onRunCommand = onRunCommand,
                modifier = Modifier.fillMaxSize(),
            )
            WorkPaneTab.PLUGINS -> PluginPanel(
                servers = controller.mcpServers(),
                modifier = Modifier.fillMaxSize(),
            )
        }
        } // end Crossfade
    }
}

@Composable
internal fun WorkTabChip(tab: WorkTab, selected: Boolean, badge: Int = 0, onClick: () -> Unit) =
    SelectableChip(label = tab.label, selected = selected, onClick = onClick, icon = tab.icon, badge = badge)
