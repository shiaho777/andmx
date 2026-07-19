package com.andmx.ui.workbench

import com.andmx.agent.ToolArgs
import com.andmx.ui.conversation.Attachment
import com.andmx.ui.conversation.ConversationGoal
import com.andmx.ui.conversation.GoalStatus

enum class SidePanelTabId {
    REVIEW,
    DIFF,
    BROWSER,
    TERMINAL,
    TIMELINE,
    MCP_APP,
    BACKGROUND,
    REFERENCE,
}

sealed interface FocusTarget {
    data object None : FocusTarget
    data class File(val path: String) : FocusTarget
    data class Diff(val path: String?) : FocusTarget
    data class Browser(val url: String, val title: String = "") : FocusTarget
    data class Terminal(val sessionId: String = "", val toolCallId: String = "", val command: String = "") : FocusTarget
    data class Timeline(val turnId: String) : FocusTarget
    data class Reference(val artifactId: String, val assetPath: String = "") : FocusTarget
    data class PluginApp(val serverName: String, val appName: String = "") : FocusTarget
    data class BackgroundTask(val taskId: String) : FocusTarget
}

enum class TurnStatus {
    IDLE,
    RUNNING,
    WAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

enum class ToolExecutionStatus {
    QUEUED,
    RUNNING,
    WAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

enum class ArtifactKind {
    IMAGE,
    FILE,
    DIFF,
    WEB,
    TERMINAL,
    REFERENCE,
    OTHER,
}

enum class BackgroundTaskKind {
    SUBAGENT,
    TERMINAL,
}

enum class BackgroundTaskStatus {
    RUNNING,
    WAITING,
    COMPLETED,
    FAILED,
    CLOSED,
}

enum class SearchResultKind {
    COMMAND,
    CONVERSATION,
    TURN,
    TOOL,
    FILE,
    DIFF,
    BROWSER,
    APPROVAL,
    BACKGROUND_TASK,
    PLUGIN,
    MCP_SERVER,
    AUTOMATION,
    REFERENCE,
}

data class ArtifactState(
    val id: String,
    val kind: ArtifactKind,
    val title: String,
    val subtitle: String = "",
    val path: String = "",
    val url: String = "",
    val imageUrl: String = "",
    val assetPath: String = "",
    val toolCallId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

data class ToolExecutionState(
    val callId: String,
    val name: String,
    val args: String,
    val preview: String = ToolArgs.preview(name, args),
    val status: ToolExecutionStatus = ToolExecutionStatus.QUEUED,
    val output: String = "",
    val error: Boolean = false,
    val artifacts: List<ArtifactState> = emptyList(),
    val focusTarget: FocusTarget = FocusTarget.None,
    val sidePanelTab: SidePanelTabId? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0L,
)

data class TurnState(
    val id: String,
    val userText: String,
    val userAttachments: List<Attachment> = emptyList(),
    val assistantText: String = "",
    val assistantStreamingText: String = "",
    val assistantDone: Boolean = false,
    val tools: List<ToolExecutionState> = emptyList(),
    val artifacts: List<ArtifactState> = emptyList(),
    val focusTarget: FocusTarget = FocusTarget.None,
    val sidePanelTab: SidePanelTabId? = null,
    val status: TurnStatus = TurnStatus.IDLE,
    val failureMessage: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0L,
)

data class BackgroundTaskState(
    val id: String,
    val kind: BackgroundTaskKind,
    val title: String,
    val status: BackgroundTaskStatus,
    val summary: String = "",
    val detail: String = "",
    val result: String = "",
    val focusTarget: FocusTarget = FocusTarget.None,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class GoalState(
    val objective: String = "",
    val status: GoalStatus = GoalStatus.EMPTY,
    val note: String = "",
    val tokenBudget: Int = 0,
    val tokensUsed: Int = 0,
    val startedAt: Long = 0L,
    val updatedAt: Long = 0L,
)

data class ComposerDraftState(
    val text: String = "",
    val attachments: List<Attachment> = emptyList(),
    val editingIndex: Int? = null,
)

data class JumpPoint(
    val id: String,
    val turnId: String,
    val title: String,
    val subtitle: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

data class SearchResult(
    val id: String,
    val kind: SearchResultKind,
    val title: String,
    val subtitle: String = "",
    val focusTarget: FocusTarget = FocusTarget.None,
)

data class PluginPackageRecord(
    val id: String,
    val name: String,
    val version: String = "",
    val source: String = "",
    val installed: Boolean = false,
    val enabled: Boolean = false,
    val authenticated: Boolean = false,
)

data class McpServerRecord(
    val id: String,
    val name: String,
    val transport: String = "",
    val authenticated: Boolean = false,
    val connected: Boolean = false,
    val toolCount: Int = 0,
    val error: String = "",
)

data class BrowserSessionRecord(
    val id: String,
    val url: String,
    val title: String = "",
    val previewPath: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

data class TerminalBindingState(
    val id: String,
    val callId: String = "",
    val title: String,
    val command: String = "",
    val output: String = "",
    val status: ToolExecutionStatus = ToolExecutionStatus.QUEUED,
    val exitCode: Int? = null,
    val error: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class ExecutionSessionState(
    val conversationId: Long? = null,
    val project: String = "",
    val turns: List<TurnState> = emptyList(),
    val activeTurnId: String? = null,
    val latestTurnId: String? = null,
    val isWorking: Boolean = false,
    val backgroundTasks: List<BackgroundTaskState> = emptyList(),
    val terminalBindings: List<TerminalBindingState> = emptyList(),
    val artifacts: List<ArtifactState> = emptyList(),
    val pendingApprovals: Int = 0,
    val goal: GoalState = GoalState(),
    val draft: ComposerDraftState = ComposerDraftState(),
    val focusTarget: FocusTarget = FocusTarget.None,
    val sidePanelTab: SidePanelTabId? = null,
    val jumpPoints: List<JumpPoint> = emptyList(),
)

fun GoalState(goal: ConversationGoal): GoalState = GoalState(
    objective = goal.text,
    status = goal.status,
    note = goal.note,
    tokenBudget = goal.tokenBudget,
    tokensUsed = goal.tokensUsed,
    startedAt = goal.startedAt,
    updatedAt = goal.updatedAt,
)

fun toolTerminalSessionKey(callId: String): String = "tool:$callId"

fun liveTerminalSessionKey(sessionId: String): String = "live:$sessionId"

fun toolTarget(
    name: String,
    args: String,
    imageUrls: List<String> = emptyList(),
    toolCallId: String = "",
): Pair<FocusTarget, SidePanelTabId?> {
    val editedPath = ToolArgs.editedPath(name, args)
    if (editedPath.isNotBlank()) return FocusTarget.Diff(editedPath) to SidePanelTabId.DIFF
    val filePath = ToolArgs.filePath(name, args)
    if (filePath.isNotBlank()) return FocusTarget.File(filePath) to SidePanelTabId.REVIEW
    val webUrl = ToolArgs.webUrl(name, args)
    if (webUrl.isNotBlank()) return FocusTarget.Browser(webUrl) to SidePanelTabId.BROWSER
    if (name == "run_shell") {
        val command = ToolArgs.value(args, "command")
        return FocusTarget.Terminal(toolCallId = toolCallId, command = command) to SidePanelTabId.TERMINAL
    }
    if (imageUrls.isNotEmpty()) {
        val artifactId = buildString {
            append(if (toolCallId.isNotBlank()) toolCallId else name)
            append("-image-")
            append(imageUrls.size)
        }
        return FocusTarget.Reference(artifactId) to SidePanelTabId.REFERENCE
    }
    return FocusTarget.None to null
}
