package com.andmx.ui2.chat

sealed class ChatEvent {
    data class UserMessage(val text: String) : ChatEvent()
    data class AssistantChunk(val text: String) : ChatEvent()
    data class ReasoningChunk(val text: String) : ChatEvent()
    data object ReasoningDone : ChatEvent()
    data class AssistantComplete(val fullText: String) : ChatEvent()
    data class ToolCallArgsDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val args: String,
    ) : ChatEvent()
    data class ToolCallStarted(val id: String, val name: String, val args: String) : ChatEvent()
    data class ToolCallFinished(val id: String, val output: String, val isError: Boolean) : ChatEvent()
    data class PlanUpdated(val steps: List<PlanStepUi>) : ChatEvent()
    data class ApprovalRequested(
        val id: String,
        val toolName: String,
        val summary: String,
        val modeLabel: String,
    ) : ChatEvent()
    data class ApprovalResolved(val id: String, val allowed: Boolean) : ChatEvent()
    data class SubAgentStarted(val agentId: String, val task: String) : ChatEvent()
    data class SubAgentDelta(val agentId: String, val text: String) : ChatEvent()
    data class SubAgentCompleted(val agentId: String, val result: String) : ChatEvent()
    data class SubAgentFailed(val agentId: String, val error: String) : ChatEvent()
    data class Error(val message: String) : ChatEvent()
    data object Done : ChatEvent()
}

data class PlanStepUi(
    val content: String,
    val status: String,
)

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val role: String,
    val content: String,
    val isStreaming: Boolean = false,
    val sortKey: Long = id,
    val isProcess: Boolean = false,
    val createdAt: Long = sortKey,
    val completedAt: Long = 0L,
)

data class ToolCall(
    val id: String,
    val name: String,
    val args: String,
    val output: String? = null,
    val isRunning: Boolean = true,
    val isError: Boolean = false,
    val sortKey: Long = System.currentTimeMillis(),
)

data class ApprovalItem(
    val id: String,
    val toolName: String,
    val summary: String,
    val modeLabel: String,
    val status: String = "pending",
    val sortKey: Long = System.currentTimeMillis(),
)


data class ReasoningItem(
    val id: String,
    val content: String,
    val isStreaming: Boolean = true,
    val sortKey: Long = System.currentTimeMillis(),
)
