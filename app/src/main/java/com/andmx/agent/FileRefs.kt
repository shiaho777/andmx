package com.andmx.agent

/**
 * Extracts `@path` file references from a user message so their contents can be
 * pulled into the model's context (Codex-style @ mentions). Pure / testable.
 */
object FileRefs {
    // @ followed by a path-ish token (letters, digits, / . _ - ~)
    private val pattern = Regex("(?<![\\w@])@([\\w./~-]+)")

    fun parse(text: String): List<String> =
        pattern.findAll(text).map { it.groupValues[1] }.filter { it.length > 1 }.distinct().toList()

    /** Build the augmented message: original text plus referenced file blocks. */
    fun augment(text: String, read: (String) -> String?): String {
        val refs = parse(text)
        if (refs.isEmpty()) return text
        val sb = StringBuilder(text)
        for (ref in refs) {
            val content = read(ref)
            sb.append("\n\n")
            if (content == null) {
                sb.append("(引用文件 @$ref 读取失败)")
            } else {
                sb.append("引用文件 @").append(ref).append(":\n```\n").append(content).append("\n```")
            }
        }
        return sb.toString()
    }
}
