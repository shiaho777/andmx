package com.andmx.agent.workflow

// ZCode workflow/lifecycle.ts 对齐：快照生命周期纯函数——
// resume 修复 / cancel / critic reopen / planner seed 应用 / node prompt 更新。

private const val DEFAULT_RESUME_RESET_REASON =
    "Reset during workflow resume because the previous process stopped before completion."
private const val DEFAULT_CANCEL_REASON = "Workflow cancelled."
private const val DEFAULT_REOPEN_REASON = "Reopened by workflow critic."
private val CANCELLABLE_STATUSES = setOf(WorkflowNodeStatus.active, WorkflowNodeStatus.pending)
private val REOPENABLE_STATUSES = setOf(
    WorkflowNodeStatus.completed, WorkflowNodeStatus.failed, WorkflowNodeStatus.skipped,
)

data class WorkflowGraphNodeChange(val nodeId: String, val phase: String?, val status: WorkflowNodeStatus)

data class WorkflowSnapshotLifecycleResult(
    val activityIds: List<String>,
    val changed: Boolean,
    val nodeChanges: List<WorkflowGraphNodeChange>,
    val phaseIds: List<String>,
    val snapshot: WorkflowRunSnapshot,
)

object WorkflowLifecycle {

    fun reconcileForResume(
        snapshot: WorkflowRunSnapshot,
        nodeIds: Set<String>? = null,
        reason: String = DEFAULT_RESUME_RESET_REASON,
        resetActivities: Boolean = true,
        resetPhases: Boolean = true,
        timestamp: String,
    ): WorkflowSnapshotLifecycleResult {
        val nodeChanges = mutableListOf<WorkflowGraphNodeChange>()
        val resetPhaseIdsFromNodes = mutableSetOf<String>()
        val nodes = snapshot.graph.nodes.map { node ->
            if (node.status != WorkflowNodeStatus.active || (nodeIds != null && node.id !in nodeIds)) {
                node
            } else {
                nodeChanges += WorkflowGraphNodeChange(node.id, node.phase, WorkflowNodeStatus.pending)
                node.phase?.let(resetPhaseIdsFromNodes::add)
                node.copy(error = reason, status = WorkflowNodeStatus.pending)
            }
        }
        val phaseIds = mutableListOf<String>()
        val phases = snapshot.phases.map { phase ->
            val repair = resetPhases && phase.status == WorkflowNodeStatus.active &&
                (nodeIds == null || phase.phase in resetPhaseIdsFromNodes)
            if (!repair) phase else {
                phaseIds += phase.phase
                WorkflowPhaseSnapshot(error = reason, phase = phase.phase, status = WorkflowNodeStatus.pending)
            }
        }
        val activityIds = mutableListOf<String>()
        val activities = snapshot.activities.map { activity ->
            val repair = resetActivities && activity.status == WorkflowNodeStatus.active &&
                (nodeIds == null ||
                    (activity.nodeId != null && activity.nodeId in nodeIds) ||
                    activity.phase in resetPhaseIdsFromNodes)
            if (!repair) activity else {
                activityIds += activity.activityId
                activity.copy(
                    completedAt = activity.completedAt ?: timestamp,
                    error = activity.error ?: reason,
                    status = WorkflowNodeStatus.cancelled,
                )
            }
        }
        val changed = nodeChanges.isNotEmpty() || phaseIds.isNotEmpty() || activityIds.isNotEmpty()
        return WorkflowSnapshotLifecycleResult(
            activityIds = activityIds,
            changed = changed,
            nodeChanges = nodeChanges,
            phaseIds = phaseIds,
            snapshot = if (changed) snapshot.copy(
                activities = activities,
                graph = snapshot.graph.copy(nodes = nodes),
                phases = phases,
                sessionLinks = deriveWorkflowSessionLinks(activities, snapshot.runId),
                updatedAt = timestamp,
            ) else snapshot,
        )
    }

    fun cancel(
        snapshot: WorkflowRunSnapshot,
        reason: String = DEFAULT_CANCEL_REASON,
        timestamp: String,
    ): WorkflowSnapshotLifecycleResult {
        val nodeChanges = mutableListOf<WorkflowGraphNodeChange>()
        val nodes = snapshot.graph.nodes.map { node ->
            if (node.status !in CANCELLABLE_STATUSES) node else {
                nodeChanges += WorkflowGraphNodeChange(node.id, node.phase, WorkflowNodeStatus.cancelled)
                node.copy(error = reason, status = WorkflowNodeStatus.cancelled)
            }
        }
        val phaseIds = mutableListOf<String>()
        val phases = snapshot.phases.map { phase ->
            if (phase.status !in CANCELLABLE_STATUSES) phase else {
                phaseIds += phase.phase
                phase.copy(
                    completedAt = phase.completedAt ?: timestamp,
                    error = phase.error ?: reason,
                    status = WorkflowNodeStatus.cancelled,
                )
            }
        }
        val activityIds = mutableListOf<String>()
        val activities = snapshot.activities.map { activity ->
            if (activity.status !in CANCELLABLE_STATUSES) activity else {
                activityIds += activity.activityId
                activity.copy(
                    completedAt = activity.completedAt ?: timestamp,
                    error = activity.error ?: reason,
                    status = WorkflowNodeStatus.cancelled,
                )
            }
        }
        val changed = snapshot.status != WorkflowRunStatus.cancelled ||
            snapshot.completedAt != timestamp ||
            nodeChanges.isNotEmpty() || phaseIds.isNotEmpty() || activityIds.isNotEmpty()
        return WorkflowSnapshotLifecycleResult(
            activityIds = activityIds,
            changed = changed,
            nodeChanges = nodeChanges,
            phaseIds = phaseIds,
            snapshot = if (changed) snapshot.copy(
                activities = activities,
                completedAt = timestamp,
                graph = snapshot.graph.copy(nodes = nodes),
                phases = phases,
                sessionLinks = deriveWorkflowSessionLinks(activities, snapshot.runId),
                status = WorkflowRunStatus.cancelled,
                updatedAt = timestamp,
            ) else snapshot,
        )
    }

    fun reopenNode(
        snapshot: WorkflowRunSnapshot,
        nodeId: String,
        maxReopens: Int = 2,
        reason: String = DEFAULT_REOPEN_REASON,
        timestamp: String,
    ): Pair<WorkflowRunSnapshot, Int> {
        val node = snapshot.graph.nodes.find { it.id == nodeId }
            ?: error("Workflow graph node not found: $nodeId")
        require(node.status in REOPENABLE_STATUSES) {
            "Cannot reopen workflow node \"$nodeId\": status is \"${node.status}\", expected completed, failed, or skipped"
        }
        val reopenAttempts = (node.reopenAttempts ?: 0)
        require(reopenAttempts < maxReopens) {
            "Workflow node \"$nodeId\" already reopened ${reopenAttempts}x (max=$maxReopens)"
        }
        val next = reopenAttempts + 1
        val nodes = snapshot.graph.nodes.map {
            if (it.id != nodeId) it else it.copy(
                error = reason, reopenAttempts = next, status = WorkflowNodeStatus.pending,
            )
        }
        return snapshot.copy(graph = snapshot.graph.copy(nodes = nodes), updatedAt = timestamp) to next
    }

    data class ApplySeedResult(
        val addedCollections: List<WorkflowGraphCollection>,
        val addedEdges: List<WorkflowGraphEdge>,
        val addedNodes: List<WorkflowGraphNode>,
        val changed: Boolean,
        val snapshot: WorkflowRunSnapshot,
    )

    fun applyGraphSeed(
        snapshot: WorkflowRunSnapshot,
        seed: WorkflowGraphSeed,
        phase: String,
        timestamp: String,
    ): ApplySeedResult {
        val existingNodeIds = snapshot.graph.nodes.map { it.id }.toMutableSet()
        val addedNodes = mutableListOf<WorkflowGraphNode>()
        val pendingNodeIds = mutableSetOf<String>()
        for (node in seed.nodes) {
            require(node.id !in existingNodeIds && node.id !in pendingNodeIds) {
                "Workflow graph seed returned duplicate node: ${node.id}"
            }
            pendingNodeIds += node.id
            addedNodes += WorkflowGraphNode(
                collectionId = node.collectionId,
                dependsOn = node.dependsOn.filter { it.isNotBlank() }.distinct(),
                description = node.description,
                id = node.id,
                kind = node.kind,
                phase = node.phase ?: phase,
                prompt = node.prompt,
                status = WorkflowNodeStatus.pending,
                title = node.title,
            )
        }
        val addedEdges = normalizeSeedEdges(snapshot.graph.edges, addedNodes, seed.edges)
        validateAddedEdges(snapshot.graph.nodes, snapshot.graph.edges, addedNodes, addedEdges)

        val knownNodeIds = snapshot.graph.nodes.map { it.id }.toSet() + addedNodes.map { it.id }
        val existingCollectionIds = snapshot.graph.collections.orEmpty().map { it.collectionId }.toMutableSet()
        val addedCollections = mutableListOf<WorkflowGraphCollection>()
        val pendingCollectionIds = mutableSetOf<String>()
        for (collection in seed.collections) {
            require(
                collection.collectionId !in existingCollectionIds &&
                    collection.collectionId !in pendingCollectionIds,
            ) { "Workflow graph seed returned duplicate collection: ${collection.collectionId}" }
            pendingCollectionIds += collection.collectionId
            val implicit = addedNodes.filter { it.collectionId == collection.collectionId }.map { it.id }
            val nodeIds = (collection.nodeIds + implicit).filter { it.isNotBlank() }.distinct()
            for (nodeId in nodeIds) {
                require(nodeId in knownNodeIds) {
                    "Workflow graph seed collection \"${collection.collectionId}\" references unknown node: $nodeId"
                }
            }
            addedCollections += WorkflowGraphCollection(
                collectionId = collection.collectionId,
                explorable = collection.explorable,
                frontierTarget = collection.frontierTarget,
                goal = collection.goal,
                metric = collection.metric,
                nodeIds = nodeIds,
                phase = collection.phase ?: phase,
                title = collection.title,
            )
        }
        val changed = addedNodes.isNotEmpty() || addedEdges.isNotEmpty() || addedCollections.isNotEmpty()
        return ApplySeedResult(
            addedCollections = addedCollections,
            addedEdges = addedEdges,
            addedNodes = addedNodes,
            changed = changed,
            snapshot = if (changed) snapshot.copy(
                graph = snapshot.graph.copy(
                    collections = snapshot.graph.collections.orEmpty() + addedCollections,
                    edges = snapshot.graph.edges + addedEdges,
                    nodes = snapshot.graph.nodes + addedNodes,
                ),
                updatedAt = timestamp,
            ) else snapshot,
        )
    }

    data class PromptUpdatesResult(
        val changed: Boolean,
        val snapshot: WorkflowRunSnapshot,
        val updatedNodes: List<WorkflowGraphNode>,
    )

    fun applyNodePromptUpdates(
        snapshot: WorkflowRunSnapshot,
        updates: List<WorkflowNodePromptUpdate>,
        phase: String,
        timestamp: String,
    ): PromptUpdatesResult {
        val byId = LinkedHashMap<String, WorkflowNodePromptUpdate>()
        for (update in updates) {
            require(update.id !in byId) {
                "Workflow node prompt update returned duplicate node: ${update.id}"
            }
            byId[update.id] = update
        }
        if (byId.isEmpty()) return PromptUpdatesResult(false, snapshot, emptyList())
        val nodeIds = snapshot.graph.nodes.map { it.id }.toSet()
        for (nodeId in byId.keys) {
            require(nodeId in nodeIds) {
                "Workflow node prompt update references unknown node: $nodeId"
            }
        }
        val updated = mutableListOf<WorkflowGraphNode>()
        val nodes = snapshot.graph.nodes.map { node ->
            val update = byId[node.id] ?: return@map node
            require(node.phase == null || node.phase == phase) {
                "Workflow node prompt update for \"${node.id}\" targets phase \"$phase\" but node belongs to \"${node.phase}\""
            }
            val next = node.copy(
                description = update.description ?: node.description,
                prompt = update.prompt ?: node.prompt,
                title = update.title ?: node.title,
            )
            if (next == node) node else {
                updated += next
                next
            }
        }
        if (updated.isEmpty()) return PromptUpdatesResult(false, snapshot, emptyList())
        return PromptUpdatesResult(
            true,
            snapshot.copy(graph = snapshot.graph.copy(nodes = nodes), updatedAt = timestamp),
            updated,
        )
    }

    private fun normalizeSeedEdges(
        existingEdges: List<WorkflowGraphEdge>,
        addedNodes: List<WorkflowGraphNode>,
        seedEdges: List<WorkflowGraphEdge>,
    ): List<WorkflowGraphEdge> {
        val edges = seedEdges.map { WorkflowGraphEdge(it.from, it.to) }.toMutableList()
        val known = (existingEdges + edges).map { it.edgeId() }.toMutableSet()
        for (node in addedNodes) {
            for (dep in node.dependsOn) {
                val edge = WorkflowGraphEdge(dep, node.id)
                if (known.add(edge.edgeId())) edges += edge
            }
        }
        return edges
    }

    private fun validateAddedEdges(
        existingNodes: List<WorkflowGraphNode>,
        existingEdges: List<WorkflowGraphEdge>,
        addedNodes: List<WorkflowGraphNode>,
        addedEdges: List<WorkflowGraphEdge>,
    ) {
        val nodeIds = (existingNodes + addedNodes).map { it.id }.toSet()
        val seen = existingEdges.map { it.edgeId() }.toMutableSet()
        val pending = existingEdges.toMutableList()
        for (edge in addedEdges) {
            require(edge.from != edge.to) {
                "Workflow graph seed returned a self-loop edge: ${edge.edgeId()}"
            }
            require(edge.from in nodeIds) {
                "Workflow graph seed returned an edge with unknown source node: ${edge.from}"
            }
            require(edge.to in nodeIds) {
                "Workflow graph seed returned an edge with unknown target node: ${edge.to}"
            }
            require(seen.add(edge.edgeId())) {
                "Workflow graph seed returned duplicate edge: ${edge.edgeId()}"
            }
            require(!wouldFormCycle(pending, edge)) {
                "Workflow graph seed returned an edge that would create a cycle: ${edge.edgeId()}"
            }
            pending += edge
        }
    }

    private fun wouldFormCycle(edges: List<WorkflowGraphEdge>, newEdge: WorkflowGraphEdge): Boolean {
        val outgoing = mutableMapOf<String, MutableList<String>>()
        for (edge in edges) outgoing.getOrPut(edge.from) { mutableListOf() }.add(edge.to)
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque(listOf(newEdge.to))
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur == newEdge.from) return true
            if (!visited.add(cur)) continue
            queue.addAll(outgoing[cur].orEmpty())
        }
        return false
    }
}
