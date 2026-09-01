package com.andmx.ui2.chat

/**
 * Turn 过程折叠（dsh web turn-process 对齐）。
 *
 * 纯函数：输入时间线，输出每个 Turn 的折叠模型。turn 进行中所有过程行保持
 * 展开；turn 结束（出现最终答案，即非 process 的 assistant 消息）后，最终
 * 答案之前的推理/过程消息/工具行折叠为一句摘要「N 步工具调用 · M 段说明」。
 * 用户消息、审批、错误、最终答案本身永不折叠。
 */
object TurnProcessFolding {

    /** 一个 Turn 的折叠控制。 */
    data class Fold(
        /** 折叠控件插入的 sortKey（在首个过程行之前）。 */
        val sortKey: Long,
        /** 摘要计数：工具调用步数。 */
        val toolCalls: Int,
        /** 摘要计数：子代理委托数。 */
        val subagents: Int,
        /** 摘要计数：回复性过程说明（非最终答案的 assistant 消息）。 */
        val narrations: Int,
    ) {
        /** 三个计数全为零时显示「思考了一会儿」；否则拼计数。 */
        val hasCounts: Boolean get() = toolCalls > 0 || subagents > 0 || narrations > 0

        fun label(): String {
            if (!hasCounts) return "思考了一会儿"
            val parts = mutableListOf<String>()
            if (toolCalls > 0) parts += "$toolCalls 步工具调用"
            if (narrations > 0) parts += "$narrations 段说明"
            if (subagents > 0) parts += "$subagents 个子代理"
            return parts.joinToString(" · ")
        }
    }

    /**
     * 扫描时间线，为每个「已关闭」（出现最终答案）的 Turn 产出一个折叠。
     * Turn 边界 = 用户消息。最终答案 = 其后最后一条非 process 的 assistant 消息。
     * 时间线尚在流式（Turn 未关闭）时不产出该 Turn 的折叠。
     */
    fun folds(timeline: List<TimelineItem>): List<Fold> {
        val out = mutableListOf<Fold>()
        var turnOpen = false
        var toolCalls = 0
        var subagents = 0
        var narrations = 0
        var firstProcessKey = -1L

        fun closeTurn(finalKey: Long) {
            if (turnOpen && firstProcessKey in 1 until finalKey) {
                out += Fold(
                    sortKey = firstProcessKey,
                    toolCalls = toolCalls,
                    subagents = subagents,
                    narrations = narrations,
                )
            }
            turnOpen = false
            toolCalls = 0
            subagents = 0
            narrations = 0
            firstProcessKey = -1L
        }

        for (item in timeline) {
            when (item) {
                is TimelineItem.Message -> {
                    val m = item.message
                    if (m.role == "user") {
                        closeTurn(finalKey = m.sortKey)
                        turnOpen = true
                    } else if (m.role == "assistant" && !m.isProcess && !m.isStreaming) {
                        closeTurn(finalKey = m.sortKey)
                    } else if (m.role == "assistant" && m.isProcess) {
                        narrations += 1
                        if (firstProcessKey < 0) firstProcessKey = m.sortKey
                    }
                }
                is TimelineItem.Tool -> {
                    if (turnOpen && !item.tool.isRunning) {
                        toolCalls += 1
                        if (firstProcessKey < 0) firstProcessKey = item.sortKey
                    }
                }
                is TimelineItem.ToolGroup -> {
                    if (turnOpen) {
                        val settled = item.tools.count { !it.isRunning }
                        if (settled > 0) {
                            toolCalls += settled
                            if (firstProcessKey < 0) firstProcessKey = item.sortKey
                        }
                    }
                }
                is TimelineItem.Reasoning -> {
                    if (turnOpen && firstProcessKey < 0) firstProcessKey = item.sortKey
                }
                is TimelineItem.SubAgent -> {
                    if (turnOpen && item.agent.state != "RUNNING") {
                        subagents += 1
                        if (firstProcessKey < 0) firstProcessKey = item.sortKey
                    }
                }
                // 审批/工作指示/折叠摘要行本身不参与折叠。
                is TimelineItem.Approval -> Unit
                is TimelineItem.Working -> Unit
                is TimelineItem.TurnProcess -> Unit
            }
        }
        // 流尾：没有最终答案收尾的 Turn 保持展开（Turn 未关闭），不产出折叠。
        return out
    }

    /**
     * 返回应被折叠隐藏的时间线条目（stableId 集合）。每个折叠覆盖其 sortKey
     * 之后、直到用户消息或最终答案出现前的过程行。
     */
    fun hiddenIds(timeline: List<TimelineItem>, folds: List<Fold>): Set<String> {
        if (folds.isEmpty()) return emptySet()
        val hidden = mutableSetOf<String>()
        var covering: Fold? = null
        var foldIdx = 0
        for (item in timeline) {
            val key = item.sortKey
            while (foldIdx < folds.size && folds[foldIdx].sortKey <= key) {
                covering = folds[foldIdx]
                foldIdx += 1
            }
            if (covering == null) continue
            when (item) {
                is TimelineItem.Message -> {
                    val m = item.message
                    if (m.role == "user" || (m.role == "assistant" && !m.isProcess && !m.isStreaming)) {
                        covering = null
                    } else if (key >= covering.sortKey) {
                        hidden += item.stableId
                    }
                }
                is TimelineItem.Tool -> if (key >= covering.sortKey) hidden += item.stableId
                is TimelineItem.ToolGroup -> if (key >= covering.sortKey) hidden += item.stableId
                is TimelineItem.Reasoning -> if (key >= covering.sortKey) hidden += item.stableId
                is TimelineItem.SubAgent -> if (key >= covering.sortKey) hidden += item.stableId
                is TimelineItem.Approval -> Unit
                is TimelineItem.Working -> Unit
                is TimelineItem.TurnProcess -> Unit
            }
        }
        return hidden
    }
}
