package com.andmx.diff

/**
 * Codex-style freeform patch engine.
 *
 * Supports the `*** Begin Patch` / `*** End Patch` format that Codex uses,
 * with three operations: Add File, Delete File, Update File (with optional Move).
 *
 * Also remains backward-compatible with unified diff format (delegates to [PatchEngine]).
 *
 * Format reference (from Codex binary reverse-engineering):
 * ```
 * *** Begin Patch
 * *** Add File: <path>
 * +line content
 * +another line
 * *** Update File: <path>
 * *** Move to: <new_path>        (optional, must come right after Update header)
 * @@ context anchor
 * -removed line
 * +added line
 *  context line
 * *** Delete File: <path>
 * *** End Patch
 * ```
 *
 * Within an Update File section, hunk context anchors start with `@@` (no line
 * numbers needed — we match by content, same as the unified-diff engine).
 * Lines prefixed with `+` are additions, `-` are removals, ` ` (space) is context.
 */
object CodexPatchEngine {

    sealed interface Result {
        /** Patch applied successfully. Contains all file changes made. */
        data class Ok(val changes: List<FileChange>) : Result
        data class Fail(val reason: String) : Result
    }

    data class FileChange(
        val path: String,
        val newPath: String?,        // non-null if file was moved/renamed
        val action: Action,
        val oldContent: String,
        val newContent: String,
    )

    enum class Action { ADD, UPDATE, DELETE }

    // ── Public API ──────────────────────────────────────────────

    /**
     * Apply a Codex freeform patch against a file-system-like interface.
     *
     * @param patch the raw patch text (*** Begin Patch ... *** End Patch)
     * @param fs operations for reading/writing/checking files
     * @return Ok with list of changes, or Fail with reason
     */
    fun apply(patch: String, fs: PatchFileSystem): Result {
        val operations = parse(patch)
        if (operations.isEmpty()) {
            // Fall back to unified diff if it looks like one
            return Result.Fail("补丁中没有可识别的操作")
        }

        val changes = ArrayList<FileChange>()
        for (op in operations) {
            when (op) {
                is Op.AddFile -> {
                    val existed = fs.exists(op.path)
                    if (existed) return Result.Fail("Add File 失败: ${op.path} 已存在")
                    val content = op.lines.joinToString("\n")
                    fs.write(op.path, content)
                    changes.add(FileChange(op.path, null, Action.ADD, "", content))
                }
                is Op.DeleteFile -> {
                    if (!fs.exists(op.path)) return Result.Fail("Delete File 失败: ${op.path} 不存在")
                    val old = fs.read(op.path)
                    fs.delete(op.path)
                    changes.add(FileChange(op.path, null, Action.DELETE, old, ""))
                }
                is Op.UpdateFile -> {
                    if (!fs.exists(op.path)) return Result.Fail("Update File 失败: ${op.path} 不存在")
                    val original = fs.read(op.path)
                    val updated = applyHunks(original, op.hunks)
                    if (updated == null) return Result.Fail("Update File 失败: ${op.path} 的 hunk 上下文未匹配")
                    val targetPath = op.moveTo ?: op.path
                    if (op.moveTo != null) {
                        fs.write(op.moveTo, updated)
                        fs.delete(op.path)
                    } else {
                        fs.write(op.path, updated)
                    }
                    changes.add(FileChange(op.path, op.moveTo, Action.UPDATE, original, updated))
                }
            }
        }
        return Result.Ok(changes)
    }

    /**
     * Check if a patch string is in Codex freeform format.
     */
    fun isFreeform(patch: String): Boolean =
        patch.contains("*** Begin Patch") || patch.contains("*** Add File:") ||
            patch.contains("*** Update File:") || patch.contains("*** Delete File:")

    // ── Internal: Parsing ───────────────────────────────────────

    private sealed interface Op {
        data class AddFile(val path: String, val lines: List<String>) : Op
        data class DeleteFile(val path: String) : Op
        data class UpdateFile(
            val path: String,
            val moveTo: String?,
            val hunks: List<Hunk>,
        ) : Op
    }

    private data class Hunk(
        val anchor: String?,          // @@ context anchor (informational)
        val oldLines: List<String>,
        val newLines: List<String>,
    )

    private fun parse(patch: String): List<Op> {
        val lines = patch.split("\n")
        val ops = ArrayList<Op>()
        var i = 0

        // Skip to *** Begin Patch (if present)
        while (i < lines.size && lines[i].trim() != "*** Begin Patch") i++
        if (i < lines.size) i++ // skip the Begin Patch line

        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("*** Add File:") -> {
                    val path = line.substringAfter("*** Add File:").trim()
                    i++
                    val content = ArrayList<String>()
                    while (i < lines.size && !lines[i].startsWith("***")) {
                        val cl = lines[i]
                        if (cl.startsWith("+")) {
                            content.add(cl.substring(1))
                        } else if (cl.isEmpty()) {
                            content.add("")
                        } else {
                            break
                        }
                        i++
                    }
                    ops.add(Op.AddFile(path, content))
                }
                line.startsWith("*** Delete File:") -> {
                    val path = line.substringAfter("*** Delete File:").trim()
                    ops.add(Op.DeleteFile(path))
                    i++
                }
                line.startsWith("*** Update File:") -> {
                    val path = line.substringAfter("*** Update File:").trim()
                    i++
                    var moveTo: String? = null
                    if (i < lines.size && lines[i].startsWith("*** Move to:")) {
                        moveTo = lines[i].substringAfter("*** Move to:").trim()
                        i++
                    }
                    val hunks = ArrayList<Hunk>()
                    var oldL = ArrayList<String>()
                    var newL = ArrayList<String>()
                    var anchor: String? = null
                    var inHunk = false

                    fun flush() {
                        // Save accumulated hunk even without @@ anchor (LLM may omit it)
                        if (oldL.isNotEmpty() || newL.isNotEmpty()) {
                            hunks.add(Hunk(anchor, oldL, newL))
                        }
                        oldL = ArrayList(); newL = ArrayList()
                    }

                    while (i < lines.size && !lines[i].startsWith("***")) {
                        val h = lines[i]
                        when {
                            h.startsWith("@@") -> {
                                flush()
                                anchor = h.substring(2).trim()
                                inHunk = true
                            }
                            h.startsWith("+") -> { inHunk = true; newL.add(h.substring(1)) }
                            h.startsWith("-") -> { inHunk = true; oldL.add(h.substring(1)) }
                            h.startsWith(" ") -> { inHunk = true; oldL.add(h.substring(1)); newL.add(h.substring(1)) }
                            h.isEmpty() -> { inHunk = true; oldL.add(""); newL.add("") }
                            else -> { inHunk = true; oldL.add(h); newL.add(h) }
                        }
                        i++
                    }
                    flush()
                    ops.add(Op.UpdateFile(path, moveTo, hunks))
                }
                line.trim() == "*** End Patch" -> break
                line.trim().isEmpty() -> i++ // skip blank lines between ops
                else -> i++ // skip unknown lines
            }
        }
        return ops
    }

    // ── Internal: Hunk Application ──────────────────────────────

    private fun applyHunks(original: String, hunks: List<Hunk>): String? {
        if (hunks.isEmpty()) return original
        val lines = if (original.isEmpty()) mutableListOf() else original.split("\n").toMutableList()
        var searchFrom = 0

        for (hunk in hunks) {
            if (hunk.oldLines.isEmpty()) {
                // Pure insertion at cursor
                lines.addAll(searchFrom.coerceAtMost(lines.size), hunk.newLines)
                searchFrom += hunk.newLines.size
                continue
            }
            val at = indexOfSub(lines, hunk.oldLines, searchFrom)
            if (at < 0) {
                // Second pass from start (tolerate drift)
                val retry = indexOfSub(lines, hunk.oldLines, 0)
                if (retry < 0) return null
                repeat(hunk.oldLines.size) { lines.removeAt(retry) }
                lines.addAll(retry, hunk.newLines)
                searchFrom = retry + hunk.newLines.size
            } else {
                repeat(hunk.oldLines.size) { lines.removeAt(at) }
                lines.addAll(at, hunk.newLines)
                searchFrom = at + hunk.newLines.size
            }
        }
        return lines.joinToString("\n")
    }

    private fun indexOfSub(hay: List<String>, needle: List<String>, from: Int): Int {
        if (needle.isEmpty()) return from
        var i = from.coerceAtLeast(0)
        while (i + needle.size <= hay.size) {
            var match = true
            for (j in needle.indices) {
                if (hay[i + j] != needle[j]) { match = false; break }
            }
            if (match) return i
            i++
        }
        return -1
    }
}

/** Abstraction over file operations so the patch engine stays testable. */
interface PatchFileSystem {
    fun exists(path: String): Boolean
    fun read(path: String): String
    fun write(path: String, content: String)
    fun delete(path: String)
}
