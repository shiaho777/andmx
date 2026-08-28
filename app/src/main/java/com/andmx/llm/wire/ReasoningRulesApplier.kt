package com.andmx.llm.wire

import com.andmx.llm.provider.ProviderKind
import com.andmx.llm.provider.ReasoningConfig
import com.andmx.llm.provider.ReasoningLevelRule
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Applies ZCode-style data-driven reasoning level rules onto a request body.
 * Each rule's dotted [ReasoningLevelRule.path] is created (nested objects as
 * needed) with its literal value. `thinking` rules carrying budgetTokens write
 * the Anthropic thinking block, clamping to [AnthropicMessagesAdapter.MIN_THINKING_BUDGET].
 */
object ReasoningRulesApplier {

    fun apply(
        body: JsonObject,
        config: ReasoningConfig?,
        kind: ProviderKind,
        userValue: String?,
        maxOutputTokens: Int,
    ): JsonObject {
        if (config == null) return body
        val levelId = config.resolveLevelId(userValue) ?: return body
        if (levelId == ReasoningConfig.OFF_SENTINEL) return body
        val level = config.level(levelId) ?: return body
        val rules = level.rules.filter { it.kind == kind }
        if (rules.isEmpty()) return body

        var out = body
        for (rule in rules) {
            out = when {
                rule.path == "thinking" -> applyThinking(out, rule, maxOutputTokens)
                rule.path.contains('.') -> applyNested(out, rule.path.split('.'), rule.value)
                else -> withMutable(out) { put(rule.path, literal(rule.value)) }
            }
        }
        return out
    }

    private fun applyThinking(body: JsonObject, rule: ReasoningLevelRule, maxOutputTokens: Int): JsonObject {
        val budget = (rule.budgetTokens ?: 1024)
            .coerceIn(AnthropicMessagesAdapter.MIN_THINKING_BUDGET, maxOutputTokens.takeIf { it > 0 } ?: Int.MAX_VALUE)
        return withMutable(body) {
            putJsonObject("thinking") {
                put("type", "enabled")
                put("budget_tokens", budget)
            }
        }
    }

    private fun applyNested(body: JsonObject, segments: List<String>, value: String): JsonObject {
        val head = segments.first()
        if (segments.size == 1) return withMutable(body) { put(head, literal(value)) }
        val child = body[head] as? JsonObject ?: JsonObject(emptyMap())
        val nested = applyNested(child, segments.drop(1), value)
        return withMutable(body) { put(head, nested) }
    }

    private fun withMutable(body: JsonObject, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            body.forEach { (k, v) -> put(k, v) }
            block()
        }

    private fun literal(value: String): kotlinx.serialization.json.JsonPrimitive =
        kotlinx.serialization.json.JsonPrimitive(value)
}
