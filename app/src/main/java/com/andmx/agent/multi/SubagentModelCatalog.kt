package com.andmx.agent.multi

import com.andmx.llm.provider.ProviderDefinition

data class SubagentModelOption(
    val value: String,
    val label: String,
    val group: String,
    val providerId: String = "",
    val modelId: String = "",
)

object SubagentModelCatalog {
    const val INHERIT = "inherit"
    private const val SEP = "::"

    fun encode(providerId: String, modelId: String): String =
        "${providerId.trim()}$SEP${modelId.trim()}"

    fun isInherit(value: String?): Boolean {
        val v = value?.trim().orEmpty()
        return v.isEmpty() || v == INHERIT || v == "defaultMain" || v == "main" || v == "lite"
    }

    fun parse(value: String?): Pair<String, String>? {
        val v = value?.trim().orEmpty()
        if (isInherit(v)) return null
        val idx = v.indexOf(SEP)
        if (idx <= 0 || idx >= v.length - SEP.length) {
            return "" to v
        }
        return v.substring(0, idx) to v.substring(idx + SEP.length)
    }

    fun displayLabel(value: String?, options: List<SubagentModelOption> = emptyList()): String {
        if (isInherit(value)) return "继承默认"
        options.firstOrNull { it.value == value }?.let { return it.label }
        val parsed = parse(value) ?: return "继承默认"
        return parsed.second.ifBlank { value.orEmpty() }
    }

    fun buildOptions(
        providers: List<ProviderDefinition>,
        activeProviderId: String = "",
        activeModel: String = "",
    ): List<SubagentModelOption> {
        val out = mutableListOf(
            SubagentModelOption(
                value = INHERIT,
                label = "继承默认",
                group = "",
            )
        )
        val enabled = providers.filter { it.enabled }
            .sortedWith(
                compareByDescending<ProviderDefinition> { it.id == activeProviderId }
                    .thenBy { it.displayName().lowercase() }
            )
        for (p in enabled) {
            val group = p.displayName()
            val models = linkedSetOf<String>()
            p.models.keys.filter { it.isNotBlank() }.forEach { models += it }
            if (p.id == activeProviderId && activeModel.isNotBlank()) {
                models += activeModel
            }
            if (models.isEmpty()) continue
            for (m in models) {
                out += SubagentModelOption(
                    value = encode(p.id, m),
                    label = m,
                    group = group,
                    providerId = p.id,
                    modelId = m,
                )
            }
        }
        return out
    }

    private fun ProviderDefinition.displayName(): String =
        name.trim().ifBlank { id.ifBlank { baseUrl } }
}
