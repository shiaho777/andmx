package com.andmx.ui.workbench

internal data class InstructionStackSummary(
    val apiStatus: String,
    val mcpStatus: String,
    val customInstructionStatus: String,
    val customInstructionPreview: String,
    val visibleLayers: List<String>,
    val safetyBoundary: String,
    val contractSummary: String = "运行 `/instructions` 查看 Codex 环境契约。",
)

internal fun buildInstructionStackSummary(
    apiConfigured: Boolean,
    mcpConfigured: Boolean,
    customInstructions: String,
    builtInTools: Int,
    mcpServers: Int,
): InstructionStackSummary {
    val custom = customInstructions.trim()
    return InstructionStackSummary(
        apiStatus = if (apiConfigured) "API Key 已配置" else "API Key 未配置",
        mcpStatus = when {
            mcpServers > 0 -> "$mcpServers 个 MCP 已连接"
            mcpConfigured -> "MCP 已填写,等待连接"
            else -> "MCP 未填写"
        },
        customInstructionStatus = if (custom.isBlank()) "未设置自定义指令" else "已设置自定义指令",
        customInstructionPreview = custom.ifBlank { "不会额外附加用户自定义指令。" }.take(160),
        visibleLayers = listOf(
            "应用边界: Android/proot Alpine guest rootfs",
            "用户设置: 模型、端点、语气、推理档、授权、MCP",
            "工具注册: $builtInTools 个内置工具, $mcpServers 个 MCP 服务器",
            "工作台: Files/Terminal/Diff/Browser/Inspector 共享同一任务上下文",
        ),
        safetyBoundary = "内部系统提示词和运行时安全策略不会作为普通文本暴露; 高风险工具受授权模式控制。",
        contractSummary = "指令优先级、运行沙箱、工具授权、上下文恢复和证据边界会进入 `/instructions` 的环境契约。",
    )
}
