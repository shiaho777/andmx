package com.andmx.ui2.chat

import com.andmx.llm.VideoAttachment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class QueuedMessage(
    val text: String,
    val images: List<String> = emptyList(),
    val videos: List<VideoAttachment> = emptyList(),
) {
    companion object {
        fun decode(raw: String): List<QueuedMessage> = runCatching {
            (Json.parseToJsonElement(raw) as? JsonArray).orEmpty().mapNotNull { item ->
                if (item is JsonPrimitive) item.contentOrNull?.let { QueuedMessage(it) }
                else runCatching { Json.decodeFromJsonElement(serializer(), item) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }
}
