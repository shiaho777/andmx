package com.andmx.workspace

import android.content.Context
import com.andmx.data.AndmxDatabase
import com.andmx.data.WorkspaceChangeEntity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** A file modification made by the agent, kept for diff review. */
data class FileChange(
    val path: String,
    val oldContent: String,
    val newContent: String,
    val existedBefore: Boolean = oldContent.isNotEmpty(),
    val timestamp: Long = System.currentTimeMillis(),
) {
    val isNew: Boolean get() = !existedBefore
}

/**
 * Per-conversation file change snapshots.
 *
 * Room is the source of truth (survives process death); the in-memory flows
 * only exist so Compose can observe the active conversation without a DB
 * query per frame. A change is never visible to another conversation.
 */
object ChangeTracker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flows = ConcurrentHashMap<Long, MutableStateFlow<List<FileChange>>>()

    @Volatile private var store: WorkspaceChangeStore? = null

    fun install(context: Context) {
        store = WorkspaceChangeStore(context.applicationContext)
    }

    fun changesFor(conversationId: Long): StateFlow<List<FileChange>> =
        flowFor(conversationId)

    /** Load persisted changes for a conversation; safe to call repeatedly. */
    fun load(conversationId: Long) {
        if (conversationId <= 0L) return
        scope.launch { refresh(conversationId) }
    }

    suspend fun refresh(conversationId: Long) {
        if (conversationId <= 0L) return
        flowFor(conversationId).value = store?.load(conversationId) ?: emptyList()
    }

    /** Record an edit. The original content is preserved from the first edit. */
    fun record(
        conversationId: Long,
        path: String,
        oldContent: String,
        newContent: String,
        existedBefore: Boolean = oldContent.isNotEmpty(),
    ) {
        if (conversationId <= 0L) return
        val flow = flowFor(conversationId)
        val existing = flow.value.firstOrNull { GuestPaths.same(it.path, path) }
        val origin = existing?.oldContent ?: oldContent
        val originExisted = existing?.existedBefore ?: existedBefore
        val change = FileChange(path, origin, newContent, originExisted)
        flow.value = (flow.value.filterNot { GuestPaths.same(it.path, path) } + change)
            .sortedByDescending { it.timestamp }
        scope.launch {
            store?.upsert(conversationId, change)
        }
    }

    fun remove(conversationId: Long, path: String) {
        if (conversationId <= 0L) return
        val flow = flowFor(conversationId)
        flow.value = flow.value.filterNot { GuestPaths.same(it.path, path) }
        scope.launch { store?.delete(conversationId, path) }
    }

    fun clear(conversationId: Long) {
        if (conversationId <= 0L) return
        flowFor(conversationId).value = emptyList()
        scope.launch { store?.clear(conversationId) }
    }

    /** Fork copies the pending review set so both branches start from the same snapshot. */
    suspend fun copy(sourceId: Long, targetId: Long) {
        if (sourceId <= 0L || targetId <= 0L) return
        val source = store?.load(sourceId) ?: flowFor(sourceId).value
        flowFor(targetId).value = source
        store?.copy(sourceId, targetId)
    }

    private fun flowFor(conversationId: Long): MutableStateFlow<List<FileChange>> =
        flows.getOrPut(conversationId) { MutableStateFlow(emptyList()) }
}

private class WorkspaceChangeStore(context: Context) {
    private val dao = AndmxDatabase.get(context).dao()

    suspend fun load(conversationId: Long): List<FileChange> =
        dao.workspaceChanges(conversationId).map { it.toChange() }

    suspend fun upsert(conversationId: Long, change: FileChange) {
        dao.upsertWorkspaceChange(
            WorkspaceChangeEntity(
                conversationId = conversationId,
                path = change.path,
                oldContent = change.oldContent,
                newContent = change.newContent,
                existedBefore = change.existedBefore,
                timestamp = change.timestamp,
            ),
        )
    }

    suspend fun delete(conversationId: Long, path: String) {
        dao.deleteWorkspaceChange(conversationId, path)
        // GuestPaths-equivalent paths may differ by a leading slash.
        dao.workspaceChanges(conversationId)
            .filter { GuestPaths.same(it.path, path) }
            .forEach { dao.deleteWorkspaceChange(conversationId, it.path) }
    }

    suspend fun clear(conversationId: Long) {
        dao.deleteWorkspaceChanges(conversationId)
    }

    suspend fun copy(sourceId: Long, targetId: Long) {
        dao.copyWorkspaceChanges(sourceId, targetId)
    }

    private fun WorkspaceChangeEntity.toChange() = FileChange(
        path = path,
        oldContent = oldContent,
        newContent = newContent,
        existedBefore = existedBefore,
        timestamp = timestamp,
    )
}
