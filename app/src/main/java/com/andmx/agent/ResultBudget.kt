package com.andmx.agent

object ResultBudget {
    const val DEFAULT_MAX_MODEL_BYTES = 100_000

    fun emptyOutput(toolName: String): String = "($toolName completed with no output)"

    fun persistedEnvelope(content: String, originalBytes: Int, persistedPath: String): String {
        val previewChars = 2_000
        val (preview, hasMore) = previewFirstChars(content, previewChars)
        return buildList {
            add("<persisted-output>")
            add("Output too large (${formatDecimalBytes(originalBytes)}). Full output saved to: $persistedPath")
            add("")
            add("Preview (first ${formatDecimalBytes(previewChars)}):")
            add(preview)
            if (hasMore) add("...")
            add("</persisted-output>")
        }.joinToString("\n")
    }

    private fun previewFirstChars(content: String, maxChars: Int): Pair<String, Boolean> {
        if (content.length <= maxChars) return content to false
        val newline = content.substring(0, maxChars).lastIndexOf('\n')
        val end = if (newline > maxChars * 0.5) newline else maxChars
        return content.substring(0, end) to true
    }

    private fun formatDecimalBytes(bytes: Int): String = when {
        bytes < 1_000 -> "$bytes B"
        bytes < 1_000_000 -> "${kotlin.math.round(bytes / 1_000.0).toInt()} KB"
        bytes < 1_000_000_000 -> "${kotlin.math.round(bytes / 1_000_000.0).toInt()} MB"
        else -> "${kotlin.math.round(bytes / 1_000_000_000.0).toInt()} GB"
    }

    fun apply(
        content: String,
        maxModelBytes: Int,
        strategy: String = "truncate",
        direction: String = "head",
    ): String {
        val cap = maxOf(0, maxModelBytes)
        val originalBytes = content.toByteArray(Charsets.UTF_8).size
        if (originalBytes <= cap) return content
        val suffix =
            "\n\n[Tool output truncated by resultBudget: originalBytes=$originalBytes, maxModelBytes=$cap, strategy=$strategy]"
        return fitContentWithSuffix(content, cap, suffix, direction)
    }

    fun fitContentWithSuffix(content: String, maxBytes: Int, suffix: String, direction: String): String {
        if (maxBytes <= 0) return ""
        val suffixContent = fitStringToBytes(suffix, maxBytes, "head")
        val remaining = maxBytes - suffixContent.toByteArray(Charsets.UTF_8).size
        if (remaining <= 0) return suffixContent
        return fitStringToBytes(content, remaining, direction) + suffixContent
    }

    fun fitStringToBytes(value: String, maxBytes: Int, direction: String): String {
        if (maxBytes <= 0) return ""
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        val chars = value.toCharArray().map { it.toString() }
        var low = 0
        var high = chars.size
        while (low < high) {
            val mid = (low + high + 1) / 2
            val candidate = if (direction == "tail") {
                chars.takeLast(mid).joinToString("")
            } else {
                chars.take(mid).joinToString("")
            }
            if (candidate.toByteArray(Charsets.UTF_8).size <= maxBytes) low = mid else high = mid - 1
        }
        return if (direction == "tail") chars.takeLast(low).joinToString("") else chars.take(low).joinToString("")
    }
}
