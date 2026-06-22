package com.andmx.exec

import android.content.Context
import android.util.Log
import com.andmx.exec.proot.ProotRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Shell Snapshots — mirrors Codex's shell_snapshot system.
 *
 * Captures the shell environment state (working directory, environment
 * variables, aliases, functions) at a point in time, allowing:
 * - Session resume with exact shell state
 * - Forking sub-shells with inherited environment
 * - Comparing environment before/after agent operations
 *
 * Snapshots are stored as JSON files and can be restored by sourcing
 * the snapshot file in a new shell session.
 */
class ShellSnapshots(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = false; explicitNulls = false },
) {
    companion object {
        private const val TAG = "ShellSnapshots"
        private const val SNAPSHOTS_DIR = "shell_snapshots"
    }

    private val snapshotsDir = File(context.filesDir, SNAPSHOTS_DIR).apply { mkdirs() }
    private val runtime = ProotRuntime(context)
    private val sh get() = if (runtime.rootfsDir.exists()) "/bin/sh" else "/system/bin/sh"

    @Serializable
    data class ShellSnapshot(
        val id: String,
        val timestamp: String,
        val cwd: String,
        val envVars: Map<String, String>,
        val aliases: Map<String, String> = emptyMap(),
        val functions: List<String> = emptyList(),
        val shellType: String = "sh",
        val sessionId: String = "",
    )

    /**
     * Capture a shell snapshot from a running PersistentShell.
     * The shell must be active and responsive.
     */
    suspend fun capture(shell: PersistentShell, sessionId: String = ""): ShellSnapshot? = withContext(Dispatchers.IO) {
        val id = "snap-${System.currentTimeMillis()}"
        val timestamp = java.time.Instant.now().toString()

        // Get current directory
        val cwdResult = shell.exec("pwd", timeoutMs = 5_000)
        val cwd = cwdResult.stdout.trim().ifBlank { "/root" }

        // Get environment variables
        val envResult = shell.exec("env", timeoutMs = 5_000)
        val envVars = parseEnvVars(envResult.stdout)

        // Get aliases (sh doesn't have aliases by default, but bash does)
        val aliasResult = shell.exec("alias 2>/dev/null || true", timeoutMs = 3_000)
        val aliases = parseAliases(aliasResult.stdout)

        // Get function definitions
        val funcResult = shell.exec("declare -F 2>/dev/null | awk '{print \$3}' || true", timeoutMs = 3_000)
        val functions = funcResult.stdout.lines().filter { it.isNotBlank() }

        val snapshot = ShellSnapshot(
            id = id,
            timestamp = timestamp,
            cwd = cwd,
            envVars = envVars,
            aliases = aliases,
            functions = functions,
            shellType = "sh",
            sessionId = sessionId,
        )

        // Save to file
        val file = File(snapshotsDir, "$id.json")
        runCatching {
            file.writeText(json.encodeToString(ShellSnapshot.serializer(), snapshot))
        }.onFailure { Log.e(TAG, "Failed to save snapshot: ${it.message}") }

        snapshot
    }

    /**
     * Restore a shell snapshot into a PersistentShell.
     * This sets the working directory and exports all environment variables.
     */
    suspend fun restore(shell: PersistentShell, snapshotId: String): Boolean = withContext(Dispatchers.IO) {
        val snapshot = load(snapshotId) ?: return@withContext false

        // Restore environment variables
        val envScript = snapshot.envVars.entries.joinToString("; ") { (k, v) ->
            "export ${k}='${v.replace("'", "'\\''")}'"
        }
        if (envScript.isNotBlank()) {
            shell.exec(envScript, timeoutMs = 5_000)
        }

        // Restore working directory
        shell.exec("cd '${snapshot.cwd}'", timeoutMs = 3_000)

        // Restore aliases
        for ((name, value) in snapshot.aliases) {
            shell.exec("alias ${name}='${value.replace("'", "'\\''")}' 2>/dev/null || true", timeoutMs = 1_000)
        }

        true
    }

    /** Load a snapshot by ID. */
    fun load(snapshotId: String): ShellSnapshot? {
        val file = File(snapshotsDir, "$snapshotId.json")
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString(ShellSnapshot.serializer(), file.readText())
        }.getOrElse {
            Log.w(TAG, "Failed to load snapshot $snapshotId: ${it.message}")
            null
        }
    }

    /** List all snapshots, newest first. */
    fun list(): List<ShellSnapshot> = snapshotsDir.listFiles { f -> f.extension == "json" }
        ?.sortedByDescending { it.lastModified() }
        ?.mapNotNull { f ->
            runCatching { json.decodeFromString(ShellSnapshot.serializer(), f.readText()) }.getOrNull()
        }
        ?: emptyList()

    /** Delete a snapshot. */
    fun delete(snapshotId: String): Boolean {
        return File(snapshotsDir, "$snapshotId.json").delete()
    }

    /** Get the most recent snapshot for a session. */
    fun latestForSession(sessionId: String): ShellSnapshot? =
        list().firstOrNull { it.sessionId == sessionId }

    /** Clean up snapshots older than [maxAgeDays] days. */
    fun cleanupOldSnapshots(maxAgeDays: Int = 7): Int {
        val cutoff = System.currentTimeMillis() - (maxAgeDays * 24 * 60 * 60 * 1000L)
        var count = 0
        snapshotsDir.listFiles { f -> f.extension == "json" }?.forEach { f ->
            if (f.lastModified() < cutoff) {
                if (f.delete()) count++
            }
        }
        return count
    }

    private fun parseEnvVars(output: String): Map<String, String> {
        return output.lines()
            .filter { it.contains('=') }
            .associate { line ->
                val idx = line.indexOf('=')
                line.substring(0, idx) to line.substring(idx + 1)
            }
            .filterKeys { it.isNotBlank() && !it.contains(' ') }
    }

    private fun parseAliases(output: String): Map<String, String> {
        return output.lines()
            .filter { it.contains('=') && it.startsWith("alias ") }
            .associate { line ->
                val rest = line.removePrefix("alias ")
                val idx = rest.indexOf('=')
                val name = rest.substring(0, idx)
                val value = rest.substring(idx + 1).trim('\'', '"')
                name to value
            }
    }
}
