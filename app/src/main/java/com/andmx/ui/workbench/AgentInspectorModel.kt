package com.andmx.ui.workbench

import com.andmx.agent.TokenEstimate
import com.andmx.agent.ToolArgs
import com.andmx.agent.contextPressureLabel
import com.andmx.ui.conversation.ChatItem

internal data class AgentInspectorSnapshot(
    val project: String,
    val model: String,
    val baseUrl: String,
    val apiConfigured: Boolean,
    val approvalModeLabel: String,
    val goalText: String,
    val goalPhaseLabel: String,
    val goalNote: String,
    val busy: Boolean,
    val reasoningEffort: String,
    val persona: String,
    val messageCount: Int,
    val userMessages: Int,
    val assistantMessages: Int,
    val toolEvents: Int,
    val runningTools: Int,
    val failedTools: Int,
    val approvalEvents: Int,
    val pendingApprovals: Int,
    val changedFiles: Int,
    val sourceLinks: Int,
    val uiReferences: Int,
    val tokenEstimate: Int,
    val contextPressure: String,
    val builtInTools: Int,
    val totalTools: Int,
    val mcpServers: Int,
)

internal fun buildAgentInspectorSnapshot(
    project: String,
    model: String,
    baseUrl: String,
    apiConfigured: Boolean,
    approvalModeLabel: String,
    goalText: String,
    goalPhaseLabel: String,
    goalNote: String,
    busy: Boolean,
    reasoningEffort: String,
    persona: String,
    items: List<ChatItem>,
    changedFiles: Int,
    builtInTools: Int,
    totalTools: Int,
    mcpServers: Int,
): AgentInspectorSnapshot {
    val tokenInputs = items.mapNotNull {
        when (it) {
            is ChatItem.User -> it.text
            is ChatItem.Assistant -> it.text
            is ChatItem.ToolUse -> it.output
            is ChatItem.Approval -> it.summary
        }
    }
    val toolEvents = items.filterIsInstance<ChatItem.ToolUse>()
    val sourceLinks = toolEvents.mapNotNull { tool ->
        val path = ToolArgs.filePath(tool.name, tool.args)
        val url = ToolArgs.webUrl(tool.name, tool.args)
        path.ifBlank { url }.takeIf { it.isNotBlank() }
    }.distinct()
    val uiReferences = buildUiReferenceLedger(items).attachmentCount
    val tokenEstimate = TokenEstimate.estimateAll(tokenInputs)

    return AgentInspectorSnapshot(
        project = project,
        model = model,
        baseUrl = baseUrl,
        apiConfigured = apiConfigured,
        approvalModeLabel = approvalModeLabel,
        goalText = goalText,
        goalPhaseLabel = goalPhaseLabel,
        goalNote = goalNote,
        busy = busy,
        reasoningEffort = reasoningEffort,
        persona = persona,
        messageCount = items.size,
        userMessages = items.count { it is ChatItem.User },
        assistantMessages = items.count { it is ChatItem.Assistant },
        toolEvents = toolEvents.size,
        runningTools = toolEvents.count { it.running },
        failedTools = toolEvents.count { it.error },
        approvalEvents = items.count { it is ChatItem.Approval },
        pendingApprovals = items.filterIsInstance<ChatItem.Approval>().count { !it.resolved },
        changedFiles = changedFiles,
        sourceLinks = sourceLinks.size,
        uiReferences = uiReferences,
        tokenEstimate = tokenEstimate,
        contextPressure = contextPressureLabel(tokenEstimate),
        builtInTools = builtInTools,
        totalTools = totalTools,
        mcpServers = mcpServers,
    )
}

internal fun inspectorNextAction(snapshot: AgentInspectorSnapshot): String = when {
    !snapshot.apiConfigured -> "先配置模型与 API 密钥"
    snapshot.pendingApprovals > 0 -> "处理 ${snapshot.pendingApprovals} 个授权请求"
    snapshot.busy || snapshot.runningTools > 0 -> "等待当前工具和模型收束"
    snapshot.failedTools > 0 -> "检查失败工具输出并重试"
    snapshot.changedFiles > 0 -> "审查 Diff 面板中的 ${snapshot.changedFiles} 个变更"
    snapshot.uiReferences > 0 && snapshot.toolEvents == 0 -> "先提取 ${snapshot.uiReferences} 个 UI 参考的界面模式"
    snapshot.contextPressure == "需要压缩" -> "运行 /handoff 后开启新线程"
    snapshot.goalText.isBlank() -> "输入目标开始当前线程"
    else -> "继续推进目标或生成交接摘要"
}

internal fun contextPressureFraction(tokenEstimate: Int): Float =
    (tokenEstimate / 100_000f).coerceIn(0.04f, 1f)
