package com.andmx.data

import android.content.Context
import kotlinx.coroutines.runBlocking

/**
 * Durable JSON snapshots of the JS dynamic-workflow journal.
 *
 * The portable journal lives in the WebView, but every mutation is mirrored
 * synchronously through the JS bridge so process death can resume from the
 * last completed node.
 */
class WorkflowJournalStore(context: Context) {
    private val dao = AndmxDatabase.get(context.applicationContext).dao()

    suspend fun load(runId: String): String? =
        dao.workflowJournal(runId)?.json

    suspend fun save(runId: String, json: String) {
        dao.upsertWorkflowJournal(
            WorkflowJournalEntity(runId = runId, json = json),
        )
    }

    /** Called from the WebView JS thread; Room's suspend bridge runs on IO. */
    fun saveBlocking(runId: String, json: String) {
        runBlocking { save(runId, json) }
    }

    suspend fun delete(runId: String) {
        dao.deleteWorkflowJournal(runId)
    }
}
