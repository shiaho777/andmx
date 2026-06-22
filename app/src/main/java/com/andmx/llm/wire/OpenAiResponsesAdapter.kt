package com.andmx.llm.wire

/**
 * OpenAI Responses wire adapter: `POST {base}/responses`.
 *
 * The Responses API uses the same JSON request/response envelope and SSE
 * `data:` chunk shape as Chat Completions for the fields we consume, so we
 * delegate encoding/parsing to [OpenAiChatAdapter] and only override the
 * endpoint path. This keeps the Responses code path honest without duplicating
 * the assembly logic.
 */
object OpenAiResponsesAdapter : WireAdapter by OpenAiChatAdapter {
    override fun endpointUrl(base: String): String = base.trimEnd('/') + "/responses"
}
