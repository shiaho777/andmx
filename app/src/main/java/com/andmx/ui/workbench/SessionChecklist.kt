package com.andmx.ui.workbench

import com.andmx.agent.PlanItemStatus
import com.andmx.agent.TaskPlanSnapshot

internal enum class ChecklistState(val label: String) {
    READY("就绪"),
    WATCH("注意"),
    MISSING("缺口"),
}

internal data class SessionChecklistItem(
    val title: String,
    val detail: String,
    val state: ChecklistState,
    val command: String = "",
)

internal data class SessionChecklistSummary(
    val title: String,
    val detail: String,
    val items: List<SessionChecklistItem>,
) {
    val missingCount: Int get() = items.count { it.state == ChecklistState.MISSING }
    val watchCount: Int get() = items.count { it.state == ChecklistState.WATCH }
    val readyCount: Int get() = items.count { it.state == ChecklistState.READY }
}

internal fun buildSessionChecklist(
    snapshot: AgentInspectorSnapshot,
    plan: TaskPlanSnapshot,
    verifications: List<VerificationEntry>,
    recentActivity: Int,
): SessionChecklistSummary {
    val planActive = plan.items.count { it.status == PlanItemStatus.ACTIVE }
    val planBlocked = plan.items.count { it.status == PlanItemStatus.BLOCKED }
    val verificationFailed = verifications.count { it.state == VerificationState.FAILED }
    val verificationRunning = verifications.count { it.state == VerificationState.RUNNING }
    val verificationPassed = verifications.count { it.state == VerificationState.PASSED }

    val items = listOf(
        SessionChecklistItem(
            title = "目标",
            detail = snapshot.goalText.ifBlank { "还没有可跟踪的用户目标" },
            state = if (snapshot.goalText.isBlank()) ChecklistState.MISSING else ChecklistState.READY,
            command = "/plan",
        ),
        SessionChecklistItem(
            title = "计划",
            detail = when {
                planBlocked > 0 -> "$planBlocked 个计划项受阻"
                planActive > 0 -> "$planActive 个计划项正在推进"
                plan.items.isEmpty() -> "没有可显示的计划项"
                else -> "${plan.items.size} 个计划项已建立"
            },
            state = when {
                plan.items.isEmpty() -> ChecklistState.MISSING
                planBlocked > 0 -> ChecklistState.MISSING
                planActive > 0 -> ChecklistState.WATCH
                else -> ChecklistState.READY
            },
            command = "/plan",
        ),
        SessionChecklistItem(
            title = "工具状态",
            detail = when {
                snapshot.pendingApprovals > 0 -> "${snapshot.pendingApprovals} 个授权等待处理"
                snapshot.runningTools > 0 -> "${snapshot.runningTools} 个工具仍在运行"
                snapshot.failedTools > 0 -> "${snapshot.failedTools} 个工具失败"
                snapshot.toolEvents > 0 -> "${snapshot.toolEvents} 个工具事件已收束"
                else -> "尚未调用工具"
            },
            state = when {
                snapshot.pendingApprovals > 0 || snapshot.failedTools > 0 -> ChecklistState.MISSING
                snapshot.busy || snapshot.runningTools > 0 -> ChecklistState.WATCH
                snapshot.toolEvents > 0 -> ChecklistState.READY
                else -> ChecklistState.WATCH
            },
            command = "/activity",
        ),
        SessionChecklistItem(
            title = "UI参考",
            detail = when {
                snapshot.uiReferences > 0 && snapshot.toolEvents == 0 && snapshot.changedFiles == 0 ->
                    "${snapshot.uiReferences} 个截图/附件参考等待提炼"
                snapshot.uiReferences > 0 ->
                    "${snapshot.uiReferences} 个截图/附件参考已进入证据链"
                else -> "暂无截图或附件参考"
            },
            state = when {
                snapshot.uiReferences > 0 && snapshot.toolEvents == 0 && snapshot.changedFiles == 0 -> ChecklistState.WATCH
                snapshot.uiReferences > 0 -> ChecklistState.READY
                else -> ChecklistState.READY
            },
            command = "/references",
        ),
        SessionChecklistItem(
            title = "变更审查",
            detail = if (snapshot.changedFiles > 0) "${snapshot.changedFiles} 个文件等待审查" else "暂无待审变更",
            state = if (snapshot.changedFiles > 0) ChecklistState.WATCH else ChecklistState.READY,
            command = "/changes",
        ),
        SessionChecklistItem(
            title = "验证证据",
            detail = when {
                verificationFailed > 0 -> "$verificationFailed 条验证失败"
                verificationRunning > 0 -> "$verificationRunning 条验证仍在运行"
                verificationPassed > 0 -> "$verificationPassed 条验证通过"
                else -> "暂无测试、构建或诊断记录"
            },
            state = when {
                verificationFailed > 0 -> ChecklistState.MISSING
                verificationRunning > 0 -> ChecklistState.WATCH
                verificationPassed > 0 -> ChecklistState.READY
                else -> ChecklistState.MISSING
            },
            command = "/verify",
        ),
        SessionChecklistItem(
            title = "上下文",
            detail = "~${snapshot.tokenEstimate} tokens · ${snapshot.contextPressure}",
            state = when (snapshot.contextPressure) {
                "需要压缩" -> ChecklistState.MISSING
                "偏高" -> ChecklistState.WATCH
                else -> ChecklistState.READY
            },
            command = "/context",
        ),
        SessionChecklistItem(
            title = "可恢复性",
            detail = when {
                snapshot.contextPressure == "需要压缩" -> "需要先生成交接摘要"
                snapshot.messageCount >= 24 || snapshot.changedFiles > 0 -> "建议生成交接摘要"
                recentActivity > 0 -> "$recentActivity 条最近活动可写入交接"
                else -> "线程仍很轻量"
            },
            state = when {
                snapshot.contextPressure == "需要压缩" -> ChecklistState.MISSING
                snapshot.messageCount >= 24 || snapshot.changedFiles > 0 -> ChecklistState.WATCH
                else -> ChecklistState.READY
            },
            command = "/handoff",
        ),
    )

    val title = when {
        items.any { it.state == ChecklistState.MISSING } -> "还不能交付"
        items.any { it.state == ChecklistState.WATCH } -> "接近就绪"
        else -> "可以交付或交接"
    }
    val detail = when {
        items.any { it.state == ChecklistState.MISSING } -> "先处理缺口项, 再汇报最终结果。"
        items.any { it.state == ChecklistState.WATCH } -> "有注意项, 但没有硬性阻塞。"
        else -> "目标、计划、验证和恢复信息都已具备。"
    }
    return SessionChecklistSummary(title = title, detail = detail, items = items)
}

internal fun sessionChecklistText(summary: SessionChecklistSummary): String = buildString {
    appendLine("## 会话清单")
    appendLine("- 状态: **${summary.title}**")
    appendLine("- 缺口: ${summary.missingCount}")
    appendLine("- 注意: ${summary.watchCount}")
    appendLine("- 就绪: ${summary.readyCount}")
    appendLine()
    summary.items.forEach { item ->
        appendLine("- **${item.state.label}** · ${item.title}: ${item.detail}")
        if (item.command.isNotBlank()) appendLine("  - 入口: `${item.command}`")
    }
    appendLine()
    appendLine("### 下一步")
    val next = summary.items.firstOrNull { it.state == ChecklistState.MISSING }
        ?: summary.items.firstOrNull { it.state == ChecklistState.WATCH }
    if (next == null) {
        appendLine("- 可以总结改动、验证证据和剩余风险, 或运行 `/handoff` 交接。")
    } else {
        appendLine("- 先处理 `${next.title}`: ${next.detail}")
        if (next.command.isNotBlank()) appendLine("- 可运行 `${next.command}` 查看详情")
    }
}
