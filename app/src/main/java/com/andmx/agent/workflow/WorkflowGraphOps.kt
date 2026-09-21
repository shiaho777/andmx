package com.andmx.agent.workflow

// ZCode workflow/scheduler/graph.ts 对齐：就绪节点推导与快照图变更辅助。

private val COMPLETED_NODE_STATUSES = setOf(
    WorkflowNodeStatus.cancelled, WorkflowNodeStatus.completed, WorkflowNodeStatus.skipped,
)

object WorkflowGraphOps {

    fun normalizeCollection(collection: WorkflowGraphCollection): WorkflowGraphCollection =
        collection.copy(
            analyzedNodeIds = collection.analyzedNodeIds ?: emptyList(),
            errorCount = collection.errorCount ?: 0,
            exhausted = collection.exhausted ?: false,
            explorable = collection.explorable ?: false,
            nodeIds = collection.nodeIds ?: emptyList(),
            plannerRuns = collection.plannerRuns ?: 0,
            status = collection.status ?: WorkflowGraphCollectionStatus.active,
        )

    fun graphCollections(graph: WorkflowGraph): List<WorkflowGraphCollection> =
        graph.collections.orEmpty().map(::normalizeCollection)

    fun collectionNodeIds(collection: WorkflowGraphCollection, graph: WorkflowGraph): List<String> =
        (collection.nodeIds.orEmpty() +
            graph.nodes.filter { it.collectionId == collection.collectionId }.map { it.id }
            ).distinct()

    fun nodeById(graph: WorkflowGraph, nodeId: String): WorkflowGraphNode? =
        graph.nodes.find { it.id == nodeId }

    fun readyExecutableNodes(
        graph: WorkflowGraph,
        executableNodeIds: Set<String>,
    ): List<WorkflowGraphNode> {
        val ready = deriveWorkflowSchedulerState(graph).readyNodeIds.toSet()
        return graph.nodes.filter { it.id in executableNodeIds && it.id in ready }
    }

    /** 上游 orderedReadyExecutableNodes：工程节点先，探索集合节点按集合轮转。 */
    fun orderedReadyExecutableNodes(
        graph: WorkflowGraph,
        executableNodeIds: Set<String>,
    ): List<WorkflowGraphNode> {
        val readyNodes = readyExecutableNodes(graph, executableNodeIds)
        val explorationNodeIds = mutableSetOf<String>()
        val explorationByCollection = LinkedHashMap<String, ArrayDeque<WorkflowGraphNode>>()
        for (collection in graphCollections(graph)) {
            if (collection.explorable != true || collection.exhausted == true) continue
            val nodeIds = collectionNodeIds(collection, graph).toSet()
            for (node in readyNodes) {
                if (node.id !in nodeIds) continue
                explorationNodeIds += node.id
                explorationByCollection.getOrPut(collection.collectionId) { ArrayDeque() }.add(node)
            }
        }
        val engineering = readyNodes.filter { it.id !in explorationNodeIds }
        val exploration = mutableListOf<WorkflowGraphNode>()
        val ids = explorationByCollection.keys.toMutableList()
        var index = 0
        while (ids.isNotEmpty()) {
            val cid = ids[index % ids.size]
            val nodes = explorationByCollection[cid]!!
            nodes.removeFirstOrNull()?.let(exploration::add)
            if (nodes.isEmpty()) ids.remove(cid) else index++
        }
        return engineering + exploration
    }

    fun blockedExecutableNodes(
        graph: WorkflowGraph,
        executableNodeIds: Set<String>,
    ): List<Pair<String, List<String>>> =
        deriveWorkflowSchedulerState(graph).blockedNodes.filter { it.first in executableNodeIds }

    fun areExecutableNodesComplete(
        graph: WorkflowGraph,
        executableNodeIds: Set<String>,
        waitsForCollections: Boolean,
    ): Boolean {
        val execNodes = graph.nodes.filter { it.id in executableNodeIds }
        if (!execNodes.all { it.status in COMPLETED_NODE_STATUSES }) return false
        if (!waitsForCollections) return true
        return graphCollections(graph)
            .filter { it.explorable == true && collectionNodeIds(it, graph).isNotEmpty() }
            .all { it.exhausted == true || it.status == WorkflowGraphCollectionStatus.exhausted }
    }

    fun collectionFrontier(graph: WorkflowGraph, collection: WorkflowGraphCollection): Int =
        collectionNodeIds(collection, graph).count { nodeId ->
            val status = nodeById(graph, nodeId)?.status
            status == WorkflowNodeStatus.pending || status == WorkflowNodeStatus.active
        }

    fun isCollectionInPhase(
        collection: WorkflowGraphCollection,
        graph: WorkflowGraph,
        executableNodeIds: Set<String>,
        phase: String,
    ): Boolean {
        collection.phase?.let { return it == phase }
        val nodeIds = collectionNodeIds(collection, graph)
        return nodeIds.isEmpty() || nodeIds.any { it in executableNodeIds }
    }

    fun updateGraphNode(
        snapshot: WorkflowRunSnapshot,
        nodeId: String,
        status: WorkflowNodeStatus? = null,
        attempts: Int? = null,
        error: String? = null,
        clearError: Boolean = false,
    ): WorkflowRunSnapshot = snapshot.copy(
        graph = snapshot.graph.copy(
            nodes = snapshot.graph.nodes.map { node ->
                if (node.id != nodeId) node else node.copy(
                    status = status ?: node.status,
                    attempts = attempts ?: node.attempts,
                    error = if (clearError) null else error ?: node.error,
                )
            },
        ),
    )

    fun updateGraphCollection(
        snapshot: WorkflowRunSnapshot,
        collectionId: String,
        patch: (WorkflowGraphCollection) -> WorkflowGraphCollection,
        timestamp: String,
    ): WorkflowRunSnapshot {
        val collections = graphCollections(snapshot.graph)
        val existing = collections.find { it.collectionId == collectionId }
        val next = normalizeCollection(patch(existing ?: WorkflowGraphCollection(collectionId = collectionId)))
        return snapshot.copy(
            graph = snapshot.graph.copy(
                collections = if (existing != null) {
                    collections.map { if (it.collectionId == collectionId) next else it }
                } else {
                    collections + next
                },
            ),
            updatedAt = timestamp,
        )
    }

    fun upsertActivity(
        snapshot: WorkflowRunSnapshot,
        activity: WorkflowActivitySnapshot,
        timestamp: String,
    ): WorkflowRunSnapshot {
        val activities = snapshot.activities.filter { it.activityId != activity.activityId } + activity
        return snapshot.copy(
            activities = activities,
            sessionLinks = deriveWorkflowSessionLinks(activities, snapshot.runId),
            updatedAt = timestamp,
        )
    }

    fun addArtifact(
        snapshot: WorkflowRunSnapshot,
        artifact: WorkflowArtifact,
        timestamp: String,
    ): WorkflowRunSnapshot = snapshot.copy(
        artifacts = snapshot.artifacts.filter { it.path != artifact.path } + artifact,
        updatedAt = timestamp,
    )

    fun updatePhase(
        snapshot: WorkflowRunSnapshot,
        phaseId: String,
        patch: (WorkflowPhaseSnapshot) -> WorkflowPhaseSnapshot,
        timestamp: String,
    ): WorkflowRunSnapshot = snapshot.copy(
        currentPhase = phaseId,
        phases = snapshot.phases.map { if (it.phase == phaseId) patch(it) else it },
        updatedAt = timestamp,
    )
}
