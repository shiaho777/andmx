package com.andmx.llm.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The wire protocol family a provider speaks. Determines endpoint path, request
 * body schema, auth header shape, and SSE event parsing.
 *
 * Adding a new protocol = add an enum value + a [com.andmx.llm.wire.WireAdapter].
 */
enum class ProviderKind(val endpointPath: String) {
    /** OpenAI Chat Completions: `POST {base}/chat/completions`. Spoken by OpenAI, DeepSeek, Ollama, vLLM, … */
    OPENAI("/chat/completions"),

    /** OpenAI Responses API: `POST {base}/responses`. */
    OPENAI_RESPONSES("/responses"),

    /** Anthropic Messages: `POST {base}/v1/messages`. Spoken by Anthropic, Z.ai, BigModel (GLM), … */
    ANTHROPIC("/v1/messages");

    companion object {
        /** Parse from a config string ("chat_completions" | "responses" | "anthropic"), tolerant. */
        fun from(wire: String?): ProviderKind = when (wire?.lowercase()?.trim()) {
            null, "", "chat_completions", "chat-completions", "chat", "openai" -> OPENAI
            "responses", "openai_responses" -> OPENAI_RESPONSES
            "anthropic", "messages", "anthropic_messages" -> ANTHROPIC
            else -> OPENAI
        }
    }
}

/**
 * How a model exposes "extended thinking" / reasoning control, if at all.
 *
 * The three values correspond to the three real-world wire conventions:
 * - DeepSeek-reasoner, gpt-4o, … → not adjustable at all
 * - OpenAI o-series / gpt-5      → semantic effort levels (minimal/low/medium/high)
 * - Anthropic Claude 4 / GLM     → absolute token budget (thinking.budget_tokens)
 */
enum class ReasoningStyle {
    /** Reasoning is not user-adjustable (either always-on like deepseek-reasoner, or unsupported). */
    NONE,
    /** OpenAI convention: `reasoning_effort` = minimal | low | medium | high. */
    EFFORT,
    /** Anthropic convention: `thinking.budget_tokens` = absolute token count (min 1024). */
    THINKING,
}

/**
 * Per-model reasoning capability declaration. Drives what the UI shows and how
 * adapters translate the user's choice onto the wire — so reasoning behavior is
 * declared by the model, not guessed from a global setting.
 *
 * [levels] carries the ZCode-style data-driven mapping (level id → per-kind
 * wire rules). When present it takes precedence over the [style] branches in
 * adapters; the legacy style/effortLevels fields stay for backwards-compatible
 * catalogs.
 */
@Serializable
data class ReasoningConfig(
    val style: ReasoningStyle = ReasoningStyle.NONE,
    /** EFFORT style: the effort levels this model actually accepts (e.g. ["minimal","low","medium","high"]). */
    val effortLevels: List<String> = emptyList(),
    /** EFFORT style: the default level applied when the user enables reasoning. */
    val defaultEffort: String = "medium",
    /** THINKING style: default budget_tokens (must be ≥ 1024 per Anthropic spec). */
    val defaultBudgetTokens: Int = 16_000,
    /** Data-driven level table (ZCode model-catalog style); wins over [style] when non-empty. */
    val levels: List<ReasoningLevel> = emptyList(),
    /** Which level id applies when reasoning is on but the user gave no level. */
    val defaultLevel: String = "",
) {
    fun level(id: String): ReasoningLevel? = levels.firstOrNull { it.id == id }

    /** Resolve the effective level id for a user-selected value (null = send nothing). */
    fun resolveLevelId(userValue: String?): String? {
        if (userValue != null && userValue.isNotBlank() && userValue != "off") {
            return if (levels.any { it.id == userValue }) userValue else defaultLevel.ifBlank { null }
        }
        if (userValue == "off") return OFF_SENTINEL
        if (levels.isNotEmpty()) return defaultLevel.ifBlank { null }
        return null
    }

    companion object {
        /** Sentinel meaning "user explicitly turned reasoning off". */
        const val OFF_SENTINEL = "__off__"

        /** Standard OpenAI effort ladder (gpt-5, o3, o4-mini). */
        val OPENAI_EFFORT = ReasoningConfig(
            style = ReasoningStyle.EFFORT,
            effortLevels = listOf("minimal", "low", "medium", "high"),
            defaultEffort = "medium",
        )
        /** Anthropic / GLM extended thinking. */
        val ANTHROPIC_THINKING = ReasoningConfig(
            style = ReasoningStyle.THINKING,
            defaultBudgetTokens = 16_000,
        )
    }
}

/**
 * A single model offered by a provider, with the metadata the agent needs to make
 * decisions (compaction threshold, reasoning capability, modalities).
 */
@Serializable
data class ModelDefinition(
    /** Display name; falls back to the map key (model id) when null. */
    val displayName: String? = null,
    /** Context window in tokens; 0 = unknown (caller falls back to a default). */
    val contextWindow: Int = 0,
    /** Max output tokens; 0 = unknown. */
    val maxOutputTokens: Int = 0,
    /** Input modalities, e.g. ["text"] or ["text","image"]. */
    val inputModalities: List<String> = listOf("text"),
    /** Output modalities. */
    val outputModalities: List<String> = listOf("text"),
    /** How this model exposes reasoning control, or null if not adjustable. */
    val reasoning: ReasoningConfig? = null,
) {
    val supportsVision: Boolean get() = "image" in inputModalities
    /** Convenience: does this model accept any reasoning parameter at all. */
    val supportsReasoning: Boolean get() = reasoning?.style != null && reasoning.style != ReasoningStyle.NONE
}

/**
 * ZCode's `providerMappings.claude`: which of this provider's models should be
 * used for each Claude model slot.
 *
 * Reverse-engineered from the engine schema in `zcode.cjs`
 * (`Xxn = f.object({haiku, sonnet, opus, reasoning})` — every slot a required
 * string, while the enclosing `claude` object is optional). The renderer in
 * this build carries only the matching zod schema, no UI yet, so the editing
 * surface here is designed from the i18n labels:
 * `slot.haiku` = Haiku（轻量任务）, `slot.sonnet` = Sonnet（常规任务）,
 * `slot.opus` = Opus（复杂任务）, `slot.reasoning` = Reasoning（推理任务）.
 *
 * A blank slot means "not set" (`mappingNotSet` = 未设置).
 */
@Serializable
data class ClaudeModelMapping(
    val haiku: String = "",
    val sonnet: String = "",
    val opus: String = "",
    val reasoning: String = "",
) {
    /** ZCode's canonical slot order. */
    companion object {
        val SLOT_ORDER = listOf("haiku", "sonnet", "opus", "reasoning")
    }
}

/**
 * A model provider configuration — the unified representation that replaces the
 * former split across `ModelProvider`, `ModelProviderConfig`, and the provider
 * fields of `ProviderSettings`.
 *
 * Each provider carries its own [models] map so the same model id can have
 * different capabilities/limits under different providers.
 */
@Serializable
data class ProviderDefinition(
    /** Stable id: "openai" / "anthropic" / a UUID for user-created providers. */
    val id: String,
    /** Human-facing label shown in the UI. */
    val name: String,
    /** Wire protocol + endpoint path. */
    val kind: ProviderKind = ProviderKind.OPENAI,
    /** Base URL, no trailing slash (e.g. "https://api.openai.com/v1"). */
    val baseUrl: String,
    /** API key, stored in plaintext for now (encryption can be layered later). */
    val apiKey: String = "",
    /** Whether a non-blank apiKey is required to consider this provider usable. */
    val apiKeyRequired: Boolean = true,
    /** Soft toggle; disabled providers are kept but not selectable as primary. */
    val enabled: Boolean = true,
    /** "builtin" (preset) | "custom" (user-created). */
    val source: String = SOURCE_CUSTOM,
    /** Max retries for non-streaming requests (Codex-style). */
    val requestMaxRetries: Int = 2,
    /** Max retries for streaming requests. */
    val streamMaxRetries: Int = 1,
    /** Idle timeout for streaming, ms. */
    val streamIdleTimeoutMs: Long = 120_000,
    /** Extra HTTP headers appended to each request (e.g. "HTTP-Referer" for OpenRouter). */
    val httpHeaders: Map<String, String> = emptyMap(),
    /** Per-provider model catalogue. */
    val models: Map<String, ModelDefinition> = emptyMap(),
    /** ZCode's `providerMappings.claude`; null = the user never configured it. */
    val claudeMapping: ClaudeModelMapping? = null,
) {
    /** A provider is usable when enabled and, if it requires a key, the key is present. */
    val isUsable: Boolean get() = enabled && (!apiKeyRequired || apiKey.isNotBlank())

    companion object {
        const val SOURCE_CUSTOM = "custom"
    }
}
