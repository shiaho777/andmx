package com.andmx.data

import com.andmx.agent.workflow.WorkflowArtifact
import com.andmx.agent.workflow.WorkflowDefinition
import com.andmx.agent.workflow.WorkflowEvent
import com.andmx.agent.workflow.WorkflowRunSnapshot
import com.andmx.agent.workflow.WorkflowRunStatus
import com.andmx.agent.workflow.WorkflowStoreSink
import com.andmx.agent.workflow.workflowJson
import com.andmx.exec.files.GuestFs
import kotlinx.serialization.json.JsonObject

/** ZCode WorkflowStorePort/DefinitionStorePort 对齐：Room 快照 + GuestFs artifact。 */
class WorkflowStore(
    context: android.content.Context,
    private val guestFs: GuestFs,
) : WorkflowStoreSink {
    private val dao = AndmxDatabase.get(context).dao()

    // ── definitions ────────────────────────────────────────────

    suspend fun listDefinitions(): List<WorkflowDefinition> =
        dao.allWorkflowDefinitions().mapNotNull { row ->
            runCatching {
                workflowJson.decodeFromString(WorkflowDefinition.serializer(), row.definitionJson)
            }.getOrNull()
        }

    suspend fun readDefinition(id: String): WorkflowDefinition? =
        dao.workflowDefinition(id)?.let { row ->
            runCatching {
                workflowJson.decodeFromString(WorkflowDefinition.serializer(), row.definitionJson)
            }.getOrNull()
        }

    suspend fun saveDefinition(definition: WorkflowDefinition, source: String = "user") {
        val existing = dao.workflowDefinition(definition.definitionId)
        dao.upsertWorkflowDefinition(
            WorkflowDefinitionEntity(
                id = definition.definitionId,
                version = definition.definitionVersion,
                kind = definition.kind,
                title = definition.title,
                description = definition.description.orEmpty(),
                enabled = true,
                source = source,
                definitionJson = workflowJson.encodeToString(WorkflowDefinition.serializer(), definition),
                createdAtMs = existing?.createdAtMs ?: System.currentTimeMillis(),
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteDefinition(id: String): Boolean = dao.deleteWorkflowDefinition(id) > 0

    // ── runs ───────────────────────────────────────────────────

    data class RunListItem(
        val runId: String,
        val definitionId: String,
        val kind: String,
        val task: String,
        val status: WorkflowRunStatus,
        val cwd: String,
        val createdAtMs: Long,
        val updatedAtMs: Long,
    )

    suspend fun listRuns(limit: Int = 50): List<RunListItem> =
        dao.workflowRuns(limit).map { it.toListItem() }

    suspend fun runConversationId(runId: String): Long =
        dao.workflowRun(runId)?.conversationId ?: 0L

    suspend fun listRunsByStatus(statuses: List<WorkflowRunStatus>): List<RunListItem> =
        dao.workflowRunsByStatus(statuses.map { it.name }).map { it.toListItem() }

    private fun WorkflowRunEntity.toListItem() = RunListItem(
        runId = runId,
        definitionId = definitionId,
        kind = kind,
        task = task,
        status = runCatching { WorkflowRunStatus.valueOf(status) }.getOrDefault(WorkflowRunStatus.pending),
        cwd = cwd,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
    )

    suspend fun writeRunRow(snapshot: WorkflowRunSnapshot, conversationId: Long) {
        val existing = dao.workflowRun(snapshot.runId)
        dao.upsertWorkflowRun(
            WorkflowRunEntity(
                runId = snapshot.runId,
                conversationId = conversationId,
                definitionId = snapshot.definitionId.orEmpty(),
                kind = snapshot.kind,
                task = snapshot.task,
                status = snapshot.status.name,
                cwd = snapshot.cwd,
                snapshotJson = workflowJson.encodeToString(WorkflowRunSnapshot.serializer(), snapshot),
                createdAtMs = existing?.createdAtMs ?: System.currentTimeMillis(),
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    // ── WorkflowStoreSink ──────────────────────────────────────

    /** 快照行由运行时经 persistRun 写（带 conversationId）；sink 路径只更新 JSON/status。 */
    override suspend fun writeSnapshot(snapshot: WorkflowRunSnapshot) {
        val existing = dao.workflowRun(snapshot.runId)
        dao.upsertWorkflowRun(
            WorkflowRunEntity(
                runId = snapshot.runId,
                conversationId = existing?.conversationId ?: 0L,
                definitionId = snapshot.definitionId.orEmpty(),
                kind = snapshot.kind,
                task = snapshot.task,
                status = snapshot.status.name,
                cwd = snapshot.cwd,
                snapshotJson = workflowJson.encodeToString(WorkflowRunSnapshot.serializer(), snapshot),
                createdAtMs = existing?.createdAtMs ?: System.currentTimeMillis(),
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun readSnapshot(runId: String): WorkflowRunSnapshot? =
        dao.workflowRun(runId)?.let { row ->
            runCatching {
                workflowJson.decodeFromString(WorkflowRunSnapshot.serializer(), row.snapshotJson)
            }.getOrNull()
        }

    override suspend fun writeArtifact(runId: String, relativePath: String, content: String): String {
        val guestPath = "$RUNS_DIR/$runId/$relativePath"
        guestFs.writeText(guestPath, content)
        return "$RUNS_DIR/$runId/$relativePath"
    }

    suspend fun readArtifactText(path: String, limit: Int = 128 * 1024): String? =
        runCatching { guestFs.readText(path, limit) }.getOrNull()

    suspend fun listArtifactFiles(runId: String): List<String> =
        runCatching { guestFs.list("$RUNS_DIR/$runId") }.getOrDefault(emptyList())

    override suspend fun appendEvent(event: WorkflowEvent) {
        val seq = dao.workflowEventMaxSeq(event.runId) + 1
        dao.insertWorkflowEvent(
            WorkflowEventEntity(
                runId = event.runId,
                seq = seq,
                type = event.type,
                phase = event.phase.orEmpty(),
                nodeId = event.nodeId.orEmpty(),
                message = event.message.orEmpty(),
                payloadJson = event.payload?.toString().orEmpty(),
                timestamp = event.timestamp,
            ),
        )
    }

    suspend fun events(runId: String): List<WorkflowEvent> =
        dao.workflowEvents(runId).map { row ->
            WorkflowEvent(
                kind = "",
                message = row.message.ifBlank { null },
                nodeId = row.nodeId.ifBlank { null },
                payload = row.payloadJson.takeIf { it.isNotBlank() }?.let {
                    runCatching { workflowJson.decodeFromString(JsonObject.serializer(), it) }.getOrNull()
                },
                phase = row.phase.ifBlank { null },
                runId = row.runId,
                timestamp = row.timestamp,
                type = row.type,
            )
        }

    companion object {
        const val RUNS_DIR = ".andmx/workflow-runs"
    }
}
