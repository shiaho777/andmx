package com.andmx.llm.provider

import android.content.Context
import com.andmx.llm.provider.ProviderKind
import com.andmx.llm.provider.ReasoningConfig
import com.andmx.llm.provider.ReasoningLevel
import com.andmx.llm.provider.ReasoningLevels
import com.andmx.llm.provider.ReasoningStyle
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ZcodeModelCatalog {
    data class Match(
        val contextWindow: Int,
        val maxOutputTokens: Int,
        val inputModalities: List<String>,
        val supportsToolCall: Boolean,
        val supportsJsonSchemaOutput: Boolean,
        val supportsNativeWebSearch: Boolean,
        val supportsMidConversationSystem: Boolean,
        val reasoning: ReasoningConfig?,
        val specific: Boolean,
    ) {
        fun toDefinition(displayName: String? = null): ModelDefinition = ModelDefinition(
            displayName = displayName,
            contextWindow = contextWindow,
            maxOutputTokens = maxOutputTokens,
            inputModalities = inputModalities,
            outputModalities = listOf("text"),
            reasoning = reasoning,
            supportsToolCall = supportsToolCall,
            supportsJsonSchemaOutput = supportsJsonSchemaOutput,
            supportsNativeWebSearch = supportsNativeWebSearch,
            supportsMidConversationSystem = supportsMidConversationSystem,
        )
    }

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var cached: JsonObject? = null

    fun load(context: Context): JsonObject {
        cached?.let { return it }
        val text = context.assets.open("zcode-model-config-rules.json").bufferedReader().use { it.readText() }
        val parsed = json.parseToJsonElement(text).jsonObject
        cached = parsed
        return parsed
    }

    fun resolve(
        rules: JsonObject,
        modelId: String,
        apiType: String?,
        baseUrl: String?,
    ): Match {
        val acc = Accumulator()
        var specific = false
        fun applyAll(key: String, extra: (JsonObject) -> Boolean) {
            val arr = rules[key] as? JsonArray ?: return
            for (el in arr) {
                val rule = el as? JsonObject ?: continue
                val pattern = rule.str("modelMatch") ?: continue
                if (!matches(pattern, modelId, ignoreCase = true)) continue
                if (!extra(rule)) continue
                if (pattern != ".*") specific = true
                overlay(acc, rule["config"] as? JsonObject)
            }
        }
        applyAll("modelRules") { true }
        applyAll("modelApiRules") { rule ->
            val api = rule.str("apiTypeMatch") ?: return@applyAll true
            apiType != null && matches(api, apiType, ignoreCase = false)
        }
        val normalized = baseUrl?.let { normalizeBaseUrl(it) }
        applyAll("providerSiteRules") { rule ->
            val api = rule.str("apiTypeMatch")
            if (api != null && (apiType == null || !matches(api, apiType, ignoreCase = false))) return@applyAll false
            val base = rule.str("baseUrlMatch") ?: return@applyAll true
            normalized != null && matches(base, normalized, ignoreCase = false)
        }
        return acc.toMatch(specific)
    }

    fun apiType(kind: ProviderKind): String = when (kind) {
        ProviderKind.OPENAI -> "openai-chat-completions"
        ProviderKind.OPENAI_RESPONSES -> "openai-responses"
        ProviderKind.ANTHROPIC -> "anthropic-messages"
    }

    private class Accumulator {
        var contextWindow: Int = 0
        var maxOutputTokens: Int = 0
        var text = true
        var image = false
        var video = false
        var audio = false
        var pdf = false
        var toolCall = true
        var jsonSchema = false
        var webSearch = false
        var midSystem = false
        var reasoningValues: List<String>? = null

        fun toMatch(specific: Boolean): Match {
            val modalities = buildList {
                if (text) add("text")
                if (image) add("image")
                if (video) add("video")
                if (audio) add("audio")
                if (pdf) add("pdf")
                if (isEmpty()) add("text")
            }
            return Match(
                contextWindow = contextWindow,
                maxOutputTokens = maxOutputTokens,
                inputModalities = modalities,
                supportsToolCall = toolCall,
                supportsJsonSchemaOutput = jsonSchema,
                supportsNativeWebSearch = webSearch,
                supportsMidConversationSystem = midSystem,
                reasoning = reasoningValues?.let { reasoningConfig(it) },
                specific = specific,
            )
        }
    }

    private fun overlay(acc: Accumulator, config: JsonObject?) {
        if (config == null) return
        val props = config["properties"] as? JsonObject
        props?.int("contextWindow")?.let { acc.contextWindow = it }
        props?.bool("supportsToolCall")?.let { acc.toolCall = it }
        props?.bool("supportsJsonSchemaOutput")?.let { acc.jsonSchema = it }
        props?.bool("supportsNativeWebSearch")?.let { acc.webSearch = it }
        props?.bool("supportsMidConversationSystem")?.let { acc.midSystem = it }
        val input = props?.get("inputFormat") as? JsonObject
        input?.bool("supportsText")?.let { acc.text = it }
        input?.bool("supportsImage")?.let { acc.image = it }
        input?.bool("supportsVideo")?.let { acc.video = it }
        input?.bool("supportsAudio")?.let { acc.audio = it }
        input?.bool("supportsPdf")?.let { acc.pdf = it }
        val specs = config["optionSpecs"] as? JsonObject
        (specs?.get("maxOutputTokens") as? JsonObject)?.int("max")?.let { acc.maxOutputTokens = it }
        val levels = (specs?.get("reasoningLevel") as? JsonObject)?.get("values") as? JsonArray
        if (levels != null) {
            acc.reasoningValues = levels.mapNotNull { it.jsonPrimitive.contentOrNull }
        }
    }

    private fun reasoningConfig(values: List<String>): ReasoningConfig? {
        if (values.isEmpty()) return null
        val active = values.firstOrNull { it != "disabled" } ?: values.first()
        val levels = values.map { value ->
            when (value) {
                "disabled" -> ReasoningLevel(id = "disabled")
                "enabled" -> ReasoningLevels.thinkingBudget(16_000)
                else -> ReasoningLevels.effort(value)
            }
        }
        return ReasoningConfig(
            style = ReasoningStyle.EFFORT,
            effortLevels = values,
            defaultEffort = active,
            levels = levels,
            defaultLevel = active,
        )
    }

    private fun matches(pattern: String, value: String, ignoreCase: Boolean): Boolean =
        runCatching {
            val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            Regex("^(?:$pattern)$", options).matches(value)
        }.getOrDefault(false)

    private fun normalizeBaseUrl(value: String): String? = runCatching {
        val parsed = URI(value)
        val scheme = parsed.scheme ?: return null
        val host = parsed.host ?: return null
        val port = if (parsed.port > 0) ":${parsed.port}" else ""
        val path = (parsed.path ?: "").trimEnd('/')
        val query = if (parsed.query.isNullOrBlank()) "" else "?${parsed.query}"
        "$scheme://$host$port$path$query"
    }.getOrNull()

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
}
