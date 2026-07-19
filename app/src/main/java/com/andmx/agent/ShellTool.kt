package com.andmx.agent

import android.content.Context
import com.andmx.exec.PersistentShell
import com.andmx.exec.ProcessSpec
import com.andmx.exec.proot.LocalProotEnvironment
import com.andmx.exec.proot.ProotRuntime
import com.andmx.exec.proot.RootfsInstaller
import com.andmx.exec.pty.PtyProcess
import com.andmx.workspace.WorkspaceAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.charset.StandardCharsets

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
    private val context: Context,
    private val cwdProvider: () -> String = { WorkspaceAccess(context).guestCwd() },
) : Tool, ExecutionAwareTool {
    private val access = WorkspaceAccess(context)
    private val runtime = ProotRuntime(context)
    private val env = LocalProotEnvironment(context, runtime)
    private val persistentShell = PersistentShell(context, runtime)
    private var usePersistent = false
    private val _events = MutableSharedFlow<ShellEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<ShellEvent> = _events

    sealed interface ShellEvent {
        data class Started(val callId: String, val command: String, val cwd: String) : ShellEvent
        data class Delta(val callId: String, val chunk: String) : ShellEvent
        data class Finished(val callId: String, val command: String, val exitCode: Int, val isError: Boolean) : ShellEvent
        data class Failed(val callId: String, val command: String, val message: String) : ShellEvent
    }

    override val name = "run_shell"
    override val description =
        "在当前工作区执行一条 shell 命令并返回合并输出。" +
            "本地工作区运行于 Linux (proot) 沙箱；远程工作区通过 SSH 在远端执行。" +
            "默认在当前项目目录下执行。支持并行：可同时调用多个 run_shell。"

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

    override suspend fun execute(args: JsonObject): ToolResult = execute("", args)

    override suspend fun execute(callId: String, args: JsonObject): ToolResult {
        val command = args["command"]?.jsonPrimitive?.content
            ?: return ToolResult("缺少参数 command", isError = true)
        val prepared = ensureNetworkTools(command)

        val cwd = cwdProvider().ifBlank { access.guestCwd() }
        val cdCommand = if (cwd.isNotBlank()) "cd '$cwd' 2>/dev/null || cd ~; $prepared" else prepared

        if (access.isRemote) {
            if (callId.isNotBlank()) {
                _events.tryEmit(ShellEvent.Started(callId, command, cwd))
            }
            val res = access.executeShell(prepared, cwd = cwd)
            if (res.error != null) {
                if (callId.isNotBlank()) {
                    _events.tryEmit(ShellEvent.Failed(callId, command, res.error ?: "error"))
                }
                return ToolResult("执行失败: ${res.error}", isError = true)
            }
            val out = buildString {
                append(res.stdout.ifBlank { "(无输出)" })
                append("\n[exit=${res.exitCode}]")
            }
            if (callId.isNotBlank()) {
                _events.tryEmit(
                    ShellEvent.Finished(callId, command, res.exitCode, isError = res.exitCode != 0),
                )
            }
            return ToolResult(out.take(16_000), isError = res.exitCode != 0)
        }

        if (callId.isNotBlank()) {
            return executeBound(callId, command, cwd, cdCommand)
        }

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

    private suspend fun executeBound(
        callId: String,
        command: String,
        cwd: String,
        cdCommand: String,
    ): ToolResult = withContext(Dispatchers.IO) {
        _events.tryEmit(ShellEvent.Started(callId, command, cwd))

        val install = runtime.install()
        if (!install.ok) {
            val message = "执行失败: ${install.message}"
            _events.tryEmit(ShellEvent.Failed(callId, command, message))
            return@withContext ToolResult(message, isError = true)
        }

        val installer = RootfsInstaller(runtime)
        if (!installer.isInstalled()) {
            _events.tryEmit(ShellEvent.Delta(callId, "正在安装 Alpine rootfs...\r\n"))
            if (!installer.install { line -> _events.tryEmit(ShellEvent.Delta(callId, "$line\r\n")) }) {
                val message = "执行失败: rootfs 安装失败"
                _events.tryEmit(ShellEvent.Failed(callId, command, message))
                return@withContext ToolResult(message, isError = true)
            }
        }

        val sh = if (runtime.rootfsDir.exists()) "/bin/sh" else "/system/bin/sh"
        val argv = runtime.prootArgv(listOf(sh, "-lc", cdCommand), rootfs = runtime.rootfsDir.takeIf { it.exists() })
        val envp = runtime.env().map { "${it.key}=${it.value}" }.toTypedArray()
        val process = runCatching {
            PtyProcess.start(
                command = runtime.prootBin.path,
                argv = argv.toTypedArray(),
                envp = envp,
                cwd = context.filesDir.path,
                rows = 24,
                cols = 80,
            )
        }.getOrElse {
            val res = env.execute(ProcessSpec(argv = listOf(sh, "-lc", cdCommand)))
            val combined = when {
                res.error != null -> "执行失败: ${res.error}"
                res.stdout.isNotBlank() -> res.stdout
                else -> res.stderr
            }.ifBlank { "(无输出)" }
            _events.tryEmit(ShellEvent.Delta(callId, combined))
            _events.tryEmit(ShellEvent.Finished(callId, command, res.exitCode, res.exitCode != 0 || res.error != null))
            return@withContext ToolResult(
                buildString {
                    append(combined.trimEnd())
                    append("\n[exit=${res.exitCode}]")
                }.take(16_000),
                isError = res.exitCode != 0 || res.error != null,
            )
        }

        val output = StringBuilder()
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val n = process.input.read(buffer)
                if (n <= 0) break
                val chunk = String(buffer, 0, n, StandardCharsets.UTF_8)
                output.append(chunk)
                _events.tryEmit(ShellEvent.Delta(callId, chunk))
            }
        } catch (_: Throwable) {
        }
        val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
        process.destroy()
        _events.tryEmit(ShellEvent.Finished(callId, command, exitCode, exitCode != 0))
        val cleaned = output.toString().replace("\r", "").trimEnd()
        val finalOutput = buildString {
            append(cleaned.ifBlank { "(无输出)" })
            append("\n[exit=$exitCode]")
        }
        ToolResult(finalOutput.take(16_000), isError = exitCode != 0)
    }


    private fun ensureNetworkTools(command: String): String {
        if (!Regex("""\bcurl\b""").containsMatchIn(command)) return command
        val ensure = buildString {
            append("command -v curl >/dev/null 2>&1 || ")
            append("(command -v apk >/dev/null 2>&1 && apk add --no-cache curl wget ca-certificates >/dev/null 2>&1) || true; ")
            append("if ! command -v curl >/dev/null 2>&1 && command -v wget >/dev/null 2>&1; then ")
            append("curl() { ")
            append("local outfile=; local url=; ")
            append("while [ ${'$'}# -gt 0 ]; do ")
            append("case \"${'$'}1\" in ")
            append("-o|--output) outfile=\"${'$'}2\"; shift 2 ;; ")
            append("-O) shift ;; ")
            append("-s|-S|-L|-f|-k|-sS|-sL|-sSL|-sSf|-sSLf|-sSfL|--silent|--show-error|--fail|--location|--insecure) shift ;; ")
            append("--connect-timeout|--max-time|--retry|--user-agent|-A|-H|--header|-X|--request|-d|--data|--data-raw|--data-binary) shift 2 ;; ")
            append("--*) shift ;; ")
            append("-*) shift ;; ")
            append("*) url=\"${'$'}1\"; shift ;; ")
            append("esac; done; ")
            append("if [ -n \"${'$'}outfile\" ]; then wget -qO \"${'$'}outfile\" \"${'$'}url\"; else wget -qO- \"${'$'}url\"; fi; ")
            append("}; fi; ")
        }
        return ensure + command
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
