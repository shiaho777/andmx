package com.andmx.ui2.chat

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class TimelineItem {
    abstract val sortKey: Long
    abstract val stableId: String

    data class Message(
        val message: ChatMessage,
        override val sortKey: Long = message.sortKey,
    ) : TimelineItem() {
        override val stableId: String = "m-${message.id}"
    }

    data class Reasoning(
        val item: ReasoningItem,
        override val sortKey: Long = item.sortKey,
    ) : TimelineItem() {
        override val stableId: String = "r-${item.id}"
    }

    data class Tool(
        val tool: ToolCall,
        override val sortKey: Long,
    ) : TimelineItem() {
        override val stableId: String = "t-${tool.id}"
    }

    data class ToolGroup(
        val tools: List<ToolCall>,
        override val sortKey: Long,
    ) : TimelineItem() {
        override val stableId: String = "tg-${tools.firstOrNull()?.id ?: sortKey}"
    }

    data class Approval(
        val item: ApprovalItem,
        override val sortKey: Long = item.sortKey,
    ) : TimelineItem() {
        override val stableId: String = "a-${item.id}"
    }

    data class SubAgent(
        val agent: SubAgentItem,
        override val sortKey: Long = agent.sortKey,
    ) : TimelineItem() {
        override val stableId: String = "s-${agent.id}"
    }

    data class Working(
        override val sortKey: Long = Long.MAX_VALUE - 1,
    ) : TimelineItem() {
        override val stableId: String = "working"
    }
}

data class SubAgentItem(
    val id: String,
    val task: String,
    val state: String,
    val result: String = "",
    val sortKey: Long = System.currentTimeMillis(),
)

fun buildTimeline(
    messages: List<ChatMessage>,
    tools: List<ToolCall>,
    approvals: List<ApprovalItem> = emptyList(),
    subAgents: List<SubAgentItem> = emptyList(),
    reasonings: List<ReasoningItem> = emptyList(),
    showWorking: Boolean = false,
): List<TimelineItem> {
    val raw = ArrayList<TimelineItem>(messages.size + tools.size + approvals.size + subAgents.size + reasonings.size + 1)
    messages.forEach { raw += TimelineItem.Message(it) }
    reasonings.forEach { r -> raw += TimelineItem.Reasoning(r) }
    tools.forEach { t ->
        val key = t.sortKey.takeIf { it > 0L } ?: t.id.hashCode().toLong().and(0x7fffffffL)
        raw += TimelineItem.Tool(t, key)
    }
    approvals.forEach { a -> raw += TimelineItem.Approval(a) }
    subAgents.forEach { s -> raw += TimelineItem.SubAgent(s) }
    val ordered = raw.sortedWith(compareBy<TimelineItem> { it.sortKey }.thenBy { it.stableId })
    val grouped = ArrayList<TimelineItem>(ordered.size)
    var i = 0
    while (i < ordered.size) {
        val item = ordered[i]
        if (item is TimelineItem.Tool && shouldGroupTool(item.tool)) {
            val batch = mutableListOf(item.tool)
            var j = i + 1
            while (j < ordered.size) {
                val next = ordered[j]
                if (next is TimelineItem.Tool && shouldGroupTool(next.tool)) {
                    batch += next.tool
                    j++
                } else break
            }
            if (batch.size >= 2) {
                grouped += TimelineItem.ToolGroup(batch, batch.minOf { it.sortKey })
            } else {
                grouped += item
            }
            i = j
        } else {
            grouped += item
            i++
        }
    }
    if (showWorking) {
        grouped += TimelineItem.Working()
    }
    return grouped
}

private fun shouldGroupTool(tool: ToolCall): Boolean {
    if (tool.isRunning || tool.isError) return false
    return tool.name in setOf(
        "read_file", "list_dir", "grep", "glob", "git", "get_goal",
    )
}

object ChatActionBus {
    sealed class Action {
        data class OpenFile(val path: String) : Action()
        data object OpenTerminal : Action()
        data class OpenUrl(val url: String) : Action()
        data object OpenSettings : Action()
        data object OpenSkillsSettings : Action()
        data object OpenSearch : Action()
    }

    private val _actions = MutableSharedFlow<Action>(extraBufferCapacity = 8)
    val actions: SharedFlow<Action> = _actions.asSharedFlow()

    fun openFile(path: String) {
        if (path.isNotBlank()) _actions.tryEmit(Action.OpenFile(path))
    }

    fun openTerminal() {
        _actions.tryEmit(Action.OpenTerminal)
    }

    fun openUrl(url: String) {
        if (url.isNotBlank()) _actions.tryEmit(Action.OpenUrl(url))
    }

    fun openSettings() {
        _actions.tryEmit(Action.OpenSettings)
    }

    fun openSkillsSettings() {
        _actions.tryEmit(Action.OpenSkillsSettings)
    }

    fun openSearch() {
        _actions.tryEmit(Action.OpenSearch)
    }
}
