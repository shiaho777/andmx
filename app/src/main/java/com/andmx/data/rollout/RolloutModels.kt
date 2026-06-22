package com.andmx.data.rollout

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Rollout JSONL models — mirrors Codex's rollout file format.
 *
 * Each line in a rollout .jsonl file is a [RolloutEntry] with a timestamp,
 * a type discriminator, and a payload. The format is append-only and
 * captures the full session lifecycle for replay and recovery.
 */

@Serializable
data class RolloutEntry(
    val timestamp: String,
    val type: String,
    val payload: JsonObject,
)

// ── Entry types ──────────────────────────────────────────

/** Written once at session start. */
@Serializable
data class SessionMeta(
    val id: String,
    val timestamp: String,
    val cwd: String,
    val originator: String = "AndMX",
    val cliVersion: String = "",
    val source: String = "android",
    val modelProvider: String = "",
    val baseInstructions: String = "",
)

/** Written at the start of each model turn. */
@Serializable
data class TurnContext(
    val turnId: String,
    val cwd: String,
    val currentDate: String,
    val timezone: String,
    val approvalPolicy: String,
    val sandboxPolicy: String,
    val model: String,
    val personality: String = "",
)

/** A response item: assistant message, tool call, tool output, reasoning, compaction. */
@Serializable
data class ResponseItem(
    val type: String,
    val role: String? = null,
    val content: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolOutput: String? = null,
    val isError: Boolean = false,
    val turnId: String? = null,
)

/** An event: task_started, task_completed, task_failed, token_usage. */
@Serializable
data class EventMsg(
    val type: String,
    val turnId: String? = null,
    val startedAt: Long? = null,
    val modelContextWindow: Int? = null,
    val errorMessage: String? = null,
    val inputTokens: Int? = null,
    val cachedInputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningOutputTokens: Int? = null,
    val totalTokens: Int? = null,
)

// ── Convenience builders ─────────────────────────────────

object RolloutEntryBuilder {
    fun sessionMeta(meta: SessionMeta): RolloutEntry = RolloutEntry(
        timestamp = meta.timestamp,
        type = "session_meta",
        payload = kotlinx.serialization.json.Json.encodeToJsonElement(SessionMeta.serializer(), meta) as JsonObject,
    )

    fun turnContext(ctx: TurnContext): RolloutEntry = RolloutEntry(
        timestamp = java.time.Instant.now().toString(),
        type = "turn_context",
        payload = kotlinx.serialization.json.Json.encodeToJsonElement(TurnContext.serializer(), ctx) as JsonObject,
    )

    fun responseItem(item: ResponseItem): RolloutEntry = RolloutEntry(
        timestamp = java.time.Instant.now().toString(),
        type = "response_item",
        payload = kotlinx.serialization.json.Json.encodeToJsonElement(ResponseItem.serializer(), item) as JsonObject,
    )

    fun eventMsg(msg: EventMsg): RolloutEntry = RolloutEntry(
        timestamp = java.time.Instant.now().toString(),
        type = "event_msg",
        payload = kotlinx.serialization.json.Json.encodeToJsonElement(EventMsg.serializer(), msg) as JsonObject,
    )
}
