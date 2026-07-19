package com.andmx.exec.remote

import android.content.Context
import com.andmx.exec.ExecutionEnvironment
import com.andmx.exec.ProcessResult
import com.andmx.exec.ProcessSpec
import com.andmx.workspace.RemoteWorkspaceSpec
import com.andmx.workspace.RemoteWorkspaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class RemoteSshEnvironment(
    private val context: Context,
    private val spec: RemoteWorkspaceSpec,
    private val remoteCwd: String = spec.remotePath.ifBlank { "~" },
) : ExecutionEnvironment {
    override val id: String = "remote-ssh:${spec.id}"
    override val displayName: String = "SSH ${spec.displayName}"

    private val store = RemoteWorkspaceStore(context)

    override suspend fun isAvailable(): Boolean = store.findSshBinary() != null

    override suspend fun execute(specProc: ProcessSpec): ProcessResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val command = buildRemoteCommand(specProc)
        try {
            val argv = store.buildSshArgv(spec, command)
            val pb = ProcessBuilder(argv)
            pb.redirectErrorStream(specProc.redirectErrorStream)
            val proc = pb.start()
            if (specProc.stdin != null) {
                proc.outputStream.use {
                    it.write(specProc.stdin!!.toByteArray())
                    it.flush()
                }
            }
            val stdout = BufferedReader(InputStreamReader(proc.inputStream)).readText()
            val stderr = if (specProc.redirectErrorStream) {
                ""
            } else {
                BufferedReader(InputStreamReader(proc.errorStream)).readText()
            }
            val code = proc.waitFor()
            ProcessResult(
                exitCode = code,
                stdout = stdout,
                stderr = stderr,
                durationMs = (System.nanoTime() - started) / 1_000_000,
            )
        } catch (t: Throwable) {
            ProcessResult(
                exitCode = -1,
                stdout = "",
                stderr = "",
                durationMs = (System.nanoTime() - started) / 1_000_000,
                error = t.message ?: t.javaClass.simpleName,
            )
        }
    }

    private fun buildRemoteCommand(specProc: ProcessSpec): String {
        val cwd = (specProc.workingDir ?: remoteCwd).ifBlank { "~" }
        val quotedCwd = q(cwd)
        val cmd = if (
            specProc.argv.size >= 3 &&
            (specProc.argv[1] == "-c" || specProc.argv[1] == "-lc")
        ) {
            specProc.argv[2]
        } else {
            specProc.argv.joinToString(" ") { q(it) }
        }
        val envExports = specProc.env.entries.joinToString(" ") { (k, v) ->
            "export ${k.filter { ch -> ch.isLetterOrDigit() || ch == '_' }}=${q(v)};"
        }
        return "cd $quotedCwd 2>/dev/null || cd ~; $envExports $cmd"
    }

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

data class RemoteDirEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
)

class RemoteFsClient(
    private val context: Context,
    private val spec: RemoteWorkspaceSpec,
) {
    suspend fun exec(command: String, cwd: String? = null): ProcessResult {
        val env = RemoteSshEnvironment(context, spec, cwd ?: spec.remotePath.ifBlank { "~" })
        return env.execute(
            ProcessSpec(
                argv = listOf("/bin/sh", "-lc", command),
                workingDir = cwd ?: spec.remotePath.ifBlank { "~" },
                redirectErrorStream = true,
            ),
        )
    }

    suspend fun listDir(path: String): List<RemoteDirEntry> {
        val target = path.ifBlank { spec.remotePath.ifBlank { "~" } }
        val dollar = "$"
        val remoteScript =
            "target=${q(target)}; " +
                "if [ ! -d \"${dollar}target\" ]; then echo __ERR__; exit 1; fi; " +
                "cd \"${dollar}target\" || exit 1; " +
                "pwd; echo __SEP__; " +
                "ls -1A 2>/dev/null | while IFS= read -r name; do " +
                "[ -z \"${dollar}name\" ] && continue; " +
                "if [ -d \"${dollar}name\" ]; then echo \"D|0|${dollar}name\"; " +
                "else sz=${dollar}(wc -c < \"${dollar}name\" 2>/dev/null | tr -d ' '); " +
                "echo \"F|${dollar}{sz:-0}|${dollar}name\"; fi; " +
                "done"
        val res = exec(remoteScript, cwd = null)
        if (res.stdout.contains("__ERR__")) return emptyList()
        val lines = res.stdout.lines()
        val sep = lines.indexOf("__SEP__")
        val base = if (sep > 0) lines[sep - 1].trim() else target
        val dataLines = if (sep >= 0) lines.drop(sep + 1) else lines
        val entries = mutableListOf<RemoteDirEntry>()
        for (line in dataLines) {
            val parts = line.split('|', limit = 3)
            if (parts.size < 3) continue
            val isDir = parts[0] == "D"
            val size = parts[1].toLongOrNull() ?: 0L
            val name = parts[2]
            if (name.isBlank() || name == "." || name == "..") continue
            val full = if (base.endsWith("/")) base + name else "$base/$name"
            entries += RemoteDirEntry(name, full, isDir, size)
        }
        return entries.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    suspend fun readText(path: String, maxBytes: Int = 512_000): Result<String> {
        val dollar = "$"
        val remoteScript =
            "f=${q(path)}; " +
                "if [ ! -f \"${dollar}f\" ]; then echo __ERR__ not_a_file; exit 1; fi; " +
                "sz=${dollar}(wc -c < \"${dollar}f\" | tr -d ' '); " +
                "if [ \"${dollar}sz\" -gt $maxBytes ]; then echo __ERR__ too_large; exit 1; fi; " +
                "cat \"${dollar}f\""
        val res = exec(remoteScript)
        val out = res.stdout
        if (out.startsWith("__ERR__")) {
            return Result.failure(IllegalStateException(out))
        }
        if (!res.ok && out.isBlank()) {
            return Result.failure(IllegalStateException(res.error ?: "read failed"))
        }
        return Result.success(out)
    }

    suspend fun writeText(path: String, content: String): Result<Unit> {
        val dir = path.substringBeforeLast('/', missingDelimiterValue = ".")
        val b64 = android.util.Base64.encodeToString(
            content.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
        val remoteScript =
            "mkdir -p ${q(dir)} && " +
                "echo ${q(b64)} | base64 -d > ${q(path)} && echo WRITE_OK"
        val res = exec(remoteScript)
        return if (res.stdout.contains("WRITE_OK")) Result.success(Unit)
        else Result.failure(IllegalStateException(res.stdout.ifBlank { res.error ?: "write failed" }))
    }

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
