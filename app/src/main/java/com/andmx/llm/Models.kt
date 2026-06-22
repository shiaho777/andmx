package com.andmx.llm

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Wire models for the OpenAI-compatible Chat Completions API (also spoken by
 * DeepSeek, Ollama, LM Studio, vLLM, ...). Kept separate from the agent's
 * domain types so the agent stays provider-agnostic.
 */

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val tools: List<ApiTool>? = null,
    val temperature: Double? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    val stream: Boolean = false,
)

/**
 * A chat message. [content] is plain text; when [imageUrls] is non-empty the
 * serializer emits OpenAI's multimodal content array (text + image_url parts).
 * [imageUrls] is encode-only (never read back from responses).
 */
@Serializable(with = ApiMessageSerializer::class)
data class ApiMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ApiToolCall>? = null,
    val toolCallId: String? = null,
    val name: String? = null,
    val imageUrls: List<String>? = null,
)

object ApiMessageSerializer : KSerializer<ApiMessage> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ApiMessage")

    override fun serialize(encoder: Encoder, value: ApiMessage) {
        val json = encoder as? JsonEncoder ?: error("ApiMessage requires Json")
        val obj = buildJsonObject {
            put("role", value.role)
            val imgs = value.imageUrls
            if (!imgs.isNullOrEmpty()) {
                put("content", buildJsonArray {
                    value.content?.let { addJsonObject { put("type", "text"); put("text", it) } }
                    imgs.forEach { url ->
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", url) }
                        }
                    }
                })
            } else if (value.content != null) {
                put("content", value.content)
            }
            value.toolCalls?.let { calls ->
                put("tool_calls", buildJsonArray {
                    calls.forEach { c ->
                        addJsonObject {
                            put("id", c.id); put("type", c.type)
                            putJsonObject("function") { put("name", c.function.name); put("arguments", c.function.arguments) }
                        }
                    }
                })
            }
            value.toolCallId?.let { put("tool_call_id", it) }
            value.name?.let { put("name", it) }
        }
        json.encodeJsonElement(obj)
    }

    override fun deserialize(decoder: Decoder): ApiMessage {
        val json = decoder as? JsonDecoder ?: error("ApiMessage requires Json")
        val obj = json.decodeJsonElement().jsonObject
        val contentEl = obj["content"]
        val content = when (contentEl) {
            is JsonPrimitive -> contentEl.takeIf { it.isString }?.content
            is JsonArray -> contentEl.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.content }.joinToString("\n").ifBlank { null }
            else -> null
        }
        val toolCalls = (obj["tool_calls"] as? JsonArray)?.map { e ->
            val o = e.jsonObject
            val fn = o["function"]!!.jsonObject
            ApiToolCall(
                id = o["id"]?.jsonPrimitive?.content ?: "",
                type = o["type"]?.jsonPrimitive?.content ?: "function",
                function = ApiFunctionCall(fn["name"]!!.jsonPrimitive.content, fn["arguments"]?.jsonPrimitive?.content ?: ""),
            )
        }
        return ApiMessage(
            role = obj["role"]?.jsonPrimitive?.content ?: "assistant",
            content = content,
            toolCalls = toolCalls,
            toolCallId = obj["tool_call_id"]?.jsonPrimitive?.content,
            name = obj["name"]?.jsonPrimitive?.content,
        )
    }
}

@Serializable
data class ApiToolCall(
    val id: String,
    val type: String = "function",
    val function: ApiFunctionCall,
)

@Serializable
data class ApiFunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
data class ApiTool(
    val type: String = "function",
    val function: ApiFunctionDef,
)

@Serializable
data class ApiFunctionDef(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class ChatResponse(
    val choices: List<Choice> = emptyList(),
)

@Serializable
data class Choice(
    val message: ApiMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)
