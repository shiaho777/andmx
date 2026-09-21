package com.andmx.agent.workflow

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

// ZCode contracts/workflow/index.ts 对齐：definition + run snapshot + graph + scheduler 派生态。
// 序列化字段名与上游 schema 逐字一致，便于跨端读写同一快照格式。

val workflowJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

enum class WorkflowNodeStatus { pending, active, completed, failed, skipped, cancelled }
enum class WorkflowRunStatus { pending, running, paused, completed, failed, cancelled }
enum class WorkflowGraphCollectionStatus { active, draining, exhausted }
enum class WorkflowPhaseBehavior { agent, scheduled_graph, critic, complete }
enum class WorkflowActivityKind { agent_session, planner_agent, subplanner_agent, actor_agent, critic_agent }

@Serializable
data class WorkflowClarifyStrategy(
    val confidenceThreshold: Double,
    val maxRounds: Int,
    val minRounds: Int,
)

@Serializable
data class WorkflowExecutorStrategy(
    val drainingChangeHours: Double,
    val frontierTarget: Int,
    val maxConcurrentLoops: Int,
    val maxConsecutiveErrors: Int,
    val maxPlannerRuns: Int,
)

@Serializable
data class WorkflowFinalCriticStrategy(val maxIterations: Int)

@Serializable
data class WorkflowReactLoopStrategy(val maxRounds: Int)

@Serializable
data class WorkflowStrategy(
    val clarify: WorkflowClarifyStrategy,
    val executor: WorkflowExecutorStrategy,
    val finalCritic: WorkflowFinalCriticStrategy,
    val reactLoop: WorkflowReactLoopStrategy,
) {
    companion object {
        val DEFAULT = WorkflowStrategy(
            clarify = WorkflowClarifyStrategy(0.8, 3, 1),
            executor = WorkflowExecutorStrategy(1.0, 3, 2, 3, 10),
            finalCritic = WorkflowFinalCriticStrategy(3),
            reactLoop = WorkflowReactLoopStrategy(30),
        )
    }
}

@Serializable
data class WorkflowGraphSeedSource(
    val gateAfterPhase: String? = null,
    val targetPhase: String,
)

@Serializable
data class WorkflowNodePromptsFromArtifact(val targetPhase: String)

@Serializable
data class WorkflowPhaseDefinition(
    val artifactPath: String? = null,
    val behavior: WorkflowPhaseBehavior = WorkflowPhaseBehavior.agent,
    val description: String,
    val nodePromptsFromArtifact: WorkflowNodePromptsFromArtifact? = null,
    val phase: String,
    val seedGraphFromArtifact: WorkflowGraphSeedSource? = null,
    val title: String,
)

@Serializable
data class WorkflowDefinition(
    val definitionId: String,
    val definitionVersion: String,
    val description: String? = null,
    val kind: String,
    val phaseOrder: List<String>,
    val phases: List<WorkflowPhaseDefinition>,
    val strategy: WorkflowStrategy,
    val title: String,
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        val seenPhases = LinkedHashSet<String>()
        for (p in phases) {
            if (!seenPhases.add(p.phase)) {
                errors += "Duplicate workflow phase definition: ${p.phase}"
            }
        }
        val seenOrder = LinkedHashSet<String>()
        for (id in phaseOrder) {
            if (!seenOrder.add(id)) {
                errors += "Duplicate workflow phase order entry: $id"
            }
            if (id !in seenPhases) {
                errors += "Workflow phaseOrder references unknown phase: $id"
            }
        }
        for (id in seenPhases) {
            if (id !in seenOrder) {
                errors += "Workflow phase definition is missing from phaseOrder: $id"
            }
        }
        return errors
    }

    fun phaseMap(): Map<String, WorkflowPhaseDefinition> = phases.associateBy { it.phase }
}

@Serializable
data class WorkflowArtifact(
    val contentType: String,
    val createdAt: String,
    val label: String,
    val path: String,
    val phase: String? = null,
)

@Serializable
data class WorkflowPhaseSnapshot(
    val artifactPath: String? = null,
    val activityId: String? = null,
    val completedAt: String? = null,
    val error: String? = null,
    val phase: String,
    val sessionId: String? = null,
    val startedAt: String? = null,
    val status: WorkflowNodeStatus,
    val traceId: String? = null,
    val turnId: String? = null,
)

@Serializable
data class WorkflowFailure(
    val activityId: String? = null,
    val code: String? = null,
    val kind: String,
    val message: String,
    val nodeId: String? = null,
    val phase: String? = null,
    val recoverable: Boolean,
    val retryable: Boolean,
    val sessionId: String? = null,
    val traceId: String? = null,
    val turnId: String? = null,
)

@Serializable
data class WorkflowRecoveryAction(
    val action: String,
    val activityId: String? = null,
    val destructive: Boolean? = null,
    val label: String,
    val nodeId: String? = null,
    val phase: String? = null,
)

@Serializable
data class WorkflowSessionLink(
    val activityId: String,
    val attempt: Int,
    val completedAt: String? = null,
    val kind: WorkflowActivityKind,
    val model: String? = null,
    val nodeId: String? = null,
    val parentSessionId: String? = null,
    val phase: String,
    val runId: String,
    val sessionId: String? = null,
    val startedAt: String,
    val status: String,
    val traceId: String? = null,
    val turnId: String? = null,
)

@Serializable
data class WorkflowActivitySnapshot(
    val activityId: String,
    val artifactPath: String? = null,
    val completedAt: String? = null,
    val error: String? = null,
    val inputArtifactPaths: List<String> = emptyList(),
    val kind: WorkflowActivityKind,
    val model: String? = null,
    val nodeId: String? = null,
    val outputArtifactPaths: List<String> = emptyList(),
    val parentSessionId: String? = null,
    val phase: String,
    val sessionId: String? = null,
    val startedAt: String,
    val status: WorkflowNodeStatus,
    val traceId: String? = null,
    val turnId: String? = null,
)

@Serializable
data class WorkflowGraphNode(
    val collectionId: String? = null,
    val id: String,
    val attempts: Int? = null,
    val dependsOn: List<String> = emptyList(),
    val description: String? = null,
    val error: String? = null,
    val kind: String = "phase",
    val phase: String? = null,
    val prompt: String? = null,
    val reopenAttempts: Int? = null,
    val status: WorkflowNodeStatus,
    val title: String,
)

@Serializable
data class WorkflowGraphEdge(val from: String, val to: String) {
    fun edgeId(): String = "$from->$to"
}

@Serializable
data class WorkflowGraphCollection(
    val analyzedNodeIds: List<String>? = null,
    val collectionId: String,
    val errorCount: Int? = null,
    val exhausted: Boolean? = null,
    val explorable: Boolean? = null,
    val frontierTarget: Int? = null,
    val goal: String? = null,
    val lastCompletionAt: String? = null,
    val lastGraphChangeAt: String? = null,
    val metric: String? = null,
    val nodeIds: List<String>? = null,
    val phase: String? = null,
    val plannerRuns: Int? = null,
    val status: WorkflowGraphCollectionStatus? = null,
    val title: String? = null,
)

@Serializable
data class WorkflowGraph(
    val collections: List<WorkflowGraphCollection>? = null,
    val edges: List<WorkflowGraphEdge>,
    val nodes: List<WorkflowGraphNode>,
)

@Serializable
data class WorkflowGraphPlannerNode(
    val collectionId: String? = null,
    val dependsOn: List<String> = emptyList(),
    val description: String? = null,
    val id: String,
    val kind: String = "task",
    val phase: String? = null,
    val prompt: String? = null,
    val title: String,
)

@Serializable
data class WorkflowGraphPlannerResult(
    val collectionNodeIds: List<String>? = null,
    val edges: List<WorkflowGraphEdge> = emptyList(),
    val exhausted: Boolean? = null,
    val nodes: List<WorkflowGraphPlannerNode> = emptyList(),
    val reasoning: String? = null,
)

@Serializable
data class WorkflowGraphSeedCollection(
    val collectionId: String,
    val explorable: Boolean? = null,
    val frontierTarget: Int? = null,
    val goal: String? = null,
    val metric: String? = null,
    val nodeIds: List<String> = emptyList(),
    val phase: String? = null,
    val title: String? = null,
)

@Serializable
data class WorkflowGraphSeed(
    val collections: List<WorkflowGraphSeedCollection> = emptyList(),
    val edges: List<WorkflowGraphEdge> = emptyList(),
    val nodes: List<WorkflowGraphPlannerNode> = emptyList(),
    val reasoning: String? = null,
)

@Serializable
data class WorkflowNodePromptUpdate(
    val description: String? = null,
    val id: String,
    val prompt: String? = null,
    val title: String? = null,
) {
    init {
        require(description != null || prompt != null || title != null) {
            "Workflow node prompt update must include prompt, description, or title"
        }
    }
}

@Serializable
data class WorkflowNodePromptUpdateSet(
    val nodes: List<WorkflowNodePromptUpdate> = emptyList(),
    val reasoning: String? = null,
)

@Serializable
data class WorkflowCriticReopenProposal(
    val nodeId: String,
    val reason: String,
    val severity: String? = null,
)

@Serializable
data class WorkflowCriticResult(
    val acceptanceGaps: List<String> = emptyList(),
    val reasoning: String = "",
    val reopenProposals: List<WorkflowCriticReopenProposal> = emptyList(),
    val verdict: String,
)

@Serializable
data class WorkflowRunSnapshot(
    val activities: List<WorkflowActivitySnapshot> = emptyList(),
    val artifacts: List<WorkflowArtifact>,
    val completedAt: String? = null,
    val createdAt: String,
    val currentPhase: String? = null,
    val cwd: String,
    val definitionId: String? = null,
    val definitionVersion: String? = null,
    val graph: WorkflowGraph,
    val kind: String,
    val phaseOrder: List<String>,
    val phases: List<WorkflowPhaseSnapshot>,
    val failure: WorkflowFailure? = null,
    val pauseReason: String? = null,
    val reportPath: String? = null,
    val recoveryActions: List<WorkflowRecoveryAction> = emptyList(),
    val runId: String,
    val schemaVersion: Int = 1,
    val sessionId: String? = null,
    val sessionLinks: List<WorkflowSessionLink> = emptyList(),
    val startedAt: String? = null,
    val status: WorkflowRunStatus,
    val strategy: WorkflowStrategy,
    val task: String,
    val traceId: String? = null,
    val updatedAt: String,
)

@Serializable
data class WorkflowEvent(
    val kind: String,
    val message: String? = null,
    val nodeId: String? = null,
    val payload: JsonObject? = null,
    val phase: String? = null,
    val runId: String,
    val timestamp: String,
    val type: String,
)

// ── deriveWorkflowSchedulerState（上游 contracts 派生函数移植）──────────

val TERMINAL_DEPENDENCY_STATUSES = setOf(
    WorkflowNodeStatus.cancelled,
    WorkflowNodeStatus.completed,
    WorkflowNodeStatus.failed,
    WorkflowNodeStatus.skipped,
)

data class WorkflowSchedulerDerivedNode(
    val blockedBy: List<String>,
    val collectionIds: List<String>,
    val incoming: List<String>,
    val node: WorkflowGraphNode,
    val outgoing: List<String>,
    val ready: Boolean,
)

data class WorkflowSchedulerCollectionState(
    val activeNodeIds: List<String>,
    val collection: WorkflowGraphCollection,
    val completedNodeIds: List<String>,
    val errorCount: Int,
    val exhausted: Boolean,
    val failedNodeIds: List<String>,
    val frontier: Int,
    val frontierTarget: Int?,
    val pendingNodeIds: List<String>,
    val plannerRuns: Int,
    val readyNodeIds: List<String>,
    val status: WorkflowGraphCollectionStatus,
)

data class WorkflowSchedulerActiveActivity(
    val activityId: String,
    val nodeId: String? = null,
    val phase: String,
    val sessionId: String? = null,
    val traceId: String? = null,
    val turnId: String? = null,
)

data class WorkflowSchedulerState(
    val activeActivities: List<WorkflowSchedulerActiveActivity>,
    val activeChildSessionIds: List<String>,
    val activeNodeIds: List<String>,
    val blockedNodes: List<Pair<String, List<String>>>,
    val completed: Int,
    val failed: Int,
    val pending: Int,
    val ready: Int,
    val active: Int,
    val blocked: Int,
    val total: Int,
    val collectionStates: List<WorkflowSchedulerCollectionState>,
    val nodes: List<WorkflowSchedulerDerivedNode>,
    val readyNodeIds: List<String>,
)

fun deriveWorkflowSchedulerState(graph: WorkflowGraph): WorkflowSchedulerState {
    val nodesById = graph.nodes.associateBy { it.id }
    val incomingById = mutableMapOf<String, MutableList<String>>()
    val outgoingById = mutableMapOf<String, MutableList<String>>()
    for (node in graph.nodes) {
        incomingById[node.id] = node.dependsOn.toMutableList()
        outgoingById[node.id] = mutableListOf()
    }
    for (edge in graph.edges) {
        incomingById.getOrPut(edge.to) { mutableListOf() }.add(edge.from)
        outgoingById.getOrPut(edge.from) { mutableListOf() }.add(edge.to)
    }
    val collections = graph.collections.orEmpty()
    val collectionIdsByNodeId = mutableMapOf<String, MutableList<String>>()
    for (collection in collections) {
        for (nodeId in collection.nodeIds.orEmpty()) {
            collectionIdsByNodeId.getOrPut(nodeId) { mutableListOf() }.add(collection.collectionId)
        }
    }
    for (node in graph.nodes) {
        val cid = node.collectionId ?: continue
        val list = collectionIdsByNodeId.getOrPut(node.id) { mutableListOf() }
        if (cid !in list) list.add(cid)
    }
    val nodes = graph.nodes.map { node ->
        val incoming = incomingById[node.id].orEmpty().distinct()
        val blockedBy = incoming.filter { depId ->
            val dep = nodesById[depId]
            dep == null || dep.status !in TERMINAL_DEPENDENCY_STATUSES
        }
        WorkflowSchedulerDerivedNode(
            blockedBy = blockedBy,
            collectionIds = collectionIdsByNodeId[node.id].orEmpty(),
            incoming = incoming,
            node = node,
            outgoing = outgoingById[node.id].orEmpty().distinct(),
            ready = node.status == WorkflowNodeStatus.pending && blockedBy.isEmpty(),
        )
    }
    val activeNodeIds = nodes.filter { it.node.status == WorkflowNodeStatus.active }.map { it.node.id }
    val blockedNodes = nodes
        .filter { it.node.status == WorkflowNodeStatus.pending && it.blockedBy.isNotEmpty() }
        .map { it.node.id to it.blockedBy }
    val readyNodeIds = nodes.filter { it.ready }.map { it.node.id }
    val collectionStates = collections.map { collection ->
        val nodeIds = (
            collection.nodeIds.orEmpty() +
                graph.nodes.filter { it.collectionId == collection.collectionId }.map { it.id }
            ).distinct()
        val activeC = nodeIds.filter { nodesById[it]?.status == WorkflowNodeStatus.active }
        val pendingC = nodeIds.filter { nodesById[it]?.status == WorkflowNodeStatus.pending }
        val completedC = nodeIds.filter { nodesById[it]?.status == WorkflowNodeStatus.completed }
        val failedC = nodeIds.filter { nodesById[it]?.status == WorkflowNodeStatus.failed }
        WorkflowSchedulerCollectionState(
            activeNodeIds = activeC,
            collection = collection,
            completedNodeIds = completedC,
            errorCount = collection.errorCount ?: 0,
            exhausted = collection.exhausted ?: false,
            failedNodeIds = failedC,
            frontier = activeC.size + pendingC.size,
            frontierTarget = collection.frontierTarget,
            pendingNodeIds = pendingC,
            plannerRuns = collection.plannerRuns ?: 0,
            readyNodeIds = readyNodeIds.filter { it in nodeIds },
            status = collection.status ?: WorkflowGraphCollectionStatus.active,
        )
    }
    return WorkflowSchedulerState(
        activeActivities = emptyList(),
        activeChildSessionIds = emptyList(),
        activeNodeIds = activeNodeIds,
        blockedNodes = blockedNodes,
        completed = nodes.count { it.node.status == WorkflowNodeStatus.completed },
        failed = nodes.count { it.node.status == WorkflowNodeStatus.failed },
        pending = nodes.count { it.node.status == WorkflowNodeStatus.pending },
        ready = readyNodeIds.size,
        active = activeNodeIds.size,
        blocked = blockedNodes.size,
        total = nodes.size,
        collectionStates = collectionStates,
        nodes = nodes,
        readyNodeIds = readyNodeIds,
    )
}

fun deriveWorkflowSessionLinks(
    activities: List<WorkflowActivitySnapshot>,
    runId: String,
): List<WorkflowSessionLink> {
    val attemptByScope = mutableMapOf<String, Int>()
    return activities.map { activity ->
        val scope = listOf(activity.phase, activity.nodeId ?: "phase:${activity.phase}", activity.kind).joinToString(":")
        val attempt = (attemptByScope[scope] ?: 0) + 1
        attemptByScope[scope] = attempt
        WorkflowSessionLink(
            activityId = activity.activityId,
            attempt = attempt,
            completedAt = activity.completedAt,
            kind = activity.kind,
            model = activity.model,
            nodeId = activity.nodeId,
            parentSessionId = activity.parentSessionId,
            phase = activity.phase,
            runId = runId,
            sessionId = activity.sessionId,
            startedAt = activity.startedAt,
            status = when (activity.status) {
                WorkflowNodeStatus.active -> "running"
                WorkflowNodeStatus.completed -> "completed"
                WorkflowNodeStatus.failed -> "failed"
                WorkflowNodeStatus.cancelled, WorkflowNodeStatus.skipped -> "cancelled"
                WorkflowNodeStatus.pending -> "starting"
            },
            traceId = activity.traceId,
            turnId = activity.turnId,
        )
    }
}
