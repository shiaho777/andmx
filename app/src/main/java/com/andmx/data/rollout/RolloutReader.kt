package com.andmx.data.rollout

import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Reads a rollout JSONL file and reconstructs the full session state.
 *
 * Used for:
 * - Session recovery after crash/restart
 * - Loading conversation history with full turn context
 * - Exporting session transcripts
 */
class RolloutReader(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    companion object {
        private const val TAG = "RolloutReader"
    }

    data class SessionReplay(
        val sessionMeta: SessionMeta?,
        val turns: List<TurnReplay>,
        val responseItems: List<ResponseItem>,
        val events: List<EventMsg>,
    )

    data class TurnReplay(
        val context: TurnContext,
        val items: List<ResponseItem>,
        val events: List<EventMsg>,
    )

    /** Read a rollout file and return the full replay. */
    fun read(file: File): SessionReplay {
        if (!file.exists()) return SessionReplay(null, emptyList(), emptyList(), emptyList())

        var sessionMeta: SessionMeta? = null
        val allItems = mutableListOf<ResponseItem>()
        val allEvents = mutableListOf<EventMsg>()
        val turns = mutableListOf<TurnReplay>()
        var currentTurnCtx: TurnContext? = null
        val currentTurnItems = mutableListOf<ResponseItem>()
        val currentTurnEvents = mutableListOf<EventMsg>()

        file.useLines { lines ->
            for (line in lines) {
                val entry = parseEntry(line) ?: continue
                when (entry.type) {
                    "session_meta" -> {
                        sessionMeta = decodePayload(entry.payload, SessionMeta.serializer())
                    }
                    "turn_context" -> {
                        // Flush previous turn
                        currentTurnCtx?.let { ctx ->
                            turns.add(TurnReplay(ctx, currentTurnItems.toList(), currentTurnEvents.toList()))
                        }
                        currentTurnCtx = decodePayload(entry.payload, TurnContext.serializer())
                        currentTurnItems.clear()
                        currentTurnEvents.clear()
                    }
                    "response_item" -> {
                        val item = decodePayload(entry.payload, ResponseItem.serializer())
                        allItems.add(item)
                        currentTurnItems.add(item)
                    }
                    "event_msg" -> {
                        val msg = decodePayload(entry.payload, EventMsg.serializer())
                        allEvents.add(msg)
                        currentTurnEvents.add(msg)
                    }
                }
            }
        }

        // Flush last turn
        currentTurnCtx?.let { ctx ->
            turns.add(TurnReplay(ctx, currentTurnItems.toList(), currentTurnEvents.toList()))
        }

        return SessionReplay(sessionMeta, turns, allItems, allEvents)
    }

    /** Read only the session metadata (fast, stops after first entry). */
    fun readSessionMeta(file: File): SessionMeta? {
        if (!file.exists()) return null
        file.useLines { lines ->
            for (line in lines) {
                val entry = parseEntry(line) ?: continue
                if (entry.type == "session_meta") {
                    return decodePayload(entry.payload, SessionMeta.serializer())
                }
            }
        }
        return null
    }

    /** Extract a readable transcript (user/assistant messages only) from a rollout. */
    fun readTranscript(file: File): List<Pair<String, String>> {
        val replay = read(file)
        return replay.responseItems
            .filter { it.role != null && it.content != null }
            .map { it.role!! to it.content!! }
    }

    private fun parseEntry(line: String): RolloutEntry? {
        if (line.isBlank()) return null
        return runCatching {
            json.decodeFromString(RolloutEntry.serializer(), line.trim())
        }.getOrElse {
            Log.w(TAG, "Failed to parse rollout line: ${line.take(100)}")
            null
        }
    }

    private fun <T> decodePayload(
        payload: kotlinx.serialization.json.JsonObject,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T = json.decodeFromJsonElement(serializer, payload)
}
