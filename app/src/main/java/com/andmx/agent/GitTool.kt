package com.andmx.agent

import android.content.Context
import com.andmx.workspace.WorkspaceAccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class GitTool(
    context: Context,
    private val cwdProvider: () -> String = { WorkspaceAccess(context).guestCwd() },
) : Tool {
    private val access = WorkspaceAccess(context)

    override val name = "git"
    override val description =
        "在当前工作区执行 git 子命令(如 status / diff / add / commit / log)。默认在项目目录执行。"
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("args") { put("type", "string"); put("description", "git 之后的参数,如 'status -s' 或 'commit -m \"msg\"'") }
            putJsonObject("cwd") { put("type", "string"); put("description", "工作目录(仓库路径),默认为当前项目目录") }
        }
        putJsonArray("required") { add("args") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val gitArgs = args["args"]?.jsonPrimitive?.content ?: return ToolResult("缺少参数 args", isError = true)
        val cwd = args["cwd"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: cwdProvider()
        val script =
            "command -v git >/dev/null 2>&1 || " +
                "(command -v apk >/dev/null 2>&1 && apk add --no-cache git >/dev/null 2>&1) || true; " +
                "git -c core.pager=cat $gitArgs"
        val res = access.executeShell(script, cwd = cwd)
        if (res.error != null) return ToolResult("执行失败: ${res.error}", isError = true)
        val out = buildString {
            append(res.stdout.ifBlank { "(无输出)" })
            append("\n[exit=${res.exitCode}]")
        }
        return ToolResult(out.take(16_000), isError = res.exitCode != 0)
    }
}
