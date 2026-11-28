package com.andmx.workspace

import android.content.Context
import com.andmx.exec.ExecutionEnvironment
import com.andmx.exec.ProcessSpec
import com.andmx.exec.ProcessResult
import com.andmx.exec.files.GuestFs
import com.andmx.exec.alpine.AlpineEnvironment
import com.andmx.exec.remote.RemoteFsClient
import com.andmx.exec.remote.RemoteSshEnvironment
import java.io.File

class WorkspaceAccess(context: Context) {
    private val appContext = context.applicationContext
    private val projectManager = ProjectManager(appContext)
    private val localFs = GuestFs(appContext)

    val isRemote: Boolean get() = LocalWorktree.current() == null && projectManager.isRemote

    val projectName: String get() = projectManager.projectName

    val hostPath: String? get() = LocalWorktree.current()?.hostDirectory(appContext)?.path ?: projectManager.hostPath.value

    fun guestCwd(): String {
        LocalWorktree.current()?.let { return it.path }
        if (isRemote) {
            return projectManager.currentRemoteSpec()?.remotePath?.ifBlank { "~" } ?: "~"
        }
        return if (projectManager.hasProject) projectManager.guestMountPath else "/root"
    }

    fun displayCwd(): String {
        return if (isRemote) {
            projectManager.currentRemoteSpec()?.workspaceUri ?: hostPath.orEmpty()
        } else {
            hostPath ?: guestCwd()
        }
    }

    fun environment(timeoutMs: Long? = null): ExecutionEnvironment {
        if (isRemote) {
            val spec = projectManager.currentRemoteSpec()
            if (spec != null) {
                return if (timeoutMs != null) {
                    RemoteSshEnvironment(appContext, spec, guestCwd(), timeoutMs)
                } else {
                    RemoteSshEnvironment(appContext, spec, guestCwd())
                }
            }
        }
        return AlpineEnvironment(appContext, timeoutMs ?: 120_000)
    }

    fun shellBinary(): String = "/bin/sh"

    fun resolvePath(path: String): String {
        val raw = path.trim().ifBlank { guestCwd() }
        if (raw.startsWith("/") || raw == "~" || raw.startsWith("~/")) {
            return if (raw.startsWith("~/")) {
                val home = if (isRemote) {
                    projectManager.currentRemoteSpec()?.remotePath?.let {
                        // best-effort: keep as $HOME-relative string for remote
                        raw
                    } ?: raw
                } else {
                    raw.replaceFirst("~", "/root")
                }
                home
            } else raw
        }
        val base = guestCwd().trimEnd('/')
        return "$base/${raw.trimStart('/')}"
    }

    suspend fun executeShell(
        command: String,
        cwd: String? = null,
        timeoutMs: Long? = null,
        stdin: String? = null,
    ): ProcessResult {
        val env = environment(timeoutMs)
        val work = cwd?.takeIf { it.isNotBlank() } ?: guestCwd()
        val sh = shellBinary()
        val script = if (LocalWorktree.current() != null) "cd ${q(work)} || exit 125; $command"
        else "cd ${q(work)} 2>/dev/null || cd ~ 2>/dev/null || true; $command"
        return env.execute(
            ProcessSpec(
                argv = listOf(sh, "-lc", script),
                workingDir = if (isRemote) work else "/root",
                stdin = stdin,
                redirectErrorStream = true,
            ),
        )
    }

    suspend fun exists(path: String): Boolean = checkPath(path, "-e", File::exists)

    suspend fun isDirectory(path: String): Boolean = checkPath(path, "-d", File::isDirectory)

    private suspend fun checkPath(path: String, test: String, check: (File) -> Boolean): Boolean {
        val resolved = resolvePath(path)
        val file = hostFile(resolved)
        return if (file != null) check(file) else executeShell("test $test ${q(resolved)}").ok
    }

    suspend fun statMtime(path: String): Long? =
        stat(path, "%Y", File::lastModified)?.takeIf { it > 0 }

    suspend fun statSize(path: String): Long? = stat(path, "%s", File::length)

    private suspend fun stat(path: String, format: String, value: (File) -> Long): Long? {
        val resolved = resolvePath(path)
        val file = hostFile(resolved)
        if (file != null) return value(file).takeIf { file.exists() }
        val result = executeShell("stat -c $format ${q(resolved)}")
        val raw = result.stdout.trim().toLongOrNull()?.takeIf { result.ok && it >= 0 } ?: return null
        return if (format == "%Y") raw * 1000 else raw
    }

    fun hostFile(path: String): File? =
        if (isRemote) null else resolveLocal(resolvePath(path))

    suspend fun readBytes(path: String, maxBytes: Int = 8 * 1024 * 1024): ByteArray? {
        val resolved = resolvePath(path)
        val file = hostFile(resolved)
        if (file != null) return runCatching {
            if (file.isFile && file.length() <= maxBytes) file.readBytes().takeIf { it.size <= maxBytes } else null
        }.getOrNull()
        val result = executeShell(
            "test -f ${q(resolved)} && head -c ${maxBytes.toLong() + 1} ${q(resolved)} | base64 | tr -d '\n'",
        )
        if (!result.ok) return null
        return runCatching { android.util.Base64.decode(result.stdout.trim(), android.util.Base64.DEFAULT) }
            .getOrNull()?.takeIf { it.size <= maxBytes }
    }

    suspend fun fetchRemoteFile(path: String, maxBytes: Int = REMOTE_FETCH_MAX_BYTES): Result<java.io.File> {
        val resolved = resolvePath(path)
        if (!isRemote) {
            val host = hostFile(resolved)
                ?: return Result.failure(IllegalStateException("无法定位本地文件: $resolved"))
            return if (host.isFile) Result.success(host)
            else Result.failure(IllegalStateException("文件不存在: $resolved"))
        }
        val spec = projectManager.currentRemoteSpec()
            ?: return Result.failure(IllegalStateException("未打开远程工作区"))
        val size = statSize(resolved)
        checkRemoteFetchSize(size, maxBytes)?.let { return Result.failure(IllegalStateException(it)) }
        val client = RemoteFsClient(appContext, spec, commandTimeoutMs = REMOTE_FETCH_TIMEOUT_MS)
        val bytes = client.readBytes(resolved, maxBytes).getOrElse { t ->
            val msg = t.message.orEmpty()
            return Result.failure(
                IllegalStateException(
                    if (msg.startsWith("too_large")) {
                        "远程文件超过大小上限（${maxBytes / (1024 * 1024)}MB）: $resolved"
                    } else {
                        "远程文件拉取失败: $resolved（$msg）"
                    },
                ),
            )
        }
        return runCatching {
            val dir = java.io.File(appContext.cacheDir, "remote-files").apply { mkdirs() }
            val name = Integer.toHexString(resolved.hashCode()) + "-" +
                resolved.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = java.io.File(dir, name)
            target.writeBytes(bytes)
            target
        }.recoverCatching { throw IllegalStateException("远程文件缓存写入失败: ${it.message}") }
    }

    companion object {
        const val REMOTE_FETCH_MAX_BYTES = 50 * 1024 * 1024
        const val REMOTE_FETCH_TIMEOUT_MS = 300_000L

        fun checkRemoteFetchSize(size: Long?, maxBytes: Int): String? {
            if (size != null && size > maxBytes) {
                return "远程文件超过大小上限（${maxBytes / (1024 * 1024)}MB，实际 ${size}B）"
            }
            return null
        }
    }

    suspend fun readText(path: String, limit: Int = 256 * 1024): String {
        val resolved = resolvePath(path)
        if (isRemote) {
            val spec = projectManager.currentRemoteSpec() ?: error("未打开远程工作区")
            return RemoteFsClient(appContext, spec).readText(resolved, maxBytes = limit).getOrThrow()
        }
        val bytes = readBytes(resolved, limit)
        requireNotNull(bytes) { "文件无法读取或超过 $limit 字节: $resolved" }
        return bytes.toString(Charsets.UTF_8)
    }

    suspend fun writeText(path: String, content: String) {
        val resolved = resolvePath(path)
        if (isRemote) {
            val spec = projectManager.currentRemoteSpec() ?: error("未打开远程工作区")
            RemoteFsClient(appContext, spec).writeText(resolved, content).getOrThrow()
            return
        }
        val file = hostFile(resolved)
        if (file != null) {
            file.parentFile?.mkdirs()
            file.writeText(content)
        } else {
            val result = executeShell(
                "mkdir -p ${q(resolved.substringBeforeLast('/').ifBlank { "/" })} && cat > ${q(resolved)}",
                stdin = content,
            )
            check(result.ok) { result.error ?: result.stdout.trim() }
        }
    }

    suspend fun deleteFile(path: String): Boolean {
        val resolved = resolvePath(path)
        val file = hostFile(resolved)
        if (file == null) return executeShell("rm -f ${q(resolved)}").ok
        require(!file.exists() || file.isFile) { "不是文件: $resolved" }
        return !file.exists() || file.delete()
    }

    suspend fun list(path: String): List<String> {
        val resolved = resolvePath(path)
        if (isRemote) {
            val spec = projectManager.currentRemoteSpec() ?: error("未打开远程工作区")
            return RemoteFsClient(appContext, spec).listDir(resolved).map {
                if (it.isDirectory) it.name + "/" else it.name
            }
        }
        val dir = hostFile(resolved)
        if (dir == null) {
            val result = executeShell("ls -1Ap ${q(resolved)}")
            check(result.ok) { result.error ?: result.stdout.trim() }
            return result.stdout.lineSequence().filter { it.isNotEmpty() }.toList()
        }
        require(dir.isDirectory) { "不是目录: $resolved" }
        return dir.listFiles()
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?.map { it.name + if (it.isDirectory) "/" else "" }
            .orEmpty()
    }

    suspend fun loadAgentsMdFragment(): String {
        val root = guestCwd()
        val files = mutableListOf<Pair<String, String>>()
        val rootAgents = "$root/AGENTS.md"
        runCatching {
            if (exists(rootAgents)) {
                val c = readText(rootAgents, 32_000)
                if (c.isNotBlank()) files += rootAgents to c
            }
        }
        runCatching {
            if (isDirectory(root)) {
                list(root).filter { it.endsWith("/") }.take(20).forEach { entry ->
                    val dir = entry.trimEnd('/')
                    val path = if (dir.startsWith("/")) "$dir/AGENTS.md" else "$root/$dir/AGENTS.md"
                    if (exists(path)) {
                        val c = readText(path, 32_000)
                        if (c.isNotBlank()) files += path to c
                    }
                }
            }
        }
        if (files.isEmpty()) return ""
        return buildString {
            appendLine("# 项目指令 (AGENTS.md)")
            appendLine("以下项目指令文件已包含在上下文中，无需重新读取。")
            appendLine()
            var total = 0
            for ((path, content) in files) {
                if (total + content.length > 64_000) break
                appendLine("## AGENTS.md ($path)")
                appendLine(content.take(32_000))
                appendLine()
                total += content.length
            }
        }
    }

    private fun resolveLocal(path: String): File? {
        val worktree = LocalWorktree.current()
        val project = worktree?.project ?: projectManager.hostPath.value
        val guest = GuestPaths.normalize(path)
        val mount = projectManager.guestMountPath
        if (project != null) {
            val root = File(project)
            if (guest == mount || guest.startsWith("$mount/")) {
                return GuestPaths.resolve(root, guest.removePrefix(mount).ifBlank { "/" })
            }
            if (path == project || path.startsWith("$project/")) {
                return GuestPaths.resolve(root, path.removePrefix(project).ifBlank { "/" })
            }
        }
        return if (guest == "/root" || guest.startsWith("/root/")) localFs.resolve(guest) else null
    }

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
