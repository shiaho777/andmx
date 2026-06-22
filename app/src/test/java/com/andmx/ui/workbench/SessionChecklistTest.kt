package com.andmx.ui.workbench

import com.andmx.agent.PlanItemStatus
import com.andmx.agent.TaskPlanItem
import com.andmx.agent.TaskPlanSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionChecklistTest {

    @Test
    fun checklistFlagsMissingGoalVerificationAndContextPressure() {
        val summary = buildSessionChecklist(
            snapshot = baseSnapshot(
                goalText = "",
                pendingApprovals = 1,
                changedFiles = 2,
                contextPressure = "需要压缩",
            ),
            plan = TaskPlanSnapshot(
                goalText = "",
                goalPhaseLabel = "等待",
                goalNote = "",
                items = listOf(TaskPlanItem("执行工具", "等待授权", PlanItemStatus.BLOCKED)),
            ),
            verifications = emptyList(),
            recentActivity = 2,
        )

        assertEquals("还不能交付", summary.title)
        assertTrue(summary.missingCount >= 4)
        assertEquals(ChecklistState.MISSING, summary.items.first { it.title == "目标" }.state)
        assertEquals(ChecklistState.MISSING, summary.items.first { it.title == "工具状态" }.state)
        assertEquals(ChecklistState.WATCH, summary.items.first { it.title == "变更审查" }.state)
        assertEquals(ChecklistState.MISSING, summary.items.first { it.title == "验证证据" }.state)
        assertEquals(ChecklistState.MISSING, summary.items.first { it.title == "上下文" }.state)
        assertEquals(ChecklistState.MISSING, summary.items.first { it.title == "可恢复性" }.state)
    }

    @Test
    fun checklistCanBeReadyWhenEvidenceIsPresent() {
        val summary = buildSessionChecklist(
            snapshot = baseSnapshot(
                goalText = "复刻 Codex 工作台",
                toolEvents = 3,
                sourceLinks = 1,
                contextPressure = "轻量",
            ),
            plan = TaskPlanSnapshot(
                goalText = "复刻 Codex 工作台",
                goalPhaseLabel = "就绪",
                goalNote = "最近一轮已结束",
                items = listOf(
                    TaskPlanItem("接收目标", "已设置", PlanItemStatus.DONE),
                    TaskPlanItem("整理结果", "已收束", PlanItemStatus.DONE),
                ),
            ),
            verifications = listOf(
                VerificationEntry(1, "./gradlew test", VerificationState.PASSED, "BUILD SUCCESSFUL"),
            ),
            recentActivity = 4,
        )

        assertEquals("可以交付或交接", summary.title)
        assertEquals(0, summary.missingCount)
        assertEquals(0, summary.watchCount)
        assertEquals(summary.items.size, summary.readyCount)

        val text = sessionChecklistText(summary)
        assertTrue(text.contains("## 会话清单"))
        assertTrue(text.contains("状态: **可以交付或交接**"))
        assertTrue(text.contains("**就绪** · 验证证据"))
    }

    @Test
    fun checklistShowsWatchStateForActiveButUnblockedWork() {
        val summary = buildSessionChecklist(
            snapshot = baseSnapshot(
                goalText = "继续优化",
                busy = true,
                runningTools = 1,
                toolEvents = 1,
                changedFiles = 1,
                contextPressure = "偏高",
            ),
            plan = TaskPlanSnapshot(
                goalText = "继续优化",
                goalPhaseLabel = "运行中",
                goalNote = "正在执行",
                items = listOf(TaskPlanItem("执行工具", "运行中", PlanItemStatus.ACTIVE)),
            ),
            verifications = listOf(
                VerificationEntry(1, "./gradlew test", VerificationState.RUNNING, ""),
            ),
            recentActivity = 3,
        )

        assertEquals("接近就绪", summary.title)
        assertEquals(0, summary.missingCount)
        assertTrue(summary.watchCount > 0)
        assertEquals(ChecklistState.WATCH, summary.items.first { it.title == "工具状态" }.state)
        assertEquals(ChecklistState.WATCH, summary.items.first { it.title == "验证证据" }.state)
    }

    @Test
    fun checklistTracksUiReferencesBeforeImplementationStarts() {
        val summary = buildSessionChecklist(
            snapshot = baseSnapshot(
                goalText = "按截图复刻 Codex UI",
                uiReferences = 2,
                toolEvents = 0,
                changedFiles = 0,
            ),
            plan = TaskPlanSnapshot(
                goalText = "按截图复刻 Codex UI",
                goalPhaseLabel = "运行中",
                goalNote = "",
                items = listOf(TaskPlanItem("读取上下文", "准备提取截图", PlanItemStatus.ACTIVE)),
            ),
            verifications = listOf(VerificationEntry(1, "./gradlew test", VerificationState.PASSED, "BUILD SUCCESSFUL")),
            recentActivity = 1,
        )

        val ui = summary.items.first { it.title == "UI参考" }
        assertEquals(ChecklistState.WATCH, ui.state)
        assertEquals("/references", ui.command)
        assertTrue(ui.detail.contains("2 个截图/附件参考"))
    }

    private fun baseSnapshot(
        goalText: String = "目标",
        busy: Boolean = false,
        toolEvents: Int = 0,
        runningTools: Int = 0,
        failedTools: Int = 0,
        pendingApprovals: Int = 0,
        changedFiles: Int = 0,
        sourceLinks: Int = 0,
        uiReferences: Int = 0,
        contextPressure: String = "轻量",
    ) = AgentInspectorSnapshot(
        project = "andmx",
        model = "gpt-test",
        baseUrl = "https://api.example.test",
        apiConfigured = true,
        approvalModeLabel = "按需",
        goalText = goalText,
        goalPhaseLabel = "运行中",
        goalNote = "",
        busy = busy,
        reasoningEffort = "medium",
        persona = "默认",
        messageCount = 6,
        userMessages = 2,
        assistantMessages = 2,
        toolEvents = toolEvents,
        runningTools = runningTools,
        failedTools = failedTools,
        approvalEvents = pendingApprovals,
        pendingApprovals = pendingApprovals,
        changedFiles = changedFiles,
        sourceLinks = sourceLinks,
        uiReferences = uiReferences,
        tokenEstimate = if (contextPressure == "需要压缩") 120_000 else 1_200,
        contextPressure = contextPressure,
        builtInTools = 9,
        totalTools = 9,
        mcpServers = 0,
    )
}
