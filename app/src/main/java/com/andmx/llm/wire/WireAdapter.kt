package com.andmx.llm.wire

import com.andmx.llm.ApiMessage
import com.andmx.llm.ChatRequest
import com.andmx.llm.provider.ProviderDefinition
import kotlinx.serialization.json.JsonObject

/**
 * A wire-protocol adapter: knows how to talk to one family of LLM HTTP API.
 *
 * Each [com.andmx.llm.provider.ProviderKind] maps 1:1 to an adapter. The adapter
 * owns: endpoint path, auth header, request body schema, and SSE event parsing.
 * [com.andmx.llm.LlmClient] is a thin transport that delegates all protocol
 * specifics here, so adding a new backend (Gemini, Bedrock, …) is one new file.
 */
interface WireAdapter {
    /** Full request URL for a provider with the given base URL. */
    fun endpointUrl(base: String): String

    /** Name→value of the auth header to send, or null if the provider needs none. */
    fun authHeader(apiKey: String): Pair<String, String>?

    /** Any extra fixed headers this protocol requires (e.g. anthropic-version). */
    fun extraHeaders(): List<Pair<String, String>> = emptyList()

    /** Encode a [ChatRequest] into the JSON body string for this protocol. */
    fun encodeRequest(req: ChatRequest, provider: ProviderDefinition): String

    /** Whether this protocol parses usage from the SSE stream's final chunk. */
    val emitsUsageInStream: Boolean get() = true

    /**
     * Parse an SSE line sequence into a fully-assembled assistant [ApiMessage].
     * [onContent] is invoked for each incremental text delta so the caller can
     * stream it to the UI live.
     */
    suspend fun parseStream(lines: Sequence<String>, onContent: suspend (String) -> Unit): ApiMessage

    /** Parse a non-streaming response body into an assistant [ApiMessage]. */
    fun parseResponse(body: String): ApiMessage

    /** Extract a usage object from the body, if present, for token tracking. */
    fun extractUsage(body: String): JsonObject? = null
}
