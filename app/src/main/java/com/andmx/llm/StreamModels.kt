package com.andmx.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Incremental events produced while streaming a chat completion. */
sealed interface LlmStreamEvent {
    /** A chunk of assistant text. */
    data class Content(val delta: String) : LlmStreamEvent
    /** A chunk of model thinking / reasoning (hidden chain-of-thought style). */
    data class Reasoning(val delta: String) : LlmStreamEvent
    /** Incremental tool-call args as the model streams function arguments. */
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsDelta: String,
    ) : LlmStreamEvent
    /** The turn finished; [message] is the fully-assembled assistant message. */
    data class Completed(val message: ApiMessage) : LlmStreamEvent
    /** Rate limit or token usage update. */
    data class UsageUpdate(val usage: TokenUsage) : LlmStreamEvent
}

// ---- SSE wire chunks (OpenAI-compatible delta streaming) ----

@Serializable
data class ChatStreamChunk(
    val choices: List<StreamChoice> = emptyList(),
    /** Present in the final chunk of some providers. */
    val usage: JsonObject? = null,
)

@Serializable
data class StreamChoice(
    val delta: Delta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class Delta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: String? = null,
    @SerialName("tool_calls") val toolCalls: List<DeltaToolCall>? = null,
)

@Serializable
data class DeltaToolCall(
    val index: Int = 0,
    val id: String? = null,
    val function: DeltaFunction? = null,
)

@Serializable
data class DeltaFunction(
    val name: String? = null,
    val arguments: String? = null,
)
