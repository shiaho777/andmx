package com.andmx.agent

enum class PlanItemStatus(val label: String) {
    DONE("完成"),
    ACTIVE("进行中"),
    PENDING("待处理"),
    BLOCKED("受阻"),
}

data class TaskPlanItem(
    val title: String,
    val detail: String,
    val status: PlanItemStatus,
)

data class TaskPlanSnapshot(
    val goalText: String,
    val goalPhaseLabel: String,
    val goalNote: String,
    val items: List<TaskPlanItem>,
)

fun taskPlanText(snapshot: TaskPlanSnapshot): String = buildString {
    appendLine("## 任务计划")
    appendLine("- 目标: ${snapshot.goalText.ifBlank { "(未设置)" }}")
    appendLine("- 状态: ${snapshot.goalPhaseLabel}")
    if (snapshot.goalNote.isNotBlank()) appendLine("- 备注: ${snapshot.goalNote}")
    appendLine()
    snapshot.items.forEachIndexed { index, item ->
        appendLine("${index + 1}. **${item.status.label}** · ${item.title}")
        appendLine("   ${item.detail.ifBlank { "(无细节)" }}")
    }
}

fun inferTaskPlan(
    goalText: String,
    goalPhaseName: String,
    goalPhaseLabel: String,
    goalNote: String,
    hasMessages: Boolean,
    toolEvents: Int,
    runningTools: Int,
    failedTools: Int,
    changedFiles: Int,
    pendingApprovals: Int,
): TaskPlanSnapshot {
    val blocked = goalPhaseName in setOf("PAUSED", "NEEDS_SETUP", "FAILED") || pendingApprovals > 0
    val hasGoal = goalText.isNotBlank()
    val readDone = toolEvents > 0 || changedFiles > 0
    val executeDone = toolEvents > 0 && runningTools == 0 && failedTools == 0 && pendingApprovals == 0
    val executeActive = runningTools > 0 || goalPhaseName == "RUNNING"
    val reviewActive = changedFiles > 0
    val finishDone = hasMessages && goalPhaseName == "READY"

    val items = listOf(
        TaskPlanItem(
            title = "接收目标",
            detail = goalText.ifBlank { "等待用户给出目标" },
            status = when {
                hasGoal || hasMessages -> PlanItemStatus.DONE
                else -> PlanItemStatus.ACTIVE
            },
        ),
        TaskPlanItem(
            title = "读取上下文",
            detail = when {
                readDone -> "已有 $toolEvents 个工具事件和 $changedFiles 个变更文件"
                hasGoal -> "准备读取项目结构、文件或外部材料"
                else -> "等待目标后读取上下文"
            },
            status = when {
                readDone -> PlanItemStatus.DONE
                hasGoal -> PlanItemStatus.ACTIVE
                else -> PlanItemStatus.PENDING
            },
        ),
        TaskPlanItem(
            title = "执行工具",
            detail = when {
                pendingApprovals > 0 -> "$pendingApprovals 个授权等待处理"
                runningTools > 0 -> "$runningTools 个工具正在运行"
                failedTools > 0 -> "$failedTools 个工具失败, 等待处理"
                toolEvents > 0 -> "$toolEvents 个工具事件已记录"
                else -> "尚未调用工具"
            },
            status = when {
                pendingApprovals > 0 || failedTools > 0 -> PlanItemStatus.BLOCKED
                executeActive -> PlanItemStatus.ACTIVE
                executeDone -> PlanItemStatus.DONE
                else -> PlanItemStatus.PENDING
            },
        ),
        TaskPlanItem(
            title = "审查变更",
            detail = if (changedFiles > 0) "$changedFiles 个文件需要在 Diff/Files 中审查" else "暂无待审文件",
            status = when {
                reviewActive -> PlanItemStatus.ACTIVE
                executeDone -> PlanItemStatus.DONE
                else -> PlanItemStatus.PENDING
            },
        ),
        TaskPlanItem(
            title = "整理结果",
            detail = when {
                blocked -> goalNote.ifBlank { "等待解除阻塞后继续" }
                finishDone -> "最近一轮已结束, 可以继续追问或交接"
                hasMessages -> "等待 agent 收束和汇报"
                else -> "尚未开始"
            },
            status = when {
                blocked -> PlanItemStatus.BLOCKED
                finishDone -> PlanItemStatus.DONE
                hasMessages -> PlanItemStatus.ACTIVE
                else -> PlanItemStatus.PENDING
            },
        ),
    )

    return TaskPlanSnapshot(
        goalText = goalText,
        goalPhaseLabel = goalPhaseLabel,
        goalNote = goalNote,
        items = items,
    )
}
