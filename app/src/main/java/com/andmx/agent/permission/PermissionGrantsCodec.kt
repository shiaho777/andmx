package com.andmx.agent.permission

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** JSON codec for the session-scoped permission grants persisted on a conversation. */
object PermissionGrantsCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PermissionService.SessionGrant.serializer())

    fun encode(grants: List<PermissionService.SessionGrant>): String =
        json.encodeToString(serializer, grants)

    fun decode(raw: String): List<PermissionService.SessionGrant> {
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }
}
