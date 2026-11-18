package com.andmx.agent.workflow

import com.andmx.agent.workflow.dynamic.PendingWorkflowQuestion
import com.andmx.agent.zcode.GET_WORKFLOW_RUN_PARAMETERS
import com.andmx.agent.zcode.GET_WORKFLOW_RUN_ROSTER_PARAMETERS
import com.andmx.agent.zcode.WorkflowTools
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowRosterTest {

    private fun activity(
        id: String,
        sessionId: String?,
        status: WorkflowNodeStatus,
        model: String? = null,
        kind: WorkflowActivityKind = WorkflowActivityKind.actor_agent,
        nodeId: String? = null,
    ) = WorkflowActivitySnapshot(
        activityId = id,
        kind = kind,
        model = model,
        nodeId = nodeId,
        phase = "execute",
        sessionId = sessionId,
        startedAt = "2026-01-01T00:00:00Z",
        status = status,
    )

    private fun snapshot(
        activities: List<WorkflowActivitySnapshot> = listOf(
            activity("act-1", "sess-1", WorkflowNodeStatus.completed, model = "prov/model-a", nodeId = "n1"),
            activity("act-2", null, WorkflowNodeStatus.active),
        ),
        sessionLinks: List<WorkflowSessionLink> = emptyList(),
        subagentModel: String? = "prov/fallback",
    ) = WorkflowRunSnapshot(
        activities = activities,
        artifacts = emptyList(),
        createdAt = "2026-01-01T00:00:00Z",
        cwd = "/root/project",
        graph = WorkflowGraph(edges = emptyList(), nodes = emptyList()),
        kind = "dynamic",
        phaseOrder = listOf("execute"),
        phases = emptyList(),
        runId = "run-1",
        sessionLinks = sessionLinks,
        status = WorkflowRunStatus.running,
        strategy = WorkflowStrategy.DEFAULT,
        subagentModel = subagentModel,
        task = "demo task",
        updatedAt = "2026-01-01T00:01:00Z",
    )

    private fun question(actor: String, name: String?) = PendingWorkflowQuestion(
        qid = "dwfq-1",
        runId = "run-1",
        actor = actor,
        actorName = name,
        question = "Which threshold?",
        context = null,
        askedAt = 0L,
    )

    @Test
    fun parseFormatDefaultsToText() {
        assertEquals("text", parseWorkflowRunFormat(null))
        assertEquals("text", parseWorkflowRunFormat(""))
        assertEquals("text", parseWorkflowRunFormat("  "))
        assertEquals("text", parseWorkflowRunFormat("text"))
    }

    @Test
    fun parseFormatAcceptsJsonCaseInsensitively() {
        assertEquals("json", parseWorkflowRunFormat("json"))
        assertEquals("json", parseWorkflowRunFormat(" JSON "))
        assertEquals("text", parseWorkflowRunFormat("Text"))
    }

    @Test
    fun parseFormatRejectsUnknownValues() {
        assertNull(parseWorkflowRunFormat("yaml"))
        assertNull(parseWorkflowRunFormat("roster"))
    }

    @Test
    fun rosterDerivesFromActivitiesWhenLinksMissing() {
        val roster = workflowRunRoster(snapshot())
        assertEquals(2, roster.size)
        assertEquals("sess-1", roster[0].actorId)
        assertEquals("actor_agent", roster[0].role)
        assertEquals("completed", roster[0].status)
        assertEquals("prov/model-a", roster[0].model)
        assertEquals("n1", roster[0].nodeId)
        assertEquals(1, roster[0].attempt)
        assertEquals("act-2", roster[1].actorId)
        assertEquals("running", roster[1].status)
        assertEquals("prov/fallback", roster[1].model)
    }

    @Test
    fun rosterPrefersSessionLinks() {
        val link = WorkflowSessionLink(
            activityId = "act-9",
            attempt = 2,
            kind = WorkflowActivityKind.critic_agent,
            model = "prov/model-b",
            phase = "review",
            runId = "run-1",
            sessionId = "sess-9",
            startedAt = "2026-01-01T00:00:00Z",
            status = "failed",
        )
        val roster = workflowRunRoster(snapshot(sessionLinks = listOf(link)))
        assertEquals(1, roster.size)
        assertEquals("sess-9", roster[0].actorId)
        assertEquals("critic_agent", roster[0].role)
        assertEquals("failed", roster[0].status)
        assertEquals("prov/model-b", roster[0].model)
        assertEquals(2, roster[0].attempt)
    }

    @Test
    fun rosterAttachesActorNamesFromPendingQuestions() {
        val roster = workflowRunRoster(snapshot(), listOf(question("sess-1", "研究员")))
        assertEquals("研究员", roster[0].name)
        assertNull(roster[1].name)
    }

    @Test
    fun rosterJsonHasStructuredShape() {
        val json = workflowRunRosterJson(snapshot(), listOf(question("act-2", "执笔者")))
        assertEquals("run-1", json["runId"]!!.jsonPrimitive.content)
        assertEquals("running", json["status"]!!.jsonPrimitive.content)
        assertEquals("dynamic", json["kind"]!!.jsonPrimitive.content)
        val roster = json["roster"]!!.jsonArray
        assertEquals(2, roster.size)
        val first = roster[0].jsonObject
        assertEquals("sess-1", first["actorId"]!!.jsonPrimitive.content)
        assertEquals("actor_agent", first["role"]!!.jsonPrimitive.content)
        assertEquals("completed", first["status"]!!.jsonPrimitive.content)
        assertEquals("prov/model-a", first["model"]!!.jsonPrimitive.content)
        val second = roster[1].jsonObject
        assertEquals("act-2", second["actorId"]!!.jsonPrimitive.content)
        assertEquals("执笔者", second["name"]!!.jsonPrimitive.content)
        assertFalse(second.containsKey("model") && second["model"]!!.jsonPrimitive.content.isEmpty())
        val pending = json["pendingQuestions"]!!.jsonArray
        assertEquals(1, pending.size)
        assertEquals("dwfq-1", pending[0].jsonObject["qid"]!!.jsonPrimitive.content)
        val reparsed = Json.parseToJsonElement(json.toString()).jsonObject
        assertEquals(json, reparsed)
    }

    @Test
    fun rosterJsonOmitsNullOptionalFields() {
        val json = workflowRunRosterJson(snapshot(subagentModel = null))
        val second = json["roster"]!!.jsonArray[1].jsonObject
        assertFalse(second.containsKey("model"))
        assertFalse(second.containsKey("name"))
        assertTrue(second.containsKey("actorId"))
    }

    @Test
    fun runSchemaDeclaresFormatEnum() {
        val props = GET_WORKFLOW_RUN_PARAMETERS["properties"]!!.jsonObject
        val format = props["format"]!!.jsonObject
        val values = format["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("text", "json"), values)
    }

    @Test
    fun rosterSchemaRequiresRunId() {
        val required = GET_WORKFLOW_RUN_ROSTER_PARAMETERS["required"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("run_id"), required)
        assertTrue(GET_WORKFLOW_RUN_ROSTER_PARAMETERS["properties"]!!.jsonObject.containsKey("run_id"))
    }

    @Test
    fun toolSurfaceRegistersRosterTool() {
        val names = WorkflowTools().all().map { it.name }
        assertTrue("GetWorkflowRunRoster" in names)
        assertTrue("GetWorkflowRun" in names)
    }
}