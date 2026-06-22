package com.andmx.ui.workbench

import com.andmx.agent.ApprovalMode
import com.andmx.agent.ApprovalPolicy
import com.andmx.agent.Decision
import com.andmx.agent.ToolCapability
import com.andmx.agent.ToolRisk
import com.andmx.agent.description
import com.andmx.agent.label
import com.andmx.agent.riskOrder

internal data class ToolPolicyRiskRow(
    val risk: ToolRisk,
    val toolCount: Int,
    val decision: Decision,
    val toolNames: List<String>,
)

internal enum class SafetyBoundaryEffect { AUTO, PROMPT, DENY, HANDOFF }

internal data class SafetyBoundaryRow(
    val title: String,
    val effect: SafetyBoundaryEffect,
    val detail: String,
    val examples: List<String>,
)

internal data class ToolPolicySummary(
    val mode: ApprovalMode,
    val rows: List<ToolPolicyRiskRow>,
    val boundaryRows: List<SafetyBoundaryRow> = defaultSafetyBoundaryRows(),
) {
    val autoCount: Int get() = rows.filter { it.decision == Decision.AUTO }.sumOf { it.toolCount }
    val promptCount: Int get() = rows.filter { it.decision == Decision.PROMPT }.sumOf { it.toolCount }
    val denyCount: Int get() = rows.filter { it.decision == Decision.DENY }.sumOf { it.toolCount }
    val boundaryAutoCount: Int get() = boundaryRows.count { it.effect == SafetyBoundaryEffect.AUTO }
    val boundaryPromptCount: Int get() = boundaryRows.count { it.effect == SafetyBoundaryEffect.PROMPT }
    val boundaryDenyCount: Int get() = boundaryRows.count { it.effect == SafetyBoundaryEffect.DENY }
    val boundaryHandoffCount: Int get() = boundaryRows.count { it.effect == SafetyBoundaryEffect.HANDOFF }
}

internal fun buildToolPolicySummary(
    mode: ApprovalMode,
    tools: List<ToolCapability>,
): ToolPolicySummary {
    val byRisk = tools.groupBy { it.risk }
    val rows = ToolRisk.entries.sortedBy { riskOrder(it) }.map { risk ->
        val group = byRisk[risk].orEmpty().sortedBy { it.name }
        ToolPolicyRiskRow(
            risk = risk,
            toolCount = group.size,
            decision = ApprovalPolicy.decide(mode, risk),
            toolNames = group.map { it.name },
        )
    }
    return ToolPolicySummary(mode = mode, rows = rows)
}

internal fun defaultSafetyBoundaryRows(): List<SafetyBoundaryRow> = listOf(
    SafetyBoundaryRow(
        title = "普通准备与观察",
        effect = SafetyBoundaryEffect.AUTO,
        detail = "读取屏幕、普通导航、查看文件和下载入站文件可以直接执行, 前提是不提交或改写第三方状态。",
        examples = listOf("读取窗口", "普通导航", "下载文件"),
    ),
    SafetyBoundaryRow(
        title = "会改变外部状态",
        effect = SafetyBoundaryEffect.PROMPT,
        detail = "删除、上传、账号权限、安装软件、发送消息、金融/医疗动作和敏感数据传输必须在动作发生前确认。",
        examples = listOf("删除/上传", "权限/账号", "第三方通信", "敏感数据"),
    ),
    SafetyBoundaryRow(
        title = "宿主与受保护窗口",
        effect = SafetyBoundaryEffect.DENY,
        detail = "当平台禁止控制某个应用、窗口或安全边界时, agent 应报告限制并改用可审计的替代路径。",
        examples = listOf("受保护应用", "宿主窗口", "系统安全边界"),
    ),
    SafetyBoundaryRow(
        title = "必须用户接管",
        effect = SafetyBoundaryEffect.HANDOFF,
        detail = "改密码最终提交、绕过浏览器安全拦截和 CAPTCHA 等动作不由 agent 代操作, 需要用户亲自完成。",
        examples = listOf("提交改密码", "绕过安全拦截", "CAPTCHA"),
    ),
)

internal fun toolPolicyText(summary: ToolPolicySummary): String = buildString {
    appendLine("## 授权策略")
    appendLine("- 当前模式: **${summary.mode.label}**")
    appendLine("- 自动: ${summary.autoCount}")
    appendLine("- 询问: ${summary.promptCount}")
    appendLine("- 阻止: ${summary.denyCount}")
    appendLine()
    appendLine("### 风险矩阵")
    summary.rows.forEach { row ->
        appendLine("- **${row.decision.label()}** · ${row.risk.label}: ${row.toolCount} 个工具")
        appendLine("  - ${row.risk.description}")
        if (row.toolNames.isNotEmpty()) {
            appendLine("  - 工具: `${row.toolNames.joinToString("`, `")}`")
        }
    }
    appendLine()
    appendLine("### Live UI 安全边界")
    appendLine("- 这些边界高于 `/full`、`/ask`、`/readonly`: 授权模式不会覆盖必须确认或必须用户接管的动作。")
    summary.boundaryRows.forEach { row ->
        appendLine("- **${row.effect.label()}** · ${row.title}")
        appendLine("  - ${row.detail}")
        appendLine("  - 示例: ${row.examples.joinToString("、")}")
    }
    appendLine()
    appendLine("### 切换模式")
    appendLine("- `/full` 完全访问: 所有工具自动执行")
    appendLine("- `/ask` 按需: 读取自动, 写入/执行/网络前询问")
    appendLine("- `/readonly` 只读: 只允许读取, 其他工具阻止")
}

internal fun Decision.label(): String = when (this) {
    Decision.AUTO -> "自动"
    Decision.PROMPT -> "询问"
    Decision.DENY -> "阻止"
}

internal fun SafetyBoundaryEffect.label(): String = when (this) {
    SafetyBoundaryEffect.AUTO -> "自动"
    SafetyBoundaryEffect.PROMPT -> "询问"
    SafetyBoundaryEffect.DENY -> "阻止"
    SafetyBoundaryEffect.HANDOFF -> "交给用户"
}
