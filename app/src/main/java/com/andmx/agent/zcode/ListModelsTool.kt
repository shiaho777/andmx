package com.andmx.agent.zcode

import com.andmx.agent.Tool
import com.andmx.agent.ToolResult
import com.andmx.agent.ToolRisk
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.agent.multi.SubagentModelCatalog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * ZCode `ListModels` 对齐：列出本机已配置的模型目录，供主代理给子代理挑模型。
 * 不是选模开关——改不了会话自身模型。id 用 AndMX 规范形 `provider::model`
 * （上游是 `providerId/modelId`），可直接填进 Agent/spawn_agent 的 `model` 字段。
 */
class ListModelsTool(
    private val providers: suspend () -> List<ProviderDefinition>,
    private val current: suspend () -> Pair<String, String>,
) : Tool {
    override val name = "ListModels"
    override val description =
        "Lists the models this host has configured, so spawned sub-agents can be pointed at one.\n\n" +
            "- Each row's `id` (`provider::model`) pastes verbatim into the `model` field of Agent / spawn_agent. Append `$<level>` to pick a reasoning level from that row's `levels`.\n" +
            "- This tool does NOT change the model you are running on. The session model is the user's choice and only the user changes it; `model` only moves the spawned sub-agents.\n" +
            "- The model the session is on right now is marked `[current]` — setting sub-agents to that one is the same as omitting the field.\n" +
            "- A row marked `disabled` cannot be used (no API key, disabled by policy). Resolve that with the user rather than picking around it silently."
    override val risk = ToolRisk.READ
    override val timeoutMs = 10_000L

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val (curProvider, curModel) = current()
        val rows = providers().flatMap { p ->
            p.models.keys.filter { it.isNotBlank() }.map { modelId -> p to modelId }
        }
        if (rows.isEmpty()) {
            return ToolResult(
                "<models count=\"0\">\n" +
                    "No models are configured on this host. Omit `model`: sub-agents run on the session model.\n" +
                    "</models>",
            )
        }
        val lines = rows.map { (p, modelId) ->
            val def = p.models[modelId]
            val id = SubagentModelCatalog.encode(p.id, modelId)
            buildString {
                append(id)
                val label = p.name.trim().ifBlank { p.id }
                if (label.isNotBlank()) append(" — $label")
                val reasoning = def?.reasoning
                val levels = reasoning?.levels?.map { it.id }?.takeIf { it.isNotEmpty() }
                    ?: reasoning?.effortLevels.orEmpty()
                if (levels.isNotEmpty()) {
                    val fallback = reasoning?.defaultLevel?.ifBlank { null }
                        ?: reasoning?.defaultEffort
                    append("; levels: ${levels.joinToString(",")}")
                    if (!fallback.isNullOrBlank()) append(" (default $fallback)")
                }
                if (def != null && def.contextWindow > 0) append("; ctx: ${def.contextWindow}")
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
