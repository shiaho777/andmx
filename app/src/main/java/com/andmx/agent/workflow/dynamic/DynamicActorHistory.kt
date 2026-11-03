package com.andmx.agent.workflow.dynamic

import com.andmx.llm.ApiMessage

/**
 * Dynamic actor conversation history kept across asks.
 *
 * System blocks are rebuilt by every AgentEngine, so only the conversation turns are
 * stored. Trimming starts at a user message so a restored history never opens with an
 * orphan tool result.
 */
object DynamicActorHistory {
    const val DEFAULT_LIMIT = 120

    fun snapshot(messages: List<ApiMessage>, limit: Int = DEFAULT_LIMIT): List<ApiMessage> {
        val turns = messages.filter { it.role != "system" }
        if (turns.size <= limit) return turns
        val tail = turns.takeLast(limit)
        val firstUser = tail.indexOfFirst { it.role == "user" }
        return if (firstUser > 0) tail.drop(firstUser) else tail
    }

    /** The prefix a successor actor inherits from its predecessor's transcript. */
    fun copyPrefix(messages: List<ApiMessage>, messageCount: Int): List<ApiMessage> {
        if (messageCount <= 0) return emptyList()
        return snapshot(messages.take(messageCount))
    }
}
