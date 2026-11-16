package com.andmx.exec.proot

import com.andmx.exec.LocalProcessEnvironment
import com.andmx.exec.ProcessSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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
        if (!runtime.install().ok) {
            onLog("✗ proot 引导未就绪")
            return@withContext false
        }

        val cache = File(runtime.rootfsDir.parentFile, "cache").apply { mkdirs() }
        val tarball = File(cache, "rootfs.tar.gz")

        onLog("↓ 下载 rootfs: $url")
        val downloaded = downloadWithRetry(url, tarball, onLog)
        if (!downloaded) return@withContext false
        onLog("  完成 ${tarball.length() / 1024} KB")

        val expected = expectedSha256(url)
        if (expected != null) {
            val actual = sha256(tarball)
            if (!actual.equals(expected, ignoreCase = true)) {
                onLog("✗ 校验和不匹配，丢弃下载文件")
                tarball.delete()
                return@withContext false
            }
            onLog("  SHA256 校验通过")
        } else {
            onLog("  ⚠ 未知发行版 URL，跳过校验和验证")
        }

        val staging = File(runtime.rootfsDir.parentFile, "rootfs.staging")
        val backup = File(runtime.rootfsDir.parentFile, "rootfs.old")
        staging.deleteRecursively()
        staging.mkdirs()

        onLog("⤷ 解压到暂存目录")
        val argv = runtime.prootArgv(
            command = listOf("/system/bin/tar", "-x", "-z", "-f", tarball.path, "-C", staging.path),
            rootfs = null,
        )
        val res = host.execute(ProcessSpec(argv = argv, env = runtime.env(), redirectErrorStream = true))
        if (res.exitCode != 0) {
            onLog("✗ 解压 exit=${res.exitCode}，保留现有环境")
            res.stdout.lineSequence().take(8).forEach { if (it.isNotBlank()) onLog("  $it") }
            staging.deleteRecursively()
            return@withContext false
        }

        runCatching {
            File(staging, "etc").mkdirs()
            File(staging, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        }
        File(staging, ".andmx-installed").writeText(System.currentTimeMillis().toString())

        backup.deleteRecursively()
        if (runtime.rootfsDir.exists() && !runtime.rootfsDir.renameTo(backup)) {
            onLog("✗ 旧环境移开失败，保留现有环境")
            staging.deleteRecursively()
            return@withContext false
        }
        if (!staging.renameTo(runtime.rootfsDir)) {
            onLog("⤷ 原子替换不可用，回退复制")
            if (!staging.copyRecursively(runtime.rootfsDir, overwrite = true)) {
                onLog("✗ 复制失败，恢复旧环境")
                backup.renameTo(runtime.rootfsDir)
                staging.deleteRecursively()
                return@withContext false
            }
            staging.deleteRecursively()
        }
        backup.deleteRecursively()

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

    private fun downloadWithRetry(url: String, dst: File, onLog: (String) -> Unit): Boolean {
        repeat(3) { attempt ->
            val ok = runCatching { download(url, dst, onLog) }.getOrElse {
                onLog("  下载异常: ${it.message}"); false
            }
            if (ok && dst.length() > 0) return true
            dst.delete()
            if (attempt < 2) {
                onLog("  重试 ${attempt + 2}/3")
                Thread.sleep(1000L * (attempt + 1))
            }
        }
        onLog("✗ 下载失败（3 次尝试）")
        return false
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

        const val DEFAULT_ALPINE_SHA256 =
            "7e8b4adbf33b1363b90b50e614bbe7bf3d2c8863f9f1718cbc65a883b27c2f43"

        fun expectedSha256(url: String): String? =
            if (url == DEFAULT_ALPINE_URL) DEFAULT_ALPINE_SHA256 else null

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}