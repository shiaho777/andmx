package com.andmx.agent.workflow

import com.andmx.agent.workflow.dynamic.PendingWorkflowQuestion
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

data class WorkflowRosterEntry(
    val actorId: String,
    val role: String,
    val status: String,
    val model: String?,
    val name: String? = null,
    val phase: String? = null,
    val nodeId: String? = null,
    val attempt: Int? = null,
)

fun parseWorkflowRunFormat(raw: String?): String? =
    when (val value = raw?.trim()?.lowercase().orEmpty()) {
        "", "text" -> "text"
        "json" -> "json"
        else -> null
    }

fun workflowRunRoster(
    snapshot: WorkflowRunSnapshot,
    pendingQuestions: List<PendingWorkflowQuestion> = emptyList(),
): List<WorkflowRosterEntry> {
    val nameByActor = pendingQuestions
        .filter { !it.actorName.isNullOrBlank() }
        .associate { it.actor to it.actorName!! }
    val links = snapshot.sessionLinks.ifEmpty {
        deriveWorkflowSessionLinks(snapshot.activities, snapshot.runId)
    }
    return links.map { link ->
        WorkflowRosterEntry(
            actorId = link.sessionId ?: link.activityId,
            role = link.kind.name,
            status = link.status,
            model = link.model ?: snapshot.subagentModel,
            name = nameByActor[link.sessionId] ?: nameByActor[link.activityId],
            phase = link.phase,
            nodeId = link.nodeId,
            attempt = link.attempt,
        )
    }
}

fun workflowRunRosterJson(
    snapshot: WorkflowRunSnapshot,
    pendingQuestions: List<PendingWorkflowQuestion> = emptyList(),
): JsonObject {
    val roster = workflowRunRoster(snapshot, pendingQuestions)
    return buildJsonObject {
        put("runId", snapshot.runId)
        put("status", snapshot.status.name)
        put("kind", snapshot.kind)
        putJsonArray("roster") {
            roster.forEach { entry ->
                add(
                    buildJsonObject {
                        put("actorId", entry.actorId)
                        put("role", entry.role)
                        put("status", entry.status)
                        entry.model?.let { put("model", it) }
                        entry.name?.let { put("name", it) }
                        entry.phase?.let { put("phase", it) }
                        entry.nodeId?.let { put("nodeId", it) }
                        entry.attempt?.let { put("attempt", it) }
                    },
                )
            }
        }
        putJsonArray("pendingQuestions") {
            pendingQuestions.forEach { q ->
                add(
                    buildJsonObject {
                        put("qid", q.qid)
                        put("actor", q.actor)
                        q.actorName?.let { put("actorName", it) }
                        put("question", q.question)
                        q.context?.let { put("context", it) }
                    },
                )
            }
        }
    }
}