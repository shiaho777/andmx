package com.andmx.workspace

import android.content.Context
import com.andmx.exec.ExecutionEnvironment
import com.andmx.exec.ProcessSpec
import com.andmx.exec.ProcessResult
import com.andmx.exec.files.GuestFs
import com.andmx.exec.proot.LocalProotEnvironment
import com.andmx.exec.proot.ProotRuntime
import com.andmx.exec.remote.RemoteFsClient
import com.andmx.exec.remote.RemoteSshEnvironment

class WorkspaceAccess(context: Context) {
    private val appContext = context.applicationContext
    private val projectManager = ProjectManager(appContext)
    private val runtime = ProotRuntime(appContext)
    private val localEnv = LocalProotEnvironment(appContext, runtime)
    private val localFs = GuestFs(runtime)

    val isRemote: Boolean get() = projectManager.isRemote

    val projectName: String get() = projectManager.projectName

    val hostPath: String? get() = projectManager.hostPath.value

    fun guestCwd(): String {
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

    fun environment(): ExecutionEnvironment {
        if (isRemote) {
            val spec = projectManager.currentRemoteSpec()
            if (spec != null) {
                return RemoteSshEnvironment(appContext, spec, guestCwd())
            }
        }
        return localEnv
    }

    fun shellBinary(): String =
        if (isRemote) "/bin/sh"
        else if (runtime.rootfsDir.exists()) "/bin/sh"
        else "/system/bin/sh"

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

    suspend fun executeShell(command: String, cwd: String? = null): ProcessResult {
        val env = environment()
        val work = cwd?.takeIf { it.isNotBlank() } ?: guestCwd()
        val sh = shellBinary()
        val script = "cd ${q(work)} 2>/dev/null || cd ~ 2>/dev/null || true; $command"
        return env.execute(
            ProcessSpec(
                argv = listOf(sh, "-lc", script),
                workingDir = work,
                redirectErrorStream = true,
            ),
        )
    }

    suspend fun exists(path: String): Boolean {
        val p = resolvePath(path)
        return if (isRemote) {
            val res = executeShell(
                "if [ -e ${q(p)} ]; then echo YES; else echo NO; fi",
                cwd = guestCwd(),
            )
            res.stdout.contains("YES")
        } else {
            localFs.exists(toGuestPath(p))
        }
    }

    suspend fun isDirectory(path: String): Boolean {
        val p = resolvePath(path)
        return if (isRemote) {
            val res = executeShell(
                "if [ -d ${q(p)} ]; then echo YES; else echo NO; fi",
                cwd = guestCwd(),
            )
            res.stdout.contains("YES")
        } else {
            runCatching { localFs.resolve(toGuestPath(p)).isDirectory }.getOrDefault(false)
        }
    }

    suspend fun readText(path: String, limit: Int = 256 * 1024): String {
        val p = resolvePath(path)
        if (isRemote) {
            val spec = projectManager.currentRemoteSpec()
                ?: error("未打开远程工作区")
            return RemoteFsClient(appContext, spec).readText(p, maxBytes = limit).getOrThrow()
        }
        return localFs.readText(toGuestPath(p), limit)
    }

    suspend fun writeText(path: String, content: String) {
        val p = resolvePath(path)
        if (isRemote) {
            val spec = projectManager.currentRemoteSpec()
                ?: error("未打开远程工作区")
            RemoteFsClient(appContext, spec).writeText(p, content).getOrThrow()
            return
        }
        localFs.writeText(toGuestPath(p), content)
    }

    suspend fun deleteFile(path: String): Boolean {
        val p = resolvePath(path)
        if (isRemote) {
            val res = executeShell("rm -f ${q(p)} && echo OK", cwd = guestCwd())
            return res.stdout.contains("OK")
        }
        return localFs.deleteFile(toGuestPath(p))
    }

    suspend fun list(path: String): List<String> {
        val p = resolvePath(path)
        if (isRemote) {
            val spec = projectManager.currentRemoteSpec()
                ?: error("未打开远程工作区")
            return RemoteFsClient(appContext, spec).listDir(p).map {
                if (it.isDirectory) it.name + "/" else it.name
            }
        }
        return localFs.list(toGuestPath(p))
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

    private fun toGuestPath(path: String): String {
        // Local GuestFs expects guest absolute paths. Host project is bound at /root/project.
        if (path.startsWith("/root")) return path
        if (path.startsWith(projectManager.guestMountPath)) return path
        // Absolute host path under selected project → map into guest mount
        val host = hostPath
        if (host != null && path.startsWith(host)) {
            val rel = path.removePrefix(host).trimStart('/')
            return if (rel.isEmpty()) projectManager.guestMountPath
            else "${projectManager.guestMountPath}/$rel"
        }
        if (path.startsWith("/")) return path
        return resolvePath(path)
    }

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
