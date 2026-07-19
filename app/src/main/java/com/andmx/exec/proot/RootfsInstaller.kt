package com.andmx.exec.proot

import com.andmx.exec.LocalProcessEnvironment
import com.andmx.exec.ProcessSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and unpacks a guest Linux rootfs into the proot tree.
 *
 * Extraction runs the host's toybox `tar` *inside proot fake-root* so that
 * ownership, symlinks and permissions from the tarball are applied without
 * real root. This is the standard no-root distro install technique.
 */
class RootfsInstaller(
    private val runtime: ProotRuntime,
    private val host: LocalProcessEnvironment = LocalProcessEnvironment(),
) {
    private val sentinel: File get() = File(runtime.rootfsDir, ".andmx-installed")

    fun isInstalled(): Boolean = sentinel.exists()

    suspend fun install(
        url: String = DEFAULT_ALPINE_URL,
        force: Boolean = false,
        onLog: (String) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        if (isInstalled() && !force) {
            onLog("rootfs 已安装: ${runtime.rootfsDir.path}")
            return@withContext true
        }
        // proot must be ready before we can extract with fake-root.
        if (!runtime.install().ok) {
            onLog("✗ proot 引导未就绪")
            return@withContext false
        }

        // cache must be in the writable filesDir, NOT under usrDir (which is now
        // nativeLibraryDir — a read-only system path).
        val cache = File(runtime.rootfsDir.parentFile, "cache").apply { mkdirs() }
        val tarball = File(cache, "rootfs.tar.gz")

        // 1) download
        onLog("↓ 下载 rootfs: $url")
        val downloaded = runCatching { download(url, tarball, onLog) }.getOrElse {
            onLog("✗ 下载失败: ${it.message}"); false
        }
        if (!downloaded) return@withContext false
        onLog("  完成 ${tarball.length() / 1024} KB")

        // 2) fresh target dir
        if (runtime.rootfsDir.exists()) runtime.rootfsDir.deleteRecursively()
        runtime.rootfsDir.mkdirs()

        // 3) extract via proot fake-root + host tar
        onLog("⤷ 解压到 ${runtime.rootfsDir.path}")
        val argv = runtime.prootArgv(
            command = listOf("/system/bin/tar", "-x", "-z", "-f", tarball.path, "-C", runtime.rootfsDir.path),
            rootfs = null, // extract while still in host mode
        )
        val res = host.execute(ProcessSpec(argv = argv, env = runtime.env(), redirectErrorStream = true))
        if (res.exitCode != 0) {
            onLog("✗ 解压 exit=${res.exitCode}")
            res.stdout.lineSequence().take(8).forEach { if (it.isNotBlank()) onLog("  $it") }
            return@withContext false
        }

        // 4) basic resolv.conf for later network use
        runCatching {
            File(runtime.rootfsDir, "etc").mkdirs()
            File(runtime.rootfsDir, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        }

        sentinel.writeText(System.currentTimeMillis().toString())
        tarball.delete()
        runCatching {
            val bootstrap = runtime.prootArgv(
                command = listOf(
                    "/bin/sh", "-lc",
                    "command -v apk >/dev/null 2>&1 && apk add --no-cache curl wget ca-certificates >/dev/null 2>&1 || true",
                ),
                rootfs = runtime.rootfsDir,
            )
            host.execute(ProcessSpec(argv = bootstrap, env = runtime.env(), redirectErrorStream = true))
        }
        onLog("✓ rootfs 安装完成")
        true
    }

    private fun download(url: String, dst: File, onLog: (String) -> Unit): Boolean {
        var current = url
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = false
            }
            when (conn.responseCode) {
                in 300..399 -> {
                    current = conn.getHeaderField("Location") ?: return false
                    conn.disconnect()
                }
                200 -> {
                    conn.inputStream.use { input -> dst.outputStream().use { input.copyTo(it) } }
                    conn.disconnect()
                    return true
                }
                else -> {
                    onLog("  HTTP ${conn.responseCode}")
                    conn.disconnect(); return false
                }
            }
        }
        return false
    }

    companion object {
        const val DEFAULT_ALPINE_URL =
            "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.9-aarch64.tar.gz"
    }
}
