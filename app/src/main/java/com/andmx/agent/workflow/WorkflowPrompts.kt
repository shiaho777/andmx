package com.andmx.agent.workflow

// ZCode workflow/{scheduler/prompts,expert/prompts,ids,formatters} 对齐。

object WorkflowPrompts {

    fun phaseNodeId(phase: String) = "phase:$phase"

    fun safeArtifactName(value: String): String =
        value.replace(Regex("[^a-zA-Z0-9._-]+"), "-").trim('-').ifBlank { "workflow" }

    fun safeRunIdSegment(value: String): String = safeArtifactName(value).replace('.', '-')

    fun executableNodeIdsForPhase(graph: WorkflowGraph, phase: String): List<String> {
        val taskIds = graph.nodes
            .filter { it.kind == "task" && (it.phase == phase || it.phase == null) }
            .map { it.id }
        return taskIds.ifEmpty { listOf(phaseNodeId(phase)) }
    }

    fun createPhaseGraph(definition: WorkflowDefinition): WorkflowGraph {
        val map = definition.phaseMap()
        val nodes = definition.phaseOrder.map { phaseId ->
            val def = map[phaseId] ?: error("${definition.title} definition is missing phase: $phaseId")
            WorkflowGraphNode(
                dependsOn = emptyList(),
                description = def.description,
                id = phaseNodeId(phaseId),
                kind = "phase",
                phase = phaseId,
                status = WorkflowNodeStatus.pending,
                title = def.title,
            )
        }
        val edges = mutableListOf<WorkflowGraphEdge>()
        val mutableNodes = nodes.toMutableList()
        for (i in 1 until definition.phaseOrder.size) {
            val prev = phaseNodeId(definition.phaseOrder[i - 1])
            val cur = phaseNodeId(definition.phaseOrder[i])
            edges += WorkflowGraphEdge(prev, cur)
            mutableNodes[i] = mutableNodes[i].copy(dependsOn = listOf(prev))
        }
        return WorkflowGraph(collections = emptyList(), edges = edges, nodes = mutableNodes)
    }

    private fun artifactLines(snapshot: WorkflowRunSnapshot) =
        snapshot.artifacts.joinToString("\n") { "- ${it.label}: ${it.path}" }

    fun buildPhasePrompt(snapshot: WorkflowRunSnapshot, definition: WorkflowPhaseDefinition): String {
        val graphContract = definition.seedGraphFromArtifact?.let { seed ->
            """
            |
            |Architecture graph contract:
            |Return a JSON object, either raw or fenced as ```json, so the workflow runtime can seed the ${seed.targetPhase} DAG:
            |{"nodes":[{"id":"implement_auth","title":"Implement auth","description":"small executable unit","dependsOn":["setup_config"],"collectionId":"implementation","prompt":"optional node-specific instructions"}],"edges":[{"from":"setup_config","to":"implement_auth"}],"collections":[{"collectionId":"implementation","title":"Implementation","nodeIds":["setup_config","implement_auth"],"explorable":false,"goal":"ship the feature","metric":"tests pass"}],"reasoning":"brief rationale"}
            |Node ids must be unique, references must point to real node ids, and edges must not create cycles.
            """.trimMargin()
        }
        return buildString {
            append("You are running the ZCode workflow phase: ${definition.phase}.\n")
            append("Workflow run: ${snapshot.runId}\n")
            append("Working directory: ${snapshot.cwd}\n\n")
            append("User task:\n${snapshot.task}\n\n")
            append("Scheduling strategy:\n")
            append("- Clarify max rounds: ${snapshot.strategy.clarify.maxRounds}, min rounds: ${snapshot.strategy.clarify.minRounds}, confidence threshold: ${snapshot.strategy.clarify.confidenceThreshold}\n")
            append("- Executor frontier target: ${snapshot.strategy.executor.frontierTarget}, max concurrent loops: ${snapshot.strategy.executor.maxConcurrentLoops}, max planner runs: ${snapshot.strategy.executor.maxPlannerRuns}\n")
            append("- React loop max rounds: ${snapshot.strategy.reactLoop.maxRounds}\n")
            append("- Final critic max iterations: ${snapshot.strategy.finalCritic.maxIterations}\n\n")
            append("Phase objective:\n${definition.description}\n\n")
            val arts = artifactLines(snapshot)
            append(if (arts.isNotEmpty()) "Previous artifacts available on disk:\n$arts" else "No previous artifacts yet.")
            graphContract?.let { append("\n").append(it) }
            append("\n\nOutput a concise Markdown artifact for this phase. Preserve concrete file paths, commands, risks, and next actions. If this phase executes code, make the edits and run focused validation when practical.")
        }
    }

    fun buildDefaultNodePrompt(snapshot: WorkflowRunSnapshot, node: WorkflowGraphNode, phase: String): String {
        val arts = artifactLines(snapshot)
        return buildString {
            append("You are running a ZCode workflow node for phase: $phase.\n")
            append("Workflow run: ${snapshot.runId}\n")
            append("Working directory: ${snapshot.cwd}\n\n")
            append("User task:\n${snapshot.task}\n\n")
            append("Node: ${node.title}\nNode id: ${node.id}\n")
            node.description?.let { append("Node objective:\n$it\n") }
            node.prompt?.let { append("Node prompt:\n$it\n") }
            append("\n")
            append(if (arts.isNotEmpty()) "Previous artifacts available on disk:\n$arts" else "No previous artifacts yet.")
            append("\n\nExecute only this node's scope. Return a concise Markdown artifact with changes, validation, and residual risk.")
        }
    }

    fun buildScheduledNodePrompt(
        snapshot: WorkflowRunSnapshot,
        definition: WorkflowPhaseDefinition,
        node: WorkflowGraphNode,
    ): String {
        if (node.phase == definition.phase && node.kind == "phase") {
            return buildPhasePrompt(snapshot, definition)
        }
        val arts = artifactLines(snapshot)
        return buildString {
            append("You are running a ZCode workflow node inside phase: ${definition.phase}.\n")
            append("Workflow run: ${snapshot.runId}\n")
            append("Working directory: ${snapshot.cwd}\n\n")
            append("User task:\n${snapshot.task}\n\n")
            append("Node: ${node.title}\nNode id: ${node.id}\n")
            node.description?.let { append("Node objective:\n$it\n") }
            node.prompt?.let { append("Node prompt:\n$it\n") }
            append("\nScheduling constraints:\n")
            append("- Max concurrent loops: ${snapshot.strategy.executor.maxConcurrentLoops}\n")
            append("- React loop max rounds: ${snapshot.strategy.reactLoop.maxRounds}\n\n")
            append(if (arts.isNotEmpty()) "Previous artifacts available on disk:\n$arts" else "No previous artifacts yet.")
            append("\n\nExecute only this node's scope. Return a concise Markdown artifact with changes, validation, and residual risk.")
        }
    }

    fun buildDefaultPlannerPrompt(
        snapshot: WorkflowRunSnapshot,
        collection: WorkflowGraphCollection,
        phase: String,
    ): String {
        val nodeLines = WorkflowGraphOps.collectionNodeIds(collection, snapshot.graph)
            .mapNotNull { WorkflowGraphOps.nodeById(snapshot.graph, it) }
            .joinToString("\n") { node ->
                "- ${node.id} [${node.status}]: ${node.title}${node.description?.let { d -> " - $d" } ?: ""}"
            }
        return buildString {
            append("You are running a ZCode workflow exploration planner for phase: $phase.\n")
            append("Workflow run: ${snapshot.runId}\n")
            append("Working directory: ${snapshot.cwd}\n\n")
            append("User task:\n${snapshot.task}\n\n")
            append("Collection: ${collection.title ?: collection.collectionId}\n")
            append("Collection id: ${collection.collectionId}\n")
            collection.goal?.let { append("Goal:\n$it\n") }
            collection.metric?.let { append("Metric:\n$it\n") }
            append("\n")
            append(if (nodeLines.isNotEmpty()) "Existing collection nodes:\n$nodeLines" else "No existing collection nodes.")
            append("\n\nReturn only JSON matching this shape:\n")
            append("{\"nodes\":[{\"id\":\"string\",\"title\":\"string\",\"description\":\"string\",\"dependsOn\":[\"node-id\"],\"prompt\":\"string\"}],\"edges\":[{\"from\":\"node-id\",\"to\":\"node-id\"}],\"collectionNodeIds\":[\"node-id\"],\"exhausted\":false,\"reasoning\":\"string\"}\n")
            append("Use unique node ids, avoid cycles, and set exhausted=true only when no useful expansion remains.")
        }
    }

    fun buildScheduledPhaseSummary(snapshot: WorkflowRunSnapshot, phase: String): String {
        val activities = snapshot.activities.filter { it.phase == phase }
        val activityLines = activities.joinToString("\n") {
            "- ${it.nodeId ?: it.activityId}: ${it.status}${it.artifactPath?.let { p -> " ($p)" } ?: ""}${it.error?.let { e -> " error=$e" } ?: ""}"
        }
        val nodeLines = snapshot.graph.nodes
            .filter { it.phase == phase || it.kind == "task" }
            .joinToString("\n") {
                "- ${it.id}: ${it.status}${it.attempts?.let { a -> " attempts=$a" } ?: ""}${it.error?.let { e -> " error=$e" } ?: ""}"
            }
        return buildString {
            append("# $phase Scheduler Summary\n\n")
            append("Run: ${snapshot.runId}\nStatus: ${snapshot.status}\nUpdated: ${snapshot.updatedAt}\n\n")
            append("## Nodes\n\n").append(nodeLines.ifEmpty { "- No scheduled nodes." })
            append("\n\n## Activities\n\n").append(activityLines.ifEmpty { "- No activities." })
            append("\n")
        }
    }

    fun buildReport(snapshot: WorkflowRunSnapshot): String = buildString {
        append("# Workflow Report\n\n")
        append("Run: ${snapshot.runId}\nTask: ${snapshot.task}\nStatus: ${snapshot.status}\n")
        append("Directory: ${snapshot.cwd}\nCreated: ${snapshot.createdAt}\nUpdated: ${snapshot.updatedAt}\n\n")
        append("## Phases\n\n")
        snapshot.phases.forEach {
            append("- ${it.phase}: ${it.status}${it.artifactPath?.let { p -> " ($p)" } ?: ""}\n")
        }
        append("\n## Activities\n\n")
        snapshot.activities.forEach {
            append("- ${it.phase}: ${it.status} (${it.activityId})${it.sessionId?.let { s -> " session=$s" } ?: ""}${it.turnId?.let { t -> " turn=$t" } ?: ""}\n")
        }
        append("\n## Artifacts\n\n")
        snapshot.artifacts.forEach { append("- ${it.label}: ${it.path}\n") }
    }

    fun formatStatus(snapshot: WorkflowRunSnapshot): String = buildString {
        append("Expert workflow ${snapshot.runId}\n")
        append("Status: ${snapshot.status}\nTask: ${snapshot.task}\n")
        append("Directory: ${snapshot.cwd}\nUpdated: ${snapshot.updatedAt}\n\nPhases:\n")
        snapshot.phases.forEach { phase ->
            val marker = when (phase.status) {
                WorkflowNodeStatus.completed -> "[x]"
                WorkflowNodeStatus.active -> ">"
                else -> "-"
            }
            append("  $marker ${phase.phase}: ${phase.status}")
            phase.activityId?.let { append(" | activity $it${phase.sessionId?.let { s -> " | session $s" } ?: ""}") }
            phase.error?.let { append(" ($it)") }
            append("\n")
        }
        snapshot.reportPath?.let { append("\nReport: $it") }
    }

    fun formatCompletion(snapshot: WorkflowRunSnapshot): String = buildString {
        append("Expert workflow ${snapshot.runId} ${snapshot.status}.\nTask: ${snapshot.task}")
        snapshot.reportPath?.let { append("\nReport: $it") }
    }
}
