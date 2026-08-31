package com.andmx.llm.provider

/**
 * Pure helpers around [ClaudeModelMapping].
 *
 * The four slot ids are a closed set in ZCode's engine schema, so the mapping
 * is kept as a typed data class rather than a `Map<String, String>` — a typo in
 * a slot name is then a compile error instead of a silently dropped setting.
 */

/** ZCode's `settings.modelProvider.slot.*` labels. */
internal fun claudeSlotLabel(slot: String): String = when (slot) {
    "haiku" -> "Haiku（轻量任务）"
    "sonnet" -> "Sonnet（常规任务）"
    "opus" -> "Opus（复杂任务）"
    "reasoning" -> "Reasoning（推理任务）"
    else -> slot
}

internal fun claudeSlotValue(mapping: ClaudeModelMapping?, slot: String): String = when (slot) {
    "haiku" -> mapping?.haiku
    "sonnet" -> mapping?.sonnet
    "opus" -> mapping?.opus
    "reasoning" -> mapping?.reasoning
    else -> null
}?.trim().orEmpty()

/** Set one slot, keeping the others. An unknown slot leaves the mapping alone. */
internal fun claudeMappingWithSlot(
    mapping: ClaudeModelMapping?,
    slot: String,
    modelId: String,
): ClaudeModelMapping {
    val current = mapping ?: ClaudeModelMapping()
    val value = modelId.trim()
    return when (slot) {
        "haiku" -> current.copy(haiku = value)
        "sonnet" -> current.copy(sonnet = value)
        "opus" -> current.copy(opus = value)
        "reasoning" -> current.copy(reasoning = value)
        else -> current
    }
}

/** ZCode's `mappingNotSet`: a mapping with every slot blank counts as not configured. */
internal fun isClaudeMappingSet(mapping: ClaudeModelMapping?): Boolean =
    mapping != null && ClaudeModelMapping.SLOT_ORDER.any {
        claudeSlotValue(mapping, it).isNotBlank()
    }

/**
 * Collapse an all-blank mapping to null so we persist ZCode's "the `claude`
 * block is optional" shape instead of an empty object.
 */
internal fun normalizeClaudeMapping(mapping: ClaudeModelMapping?): ClaudeModelMapping? =
    mapping?.takeIf { isClaudeMappingSet(it) }
