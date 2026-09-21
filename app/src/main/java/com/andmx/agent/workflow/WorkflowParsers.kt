package com.andmx.agent.workflow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ZCode workflow/expert/parsers/* 对齐：宽松 JSON 提取 + planner/critic/seed/prompts 归一化。

object WorkflowParsers {

    fun parsePlannerJson(response: String): JsonElement {
        val trimmed = response.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return workflowJson.parseToJsonElement(trimmed)
        }
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```").find(trimmed)
        fenced?.groupValues?.get(1)?.let {
            return workflowJson.parseToJsonElement(it.trim())
        }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return workflowJson.parseToJsonElement(trimmed.substring(start, end + 1))
        }
        throw IllegalArgumentException("Workflow planner did not return JSON graph expansion data")
    }

    private fun normKey(key: String) = key.trim().lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun looseValue(record: JsonObject, keys: List<String>): JsonElement? {
        for (key in keys) record[key]?.let { return it }
        val normalized = keys.map(::normKey).filter { it.isNotEmpty() }.toSet()
        if (normalized.isEmpty()) return null
        for ((k, v) in record) if (normKey(k) in normalized) return v
        return null
    }

    private fun looseString(record: JsonObject, keys: List<String>): String? =
        (looseValue(record, keys) as? JsonPrimitive)?.contentOrNull

    private fun looseArray(record: JsonObject, keys: List<String>): JsonArray? =
        looseValue(record, keys) as? JsonArray

    private fun looseStringArray(record: JsonObject, keys: List<String>): List<String>? =
        looseArray(record, keys)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun looseBoolean(record: JsonObject, keys: List<String>): Boolean? =
        (looseValue(record, keys) as? JsonPrimitive)?.booleanOrNull

    private fun loosePositiveInt(record: JsonObject, keys: List<String>): Int? =
        (looseValue(record, keys) as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }

    // ── planner result ─────────────────────────────────────────

    fun parsePlannerResult(response: String, defaultPhase: String): WorkflowGraphPlannerResult {
        val raw = parsePlannerJson(response)
        runCatching { return workflowJson.decodeFromJsonElement(WorkflowGraphPlannerResult.serializer(), raw) }
        if (raw !is JsonObject) {
            throw IllegalArgumentException("Workflow planner did not return JSON graph expansion data")
        }
        val seed = normalizeSeedCandidate(raw, defaultPhase)
        val collectionNodeIds = looseStringArray(
            raw, listOf("collectionNodeIds", "collection_node_ids", "collectionUpdates", "collection_updates"),
        ) ?: seed?.collections?.flatMap { it.nodeIds }
        val nodes = seed?.nodes?.map {
            WorkflowGraphPlannerNode(
                collectionId = it.collectionId, dependsOn = it.dependsOn,
                description = it.description, id = it.id, kind = it.kind,
                phase = it.phase, prompt = it.prompt, title = it.title,
            )
        } ?: emptyList()
        val parsed = WorkflowGraphPlannerResult(
            collectionNodeIds = collectionNodeIds,
            edges = seed?.edges ?: emptyList(),
            exhausted = looseBoolean(raw, listOf("exhausted")),
            nodes = nodes,
            reasoning = (raw["reasoning"] as? JsonPrimitive)?.contentOrNull,
        )
        return parsed
    }

    // ── critic result ──────────────────────────────────────────

    fun parseCriticResult(response: String): WorkflowCriticResult {
        val raw = parsePlannerJson(response)
        if (raw !is JsonObject) {
            throw IllegalArgumentException("Workflow critic did not return JSON verdict data")
        }
        val hasLegacy = raw.containsKey("acceptance_gaps") || raw.containsKey("overallVerdict") ||
            raw.containsKey("passed") || raw.containsKey("reopenNodes") || raw.containsKey("reopen_proposals")
        if (!hasLegacy) {
            runCatching { return workflowJson.decodeFromJsonElement(WorkflowCriticResult.serializer(), raw) }
        }
        val verdict = when {
            raw["verdict"]?.jsonPrimitive?.contentOrNull in listOf("pass", "fail") ->
                raw["verdict"]!!.jsonPrimitive.contentOrNull!!
            (raw["passed"] as? JsonPrimitive)?.booleanOrNull == true -> "pass"
            (raw["passed"] as? JsonPrimitive)?.booleanOrNull == false -> "fail"
            raw["overallVerdict"]?.jsonPrimitive?.contentOrNull in listOf("approved", "conditionallyApproved") -> "pass"
            raw["overallVerdict"] is JsonPrimitive -> "fail"
            else -> throw IllegalArgumentException("Workflow critic did not return JSON verdict data")
        }
        val reasoning = looseString(raw, listOf("reasoning", "summary"))
            ?: (raw["verdict"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val rawProposals = (raw["reopenProposals"] as? JsonArray)
            ?: (raw["reopen_proposals"] as? JsonArray)
            ?: (raw["reopenNodes"] as? JsonArray)?.map {
                JsonObject(
                    mapOf(
                        "nodeId" to it,
                        "reason" to JsonPrimitive(reasoning.ifBlank { "critic requested reopen" }),
                    ),
                )
            }
            ?: JsonArray(emptyList())
        val proposals = rawProposals.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val nodeId = looseString(obj, listOf("nodeId", "node_id", "nodeName", "node_name"))
                ?: return@mapNotNull null
            val reason = looseString(obj, listOf("reason", "issue")) ?: return@mapNotNull null
            val severity = looseString(obj, listOf("severity"))
                ?.takeIf { it in listOf("critical", "major", "minor") }
            WorkflowCriticReopenProposal(nodeId, reason, severity)
        }
        val gaps = looseStringArray(raw, listOf("acceptanceGaps", "acceptance_gaps")) ?: emptyList()
        return WorkflowCriticResult(
            acceptanceGaps = gaps,
            reasoning = reasoning,
            reopenProposals = proposals,
            verdict = verdict,
        )
    }

    fun dedupeReopenProposals(
        proposals: List<WorkflowCriticReopenProposal>,
    ): List<WorkflowCriticReopenProposal> {
        val seen = mutableSetOf<String>()
        return proposals.filter { seen.add(it.nodeId) }
    }

    // ── graph seed ─────────────────────────────────────────────

    fun parseWorkflowGraphSeed(response: String, defaultPhase: String): WorkflowGraphSeed? {
        val raw = runCatching { parsePlannerJson(response) }.getOrNull() ?: return null
        return normalizeSeedCandidate(raw, defaultPhase)
    }

    fun gateRootSeedNodes(seed: WorkflowGraphSeed, gateNodeId: String): WorkflowGraphSeed {
        val incoming = seed.edges.map { it.to }.toSet()
        val roots = seed.nodes
            .filter { it.dependsOn.isEmpty() && it.id !in incoming }
            .map { it.id }.toSet()
        if (roots.isEmpty()) return seed
        val edgeIds = seed.edges.map { it.edgeId() }.toMutableSet()
        val edges = seed.edges.toMutableList()
        for (id in roots) {
            val edge = WorkflowGraphEdge(gateNodeId, id)
            if (edgeIds.add(edge.edgeId())) edges += edge
        }
        return seed.copy(
            edges = edges,
            nodes = seed.nodes.map { node ->
                if (node.id !in roots) node else node.copy(
                    dependsOn = (node.dependsOn + gateNodeId).distinct(),
                )
            },
        )
    }

    fun normalizeSeedCandidate(value: JsonElement, defaultPhase: String): WorkflowGraphSeed? {
        if (value is JsonArray) {
            if (value.all { isCollectionLike(it) }) {
                return normalizeSeedCandidate(JsonObject(mapOf("collections" to value)), defaultPhase)
            }
            if (value.all { isNodeLike(it) }) {
                return normalizeSeedCandidate(JsonObject(mapOf("nodes" to value)), defaultPhase)
            }
            if (value.all { isEdgeLike(it) }) {
                return normalizeSeedCandidate(JsonObject(mapOf("edges" to value)), defaultPhase)
            }
        }
        if (value !is JsonObject) return null
        val nodeCands = looseArray(value, listOf("nodes", "newNodes", "new_nodes"))
        val edgeCands = looseArray(value, listOf("edges", "newEdges", "new_edges"))
        val collectionCands = looseArray(value, listOf("collections"))
            ?: if (isCollectionLike(value)) JsonArray(listOf(value)) else null
        val nodes = nodeCands.orEmpty().mapNotNull { normalizeSeedNode(it, defaultPhase) }
        val edges = edgeCands.orEmpty().mapNotNull(::normalizeSeedEdge)
        val collections = collectionCands.orEmpty().mapNotNull { normalizeSeedCollection(it, defaultPhase) }
        if (nodes.isEmpty() && edges.isEmpty() && collections.isEmpty()) return null
        return WorkflowGraphSeed(
            collections = collections,
            edges = edges,
            nodes = nodes,
            reasoning = (value["reasoning"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    private fun normalizeSeedNode(value: JsonElement, defaultPhase: String): WorkflowGraphPlannerNode? {
        val obj = value as? JsonObject ?: return null
        val id = looseString(obj, listOf("id", "name", "nodeName", "node_name")) ?: return null
        return WorkflowGraphPlannerNode(
            collectionId = looseString(obj, listOf("collectionId", "collection_id", "collection")),
            dependsOn = looseStringArray(obj, listOf("dependsOn", "depends_on", "references", "inputs")) ?: emptyList(),
            description = looseString(obj, listOf("description", "goal")),
            id = id,
            kind = if (obj["kind"]?.jsonPrimitive?.contentOrNull == "phase") "phase" else "task",
            phase = looseString(obj, listOf("phase")) ?: defaultPhase,
            prompt = looseString(obj, listOf("prompt", "instructions")),
            title = looseString(obj, listOf("title", "summary")) ?: id,
        )
    }

    private fun normalizeSeedEdge(value: JsonElement): WorkflowGraphEdge? {
        val obj = value as? JsonObject ?: return null
        val from = looseString(obj, listOf("from", "source")) ?: return null
        val to = looseString(obj, listOf("to", "target")) ?: return null
        return WorkflowGraphEdge(from, to)
    }

    private fun normalizeSeedCollection(value: JsonElement, defaultPhase: String): WorkflowGraphSeedCollection? {
        val obj = value as? JsonObject ?: return null
        val id = looseString(obj, listOf("collectionId", "collection_id", "name", "id", "collectionsname"))
            ?: return null
        return WorkflowGraphSeedCollection(
            collectionId = id,
            explorable = looseBoolean(obj, listOf("explorable")),
            frontierTarget = loosePositiveInt(obj, listOf("frontierTarget", "frontier_target")),
            goal = looseString(obj, listOf("goal")),
            metric = looseString(obj, listOf("metric")),
            nodeIds = looseStringArray(obj, listOf("nodeIds", "node_ids", "nodeNames", "node_names")) ?: emptyList(),
            phase = looseString(obj, listOf("phase")) ?: defaultPhase,
            title = looseString(obj, listOf("title")) ?: id,
        )
    }

    private fun isNodeLike(value: JsonElement): Boolean {
        val obj = value as? JsonObject ?: return false
        return obj["kind"]?.jsonPrimitive?.contentOrNull in listOf("task", "phase") ||
            looseString(obj, listOf("id", "name", "nodeName", "node_name")) != null ||
            looseString(obj, listOf("description", "goal")) != null ||
            looseArray(obj, listOf("dependsOn", "depends_on", "references", "inputs")) != null
    }

    private fun isEdgeLike(value: JsonElement): Boolean {
        val obj = value as? JsonObject ?: return false
        return looseString(obj, listOf("from", "source")) != null ||
            looseString(obj, listOf("to", "target")) != null
    }

    private fun isCollectionLike(value: JsonElement): Boolean {
        val obj = value as? JsonObject ?: return false
        return looseString(obj, listOf("collectionId", "collection_id", "name", "id")) != null ||
            looseArray(obj, listOf("nodeIds", "node_ids", "nodeNames", "node_names")) != null ||
            looseString(obj, listOf("goal")) != null ||
            looseString(obj, listOf("metric")) != null ||
            (looseValue(obj, listOf("explorable")) as? JsonPrimitive)?.booleanOrNull != null
    }

    // ── node prompt updates ────────────────────────────────────

    fun parseWorkflowNodePromptUpdateSet(response: String): WorkflowNodePromptUpdateSet? {
        val raw = runCatching { parsePlannerJson(response) }.getOrNull() ?: return null
        val obj = when {
            raw is JsonArray -> JsonObject(mapOf("nodes" to raw))
            raw is JsonObject -> raw
            else -> return null
        }
        val nodesEl = looseArray(obj, listOf("nodes", "updates", "nodeUpdates", "node_updates")) ?: return null
        val nodes = nodesEl.mapNotNull { el ->
            val no = el as? JsonObject ?: return@mapNotNull null
            val id = looseString(no, listOf("id", "nodeId", "node_id", "name", "nodeName", "node_name"))
                ?: return@mapNotNull null
            val desc = looseString(no, listOf("description"))
            val prompt = looseString(no, listOf("prompt", "instructions"))
            val title = looseString(no, listOf("title"))
            if (desc == null && prompt == null && title == null) return@mapNotNull null
            WorkflowNodePromptUpdate(description = desc, id = id, prompt = prompt, title = title)
        }
        return WorkflowNodePromptUpdateSet(
            nodes = nodes,
            reasoning = (obj["reasoning"] as? JsonPrimitive)?.contentOrNull,
        )
    }
}
