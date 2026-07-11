package com.andmx.ui2.chat

sealed class ChatEvent {
    data class UserMessage(val text: String) : ChatEvent()
    data class AssistantChunk(val text: String) : ChatEvent()
    data class AssistantComplete(val fullText: String) : ChatEvent()
    data class ToolCallStarted(val id: String, val name: String, val args: String) : ChatEvent()
    data class ToolCallFinished(val id: String, val output: String, val isError: Boolean) : ChatEvent()
    data class Error(val message: String) : ChatEvent()
    data object Done : ChatEvent()
}

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val role: String,
    val content: String,
    val isStreaming: Boolean = false
)

data class ToolCall(
    val id: String,
    val name: String,
    val args: String,
    val output: String? = null,
    val isRunning: Boolean = true,
    val isError: Boolean = false
)
