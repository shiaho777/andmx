package com.andmx.agent

import android.content.Context
import com.andmx.diff.CodexPatchEngine
import com.andmx.diff.PatchEngine
import com.andmx.diff.PatchFileSystem
import com.andmx.exec.files.GuestFs
import com.andmx.exec.proot.ProotRuntime
import com.andmx.workspace.ChangeTracker
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * apply_patch: Edit files using either Codex freeform patch format or
 * unified diff. Changes enter the diff review panel.
 *
 * Supports two formats:
 * 1. Codex freeform: `*** Begin Patch` / `*** Add File:` / `*** Update File:` / `*** Delete File:` / `*** End Patch`
 * 2. Unified diff: `@@` hunks with `+` / `-` / ` ` line prefixes
 *
 * The engine auto-detects which format is used.
 */
class ApplyPatchTool(context: Context) : Tool {
    private val fs = GuestFs(ProotRuntime(context))

    /** Adapter from GuestFs to PatchFileSystem. */
    private inner class GuestFsAdapter(val guestPath: String) : PatchFileSystem {
        override fun exists(path: String): Boolean = fs.exists(path)
        override fun read(path: String): String = fs.readText(path)
        override fun write(path: String, content: String) = fs.writeText(path, content)
        override fun delete(path: String) { fs.deleteFile(path) }
    }

    override val name = "apply_patch"
    override val description =
        "对沙箱中的文件应用补丁。支持两种格式:" +
            "Codex freeform (*** Begin Patch / *** Add File / *** Update File / *** Delete File / *** End Patch) " +
            "和 unified diff (@@ hunk + +/- 行)。变更会进入 diff 审查。"
    override val risk = ToolRisk.WRITE
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "目标文件路径 (unified diff 模式必填; freeform 模式可省略)")
            }
            putJsonObject("patch") {
                put("type", "string")
                put("description", "补丁文本。Codex freeform 或 unified diff 格式")
            }
        }
        putJsonArray("required") { add("patch") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val patch = args["patch"]?.jsonPrimitive?.content
            ?: return ToolResult("缺少参数 patch", isError = true)
        val path = args["path"]?.jsonPrimitive?.content

        return runCatching {
            if (CodexPatchEngine.isFreeform(patch)) {
                // Codex freeform format — may touch multiple files
                applyFreeform(patch)
            } else {
                // Unified diff — single file
                if (path == null) return@runCatching ToolResult("unified diff 模式需要 path 参数", isError = true)
                applyUnified(path, patch)
            }
        }.getOrElse { ToolResult("应用补丁失败: ${it.message}", isError = true) }
    }

    private fun applyFreeform(patch: String): ToolResult {
        val adapter = GuestFsAdapter("")
        when (val r = CodexPatchEngine.apply(patch, adapter)) {
            is CodexPatchEngine.Result.Ok -> {
                // Record all changes in ChangeTracker
                for (change in r.changes) {
                    val targetPath = change.newPath ?: change.path
                    if (change.action != CodexPatchEngine.Action.DELETE) {
                        ChangeTracker.record(targetPath, change.oldContent, change.newContent, existedBefore = change.action != CodexPatchEngine.Action.ADD)
                    } else {
                        ChangeTracker.record(change.path, change.oldContent, "", existedBefore = true)
                    }
                }
                val summary = r.changes.joinToString(", ") { c ->
                    val verb = when (c.action) {
                        CodexPatchEngine.Action.ADD -> "新建"
                        CodexPatchEngine.Action.UPDATE -> "更新"
                        CodexPatchEngine.Action.DELETE -> "删除"
                    }
                    "$verb ${c.newPath ?: c.path}"
                }
                return ToolResult("已应用补丁: $summary (${r.changes.size} 个文件)")
            }
            is CodexPatchEngine.Result.Fail -> return ToolResult("应用补丁失败: ${r.reason}", isError = true)
        }
    }

    private fun applyUnified(path: String, patch: String): ToolResult {
        val existed = fs.exists(path)
        val original = if (existed) fs.readText(path) else ""
        return when (val r = PatchEngine.apply(original, patch)) {
            is PatchEngine.Result.Ok -> {
                fs.writeText(path, r.content)
                ChangeTracker.record(path, original, r.content, existedBefore = existed)
                ToolResult("已应用补丁到 $path")
            }
            is PatchEngine.Result.Fail -> ToolResult("应用补丁失败: ${r.reason}", isError = true)
        }
    }
}
