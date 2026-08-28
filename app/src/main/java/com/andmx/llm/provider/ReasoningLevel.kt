package com.andmx.llm.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single reasoning level bound to the concrete wire parameters that activate
 * it — the data-driven equivalent of ZCode's model-catalog `reasoning.levels`
 * entries. Declaring levels as data (instead of hard-coded adapter branches)
 * lets a model catalog specify exactly how "max" or "enabled" translate onto
 * anthropic / openai-compatible requests without code changes.
 */
@Serializable
data class ReasoningLevelRule(
    /** Wire protocol this rule applies to. */
    val kind: ProviderKind,
    /** Dotted path into the request body, e.g. "reasoning_effort" or "output_config.effort". */
    val path: String,
    /** Literal JSON value to set at [path] (string / number / boolean / object). */
    val value: String,
    /**
     * For anthropic thinking budgets: when non-null, [value] names a numeric
     * field of the request context — only "budget_tokens" is understood — and
     * the adapter writes thinking{type:enabled, budget_tokens:resolved}.
     */
    val budgetTokens: Int? = null,
)

/**
 * A named reasoning level (low / high / max / enabled / off) and the per-kind
 * rules that activate it.
 */
@Serializable
data class ReasoningLevel(
    val id: String,
    val rules: List<ReasoningLevelRule> = emptyList(),
)

/**
 * Reasoning presets shared by model catalogs. Wire values follow the 2026
 * model catalogs: kimi/deepseek/glm-5.3 use output_config.effort on anthropic
 * and reasoning_effort on openai-compatible; qwen/k2.6 use a boolean or a
 * small thinking budget.
 */
object ReasoningLevels {
    const val LOW = "low"
    const val HIGH = "high"
    const val MAX = "max"
    const val ENABLED = "enabled"
    const val OFF = "off"

    fun effort(name: String): ReasoningLevel = ReasoningLevel(
        id = name,
        rules = listOf(
            ReasoningLevelRule(
                kind = ProviderKind.ANTHROPIC,
                path = "output_config.effort",
                value = name,
            ),
            ReasoningLevelRule(
                kind = ProviderKind.OPENAI,
                path = "reasoning_effort",
                value = name,
            ),
        ),
    )

    fun thinkingBudget(tokens: Int): ReasoningLevel = ReasoningLevel(
        id = ENABLED,
        rules = listOf(
            ReasoningLevelRule(
                kind = ProviderKind.ANTHROPIC,
                path = "thinking",
                value = "enabled",
                budgetTokens = tokens,
            ),
        ),
    )

    fun off(): ReasoningLevel = ReasoningLevel(id = OFF, rules = emptyList())

    val EFFORT_MAX: ReasoningLevel = effort(MAX)
    val EFFORT_HIGH: ReasoningLevel = effort(HIGH)
    val EFFORT_LOW: ReasoningLevel = effort(LOW)
    val THINKING_MIN_BUDGET: ReasoningLevel = thinkingBudget(1024)
    val OFF_LEVEL: ReasoningLevel = off()
}
