package com.andmx.agent.permission

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal fun jsonObject(value: Any?): Map<String, Any?>? = when (value) {
    is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to v }
    is JsonObject -> value.entries.associate { (k, v) ->
        k to when (v) {
            is JsonPrimitive -> when {
                v.booleanOrNull != null -> v.booleanOrNull
                v.contentOrNull != null && v.contentOrNull != "null" -> v.content
                else -> null
            }
            is JsonObject -> v
            else -> v
        }
    }
    else -> null
}

internal fun jsonString(value: Any?, key: String): String? {
    val obj = jsonObject(value) ?: return null
    val raw = obj[key] ?: return null
    return when (raw) {
        is String -> raw
        is JsonPrimitive -> raw.contentOrNull
        else -> null
    }
}
