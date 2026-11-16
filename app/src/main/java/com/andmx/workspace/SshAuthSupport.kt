package com.andmx.workspace

import java.io.File

internal object SshAuthSupport {
    const val KEY_AUTH_HINT =
        "当前环境无法进行 SSH 密码认证（缺少 sshpass，且 SSH_ASKPASS 不可用）。" +
            "请改用密钥认证：将私钥放到 ~/.ssh/id_ed25519 或 ~/.ssh/id_rsa，或在工作区设置中指定私钥路径。"

    private val KEY_NAMES = listOf("id_ed25519", "id_ecdsa", "id_rsa", "id_dsa", "identity")

    fun discoverPrivateKey(dirs: List<File>): String? {
        for (dir in dirs) {
            for (name in KEY_NAMES) {
                val key = File(dir, name)
                if (key.isFile && !name.endsWith(".pub")) return key.absolutePath
            }
        }
        return null
    }

    fun askpassScriptContent(password: String): String {
        val quoted = "'" + password.replace("'", "'\\''") + "'"
        return "#!/system/bin/sh\nprintf '%s\\n' $quoted\n"
    }

    fun writeAskpassScript(dir: File, password: String, namePrefix: String = "ssh-askpass"): File {
        dir.mkdirs()
        val script = File.createTempFile(namePrefix, ".sh", dir)
        script.writeText(askpassScriptContent(password), Charsets.UTF_8)
        makeOwnerOnly(script)
        return script
    }

    fun makeOwnerOnly(file: File): Boolean {
        val ownerOnly = runCatching {
            file.setReadable(false, false) &&
                file.setWritable(false, false) &&
                file.setExecutable(false, false) &&
                file.setReadable(true, true) &&
                file.setWritable(true, true) &&
                file.setExecutable(true, true)
        }.getOrDefault(false)
        if (ownerOnly) return true
        return runCatching {
            val p = ProcessBuilder("chmod", "700", file.absolutePath).start()
            p.waitFor() == 0
        }.getOrDefault(false)
    }

    fun askpassEnv(script: File): Map<String, String> = mapOf(
        "SSH_ASKPASS" to script.absolutePath,
        "SSH_ASKPASS_REQUIRE" to "force",
        "DISPLAY" to ":0",
    )

    fun resolveAuth(
        spec: RemoteWorkspaceSpec,
        auth: SshAuthMethod,
        sshpassPath: String?,
        keyDirs: List<File>,
        askpassDir: File,
    ): SshCommandPlan {
        val argv = mutableListOf(
            "-p", spec.port.toString(),
            "-o", "StrictHostKeyChecking=accept-new",
            "-o", "ConnectTimeout=15",
            "-o", "ConnectionAttempts=1",
            "-o", "ServerAliveInterval=15",
            "-o", "ServerAliveCountMax=3",
        )
        when (auth) {
            SshAuthMethod.PRIVATE_KEY -> {
                val keyPath = spec.privateKeyPath.ifBlank { discoverPrivateKey(keyDirs) }
                if (keyPath != null) {
                    argv += listOf("-i", keyPath, "-o", "BatchMode=yes")
                } else {
                    argv += listOf("-o", "BatchMode=yes")
                }
                return SshCommandPlan(argv = argv)
            }
            SshAuthMethod.PASSWORD -> {
                if (sshpassPath != null) {
                    return SshCommandPlan(
                        argv = listOf("-o", "NumberOfPasswordPrompts=1") + argv,
                        wrapper = listOf(sshpassPath, "-p", spec.password),
                    )
                }
                val script = writeAskpassScript(askpassDir, spec.password)
                return SshCommandPlan(
                    argv = listOf("-o", "NumberOfPasswordPrompts=1") + argv,
                    env = askpassEnv(script),
                    askpassScript = script,
                )
            }
        }
    }
}

internal data class SshCommandPlan(
    val argv: List<String>,
    val wrapper: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val askpassScript: File? = null,
) {
    fun commandLine(sshPath: String, target: String, remoteCommand: String): List<String> =
        wrapper + listOf(sshPath) + argv + listOf(target, remoteCommand)
}
