package com.andmx.data.rollout

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Session index — mirrors Codex's session_index.jsonl.
 *
 * Maintains a lightweight JSONL index file with one entry per session,
 * allowing fast session listing/searching without reading full rollout files.
 *
 * Format (one JSON object per line):
 * {"id":"<UUID>","thread_name":"<name>","updated_at":"<ISO>","cwd":"<path>","rollout_path":"<path>"}
 */
class SessionIndex(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false },
) {
    companion object {
        private const val TAG = "SessionIndex"
        private const val INDEX_FILE = "session_index.jsonl"
    }

    private val indexFile = File(context.filesDir, INDEX_FILE)
    private val mutex = Mutex()

    @Serializable
    data class IndexEntry(
        val id: String,
        val threadName: String,
        val updatedAt: String,
        val cwd: String = "",
        val rolloutPath: String = "",
        val modelProvider: String = "",
        val archived: Boolean = false,
    )

    /** Add or update a session in the index. */
    suspend fun upsert(entry: IndexEntry) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entries = readAllInternal().toMutableList()
            val idx = entries.indexOfFirst { it.id == entry.id }
            if (idx >= 0) entries[idx] = entry else entries.add(entry)
            writeAllInternal(entries)
        }
    }

    /** Remove a session from the index. */
    suspend fun remove(sessionId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entries = readAllInternal().toMutableList()
            entries.removeAll { it.id == sessionId }
            writeAllInternal(entries)
        }
    }

    /** Mark a session as archived (soft delete). */
    suspend fun archive(sessionId: String, archived: Boolean = true) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entries = readAllInternal().toMutableList()
            val idx = entries.indexOfFirst { it.id == sessionId }
            if (idx >= 0) {
                entries[idx] = entries[idx].copy(archived = archived)
                writeAllInternal(entries)
            }
        }
    }

    /** List all sessions, newest first. */
    suspend fun list(includeArchived: Boolean = false): List<IndexEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            readAllInternal()
                .filter { includeArchived || !it.archived }
                .sortedByDescending { it.updatedAt }
        }
    }

    /** Search sessions by name or cwd. */
    suspend fun search(query: String): List<IndexEntry> = withContext(Dispatchers.IO) {
        val q = query.lowercase()
        mutex.withLock {
            readAllInternal()
                .filter {
                    it.threadName.lowercase().contains(q) ||
                        it.cwd.lowercase().contains(q) ||
                        it.id.lowercase().contains(q)
                }
                .sortedByDescending { it.updatedAt }
        }
    }

    /** Get a single session by ID. */
    suspend fun get(sessionId: String): IndexEntry? = withContext(Dispatchers.IO) {
        mutex.withLock {
            readAllInternal().firstOrNull { it.id == sessionId }
        }
    }

    private fun readAllInternal(): List<IndexEntry> {
        if (!indexFile.exists()) return emptyList()
        return indexFile.useLines { lines ->
            lines.mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                runCatching {
                    json.decodeFromString(IndexEntry.serializer(), line.trim())
                }.getOrElse {
                    Log.w(TAG, "Failed to parse index entry: ${line.take(100)}")
                    null
                }
            }.toList()
        }
    }

    private fun writeAllInternal(entries: List<IndexEntry>) {
        indexFile.bufferedWriter().use { writer ->
            for (entry in entries) {
                val line = runCatching {
                    json.encodeToString(IndexEntry.serializer(), entry)
                }.getOrNull() ?: continue
                writer.write(line)
                writer.newLine()
            }
        }
    }
}
