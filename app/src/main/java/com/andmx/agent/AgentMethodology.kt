package com.andmx.agent

data class AgentMethodologyContext(
    val project: String,
    val model: String,
    val approvalModeLabel: String,
    val builtInToolCount: Int,
    val mcpServerCount: Int,
    val goalText: String = "",
    val goalPhaseLabel: String = "",
    val contextPressure: String = "",
    val tokenEstimate: Int = 0,
    val messageCount: Int = 0,
    val toolEvents: Int = 0,
    val runningTools: Int = 0,
    val failedTools: Int = 0,
    val pendingApprovals: Int = 0,
    val changedFiles: Int = 0,
    val uiReferences: Int = 0,
    val evidenceCount: Int = 0,
    val verificationPassed: Int = 0,
    val verificationFailed: Int = 0,
    val checklistMissing: Int = 0,
    val checklistWatch: Int = 0,
    val nextActionTitle: String = "",
    val nextActionCommand: String = "",
    val runtimeSurface: String = "",
    val runtimeHealth: String = "",
    val instructionLayers: List<String> = emptyList(),
)

fun agentMethodologyText(context: AgentMethodologyContext): String = buildString {
    appendLine("## 工作方法")
    appendLine("- 项目: `${context.project}`")
    appendLine("- 模型: `${context.model}`")
    appendLine("- 授权: **${context.approvalModeLabel}**")
    appendLine("- 工具: ${context.builtInToolCount} 个内置工具, ${context.mcpServerCount} 个 MCP 服务器")
    if (context.goalText.isNotBlank()) appendLine("- 目标: ${context.goalText}")
    if (context.goalPhaseLabel.isNotBlank()) appendLine("- 目标状态: ${context.goalPhaseLabel}")
    if (context.nextActionTitle.isNotBlank()) {
        appendLine("- 下一步: ${context.nextActionTitle}${context.nextActionCommand.takeIf { it.isNotBlank() }?.let { " (`$it`)" }.orEmpty()}")
    }
    appendLine()
    appendLine("### 当前线程")
    appendLine("- 上下文: ${context.contextPressure.ifBlank { "未知" }} · ~${context.tokenEstimate} tokens · ${context.messageCount} 条消息")
    appendLine("- 工具: 事件 ${context.toolEvents} · 运行 ${context.runningTools} · 失败 ${context.failedTools} · 授权等待 ${context.pendingApprovals}")
    appendLine("- 产物: UI 参考 ${context.uiReferences} · 变更 ${context.changedFiles} · 证据 ${context.evidenceCount}")
    appendLine("- 验证: 通过 ${context.verificationPassed} · 失败 ${context.verificationFailed}")
    appendLine("- 清单: 缺口 ${context.checklistMissing} · 注意 ${context.checklistWatch}")
    appendLine()
    appendLine("### 执行循环")
    appendLine("1. 观察: 读项目结构、相关文件、截图、工具输出和当前 UI 状态")
    appendLine("2. 定向: 把目标拆成计划、下一步、风险和可验证验收项")
    appendLine("3. 执行: 按现有代码风格做小范围修改, 或调用终端、网络、MCP、Live UI 工具推进")
    appendLine("4. 审计: 把来源、截图、变更、审批、验证和失败写入可查看面板")
    appendLine("5. 收束: 汇报改动、证据、剩余风险, 必要时生成 handoff/resume")
    appendLine()
    appendLine("### 环境边界")
    appendLine("- 文件、终端、diff、浏览器都围绕 Android/proot Alpine guest rootfs 工作")
    appendLine("- `@/root/file` 和 `@relative/path` 会被解析为工作区文件引用")
    appendLine("- 右侧 Files/Terminal/Diff/Browser 是同一任务上下文的工作面板")
    appendLine("- 底部终端适合保留长时间运行的 shell 状态")
    appendLine("- 运行环境: ${context.runtimeSurface.ifBlank { "Android/proot guest rootfs" }} · ${context.runtimeHealth.ifBlank { "等待诊断" }}")
    if (context.instructionLayers.isNotEmpty()) {
        appendLine("- 可见指令层: ${context.instructionLayers.joinToString(" / ")}")
    }
    appendLine()
    appendLine("### 工具策略")
    appendLine("- `/tools` 将观察、编辑、执行、浏览与截图、MCP 和交付映射成能力地图")
    appendLine("- 读取类工具优先自动执行,用于建立事实")
    appendLine("- 写入、补丁和编辑类工具会进入变更审查路径")
    appendLine("- 执行和网络类工具根据授权模式自动、询问或阻止")
    appendLine("- MCP 工具作为外部能力接入,需要在设置里显式配置")
    appendLine("- 截图参考进入 `/references`、`/screenshot-extract`、`/trace`、`/visual-check` 和 `/report`")
    appendLine()
    appendLine("### 交互习惯")
    appendLine("- `Ctrl/Meta+K` 或 `Ctrl/Meta+Shift+P` 打开命令面板")
    appendLine("- `/status` 看会话状态, `/tools` 看能力边界, `/diag` 检查运行环境")
    appendLine("- `/self-model` 看指令、工具、环境和工作循环, `/flow` 看目标到交付链路")
    appendLine("- `/improve` 看自我完善队列、证据缺口和下一入口")
    appendLine("- `/full`、`/ask`、`/readonly` 控制 agent 自治程度")
    appendLine("- 任务面板用于追踪目标、工具调用、来源和变更")
}
