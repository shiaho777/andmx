package com.andmx.agent

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionModesTest {
    @Test
    fun autoAllowsLowRiskAndAsksForDestructive() {
        val read = PermissionModes.decide(PermissionModes.Kind.AUTO, readTool())
        val bash = PermissionModes.decide(PermissionModes.Kind.AUTO, bashTool())
        assertEquals(PermissionModes.Effect.ALLOW, read.effect)
        assertEquals(PermissionModes.Effect.ASK, bash.effect)
        assertEquals("mode.auto.destructive", bash.ruleId)
    }

    @Test
    fun editAllowsFileEditsAndAsksForBash() {
        val edit = PermissionModes.decide(PermissionModes.Kind.EDIT, editTool())
        val bash = PermissionModes.decide(PermissionModes.Kind.EDIT, bashTool())
        assertEquals(PermissionModes.Effect.ALLOW, edit.effect)
        assertEquals(PermissionModes.Effect.ASK, bash.effect)
    }

    @Test
    fun buildAllowsReadOnlyAndAsksForSideEffects() {
        assertEquals(
            PermissionModes.Effect.ALLOW,
            PermissionModes.decide(PermissionModes.Kind.BUILD, readTool()).effect,
        )
        assertEquals(
            PermissionModes.Effect.ASK,
            PermissionModes.decide(PermissionModes.Kind.BUILD, editTool()).effect,
        )
    }

    @Test
    fun planAllowsReadOnlyOnly() {
        assertEquals(
            PermissionModes.Effect.ALLOW,
            PermissionModes.decide(PermissionModes.Kind.PLAN, readTool()).effect,
        )
        assertEquals(
            PermissionModes.Effect.DENY,
            PermissionModes.decide(PermissionModes.Kind.PLAN, editTool()).effect,
        )
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
