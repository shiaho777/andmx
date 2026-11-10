package com.andmx.ui2.chat

import com.andmx.agent.workflow.WorkflowRunStatus
import com.andmx.data.WorkflowStore
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkflowCapsuleTest {

    private fun run(id: String, status: WorkflowRunStatus) = WorkflowStore.RunListItem(
        runId = id,
        definitionId = "dynamic",
        kind = "dynamic",
        task = "task-$id",
        status = status,
        cwd = "/",
        createdAtMs = 1,
        updatedAtMs = 1,
    )

    @Test
    fun onlyRunningAndPendingRunsReachTheCapsule() {
        val runs = listOf(
            run("a", WorkflowRunStatus.completed),
            run("b", WorkflowRunStatus.running),
            run("c", WorkflowRunStatus.pending),
            run("d", WorkflowRunStatus.failed),
        )

        assertEquals(listOf("b", "c"), activeWorkflowRuns(runs).map { it.runId })
    }

    @Test
    fun emptyInputStaysEmpty() {
        assertEquals(emptyList<WorkflowStore.RunListItem>(), activeWorkflowRuns(emptyList()))
    }
}
