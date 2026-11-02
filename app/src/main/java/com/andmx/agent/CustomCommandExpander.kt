package com.andmx.agent

/**
 * ZCode custom command 展开：支持 `$ARGUMENTS`、`$1..$n` 与 `${n}`；
 * 没有占位符时把参数追加到 prompt 末尾。
 */
object CustomCommandExpander {
    private val positional = Regex("""\$\{(\d+)}""")
    private val positionalShort = Regex("""\$(\d+)""")

    fun expand(prompt: String, args: String): String {
        val trimmed = args.trim()
        val parts = trimmed.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val hasPlaceholder = prompt.contains("\$ARGUMENTS") ||
            positional.containsMatchIn(prompt) ||
            positionalShort.containsMatchIn(prompt)
        var out = positional.replace(prompt) { m ->
            parts.getOrNull((m.groupValues[1].toIntOrNull() ?: 0) - 1) ?: m.value
        }
        out = positionalShort.replace(out) { m ->
            parts.getOrNull((m.groupValues[1].toIntOrNull() ?: 0) - 1) ?: m.value
        }
        out = out
            .replace("\${ARGUMENTS}", trimmed)
            .replace("\$ARGUMENTS", trimmed)
        if (!hasPlaceholder && trimmed.isNotEmpty()) {
            out = prompt.trimEnd() + "\n\n" + trimmed
        }
        return out.trim()
    }
}
