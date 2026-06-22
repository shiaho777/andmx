package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPlanSnapshotTest {

    @Test
    fun infersBlockedPlanForPendingApproval() {
        val plan = inferTaskPlan(
            goalText = "修复构建",
            goalPhaseName = "WAITING_APPROVAL",
            goalPhaseLabel = "等待授权",
            goalNote = "等待授权: run_shell",
            hasMessages = true,
            toolEvents = 1,
            runningTools = 0,
            failedTools = 0,
            changedFiles = 0,
            pendingApprovals = 1,
        )

        assertEquals(PlanItemStatus.BLOCKED, plan.items[2].status)
        assertEquals(PlanItemStatus.BLOCKED, plan.items[4].status)
        assertTrue(taskPlanText(plan).contains("## 任务计划"))
        assertTrue(taskPlanText(plan).contains("等待授权: run_shell"))
    }

    @Test
    fun infersReadyPlanAfterSuccessfulTools() {
        val plan = inferTaskPlan(
            goalText = "整理项目",
            goalPhaseName = "READY",
            goalPhaseLabel = "待继续",
            goalNote = "最近一轮已结束",
            hasMessages = true,
            toolEvents = 3,
            runningTools = 0,
            failedTools = 0,
            changedFiles = 0,
            pendingApprovals = 0,
        )

        assertEquals(PlanItemStatus.DONE, plan.items[0].status)
        assertEquals(PlanItemStatus.DONE, plan.items[1].status)
        assertEquals(PlanItemStatus.DONE, plan.items[2].status)
        assertEquals(PlanItemStatus.DONE, plan.items[4].status)
    }

    @Test
    fun keepsReviewActiveWhenThereAreChangedFiles() {
        val plan = inferTaskPlan(
            goalText = "复刻 Codex 工作台",
            goalPhaseName = "READY",
            goalPhaseLabel = "待继续",
            goalNote = "最近一轮已结束",
            hasMessages = true,
            toolEvents = 4,
            runningTools = 0,
            failedTools = 0,
            changedFiles = 2,
            pendingApprovals = 0,
        )

        assertEquals(PlanItemStatus.ACTIVE, plan.items[3].status)
        assertTrue(plan.items[3].detail.contains("2 个文件"))
    }
}
