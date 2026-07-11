package com.andmx.term

import android.content.Context
import com.andmx.exec.proot.ProotRuntime
import com.andmx.exec.proot.RootfsInstaller
import com.andmx.exec.pty.PtyProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

/**
 * An interactive shell session running inside the proot guest, surfaced to the
 * UI as a stream of terminal output. Output bytes feed an [AnsiTerminalBuffer];
 * keystrokes are written straight to the PTY master.
 */
class TerminalSession(
    private val context: Context,
    private val runtime: ProotRuntime = ProotRuntime(context),
) {
    val sessionId: String = java.util.UUID.randomUUID().toString()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val buffer = TerminalEmulator(rows = 24, cols = 80)
    private var process: PtyProcess? = null

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state

    var rows = 24
        private set
    var cols = 80
        private set

    data class SessionState(
        val revision: Long = 0,
        val running: Boolean = false,
        val exitCode: Int? = null,
        val status: String = "",
        val screenText: String = "",
        val coloredLines: List<List<Pair<String, Int>>> = emptyList(),
    )

    val screen: String get() = _state.value.screenText

    fun start() {
        if (process != null) return
        scope.launch {
            emit(status = "准备 proot…")
            if (!runtime.install().ok) { emit(status = "✗ proot 引导失败"); return@launch }

            // Ensure a guest distro exists; install on first run.
            val installer = RootfsInstaller(runtime)
            if (!installer.isInstalled()) {
                emit(status = "首次运行:安装 Alpine rootfs…")
                feed("\u001b[36m正在下载并安装 Alpine rootfs,请稍候…\u001b[0m\r\n")
                if (!installer.install { feed("$it\r\n") }) {
                    emit(status = "✗ rootfs 安装失败"); return@launch
                }
            }

            val sh = if (runtime.rootfsDir.exists()) "/bin/sh" else "/system/bin/sh"
            val argv = runtime.prootArgv(listOf(sh, "-l"), rootfs = runtime.rootfsDir.takeIf { it.exists() })
            val envp = (runtime.env() + mapOf("PS1" to "\\w \\$ ")).map { "${it.key}=${it.value}" }.toTypedArray()

            val p = try {
                PtyProcess.start(
                    command = runtime.prootBin.path,
                    argv = argv.toTypedArray(),
                    envp = envp,
                    // cwd is the HOST-side working dir for proot itself — must be
                    // a writable path (filesDir), NOT nativeLibraryDir (read-only).
                    // proot's -w flag sets the guest-side cwd separately.
                    cwd = context.filesDir.path,
                    rows = rows,
                    cols = cols,
                )
            } catch (t: Throwable) {
                emit(status = "✗ 启动失败: ${t.message}"); return@launch
            }
            process = p
            emit(running = true, status = "已连接 · pid=${p.pid}")
            readLoop(p)
        }
    }

    private suspend fun readLoop(p: PtyProcess) = withContext(Dispatchers.IO) {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = p.input.read(buf)
                if (n <= 0) break
                feed(String(buf, 0, n, StandardCharsets.UTF_8))
            }
        } catch (_: Throwable) {
        }
        val code = runCatching { p.waitFor() }.getOrDefault(-1)
        emit(running = false, exitCode = code, status = "会话结束 (exit=$code)")
    }

    fun write(text: String) {
        val p = process ?: return
        scope.launch {
            runCatching { p.output.write(text.toByteArray(StandardCharsets.UTF_8)); p.output.flush() }
        }
    }

    fun resize(rows: Int, cols: Int) {
        this.rows = rows; this.cols = cols
        buffer.resize(rows, cols)
        process?.resize(rows, cols)
    }

    private fun feed(s: String) {
        buffer.feed(s)
        _state.value = _state.value.copy(
            revision = buffer.revision,
            screenText = buffer.render(),
            coloredLines = buffer.renderColored()
        )
    }

    private fun emit(running: Boolean = _state.value.running, exitCode: Int? = _state.value.exitCode, status: String) {
        _state.value = _state.value.copy(
            running = running, exitCode = exitCode, status = status,
            revision = buffer.revision, screenText = buffer.render(),
            coloredLines = buffer.renderColored()
        )
    }

    fun destroy() {
        process?.destroy()
        process = null
        scope.cancel()
    }
}
