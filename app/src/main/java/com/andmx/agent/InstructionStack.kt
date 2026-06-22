package com.andmx.agent

data class InstructionStackSnapshot(
    val model: String,
    val baseUrl: String,
    val apiConfigured: Boolean,
    val persona: String,
    val reasoningEffort: String,
    val approvalModeLabel: String,
    val customInstructions: String,
    val builtInToolCount: Int,
    val mcpServerCount: Int,
    val mcpConfigured: Boolean,
    val environmentContractText: String = "",
)

fun instructionStackText(snapshot: InstructionStackSnapshot): String = buildString {
    appendLine("## 指令栈")
    appendLine("- 模型: `${snapshot.model}`")
    appendLine("- 端点: `${snapshot.baseUrl}`")
    appendLine("- API Key: ${if (snapshot.apiConfigured) "已配置" else "未配置"}")
    appendLine("- 语气: ${snapshot.persona.ifBlank { "默认" }}")
    appendLine("- 推理档: ${snapshot.reasoningEffort}")
    appendLine("- 授权: **${snapshot.approvalModeLabel}**")
    appendLine()
    appendLine("### 可见指令层")
    appendLine("- 应用边界: Android/proot Alpine guest rootfs, 文件/终端/diff/browser 共享同一工作上下文")
    appendLine("- 用户设置: 模型、端点、语气、推理档、授权模式、自定义指令、MCP")
    appendLine("- 工具注册: ${snapshot.builtInToolCount} 个内置工具, ${snapshot.mcpServerCount} 个已连接 MCP 服务器")
    appendLine("- MCP 配置: ${if (snapshot.mcpConfigured) "已填写" else "未填写"}")
    appendLine()
    appendLine("### 自定义指令")
    if (snapshot.customInstructions.isBlank()) {
        appendLine("- 未设置")
    } else {
        appendLine("```")
        appendLine(snapshot.customInstructions.trim().take(1200))
        appendLine("```")
    }
    appendLine()
    appendLine("### 安全边界")
    appendLine("- 内部系统提示词和运行时安全策略不会作为普通文本暴露")
    appendLine("- `/method` 展示工作方法, `/tools` 展示工具能力, `/context` 展示上下文预算")
    appendLine("- 写入、执行、网络等高风险操作受授权模式和审批卡片控制")
    if (snapshot.environmentContractText.isNotBlank()) {
        appendLine()
        append(snapshot.environmentContractText.trim())
    }
}
