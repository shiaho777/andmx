package com.andmx.term

import android.content.Context
import com.andmx.exec.proot.ProotRuntime
import com.andmx.exec.proot.RootfsInstaller
import com.andmx.exec.pty.PtyProcess
import com.andmx.workspace.ProjectManager
import com.andmx.workspace.RemoteWorkspaceStore
import com.andmx.workspace.WorkspaceKind
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
            val projectManager = ProjectManager(context)
            if (projectManager.workspaceKind.value == WorkspaceKind.REMOTE) {
                startRemoteSsh(projectManager)
                return@launch
            }

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


    private suspend fun startRemoteSsh(projectManager: ProjectManager) {
        emit(status = "准备 SSH 远程终端…")
        val spec = projectManager.currentRemoteSpec()
        if (spec == null) {
            emit(status = "✗ 未找到远程工作区配置")
            return
        }
        val store = RemoteWorkspaceStore(context)
        if (store.findSshBinary() == null) {
            emit(status = "✗ 当前环境没有可用的 ssh 客户端")
            return
        }
        val remotePath = spec.remotePath.ifBlank { "~" }
        val remoteCmd = "cd " + shellQuote(remotePath) + " 2>/dev/null || cd ~; exec " + 36.toChar() + "SHELL -l"
        val argvList = try {
            store.buildSshArgv(spec, remoteCmd, batchMode = false).toMutableList()
        } catch (t: Throwable) {
            emit(status = "✗ ${t.message}")
            return
        }
        val sshIndex = argvList.indexOfFirst { it == "ssh" || it.endsWith("/ssh") }
        if (sshIndex >= 0 && "-tt" !in argvList) {
            argvList.add(sshIndex + 1, "-tt")
        }
        val banner = buildString {
            append(27.toChar())
            append("[36mSSH ")
            append(spec.username)
            append('@')
            append(spec.host)
            append(':')
            append(spec.port)
            append("  ")
            append(remotePath)
            append(27.toChar())
            append("[0m")
            append("\r\n")
        }
        feed(banner)
        val p = try {
            PtyProcess.start(
                command = argvList.first(),
                argv = argvList.toTypedArray(),
                envp = emptyArray(),
                cwd = context.filesDir.path,
                rows = rows,
                cols = cols,
            )
        } catch (t: Throwable) {
            emit(status = "✗ SSH 启动失败: ${t.message}")
            return
        }
        process = p
        emit(running = true, status = "远程 SSH · pid=${p.pid}")
        readLoop(p)
    }

    private fun shellQuote(value: String): String {
        val q = 39.toChar()
        return buildString {
            append(q)
            value.forEach { ch ->
                if (ch == q) {
                    append(q)
                    append(92.toChar())
                    append(q)
                    append(q)
                } else {
                    append(ch)
                }
            }
            append(q)
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
