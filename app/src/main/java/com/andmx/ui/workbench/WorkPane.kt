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

private data class WorkTab(val label: String, val icon: ImageVector)

enum class WorkPaneTab { INSPECTOR, FILES, TERMINAL, DIFF, BROWSER, MEMORY, PLUGINS, SECURITY }

private val workTabs = listOf(
    WorkPaneTab.INSPECTOR to WorkTab("状态", Icons.Outlined.Psychology),
    WorkPaneTab.FILES to WorkTab("文件", Icons.Outlined.FolderOpen),
    WorkPaneTab.TERMINAL to WorkTab("终端", Icons.Outlined.Terminal),
    WorkPaneTab.DIFF to WorkTab("差异", Icons.Outlined.Difference),
    WorkPaneTab.BROWSER to WorkTab("浏览器", Icons.Outlined.Language),
    WorkPaneTab.MEMORY to WorkTab("记忆", Icons.Outlined.Memory),
    WorkPaneTab.PLUGINS to WorkTab("插件", Icons.Outlined.Extension),
    WorkPaneTab.SECURITY to WorkTab("安全", Icons.Outlined.Security),
)

/**
 * The right-hand work pane — Codex's IDE surface. A tab strip switches between
 * the agent's "hands": file tree, terminal, diff/review and browser.
 * Content is placeholder for now; each tab will be wired to the execution
 * environment in later milestones.
 */
@Composable
fun WorkPane(
    selected: WorkPaneTab,
    onSelect: (WorkPaneTab) -> Unit,
    controller: ConversationController,
    selectedFilePath: String? = null,
    selectedDiffPath: String? = null,
    browserUrl: String? = null,
    fileState: FilePaneState,
    browserState: BrowserPaneState,
    terminalSessions: androidx.compose.runtime.snapshots.SnapshotStateList<TerminalSession>,
    selectedTerminalIndex: Int,
    onSelectedTerminalIndexChange: (Int) -> Unit,
    onOpenDiff: (String?) -> Unit = {},
    onOpenFile: (String) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    onOpenProgress: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onRunCommand: (String) -> Unit = {},
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = AndmxTheme.colors
    val changes by com.andmx.workspace.ChangeTracker.changes.collectAsState()
    val changeCount = changes.size
    val subtitle = remember(selected, selectedFilePath, selectedDiffPath, browserUrl, fileState.currentGuestPath, fileState.viewingGuestPath, changeCount) {
        when (selected) {
            WorkPaneTab.INSPECTOR -> "Agent 状态、上下文与能力边界"
            WorkPaneTab.FILES -> fileState.viewingGuestPath
                ?: fileState.currentGuestPath.takeIf { it.isNotBlank() }
                ?: selectedFilePath?.takeIf { it.isNotBlank() }
                ?: "浏览 Linux rootfs"
            WorkPaneTab.TERMINAL -> "Android/proot shell"
            WorkPaneTab.DIFF -> selectedDiffPath?.takeIf { it.isNotBlank() }
                ?: if (changeCount == 0) "暂无待审变更" else "$changeCount 个 agent 变更"
            WorkPaneTab.BROWSER -> browserUrl?.takeIf { it.isNotBlank() } ?: "页面预览"
            WorkPaneTab.MEMORY -> "持久化记忆与上下文"
            WorkPaneTab.PLUGINS -> "插件与扩展能力"
            WorkPaneTab.SECURITY -> "安全策略与授权"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        // Tab strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            workTabs.forEach { (tabId, tab) ->
                val badge = if (tabId == WorkPaneTab.DIFF) changeCount else 0
                WorkTabChip(tab, selected == tabId, badge) { onSelect(tabId) }
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

        // Body — wired tabs render real surfaces; others stay placeholders.
        // Crossfade between tabs so the panel content swaps gracefully.
        androidx.compose.animation.Crossfade(
            targetState = selected,
            animationSpec = androidx.compose.animation.core.tween(com.andmx.ui.theme.Motion.DUR_STD, easing = com.andmx.ui.theme.Motion.EASE_OUT),
            label = "workPane",
        ) { tab ->
        when (tab) {
            WorkPaneTab.INSPECTOR -> AgentInspectorPane(
                controller = controller,
                changedFiles = changeCount,
                onOpenProgress = onOpenProgress,
                onOpenSettings = onOpenSettings,
                onRunCommand = onRunCommand,
                onOpenFile = onOpenFile,
                onOpenUrl = onOpenUrl,
                modifier = Modifier.fillMaxSize(),
            )
            WorkPaneTab.FILES -> FilePane(
                state = fileState,
                selectedGuestPath = selectedFilePath,
                onOpenDiff = onOpenDiff,
                modifier = Modifier.fillMaxSize(),
            )
            WorkPaneTab.TERMINAL -> TerminalHost(
                sessions = terminalSessions,
                selectedIndex = selectedTerminalIndex,
                onSelectedIndexChange = onSelectedTerminalIndexChange,
                modifier = Modifier.fillMaxSize(),
            )
            WorkPaneTab.DIFF -> DiffPane(
                selectedPath = selectedDiffPath,
                onOpenFile = onOpenFile,
                modifier = Modifier.fillMaxSize(),
            )
            WorkPaneTab.BROWSER -> BrowserPane(
                state = browserState,
                initialUrl = browserUrl,
                modifier = Modifier.fillMaxSize(),
            )
            WorkPaneTab.MEMORY -> Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            ) {
                MemoryPanel(
                    memoryState = controller.memoryState,
                    onClearMemory = { controller.clearMemory() },
                    onConsolidate = { controller.consolidateMemory() },
                    modifier = Modifier.padding(8.dp),
                )
            }
            WorkPaneTab.PLUGINS -> Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            ) {
                PluginPanel(
                    pluginState = controller.pluginState,
                    onTogglePlugin = { name, enabled -> controller.togglePlugin(name, enabled) },
                    modifier = Modifier.padding(8.dp),
                )
            }
            WorkPaneTab.SECURITY -> Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            ) {
                SecurityPanel(
                    approvalMode = controller.currentApprovalMode,
                    execPolicy = controller.execPolicyInstance,
                    networkPolicy = controller.networkPolicyInstance,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        } // end Crossfade
    }
}

@Composable
private fun WorkTabChip(tab: WorkTab, selected: Boolean, badge: Int = 0, onClick: () -> Unit) =
    SelectableChip(label = tab.label, selected = selected, onClick = onClick, icon = tab.icon, badge = badge)
