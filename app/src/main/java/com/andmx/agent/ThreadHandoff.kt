package com.andmx.agent

data class HandoffRunLogItem(
    val title: String,
    val state: String,
    val detail: String,
)

data class ThreadHandoffContext(
    val project: String,
    val model: String,
    val approvalModeLabel: String,
    val goalText: String,
    val goalPhaseLabel: String,
    val goalNote: String,
    val tokenEstimate: Int = 0,
    val contextPressureLabel: String = "轻量",
    val messageCount: Int,
    val toolCount: Int,
    val mcpServerCount: Int,
    val changedFiles: List<String>,
    val sourceLinks: List<String>,
    val recentActivity: List<HandoffRunLogItem>,
    val planItems: List<TaskPlanItem> = emptyList(),
    val runtimeEnvironment: List<String> = emptyList(),
    val instructionBoundaries: List<String> = emptyList(),
    val verifications: List<String> = emptyList(),
    val resumePrompt: String = defaultResumePrompt(project, goalText),
)

fun threadHandoffText(context: ThreadHandoffContext): String = buildString {
    appendLine("## 线程交接")
    appendLine("- 项目: `${context.project}`")
    appendLine("- 模型: `${context.model}`")
    appendLine("- 授权: **${context.approvalModeLabel}**")
    appendLine("- 消息: ${context.messageCount}")
    appendLine("- 上下文: ~${context.tokenEstimate} tokens · **${context.contextPressureLabel}**")
    appendLine("- 工具: ${context.toolCount} 个可用工具, ${context.mcpServerCount} 个 MCP 服务器")
    appendLine()
    appendLine("### 当前目标")
    appendLine("- 目标: ${context.goalText.ifBlank { "(未设置)" }}")
    appendLine("- 状态: ${context.goalPhaseLabel}")
    if (context.goalNote.isNotBlank()) appendLine("- 备注: ${context.goalNote}")
    appendLine()
    appendLine("### 执行环境")
    if (context.runtimeEnvironment.isEmpty()) {
        appendLine("- 暂无环境快照")
    } else {
        context.runtimeEnvironment.forEach { appendLine("- $it") }
    }
    appendLine()
    appendLine("### 指令边界")
    if (context.instructionBoundaries.isEmpty()) {
        appendLine("- 暂无指令边界快照")
    } else {
        context.instructionBoundaries.forEach { appendLine("- $it") }
    }
    appendLine()
    appendLine("### 当前计划")
    if (context.planItems.isEmpty()) {
        appendLine("- 暂无计划快照")
    } else {
        context.planItems.forEachIndexed { index, item ->
            appendLine("${index + 1}. ${item.status.label} · ${item.title}: ${item.detail.ifBlank { "(无细节)" }}")
        }
    }
    appendLine()
    appendLine("### 验证")
    if (context.verifications.isEmpty()) {
        appendLine("- 暂无测试、构建或诊断记录")
    } else {
        context.verifications.forEach { appendLine("- $it") }
    }
    appendLine()
    appendLine("### 最近活动")
    if (context.recentActivity.isEmpty()) {
        appendLine("- 暂无运行记录")
    } else {
        context.recentActivity.forEach { item ->
            appendLine("- ${item.state} · ${item.title}: ${item.detail}")
        }
    }
    appendLine()
    appendLine("### 变更")
    if (context.changedFiles.isEmpty()) {
        appendLine("- 暂无待审文件")
    } else {
        context.changedFiles.forEach { appendLine("- `$it`") }
    }
    appendLine()
    appendLine("### 来源")
    if (context.sourceLinks.isEmpty()) {
        appendLine("- 暂无来源链接")
    } else {
        context.sourceLinks.forEach { appendLine("- `$it`") }
    }
    appendLine()
    appendLine("### 建议下一步")
    appendLine("1. 先确认目标状态和最近活动是否符合预期")
    appendLine("2. 打开 Diff/Files 审查变更文件")
    appendLine("3. 继续读取缺失上下文或运行验证命令")
    appendLine("4. 汇报验证结果、剩余风险和下一步")
    appendLine()
    appendLine("### 恢复提示")
    appendLine("```")
    appendLine(context.resumePrompt.trim().ifBlank { defaultResumePrompt(context.project, context.goalText) })
    appendLine("```")
}

private fun defaultResumePrompt(project: String, goalText: String): String =
    "继续这个 AndMX 线程。项目: $project。目标: ${goalText.ifBlank { "(未设置)" }}。先阅读上方交接摘要, 再继续推进下一步。"
