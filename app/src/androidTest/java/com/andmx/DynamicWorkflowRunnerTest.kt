package com.andmx

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andmx.agent.workflow.WorkflowRunStatus
import com.andmx.agent.workflow.WorkflowService
import com.andmx.agent.workflow.dynamic.DynamicAskRequest
import com.andmx.agent.workflow.dynamic.DynamicAskResult
import com.andmx.agent.workflow.dynamic.DynamicWorkflowHost
import com.andmx.agent.workflow.dynamic.DynamicWorkflowRunner
import com.andmx.agent.workflow.script.WebViewWorkflowScriptAnalyzer
import com.andmx.agent.zcode.WorkflowTools
import com.andmx.data.WorkflowJournalStore
import com.andmx.data.WorkflowStore
import com.andmx.exec.files.GuestFs
import com.andmx.exec.proot.ProotRuntime
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DynamicWorkflowRunnerTest {

    private class Host : DynamicWorkflowHost {
        val asks = AtomicInteger(0)
        val worldReads = AtomicInteger(0)
        val typedStarts = AtomicInteger(0)
        val instructions = java.util.concurrent.CopyOnWriteArrayList<String>()
        val seeds = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var typedRepair = false
        @Volatile var blockSecondAsk = false
        val secondAskStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        private val secondAskGate = kotlinx.coroutines.CompletableDeferred<Unit>()

        override suspend fun createActor(
            runId: String,
            siteId: String,
            ordinal: Int,
            personaJson: String,
            seedJson: String,
        ): String {
            seeds += seedJson
            return "session:$runId:$siteId@$ordinal"
        }

        override fun actorMessageCount(sessionId: String): Int = asks.get()

        override suspend fun ask(request: DynamicAskRequest): DynamicAskResult {
            val call = asks.incrementAndGet()
            instructions += request.instructions
            if (typedRepair && request.typed) {
                typedStarts.incrementAndGet()
                return DynamicAskResult.Submit("{}")
            }
            if (blockSecondAsk && call == 2) {
                secondAskStarted.complete(Unit)
                secondAskGate.await()
            }
            return DynamicAskResult.Text("answer-$call")
        }

        override suspend fun continueAsk(
            request: DynamicAskRequest,
            verdictJson: String,
        ): DynamicAskResult =
            if (typedRepair) DynamicAskResult.Submit("""{"value":"repaired"}""")
            else DynamicAskResult.Text("fixed")

        override fun cancelAsk(requestId: String) {
            secondAskGate.completeExceptionally(
                kotlinx.coroutines.CancellationException("cancelled by test"),
            )
        }

        override suspend fun worldRead(op: String, argsJson: String): String {
            worldReads.incrementAndGet()
            return "\"file-content\""
        }

        override suspend fun publishArtifact(runId: String, requestJson: String): String =
            """{"id":"a","kind":"markdown","version":1,"contentType":"text/markdown","bytes":1,"uri":".andmx/workflow-runs/$runId/a-v1.md","publishedAt":1}"""
    }

    @Test
    fun cancelAndResumeReplaysFinishedAskAndCompletes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var analyzer = WebViewWorkflowScriptAnalyzer(context)
        val host = Host().apply { blockSecondAsk = true }
        val store = WorkflowStore(context, GuestFs(ProotRuntime(context)))
        val journals = WorkflowJournalStore(context)
        var runner = DynamicWorkflowRunner(context, analyzer, host, store, journals, scope)
        try {
            val script = """
                const reviewer = agent('reviewer', { system: 'be brief' });
                const first = await reviewer.ask('first');
                const second = await reviewer.ask('second');
                return first + '|' + second;
            """.trimIndent()
            val (runId, error) = runner.start(script, cwd = "/root/project", conversationId = 0L)
            assertFalse(error.orEmpty(), error != null)
            assertNotNull(runId)
            kotlinx.coroutines.withTimeout(30_000) { host.secondAskStarted.await() }

            runner.cancel(runId!!)
            var snapshot = store.readSnapshot(runId)
            val pausedDeadline = System.currentTimeMillis() + 20_000
            while (snapshot?.status != WorkflowRunStatus.paused &&
                System.currentTimeMillis() < pausedDeadline
            ) {
                delay(200)
                snapshot = store.readSnapshot(runId)
            }
            assertEquals(WorkflowRunStatus.paused, snapshot?.status)

            runner.close()
            analyzer.close()
            analyzer = WebViewWorkflowScriptAnalyzer(context)
            runner = DynamicWorkflowRunner(context, analyzer, Host(), store, journals, scope)
            val (resumed, resumeError) = runner.resume(runId)
            assertTrue(resumeError.orEmpty(), resumed)

            val deadline = System.currentTimeMillis() + 30_000
            while (snapshot?.status != WorkflowRunStatus.completed &&
                System.currentTimeMillis() < deadline
            ) {
                delay(200)
                snapshot = store.readSnapshot(runId)
            }
            assertEquals(WorkflowRunStatus.completed, snapshot?.status)
            assertNotNull(journals.load(runId))
        } finally {
            runner.close()
            analyzer.close()
            scope.cancel()
        }
    }

    @Test
    fun runsScriptThroughRealWebViewAndPersistsJournal() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val analyzer = WebViewWorkflowScriptAnalyzer(context)
        val host = Host()
        val store = WorkflowStore(context, GuestFs(ProotRuntime(context)))
        val journals = WorkflowJournalStore(context)
        val runner = DynamicWorkflowRunner(context, analyzer, host, store, journals, scope)
        try {
            val script = """
                const reviewer = agent('reviewer', { system: 'be brief' });
                artifact.metrics('latency', { metrics: [{ field: 'ms', label: 'Latency', unit: 'ms' }] });
                report({ ms: 12 }, 'latency');
                report({ ms: 18 }, 'latency');
                log('starting');
                const answer = await reviewer.ask('say hi');
                const body = await files.read('README.md');
                return answer + '|' + body;
            """.trimIndent()
            val (runId, error) = runner.start(script, cwd = "/root/project", conversationId = 0L)
            assertFalse(error.orEmpty(), error != null)
            assertNotNull(runId)

            var snapshot = store.readSnapshot(runId!!)
            val deadline = System.currentTimeMillis() + 45_000
            while (snapshot?.status != WorkflowRunStatus.completed &&
                System.currentTimeMillis() < deadline
            ) {
                delay(200)
                snapshot = store.readSnapshot(runId)
            }

            assertEquals(WorkflowRunStatus.completed, snapshot?.status)
            assertEquals(1, host.asks.get())
            assertEquals(1, host.worldReads.get())
            assertTrue(
                "graph nodes were not populated",
                snapshot!!.graph.nodes.any {
                    it.kind == "ask" && it.status == com.andmx.agent.workflow.WorkflowNodeStatus.completed
                },
            )
            assertTrue(
                "preset artifact was not recorded: ${snapshot.presets}",
                snapshot.presets.any { it.id == "latency" && it.kind == "metrics" },
            )
            assertEquals(2, snapshot.reports.count { it.artifactId == "latency" })
            val journal = journals.load(runId)
            assertNotNull(journal)
            assertTrue(journal!!.contains("\"runId\":\"$runId\""))
            assertTrue(journal.contains("\"ask\""))
        } finally {
            runner.close()
            analyzer.close()
            scope.cancel()
        }
    }

    @Test
    fun fillHoleThroughToolCompletesRunAndRecordsArtifact() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val analyzer = WebViewWorkflowScriptAnalyzer(context)
        val host = Host()
        val store = WorkflowStore(context, GuestFs(ProotRuntime(context)))
        val journals = WorkflowJournalStore(context)
        val runner = DynamicWorkflowRunner(context, analyzer, host, store, journals, scope)
        val service = WorkflowService(
            scope = scope,
            store = store,
            actorRunner = { error("dynamic runs do not use the phase actor runner") },
        )
        try {
            val script = """
                const extra = await hole<string>('补充规则', '需要补一段规则');
                const worker = agent('worker');
                const answer = await worker.ask('结论：' + extra);
                await artifact.markdown('hole-result', answer);
                return extra + '|' + answer;
            """.trimIndent()
            val (runId, error) = runner.start(script, cwd = "/root/project", conversationId = 0L)
            assertFalse(error.orEmpty(), error != null)
            assertNotNull(runId)

            var siteId: String? = null
            val holeDeadline = System.currentTimeMillis() + 45_000
            while (siteId == null && System.currentTimeMillis() < holeDeadline) {
                val open = runner.openHoles(runId!!)
                val parsed = runCatching { Json.parseToJsonElement(open) }.getOrNull()
                siteId = parsed?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("siteId")?.jsonPrimitive?.contentOrNull
                if (siteId == null) delay(100)
            }
            assertNotNull("hole never opened", siteId)

            val filled = script.replace(
                "hole<string>('补充规则', '需要补一段规则')",
                "hole<string>('补充规则', async () => '补好的值')",
            )
            val tools = WorkflowTools(
                service = service,
                analyzer = analyzer,
                conversationId = 0L,
                cwd = { "/root/project" },
                dynamicRunner = runner,
            )
            val fill = tools.all().first { it.name == "FillWorkflowHole" }
            val result = fill.execute(
                buildJsonObject {
                    put("run_id", runId)
                    put("hole_id", siteId)
                    put("script", filled)
                },
            )
            assertFalse(result.output, result.isError)

            var snapshot = store.readSnapshot(runId!!)
            val deadline = System.currentTimeMillis() + 30_000
            while (snapshot?.status != WorkflowRunStatus.completed &&
                System.currentTimeMillis() < deadline
            ) {
                delay(200)
                snapshot = store.readSnapshot(runId)
            }

            assertEquals(WorkflowRunStatus.completed, snapshot?.status)
            assertTrue(
                "filled value never reached the actor: ${host.instructions}",
                host.instructions.any { it.contains("补好的值") },
            )
            assertTrue("artifact was not recorded", snapshot?.artifacts?.isNotEmpty() == true)
            assertTrue(
                "result was not recorded: ${snapshot?.resultJson}",
                snapshot?.resultJson?.contains("补好的值") == true,
            )
            assertNotNull(journals.load(runId))
        } finally {
            runner.close()
            analyzer.close()
            scope.cancel()
        }
    }

    @Test
    fun amendThroughToolSupersedesPredecessor() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val analyzer = WebViewWorkflowScriptAnalyzer(context)
        val host = Host().apply { blockSecondAsk = true }
        val store = WorkflowStore(context, GuestFs(ProotRuntime(context)))
        val journals = WorkflowJournalStore(context)
        val runner = DynamicWorkflowRunner(context, analyzer, host, store, journals, scope)
        val service = WorkflowService(
            scope = scope,
            store = store,
            actorRunner = { error("dynamic runs do not use the phase actor runner") },
        )
        try {
            val oldScript = """
                const worker = agent('worker');
                const content = await files.read('a.txt');
                const first = await worker.ask('first');
                const second = await worker.ask('second');
                return content + '|' + first + '|' + second;
            """.trimIndent()
            val (oldRunId, error) = runner.start(oldScript, cwd = "/root/project", conversationId = 0L)
            assertFalse(error.orEmpty(), error != null)
            assertNotNull(oldRunId)
            kotlinx.coroutines.withTimeout(30_000) { host.secondAskStarted.await() }

            val tools = WorkflowTools(
                service = service,
                analyzer = analyzer,
                conversationId = 0L,
                cwd = { "/root/project" },
                dynamicRunner = runner,
            )
            val amend = tools.all().first { it.name == "AmendWorkflow" }
            val result = amend.execute(
                buildJsonObject {
                    put("run_id", oldRunId)
                    put(
                        "script",
                        """
                            const worker = agent('worker');
                            const content = await files.read('a.txt');
                            const first = await worker.ask('first');
                            const amended = await worker.ask('amended');
                            return content + '|' + first + '|' + amended;
                        """.trimIndent(),
                    )
                },
            )
            assertFalse(result.output, result.isError)

            var predecessor = store.readSnapshot(oldRunId!!)
            val deadline = System.currentTimeMillis() + 20_000
            while (predecessor?.pauseReason != "superseded" &&
                System.currentTimeMillis() < deadline
            ) {
                delay(100)
                predecessor = store.readSnapshot(oldRunId)
            }
            assertEquals(WorkflowRunStatus.cancelled, predecessor?.status)
            assertEquals("superseded", predecessor?.pauseReason)
            val successorId = predecessor?.supersededBy
            assertNotNull("successor id was not recorded", successorId)
            assertEquals(oldRunId, store.readSnapshot(successorId!!)?.resumedFrom)

            val (resumed, resumeError) = runner.resume(oldRunId)
            assertFalse(resumed)
            assertTrue(resumeError.orEmpty().contains("superseded"))

            var successor = store.readSnapshot(successorId)
            val successorDeadline = System.currentTimeMillis() + 30_000
            while (successor?.status != WorkflowRunStatus.completed &&
                System.currentTimeMillis() < successorDeadline
            ) {
                delay(200)
                successor = store.readSnapshot(successorId)
            }
            assertEquals(WorkflowRunStatus.completed, successor?.status)
            assertEquals(
                "the first ask should have been imported, instructions=${host.instructions}",
                1,
                host.instructions.count { it == "first" },
            )
            assertTrue("the amended ask never went live", host.instructions.contains("amended"))
            assertEquals(
                "the world read should have been imported",
                1,
                host.worldReads.get(),
            )
            assertTrue(
                "successor did not receive a transcript seed: ${host.seeds}",
                host.seeds.any { it.contains("\"messageCount\":1") },
            )
            assertNotNull(journals.load(successorId))
        } finally {
            runner.close()
            analyzer.close()
            scope.cancel()
        }
    }

    @Test
    fun chainedAmendResolvesAncestorTranscriptSource() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val analyzer = WebViewWorkflowScriptAnalyzer(context)
        val host = Host()
        val store = WorkflowStore(context, GuestFs(ProotRuntime(context)))
        val journals = WorkflowJournalStore(context)
        val runner = DynamicWorkflowRunner(context, analyzer, host, store, journals, scope)
        val service = WorkflowService(
            scope = scope,
            store = store,
            actorRunner = { error("dynamic runs do not use the phase actor runner") },
        )
        val tools = WorkflowTools(
            service = service,
            analyzer = analyzer,
            conversationId = 0L,
            cwd = { "/root/project" },
            dynamicRunner = runner,
        )
        val amend = tools.all().first { it.name == "AmendWorkflow" }

        suspend fun waitCompleted(runId: String): com.andmx.agent.workflow.WorkflowRunSnapshot {
            var snapshot = store.readSnapshot(runId)
            val deadline = System.currentTimeMillis() + 30_000
            while (snapshot?.status != WorkflowRunStatus.completed &&
                System.currentTimeMillis() < deadline
            ) {
                delay(100)
                snapshot = store.readSnapshot(runId)
            }
            assertEquals(WorkflowRunStatus.completed, snapshot?.status)
            return snapshot!!
        }

        suspend fun amendTo(runId: String, script: String): String {
            val result = amend.execute(
                buildJsonObject {
                    put("run_id", runId)
                    put("script", script)
                },
            )
            assertFalse(result.output, result.isError)
            return Regex("new run (\\w+)").find(result.output)?.groupValues?.get(1)
                ?: error("no successor id in: ${result.output}")
        }

        try {
            val base = """
                const worker = agent('worker');
                const content = await files.read('a.txt');
                const first = await worker.ask('first');
                return content + '|' + first;
            """.trimIndent()
            val (baseId, error) = runner.start(base, cwd = "/root/project", conversationId = 0L)
            assertFalse(error.orEmpty(), error != null)
            waitCompleted(baseId!!)

            val middleId = amendTo(baseId, base)
            waitCompleted(middleId)
            assertEquals(baseId, store.readSnapshot(middleId)?.resumedFrom)

            val finalScript = """
                const worker = agent('worker');
                const content = await files.read('a.txt');
                const first = await worker.ask('first');
                const third = await worker.ask('third');
                return content + '|' + first + '|' + third;
            """.trimIndent()
            val finalId = amendTo(middleId, finalScript)
            waitCompleted(finalId)
            assertEquals(middleId, store.readSnapshot(finalId)?.resumedFrom)

            assertEquals(1, host.instructions.count { it == "first" })
            assertEquals(1, host.instructions.count { it == "third" })
            assertEquals(1, host.worldReads.get())
            assertTrue(
                "successor did not fall back to the ancestor session: ${host.seeds}",
                host.seeds.any { it.contains(baseId) },
            )
            assertNotNull(journals.load(finalId))
        } finally {
            runner.close()
            analyzer.close()
            scope.cancel()
        }
    }

    @Test
    fun typedAskRejectsAndRepairs() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val analyzer = WebViewWorkflowScriptAnalyzer(context)
        val host = Host().apply { typedRepair = true }
        val store = WorkflowStore(context, GuestFs(ProotRuntime(context)))
        val journals = WorkflowJournalStore(context)
        val runner = DynamicWorkflowRunner(context, analyzer, host, store, journals, scope)
        try {
            val script = """
                interface Answer { value: string }
                const worker = agent('worker');
                const result = await worker.ask<Answer>('give me a value');
                return result.value;
            """.trimIndent()
            val (runId, error) = runner.start(script, cwd = "/root/project", conversationId = 0L)
            assertFalse(error.orEmpty(), error != null)
            assertNotNull(runId)

            var snapshot = store.readSnapshot(runId!!)
            val deadline = System.currentTimeMillis() + 45_000
            while (snapshot?.status != WorkflowRunStatus.completed &&
                System.currentTimeMillis() < deadline
            ) {
                delay(200)
                snapshot = store.readSnapshot(runId)
            }

            assertEquals(WorkflowRunStatus.completed, snapshot?.status)
            assertTrue("typed ask was not dispatched", host.typedStarts.get() >= 1)
            assertNotNull(journals.load(runId))
        } finally {
            runner.close()
            analyzer.close()
            scope.cancel()
        }
    }
}
