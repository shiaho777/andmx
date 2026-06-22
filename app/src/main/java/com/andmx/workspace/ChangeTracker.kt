package com.andmx.workspace

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
 * Process-wide store of pending file changes the agent has made, so the diff
 * pane can review them — mirroring Codex's diff-centric workflow. Latest change
 * per path wins (collapses repeated edits into one reviewable diff vs origin).
 */
object ChangeTracker {
    private val _changes = MutableStateFlow<List<FileChange>>(emptyList())
    val changes: StateFlow<List<FileChange>> = _changes

    /** Record an edit. The original content is preserved from the first edit. */
    fun record(path: String, oldContent: String, newContent: String, existedBefore: Boolean = oldContent.isNotEmpty()) {
        val existing = _changes.value.firstOrNull { GuestPaths.same(it.path, path) }
        val origin = existing?.oldContent ?: oldContent
        val originExisted = existing?.existedBefore ?: existedBefore
        val updated = _changes.value.filterNot { GuestPaths.same(it.path, path) } +
            FileChange(path, origin, newContent, originExisted)
        _changes.value = updated.sortedByDescending { it.timestamp }
    }

    fun clear() { _changes.value = emptyList() }

    fun remove(path: String) {
        _changes.value = _changes.value.filterNot { GuestPaths.same(it.path, path) }
    }

    fun accept(path: String) = remove(path)

    fun acceptAll() = clear()
}
