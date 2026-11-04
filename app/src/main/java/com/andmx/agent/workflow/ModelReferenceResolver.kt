package com.andmx.agent.workflow

import com.andmx.llm.provider.ProviderDefinition

data class ModelCatalogEntry(
    val providerId: String,
    val modelId: String,
    val providerLabel: String? = null,
    val reasoningLevels: List<String> = emptyList(),
    val defaultReasoningLevel: String? = null,
    val contextWindow: Int? = null,
    val current: Boolean = false,
    val disabledReason: String? = null,
) {
    val pickerValue: String get() = "$providerId/$modelId"
}

sealed interface ModelReferenceResolution {
    data class Ok(val canonical: String, val entry: ModelCatalogEntry) : ModelReferenceResolution
    data class Failed(val reason: String, val message: String) : ModelReferenceResolution
}

object ModelReferenceResolver {
    private const val CANDIDATE_LINES = 40
    private const val REASONING_SEPARATOR = "$"

    fun resolve(text: String, entries: List<ModelCatalogEntry>): ModelReferenceResolution {
        val (reference, level) = splitReasoningLevel(text.trim())
        val qualified = isQualifiedReference(reference)
        val matches = matchEntries(reference, qualified, entries)
        if (matches.isEmpty()) return notFound(text, entries)

        val enabled = matches.filter { it.disabledReason == null }
        if (enabled.isEmpty()) return disabled(text, matches)

        val sessionEntry = entries.firstOrNull { it.current }
        val guarded = !qualified && sessionEntry != null
        val home = if (guarded) {
            enabled.filter { sameToken(it.providerId, sessionEntry!!.providerId) }
        } else {
            enabled
        }
        if (guarded && home.isEmpty()) {
            return otherProvider(text, sessionEntry!!, enabled)
        }

        val entry = if (home.size == 1) home[0] else home.firstOrNull { it.current }
            ?: return ambiguous(text, home)

        val reasoning = when (val choice = resolveReasoningLevel(entry, level)) {
            is LevelChoice.Chosen -> choice.level
            LevelChoice.Unknown -> return reasoningLevelUnknown(text, entry, level.orEmpty())
        }
        val canonical = buildString {
            append(entry.providerId).append('/').append(entry.modelId)
            reasoning?.let { append(REASONING_SEPARATOR).append(it) }
        }
        return ModelReferenceResolution.Ok(canonical, entry)
    }

    fun catalogEntries(
        providers: List<ProviderDefinition>,
        currentProviderId: String,
        currentModelId: String,
    ): List<ModelCatalogEntry> = providers.flatMap { provider ->
        provider.models.keys.filter { it.isNotBlank() }.map { modelId ->
            val definition = provider.models[modelId]
            val reasoning = definition?.reasoning
            val levels = reasoning?.levels?.map { it.id }?.takeIf { it.isNotEmpty() }
                ?: reasoning?.effortLevels.orEmpty()
            val fallback = reasoning?.defaultLevel?.ifBlank { null }
                ?: reasoning?.defaultEffort?.takeIf { reasoning.effortLevels.isNotEmpty() }
            ModelCatalogEntry(
                providerId = provider.id,
                modelId = modelId,
                providerLabel = provider.name.trim().ifBlank { null },
                reasoningLevels = levels,
                defaultReasoningLevel = fallback?.takeIf { levels.isNotEmpty() },
                contextWindow = definition?.contextWindow?.takeIf { it > 0 },
                current = provider.id == currentProviderId && modelId == currentModelId,
                disabledReason = when {
                    !provider.enabled -> "provider disabled"
                    provider.apiKeyRequired && provider.apiKey.isBlank() -> "missing api key"
                    else -> null
                },
            )
        }
    }

    private fun splitReasoningLevel(text: String): Pair<String, String?> {
        val providerSeparatorIndex = text.indexOf('/')
        val searchFrom = providerSeparatorIndex + 1
        val index = text.indexOf(REASONING_SEPARATOR, searchFrom)
        if (index <= searchFrom || index >= text.length - 1) return text to null
        return text.substring(0, index) to text.substring(index + 1)
    }

    private fun isQualifiedReference(reference: String): Boolean = reference.indexOf('/') > 0

    private fun matchEntries(
        reference: String,
        qualified: Boolean,
        entries: List<ModelCatalogEntry>,
    ): List<ModelCatalogEntry> {
        if (qualified) {
            val separator = reference.indexOf('/')
            val providerId = reference.substring(0, separator)
            val modelId = reference.substring(separator + 1)
            return entries.filter {
                sameToken(it.providerId, providerId) && sameToken(it.modelId, modelId)
            }
        }
        return entries.filter { sameToken(it.modelId, reference) }
    }

    private sealed interface LevelChoice {
        data class Chosen(val level: String?) : LevelChoice
        data object Unknown : LevelChoice
    }

    private fun resolveReasoningLevel(
        entry: ModelCatalogEntry,
        level: String?,
    ): LevelChoice {
        if (level != null) {
            val matched = entry.reasoningLevels.firstOrNull { sameToken(it, level) }
                ?: return LevelChoice.Unknown
            return LevelChoice.Chosen(matched)
        }
        if (entry.reasoningLevels.isEmpty()) return LevelChoice.Chosen(null)
        return LevelChoice.Chosen(entry.defaultReasoningLevel)
    }

    private fun notFound(text: String, entries: List<ModelCatalogEntry>): ModelReferenceResolution.Failed {
        val candidates = entries.filter { it.disabledReason == null }
        val shown = candidates.take(CANDIDATE_LINES)
        val lines = shown.map { "${it.pickerValue}${if (it.current) " [current]" else ""}" }.toMutableList()
        val overflow = candidates.size - shown.size
        if (overflow > 0) lines += "… and $overflow more."
        val body = if (candidates.isEmpty()) {
            "No models are configured on this host."
        } else {
            (listOf("Available models:") + lines).joinToString("\n")
        }
        return ModelReferenceResolution.Failed(
            "not_found",
            "No configured model matches `$text`. $body\n\nPass one of these ids, or call ListModels.",
        )
    }

    private fun otherProvider(
        text: String,
        sessionEntry: ModelCatalogEntry,
        candidates: List<ModelCatalogEntry>,
    ): ModelReferenceResolution.Failed {
        val lines = candidates.joinToString("\n") { it.pickerValue }
        val sessionProvider = sessionEntry.providerLabel ?: sessionEntry.providerId
        return ModelReferenceResolution.Failed(
            "other_provider",
            "`$text` is not configured under the session's provider ($sessionProvider), only under:\n" +
                "$lines\n\nA bare model name never moves the subagents to another provider. " +
                "Do not pick one on your own: if you think it would help, ask the user first; " +
                "once they asked for that model, pass its full `providerId/modelId`.",
        )
    }

    private fun ambiguous(
        text: String,
        candidates: List<ModelCatalogEntry>,
    ): ModelReferenceResolution.Failed {
        val lines = candidates.joinToString("\n") { it.pickerValue }
        return ModelReferenceResolution.Failed(
            "ambiguous",
            "`$text` is configured under more than one provider:\n$lines\n\n" +
                "Pass the full `providerId/modelId` of the one you want.",
        )
    }

    private fun disabled(
        text: String,
        candidates: List<ModelCatalogEntry>,
    ): ModelReferenceResolution.Failed {
        val lines = candidates.joinToString("\n") { "${it.pickerValue} — ${it.disabledReason}" }
        return ModelReferenceResolution.Failed(
            "disabled",
            "`$text` matches a model that cannot be used on this host:\n$lines\n\n" +
                "Resolve that with the user, or call ListModels and pick another id.",
        )
    }

    private fun reasoningLevelUnknown(
        text: String,
        entry: ModelCatalogEntry,
        level: String,
    ): ModelReferenceResolution.Failed {
        val levels = if (entry.reasoningLevels.isEmpty()) {
            "${entry.pickerValue} has no reasoning levels — drop the `$` suffix."
        } else {
            val suffix = entry.defaultReasoningLevel?.let { " Omit the suffix to use $it." }.orEmpty()
            "Its levels are: ${entry.reasoningLevels.joinToString(", ")}.$suffix"
        }
        return ModelReferenceResolution.Failed(
            "reasoning_level_unknown",
            "`$level` is not a reasoning level of ${entry.pickerValue}. $levels",
        )
    }

    private fun sameToken(left: String, right: String): Boolean =
        left.trim().equals(right.trim(), ignoreCase = true)
}
