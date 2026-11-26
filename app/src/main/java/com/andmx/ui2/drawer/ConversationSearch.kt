package com.andmx.ui2.drawer

import com.andmx.data.ConversationEntity

internal fun filterConversations(
    conversations: List<ConversationEntity>,
    query: String,
    bodyMatchIds: Set<Long> = emptySet(),
): List<ConversationEntity> {
    val term = query.trim()
    if (term.isEmpty()) return conversations
    return conversations.filter {
        it.title.contains(term, ignoreCase = true) ||
            it.project.contains(term, ignoreCase = true) ||
            it.firstUserMessage.contains(term, ignoreCase = true) ||
            it.id in bodyMatchIds
    }
}
