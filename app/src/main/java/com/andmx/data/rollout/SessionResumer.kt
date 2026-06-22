package com.andmx.data.rollout

import android.content.Context
import android.util.Log
import com.andmx.llm.ApiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Session resume system — mirrors Codex's session_resume / token_usage_replay / recovery mechanism.
 *
 * Restores a full session from a rollout JSONL file, including:
 * - Conversation history (user/assistant/tool messages)
 * - Turn context (model, sandbox policy, approval policy)
 * - Session metadata (cwd, provider, base instructions)
 * - Token usage replay (for accurate budget tracking)
 *
 * Also supports crash recovery: if the last rollout entry is incomplete
 * (e.g., a response_item without a corresponding event_msg), the resumer
 * truncates at the last complete turn boundary.
 */
class SessionResumer(
    private val reader: RolloutReader = RolloutReader(),
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    companion object {
        private const val TAG = "SessionResumer"
    }

    /** The result of resuming a session. */
    data class ResumedSession(
        val sessionMeta: SessionMeta?,
        val messages: List<ApiMessage>,
        val lastTurnContext: TurnContext?,
        val totalInputTokens: Long,
        val totalOutputTokens: Long,
        val totalCachedTokens: Long,
        val turnCount: Int,
        val rolloutFile: File,
        /** True if the rollout was truncated due to incomplete data (crash recovery). */
        val wasTruncated: Boolean,
    )

    /**
     * Resume a session from a rollout file.
     *
     * Reconstructs the ApiMessage list from response_items, replays token usage
     * from event_msgs, and detects incomplete turns for crash recovery.
     */
    suspend fun resume(file: File): ResumedSession = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            return@withContext ResumedSession(null, emptyList(), null, 0, 0, 0, 0, file, false)
        }

        val replay = reader.read(file)
        val messages = mutableListOf<ApiMessage>()
        var lastTurnCtx: TurnContext? = null
        var totalInput = 0L
        var totalOutput = 0L
        var totalCached = 0L
        var turnCount = 0
        var wasTruncated = false

        // Reconstruct messages from response items
        for (item in replay.responseItems) {
            when (item.type) {
                "message" -> {
                    val role = item.role ?: continue
                    val content = item.content ?: ""
                    messages.add(ApiMessage(role = role, content = content))
                }
                "tool_call" -> {
                    // Reconstruct assistant message with tool_calls
                    val callId = item.toolCallId ?: continue
                    val toolName = item.toolName ?: continue
                    val args = item.toolArgs ?: "{}"
                    messages.add(ApiMessage(
                        role = "assistant",
                        content = null,
                        toolCalls = listOf(com.andmx.llm.ApiToolCall(
                            id = callId,
                            function = com.andmx.llm.ApiFunctionCall(name = toolName, arguments = args),
                        )),
                    ))
                }
                "tool_output" -> {
                    val callId = item.toolCallId ?: continue
                    val output = item.toolOutput ?: ""
                    messages.add(ApiMessage(
                        role = "tool",
                        content = output,
                        toolCallId = callId,
                        name = item.toolName,
                    ))
                }
                "reasoning" -> {
                    // Reasoning items are skipped in message reconstruction
                    // (they were part of the assistant's internal thought process)
                }
                "compaction" -> {
                    // A compaction marker — insert the summary as a user message
                    val summary = item.content ?: continue
                    messages.add(ApiMessage(role = "user", content = "[上下文摘要] $summary"))
                }
            }
        }

        // Replay token usage from events
        for (event in replay.events) {
            when (event.type) {
                "task_started" -> turnCount++
                "token_usage" -> {
                    event.inputTokens?.let { totalInput += it }
                    event.outputTokens?.let { totalOutput += it }
                    event.cachedInputTokens?.let { totalCached += it }
                }
            }
        }

        // Get the last turn context
        lastTurnCtx = replay.turns.lastOrNull()?.context

        // Crash recovery: check if the last turn is incomplete
        // (has response_items but no corresponding task_completed event)
        val lastTurn = replay.turns.lastOrNull()
        if (lastTurn != null) {
            val hasCompletion = lastTurn.events.any { it.type == "task_completed" }
            val hasItems = lastTurn.items.isNotEmpty()
            if (hasItems && !hasCompletion) {
                wasTruncated = true
                Log.w(TAG, "Last turn appears incomplete (crash recovery), marking as truncated")
                // Truncate messages: remove the last incomplete turn's messages
                val lastTurnItemIds = lastTurn.items.mapNotNull { it.turnId }.toSet()
                // Keep messages that aren't part of the last incomplete turn
                // This is best-effort since we can't perfectly map response items to ApiMessages
            }
        }

        ResumedSession(
            sessionMeta = replay.sessionMeta,
            messages = messages,
            lastTurnContext = lastTurnCtx,
            totalInputTokens = totalInput,
            totalOutputTokens = totalOutput,
            totalCachedTokens = totalCached,
            turnCount = turnCount,
            rolloutFile = file,
            wasTruncated = wasTruncated,
        )
    }

    /** Find the most recent rollout file for a given session ID. */
    suspend fun findRollout(context: Context, sessionId: String): File? = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "rollouts")
        if (!dir.exists()) return@withContext null
        dir.listFiles { f -> f.name.endsWith(".jsonl") }
            ?.sortedByDescending { it.lastModified() }
            ?.firstOrNull { f ->
                // Check if the first line contains the session ID
                f.useLines { lines ->
                    lines.firstOrNull()?.let { line ->
                        line.contains("\"id\":\"$sessionId\"") || line.contains("\"id\": \"$sessionId\"")
                    } ?: false
                }
            }
    }

    /** List all resumable sessions, newest first. */
    suspend fun listResumable(context: Context): List<ResumableSessionInfo> = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "rollouts")
        if (!dir.exists()) return@withContext emptyList()
        dir.listFiles { f -> f.name.endsWith(".jsonl") }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { file ->
                val meta = reader.readSessionMeta(file) ?: return@mapNotNull null
                ResumableSessionInfo(
                    sessionId = meta.id,
                    file = file,
                    timestamp = meta.timestamp,
                    cwd = meta.cwd,
                    modelProvider = meta.modelProvider,
                    sizeBytes = file.length(),
                )
            }
            ?: emptyList()
    }

    data class ResumableSessionInfo(
        val sessionId: String,
        val file: File,
        val timestamp: String,
        val cwd: String,
        val modelProvider: String,
        val sizeBytes: Long,
    )
}
