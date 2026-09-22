package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanFilesTest {

    @Test
    fun planRelativePathUsesAndmxPlansDir() {
        assertEquals(".andmx/plans/plan-42.md", PlanFiles.planRelativePath(42))
    }

    @Test
    fun formatPlanFileReferenceMatchesUpstreamShape() {
        val body = PlanFiles.formatPlanFileReference("do thing", ".andmx/plans/plan-1.md")
        assertTrue(body.startsWith("A plan file exists from plan mode at: .andmx/plans/plan-1.md"))
        assertTrue(body.contains("Plan contents:"))
        assertTrue(body.contains("do thing"))
        assertTrue(body.endsWith("If this plan is relevant to the current work and not already complete, continue working on it."))
    }
}
