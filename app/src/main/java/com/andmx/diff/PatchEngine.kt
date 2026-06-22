package com.andmx.diff

/**
 * Applies a unified diff to file content. Rather than trusting hunk line
 * numbers (which drift), it locates each hunk's context+removed block by
 * content and replaces it — a forgiving, fuzz-tolerant apply that suits an
 * LLM-generated patch.
 */
object PatchEngine {

    private data class Hunk(val oldLines: List<String>, val newLines: List<String>)

    sealed interface Result {
        data class Ok(val content: String) : Result
        data class Fail(val reason: String) : Result
    }

    fun apply(original: String, patch: String): Result {
        val hunks = parse(patch)
        if (hunks.isEmpty()) return Result.Fail("补丁中没有可用的 hunk")

        val lines = if (original.isEmpty()) mutableListOf() else original.split("\n").toMutableList()
        var searchFrom = 0

        for ((hi, hunk) in hunks.withIndex()) {
            if (hunk.oldLines.isEmpty()) {
                // pure insertion: append at current cursor
                lines.addAll(searchFrom.coerceAtMost(lines.size), hunk.newLines)
                searchFrom += hunk.newLines.size
                continue
            }
            val at = indexOfSub(lines, hunk.oldLines, searchFrom)
            if (at < 0) return Result.Fail("第 ${hi + 1} 个 hunk 的上下文未在文件中找到")
            repeat(hunk.oldLines.size) { lines.removeAt(at) }
            lines.addAll(at, hunk.newLines)
            searchFrom = at + hunk.newLines.size
        }
        return Result.Ok(lines.joinToString("\n"))
    }

    private fun indexOfSub(hay: List<String>, needle: List<String>, from: Int): Int {
        if (needle.isEmpty()) return from
        var i = from.coerceAtLeast(0)
        while (i + needle.size <= hay.size) {
            var match = true
            for (j in needle.indices) if (hay[i + j] != needle[j]) { match = false; break }
            if (match) return i
            i++
        }
        // second pass from start (tolerate out-of-order hints)
        i = 0
        while (i + needle.size <= hay.size) {
            var match = true
            for (j in needle.indices) if (hay[i + j] != needle[j]) { match = false; break }
            if (match) return i
            i++
        }
        return -1
    }

    private fun parse(patch: String): List<Hunk> {
        val hunks = ArrayList<Hunk>()
        var old = ArrayList<String>()
        var new = ArrayList<String>()
        var inHunk = false

        fun flush() {
            if (inHunk && (old.isNotEmpty() || new.isNotEmpty())) hunks.add(Hunk(old, new))
            old = ArrayList(); new = ArrayList()
        }

        for (raw in patch.split("\n")) {
            when {
                raw.startsWith("@@") -> { flush(); inHunk = true }
                raw.startsWith("---") || raw.startsWith("+++") -> { /* file headers: ignore */ }
                !inHunk -> { /* preamble */ }
                raw.startsWith("+") -> new.add(raw.substring(1))
                raw.startsWith("-") -> old.add(raw.substring(1))
                raw.startsWith(" ") -> { old.add(raw.substring(1)); new.add(raw.substring(1)) }
                raw.isEmpty() -> { old.add(""); new.add("") }
                raw == "\\ No newline at end of file" -> {}
                else -> { old.add(raw); new.add(raw) }
            }
        }
        flush()
        return hunks
    }
}
