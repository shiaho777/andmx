package com.andmx.ui.workbench

internal enum class NextActionPriority(val label: String) {
    BLOCKED("阻塞"),
    ACTIVE("进行中"),
    REVIEW("审查"),
    VERIFY("验证"),
    HANDOFF("交接"),
    CONTINUE("继续"),
}

internal data class NextActionDecision(
    val priority: NextActionPriority,
    val title: String,
    val reason: String,
    val evidence: List<String>,
    val command: String,
)

internal fun buildNextActionDecision(
    snapshot: AgentInspectorSnapshot,
    checklist: SessionChecklistSummary,
    verifications: List<VerificationEntry>,
): NextActionDecision {
    val failedVerifications = verifications.count { it.state == VerificationState.FAILED }
    val runningVerifications = verifications.count { it.state == VerificationState.RUNNING }
    val passedVerifications = verifications.count { it.state == VerificationState.PASSED }
    val firstMissing = checklist.items.firstOrNull { it.state == ChecklistState.MISSING }
    val firstWatch = checklist.items.firstOrNull { it.state == ChecklistState.WATCH }

    return when {
        !snapshot.apiConfigured -> NextActionDecision(
            priority = NextActionPriority.BLOCKED,
            title = "先配置模型",
            reason = "没有可用模型时, agent 无法可靠执行下一轮推理。",
            evidence = listOf("API 未配置", "模型: ${snapshot.model}", "端点: ${snapshot.baseUrl.ifBlank { "(未设置)" }}"),
            command = "/model",
        )
        snapshot.pendingApprovals > 0 -> NextActionDecision(
            priority = NextActionPriority.BLOCKED,
            title = "处理授权请求",
            reason = "工具调用正在等待用户确认, 当前执行链不能继续收束。",
            evidence = listOf("${snapshot.pendingApprovals} 个授权待处理", "授权模式: ${snapshot.approvalModeLabel}"),
            command = "/activity",
        )
        snapshot.busy || snapshot.runningTools > 0 || runningVerifications > 0 -> NextActionDecision(
            priority = NextActionPriority.ACTIVE,
            title = "等待当前运行完成",
            reason = "还有模型、工具或验证任务在运行, 先等待结果再判断下一步。",
            evidence = listOf(
                "busy=${snapshot.busy}",
                "运行中工具: ${snapshot.runningTools}",
                "运行中验证: $runningVerifications",
            ),
            command = "/activity",
        )
        snapshot.failedTools > 0 -> NextActionDecision(
            priority = NextActionPriority.BLOCKED,
            title = "检查失败工具",
            reason = "失败工具会污染后续判断, 需要先查看输出并决定重试或换路径。",
            evidence = listOf("${snapshot.failedTools} 个工具失败", "工具事件: ${snapshot.toolEvents}"),
            command = "/activity",
        )
        failedVerifications > 0 -> NextActionDecision(
            priority = NextActionPriority.VERIFY,
            title = "处理失败验证",
            reason = "验证失败说明当前实现或环境证据还不能支持交付。",
            evidence = listOf("$failedVerifications 条验证失败", "验证记录: ${verifications.size}"),
            command = "/verify",
        )
        snapshot.uiReferences > 0 && snapshot.toolEvents == 0 && snapshot.changedFiles == 0 -> NextActionDecision(
            priority = NextActionPriority.REVIEW,
            title = "提取界面模式",
            reason = "线程里已有截图或附件参考, 下一步应先抽取布局、控件和交互状态, 再映射成实现任务。",
            evidence = listOf("${snapshot.uiReferences} 个 UI 参考", "工具事件: 0", "待审变更: 0"),
            command = "/references",
        )
        snapshot.changedFiles > 0 -> NextActionDecision(
            priority = NextActionPriority.REVIEW,
            title = "审查待变更",
            reason = "已经有文件变化, 下一步应先看 diff 和文件目标是否符合任务。",
            evidence = listOf("${snapshot.changedFiles} 个待审文件", "来源链接: ${snapshot.sourceLinks}"),
            command = "/changes",
        )
        snapshot.contextPressure == "需要压缩" -> NextActionDecision(
            priority = NextActionPriority.HANDOFF,
            title = "生成交接摘要",
            reason = "上下文压力过高, 继续堆叠会降低恢复和判断质量。",
            evidence = listOf("上下文: ${snapshot.contextPressure}", "~${snapshot.tokenEstimate} tokens"),
            command = "/handoff",
        )
        firstMissing != null -> NextActionDecision(
            priority = NextActionPriority.BLOCKED,
            title = "补齐${firstMissing.title}",
            reason = "会话清单仍有硬缺口, 先补齐再进入最终汇报。",
            evidence = listOf(firstMissing.detail, "缺口: ${checklist.missingCount}", "注意: ${checklist.watchCount}"),
            command = firstMissing.command.ifBlank { "/checklist" },
        )
        passedVerifications == 0 && snapshot.toolEvents > 0 -> NextActionDecision(
            priority = NextActionPriority.VERIFY,
            title = "补充验证证据",
            reason = "已经执行过工具, 但还没有测试、构建或诊断结果支撑交付。",
            evidence = listOf("工具事件: ${snapshot.toolEvents}", "验证通过: 0"),
            command = "/verify",
        )
        snapshot.contextPressure == "偏高" || snapshot.messageCount >= 24 -> NextActionDecision(
            priority = NextActionPriority.HANDOFF,
            title = "准备交接摘要",
            reason = "线程开始变长, 提前保留目标、计划、变更和验证结果会更稳。",
            evidence = listOf("上下文: ${snapshot.contextPressure}", "消息: ${snapshot.messageCount}"),
            command = "/handoff",
        )
        firstWatch != null -> NextActionDecision(
            priority = NextActionPriority.REVIEW,
            title = "确认${firstWatch.title}",
            reason = "没有硬性阻塞, 但仍有注意项需要在继续或交付前确认。",
            evidence = listOf(firstWatch.detail, "注意: ${checklist.watchCount}"),
            command = firstWatch.command.ifBlank { "/checklist" },
        )
        snapshot.goalText.isBlank() -> NextActionDecision(
            priority = NextActionPriority.CONTINUE,
            title = "等待用户目标",
            reason = "还没有目标锚点, 无法建立可靠计划。",
            evidence = listOf("消息: ${snapshot.messageCount}", "目标为空"),
            command = "/plan",
        )
        else -> NextActionDecision(
            priority = NextActionPriority.CONTINUE,
            title = "继续推进或交付",
            reason = "目标、计划、验证和恢复状态没有硬缺口。",
            evidence = listOf("清单: ${checklist.title}", "验证通过: $passedVerifications", "上下文: ${snapshot.contextPressure}"),
            command = "/checklist",
        )
    }
}

internal fun nextActionText(decision: NextActionDecision): String = buildString {
    appendLine("## 下一步")
    appendLine("- 优先级: **${decision.priority.label}**")
    appendLine("- 动作: ${decision.title}")
    appendLine("- 原因: ${decision.reason}")
    appendLine("- 入口: `${decision.command}`")
    appendLine()
    appendLine("### 证据")
    decision.evidence.forEach { appendLine("- $it") }
}
