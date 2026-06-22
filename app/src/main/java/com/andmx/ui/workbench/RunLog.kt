package com.andmx.ui.workbench

import com.andmx.agent.ToolArgs
import com.andmx.agent.label
import com.andmx.ui.conversation.ChatItem

enum class RunLogKind { USER, ASSISTANT, TOOL, APPROVAL }

enum class RunLogState { DONE, RUNNING, FAILED, WAITING, DENIED }

enum class RunLogTargetKind { NONE, FILE, WEB }

data class RunLogEntry(
    val key: Long,
    val kind: RunLogKind,
    val state: RunLogState,
    val title: String,
    val detail: String,
    val targetUrl: String = "",
    val targetPath: String = "",
    val targetKind: RunLogTargetKind = RunLogTargetKind.NONE,
)

fun runLogEntries(items: List<ChatItem>, limit: Int = 12): List<RunLogEntry> =
    items.mapNotNull { item ->
        when (item) {
            is ChatItem.User -> RunLogEntry(
                key = item.key,
                kind = RunLogKind.USER,
                state = RunLogState.DONE,
                title = "用户目标",
                detail = item.text.singleLinePreview(120),
            )
            is ChatItem.Assistant -> RunLogEntry(
                key = item.key,
                kind = RunLogKind.ASSISTANT,
                state = RunLogState.DONE,
                title = "助手回复",
                detail = item.text.singleLinePreview(120),
            )
            is ChatItem.ToolUse -> {
                val url = ToolArgs.webUrl(item.name, item.args)
                val path = ToolArgs.filePath(item.name, item.args).ifBlank { ToolArgs.editedPath(item.name, item.args) }
                RunLogEntry(
                    key = item.key,
                    kind = RunLogKind.TOOL,
                    state = when {
                        item.running -> RunLogState.RUNNING
                        item.error -> RunLogState.FAILED
                        else -> RunLogState.DONE
                    },
                    title = "工具 · ${item.name}",
                    detail = ToolArgs.preview(item.name, item.args, limit = 120).singleLinePreview(120),
                    targetUrl = url,
                    targetPath = path,
                    targetKind = when {
                        url.isNotBlank() -> RunLogTargetKind.WEB
                        path.isNotBlank() -> RunLogTargetKind.FILE
                        else -> RunLogTargetKind.NONE
                    },
                )
            }
            is ChatItem.Approval -> RunLogEntry(
                key = item.key,
                kind = RunLogKind.APPROVAL,
                state = when {
                    !item.resolved -> RunLogState.WAITING
                    item.allowed -> RunLogState.DONE
                    else -> RunLogState.DENIED
                },
                title = "授权 · ${item.risk.label} · ${item.toolName}",
                detail = item.summary.singleLinePreview(120),
            )
        }
    }.takeLast(limit).asReversed()

fun RunLogState.runLogStateLabel(): String = when (this) {
    RunLogState.DONE -> "完成"
    RunLogState.RUNNING -> "运行中"
    RunLogState.FAILED -> "失败"
    RunLogState.WAITING -> "等待"
    RunLogState.DENIED -> "拒绝"
}

fun activitySummaryText(items: List<ChatItem>, limit: Int = 12): String = buildString {
    val entries = runLogEntries(items, limit)
    appendLine("## 最近活动")
    if (entries.isEmpty()) {
        appendLine("- 尚无运行记录")
        appendLine()
        appendLine("### 建议")
        appendLine("- 开始对话或运行工具后, 这里会按时间线汇总用户、助手、工具和授权事件")
        return@buildString
    }
    entries.forEach { entry ->
        appendLine("- ${entry.state.runLogStateLabel()} · ${entry.title}: ${entry.detail}")
        when (entry.targetKind) {
            RunLogTargetKind.FILE -> appendLine("  - 文件: `${entry.targetPath}`")
            RunLogTargetKind.WEB -> appendLine("  - 网页: ${entry.targetUrl}")
            RunLogTargetKind.NONE -> Unit
        }
    }
    appendLine()
    appendLine("### 相关入口")
    appendLine("- `/plan` 查看当前计划")
    appendLine("- `/changes` 查看待审变更")
    appendLine("- `/verify` 查看验证结果")
}

private fun String.singleLinePreview(limit: Int): String =
    lineSequence().joinToString(" ") { it.trim() }
        .replace(Regex("\\s+"), " ")
        .ifBlank { "(空)" }
        .take(limit)
