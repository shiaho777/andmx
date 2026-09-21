package com.andmx.agent.workflow

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WorkflowEngineTest {

    private fun node(
        id: String,
        status: WorkflowNodeStatus = WorkflowNodeStatus.pending,
        dependsOn: List<String> = emptyList(),
        phase: String? = "exec",
        attempts: Int? = null,
    ) = WorkflowGraphNode(
        attempts = attempts, dependsOn = dependsOn, id = id,
        phase = phase, status = status, title = id,
    )

    private fun snapshot(
        nodes: List<WorkflowGraphNode>,
        edges: List<WorkflowGraphEdge> = emptyList(),
        collections: List<WorkflowGraphCollection>? = null,
        strategy: WorkflowStrategy = WorkflowStrategy.DEFAULT,
    ) = WorkflowRunSnapshot(
        artifacts = emptyList(),
        createdAt = "t0", cwd = "/",
        graph = WorkflowGraph(collections = collections, edges = edges, nodes = nodes),
        kind = "expert",
        phaseOrder = listOf("exec"),
        phases = listOf(
            WorkflowPhaseSnapshot(phase = "exec", status = WorkflowNodeStatus.active),
        ),
        runId = "run_test", status = WorkflowRunStatus.running,
        strategy = strategy, task = "do stuff", updatedAt = "t0",
    )

    private class MemStore : WorkflowStoreSink {
        val artifacts = mutableMapOf<String, String>()
        val events = mutableListOf<WorkflowEvent>()
        var snap: WorkflowRunSnapshot? = null
        override suspend fun writeSnapshot(snapshot: WorkflowRunSnapshot) { snap = snapshot }
        override suspend fun readSnapshot(runId: String) = snap?.takeIf { it.runId == runId }
        override suspend fun writeArtifact(runId: String, relativePath: String, content: String): String {
            artifacts[relativePath] = content
            return relativePath
        }
        override suspend fun appendEvent(event: WorkflowEvent) { events += event }
    }

    private class RecordingRunner(
        private val behavior: suspend (WorkflowAgentInput) -> String = { "ok:${it.node?.id}" },
    ) : WorkflowAgentRunner {
        val calls = CopyOnWriteArrayList<WorkflowAgentInput>()
        override suspend fun run(input: WorkflowAgentInput): WorkflowAgentResult {
            calls += input
            return WorkflowAgentResult(response = behavior(input), sessionId = "s1", model = "m1")
        }
    }

    private fun options(snap: WorkflowRunSnapshot, phase: String = "exec") =
        SchedulerRunOptions(cwd = "/", phase = phase, snapshot = snap)

    // ── parsers ────────────────────────────────────────────────

    @Test
    fun parsesPlannerResultFromFencedJsonWithLooseFields() {
        val response = """
            Here is the plan:
            ```json
            {"nodes":[
              {"name":"n1","summary":"first","depends_on":[]},
              {"id":"n2","title":"second","references":["n1"]}
            ],"edges":[{"source":"n1","target":"n2"}]}
            ```
        """.trimIndent()
        val result = WorkflowParsers.parsePlannerResult(response, "exec")
        assertEquals(2, result.nodes.size)
        assertEquals("n1", result.nodes[0].id)
        assertEquals("first", result.nodes[0].title)
        assertEquals(listOf("n1"), result.nodes[1].dependsOn)
        assertEquals(listOf(WorkflowGraphEdge("n1", "n2")), result.edges)
    }

    @Test
    fun parsesEmbeddedJsonObjectInsideProse() {
        val response = "reasoning text {\"nodes\":[{\"id\":\"a\",\"title\":\"A\"}]} trailing"
        val result = WorkflowParsers.parsePlannerResult(response, "exec")
        assertEquals(listOf("a"), result.nodes.map { it.id })
    }

    @Test
    fun plannerResultRejectsNonJson() {
        try {
            WorkflowParsers.parsePlannerResult("no json here", "exec")
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("JSON"))
        }
    }

    @Test
    fun gateRootSeedNodesWiresRootsBehindGate() {
        val seed = WorkflowGraphSeed(
            nodes = listOf(
                WorkflowGraphPlannerNode(id = "r1", title = "root1"),
                WorkflowGraphPlannerNode(id = "r2", title = "root2"),
                WorkflowGraphPlannerNode(id = "c1", title = "child", dependsOn = listOf("r1")),
            ),
        )
        val gated = WorkflowParsers.gateRootSeedNodes(seed, "phase_gate")
        assertEquals(listOf("phase_gate"), gated.nodes.first { it.id == "r1" }.dependsOn)
        assertEquals(listOf("phase_gate"), gated.nodes.first { it.id == "r2" }.dependsOn)
        assertEquals(listOf("r1"), gated.nodes.first { it.id == "c1" }.dependsOn)
        assertTrue(gated.edges.contains(WorkflowGraphEdge("phase_gate", "r1")))
        assertTrue(gated.edges.contains(WorkflowGraphEdge("phase_gate", "r2")))
    }

    @Test
    fun parseCriticResultAcceptsLegacyShapes() {
        val fail = WorkflowParsers.parseCriticResult(
            """{"passed":false,"reasoning":"gaps","reopenNodes":["n1"]}""",
        )
        assertEquals("fail", fail.verdict)
        assertEquals(listOf("n1"), fail.reopenProposals.map { it.nodeId })

        val pass = WorkflowParsers.parseCriticResult(
            """{"verdict":"pass","reasoning":"ok"}""",
        )
        assertEquals("pass", pass.verdict)

        val approved = WorkflowParsers.parseCriticResult(
            """{"overallVerdict":"conditionallyApproved","acceptance_gaps":["minor"]}""",
        )
        assertEquals("pass", approved.verdict)
        assertEquals(listOf("minor"), approved.acceptanceGaps)
    }

    @Test
    fun parseNodePromptUpdatesNormalizesAliases() {
        val set = WorkflowParsers.parseWorkflowNodePromptUpdateSet(
            """{"updates":[{"node_id":"n1","instructions":"new prompt","title":"renamed"}]}""",
        )
        assertNotNull(set)
        val update = set!!.nodes.single()
        assertEquals("n1", update.id)
        assertEquals("new prompt", update.prompt)
        assertEquals("renamed", update.title)
    }

    // ── lifecycle ──────────────────────────────────────────────

    @Test
    fun applyGraphSeedAddsNodesAndDependsOnEdges() {
        val seed = WorkflowGraphSeed(
            nodes = listOf(
                WorkflowGraphPlannerNode(id = "a", title = "A"),
                WorkflowGraphPlannerNode(id = "b", title = "B", dependsOn = listOf("a")),
            ),
        )
        val result = WorkflowLifecycle.applyGraphSeed(snapshot(emptyList()), seed, "exec", "t1")
        assertTrue(result.changed)
        assertEquals(listOf("a", "b"), result.addedNodes.map { it.id })
        assertEquals(listOf(WorkflowGraphEdge("a", "b")), result.addedEdges)
        assertEquals(2, result.snapshot.graph.nodes.size)
    }

    @Test
    fun applyGraphSeedRejectsDuplicatesAndUnknownRefs() {
        val existing = snapshot(listOf(node("a", status = WorkflowNodeStatus.completed)))
        try {
            WorkflowLifecycle.applyGraphSeed(
                existing,
                WorkflowGraphSeed(nodes = listOf(WorkflowGraphPlannerNode(id = "a", title = "dup"))),
                "exec", "t1",
            )
            fail("expected duplicate rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("duplicate"))
        }
        try {
            WorkflowLifecycle.applyGraphSeed(
                existing,
                WorkflowGraphSeed(
                    edges = listOf(WorkflowGraphEdge("ghost", "a")),
                ),
                "exec", "t1",
            )
            fail("expected unknown source rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("unknown source"))
        }
    }

    @Test
    fun applyGraphSeedRejectsCycles() {
        val seed = WorkflowGraphSeed(
            nodes = listOf(
                WorkflowGraphPlannerNode(id = "x", title = "X", dependsOn = listOf("y")),
                WorkflowGraphPlannerNode(id = "y", title = "Y", dependsOn = listOf("x")),
            ),
        )
        try {
            WorkflowLifecycle.applyGraphSeed(snapshot(emptyList()), seed, "exec", "t1")
            fail("expected cycle rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("cycle"))
        }
    }

    @Test
    fun reconcileForResumeResetsActiveNodesAndActivities() {
        val snap = snapshot(
            listOf(
                node("a", status = WorkflowNodeStatus.completed),
                node("b", status = WorkflowNodeStatus.active),
            ),
        ).copy(
            activities = listOf(
                WorkflowActivitySnapshot(
                    activityId = "act1", kind = WorkflowActivityKind.agent_session,
                    nodeId = "b", phase = "exec", startedAt = "t0",
                    status = WorkflowNodeStatus.active,
                ),
            ),
        )
        val result = WorkflowLifecycle.reconcileForResume(snap, timestamp = "t9")
        assertTrue(result.changed)
        val b = result.snapshot.graph.nodes.first { it.id == "b" }
        assertEquals(WorkflowNodeStatus.pending, b.status)
        val act = result.snapshot.activities.single()
        assertEquals(WorkflowNodeStatus.cancelled, act.status)
        assertEquals("t9", act.completedAt)
        assertEquals("exec", result.phaseIds.single())
    }

    @Test
    fun cancelMarksRunNodesPhasesCancelled() {
        val snap = snapshot(
            listOf(
                node("a", status = WorkflowNodeStatus.completed),
                node("b", status = WorkflowNodeStatus.pending),
            ),
        )
        val result = WorkflowLifecycle.cancel(snap, timestamp = "t2")
        assertEquals(WorkflowRunStatus.cancelled, result.snapshot.status)
        assertEquals(WorkflowNodeStatus.completed, result.snapshot.graph.nodes[0].status)
        assertEquals(WorkflowNodeStatus.cancelled, result.snapshot.graph.nodes[1].status)
    }

    @Test
    fun reopenNodeResetsStatusAndCountsAttempts() {
        val snap = snapshot(
            listOf(node("a", status = WorkflowNodeStatus.completed)),
        )
        val (next, attempts) = WorkflowLifecycle.reopenNode(snap, "a", timestamp = "t3")
        assertEquals(1, attempts)
        assertEquals(WorkflowNodeStatus.pending, next.graph.nodes.single().status)
        try {
            WorkflowLifecycle.reopenNode(
                snapshot(listOf(node("a", status = WorkflowNodeStatus.active))),
                "a", timestamp = "t3",
            )
            fail("expected reopenable status rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Cannot reopen"))
        }
    }

    // ── graph ops ──────────────────────────────────────────────

    @Test
    fun deriveSchedulerStateMarksReadyAndBlocked() {
        val graph = WorkflowGraph(
            edges = listOf(WorkflowGraphEdge("a", "b")),
            nodes = listOf(node("a"), node("b", dependsOn = listOf("a"))),
        )
        val state = deriveWorkflowSchedulerState(graph)
        assertEquals(listOf("a"), state.readyNodeIds)
        assertEquals(listOf("b" to listOf("a")), state.blockedNodes)

        val done = graph.copy(
            nodes = graph.nodes.map {
                if (it.id == "a") it.copy(status = WorkflowNodeStatus.completed) else it
            },
        )
        assertEquals(listOf("b"), deriveWorkflowSchedulerState(done).readyNodeIds)
    }

    @Test
    fun failedDependencyStillUnblocksDependents() {
        val graph = WorkflowGraph(
            edges = emptyList(),
            nodes = listOf(
                node("a", status = WorkflowNodeStatus.failed),
                node("b", dependsOn = listOf("a")),
            ),
        )
        assertEquals(listOf("b"), deriveWorkflowSchedulerState(graph).readyNodeIds)
    }

    @Test
    fun definitionValidateCatchesOrderMismatch() {
        val def = WorkflowDefinition(
            definitionId = "d1", definitionVersion = "1", kind = "expert",
            phaseOrder = listOf("p1", "ghost"),
            phases = listOf(
                WorkflowPhaseDefinition(description = "d", phase = "p1", title = "P1"),
                WorkflowPhaseDefinition(description = "d", phase = "p2", title = "P2"),
            ),
            strategy = WorkflowStrategy.DEFAULT, title = "wf",
        )
        val errors = def.validate()
        assertTrue(errors.any { it.contains("unknown phase: ghost") })
        assertTrue(errors.any { it.contains("missing from phaseOrder: p2") })
    }

    // ── scheduler ──────────────────────────────────────────────

    @Test
    fun schedulerRunsDependenciesInOrder() = runTest {
        val snap = snapshot(
            listOf(node("a"), node("b", dependsOn = listOf("a"))),
            edges = listOf(WorkflowGraphEdge("a", "b")),
        )
        val store = MemStore()
        val runner = RecordingRunner()
        val result = WorkflowGraphScheduler(store, runner).run(options(snap))
        assertEquals("completed", result.reason)
        assertEquals("completed", result.status)
        val order = runner.calls.map { it.node!!.id }
        assertTrue(order.indexOf("a") < order.indexOf("b"))
        val nodes = result.snapshot.graph.nodes.associateBy { it.id }
        assertEquals(WorkflowNodeStatus.completed, nodes["a"]!!.status)
        assertEquals(WorkflowNodeStatus.completed, nodes["b"]!!.status)
        assertEquals(2, result.snapshot.artifacts.size)
    }

    @Test
    fun schedulerRetriesFailedNodeThenCompletes() = runTest {
        val snap = snapshot(listOf(node("a")))
        val store = MemStore()
        var attempts = 0
        val runner = RecordingRunner {
            attempts++
            if (attempts == 1) throw IllegalStateException("flaky")
            "recovered"
        }
        val result = WorkflowGraphScheduler(store, runner).run(options(snap))
        assertEquals("completed", result.reason)
        assertEquals(2, attempts)
        val node = result.snapshot.graph.nodes.single()
        assertEquals(WorkflowNodeStatus.completed, node.status)
        assertEquals(1, node.attempts)
        assertTrue(store.artifacts.values.contains("recovered"))
    }

    @Test
    fun schedulerPausesAfterConsecutiveErrorThreshold() = runTest {
        val strategy = WorkflowStrategy.DEFAULT.copy(
            executor = WorkflowStrategy.DEFAULT.executor.copy(maxConsecutiveErrors = 2),
        )
        val snap = snapshot(listOf(node("a")), strategy = strategy)
        val runner = RecordingRunner { throw IllegalStateException("always fails") }
        val result = WorkflowGraphScheduler(MemStore(), runner).run(options(snap))
        assertEquals("error_threshold", result.reason)
        assertEquals("paused", result.status)
        val node = result.snapshot.graph.nodes.single()
        assertEquals(WorkflowNodeStatus.failed, node.status)
        assertEquals(2, node.attempts)
    }

    @Test
    fun schedulerDetectsDeadlockOnMissingDependency() = runTest {
        val snap = snapshot(listOf(node("a", dependsOn = listOf("ghost"))))
        val runner = RecordingRunner()
        val result = WorkflowGraphScheduler(MemStore(), runner).run(options(snap))
        assertEquals("deadlock", result.reason)
        assertEquals("paused", result.status)
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun schedulerCancellationPropagates() = runTest {
        val snap = snapshot(listOf(node("a")))
        val runner = RecordingRunner { awaitCancellation() }
        val scheduler = WorkflowGraphScheduler(MemStore(), runner)
        val job = async { scheduler.run(options(snap)) }
        testScheduler.advanceUntilIdle()
        job.cancel()
        try {
            job.await()
            fail("expected cancellation")
        } catch (e: CancellationException) {
            // expected
        }
    }

    @Test
    fun executableNodeIdsRestrictsRunScope() = runTest {
        val snap = snapshot(listOf(node("a"), node("b")))
        val runner = RecordingRunner()
        val result = WorkflowGraphScheduler(MemStore(), runner).run(
            options(snap).copy(executableNodeIds = setOf("a")),
        )
        assertEquals("completed", result.reason)
        assertEquals(listOf("a"), runner.calls.map { it.node!!.id })
        assertEquals(WorkflowNodeStatus.pending, result.snapshot.graph.nodes[1].status)
    }

    @Test
    fun sessionLinksDeriveFromActivities() {
        val snap = snapshot(emptyList()).copy(
            activities = listOf(
                WorkflowActivitySnapshot(
                    activityId = "a1", completedAt = "t1",
                    kind = WorkflowActivityKind.agent_session, model = "m",
                    nodeId = "n1", phase = "exec", sessionId = "s1",
                    startedAt = "t0", status = WorkflowNodeStatus.completed,
                ),
                WorkflowActivitySnapshot(
                    activityId = "a2",
                    kind = WorkflowActivityKind.agent_session, model = "m",
                    nodeId = "n1", phase = "exec", sessionId = "s2",
                    startedAt = "t2", status = WorkflowNodeStatus.active,
                ),
            ),
        )
        val links = deriveWorkflowSessionLinks(snap.activities, snap.runId)
        assertEquals(2, links.size)
        assertEquals(1, links[0].attempt)
        assertEquals(2, links[1].attempt)
        assertEquals("completed", links[0].status)
        assertEquals("running", links[1].status)
    }

    @Test
    fun snapshotRoundTripsThroughWorkflowJson() {
        val snap = snapshot(listOf(node("a")), edges = listOf(WorkflowGraphEdge("a", "b")))
        val decoded = workflowJson.decodeFromString(
            WorkflowRunSnapshot.serializer(),
            workflowJson.encodeToString(WorkflowRunSnapshot.serializer(), snap),
        )
        assertEquals(snap, decoded)
        assertNull(decoded.failure)
    }

    @Test
    fun artifactWritePathStaysInsideRunDirectory() {
        val safe = WorkflowGraphScheduler.safeArtifactName("../evil/../../x.md")
        assertFalse(safe.contains("/"))
        assertFalse(safe.contains("\\"))
    }
}
