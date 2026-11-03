package com.andmx.agent.zcode

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andmx.agent.workflow.WorkflowAgentResult
import com.andmx.agent.workflow.WorkflowService
import com.andmx.data.WorkflowStore
import com.andmx.data.AndmxDatabase
import com.andmx.exec.files.GuestFs
import com.andmx.exec.proot.ProotRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WorkflowResumeToolTest {

    @Test
    fun resumeReportsUnknownRunInsteadOfStubUnavailable() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val service = WorkflowService(
            scope = scope,
            store = WorkflowStore(context, GuestFs(ProotRuntime(context))),
            actorRunner = { WorkflowAgentResult("ok") },
        )
        try {
            val result = WorkflowTools(service = service).Resume().execute(
                buildJsonObject { put("run_id", "wf_missing") },
            )

            assertTrue(result.isError)
            assertTrue(result.output.contains("not found"))
        } finally {
            scope.cancel()
            AndmxDatabase.resetForTests()
        }
    }

    @Test
    fun readArtifactTextRoundTripsThroughService() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = WorkflowStore(context, GuestFs(ProotRuntime(context)))
        val service = WorkflowService(
            scope = scope,
            store = store,
            actorRunner = { WorkflowAgentResult("ok") },
        )
        try {
            val path = store.writeArtifact("wf_artifact", "artifacts/note-v1.md", "# hello\n")
            assertEquals("# hello\n", service.readArtifactText(path))
        } finally {
            scope.cancel()
            AndmxDatabase.resetForTests()
        }
    }
}
