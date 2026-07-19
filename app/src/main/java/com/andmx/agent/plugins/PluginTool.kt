package com.andmx.agent.plugins

import android.content.Context
import com.andmx.agent.Tool
import com.andmx.agent.ToolResult
import com.andmx.agent.ToolRisk
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
 * A tool backed by an executable script declared in a plugin manifest.
 *
 * Each entry in [PluginManifest.tools] names a script (relative to the plugin
 * directory). This tool runs `<pluginDir>/<script> <args>` inside the proot
 * guest and returns its output — turning a declarative plugin tool into a real
 * callable agent tool. The script receives the tool's `args` JSON on argv
 * (space-joined values) and on stdin.
 *
 * @param toolName  the agent-facing tool name (namespaced, e.g. `plugin_lint`)
 * @param pluginDir absolute plugin directory in the guest
 * @param script    script filename, relative to [pluginDir]
 * @param desc      human-readable description for the model
 */
class PluginTool(
    context: Context,
    private val toolName: String,
    private val pluginDir: String,
    private val script: String,
    private val desc: String,
) : Tool {
    override val name = toolName
    override val description = desc
    override val risk = ToolRisk.EXECUTE

    private val env = LocalProotEnvironment(context, ProotRuntime(context))

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("args") {
                put("type", "string")
                put("description", "传给脚本的参数(JSON 字符串,脚本可从 stdin 读取)")
            }
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val scriptPath = "$pluginDir/$script"
        val input = args["args"]?.jsonPrimitive?.content ?: args.toString()
        return runCatching {
            val escaped = input.replace("'", "'\"'\"'")
            val cmd = buildString {
                append("cd '")
                append(pluginDir)
                append("' && ")
                append("if [ -x '")
                append(scriptPath)
                append("' ]; then '")
                append(scriptPath)
                append("' '")
                append(escaped)
                append("'; elif [ -f '")
                append(scriptPath)
                append("' ]; then /bin/sh '")
                append(scriptPath)
                append("' '")
                append(escaped)
                append("'; else echo 'script not found: ")
                append(scriptPath)
                append("' >&2; exit 127; fi")
            }
            val res = env.execute(
                ProcessSpec(
                    argv = listOf("/bin/sh", "-c", cmd),
                    stdin = input,
                    redirectErrorStream = true,
                ),
            )
            val out = (res.stdout + res.stderr).take(16_000)
            if (res.exitCode == 0) ToolResult(out.ifBlank { "(无输出)" })
            else ToolResult("脚本退出码 ${res.exitCode}: $out", isError = true)
        }.getOrElse { ToolResult("插件工具执行失败: ${it.message}", isError = true) }
    }
}
