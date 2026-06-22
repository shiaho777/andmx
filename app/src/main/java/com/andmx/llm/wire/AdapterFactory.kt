package com.andmx.llm.wire

import com.andmx.llm.provider.ProviderKind

/**
 * Selects the right [WireAdapter] for a [ProviderKind]. Single dispatch point
 * used by [com.andmx.llm.LlmClient] when it is constructed from a provider.
 */
object AdapterFactory {
    fun forKind(kind: ProviderKind): WireAdapter = when (kind) {
        ProviderKind.OPENAI -> OpenAiChatAdapter
        ProviderKind.OPENAI_RESPONSES -> OpenAiResponsesAdapter
        ProviderKind.ANTHROPIC -> AnthropicMessagesAdapter
    }
}
