package com.andmx.llm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Token usage statistics for a single turn or accumulated across a session.
 * Mirrors Codex's TokenUsage struct.
 */
data class TokenUsage(
    val inputTokens: Int = 0,
    val cachedInputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningOutputTokens: Int = 0,
    val totalTokens: Int = 0,
) {
    operator fun plus(other: TokenUsage): TokenUsage = TokenUsage(
        inputTokens = inputTokens + other.inputTokens,
        cachedInputTokens = cachedInputTokens + other.cachedInputTokens,
        outputTokens = outputTokens + other.outputTokens,
        reasoningOutputTokens = reasoningOutputTokens + other.reasoningOutputTokens,
        totalTokens = totalTokens + other.totalTokens,
    )
}

/**
 * Rate limit information parsed from response headers.
 * Mirrors Codex's RateLimitStatusDetails.
 */
data class RateLimitStatus(
    val remaining: Int? = null,
    val limit: Int? = null,
    val resetAt: Long? = null,
    val limitReached: Boolean = false,
)

/**
 * Tracks token usage and rate limit status across turns.
 * Exposes a [StateFlow] for UI observation.
 */
class TokenUsageTracker {
    private val _sessionUsage = MutableStateFlow(TokenUsage())
    val sessionUsage: StateFlow<TokenUsage> = _sessionUsage.asStateFlow()

    private val _rateLimit = MutableStateFlow(RateLimitStatus())
    val rateLimit: StateFlow<RateLimitStatus> = _rateLimit.asStateFlow()

    private val _lastTurnUsage = MutableStateFlow(TokenUsage())
    val lastTurnUsage: StateFlow<TokenUsage> = _lastTurnUsage.asStateFlow()

    /** Record usage from a completed turn. */
    fun recordTurn(usage: TokenUsage) {
        _lastTurnUsage.value = usage
        _sessionUsage.value = _sessionUsage.value + usage
    }

    /** Update rate limit info from response headers. */
    fun updateRateLimit(status: RateLimitStatus) {
        _rateLimit.value = status
    }

    /** Reset session-level tracking (new conversation). */
    fun reset() {
        _sessionUsage.value = TokenUsage()
        _lastTurnUsage.value = TokenUsage()
        _rateLimit.value = RateLimitStatus()
    }

    /** Whether rate limit is critically low (< 10%). */
    fun isRateLimitCritical(): Boolean {
        val rl = _rateLimit.value
        val limit = rl.limit ?: return false
        val remaining = rl.remaining ?: return false
        return limit > 0 && remaining.toDouble() / limit < 0.1
    }

    /** Parse token usage from a JSON response object (usage field). */
    companion object {
        fun parseUsage(obj: kotlinx.serialization.json.JsonObject): TokenUsage {
            val input = obj["prompt_tokens"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
                ?: obj["input_tokens"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
                ?: 0
            val cached = obj["prompt_tokens_details"]?.let {
                ((it as? kotlinx.serialization.json.JsonObject)?.get("cached_tokens") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
            } ?: obj["cached_input_tokens"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
                ?: 0
            val output = obj["completion_tokens"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
                ?: obj["output_tokens"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
                ?: 0
            val reasoning = obj["completion_tokens_details"]?.let {
                ((it as? kotlinx.serialization.json.JsonObject)?.get("reasoning_tokens") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
            } ?: obj["reasoning_output_tokens"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
                ?: 0
            val total = obj["total_tokens"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
                ?: (input + output)
            return TokenUsage(input, cached, output, reasoning, total)
        }

        fun parseRateLimit(headers: java.net.HttpURLConnection): RateLimitStatus {
            fun header(name: String): Int? = headers.getHeaderField(name)?.toIntOrNull()
            val remaining = header("x-ratelimit-remaining-requests") ?: header("x-ratelimit-remaining-tokens")
            val limit = header("x-ratelimit-limit-requests") ?: header("x-ratelimit-limit-tokens")
            val resetStr = headers.getHeaderField("x-ratelimit-reset-requests")
                ?: headers.getHeaderField("x-ratelimit-reset-tokens")
            val resetAt = resetStr?.let { parseResetTime(it) }
            val reached = remaining != null && remaining == 0
            return RateLimitStatus(remaining, limit, resetAt, reached)
        }

        private fun parseResetTime(value: String): Long? {
            // OpenAI uses formats like "1s", "500ms", "1m30s"
            val numRegex = Regex("(\\d+)([smh])")
            var totalMs = 0L
            for (match in numRegex.findAll(value)) {
                val n = match.groupValues[1].toLong()
                totalMs += when (match.groupValues[2]) {
                    "s" -> n * 1000
                    "m" -> n * 60_000
                    "h" -> n * 3_600_000
                    else -> n
                }
            }
            return if (totalMs > 0) System.currentTimeMillis() + totalMs else null
        }
    }
}
