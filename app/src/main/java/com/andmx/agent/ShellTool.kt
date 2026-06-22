package com.andmx.agent

import android.content.Context
import com.andmx.exec.PersistentShell
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
 * The agent's primary hand: run a shell command inside the proot Linux guest
 * and return its combined output. This is what turns AndMX from a chat app
 * into a workbench.
 *
 * Uses [PersistentShell] for 5-10x faster execution by keeping a long-running
 * shell process alive. Falls back to fork+exec per command if the persistent
 * shell is unavailable or for commands that need a fresh environment.
 */
class ShellTool(
    context: Context,
    /** Provides the current project working directory (guest absolute path). */
    private val cwdProvider: () -> String = { "/root" },
) : Tool {
    private val runtime = ProotRuntime(context)
    private val env = LocalProotEnvironment(context, runtime)
    private val persistentShell = PersistentShell(context, runtime)
    private var usePersistent = false

    override val name = "run_shell"
    override val description =
        "在设备上的 Linux (Alpine/proot) 沙箱里执行一条 shell 命令,返回合并后的标准输出与错误输出。" +
            "默认在当前项目目录下执行。可用于读写文件、运行 git/python、安装软件包(apk add)等。" +
            "支持并行执行: 可以同时调用多个 run_shell。"

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("command") {
                put("type", "string")
                put("description", "要执行的 shell 命令,例如 'ls -la /root' 或 'python3 -c \"print(1+1)\"'")
            }
        }
        putJsonArray("required") { add("command") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val command = args["command"]?.jsonPrimitive?.content
            ?: return ToolResult("缺少参数 command", isError = true)

        // Run in the active project directory so file/git operations land in
        // the right place (Codex parity: project = cwd).
        val cwd = cwdProvider()
        val cdCommand = if (cwd.isNotBlank() && cwd != "/root") "cd '$cwd' 2>/dev/null; $command" else command

        // Try persistent shell first (much faster)
        if (usePersistent && persistentShell.isAlive) {
            val res = persistentShell.exec(cdCommand)
            if (res.error == null) {
                val out = buildString {
                    append(res.stdout.ifBlank { "(无输出)" })
                    append("\n[exit=${res.exitCode}]")
                }
                return ToolResult(out.take(16_000), isError = res.exitCode != 0)
            }
            // Persistent shell failed — fall through to fork+exec
        }

        // Fallback: fork+exec per command
        val sh = if (runtime.rootfsDir.exists()) "/bin/sh" else "/system/bin/sh"
        val res = env.execute(ProcessSpec(argv = listOf(sh, "-lc", cdCommand)))
        if (res.error != null) return ToolResult("执行失败: ${res.error}", isError = true)

        val out = buildString {
            append(res.stdout.ifBlank { "(无输出)" })
            append("\n[exit=${res.exitCode}]")
        }
        return ToolResult(out.take(16_000), isError = res.exitCode != 0)
    }

    /** Start the persistent shell for faster subsequent executions. */
    suspend fun enablePersistentShell(): Boolean {
        usePersistent = true
        return persistentShell.start()
    }

    /** Destroy the persistent shell. */
    fun disablePersistentShell() {
        usePersistent = false
        persistentShell.destroy()
    }
}
