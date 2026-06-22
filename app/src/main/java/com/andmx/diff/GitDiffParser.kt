package com.andmx.diff

/** A single file's diff parsed from `git diff` output. */
data class GitFileDiff(
    val path: String,
    val lines: List<DiffLine>,
)

/**
 * Parses unified `git diff` output into per-file [DiffLine] lists that the diff
 * pane can render with the same row renderer used for agent changes. Pure.
 */
object GitDiffParser {

    private val fileHeader = Regex("^diff --git a/(.+) b/(.+)$")
    private val hunkHeader = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@")

    fun parse(diff: String): List<GitFileDiff> {
        val files = ArrayList<GitFileDiff>()
        var path: String? = null
        var lines = ArrayList<DiffLine>()
        var oldNo = 0
        var newNo = 0

        fun flush() {
            if (path != null && lines.isNotEmpty()) files.add(GitFileDiff(path!!, lines))
            lines = ArrayList()
        }

        for (raw in diff.split("\n")) {
            val fh = fileHeader.find(raw)
            when {
                fh != null -> { flush(); path = fh.groupValues[2] }
                raw.startsWith("index ") || raw.startsWith("new file") ||
                    raw.startsWith("deleted file") || raw.startsWith("similarity") ||
                    raw.startsWith("rename ") || raw.startsWith("--- ") || raw.startsWith("+++ ") -> {}
                hunkHeader.matches(raw) -> {
                    val m = hunkHeader.find(raw)!!
                    oldNo = m.groupValues[1].toInt()
                    newNo = m.groupValues[2].toInt()
                    if (lines.isNotEmpty()) lines.add(DiffLine(DiffLine.Kind.CONTEXT, "", null, null)) // hunk gap
                }
                path == null -> {}
                raw.startsWith("+") -> lines.add(DiffLine(DiffLine.Kind.ADD, raw.substring(1), newNo = newNo++))
                raw.startsWith("-") -> lines.add(DiffLine(DiffLine.Kind.REMOVE, raw.substring(1), oldNo = oldNo++))
                raw.startsWith(" ") -> lines.add(DiffLine(DiffLine.Kind.CONTEXT, raw.substring(1), oldNo++, newNo++))
                raw.startsWith("\\") -> {} // "\ No newline at end of file"
            }
        }
        flush()
        return files
    }
}
