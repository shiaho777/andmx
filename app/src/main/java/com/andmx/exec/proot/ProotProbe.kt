package com.andmx.exec.proot

import android.content.Context
import com.andmx.exec.ProcessResult
import com.andmx.exec.ProcessSpec

/**
 * Validates the proot machinery end to end: install the bootstrap, print
 * proot's version, then run a shell *inside* proot against the host rootfs.
 * Success here means ptrace-based syscall virtualisation works no-root.
 */
class ProotProbe(private val context: Context) {

    private val runtime = ProotRuntime(context)
    private val env = LocalProotEnvironment(context, runtime)

    suspend fun run(): String {
        val sb = StringBuilder()
        sb.appendLine("# proot 运行时探针")

        if (!runtime.isBundled()) {
            sb.appendLine("✗ 当前变体未捆绑 proot(需 proot 变体)。跳过。")
            return sb.toString().trimEnd()
        }

        val install = runtime.install(force = true)
        sb.appendLine(install.message)
        sb.appendLine()
        if (!install.ok) return sb.toString().trimEnd()

        // proot -V (run directly via host, not virtualised)
        sb.appendLine("[1] proot 版本")
        val ver = com.andmx.exec.LocalProcessEnvironment().execute(
            ProcessSpec(
                argv = listOf(runtime.prootBin.path, "--version"),
                env = runtime.env(),
                redirectErrorStream = true,
            ),
        )
        appendResult(sb, ver)
        sb.appendLine()

        // shell inside proot
        sb.appendLine("[2] proot 内执行 shell (rootfs=/)")
        val r = env.shell("echo PROOT_OK; uname -a; id; pwd")
        appendResult(sb, r)
        sb.appendLine()

        sb.appendLine("# 结论")
        val ok = r.stdout.contains("PROOT_OK")
        sb.appendLine("proot ptrace 虚拟化: ${if (ok) "✓ 工作正常" else "✗ 失败"}")
        if (!ok) return finish(sb)

        // [3] install a real guest distro and run a shell inside it
        sb.appendLine()
        sb.appendLine("[3] 安装 Alpine rootfs 并在其内执行")
        val installer = RootfsInstaller(runtime)
        val installed = installer.install { line -> sb.appendLine("    $line") }
        sb.appendLine()
        if (installed) {
            val guest = env.shell("cat /etc/os-release | head -2; echo '---'; uname -m; busybox | head -1; pwd")
            appendResult(sb, guest)
            sb.appendLine()
            val distroOk = guest.stdout.contains("Alpine", ignoreCase = true)
            sb.appendLine("guest 发行版 shell: ${if (distroOk) "✓ Alpine 运行中" else "✗ 失败"}")
            if (distroOk) sb.appendLine("→ 真实 Linux 用户态就绪:可装 git/python/node。")
        }

        return finish(sb)
    }

    private fun finish(sb: StringBuilder): String =
        sb.toString().trimEnd().also { android.util.Log.i("AndmxProot", "\n$it") }

    private fun appendResult(sb: StringBuilder, r: ProcessResult) {
        if (r.error != null) {
            sb.appendLine("    ✗ ${r.error}")
            return
        }
        sb.appendLine("    exit=${r.exitCode} (${r.durationMs}ms)")
        r.stdout.lineSequence().filter { it.isNotBlank() }.forEach { sb.appendLine("    | $it") }
    }
}
