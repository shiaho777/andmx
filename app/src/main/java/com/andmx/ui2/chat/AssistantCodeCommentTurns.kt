package com.andmx.ui2.chat

private const val MAX_TURN_COMMENTS = 50

fun assistantCodeCommentsByTail(messages: List<ChatMessage>): Map<Long, List<AssistantCodeComment>> {
    val result = LinkedHashMap<Long, List<AssistantCodeComment>>()
    var turn = ArrayList<ChatMessage>()

    fun flush() {
        if (turn.isEmpty()) return
        val comments = ArrayList<AssistantCodeComment>()
        turn.filter { it.role == "assistant" }
            .forEach { comments += AssistantCodeComments.project(it.content).comments }
        val lastAssistant = turn.lastOrNull { it.role == "assistant" }
        val tail = lastAssistant?.takeIf { !it.isStreaming && !it.isProcess }
        if (tail != null && comments.isNotEmpty()) {
            result[tail.id] = comments.take(MAX_TURN_COMMENTS)
        }
        turn = ArrayList()
    }

    messages.sortedBy { it.sortKey }.forEach { message ->
        if (message.role == "user" && turn.isNotEmpty()) flush()
        turn += message
    }
    flush()
    return result
}
