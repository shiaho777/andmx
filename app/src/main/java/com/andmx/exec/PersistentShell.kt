package com.andmx.exec

import android.content.Context
import android.util.Log
import com.andmx.exec.proot.LocalProotEnvironment
import com.andmx.exec.proot.ProotRuntime
import com.andmx.exec.pty.PtyProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * A persistent shell session that keeps a single long-running shell process
 * alive inside the proot guest, sending commands and reading results through
 * the PTY. This avoids the 50-200ms fork+exec+proot overhead per command.
 *
 * Design (mirrors Codex's shell_snapshot / zsh-exec-bridge concept):
 * 1. Start a shell (/bin/sh) inside proot via PTY
 * 2. Set a unique prompt marker so we can detect command completion
 * 3. For each command: send "command; echo __EXIT_<uuid>_$?" and read until marker
 * 4. Parse exit code from the marker line
 *
 * Benefits:
 * - 5-10x faster than fork+exec per command
 * - Shell state persists (cd, env vars, functions, aliases)
 * - Natural integration point for execpolicy (intercept before sending)
 *
 * Limitations:
 * - Interactive commands (vi, top) won't work — use TerminalSession for those
 * - Output is captured as raw bytes (ANSI codes included)
 */
class PersistentShell(
    private val context: Context,
    private val runtime: ProotRuntime = ProotRuntime(context),
    private val rows: Int = 24,
    private val cols: Int = 80,
) {
    companion object {
        private const val TAG = "PersistentShell"
        private const val READ_TIMEOUT_MS = 30_000L
        private const val READ_INTERVAL_MS = 20L
        private const val MAX_OUTPUT = 64_000

        // Matches ANSI escape sequences: ESC [ ... letter, ESC ] ... BEL, ESC ( B, etc.
        private val ANSI_ESCAPE_REGEX = Regex(
            "\\u001B\\[[0-9;]*[a-zA-Z]" +
                "|\\u001B\\][^\\u0007]*\\u0007" +
                "|\\u001B\\[[0-9;]*[mKHF]" +
                "|\\u001B[=>]" +
                "|\\u001B\\([AB0]" +
                "|\\r"
        )
    }

    private var pty: PtyProcess? = null
    private val markerId: String = UUID.randomUUID().toString().replace("-", "").take(16)
    private val exitMarker = "__ANDMX_EXIT_${markerId}_"
    private val promptMarker = "__ANDMX_PROMPT_${markerId}"
    private var initialized = false
    private var dead = false

    /**
     * Start the persistent shell. Idempotent — safe to call multiple times.
     */
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (initialized && pty?.let { true } == true) return@withContext true
        if (dead) return@withContext false

        val install = runtime.install()
        if (!install.ok) {
            Log.e(TAG, "proot install failed: ${install.message}")
            return@withContext false
        }

        val sh = if (runtime.rootfsDir.exists()) "/bin/sh" else "/system/bin/sh"
        val prootArgv = runtime.prootArgv(listOf(sh), rootfs = runtime.rootfsDir.takeIf { it.exists() })
        val env = runtime.env()

        try {
            pty = PtyProcess.start(
                command = prootArgv.first(),
                argv = prootArgv.toTypedArray(),
                envp = env.map { "${it.key}=${it.value}" }.toTypedArray(),
                cwd = null,
                rows = rows,
                cols = cols,
            )
            // Set a minimal prompt so we can detect readiness
            val p = pty!!
            p.output.write("export PS1='$promptMarker'\n".toByteArray())
            p.output.flush()
            // Wait for the first prompt marker
            readUntilMarker(promptMarker, timeoutMs = 10_000)
            // Clear the screen of marker artifacts
            p.output.write("clear\n".toByteArray())
            p.output.flush()
            readUntilMarker(promptMarker, timeoutMs = 3_000)
            initialized = true
            Log.i(TAG, "PersistentShell started (pid=${p.pid})")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start persistent shell", t)
            dead = true
            false
        }
    }

    /**
     * Execute a command in the persistent shell.
     * Returns the combined stdout+stderr and exit code.
     */
    suspend fun exec(command: String, timeoutMs: Long = READ_TIMEOUT_MS): ProcessResult = withContext(Dispatchers.IO) {
        if (!initialized || dead) {
            val ok = start()
            if (!ok) return@withContext ProcessResult(-1, "", "", 0, error = "Shell 未启动")
        }

        val p = pty ?: return@withContext ProcessResult(-1, "", "", 0, error = "Shell 不可用")

        try {
            // Send the command followed by an exit-code marker
            val cmd = "$command\necho '$exitMarker'$?\n"
            p.output.write(cmd.toByteArray())
            p.output.flush()

            // Read until we see the exit marker
            val rawOutput = readUntilMarker(exitMarker, timeoutMs = timeoutMs)

            // Parse exit code from the marker line
            val exitCode = parseExitCode(rawOutput)
            // Strip the marker and command echo from output
            val cleanOutput = cleanOutput(rawOutput, command)

            ProcessResult(
                exitCode = exitCode,
                stdout = cleanOutput.take(MAX_OUTPUT),
                stderr = "",
                durationMs = 0,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Command failed, shell may be dead: ${t.message}")
            dead = true
            ProcessResult(-1, "", t.message ?: "执行失败", 0, error = t.message)
        }
    }

    /** Check if the shell is alive and ready. */
    val isAlive: Boolean get() = initialized && !dead && pty != null

    /** Destroy the shell session. */
    fun destroy() {
        pty?.destroy()
        pty = null
        initialized = false
        dead = true
    }

    // ── Internal ──────────────────────────────────────

    private fun readUntilMarker(marker: String, timeoutMs: Long): String {
        val p = pty ?: return ""
        val buffer = ByteArrayOutputStream()
        val startTime = System.currentTimeMillis()
        val markerBytes = marker.toByteArray()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val available = p.input.available()
            if (available > 0) {
                val b = p.input.read()
                if (b >= 0) buffer.write(b)

                // Check if marker appeared in the last bytes
                val data = buffer.toByteArray()
                if (containsMarker(data, markerBytes)) {
                    return String(data)
                }
            } else {
                try {
                    Thread.sleep(READ_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        // Timeout — return what we have
        return String(buffer.toByteArray())
    }

    private fun containsMarker(data: ByteArray, marker: ByteArray): Boolean {
        if (data.size < marker.size) return false
        for (i in 0..data.size - marker.size) {
            var match = true
            for (j in marker.indices) {
                if (data[i + j] != marker[j]) { match = false; break }
            }
            if (match) return true
        }
        return false
    }

    private fun parseExitCode(output: String): Int {
        // Look for exitMarker followed by digits
        val regex = Regex("${Regex.escape(exitMarker)}(\\d+)")
        val match = regex.find(output)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    private fun cleanOutput(raw: String, command: String): String {
        var output = raw
        // Strip ANSI escape sequences (colors, cursor movement, etc.)
        output = ANSI_ESCAPE_REGEX.replace(output, "")
        // Remove exit marker line
        output = output.replace(Regex("${Regex.escape(exitMarker)}\\d+\n?"), "")
        // Remove the prompt marker if present
        output = output.replace(promptMarker, "")
        // Remove the echoed echo marker command
        output = output.replace(Regex("echo '$exitMarker'\\$?\\d*\n?"), "")
        // Remove command echo (the shell echoes what we type)
        val lines = output.split("\n").toMutableList()
        val cmdFirstToken = command.trim().split(Regex("\\s+")).firstOrNull() ?: ""
        // Remove lines that are just the echoed command
        while (lines.isNotEmpty()) {
            val first = lines.first().trim()
            if (first.isEmpty()) { lines.removeAt(0); continue }
            // The echoed command + our echo marker line
            if (first.startsWith(cmdFirstToken) && first.length < command.length + 30) {
                lines.removeAt(0)
                continue
            }
            break
        }
        // Remove trailing empty lines
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        return lines.joinToString("\n").trim()
    }
}
