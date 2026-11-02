package com.andmx.data.rollout

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.time.Instant

/**
 * Reads real token usage out of rollout JSONL files.
 *
 * Usage charts used to estimate tokens from message length. The rollout log is
 * the authoritative record: every completed turn writes a `token_usage` event
 * next to the `turn_context` that names the model.
 */
object RolloutUsageReader {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    data class UsageEvent(
        val atMs: Long,
        val model: String,
        val tokens: Long,
    )

    fun read(file: File): List<UsageEvent> {
        if (!file.isFile) return emptyList()
        val events = mutableListOf<UsageEvent>()
        var model = ""
        file.useLines { lines ->
            for (line in lines) {
                if (!line.contains("turn_context") && !line.contains("token_usage")) continue
                val entry = runCatching { json.parseToJsonElement(line) as? JsonObject }
                    .getOrNull() ?: continue
                when (entry.string("type")) {
                    "turn_context" -> {
                        val payload = entry["payload"] as? JsonObject ?: continue
                        model = payload.string("model")
                    }
                    "event_msg" -> {
                        val payload = entry["payload"] as? JsonObject ?: continue
                        if (payload.string("type") != "token_usage") continue
                        val at = parseTime(entry.string("timestamp")) ?: continue
                        val input = payload.long("inputTokens")
                        val output = payload.long("outputTokens")
                        val total = payload.long("totalTokens").takeIf { it > 0 } ?: (input + output)
                        if (total <= 0) continue
                        events += UsageEvent(atMs = at, model = model, tokens = total)
                    }
                }
            }
        }
        return events
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.long(key: String): Long =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L

    private fun parseTime(raw: String): Long? =
        runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
}
