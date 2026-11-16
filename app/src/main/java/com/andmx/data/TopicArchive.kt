package com.andmx.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class TopicRecord(
    val id: String,
    val text: String,
    val senderType: String,
    val createdAt: Long,
    val clipped: Boolean = false,
)

data class TopicReadResult(
    val records: List<TopicRecord>,
    val nextCursor: Int?,
    val truncated: Boolean,
    val total: Int,
)

class TopicArchive(private val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, "topics"))

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private fun fileFor(sessionId: String): File =
        File(dir, sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jsonl")

    suspend fun append(sessionId: String, record: TopicRecord) = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.mkdirs()
            val file = fileFor(sessionId)
            val existing = readAll(file)
            if (existing.any { it.id == record.id }) return@withLock
            file.appendText(json.encodeToString(record) + "\n")
        }
    }

    suspend fun read(
        sessionId: String,
        query: String,
        cursor: Int,
        maxTokens: Int,
    ): TopicReadResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readAll(fileFor(sessionId)).filter { it.senderType == "user" }
            val q = query.trim().lowercase()
            val matched = if (q.isEmpty()) all else all.filter {
                it.id.lowercase() == q || it.text.lowercase().contains(q)
            }
            val page = matched.drop(cursor.coerceAtLeast(0)).take(PAGE_SIZE)
            val budgetChars = (maxTokens.coerceIn(256, 12_000) - 100) * 4
            var used = 0
            var clippedAny = false
            val budgeted = page.map { rec ->
                var text = rec.text
                var clipped = rec.clipped
                while (used + text.length > budgetChars && text.length > 200) {
                    text = text.take((text.length * 0.8).toInt())
                    clipped = true
                }
                if (used + text.length > budgetChars) {
                    text = text.take((budgetChars - used).coerceAtLeast(0))
                    clipped = clipped || text.length < rec.text.length
                }
                used += text.length
                if (clipped) clippedAny = true
                rec.copy(text = text, clipped = clipped)
            }.filter { it.text.isNotEmpty() }
            val consumed = cursor.coerceAtLeast(0) + page.size
            val hasMore = consumed < matched.size
            TopicReadResult(
                records = budgeted,
                nextCursor = if (hasMore) consumed else null,
                truncated = hasMore || clippedAny || budgeted.size < page.size,
                total = matched.size,
            )
        }
    }

    suspend fun delete(sessionId: String) = withContext(Dispatchers.IO) {
        mutex.withLock { fileFor(sessionId).delete() }
    }

    private fun readAll(file: File): List<TopicRecord> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            if (line.isBlank()) null else runCatching { json.decodeFromString<TopicRecord>(line) }.getOrNull()
        }
    }

    companion object {
        const val PAGE_SIZE = 20
    }
}