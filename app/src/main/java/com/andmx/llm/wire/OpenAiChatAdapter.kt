package com.andmx.llm.wire

import com.andmx.llm.ApiMessage
import com.andmx.llm.ApiToolCall
import com.andmx.llm.ApiFunctionCall
import com.andmx.llm.ChatRequest
import com.andmx.llm.ChatResponse
import com.andmx.llm.provider.ProviderDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI Chat Completions wire adapter: `POST {base}/chat/completions`.
 *
 * Also spoken by DeepSeek, Ollama, LM Studio, vLLM, Groq, OpenRouter, and most
 * OpenAI-compatible endpoints — they share the exact same schema.
 *
 * The SSE assembly and request encoding here were lifted verbatim from the
 * former `LlmClient` so behavior is unchanged for the OpenAI path.
 */
object OpenAiChatAdapter : WireAdapter {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    override fun endpointUrl(base: String): String = base.trimEnd('/') + "/chat/completions"

    override fun authHeader(apiKey: String): Pair<String, String>? =
        if (apiKey.isNotBlank()) "Authorization" to "Bearer $apiKey" else null

    override fun encodeRequest(req: ChatRequest, provider: ProviderDefinition): String {
        // Only forward reasoning_effort when the target model actually speaks the
        // OpenAI EFFORT convention AND the requested value is one it accepts.
        // Otherwise strip it so we don't 400 on models like gpt-4o / deepseek-chat.
        val reasoning = provider.models[req.model]?.reasoning
        val safeEffort = when {
            reasoning == null || reasoning.style != com.andmx.llm.provider.ReasoningStyle.EFFORT -> null
            req.reasoningEffort == null || req.reasoningEffort == "off" -> null
            req.reasoningEffort in reasoning.effortLevels -> req.reasoningEffort
            else -> null
        }
        val toEncode = if (safeEffort == null && req.reasoningEffort != null) req.copy(reasoningEffort = null) else req
        return json.encodeToString(ChatRequest.serializer(), toEncode)
    }

    override fun parseResponse(body: String): ApiMessage {
        val resp = json.decodeFromString(ChatResponse.serializer(), body)
        return resp.choices.firstOrNull()?.message
            ?: error("空响应")
    }

    override fun extractUsage(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body).jsonObject["usage"] as? JsonObject }.getOrNull()

    override suspend fun parseStream(lines: Sequence<String>, onContent: suspend (String) -> Unit): ApiMessage {
        val contentBuf = StringBuilder()
        // tool_calls arrive incrementally, indexed; accumulate by index in order.
        val toolAcc = sortedMapOf<Int, Acc>()
        for (raw in lines) {
            val line = raw.trim()
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            val chunk = runCatching { json.decodeFromString(com.andmx.llm.ChatStreamChunk.serializer(), data) }.getOrNull() ?: continue
            val delta = chunk.choices.firstOrNull()?.delta ?: continue
            delta.content?.let { if (it.isNotEmpty()) { contentBuf.append(it); onContent(it) } }
            delta.toolCalls?.forEach { tc ->
                val acc = toolAcc.getOrPut(tc.index) { Acc() }
                tc.id?.let { acc.id = it }
                tc.function?.name?.let { acc.name = it }
                tc.function?.arguments?.let { acc.arguments.append(it) }
            }
        }
        val toolCalls = toolAcc.values
            .filter { it.name != null }
            .mapIndexed { i, t -> ApiToolCall(id = t.id ?: "call_$i", function = ApiFunctionCall(t.name!!, t.arguments.toString())) }
            .takeIf { it.isNotEmpty() }
        return ApiMessage(
            role = "assistant",
            content = contentBuf.toString().takeIf { it.isNotEmpty() },
            toolCalls = toolCalls,
        )
    }

    private class Acc {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    /**
     * `GET {base}/models` — OpenAI-style catalogue listing.
     *
     * Shared by every OpenAI-compatible backend (OpenAI, DeepSeek, Ollama, LM
     * Studio, Groq, OpenRouter, …). Response shape: `{ "data": [{ "id": "..." }] }`.
     * Returns an empty list on any failure (network/HTTP/parse) so the caller
     * can fall back to manual entry.
     */
    override suspend fun listModels(def: ProviderDefinition): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(def.baseUrl.trimEnd('/') + "/models")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                authHeader(def.apiKey)?.let { (k, v) -> setRequestProperty(k, v) }
                extraHeaders().forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) return@runCatching emptyList()
                val data = json.parseToJsonElement(text).jsonObject["data"]?.jsonArray ?: return@runCatching emptyList()
                data.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(emptyList())
    }
}
