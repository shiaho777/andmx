package com.andmx.agent

data class ContextSnapshot(
    val project: String,
    val model: String,
    val tokenEstimate: Int,
    val messageCount: Int,
    val userMessages: Int,
    val assistantMessages: Int,
    val toolEvents: Int,
    val approvalEvents: Int,
    val changedFiles: Int,
    val sourceLinks: Int,
    val recentActivity: Int,
)

fun contextPressureLabel(tokens: Int): String = when {
    tokens >= 100_000 -> "需要压缩"
    tokens >= 50_000 -> "偏高"
    tokens >= 20_000 -> "中等"
    else -> "轻量"
}

fun contextSnapshotText(snapshot: ContextSnapshot): String = buildString {
    appendLine("## 上下文快照")
    appendLine("- 项目: `${snapshot.project}`")
    appendLine("- 模型: `${snapshot.model}`")
    appendLine("- 估算上下文: ~${snapshot.tokenEstimate} tokens")
    appendLine("- 压力: **${contextPressureLabel(snapshot.tokenEstimate)}**")
    appendLine()
    appendLine("### 消息结构")
    appendLine("- 总消息: ${snapshot.messageCount}")
    appendLine("- 用户: ${snapshot.userMessages}")
    appendLine("- 助手: ${snapshot.assistantMessages}")
    appendLine("- 工具事件: ${snapshot.toolEvents}")
    appendLine("- 授权事件: ${snapshot.approvalEvents}")
    appendLine()
    appendLine("### 工作区上下文")
    appendLine("- 待审变更: ${snapshot.changedFiles}")
    appendLine("- 来源链接: ${snapshot.sourceLinks}")
    appendLine("- 最近活动: ${snapshot.recentActivity}")
    appendLine()
    appendLine("### 建议")
    when (contextPressureLabel(snapshot.tokenEstimate)) {
        "需要压缩" -> appendLine("- 建议先运行 `/handoff`, 再开新线程继续")
        "偏高" -> appendLine("- 建议减少重复输出, 保留关键文件和验证结果")
        "中等" -> appendLine("- 可以继续推进, 但较长输出应优先摘要")
        else -> appendLine("- 上下文仍然轻量, 可以继续读取和执行")
    }
    if (snapshot.changedFiles > 0) appendLine("- 先审查 Diff 面板中的待审文件")
    if (snapshot.sourceLinks == 0 && snapshot.toolEvents > 0) appendLine("- 最近工具没有可打开来源, 必要时补充文件或网页引用")
}
