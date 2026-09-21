package com.andmx.agent.workflow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// ZCode workflow/scheduler.ts + node-runner.ts + collection-planner.ts 协程移植。
// 上游共享可变 snapshot；这里用 Mutex 串行化所有快照读写，语义等价。

interface WorkflowAgentRunner {
    suspend fun run(input: WorkflowAgentInput): WorkflowAgentResult
}

data class WorkflowAgentInput(
    val activityId: String,
    val node: WorkflowGraphNode? = null,
    val collection: WorkflowGraphCollection? = null,
    val phase: String,
    val prompt: String,
    val runId: String,
    val task: String,
    val parentSessionId: String? = null,
    val onChildSessionStarted: (suspend (sessionId: String, model: String?) -> Unit)? = null,
)

data class WorkflowAgentResult(
    val response: String,
    val sessionId: String = "",
    val model: String? = null,
)

interface WorkflowStoreSink {
    suspend fun writeSnapshot(snapshot: WorkflowRunSnapshot)
    suspend fun readSnapshot(runId: String): WorkflowRunSnapshot?
    suspend fun writeArtifact(runId: String, relativePath: String, content: String): String
    suspend fun writeReport(runId: String, content: String): String =
        writeArtifact(runId, "report.md", content)
    suspend fun appendEvent(event: WorkflowEvent)
}

class WorkflowGraphScheduler(
    private val store: WorkflowStoreSink,
    private val runner: WorkflowAgentRunner,
    private val plannerRunner: WorkflowAgentRunner? = null,
    private val createActivityId: () -> String = { "act_${java.util.UUID.randomUUID().toString().take(12)}" },
    private val now: () -> String = { java.time.Instant.now().toString() },
) {
    data class RunResult(val reason: String, val snapshot: WorkflowRunSnapshot, val status: String)

    private val snapshotMutex = Mutex()
    private var snapshot: WorkflowRunSnapshot? = null

    private suspend fun getSnapshot(): WorkflowRunSnapshot = snapshotMutex.withLock { snapshot!! }

    private suspend fun setSnapshot(next: WorkflowRunSnapshot): WorkflowRunSnapshot {
        snapshotMutex.withLock { snapshot = next }
        return next
    }

    private suspend fun mutateSnapshot(block: (WorkflowRunSnapshot) -> WorkflowRunSnapshot): WorkflowRunSnapshot {
        val next = snapshotMutex.withLock { block(snapshot!!).also { snapshot = it } }
        store.writeSnapshot(next)
        return next
    }

    private suspend fun emit(snapshot: WorkflowRunSnapshot, type: String, message: String? = null, phase: String? = null, nodeId: String? = null, payload: kotlinx.serialization.json.JsonObject? = null) {
        store.appendEvent(
            WorkflowEvent(
                kind = snapshot.kind, message = message, nodeId = nodeId, payload = payload,
                phase = phase, runId = snapshot.runId, timestamp = now(), type = type,
            ),
        )
    }

    suspend fun run(
        options: SchedulerRunOptions,
    ): RunResult = coroutineScope {
        val executableNodeIds = (options.executableNodeIds
            ?: options.snapshot.graph.nodes.map { it.id }).toMutableSet()

        val repair = WorkflowLifecycle.reconcileForResume(
            options.snapshot, executableNodeIds, resetPhases = false, timestamp = now(),
        )
        snapshot = repair.snapshot
        if (repair.changed) store.writeSnapshot(repair.snapshot)

        val maxConcurrent = options.snapshot.strategy.executor.maxConcurrentLoops.coerceAtLeast(1)
        val maxConsecutiveErrors = options.snapshot.strategy.executor.maxConsecutiveErrors.coerceAtLeast(1)
        val active = mutableMapOf<String, Deferred<NodeRunOutcome>>()
        var consecutiveErrors = 0
        var frontierKey = ""

        while (true) {
            val plannerResult = checkCollectionPlanners(executableNodeIds, options)
            for (id in plannerResult) executableNodeIds += id

            frontierKey = emitFrontierChangedIfNeeded(executableNodeIds, frontierKey)

            val current = getSnapshot()
            if (
                WorkflowGraphOps.areExecutableNodesComplete(
                    current.graph, executableNodeIds, plannerRunner != null,
                ) && active.isEmpty()
            ) {
                emit(current, "executor_completed", "${options.phase} scheduler completed.", options.phase)
                return@coroutineScope RunResult("completed", current, "completed")
            }
            if (consecutiveErrors >= maxConsecutiveErrors) {
                if (active.isNotEmpty()) {
                    active.values.awaitAll()
                    active.clear()
                }
                emit(current, "executor_paused", "${options.phase} scheduler paused after $consecutiveErrors consecutive node error(s).", options.phase)
                return@coroutineScope RunResult("error_threshold", getSnapshot(), "paused")
            }
            val readyNodes = WorkflowGraphOps.orderedReadyExecutableNodes(current.graph, executableNodeIds)
                .filter { it.id !in active }
            var dispatched = 0
            for (node in readyNodes) {
                if (active.size >= maxConcurrent) break
                val deferred = async { runWorkflowNode(node, options, maxConsecutiveErrors) }
                active[node.id] = deferred
                deferred.awaitStarted()
                dispatched++
            }
            if (dispatched > 0) continue
            if (active.isNotEmpty()) {
                val outcome = select<NodeRunOutcome> {
                    active.values.forEach { it.onAwait { value -> value } }
                }
                active.remove(outcome.nodeId)
                consecutiveErrors = if (outcome.ok) 0 else consecutiveErrors + 1
                continue
            }
            emit(
                current, "executor_paused",
                "${options.phase} scheduler paused because pending nodes are blocked.",
                options.phase,
            )
            return@coroutineScope RunResult("deadlock", getSnapshot(), "paused")
        }
        error("scheduler loop exited without a terminal reason")
    }

    private suspend fun emitFrontierChangedIfNeeded(
        executableNodeIds: Set<String>,
        previousKey: String,
    ): String {
        val current = getSnapshot()
        val ready = WorkflowGraphOps.readyExecutableNodes(current.graph, executableNodeIds).map { it.id }
        val activeIds = current.graph.nodes
            .filter { it.id in executableNodeIds && it.status == WorkflowNodeStatus.active }
            .map { it.id }
        val blocked = WorkflowGraphOps.blockedExecutableNodes(current.graph, executableNodeIds)
        val key = "$ready|$activeIds|$blocked"
        if (key == previousKey) return previousKey
        emit(current, "frontier_changed", "Workflow scheduler frontier changed.", payload = null)
        return key
    }

    private suspend fun runWorkflowNode(
        node: WorkflowGraphNode,
        options: SchedulerRunOptions,
        maxAttempts: Int,
    ): NodeRunOutcome {
        val activityId = createActivityId()
        val startedAt = now()
        val inputArtifactPaths = getSnapshot().artifacts.map { it.path }
        mutateSnapshot { snap ->
            WorkflowGraphOps.upsertActivity(
                WorkflowGraphOps.updateGraphNode(snap, node.id, status = WorkflowNodeStatus.active, clearError = true),
                WorkflowActivitySnapshot(
                    activityId = activityId,
                    inputArtifactPaths = inputArtifactPaths,
                    kind = WorkflowActivityKind.agent_session,
                    nodeId = node.id,
                    phase = options.phase,
                    startedAt = startedAt,
                    status = WorkflowNodeStatus.active,
                ),
                now(),
            )
        }
        emit(getSnapshot(), "node_started", "Node started: ${node.title}", options.phase, node.id)
        return try {
            val result = runner.run(
                WorkflowAgentInput(
                    activityId = activityId,
                    node = node,
                    phase = options.phase,
                    prompt = options.buildPrompt(node, getSnapshot()),
                    runId = getSnapshot().runId,
                    task = getSnapshot().task,
                    parentSessionId = options.parentSessionId,
                    onChildSessionStarted = { sessionId, model ->
                        val latest = getSnapshot()
                        val activity = latest.activities.find { it.activityId == activityId }
                        if (activity?.status == WorkflowNodeStatus.active) {
                            mutateSnapshot { snap ->
                                WorkflowGraphOps.upsertActivity(
                                    snap,
                                    activity.copy(model = model, sessionId = sessionId),
                                    now(),
                                )
                            }
                            emit(getSnapshot(), "workflow_session_linked", "Workflow session linked: $sessionId", options.phase, node.id)
                        }
                    },
                ),
            )
            val artifactPath = "${options.artifactDirectory}/${safeArtifactName(node.id)}.md"
            val rel = store.writeArtifact(getSnapshot().runId, artifactPath, result.response)
            mutateSnapshot { snap ->
                WorkflowGraphOps.addArtifact(
                    WorkflowGraphOps.upsertActivity(
                        WorkflowGraphOps.updateGraphNode(
                            snap, node.id, status = WorkflowNodeStatus.completed,
                            attempts = node.attempts, clearError = true,
                        ),
                        WorkflowActivitySnapshot(
                            activityId = activityId, artifactPath = rel,
                            completedAt = now(), inputArtifactPaths = inputArtifactPaths,
                            kind = WorkflowActivityKind.agent_session, model = result.model,
                            nodeId = node.id, outputArtifactPaths = listOf(rel),
                            phase = options.phase, sessionId = result.sessionId,
                            startedAt = startedAt, status = WorkflowNodeStatus.completed,
                        ),
                        now(),
                    ),
                    WorkflowArtifact(
                        contentType = "text/markdown", createdAt = now(),
                        label = node.title, path = rel, phase = options.phase,
                    ),
                    now(),
                )
            }
            emit(getSnapshot(), "artifact_written", "Artifact written: $rel", options.phase, node.id)
            emit(getSnapshot(), "node_completed", "Node completed: ${node.title}", options.phase, node.id)
            NodeRunOutcome(node.id, ok = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val attempts = (node.attempts ?: 0) + 1
            val nextStatus = if (attempts >= maxAttempts) WorkflowNodeStatus.failed else WorkflowNodeStatus.pending
            mutateSnapshot { snap ->
                val current = snap.activities.find { it.activityId == activityId }
                WorkflowGraphOps.upsertActivity(
                    WorkflowGraphOps.updateGraphNode(
                        snap, node.id, status = nextStatus,
                        attempts = attempts, error = e.message ?: e.toString(),
                    ),
                    WorkflowActivitySnapshot(
                        activityId = activityId, completedAt = now(),
                        error = e.message ?: e.toString(), inputArtifactPaths = inputArtifactPaths,
                        kind = WorkflowActivityKind.agent_session, model = current?.model,
                        nodeId = node.id, phase = options.phase,
                        sessionId = current?.sessionId, startedAt = startedAt,
                        status = nextStatus,
                    ),
                    now(),
                )
            }
            emit(getSnapshot(), "node_failed", e.message ?: e.toString(), options.phase, node.id)
            NodeRunOutcome(node.id, ok = false)
        }
    }

    private suspend fun Deferred<NodeRunOutcome>.awaitStarted() {
        // 上游 promise.started 语义：等节点进入 active 再继续派发。
        // runWorkflowNode 首个 suspend 点即完成 active 标记，靠 async 内顺序保证；
        // 这里 yield 一轮让协程跑到首个挂起点。
        kotlinx.coroutines.yield()
    }

    private data class NodeRunOutcome(val nodeId: String, val ok: Boolean)

    private suspend fun checkCollectionPlanners(
        executableNodeIds: MutableSet<String>,
        options: SchedulerRunOptions,
    ): List<String> {
        val planner = plannerRunner ?: return emptyList()
        val addedNodeIds = mutableListOf<String>()
        for (collection in WorkflowGraphOps.graphCollections(getSnapshot().graph)) {
            if (collection.explorable != true ||
                !WorkflowGraphOps.isCollectionInPhase(collection, getSnapshot().graph, executableNodeIds, options.phase)
            ) continue
            val collectionNodeIds = WorkflowGraphOps.collectionNodeIds(collection, getSnapshot().graph)
            val frontier = WorkflowGraphOps.collectionFrontier(getSnapshot().graph, collection)
            val completedNodeIds = collectionNodeIds.filter {
                WorkflowGraphOps.nodeById(getSnapshot().graph, it)?.status == WorkflowNodeStatus.completed
            }
            val unseen = completedNodeIds.filter { it !in collection.analyzedNodeIds.orEmpty() }
            if (unseen.isNotEmpty()) {
                mutateSnapshot { snap ->
                    WorkflowGraphOps.updateGraphCollection(snap, collection.collectionId, {
                        it.copy(lastCompletionAt = now())
                    }, now())
                }
            }
            val latest = WorkflowGraphOps.graphCollections(getSnapshot().graph)
                .find { it.collectionId == collection.collectionId } ?: continue
            if (latest.exhausted == true || latest.status == WorkflowGraphCollectionStatus.exhausted) continue
            val deferInitial = frontier > 0 && (latest.plannerRuns ?: 0) == 0 &&
                latest.analyzedNodeIds.orEmpty().isEmpty() && unseen.isEmpty()
            val target = latest.frontierTarget ?: getSnapshot().strategy.executor.frontierTarget
            if (deferInitial || (frontier >= target && unseen.isEmpty())) {
                mutateSnapshot { snap ->
                    WorkflowGraphOps.updateGraphCollection(snap, latest.collectionId, {
                        it.copy(status = WorkflowGraphCollectionStatus.active)
                    }, now())
                }
                continue
            }
            if ((latest.plannerRuns ?: 0) >= getSnapshot().strategy.executor.maxPlannerRuns ||
                (latest.errorCount ?: 0) >= getSnapshot().strategy.executor.maxConsecutiveErrors
            ) {
                mutateSnapshot { snap ->
                    WorkflowGraphOps.updateGraphCollection(snap, latest.collectionId, {
                        it.copy(exhausted = true, status = WorkflowGraphCollectionStatus.exhausted)
                    }, now())
                }
                emit(getSnapshot(), "collection_exhausted", "Collection exhausted: ${latest.collectionId}", options.phase)
                continue
            }
            addedNodeIds += runCollectionPlanner(latest, unseen, options, planner)
        }
        return addedNodeIds
    }

    private suspend fun runCollectionPlanner(
        collection: WorkflowGraphCollection,
        unseenCompletions: List<String>,
        options: SchedulerRunOptions,
        planner: WorkflowAgentRunner,
    ): List<String> {
        val activityId = createActivityId()
        val startedAt = now()
        val plannerRuns = (collection.plannerRuns ?: 0) + 1
        val inputArtifactPaths = getSnapshot().artifacts.map { it.path }
        mutateSnapshot { snap ->
            WorkflowGraphOps.upsertActivity(
                WorkflowGraphOps.updateGraphCollection(snap, collection.collectionId, {
                    it.copy(plannerRuns = plannerRuns, status = WorkflowGraphCollectionStatus.active)
                }, now()),
                WorkflowActivitySnapshot(
                    activityId = activityId, inputArtifactPaths = inputArtifactPaths,
                    kind = WorkflowActivityKind.planner_agent, phase = options.phase,
                    startedAt = startedAt, status = WorkflowNodeStatus.active,
                ),
                now(),
            )
        }
        emit(getSnapshot(), "planner_started", "Planner started for collection: ${collection.collectionId}", options.phase)
        return try {
            val result = planner.run(
                WorkflowAgentInput(
                    activityId = activityId,
                    collection = collection,
                    phase = options.phase,
                    prompt = options.plannerPrompt(collection, getSnapshot()),
                    runId = getSnapshot().runId,
                    task = getSnapshot().task,
                    parentSessionId = options.parentSessionId,
                ),
            )
            val plannerResult = WorkflowParsers.parsePlannerResult(result.response, options.phase)
            val rel = store.writeArtifact(
                getSnapshot().runId,
                "${options.artifactDirectory}/planners/${safeArtifactName(collection.collectionId)}-$plannerRuns.md",
                result.response,
            )
            val expanded = applyPlannerExpansion(collection, plannerResult, unseenCompletions, options.phase)
            mutateSnapshot { snap ->
                WorkflowGraphOps.upsertActivity(
                    WorkflowGraphOps.addArtifact(
                        expanded.snapshot,
                        WorkflowArtifact(
                            contentType = "text/markdown", createdAt = now(),
                            label = "Planner ${collection.collectionId}", path = rel, phase = options.phase,
                        ),
                        now(),
                    ),
                    WorkflowActivitySnapshot(
                        activityId = activityId, artifactPath = rel, completedAt = now(),
                        inputArtifactPaths = inputArtifactPaths,
                        kind = WorkflowActivityKind.planner_agent, model = result.model,
                        outputArtifactPaths = listOf(rel), phase = options.phase,
                        sessionId = result.sessionId, startedAt = startedAt,
                        status = WorkflowNodeStatus.completed,
                    ),
                    now(),
                )
            }
            emit(getSnapshot(), "planner_completed", "Planner completed for collection: ${collection.collectionId}", options.phase)
            expanded.addedNodeIds
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val errorCount = (collection.errorCount ?: 0) + 1
            val exhausted = errorCount >= getSnapshot().strategy.executor.maxConsecutiveErrors
            mutateSnapshot { snap ->
                WorkflowGraphOps.upsertActivity(
                    WorkflowGraphOps.updateGraphCollection(snap, collection.collectionId, {
                        it.copy(
                            errorCount = errorCount, exhausted = exhausted,
                            status = if (exhausted) WorkflowGraphCollectionStatus.exhausted
                            else WorkflowGraphCollectionStatus.draining,
                        )
                    }, now()),
                    WorkflowActivitySnapshot(
                        activityId = activityId, completedAt = now(),
                        error = e.message ?: e.toString(), inputArtifactPaths = inputArtifactPaths,
                        kind = WorkflowActivityKind.planner_agent, phase = options.phase,
                        startedAt = startedAt, status = WorkflowNodeStatus.failed,
                    ),
                    now(),
                )
            }
            emit(getSnapshot(), "planner_failed", e.message ?: e.toString(), options.phase)
            if (exhausted) {
                emit(getSnapshot(), "collection_exhausted", "Collection exhausted: ${collection.collectionId}", options.phase)
            }
            emptyList()
        }
    }

    private class PlannerExpansion(
        val snapshot: WorkflowRunSnapshot,
        val addedNodeIds: List<String>,
    )

    private suspend fun applyPlannerExpansion(
        collection: WorkflowGraphCollection,
        result: WorkflowGraphPlannerResult,
        unseenCompletions: List<String>,
        phase: String,
    ): PlannerExpansion {
        val current = getSnapshot()
        val seed = WorkflowGraphSeed(
            edges = result.edges,
            nodes = result.nodes,
            reasoning = result.reasoning,
        )
        var addedNodeIds = emptyList<String>()
        var next = if (seed.nodes.isNotEmpty() || seed.edges.isNotEmpty()) {
            val applied = WorkflowLifecycle.applyGraphSeed(current, seed, phase, now())
            addedNodeIds = applied.addedNodes.map { it.id }
            applied.snapshot
        } else current
        val memberIds = result.collectionNodeIds?.toSet()
        if (memberIds != null) {
            next = WorkflowGraphOps.updateGraphCollection(next, collection.collectionId, {
                it.copy(nodeIds = (it.nodeIds.orEmpty() + memberIds).distinct())
            }, now())
        }
        if (result.exhausted == true) {
            next = WorkflowGraphOps.updateGraphCollection(next, collection.collectionId, {
                it.copy(exhausted = true, status = WorkflowGraphCollectionStatus.exhausted)
            }, now())
        }
        next = WorkflowGraphOps.updateGraphCollection(next, collection.collectionId, {
            it.copy(analyzedNodeIds = (it.analyzedNodeIds.orEmpty() + unseenCompletions).distinct())
        }, now())
        return PlannerExpansion(next, addedNodeIds)
    }

    companion object {
        fun safeArtifactName(value: String): String =
            value.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(100).ifBlank { "node" }
    }
}

data class SchedulerRunOptions(
    val artifactDirectory: String = "artifacts/exec",
    val buildPrompt: (WorkflowGraphNode, WorkflowRunSnapshot) -> String = { node, snap ->
        WorkflowPrompts.buildDefaultNodePrompt(snap, node, "")
    },
    val plannerPrompt: (WorkflowGraphCollection, WorkflowRunSnapshot) -> String = { col, snap ->
        WorkflowPrompts.buildDefaultPlannerPrompt(snap, col, "")
    },
    val cwd: String,
    val executableNodeIds: Iterable<String>? = null,
    val parentSessionId: String? = null,
    val phase: String,
    val snapshot: WorkflowRunSnapshot,
)
