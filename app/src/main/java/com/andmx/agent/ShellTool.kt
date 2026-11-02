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
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The agent's primary hand: run a shell command inside the proot Linux guest
 * and return its combined output. This is what turns AndMX from a chat app
 * into a workbench.
 *
 * Uses [PersistentShell] for 5-10x faster execution by keeping a long-running
 * shell process alive. Falls back to fork+exec per command if the persistent
 * shell is unavailable or for commands that need a fresh environment.
 */
private const val DEFAULT_TIMEOUT_MS = 120_000L
private const val MAX_TIMEOUT_MS = 600_000L

private val BASH_DESCRIPTION_FIELD_PROMPT = listOf(
    "Clear, concise description of what this command does in active voice. Never use words like \"complex\" or \"risk\" in the description - just describe what it does.",
    "",
    "For simple commands (git, npm, standard CLI tools), keep it brief (5-10 words):",
    "- ls → \"List files in current directory\"",
    "- git status → \"Show working tree status\"",
    "- npm install → \"Install package dependencies\"",
    "",
    "For commands that are harder to parse at a glance (piped commands, obscure flags, etc.), add enough context to clarify what it does:",
    "- find . -name \"*.tmp\" -exec rm {} \\; → \"Find and delete all .tmp files recursively\"",
    "- git reset --hard origin/main → \"Discard all local changes and match remote main\"",
    "- curl -s url | jq '.data[]' → \"Fetch JSON from URL and extract data array elements\"",
).joinToString("\n")

private val BASH_PROVIDER_DESCRIPTION = listOf(
    "Executes a bash command and returns its output.",
    "",
    "- Working directory persists between calls, but prefer absolute paths — `cd` in a compound command can trigger a permission prompt. Shell state (env vars, functions) does not persist; the shell is initialized from the user's profile.",
    "- IMPORTANT: Avoid using this tool to run `find`, `grep`, `cat`, `head`, `tail`, `sed`, `awk`, or `echo` commands, unless explicitly instructed or after you have verified that a dedicated tool cannot accomplish your task. Instead, use the appropriate dedicated tool as this will provide a much better experience for the user.",
    "- `timeout` is in milliseconds: default $DEFAULT_TIMEOUT_MS, max $MAX_TIMEOUT_MS.",
    "- `run_in_background` runs the command detached: it keeps running across turns and re-invokes you when it exits. No `&` needed.",
    "",
    "# Git",
    "- Interactive flags (`-i`, e.g. `git rebase -i`, `git add -i`) are not supported in this environment.",
    "- Use the `gh` CLI for GitHub operations (PRs, issues, API).",
    "- Commit or push only when the user asks. If on the default branch, branch first.",
).joinToString("\n")

private fun positiveTimeout(args: JsonObject, key: String): Long? {
    if (key !in args) return null
    val prim = args[key] as? JsonPrimitive ?: return null
    return prim.longOrNull?.takeIf { it > 0L }
        ?: prim.content.toDoubleOrNull()?.takeIf { it > 0.0 }?.toLong()
}

class ShellTool(
    private val context: Context,
    private val cwdProvider: () -> String = { WorkspaceAccess(context).guestCwd() },
    private val backgroundTasks: BackgroundTasks? = null,
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

    override val name = "Bash"
    override val description = BASH_PROVIDER_DESCRIPTION
    override val risk = ToolRisk.EXECUTE
    override val destructive = false
    override val timeoutMs: Long = DEFAULT_TIMEOUT_MS
    override val maxOutputBytes: Int = 10_000_000
    override val permissionName: String = "bash"
    override val sideEffectScope: String = "system"

    override fun resolveTimeoutMs(args: JsonObject): Long? {
        val requested = positiveTimeout(args, "timeout") ?: positiveTimeout(args, "timeout_ms")
        return (requested ?: timeoutMs).coerceAtMost(MAX_TIMEOUT_MS)
    }

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties") {
            putJsonObject("command") {
                put("type", "string")
                put("description", "The command to execute")
            }
            putJsonObject("timeout") {
                put("type", "number")
                put("description", "Optional timeout in milliseconds (max $MAX_TIMEOUT_MS)")
            }
            putJsonObject("description") {
                put("type", "string")
                put("description", BASH_DESCRIPTION_FIELD_PROMPT)
            }
            putJsonObject("run_in_background") {
                put("type", "boolean")
                put("description", "Set to true to run this command in the background.")
            }
            putJsonObject("dangerouslyDisableSandbox") {
                put("type", "boolean")
                put(
                    "description",
                    "Set this to true to dangerously override sandbox mode and run commands without sandboxing.",
                )
            }
        }
        putJsonArray("required") { add("command") }
    }

    override suspend fun execute(args: JsonObject): ToolResult = execute("", args)

    override suspend fun execute(callId: String, args: JsonObject): ToolResult {
        val command = args["command"]?.jsonPrimitive?.content
            ?: return ToolResult("command is required", isError = true)
        val requestedTimeout = positiveTimeout(args, "timeout") ?: positiveTimeout(args, "timeout_ms")
        if (("timeout" in args || "timeout_ms" in args) && requestedTimeout == null) {
            return ToolResult("timeout must be a positive number of milliseconds", isError = true)
        }
        val bounded = requestedTimeout != null || "max_output_chars" in args || "strict_cwd" in args
        val timeoutMs = (requestedTimeout ?: 600_000L).coerceAtMost(MAX_TIMEOUT_MS)
        val maxOutputChars = if ("max_output_chars" in args) {
            (args["max_output_chars"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..16_000 }
                ?: return ToolResult("max_output_chars must be between 1 and 16000", isError = true)
        } else 16_000
        val strictCwd = if ("strict_cwd" in args) {
            (args["strict_cwd"] as? JsonPrimitive)?.booleanOrNull
                ?: return ToolResult("strict_cwd must be a boolean", isError = true)
        } else false
        val prepared = if (bounded) command else ensureNetworkTools(command)

        val cwd = cwdProvider().ifBlank { access.guestCwd() }
        if (strictCwd && cwd.isBlank()) {
            return ToolResult("执行失败: 工作区未就绪,无法定位命令工作目录", isError = true)
        }
        val quotedCwd = "'${cwd.replace("'", "'\"'\"'")}'"
        val cdCommand = when {
            strictCwd -> "cd $quotedCwd || exit 125; $prepared"
            cwd.isNotBlank() -> "cd $quotedCwd 2>/dev/null || cd ~; $prepared"
            else -> prepared
        }

        val runBackground = (args["run_in_background"] as? JsonPrimitive)?.booleanOrNull == true
        if (runBackground) {
            val tasks = backgroundTasks
                ?: return ToolResult("执行失败: 后台任务不可用", isError = true)
            if (access.isRemote) {
                return ToolResult("执行失败: 远程工作区暂不支持后台执行", isError = true)
            }
            val task = tasks.startShell(cdCommand, cwd)
            return ToolResult(
                "Command started in background task_id=${task.id} output=${task.outputPath}",
            )
        }

        if (access.isRemote) {
            if (bounded) {
                return ToolResult("执行失败: 远程工作区不支持带超时的验证命令执行", isError = true)
            }
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
            return ToolResult(withPersistedOverflow(res.stdout, out, 16_000), isError = res.exitCode != 0)
        }

        val noSandbox = (args["dangerouslyDisableSandbox"] as? JsonPrimitive)?.booleanOrNull == true

        if (bounded) {
            return executeBounded(cdCommand, timeoutMs, maxOutputChars, noSandbox)
        }

        if (callId.isNotBlank()) {
            return executeBound(callId, command, cwd, cdCommand, noSandbox)
        }

        if (usePersistent && !noSandbox && persistentShell.isAlive) {
            val res = persistentShell.exec(cdCommand)
            if (res.error == null) {
                val out = buildString {
                    append(res.stdout.ifBlank { "(无输出)" })
                    append("\n[exit=${res.exitCode}]")
                }
                return withImageOutput(
                    ToolResult(withPersistedOverflow(res.stdout, out, 16_000), isError = res.exitCode != 0),
                )
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
        return withImageOutput(
            ToolResult(withPersistedOverflow(res.stdout, out, 16_000), isError = res.exitCode != 0),
        )
    }

    /** 上游 persistOutput=on_truncate 等价：超限时完整 stdout 落 .andmx/outputs/ 并把路径回给模型。 */
    private suspend fun withPersistedOverflow(rawStdout: String, rendered: String, limit: Int): String {
        if (rendered.length <= limit) return rendered
        val guest = "${cwdProvider().trimEnd('/')}/.andmx/outputs/shell-${System.currentTimeMillis()}.log"
        val saved = runCatching {
            access.writeText(guest, rawStdout)
            guest
        }.getOrNull()
        val marker = "\n…[截断]${saved?.let { " 完整输出: $it" }.orEmpty()}"
        return rendered.take((limit - marker.length).coerceAtLeast(0)) + marker
    }

    private suspend fun executeBounded(
        cdCommand: String,
        timeoutMs: Long,
        maxOutputChars: Int,
        noSandbox: Boolean = false,
    ): ToolResult {
        val install = runtime.install()
        if (!install.ok) {
            return ToolResult("执行失败: ${install.message}", isError = true)
        }
        val rootfs = runtime.rootfsDir.takeIf { it.exists() && !noSandbox }
        val sh = if (rootfs != null) "/bin/sh" else "/system/bin/sh"
        val argv = if (noSandbox) {
            listOf(sh, "-lc", cdCommand)
        } else {
            runtime.prootArgv(listOf(sh, "-lc", cdCommand), rootfs = rootfs)
        }

        return withContext(Dispatchers.IO) {
            val process = runCatching {
                ProcessBuilder(argv)
                    .directory(File(context.filesDir.path))
                    .apply {
                        environment().clear()
                        environment().putAll(com.andmx.exec.policy.EnvScrubber.scrub(runtime.env()))
                        redirectErrorStream(true)
                    }
                    .start()
            }.getOrElse {
                return@withContext ToolResult("执行失败: ${it.message}", isError = true)
            }
            val output = StringBuilder()
            val buffer = ByteArray(8_192)
            var timedOut = false
            var truncated = false
            var overflowWriter: java.io.BufferedWriter? = null
            var overflowGuestPath: String? = null
            val started = System.nanoTime()
            val reader = process.inputStream
            try {
                process.outputStream.close()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if ((System.nanoTime() - started) / 1_000_000 >= timeoutMs) {
                        timedOut = true
                        break
                    }
                    val available = reader.available()
                    if (available > 0) {
                        val n = reader.read(buffer, 0, minOf(available, buffer.size))
                        if (n <= 0) break
                        val chunk = String(buffer, 0, n, StandardCharsets.UTF_8)
                        val remaining = maxOutputChars - output.length
                        output.append(chunk.take(remaining))
                        if (chunk.length > remaining) {
                            if (!truncated) {
                                truncated = true
                                // 上游 bash persistOutput=on_truncate 等价：截断后完整输出落盘，
                                // 路径回给模型（本地工作区才落；远端无 host fd 保持纯截断）。
                                val guest = "${cwdProvider().trimEnd('/')}/.andmx/outputs/" +
                                    "shell-${System.currentTimeMillis()}.log"
                                overflowGuestPath = guest
                                overflowWriter = runCatching {
                                    access.hostFile(guest)?.also { f ->
                                        f.parentFile?.mkdirs()
                                    }?.bufferedWriter(StandardCharsets.UTF_8)
                                }.getOrNull()
                                overflowWriter?.write(output.toString())
                            }
                            runCatching { overflowWriter?.write(chunk) }
                        }
                    } else if (!process.isAlive) {
                        break
                    } else {
                        delay(25)
                    }
                }
                runCatching { overflowWriter?.close() }
                val exitCode = if (timedOut) 124 else process.exitValue()
                val suffix = if (timedOut) "\n(超时,已终止)\n[exit=124]" else "\n[exit=$exitCode]"
                val text = output.toString().replace("\r", "").trimEnd().ifBlank { "(无输出)" }
                val persisted = overflowGuestPath?.let { " 完整输出: $it" }.orEmpty()
                val marker = "\n…[截断]$persisted"
                val rendered = if (truncated || text.length + suffix.length > maxOutputChars) {
                    text.take((maxOutputChars - marker.length - suffix.length).coerceAtLeast(0)) + marker + suffix
                } else text + suffix
                ToolResult(rendered.takeLast(maxOutputChars), isError = timedOut || exitCode != 0)
                    .let { withImageOutput(it) }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                ToolResult("执行失败: ${e.message}".take(maxOutputChars), isError = true)
            } finally {
                runCatching { process.destroy() }
                runCatching { if (process.isAlive) process.destroyForcibly() }
                runCatching { reader.close() }
                runCatching { process.errorStream.close() }
                runCatching { process.outputStream.close() }
            }
        }
    }

    private suspend fun executeBound(
        callId: String,
        command: String,
        cwd: String,
        cdCommand: String,
        noSandbox: Boolean = false,
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

        val sh = if (runtime.rootfsDir.exists() && !noSandbox) "/bin/sh" else "/system/bin/sh"
        val argv = if (noSandbox) {
            listOf(sh, "-lc", cdCommand)
        } else {
            runtime.prootArgv(listOf(sh, "-lc", cdCommand), rootfs = runtime.rootfsDir.takeIf { it.exists() })
        }
        val envp = runtime.env().map { "${it.key}=${it.value}" }.toTypedArray()
        val process = runCatching {
            PtyProcess.start(
                command = if (noSandbox) sh else runtime.prootBin.path,
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

    /**
     * 上游 bash-image-output：stdout 里的 data:image/... 提取为 imageUrls
     * 返回给模型，文本里留占位行。
     */
    private fun withImageOutput(result: ToolResult): ToolResult {
        val m = IMAGE_DATA_URL.find(result.output) ?: return result
        val dataUrl = m.value
        if (dataUrl.length > IMAGE_DATA_URL_MAX_CHARS) return result
        val cleaned = result.output.replace(m.value, "[image output]")
        return result.copy(output = cleaned, imageUrls = listOf(dataUrl))
    }

    companion object {
        private val IMAGE_DATA_URL =
            Regex("data:image/(?:png|jpe?g|gif|webp|bmp);base64,[A-Za-z0-9+/=\\r\\n]+")
        private const val IMAGE_DATA_URL_MAX_CHARS = 6 * 1024 * 1024
    }
}
