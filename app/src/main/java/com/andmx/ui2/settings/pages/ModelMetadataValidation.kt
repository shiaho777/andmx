package com.andmx.ui2.settings.pages

internal enum class AddModelProblem { BLANK_ID, DUPLICATE_ID, INVALID_CONTEXT }

internal fun validateNewModel(
    id: String,
    existingIds: Set<String>,
    contextWindowText: String,
): AddModelProblem? {
    val trimmed = id.trim()
    if (trimmed.isBlank()) return AddModelProblem.BLANK_ID
    if (trimmed in existingIds) return AddModelProblem.DUPLICATE_ID
    if ((contextWindowText.toIntOrNull() ?: 0) <= 0) return AddModelProblem.INVALID_CONTEXT
    return null
}

internal val MODALITY_ORDER = listOf("text", "image", "video", "audio", "pdf")

internal fun normalizeInputModalities(selected: Iterable<String>): List<String> {
    val picked = selected.toSet()
    return MODALITY_ORDER.filter { it in picked }
}

internal fun newModelModalities(selected: Iterable<String>): List<String> =
    normalizeInputModalities(selected + "text")

internal fun splitInputModalities(modalities: Iterable<String>): Set<String> =
    (normalizeInputModalities(modalities) + "text").toSet()

internal enum class ModelMetadataProblem { ID, CONTEXT_WINDOW, MAX_OUTPUT_TOKENS, INPUT_MODALITIES }

internal data class ModelMetadataDraft(
    val id: String,
    val contextWindowText: String,
    val maxOutputTokensText: String,
    val inputModalities: List<String>,
)

internal fun validateModelMetadata(draft: ModelMetadataDraft): ModelMetadataProblem? {
    if (draft.id.trim().isEmpty()) return ModelMetadataProblem.ID
    if (!isValidTokenCount(draft.contextWindowText)) return ModelMetadataProblem.CONTEXT_WINDOW
    if (!isValidTokenCount(draft.maxOutputTokensText)) return ModelMetadataProblem.MAX_OUTPUT_TOKENS
    if ("text" !in normalizeInputModalities(draft.inputModalities)) {
        return ModelMetadataProblem.INPUT_MODALITIES
    }
    return null
}

internal fun isValidTokenCount(raw: String): Boolean {
    val t = raw.trim()
    if (t.isEmpty() || t == "0") return true
    val n = t.toDoubleOrNull() ?: return false
    if (!n.isFinite()) return false
    val asLong = n.toLong()
    return n == asLong.toDouble() && asLong > 0 && asLong <= Int.MAX_VALUE
}

internal fun metadataTokenText(value: Int): String = if (value > 0) value.toString() else ""

internal fun modelMetadataProblemText(problem: ModelMetadataProblem): String = when (problem) {
    ModelMetadataProblem.ID -> "模型 ID 不能为空"
    ModelMetadataProblem.CONTEXT_WINDOW -> "上下文窗口必须是正整数"
    ModelMetadataProblem.MAX_OUTPUT_TOKENS -> "最大输出 Token 必须是正整数"
    ModelMetadataProblem.INPUT_MODALITIES -> "输入类型必须包含文本"
}
