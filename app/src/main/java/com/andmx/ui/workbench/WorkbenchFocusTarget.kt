package com.andmx.ui.workbench

internal sealed interface WorkbenchFocusTarget {
    val tab: WorkPaneTab

    data class Files(val path: String? = null) : WorkbenchFocusTarget {
        override val tab: WorkPaneTab = WorkPaneTab.FILES
    }

    data class Terminal(val sessionKey: String? = null) : WorkbenchFocusTarget {
        override val tab: WorkPaneTab = WorkPaneTab.TERMINAL
    }

    data class Diff(val path: String? = null) : WorkbenchFocusTarget {
        override val tab: WorkPaneTab = WorkPaneTab.DIFF
    }

    data class Browser(val url: String? = null) : WorkbenchFocusTarget {
        override val tab: WorkPaneTab = WorkPaneTab.BROWSER
    }

    data class Reference(val assetPath: String? = null) : WorkbenchFocusTarget {
        override val tab: WorkPaneTab = WorkPaneTab.REFERENCE
    }

    data object Plugins : WorkbenchFocusTarget {
        override val tab: WorkPaneTab = WorkPaneTab.PLUGINS
    }
}

internal fun focusTargetFor(
    tab: WorkPaneTab,
    selectedFilePath: String? = null,
    selectedDiffPath: String? = null,
    browserUrl: String? = null,
    selectedReferencePath: String? = null,
    selectedTerminalKey: String? = null,
): WorkbenchFocusTarget = when (tab) {
    WorkPaneTab.FILES -> WorkbenchFocusTarget.Files(selectedFilePath)
    WorkPaneTab.TERMINAL -> WorkbenchFocusTarget.Terminal(selectedTerminalKey)
    WorkPaneTab.DIFF -> WorkbenchFocusTarget.Diff(selectedDiffPath)
    WorkPaneTab.BROWSER -> WorkbenchFocusTarget.Browser(browserUrl)
    WorkPaneTab.REFERENCE -> WorkbenchFocusTarget.Reference(selectedReferencePath)
    WorkPaneTab.PLUGINS -> WorkbenchFocusTarget.Plugins
}

internal fun focusTargetLabel(target: WorkbenchFocusTarget): String = when (target) {
    is WorkbenchFocusTarget.Files -> "文件"
    is WorkbenchFocusTarget.Terminal -> "终端"
    is WorkbenchFocusTarget.Diff -> "差异"
    is WorkbenchFocusTarget.Browser -> "浏览器"
    is WorkbenchFocusTarget.Reference -> "参考"
    WorkbenchFocusTarget.Plugins -> "MCP"
}

internal fun FocusTarget.toWorkbenchFocusTarget(
    artifacts: List<ArtifactState> = emptyList(),
): WorkbenchFocusTarget? = when (this) {
    FocusTarget.None -> null
    is FocusTarget.File -> WorkbenchFocusTarget.Files(path)
    is FocusTarget.Diff -> WorkbenchFocusTarget.Diff(path)
    is FocusTarget.Browser -> WorkbenchFocusTarget.Browser(url)
    is FocusTarget.Terminal -> WorkbenchFocusTarget.Terminal(
        when {
            toolCallId.isNotBlank() -> toolTerminalSessionKey(toolCallId)
            sessionId.isNotBlank() && (sessionId.startsWith("tool:") || sessionId.startsWith("live:")) -> sessionId
            sessionId.isNotBlank() -> liveTerminalSessionKey(sessionId)
            else -> null
        },
    )
    is FocusTarget.Reference -> {
        val resolvedPath = assetPath.ifBlank {
            artifacts.firstOrNull { it.id == artifactId }?.assetPath.orEmpty()
        }
        resolvedPath.takeIf { it.isNotBlank() }?.let { WorkbenchFocusTarget.Reference(it) }
    }
    is FocusTarget.PluginApp -> WorkbenchFocusTarget.Plugins
    is FocusTarget.BackgroundTask -> null
    is FocusTarget.Timeline -> null
}
