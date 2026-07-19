package com.andmx.workspace

import android.content.Context
import com.andmx.exec.remote.RemoteDirEntry
import com.andmx.exec.remote.RemoteFsClient
import com.andmx.exec.remote.RemoteSshEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID

enum class RemoteKind { SSH }

enum class SshAuthMethod { PASSWORD, PRIVATE_KEY }

@Serializable
data class RemoteWorkspaceSpec(
    val id: String = UUID.randomUUID().toString(),
    val kind: String = RemoteKind.SSH.name,
    val label: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val authMethod: String = SshAuthMethod.PRIVATE_KEY.name,
    val password: String = "",
    val privateKeyPath: String = "",
    val passphrase: String = "",
    val remotePath: String = "",
    val lastConnectedAt: Long = 0L,
) {
    val displayName: String
        get() = label.ifBlank {
            val pathName = remotePath.trimEnd('/').substringAfterLast('/').ifBlank { remotePath }
            listOfNotNull(
                "$username@$host".takeIf { username.isNotBlank() && host.isNotBlank() },
                pathName.takeIf { it.isNotBlank() },
            ).joinToString(":")
        }

    val sshTarget: String get() = "$username@$host"

    val workspaceUri: String
        get() {
            val path = remotePath.ifBlank { "~" }
            return "ssh://$username@$host:$port$path"
        }
}

data class RemoteProbeResult(
    val ok: Boolean,
    val message: String,
    val logs: List<String> = emptyList(),
    val remotePath: String = "",
)

data class RemoteConnectResult(
    val ok: Boolean,
    val message: String,
    val logs: List<String> = emptyList(),
    val spec: RemoteWorkspaceSpec? = null,
    val homePath: String = "",
)

class RemoteWorkspaceStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val appContext = context.applicationContext

    private val _profiles = MutableStateFlow(load())
    val profiles: StateFlow<List<RemoteWorkspaceSpec>> = _profiles.asStateFlow()

    private val _activeRemoteId = MutableStateFlow(prefs.getString(KEY_ACTIVE, null))
    val activeRemoteId: StateFlow<String?> = _activeRemoteId.asStateFlow()

    private val _sessionHome = MutableStateFlow(prefs.getString(KEY_SESSION_HOME, null))
    val sessionHome: StateFlow<String?> = _sessionHome.asStateFlow()

    val activeRemote: RemoteWorkspaceSpec?
        get() = _profiles.value.firstOrNull { it.id == _activeRemoteId.value }

    fun upsert(spec: RemoteWorkspaceSpec) {
        val next = listOf(spec) + _profiles.value.filter { it.id != spec.id }
        save(next)
    }

    fun delete(id: String) {
        val next = _profiles.value.filter { it.id != id }
        save(next)
        if (_activeRemoteId.value == id) clearActive()
    }

    fun setActive(id: String?) {
        if (id == null) {
            clearActive()
            return
        }
        prefs.edit().putString(KEY_ACTIVE, id).apply()
        _activeRemoteId.value = id
    }

    fun clearActive() {
        prefs.edit().remove(KEY_ACTIVE).remove(KEY_SESSION_HOME).apply()
        _activeRemoteId.value = null
        _sessionHome.value = null
    }

    fun findSshBinary(): String? = findBinary("ssh")

    fun buildSshArgv(
        spec: RemoteWorkspaceSpec,
        remoteCommand: String,
        batchMode: Boolean = true,
    ): List<String> {
        val ssh = findSshBinary() ?: error("ssh binary not found")
        val auth = runCatching { SshAuthMethod.valueOf(spec.authMethod) }
            .getOrDefault(SshAuthMethod.PRIVATE_KEY)
        val argv = mutableListOf(
            ssh,
            "-p", spec.port.toString(),
            "-o", "StrictHostKeyChecking=accept-new",
            "-o", "ConnectTimeout=15",
            "-o", "ConnectionAttempts=1",
            "-o", "ServerAliveInterval=30",
        )
        if (batchMode) {
            argv.add(2, "-o")
            argv.add(3, "BatchMode=yes")
        }
        if (auth == SshAuthMethod.PRIVATE_KEY && spec.privateKeyPath.isNotBlank()) {
            argv += listOf("-i", spec.privateKeyPath)
            if (spec.passphrase.isNotBlank()) {
                // OpenSSH will prompt; without ssh-agent, passphrase keys may fail in batch mode.
            }
        }
        argv += listOf(spec.sshTarget, remoteCommand)
        if (auth == SshAuthMethod.PASSWORD) {
            val sshpass = findBinary("sshpass")
                ?: error("密码认证需要 sshpass，请改用私钥")
            return listOf(sshpass, "-p", spec.password) + argv
        }
        return argv
    }

    suspend fun probe(spec: RemoteWorkspaceSpec): RemoteProbeResult = withContext(Dispatchers.IO) {
        val connected = connect(spec)
        RemoteProbeResult(
            ok = connected.ok,
            message = connected.message,
            logs = connected.logs,
            remotePath = connected.spec?.remotePath ?: connected.homePath,
        )
    }

    suspend fun connect(spec: RemoteWorkspaceSpec): RemoteConnectResult = withContext(Dispatchers.IO) {
        val logs = mutableListOf<String>()
        if (spec.host.isBlank() || spec.username.isBlank()) {
            return@withContext RemoteConnectResult(false, "主机和用户名不能为空", logs)
        }
        val auth = runCatching { SshAuthMethod.valueOf(spec.authMethod) }
            .getOrDefault(SshAuthMethod.PRIVATE_KEY)
        if (auth == SshAuthMethod.PRIVATE_KEY && spec.privateKeyPath.isBlank()) {
            return@withContext RemoteConnectResult(false, "私钥路径不能为空", logs)
        }
        if (auth == SshAuthMethod.PASSWORD && spec.password.isBlank()) {
            return@withContext RemoteConnectResult(false, "密码不能为空", logs)
        }
        logs += "已校验向导参数，准备发起连接。"
        val ssh = findSshBinary()
        if (ssh == null) {
            logs += "未找到 ssh 可执行文件（可在 proot rootfs 中安装 openssh）"
            return@withContext RemoteConnectResult(false, "当前环境没有可用的 ssh 客户端", logs)
        }
        logs += "使用 ssh：$ssh"
        logs += "目标 SSH 节点：${spec.username}@${spec.host}:${spec.port}"

        val remoteCmd = buildString {
            append("echo REMOTE_OK; ")
            append("pwd; ")
            append("uname -s 2>/dev/null; ")
            append("uname -m 2>/dev/null; ")
            if (spec.remotePath.isNotBlank()) {
                append("cd ${shellSingleQuote(spec.remotePath)} 2>/dev/null && pwd || echo REMOTE_PATH_MISSING")
            }
        }
        try {
            val argv = buildSshArgv(spec, remoteCmd)
            logs += "正在请求宿主进程创建远程 session..."
            val pb = ProcessBuilder(argv)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = BufferedReader(InputStreamReader(proc.inputStream)).readText()
            val code = proc.waitFor()
            logs += out.lineSequence().filter { it.isNotBlank() }.take(24).toList()
            val ok = code == 0 && out.contains("REMOTE_OK")
            if (!ok) {
                return@withContext RemoteConnectResult(false, "连接失败（exit=$code）", logs)
            }
            val paths = out.lines().map { it.trim() }.filter { it.startsWith("/") }
            val home = paths.firstOrNull().orEmpty()
            val remotePath = when {
                spec.remotePath.isNotBlank() && !out.contains("REMOTE_PATH_MISSING") ->
                    paths.lastOrNull { it != home } ?: spec.remotePath
                else -> home
            }
            val saved = spec.copy(
                lastConnectedAt = System.currentTimeMillis(),
                remotePath = remotePath.ifBlank { spec.remotePath },
                label = spec.label.ifBlank {
                    listOfNotNull(
                        "${spec.username}@${spec.host}",
                        remotePath.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() },
                    ).joinToString(":")
                },
            )
            upsert(saved)
            setActive(saved.id)
            prefs.edit().putString(KEY_SESSION_HOME, home.ifBlank { remotePath }).apply()
            _sessionHome.value = home.ifBlank { remotePath }
            logs += "远程 session 已创建完成，接下来可以选择目录。"
            RemoteConnectResult(
                ok = true,
                message = "连接成功，继续选择要打开的目录。",
                logs = logs,
                spec = saved,
                homePath = home.ifBlank { remotePath },
            )
        } catch (t: Throwable) {
            logs += (t.message ?: t.toString())
            RemoteConnectResult(false, "SSH 连接失败：${t.message ?: "unknown"}", logs)
        }
    }

    suspend fun listRemoteDir(spec: RemoteWorkspaceSpec, path: String): List<RemoteDirEntry> =
        withContext(Dispatchers.IO) {
            RemoteFsClient(appContext, spec).listDir(path)
        }

    suspend fun openRemoteWorkspace(
        projectManager: ProjectManager,
        spec: RemoteWorkspaceSpec,
        remotePath: String,
    ): RemoteConnectResult = withContext(Dispatchers.IO) {
        val logs = mutableListOf<String>()
        val path = remotePath.trim().ifBlank { spec.remotePath }.ifBlank { "~" }
        logs += "正在打开远程目录：$path"
        val check = RemoteFsClient(appContext, spec).exec(
            run {
                val d = "$"
                "target=${shellSingleQuote(path)}; " +
                    "if [ -d \"${d}target\" ]; then cd \"${d}target\" && pwd && echo DIR_OK; " +
                    "else echo DIR_MISSING; exit 1; fi"
            },
        )
        logs += check.stdout.lineSequence().filter { it.isNotBlank() }.take(10).toList()
        if (!check.stdout.contains("DIR_OK")) {
            return@withContext RemoteConnectResult(false, "远程目录不可用：$path", logs, spec)
        }
        val resolved = check.stdout.lines().firstOrNull { it.startsWith("/") }?.trim() ?: path
        val saved = spec.copy(
            remotePath = resolved,
            lastConnectedAt = System.currentTimeMillis(),
            label = spec.label.ifBlank {
                listOfNotNull(
                    "${spec.username}@${spec.host}",
                    resolved.trimEnd('/').substringAfterLast('/'),
                ).joinToString(":")
            },
        )
        upsert(saved)
        setActive(saved.id)
        projectManager.selectRemoteProject(saved)
        logs += "已在当前窗口打开远程工作区"
        RemoteConnectResult(true, "已打开远程工作区", logs, saved, homePath = _sessionHome.value.orEmpty())
    }

    fun environmentForActive(): RemoteSshEnvironment? {
        val remote = activeRemote ?: return null
        if (remote.remotePath.isBlank()) return null
        return RemoteSshEnvironment(appContext, remote, remote.remotePath)
    }

    private fun findBinary(name: String): String? {
        val candidates = listOf(
            "/system/bin/$name",
            "/system/xbin/$name",
            "/data/data/${appContext.packageName}/files/proot-rootfs/usr/bin/$name",
            "/data/data/${appContext.packageName}/files/rootfs/usr/bin/$name",
        )
        candidates.firstOrNull { File(it).exists() }?.let { return it }
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "command -v $name"))
            val path = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() == 0 && path.isNotBlank() && File(path).exists()) path else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun load(): List<RemoteWorkspaceSpec> {
        val raw = prefs.getString(KEY_PROFILES, "[]") ?: "[]"
        return runCatching {
            json.decodeFromString<List<RemoteWorkspaceSpec>>(raw)
        }.getOrDefault(emptyList())
    }

    private fun save(list: List<RemoteWorkspaceSpec>) {
        _profiles.value = list.take(30)
        prefs.edit().putString(KEY_PROFILES, json.encodeToString(list.take(30))).apply()
    }

    private fun shellSingleQuote(value: String): String =
        value.replace("'", "'\\''")

    companion object {
        private const val PREFS = "andmx_remote_workspace"
        private const val KEY_PROFILES = "profiles_json"
        private const val KEY_ACTIVE = "active_remote_id"
        private const val KEY_SESSION_HOME = "session_home"
    }
}
