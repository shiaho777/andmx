package com.andmx.ui2.chat

data class TurnEntry(
    val number: Int,
    val summary: String,
    val startIndex: Int,
    val endIndex: Int,
    val toolCalls: Int,
)

object TurnIndex {

    fun from(items: List<TimelineItem>): List<TurnEntry> {
        val starts = items.mapIndexedNotNull { i, item ->
            if (item is TimelineItem.Message && item.message.role == "user") i else null
        }
        if (starts.isEmpty()) return emptyList()
        return starts.mapIndexed { n, start ->
            val end = (starts.getOrNull(n + 1) ?: items.size) - 1
            var tools = 0
            for (i in start..end) {
                when (val item = items[i]) {
                    is TimelineItem.Tool -> tools++
                    is TimelineItem.ToolGroup -> tools += item.tools.size
                    is TimelineItem.TurnProcess -> tools += item.fold.toolCalls
                    else -> Unit
                }
            }
            val message = (items[start] as TimelineItem.Message).message
            TurnEntry(
                number = n + 1,
                summary = summarize(message.content),
                startIndex = start,
                endIndex = end,
                toolCalls = tools,
            )
        }
    }

    fun summarize(content: String): String =
        content.trim().replace(Regex("\\s+"), " ").take(40)
}