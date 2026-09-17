package com.andmx.ui2.chat

internal fun turnLog(
    messageId: Long,
    messages: List<ChatMessage>,
    reasonings: List<ReasoningItem>,
    tools: List<ToolCall>,
    approvals: List<ApprovalItem>,
    subAgents: List<SubAgentItem>,
): String {
    val targetIdx = messages.indexOfFirst { it.id == messageId }
    if (targetIdx < 0) return ""
    val target = messages[targetIdx]
    val endKey = target.sortKey
    var startIdx = targetIdx
    while (startIdx > 0 && messages[startIdx].role != "user") {
        startIdx--
    }
    if (messages.getOrNull(startIdx)?.role != "user") {
        startIdx = (0..targetIdx).lastOrNull { messages[it].role == "user" } ?: 0
    }
    val startKey = messages.getOrNull(startIdx)?.sortKey ?: 0L

    val inRangeMessages = messages.filter { m ->
        m.sortKey in startKey..endKey && m.content.isNotBlank()
    }
    val inRangeReasonings = reasonings
        .filter { it.sortKey in startKey..endKey && it.content.isNotBlank() }
    val inRangeTools = tools.filter { t ->
        val k = t.sortKey
        k in startKey..endKey || (k == 0L && t.id.isNotBlank() && t.sortKey <= endKey)
    }
    val inRangeApprovals = approvals.filter { it.sortKey in startKey..endKey }
    val inRangeSubs = subAgents.filter { it.sortKey in startKey..endKey }

    data class Line(val key: Long, val order: Int, val text: String)
    val lines = ArrayList<Line>()
    var order = 0
    fun add(key: Long, text: String) {
        lines += Line(key, order++, text)
    }

    inRangeMessages.forEach { m ->
        val title = when {
            m.role == "user" -> "用户"
            m.isProcess -> "过程"
            else -> "助手"
        }
        add(
            m.sortKey,
            buildString {
                appendLine("## $title")
                append(m.content.trimEnd())
            },
        )
    }
    inRangeReasonings.forEach { r ->
        add(
            r.sortKey,
            buildString {
                appendLine("## 思考")
                append(r.content.trimEnd())
            },
        )
    }
    inRangeTools.forEach { t ->
        val status = when {
            t.isRunning -> "运行中"
            t.isError -> "失败"
            else -> "完成"
        }
        add(
            t.sortKey.takeIf { it > 0L } ?: endKey,
            buildString {
                appendLine("## 工具 · ${t.name} · $status")
                if (t.args.isNotBlank()) {
                    appendLine("参数:")
                    appendLine(t.args.trimEnd())
                }
                val out = t.output?.trimEnd().orEmpty()
                if (out.isNotBlank()) {
                    appendLine("输出:")
                    append(out)
                } else if (!t.isRunning) {
                    append("(无输出)")
                }
            },
        )
    }
    inRangeApprovals.forEach { a ->
        add(
            a.sortKey,
            buildString {
                appendLine("## 审批 · ${a.toolName} · ${a.status}")
                append(a.summary.trimEnd())
            },
        )
    }
    inRangeSubs.forEach { s ->
        add(
            s.sortKey,
            buildString {
                appendLine("## 子代理 · ${s.state}")
                appendLine("任务: ${s.task.trimEnd()}")
                if (s.result.isNotBlank()) {
                    appendLine("结果:")
                    append(s.result.trimEnd())
                }
            },
        )
    }

    if (lines.isEmpty()) return target.content.trim()
    return lines
        .sortedWith(compareBy<Line> { it.key }.thenBy { it.order })
        .joinToString("\n\n") { it.text }
        .trim() + "\n"
}
