package com.andmx.data

import android.content.Context

/** Durable dynamic-workflow actor conversations keyed by the runner's session id. */
class WorkflowActorStore(context: Context) {
    private val dao = AndmxDatabase.get(context.applicationContext).dao()

    suspend fun load(sessionId: String): String? =
        dao.workflowActorMessages(sessionId)?.messagesJson

    suspend fun save(sessionId: String, runId: String, messagesJson: String) {
        dao.upsertWorkflowActorMessages(
            WorkflowActorMessageEntity(
                sessionId = sessionId,
                runId = runId,
                messagesJson = messagesJson,
            ),
        )
    }

    suspend fun deleteRun(runId: String) {
        dao.deleteWorkflowActorMessages(runId)
    }
}
