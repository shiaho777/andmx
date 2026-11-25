package com.andmx.agent.zcode

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andmx.agent.GoalToolState
import com.andmx.agent.UpdatePlanTool
import com.andmx.exec.policy.NetworkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SessionToolsSurfaceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun surface(sessionTools: SessionControlHost?) = buildZCodeToolSurface(
        context = context,
        networkPolicy = NetworkPolicy(),
        planTool = UpdatePlanTool(),
        goalState = GoalToolState(),
        todo = TodoState(),
        planMode = PlanModeState(),
        cwdProvider = { "/root" },
        includeLegacyAliases = false,
        sessionTools = sessionTools,
    )

    private fun fakeHost(): SessionControlHost = object : SessionControlHost {
        override suspend fun listSessions(query: String?, includeArchived: Boolean): List<SessionSummary> = emptyList()
        override suspend fun readSession(sessionId: String, maxTokens: Int): SessionContextRead =
            SessionContextRead(status = "not_found")
        override suspend fun createSession(title: String, task: String?, workspace: String?): SessionCreateResult =
            SessionCreateResult(1L, "1", title, "none")
        override suspend fun sendToSession(sessionId: String, message: String, requestId: String): SessionSendResult =
            SessionSendResult(requestId, sessionId, "sent")
        override suspend fun waitState(sessionId: String): SessionWaitState? = null
        override suspend fun mutateSession(action: SessionMutation, sessionId: String, value: String?): String = "ok"
    }

    @Test
    fun defaultSurfaceHasNoSessionControlTools() {
        val names = surface(null).map { it.name }.toSet()
        assertTrue(names.intersect(SESSION_CONTROL_TOOL_NAMES).isEmpty())
    }

    @Test
    fun injectedHostRegistersAllSessionTools() {
        val tools = surface(fakeHost())
        val sessionTools = tools.filter { it.name in SESSION_CONTROL_TOOL_NAMES }
        assertEquals(SESSION_CONTROL_TOOL_NAMES, sessionTools.map { it.name }.toSet())
        assertEquals(SESSION_CONTROL_TOOL_NAMES.size, sessionTools.size)
        assertEquals(SESSION_CONTROL_TOOL_NAMES.size, tools.count { it.name in SESSION_CONTROL_TOOL_NAMES })
    }
}