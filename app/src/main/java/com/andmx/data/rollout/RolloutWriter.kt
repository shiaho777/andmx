package com.andmx.data.rollout

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.util.UUID

/**
 * Append-only JSONL writer for session rollout files.
 *
 * Mirrors Codex's rollout persistence: every agent event is serialized as a
 * single JSON line, allowing full session replay and crash recovery.
 *
 * Thread-safe via a [Mutex]; all writes happen on [Dispatchers.IO].
 */
class RolloutWriter(
    context: Context,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    },
) {
    companion object {
        private const val TAG = "RolloutWriter"
        private const val FLUSH_EVERY_LINES = 12
    }

    private val rolloutsDir = File(context.filesDir, "rollouts").apply { mkdirs() }
    private val mutex = Mutex()

    @Volatile
    private var currentWriter: PrintWriter? = null

    @Volatile
    private var currentFile: File? = null

    @Volatile
    private var sessionId: String = ""

    @Volatile
    private var unflushedLines: Int = 0

    /** Start a new rollout file. Returns the session ID. */
    suspend fun startSession(
        cwd: String,
        modelProvider: String,
        baseInstructions: String = "",
        cliVersion: String = "",
    ): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeInternal()
            sessionId = UUID.randomUUID().toString()
            val timestamp = java.time.Instant.now().toString()
                .replace(":", "-").replace(".", "-")
            val filename = "rollout-$timestamp-${sessionId.take(8)}.jsonl"
            val file = File(rolloutsDir, filename)
            currentFile = file
            currentWriter = PrintWriter(FileWriter(file, true), false)
            unflushedLines = 0

            val meta = SessionMeta(
                id = sessionId,
                timestamp = java.time.Instant.now().toString(),
                cwd = cwd,
                cliVersion = cliVersion,
                modelProvider = modelProvider,
                baseInstructions = baseInstructions.take(2000),
            )
            writeEntry(RolloutEntryBuilder.sessionMeta(meta))
            sessionId
        }
    }

    /** Write a turn context snapshot at the start of each model turn. */
    suspend fun writeTurnContext(ctx: TurnContext) = withContext(Dispatchers.IO) {
        mutex.withLock { writeEntry(RolloutEntryBuilder.turnContext(ctx)) }
    }

    /** Write a response item (assistant message, tool call, tool output). */
    suspend fun writeResponseItem(item: ResponseItem) = withContext(Dispatchers.IO) {
        mutex.withLock { writeEntry(RolloutEntryBuilder.responseItem(item)) }
    }

    /** Write an event message (task_started, task_completed, token usage). */
    suspend fun writeEventMsg(msg: EventMsg) = withContext(Dispatchers.IO) {
        mutex.withLock { writeEntry(RolloutEntryBuilder.eventMsg(msg)) }
    }

    /** Close the current rollout file. */
    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock { closeInternal() }
    }

    suspend fun attachExisting(file: File, existingSessionId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeInternal()
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
            currentFile = file
            currentWriter = PrintWriter(FileWriter(file, true), false)
            unflushedLines = 0
            sessionId = existingSessionId.ifBlank { file.nameWithoutExtension }
        }
    }

    /** Get the current rollout file path (for storing in the database). */
    fun currentFilePath(): String? = currentFile?.absolutePath

    /** Get the current session ID. */
    fun currentSessionId(): String = sessionId

    /** List all rollout files, newest first. */
    fun listRollouts(): List<File> =
        rolloutsDir.listFiles { f -> f.extension == "jsonl" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    private fun writeEntry(entry: RolloutEntry, forceFlush: Boolean = false) {
        val writer = currentWriter
        if (writer == null) {
            Log.w(TAG, "No active rollout writer, dropping entry")
            return
        }
        val line = runCatching { json.encodeToString(RolloutEntry.serializer(), entry) }
            .getOrElse { return }
        writer.println(line)
        unflushedLines++
        val critical = entry.type == "event_msg" || entry.type == "session_meta"
        if (forceFlush || critical || unflushedLines >= FLUSH_EVERY_LINES) {
            writer.flush()
            unflushedLines = 0
        }
        if (writer.checkError()) {
            Log.e(TAG, "Write error in rollout file")
        }
    }

    suspend fun flush() = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentWriter?.flush()
            unflushedLines = 0
        }
    }

    private fun closeInternal() {
        currentWriter?.flush()
        currentWriter?.close()
        currentWriter = null
        currentFile = null
        unflushedLines = 0
    }
}
