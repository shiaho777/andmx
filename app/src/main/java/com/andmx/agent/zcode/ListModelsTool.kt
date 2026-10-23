package com.andmx.agent.zcode

import com.andmx.agent.Tool
import com.andmx.agent.ToolResult
import com.andmx.agent.ToolRisk
import com.andmx.llm.provider.ProviderDefinition
import kotlinx.serialization.json.JsonObject

class ListModelsTool(
    private val providers: suspend () -> List<ProviderDefinition>,
    private val current: suspend () -> Pair<String, String>,
) : Tool {
    override val name = "ListModels"
    override val description = LIST_MODELS_DESCRIPTION
    override val risk = ToolRisk.READ
    override val readOnly = true
    override val destructive = false
    override val concurrentSafe = true
    override val needsApproval = false
    override val timeoutMs = LIST_MODELS_TIMEOUT_MS
    override val maxOutputBytes = LIST_MODELS_MODEL_BYTES
    override val sideEffectScope = "none"
    override val riskLevel = "low"
    override val permissionName = "listModels"
    override val parameters: JsonObject = LIST_MODELS_PARAMETERS

    override suspend fun execute(args: JsonObject): ToolResult {
        val catalog = runCatching { providers() }.getOrElse {
            return ToolResult(LIST_MODELS_UNAVAILABLE, isError = true)
        }
        val (curProvider, curModel) = runCatching { current() }.getOrDefault("" to "")
        val rows = catalog.flatMap { p ->
            p.models.keys.filter { it.isNotBlank() }.map { modelId -> p to modelId }
        }
        if (rows.isEmpty()) {
            return ToolResult(
                "<models count=\"0\">\n" +
                    "No models are configured on this host. Omit `subagent_model`: the workflow's subagents run on the session model.\n" +
                    "</models>",
            )
        }
        val lines = rows.map { (p, modelId) ->
            val def = p.models[modelId]
            val id = "${p.id}/$modelId"
            buildString {
                append(id)
                val label = p.name.trim().ifBlank { null }
                if (label != null) append(" — $label")
                val reasoning = def?.reasoning
                val levels = reasoning?.levels?.map { it.id }?.takeIf { it.isNotEmpty() }
                    ?: reasoning?.effortLevels.orEmpty()
                if (levels.isNotEmpty()) {
                    val fallback = reasoning?.defaultLevel?.ifBlank { null }
                        ?: reasoning?.defaultEffort?.takeIf { reasoning.effortLevels.isNotEmpty() }
                    append("; levels: ${levels.joinToString(",")}")
                    if (!fallback.isNullOrBlank()) append(" (default $fallback)")
                }
                if (p.id == curProvider && modelId == curModel) append(" [current]")
                val disabledReason = when {
                    !p.enabled -> "provider disabled"
                    p.apiKeyRequired && p.apiKey.isBlank() -> "missing api key"
                    else -> null
                }
                if (disabledReason != null) append(" [disabled: $disabledReason]")
            }
        }
        return ToolResult(
            "<models count=\"${rows.size}\">\n${lines.joinToString("\n")}\n</models>",
        )
    }
}
