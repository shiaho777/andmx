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

        override suspend fun createActor(
            runId: String,
            siteId: String,
            ordinal: Int,
            personaJson: String,
        ): String = "session:$runId:$siteId@$ordinal"

        override suspend fun ask(request: DynamicAskRequest): DynamicAskResult {
            asks.incrementAndGet()
            return DynamicAskResult.Text("actor-done")
        }

        override suspend fun continueAsk(
            request: DynamicAskRequest,
            verdictJson: String,
        ): DynamicAskResult = DynamicAskResult.Text("fixed")

        override fun cancelAsk(requestId: String) = Unit

        override suspend fun worldRead(op: String, argsJson: String): String {
            worldReads.incrementAndGet()
            return "\"file-content\""
        }

        override suspend fun publishArtifact(runId: String, requestJson: String): String =
            """{"id":"a","kind":"markdown","version":1,"contentType":"text/markdown","bytes":1,"uri":".andmx/workflow-runs/$runId/a-v1.md","publishedAt":1}"""
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
