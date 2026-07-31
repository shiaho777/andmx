package com.andmx.ui2.chat

import com.andmx.agent.ToolArgs
import com.andmx.diff.DiffEngine
import com.andmx.diff.DiffLine
import com.andmx.diff.DiffStats
import com.andmx.diff.GitDiffParser

data class ToolEditPreview(
    val path: String,
    val operation: Operation,
    val stats: DiffStats,
    val lines: List<DiffLine>,
) {
    enum class Operation { WRITE, EDIT, DELETE }
}

object ToolEditDiff {
    private val writeNames = setOf("Write", "write_file")
    private val editNames = setOf("Edit", "edit_file")
    private val patchNames = setOf("apply_patch", "ApplyPatch")

    fun isEditTool(name: String): Boolean {
        val n = name.trim()
        return n in writeNames || n in editNames || n in patchNames
    }

    fun preview(name: String, args: String): ToolEditPreview? {
        val n = name.trim()
        return when {
            n in writeNames -> writePreview(args)
            n in editNames -> editPreview(args)
            n in patchNames -> patchPreview(args)
            else -> null
        }
    }

    private fun writePreview(args: String): ToolEditPreview? {
        val path = ToolArgs.pathOf(args).ifBlank { return null }
        val content = contentOf(args) ?: return ToolEditPreview(
            path = path,
            operation = ToolEditPreview.Operation.WRITE,
            stats = DiffStats(0, 0),
            lines = emptyList(),
        )
        val lines = DiffEngine.diff("", content)
        return ToolEditPreview(
            path = path,
            operation = ToolEditPreview.Operation.WRITE,
            stats = DiffEngine.stats(lines),
            lines = lines,
        )
    }

    private fun editPreview(args: String): ToolEditPreview? {
        val path = ToolArgs.pathOf(args).ifBlank { return null }
        val oldStr = ToolArgs.firstValue(args, "old_string", "old_str", "oldString", "old_text", "oldText")
        val newStr = ToolArgs.firstValue(args, "new_string", "new_str", "newString", "new_text", "newText")
        if (oldStr.isBlank() && newStr.isBlank()) {
            return ToolEditPreview(
                path = path,
                operation = ToolEditPreview.Operation.EDIT,
                stats = DiffStats(0, 0),
                lines = emptyList(),
            )
        }
        val lines = DiffEngine.diff(oldStr, newStr)
        return ToolEditPreview(
            path = path,
            operation = ToolEditPreview.Operation.EDIT,
            stats = DiffEngine.stats(lines),
            lines = lines,
        )
    }

    private fun patchPreview(args: String): ToolEditPreview? {
        val pathHint = ToolArgs.pathOf(args)
        val patch = ToolArgs.firstValue(args, "patch", "diff", "patch_text").ifBlank {
            val raw = args.trim()
            if (raw.contains("@@") || raw.contains("*** Begin Patch") || raw.startsWith("diff ")) raw else ""
        }
        if (patch.isBlank()) return null

        val unified = normalizePatch(patch, pathHint)
        val files = runCatching { GitDiffParser.parse(unified) }.getOrDefault(emptyList())
        if (files.isNotEmpty()) {
            val file = files.first()
            val stats = DiffEngine.stats(file.lines)
            return ToolEditPreview(
                path = file.path.ifBlank { pathHint },
                operation = when {
                    stats.added > 0 && stats.removed == 0 -> ToolEditPreview.Operation.WRITE
                    stats.added == 0 && stats.removed > 0 -> ToolEditPreview.Operation.DELETE
                    else -> ToolEditPreview.Operation.EDIT
                },
                stats = stats,
                lines = file.lines,
            )
        }

        val lines = parseLooseDiffLines(patch)
        if (lines.isEmpty()) return null
        val stats = DiffEngine.stats(lines)
        return ToolEditPreview(
            path = pathHint.ifBlank { extractPathFromPatch(patch) },
            operation = ToolEditPreview.Operation.EDIT,
            stats = stats,
            lines = lines,
        )
    }

    private fun contentOf(args: String): String? {
        val content = ToolArgs.firstValue(args, "content", "new_string", "new_str", "newString", "text", "new_text", "newText")
        return content.takeIf { it.isNotBlank() }
    }

    private fun normalizePatch(patch: String, pathHint: String): String {
        val t = patch.trim()
        if (t.startsWith("*** Begin Patch")) {
            return codexPatchToUnified(t, pathHint)
        }
        if (t.contains("@@") && !t.contains("diff --git") && !t.startsWith("--- ")) {
            val name = pathHint.ifBlank { "file" }.substringAfterLast('/')
            return buildString {
                appendLine("--- a/$name")
                appendLine("+++ b/$name")
                append(t)
            }
        }
        return t
    }

    private fun codexPatchToUnified(patch: String, pathHint: String): String {
        val lines = patch.lines()
        val out = StringBuilder()
        var path = pathHint
        var oldLines = ArrayList<String>()
        var newLines = ArrayList<String>()
        fun flush() {
            if (path.isBlank() && oldLines.isEmpty() && newLines.isEmpty()) return
            val name = path.ifBlank { "file" }
            val fileName = name.substringAfterLast('/')
            if (oldLines.isEmpty() && newLines.isNotEmpty()) {
                out.appendLine("diff --git a/$fileName b/$fileName")
                out.appendLine("--- /dev/null")
                out.appendLine("+++ b/$fileName")
                out.appendLine("@@ -0,0 +1,${newLines.size} @@")
                newLines.forEach { out.appendLine("+$it") }
            } else {
                out.appendLine("diff --git a/$fileName b/$fileName")
                out.appendLine("--- a/$fileName")
                out.appendLine("+++ b/$fileName")
                out.appendLine("@@ -1,${oldLines.size.coerceAtLeast(1)} +1,${newLines.size.coerceAtLeast(1)} @@")
                oldLines.forEach { out.appendLine("-$it") }
                newLines.forEach { out.appendLine("+$it") }
            }
            oldLines = ArrayList()
            newLines = ArrayList()
        }
        var mode = ""
        for (line in lines) {
            when {
                line.startsWith("*** Add File:") -> {
                    flush()
                    path = line.substringAfter(':').trim()
                    mode = "add"
                }
                line.startsWith("*** Update File:") || line.startsWith("*** Delete File:") -> {
                    flush()
                    path = line.substringAfter(':').trim()
                    mode = "update"
                }
                line.startsWith("*** End Patch") || line.startsWith("*** Begin Patch") -> Unit
                line.startsWith("+") && mode.isNotBlank() -> newLines += line.drop(1)
                line.startsWith("-") && mode.isNotBlank() -> oldLines += line.drop(1)
                line.startsWith(" ") && mode.isNotBlank() -> {
                    oldLines += line.drop(1)
                    newLines += line.drop(1)
                }
            }
        }
        flush()
        return out.toString()
    }

    private fun parseLooseDiffLines(patch: String): List<DiffLine> {
        val out = ArrayList<DiffLine>()
        var oldNo = 1
        var newNo = 1
        for (raw in patch.lines()) {
            when {
                raw.startsWith("+++") || raw.startsWith("---") || raw.startsWith("@@") ||
                    raw.startsWith("diff ") || raw.startsWith("index ") ||
                    raw.startsWith("***") -> Unit
                raw.startsWith("+") -> out += DiffLine(DiffLine.Kind.ADD, raw.drop(1), newNo = newNo++)
                raw.startsWith("-") -> out += DiffLine(DiffLine.Kind.REMOVE, raw.drop(1), oldNo = oldNo++)
                raw.startsWith(" ") -> out += DiffLine(DiffLine.Kind.CONTEXT, raw.drop(1), oldNo++, newNo++)
            }
        }
        return out
    }

    private fun extractPathFromPatch(patch: String): String {
        for (line in patch.lines()) {
            when {
                line.startsWith("+++ b/") -> return line.removePrefix("+++ b/").trim()
                line.startsWith("*** Update File:") -> return line.substringAfter(':').trim()
                line.startsWith("*** Add File:") -> return line.substringAfter(':').trim()
            }
        }
        return ""
    }

    fun focusedPreview(lines: List<DiffLine>, limit: Int = 56): List<DiffLine> {
        if (lines.isEmpty()) return emptyList()
        if (lines.all { it.kind == DiffLine.Kind.ADD }) {
            return lines.take(limit)
        }
        val first = lines.indexOfFirst { it.kind != DiffLine.Kind.CONTEXT }
        if (first < 0) return lines.take(limit)
        val start = (first - 2).coerceAtLeast(0)
        val end = (start + limit).coerceAtMost(lines.size)
        return lines.subList(start, end)
    }
}
