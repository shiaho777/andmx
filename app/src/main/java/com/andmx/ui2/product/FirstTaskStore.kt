package com.andmx.ui2.product

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.andmx.workspace.FileChange
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

internal const val FIRST_TASK_LIMIT = 32 * 1024
internal const val SAMPLE_BEFORE = "# AndMX sample\n\nStatus: draft\n"
internal const val SAMPLE_AFTER = "# AndMX sample\n\nStatus: ready for review\n"

internal enum class FirstTaskKind { SAMPLE, DOCUMENT }

@Serializable
internal data class FirstTaskRecord(
    val id: String,
    val name: String,
    val original: String,
    val proposal: String? = null,
    val sourceUri: String? = null,
)

internal data class FirstTask(val record: FirstTaskRecord, val current: String) {
    val proposal: String? get() = record.proposal?.takeUnless { it == current }
    val change: FileChange?
        get() = current.takeUnless { it == record.original }?.let {
            FileChange(record.name, record.original, it, existedBefore = true)
        }
}

internal class FirstTaskStore(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.noBackupFilesDir, "first-task")
    private val json = Json { ignoreUnknownKeys = true }

    fun read(kind: FirstTaskKind): FirstTask? = synchronized(lock) { readLocked(kind) }

    fun sample(): FirstTask = synchronized(lock) {
        readLocked(FirstTaskKind.SAMPLE) ?: create(FirstTaskKind.SAMPLE, "README.md", SAMPLE_BEFORE)
    }

    fun import(uri: Uri): FirstTask {
        val resolver = app.contentResolver
        val rawName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        val name = rawName.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            .filterNot { it.isISOControl() }.take(100).ifBlank { "document.txt" }
        val bytes = checkNotNull(resolver.openInputStream(uri)).use { it.readBounded(FIRST_TASK_LIMIT) }
        val content = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        require('\u0000' !in content)
        return synchronized(lock) { create(FirstTaskKind.DOCUMENT, name, content, uri.toString()) }
    }

    fun propose(kind: FirstTaskKind, expected: FirstTask, text: String): FirstTask = synchronized(lock) {
        val task = requireCurrent(kind, expected)
        require(text.toByteArray().size <= FIRST_TASK_LIMIT && '\u0000' !in text)
        val record = task.record.copy(proposal = text.takeUnless { it == task.current })
        writeRecord(kind, record)
        FirstTask(record, task.current)
    }

    fun accept(kind: FirstTaskKind, expected: FirstTask): FirstTask = synchronized(lock) {
        val task = requireCurrent(kind, expected)
        val replacement = checkNotNull(task.proposal)
        file(task.record).writeTextAtomically(replacement)
        clearProposal(kind, task.record, replacement)
    }

    fun reject(kind: FirstTaskKind, expected: FirstTask): FirstTask = synchronized(lock) {
        val task = requireCurrent(kind, expected)
        clearProposal(kind, task.record, task.current)
    }

    fun restore(kind: FirstTaskKind, expected: FirstTask): FirstTask = synchronized(lock) {
        val task = requireCurrent(kind, expected)
        file(task.record).writeTextAtomically(task.record.original)
        clearProposal(kind, task.record, task.record.original)
    }

    fun export(uri: Uri, expected: FirstTask) = synchronized(lock) {
        val task = requireCurrent(FirstTaskKind.DOCUMENT, expected)
        require(task.record.sourceUri?.let { !sameDocument(Uri.parse(it), uri) } != false)
        require(task.change != null && task.proposal == null)
        checkNotNull(app.contentResolver.openOutputStream(uri, "wt")).use {
            it.write(task.current.toByteArray(Charsets.UTF_8))
        }
    }

    private fun requireCurrent(kind: FirstTaskKind, expected: FirstTask): FirstTask {
        val current = checkNotNull(readLocked(kind))
        check(current == expected)
        return current
    }

    private fun clearProposal(kind: FirstTaskKind, record: FirstTaskRecord, content: String): FirstTask {
        val updated = record.copy(proposal = null)
        writeRecord(kind, updated)
        return FirstTask(updated, content)
    }

    private fun create(kind: FirstTaskKind, name: String, content: String, sourceUri: String? = null): FirstTask {
        val previous = readLocked(kind)
        val record = FirstTaskRecord(UUID.randomUUID().toString(), name, content, sourceUri = sourceUri)
        directory.mkdirs()
        file(record).writeTextAtomically(content)
        writeRecord(kind, record)
        previous?.let { AtomicFile(file(it.record)).delete() }
        return FirstTask(record, content)
    }

    private fun readLocked(kind: FirstTaskKind): FirstTask? {
        val metadata = metadata(kind)
        if (!metadata.baseFile.exists() && !File(metadata.baseFile.path + ".bak").exists()) return null
        val record = json.decodeFromString<FirstTaskRecord>(metadata.readFully().toString(Charsets.UTF_8))
        check(UUID.fromString(record.id).toString() == record.id)
        return FirstTask(record, AtomicFile(file(record)).readFully().toString(Charsets.UTF_8))
    }

    private fun file(record: FirstTaskRecord) = File(directory, record.id + ".txt")

    private fun metadata(kind: FirstTaskKind) = AtomicFile(File(directory, kind.name + ".json"))

    private fun writeRecord(kind: FirstTaskKind, record: FirstTaskRecord) {
        metadata(kind).baseFile.writeTextAtomically(json.encodeToString(FirstTaskRecord.serializer(), record))
    }

    private fun File.writeTextAtomically(content: String) {
        val atomic = AtomicFile(this)
        val output = atomic.startWrite()
        try {
            output.write(content.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (failure: Throwable) {
            atomic.failWrite(output)
            throw failure
        }
    }

    private companion object {
        val lock = Any()
    }
}

internal fun InputStream.readBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    while (true) {
        val count = read(chunk)
        if (count < 0) break
        require(output.size() + count <= limit)
        output.write(chunk, 0, count)
    }
    return output.toByteArray()
}

internal fun sameDocument(first: Uri, second: Uri): Boolean {
    if (first == second) return true
    if (first.authority != second.authority) return false
    val firstId = runCatching { android.provider.DocumentsContract.getDocumentId(first) }.getOrNull()
    val secondId = runCatching { android.provider.DocumentsContract.getDocumentId(second) }.getOrNull()
    return firstId != null && firstId == secondId
}
