package com.andmx.agent.hooks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Parses `hooks.json` payloads shared by user hooks (`/root/.andmx/hooks.json`),
 * project hooks (`.zcode/hooks.json`) and plugin manifests.
 */
object HookConfigParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(
        raw: String,
        source: HookSystem.HookSource,
        cwd: String? = null,
        namePrefix: String = "",
    ): List<HookSystem.HookConfig> {
        if (raw.isBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> root["hooks"] as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val eventRaw = obj.string("event")
            val command = obj.string("command")
            if (command.isBlank()) return@mapNotNull null
            val event = HookSystem.parseEvent(eventRaw) ?: return@mapNotNull null
            val timeoutMs = timeoutMs(obj)
            val async = obj.bool("async")
            HookSystem.HookConfig(
                event = event,
                command = command,
                timeoutMs = timeoutMs,
                mode = if (async) HookSystem.HookMode.ASYNC else HookSystem.HookMode.SYNC,
                name = listOf(namePrefix, obj.string("name"))
                    .filter { it.isNotBlank() }
                    .joinToString(":")
                    .ifBlank { "${source.name.lowercase()}:$eventRaw" },
                matcher = obj.string("matcher"),
                source = source,
                cwd = obj.string("cwd").ifBlank { cwd },
            )
        }
    }

    private fun timeoutMs(obj: JsonObject): Long {
        val explicitMs = obj.string("timeout_ms").toLongOrNull()
            ?: obj.string("timeoutMs").toLongOrNull()
        val seconds = obj.string("timeout").toLongOrNull()
        val raw = explicitMs ?: seconds?.times(1000L) ?: 10_000L
        return raw.coerceIn(1_000L, 600_000L)
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()

    private fun JsonObject.bool(key: String): Boolean {
        val value = this[key] as? JsonPrimitive ?: return false
        return value.booleanOrNull ?: (value.contentOrNull == "true")
    }
}
