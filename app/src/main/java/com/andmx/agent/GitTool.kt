package com.andmx.agent

import android.content.Context
import com.andmx.exec.ProcessSpec
import com.andmx.exec.proot.LocalProotEnvironment
import com.andmx.exec.proot.ProotRuntime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * git: run a git subcommand in the guest. git is installed on demand
 * (`apk add git`) the first time it's used, so it works on a fresh rootfs.
 */
class GitTool(
    context: Context,
    /** Provides the current project working directory (guest absolute path). */
    private val cwdProvider: () -> String = { "/root" },
) : Tool {
    private val runtime = ProotRuntime(context)
    private val env = LocalProotEnvironment(context, runtime)

    override val name = "git"
    override val description =
        "在 Linux 沙箱中执行 git 子命令(如 status / diff / add / commit / log)。默认在当前项目目录执行。首次使用会自动安装 git。"
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
        // Prefer the explicit cwd arg, fall back to the active project directory.
        val cwd = args["cwd"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: cwdProvider()
        val sh = if (runtime.rootfsDir.exists()) "/bin/sh" else "/system/bin/sh"
        val script =
            "cd '$cwd' 2>/dev/null || cd /root; " +
                "command -v git >/dev/null 2>&1 || apk add --no-cache git >/dev/null 2>&1; " +
                "git -c core.pager=cat $gitArgs"
        val res = env.execute(ProcessSpec(argv = listOf(sh, "-lc", script)))
        if (res.error != null) return ToolResult("执行失败: ${res.error}", isError = true)
        val out = buildString {
            append(res.stdout.ifBlank { "(无输出)" })
            append("\n[exit=${res.exitCode}]")
        }
        return ToolResult(out.take(16_000), isError = res.exitCode != 0)
    }
}
