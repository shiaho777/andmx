package com.andmx.agent

import android.content.Context
import com.andmx.diff.CodexPatchEngine
import com.andmx.diff.PatchEngine
import com.andmx.diff.PatchFileSystem
import com.andmx.workspace.ChangeTracker
import com.andmx.workspace.WorkspaceAccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class ApplyPatchTool(context: Context) : Tool {
    private val access = WorkspaceAccess(context)

    private inner class AccessAdapter : PatchFileSystem {
        override fun exists(path: String): Boolean = runBlocking { access.exists(path) }
        override fun read(path: String): String = runBlocking { access.readText(path) }
        override fun write(path: String, content: String) = runBlocking { access.writeText(path, content) }
        override fun delete(path: String) { runBlocking { access.deleteFile(path) } }
    }

    override val name = "apply_patch"
    override val description =
        "对当前工作区中的文件应用补丁。支持 Codex freeform 与 unified diff。变更会进入 diff 审查。"
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
            if (CodexPatchEngine.isFreeform(patch)) applyFreeform(patch)
            else {
                if (path == null) ToolResult("unified diff 模式需要 path 参数", isError = true)
                else applyUnified(path, patch)
            }
        }.getOrElse { ToolResult("应用补丁失败: ${it.message}", isError = true) }
    }

    private fun applyFreeform(patch: String): ToolResult {
        val adapter = AccessAdapter()
        return when (val r = CodexPatchEngine.apply(patch, adapter)) {
            is CodexPatchEngine.Result.Ok -> {
                for (change in r.changes) {
                    if (change.action == CodexPatchEngine.Action.DELETE) {
                        ChangeTracker.record(change.path, change.oldContent, "", existedBefore = true)
                    } else if (change.action == CodexPatchEngine.Action.ADD) {
                        ChangeTracker.record(
                            change.newPath ?: change.path,
                            "",
                            change.newContent,
                            existedBefore = false,
                        )
                    } else {
                        ChangeTracker.record(
                            change.path,
                            change.oldContent,
                            change.newContent,
                            existedBefore = true,
                        )
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
                ToolResult("已应用补丁: $summary (${r.changes.size} 个文件)")
            }
            is CodexPatchEngine.Result.Fail -> ToolResult("应用补丁失败: ${r.reason}", isError = true)
        }
    }

    private fun applyUnified(path: String, patch: String): ToolResult {
        val resolved = access.resolvePath(path)
        val existed = runBlocking { access.exists(resolved) }
        val original = if (existed) runBlocking { access.readText(resolved) } else ""
        return when (val r = PatchEngine.apply(original, patch)) {
            is PatchEngine.Result.Ok -> {
                runBlocking { access.writeText(resolved, r.content) }
                ChangeTracker.record(resolved, original, r.content, existedBefore = existed)
                ToolResult("已应用补丁到 $resolved")
            }
            is PatchEngine.Result.Fail -> ToolResult("应用补丁失败: ${r.reason}", isError = true)
        }
    }
}
