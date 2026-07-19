package com.andmx.llm

import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.wire.AdapterFactory
import com.andmx.llm.wire.WireAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Provider-agnostic chat interface so the agent loop can be tested with fakes
 * and swapped between backends.
 *
 * The provider (base URL, key, protocol) is bound when the client is built, not
 * passed per call — so the interface deals only in request payloads.
 */
interface LlmApi {
    suspend fun chat(request: ChatRequest): Result<ApiMessage>

    fun chatStream(request: ChatRequest): Flow<LlmStreamEvent> = flow {
        chat(request)
            .onSuccess { msg ->
                msg.content?.takeIf { it.isNotEmpty() }?.let { emit(LlmStreamEvent.Content(it)) }
                emit(LlmStreamEvent.Completed(msg))
            }
            .onFailure { throw it }
    }
}

/**
 * HTTP chat client that delegates all protocol specifics to a [WireAdapter].
 *
 * The adapter is chosen from [ProviderDefinition.kind] at construction; this
 * class then owns only the transport: retries, headers, token tracking, and the
 * SSE read loop. Mirrors Codex's model_client (x-codex-installation-id,
 * exponential backoff, rate-limit awareness).
 *
 * @param def      the bound provider definition (URL/key/headers/retry policy)
 * @param tracker  optional token-usage recorder
 * @param adapter  the wire adapter; defaults to one selected from [def].kind
 */
class LlmClient(
    private val def: ProviderDefinition,
    private val tracker: TokenUsageTracker? = null,
    private val adapter: WireAdapter = AdapterFactory.forKind(def.kind),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false },
    private val installationId: String = UUID.randomUUID().toString(),
) : LlmApi {

    override suspend fun chat(request: ChatRequest): Result<ApiMessage> = withContext(Dispatchers.IO) {
        val maxRetries = def.requestMaxRetries + 1
        var lastError: Throwable? = null
        for (attempt in 0 until maxRetries) {
            try {
                return@withContext Result.success(doChat(request))
            } catch (t: Throwable) {
                lastError = t
                if (attempt < maxRetries - 1 && t !is RateLimitException) {
                    delay((1000L shl attempt).coerceAtMost(10_000))
                }
            }
        }
        Result.failure(lastError ?: RuntimeException("Unknown error"))
    }

    private fun doChat(request: ChatRequest): ApiMessage {
        val body = adapter.encodeRequest(request.copy(stream = false), def)
        val text = doPost(body, stream = false)
        tracker?.let {
            adapter.extractUsage(text)?.let { usage -> it.recordTurn(TokenUsageTracker.parseUsage(usage)) }
        }
        return adapter.parseResponse(text)
    }

    override fun chatStream(request: ChatRequest): Flow<LlmStreamEvent> = flow {
        val maxRetries = def.streamMaxRetries + 1
        var lastError: Throwable? = null
        for (attempt in 0 until maxRetries) {
            try {
                doChatStream(request).collect { emit(it) }
                return@flow
            } catch (t: Throwable) {
                lastError = t
                if (attempt < maxRetries - 1 && t !is RateLimitException) {
                    delay((1000L shl attempt).coerceAtMost(8_000))
                }
            }
        }
        throw lastError ?: RuntimeException("Stream failed")
    }.flowOn(Dispatchers.IO)

    private fun doChatStream(request: ChatRequest): Flow<LlmStreamEvent> = flow {
        val body = adapter.encodeRequest(request.copy(stream = true), def)
        val conn = openConnection(body, stream = true)
        val code = conn.responseCode
        tracker?.updateRateLimit(TokenUsageTracker.parseRateLimit(conn))

        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            conn.disconnect()
            if (code == 429) throw RateLimitException(err.take(500))
            error("HTTP $code: ${err.take(500)}")
        }

        conn.inputStream.bufferedReader().use { reader ->
            val message = adapter.parseStream(
                lines = reader.lineSequence(),
                onContent = { delta -> emit(LlmStreamEvent.Content(delta)) },
                onReasoning = { delta -> emit(LlmStreamEvent.Reasoning(delta)) },
                onToolCall = { index, id, name, argDelta ->
                    emit(LlmStreamEvent.ToolCallDelta(index, id, name, argDelta))
                },
            )
            conn.disconnect()
            emit(LlmStreamEvent.Completed(message))
        }
    }

    /** POST [body] and return the response text (non-streaming). */
    private fun doPost(body: String, stream: Boolean): String {
        val conn = openConnection(body, stream)
        val code = conn.responseCode
        tracker?.updateRateLimit(TokenUsageTracker.parseRateLimit(conn))
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        conn.disconnect()
        if (code !in 200..299) {
            if (code == 429) throw RateLimitException(text.take(500))
            error("HTTP $code: ${text.take(500)}")
        }
        return text
    }

    /** Build and open the HTTP connection with all protocol/provider headers. */
    private fun openConnection(body: String, stream: Boolean): HttpURLConnection {
        val url = URL(adapter.endpointUrl(def.baseUrl))
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = def.streamIdleTimeoutMs.coerceAtLeast(30_000).toInt()
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (stream) setRequestProperty("Accept", "text/event-stream")
            // Auth (protocol-specific header name)
            adapter.authHeader(def.apiKey)?.let { (k, v) -> setRequestProperty(k, v) }
            // Protocol-fixed headers (e.g. anthropic-version)
            adapter.extraHeaders().forEach { (k, v) -> setRequestProperty(k, v) }
            // Codex-style tracing headers
            setRequestProperty("x-client-request-id", UUID.randomUUID().toString())
            setRequestProperty("x-codex-installation-id", installationId)
            // Provider-specific custom headers
            def.httpHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            outputStream.use { it.write(body.toByteArray()) }
        }
    }
}

/** Thrown when the API returns 429 Too Many Requests. */
class RateLimitException(message: String) : RuntimeException(message)
