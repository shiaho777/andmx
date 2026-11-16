package com.andmx.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andmx.agent.zcode.PlanModeState
import com.andmx.agent.zcode.TodoState
import com.andmx.agent.zcode.buildZCodeToolSurface
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
        val tools = surface()
        val computer = tools.single { it.name == "computer" }
        assertEquals(ToolRisk.EXECUTE, computer.risk)
        val service = com.andmx.agent.permission.PermissionService()
        fun planDecision(tool: Tool): String = service.checkPermission(
            com.andmx.agent.permission.PermissionContext(
                toolName = tool.name,
                mode = com.andmx.agent.permission.CollaborationMode.PLAN,
                riskLevel = tool.riskLevel,
            ),
            com.andmx.agent.permission.PermissionToolCapability(
                userApprovalRule = tool.userApprovalRule,
                allowedInPlanMode = tool.allowedInPlanMode,
                alwaysAsk = tool.alwaysAsk,
                readOnly = tool.readOnly,
                destructive = tool.destructive,
                requiresUserInteraction = tool.requiresUserInteraction,
                sideEffectScope = tool.sideEffectScope,
                riskLevel = tool.riskLevel,
                needsApproval = tool.needsApproval,
                permissionName = tool.permissionName,
            ),
        ).decision
        assertEquals("deny", planDecision(computer))
        assertEquals("allow", planDecision(tools.first { it.name == "Read" }))
        assertEquals("deny", planDecision(tools.first { it.name == "Bash" }))
        assertEquals("deny", planDecision(tools.first { it.name == "Write" }))
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
