package com.andmx.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andmx.agent.zcode.PlanModeState
import com.andmx.agent.zcode.TodoState
import com.andmx.agent.zcode.buildZCodeToolSurface
import com.andmx.agent.zcode.isPlanModeAllowed
import com.andmx.exec.policy.NetworkPolicy
import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ComputerToolSurfaceTest {
    private fun surface(): List<Tool> = buildZCodeToolSurface(
        context = ApplicationProvider.getApplicationContext<Context>(),
        networkPolicy = NetworkPolicy(),
        planTool = UpdatePlanTool(),
        goalState = GoalToolState(),
        todo = TodoState(),
        planMode = PlanModeState(),
        cwdProvider = { "/data" },
        includeLegacyAliases = false,
    )

    @Test
    fun computerToolDefaultsToExecuteRiskAndIsGatedOutOfPlanMode() {
        val computer = surface().single { it.name == "computer" }
        assertEquals(ToolRisk.EXECUTE, computer.risk)
        assertFalse(isPlanModeAllowed("computer"))
        assertTrue(isPlanModeAllowed("read"))
        assertTrue(isPlanModeAllowed("mcp_anything"))
        assertFalse(isPlanModeAllowed("bash"))
        assertFalse(isPlanModeAllowed("write"))
    }

    @Test
    fun zcodeWireNamesExposeCoreSurface() {
        val names = surface().map { it.name }.toSet()
        assertTrue("Read" in names)
        assertTrue("Write" in names)
        assertTrue("Edit" in names)
        assertTrue("Bash" in names)
        assertTrue("Grep" in names)
        assertTrue("Glob" in names)
        assertTrue("computer" in names)
        val read = surface().first { it.name == "Read" }
        assertEquals(ToolRisk.READ, read.risk)
        assertNotNull(read.parameters)
    }
}
