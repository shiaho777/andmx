package com.andmx

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andmx.agent.workflow.WorkflowRunStatus
import com.andmx.agent.workflow.dynamic.DynamicAskRequest
import com.andmx.agent.workflow.dynamic.DynamicAskResult
import com.andmx.agent.workflow.dynamic.DynamicWorkflowHost
import com.andmx.agent.workflow.dynamic.DynamicWorkflowRunner
import com.andmx.agent.workflow.script.WebViewWorkflowScriptAnalyzer
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
        @Volatile var blockSecondAsk = false
        val secondAskStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        private val secondAskGate = kotlinx.coroutines.CompletableDeferred<Unit>()

        override suspend fun createActor(
            runId: String,
            siteId: String,
            ordinal: Int,
            personaJson: String,
        ): String = "session:$runId:$siteId@$ordinal"

        override suspend fun ask(request: DynamicAskRequest): DynamicAskResult {
            val call = asks.incrementAndGet()
            if (blockSecondAsk && call == 2) {
                secondAskStarted.complete(Unit)
                secondAskGate.await()
            }
            return DynamicAskResult.Text("answer-$call")
        }

        override suspend fun continueAsk(
            request: DynamicAskRequest,
            verdictJson: String,
        ): DynamicAskResult = DynamicAskResult.Text("fixed")

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
        val analyzer = WebViewWorkflowScriptAnalyzer(context)
        val host = Host().apply { blockSecondAsk = true }
        val store = WorkflowStore(context, GuestFs(ProotRuntime(context)))
        val journals = WorkflowJournalStore(context)
        val runner = DynamicWorkflowRunner(context, analyzer, host, store, journals, scope)
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

            host.blockSecondAsk = false
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
}
