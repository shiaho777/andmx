package com.andmx.agent.workflow

import com.andmx.data.WorkflowStore
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * ZCode dwf runtime 对齐：run 生命周期登记处。
 * actorRunner 由宿主注入（ChatController 侧建 AgentEngine 回合），
 * 这里只管定义库 + 快照 + 事件 + job 生命周期。
 */
class WorkflowService(
    private val scope: CoroutineScope,
    private val store: WorkflowStore,
    private val actorRunner: suspend (WorkflowAgentInput) -> WorkflowAgentResult,
) {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val runtimes = ConcurrentHashMap<String, WorkflowRuntime>()

    private fun runtime(): WorkflowRuntime = WorkflowRuntime(
        store = store,
        agentRunner = object : WorkflowAgentRunner {
            override suspend fun run(input: WorkflowAgentInput) = actorRunner(input)
        },
    )

    data class RunHandle(val runId: String, val running: Boolean)

    suspend fun start(
        definition: WorkflowDefinition,
        task: String,
        conversationId: Long,
        cwd: String,
        parentSessionId: String? = null,
    ): WorkflowRunSnapshot {
        val rt = runtime()
        val snapshot = rt.newSnapshot(
            definition = definition,
            task = task,
            cwd = cwd,
            sessionId = parentSessionId,
        )
        store.writeRunRow(snapshot, conversationId)
        store.appendEvent(
            WorkflowEvent(
                kind = snapshot.kind, runId = snapshot.runId,
                timestamp = java.time.Instant.now().toString(),
                type = "run_created", message = task.take(200),
            ),
        )
        launchRun(definition, snapshot, parentSessionId)
        return snapshot
    }

    suspend fun resume(runId: String, parentSessionId: String? = null): Pair<WorkflowRunSnapshot?, String?> {
        val snapshot = store.readSnapshot(runId)
            ?: return null to "Workflow run $runId was not found."
        if (snapshot.status == WorkflowRunStatus.running && jobs[runId]?.isActive == true) {
            return snapshot to "Workflow run $runId is already running."
        }
        if (snapshot.status == WorkflowRunStatus.completed || snapshot.status == WorkflowRunStatus.cancelled) {
            return snapshot to "Workflow run $runId already ${snapshot.status}; create a new run instead."
        }
        val definition = readDefinition(snapshot.definitionId.orEmpty())
            ?: if (snapshot.kind == ExpertWorkflow.KIND) ExpertWorkflow.definition() else null
            ?: return snapshot to "Workflow definition ${snapshot.definitionId} is missing; cannot resume."
        launchRun(definition, snapshot, parentSessionId)
        return snapshot to null
    }

    private fun launchRun(
        definition: WorkflowDefinition,
        snapshot: WorkflowRunSnapshot,
        parentSessionId: String?,
    ) {
        jobs[snapshot.runId]?.cancel()
        runtimes[snapshot.runId] = runtime()
        jobs[snapshot.runId] = scope.launch {
            runtimes[snapshot.runId]!!.continueRun(definition, snapshot, parentSessionId)
        }
    }

    suspend fun cancel(runId: String): Pair<Boolean, String?> {
        val job = jobs.remove(runId)
        job?.cancel()
        val snapshot = store.readSnapshot(runId)
        if (snapshot != null && snapshot.status != WorkflowRunStatus.cancelled &&
            snapshot.status != WorkflowRunStatus.completed
        ) {
            val cancelled = WorkflowLifecycle.cancel(
                snapshot,
                timestamp = java.time.Instant.now().toString(),
            ).snapshot
            store.writeSnapshot(cancelled)
        }
        return (job != null || snapshot != null) to null
    }

    suspend fun getRun(runId: String): WorkflowRunSnapshot? = store.readSnapshot(runId)

    suspend fun listRuns(limit: Int = 50) = store.listRuns(limit)

    suspend fun events(runId: String) = store.events(runId)

    suspend fun schedulerState(runId: String) =
        store.readSnapshot(runId)?.let { deriveWorkflowSchedulerState(it.graph) }

    suspend fun sessionLinks(runId: String) = store.readSnapshot(runId)?.sessionLinks.orEmpty()

    suspend fun listDefinitions(): List<WorkflowDefinition> {
        val stored = store.listDefinitions()
        val hasExpert = stored.any { it.definitionId == ExpertWorkflow.DEFINITION_ID }
        return if (hasExpert) stored else listOf(ExpertWorkflow.definition()) + stored
    }

    suspend fun readDefinition(id: String): WorkflowDefinition? =
        store.readDefinition(id)
            ?: if (id == ExpertWorkflow.DEFINITION_ID) ExpertWorkflow.definition() else null

    suspend fun saveDefinition(definition: WorkflowDefinition): Pair<WorkflowDefinition?, String?> {
        val errors = definition.validate()
        if (errors.isNotEmpty()) return null to errors.joinToString("\n")
        store.saveDefinition(definition)
        return definition to null
    }

    fun isRunning(runId: String): Boolean = jobs[runId]?.isActive == true
}
