package com.andmx.agent

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCapabilitiesTest {

    @Test
    fun capabilitiesAreSortedByRiskThenName() {
        val caps = listOf(
            fakeTool("z_exec", ToolRisk.EXECUTE),
            fakeTool("b_read", ToolRisk.READ),
            fakeTool("a_read", ToolRisk.READ),
            fakeTool("write", ToolRisk.WRITE),
        ).toCapabilities()

        assertEquals(listOf("a_read", "b_read", "write", "z_exec"), caps.map { it.name })
    }

    @Test
    fun approvalEffectMatchesPolicy() {
        assertEquals("自动", approvalEffect(ApprovalMode.FULL, ToolRisk.WRITE))
        assertEquals("询问", approvalEffect(ApprovalMode.ASK, ToolRisk.WRITE))
        assertEquals("阻止", approvalEffect(ApprovalMode.READ_ONLY, ToolRisk.WRITE))
    }

    private fun fakeTool(name: String, risk: ToolRisk): Tool = object : Tool {
        override val name = name
        override val description = "desc"
        override val parameters = JsonObject(emptyMap())
        override val risk = risk
        override suspend fun execute(args: JsonObject): ToolResult = ToolResult("")
    }
}
