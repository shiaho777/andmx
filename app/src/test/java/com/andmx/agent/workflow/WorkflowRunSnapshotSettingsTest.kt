package com.andmx.agent.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkflowRunSnapshotSettingsTest {

    private fun snapshot(
        subagentModel: String? = null,
        maxConcurrency: Int? = null,
    ) = WorkflowRunSnapshot(
        artifacts = emptyList(),
        createdAt = "t0",
        cwd = "/",
        graph = WorkflowGraph(edges = emptyList(), nodes = emptyList()),
        kind = "dynamic",
        maxConcurrency = maxConcurrency,
        phaseOrder = emptyList(),
        phases = emptyList(),
        runId = "run_test",
        status = WorkflowRunStatus.running,
        strategy = WorkflowStrategy.DEFAULT,
        subagentModel = subagentModel,
        task = "do stuff",
        updatedAt = "t0",
    )

    @Test
    fun settingsSurviveSnapshotRoundTrip() {
        val encoded = workflowJson.encodeToString(
            WorkflowRunSnapshot.serializer(),
            snapshot(subagentModel = "openai/gpt-5\$high", maxConcurrency = 12),
        )
        val decoded = workflowJson.decodeFromString(WorkflowRunSnapshot.serializer(), encoded)

        assertEquals("openai/gpt-5\$high", decoded.subagentModel)
        assertEquals(12, decoded.maxConcurrency)
    }

    @Test
    fun missingSettingsDecodeAsInheritedDefaults() {
        val encoded = workflowJson.encodeToString(WorkflowRunSnapshot.serializer(), snapshot())
        val decoded = workflowJson.decodeFromString(WorkflowRunSnapshot.serializer(), encoded)

        assertNull(decoded.subagentModel)
        assertNull(decoded.maxConcurrency)
    }
}
