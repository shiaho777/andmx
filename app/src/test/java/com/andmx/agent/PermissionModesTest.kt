package com.andmx.agent

import com.andmx.agent.permission.CollaborationMode
import com.andmx.agent.permission.PermissionContext
import com.andmx.agent.permission.PermissionService
import com.andmx.agent.permission.PermissionToolCapability
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionModesTest {
    private fun decide(mode: CollaborationMode, tool: Tool) =
        PermissionService().checkPermission(
            PermissionContext(toolName = tool.name, mode = mode, riskLevel = tool.riskLevel),
            PermissionToolCapability(
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
        )

    @Test
    fun autoAllowsLowRiskAndAsksForDestructive() {
        val read = decide(CollaborationMode.AUTO, readTool())
        val bash = decide(CollaborationMode.AUTO, bashTool())
        assertEquals("allow", read.decision)
        assertEquals("ask", bash.decision)
        assertEquals("mode.auto.destructive", bash.ruleId)
    }

    @Test
    fun editAllowsFileEditsAndAsksForBash() {
        assertEquals("allow", decide(CollaborationMode.EDIT, editTool()).decision)
        assertEquals("ask", decide(CollaborationMode.EDIT, bashTool()).decision)
    }

    @Test
    fun buildAllowsReadOnlyAndAsksForSideEffects() {
        assertEquals("allow", decide(CollaborationMode.BUILD, readTool()).decision)
        assertEquals("ask", decide(CollaborationMode.BUILD, editTool()).decision)
    }

    @Test
    fun planAllowsReadOnlyOnly() {
        assertEquals("allow", decide(CollaborationMode.PLAN, readTool()).decision)
        assertEquals("deny", decide(CollaborationMode.PLAN, editTool()).decision)
    }

    private fun readTool() = object : Tool {
        override val name = "Read"
        override val description = ""
        override val parameters = buildJsonObject { }
        override val risk = ToolRisk.READ
        override suspend fun execute(args: kotlinx.serialization.json.JsonObject) = ToolResult("ok")
    }

    private fun editTool() = object : Tool {
        override val name = "Edit"
        override val description = ""
        override val parameters = buildJsonObject { }
        override val risk = ToolRisk.WRITE
        override val permissionName = "edit"
        override suspend fun execute(args: kotlinx.serialization.json.JsonObject) = ToolResult("ok")
    }

    private fun bashTool() = object : Tool {
        override val name = "Bash"
        override val description = ""
        override val parameters = buildJsonObject { }
        override val risk = ToolRisk.EXECUTE
        override val destructive = true
        override suspend fun execute(args: kotlinx.serialization.json.JsonObject) = ToolResult("ok")
    }
}