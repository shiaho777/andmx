package com.andmx.llm.wire

import com.andmx.llm.ApiFunctionCall
import com.andmx.llm.ApiMessage
import com.andmx.llm.ApiToolCall
import com.andmx.llm.ChatRequest
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.provider.ReasoningStyle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI Responses wire adapter: `POST {base}/responses`.
 *
 * Structurally different from Chat Completions: the system prompt rides the
 * top-level `instructions` field, history is a typed `input` item array
 * (`function_call` / `function_call_output` items instead of `tool_calls` /
 * `role:"tool"` messages), tool definitions are flat (no nested `function`
 * object), and SSE is a stream of typed `response.*` events rather than
 * `chat.completion.chunk` deltas. `store:false` keeps the turn stateless —
 * the full history is replayed on every request, so no `previous_response_id`
 * bookkeeping is needed.
 */
object OpenAiResponsesAdapter : WireAdapter {
    private val json = Json { ignoreUnknownKeys = true }

    override fun endpointUrl(base: String): String = base.trimEnd('/') + "/responses"

    override fun authHeader(apiKey: String): Pair<String, String>? =
        if (apiKey.isNotBlank()) "Authorization" to "Bearer $apiKey" else null

    override suspend fun listModels(def: ProviderDefinition): List<String> = withContext(Dispatchers.IO) {
        OpenAiChatAdapter.listModels(def)
    }

    // ── Request encoding ──────────────────────────────────────────────────────

    override fun encodeRequest(req: ChatRequest, provider: ProviderDefinition): String {
        val instructions = req.messages
            .filter { it.role == "system" }
            .joinToString("\n\n") { it.content.orEmpty() }
            .ifBlank { null }

        val input = buildJsonArray {
            req.messages.filter { it.role != "system" }.forEach { m ->
                when (m.role) {
                    "tool" -> add(functionCallOutput(m))
                    "assistant" -> addAll(assistantItems(m))
                    else -> add(userItem(m))
                }
            }
        }

        val root = buildJsonObject {
            put("model", req.model)
            if (instructions != null) put("instructions", instructions)
            put("input", input)
            req.temperature?.let { put("temperature", it) }
            if (req.stream) put("stream", true)
            put("store", false)
            req.tools?.takeIf { it.isNotEmpty() }?.let { tools ->
                putJsonArray("tools") {
                    tools.forEach { t ->
                        addJsonObject {
                            put("type", "function")
                            put("name", t.function.name)
                            put("description", t.function.description)
                            put("parameters", t.function.parameters)
                        }
                    }
                }
            }
            val reasoning = provider.models[req.model]?.reasoning
            val effort = req.reasoningEffort
                ?.takeIf { it.isNotBlank() && it != "off" }
                ?.takeIf { reasoning?.style == ReasoningStyle.EFFORT && it in reasoning.effortLevels }
            if (effort != null) {
                putJsonObject("reasoning") {
                    put("effort", effort)
                    put("summary", "auto")
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    private fun userItem(m: ApiMessage): JsonObject = buildJsonObject {
        put("role", m.role.ifBlank { "user" })
        if (m.imageUrls.isNullOrEmpty() && m.content == null) {
            put("content", "")
            return@buildJsonObject
        }
        putJsonArray("content") {
            m.content?.let { addJsonObject { put("type", "input_text"); put("text", it) } }
            m.imageUrls.orEmpty().forEach { url ->
                addJsonObject { put("type", "input_image"); put("image_url", url) }
            }
        }
    }

    private fun assistantItems(m: ApiMessage): List<JsonObject> = buildList {
        m.content?.takeIf { it.isNotBlank() }?.let {
            add(buildJsonObject {
                put("role", "assistant")
                putJsonArray("content") {
                    addJsonObject { put("type", "output_text"); put("text", it) }
                }
            })
        }
        m.toolCalls?.forEach { tc ->
            add(buildJsonObject {
                put("type", "function_call")
                put("call_id", tc.id)
                put("name", tc.function.name)
                put("arguments", tc.function.arguments)
            })
        }
    }

    private fun functionCallOutput(m: ApiMessage): JsonObject = buildJsonObject {
        put("type", "function_call_output")
        put("call_id", m.toolCallId ?: "")
        val imgs = m.imageUrls
        if (!imgs.isNullOrEmpty()) {
            putJsonArray("output") {
                m.content?.let { addJsonObject { put("type", "output_text"); put("text", it) } }
                imgs.forEach { url -> addJsonObject { put("type", "input_image"); put("image_url", url) } }
            }
        } else {
            put("output", m.content.orEmpty())
        }
    }

    // ── Response parsing (non-streaming) ──────────────────────────────────────

    override fun parseResponse(body: String): ApiMessage {
        val root = json.parseToJsonElement(body).jsonObject
        return assembleFromOutput(root["output"] as? JsonArray)
    }

    override fun extractUsage(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body).jsonObject["usage"] as? JsonObject }.getOrNull()

    private fun assembleFromOutput(output: JsonArray?): ApiMessage {
        if (output == null) return ApiMessage(role = "assistant")
        val text = StringBuilder()
        val toolCalls = mutableListOf<ApiToolCall>()
        output.forEach { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@forEach
            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "message" -> (o["content"] as? JsonArray)?.forEach { part ->
                    val p = runCatching { part.jsonObject }.getOrNull() ?: return@forEach
                    if (p["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
                        text.append(p["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    }
                }
                "function_call" -> toolCalls += ApiToolCall(
                    id = o["call_id"]?.jsonPrimitive?.contentOrNull
                        ?: o["id"]?.jsonPrimitive?.contentOrNull
                        ?: "call_${toolCalls.size}",
                    function = ApiFunctionCall(
                        name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        arguments = o["arguments"]?.jsonPrimitive?.contentOrNull?.ifBlank { "{}" } ?: "{}",
                    ),
                )
            }
        }
        return ApiMessage(
            role = "assistant",
            content = text.toString().takeIf { it.isNotEmpty() },
            toolCalls = toolCalls.takeIf { it.isNotEmpty() },
        )
    }

    // ── SSE stream parsing ────────────────────────────────────────────────────

    /**
     * Responses streams typed `response.*` events. Text and reasoning deltas are
     * forwarded live; function calls accumulate by `output_index`. A
     * `response.completed` event carries the authoritative `response.output`
     * array — prefer it over the accumulated buffers when present.
     */
    override suspend fun parseStream(
        lines: Sequence<String>,
        onContent: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit,
        onToolCall: suspend (index: Int, id: String?, name: String?, argumentsDelta: String) -> Unit,
    ): ApiMessage {
        val text = StringBuilder()
        val calls = sortedMapOf<Int, CallAcc>()
        var assembled: ApiMessage? = null

        for (raw in lines) {
            val line = raw.trim()
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val ev = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
            when (ev["type"]?.jsonPrimitive?.contentOrNull) {
                "response.output_text.delta" -> {
                    val piece = ev["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (piece.isNotEmpty()) { text.append(piece); onContent(piece) }
                }
                "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                    val piece = ev["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (piece.isNotEmpty()) onReasoning(piece)
                }
                "response.output_item.added" -> {
                    val item = ev["item"]?.jsonObject ?: continue
                    if (item["type"]?.jsonPrimitive?.contentOrNull != "function_call") continue
                    val idx = ev["output_index"]?.jsonPrimitive?.intOrNull ?: calls.size
                    val acc = calls.getOrPut(idx) { CallAcc() }
                    acc.callId = item["call_id"]?.jsonPrimitive?.contentOrNull
                        ?: item["id"]?.jsonPrimitive?.contentOrNull
                    acc.name = item["name"]?.jsonPrimitive?.contentOrNull
                    onToolCall(idx, acc.callId, acc.name, "")
                }
                "response.function_call_arguments.delta" -> {
                    val piece = ev["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (piece.isEmpty()) continue
                    val itemId = ev["item_id"]?.jsonPrimitive?.contentOrNull
                    val idx = ev["output_index"]?.jsonPrimitive?.intOrNull
                        ?: calls.entries.firstOrNull { it.value.itemId == itemId }?.key
                        ?: calls.size
                    val acc = calls.getOrPut(idx) { CallAcc() }
                    acc.itemId = itemId
                    acc.arguments.append(piece)
                    onToolCall(idx, acc.callId, acc.name, piece)
                }
                "response.completed" -> {
                    val output = ev["response"]?.jsonObject?.get("output") as? JsonArray
                    assembled = output?.let { assembleFromOutput(it) }
                    break
                }
                "response.failed", "response.incomplete" -> {
                    val msg = ev["response"]?.jsonObject?.get("error")?.jsonObject
                        ?.get("message")?.jsonPrimitive?.contentOrNull
                        ?: "Responses stream failed"
                    error(msg)
                }
                "error" -> {
                    val msg = ev["message"]?.jsonPrimitive?.contentOrNull ?: "Responses stream error"
                    error(msg)
                }
            }
        }

        return assembled ?: assembleFromBuffers(text, calls)
    }

    private fun assembleFromBuffers(text: StringBuilder, calls: Map<Int, CallAcc>): ApiMessage {
        val toolCalls = calls.values
            .filter { it.name != null }
            .mapIndexed { i, c ->
                ApiToolCall(
                    id = c.callId ?: "call_$i",
                    function = ApiFunctionCall(c.name!!, c.arguments.toString().ifBlank { "{}" }),
                )
            }
            .takeIf { it.isNotEmpty() }
        return ApiMessage(
            role = "assistant",
            content = text.toString().takeIf { it.isNotEmpty() },
            toolCalls = toolCalls,
        )
    }

    private class CallAcc {
        var itemId: String? = null
        var callId: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }
}
