package com.andmx.ui.workbench

internal enum class HandoffAdviceLevel {
    OK,
    WATCH,
    RECOMMENDED,
    REQUIRED,
}

internal data class ContextHandoffAdvice(
    val level: HandoffAdviceLevel,
    val title: String,
    val detail: String,
    val primaryCommand: String,
)

internal fun contextHandoffAdvice(snapshot: AgentInspectorSnapshot): ContextHandoffAdvice = when {
    snapshot.contextPressure == "需要压缩" -> ContextHandoffAdvice(
        level = HandoffAdviceLevel.REQUIRED,
        title = "上下文需要压缩",
        detail = "运行 /handoff 生成可恢复摘要, 再开新线程继续。",
        primaryCommand = "/handoff",
    )
    snapshot.contextPressure == "偏高" -> ContextHandoffAdvice(
        level = HandoffAdviceLevel.RECOMMENDED,
        title = "建议准备交接",
        detail = "上下文已偏高, 先保留关键文件、验证结果和下一步。",
        primaryCommand = "/handoff",
    )
    snapshot.messageCount >= 30 || snapshot.toolEvents >= 18 -> ContextHandoffAdvice(
        level = HandoffAdviceLevel.WATCH,
        title = "线程开始变长",
        detail = "可以运行 /context 查看预算, 需要切换任务前再生成交接。",
        primaryCommand = "/context",
    )
    snapshot.changedFiles > 0 -> ContextHandoffAdvice(
        level = HandoffAdviceLevel.WATCH,
        title = "先审查待变更",
        detail = "交接前确认 Diff/Files 中的变更是否符合预期。",
        primaryCommand = "/context",
    )
    else -> ContextHandoffAdvice(
        level = HandoffAdviceLevel.OK,
        title = "上下文健康",
        detail = "可以继续读取、执行和迭代; 长输出会按需摘要。",
        primaryCommand = "/context",
    )
}
