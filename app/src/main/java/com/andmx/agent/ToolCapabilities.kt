package com.andmx.agent

data class ToolCapability(
    val name: String,
    val description: String,
    val risk: ToolRisk,
)

fun List<Tool>.toCapabilities(): List<ToolCapability> =
    map { ToolCapability(it.name, it.description, it.risk) }
        .sortedWith(compareBy<ToolCapability> { riskOrder(it.risk) }.thenBy { it.name })

val ToolRisk.label: String
    get() = when (this) {
        ToolRisk.READ -> "读取"
        ToolRisk.WRITE -> "写入"
        ToolRisk.EXECUTE -> "执行"
        ToolRisk.NETWORK -> "网络"
    }

val ToolRisk.description: String
    get() = when (this) {
        ToolRisk.READ -> "可自动读取文件、目录或本地状态"
        ToolRisk.WRITE -> "会修改文件或工作区,通常需要审查"
        ToolRisk.EXECUTE -> "会运行命令或调用外部执行环境"
        ToolRisk.NETWORK -> "会访问网络或远程内容"
    }

fun riskOrder(risk: ToolRisk): Int = when (risk) {
    ToolRisk.READ -> 0
    ToolRisk.WRITE -> 1
    ToolRisk.EXECUTE -> 2
    ToolRisk.NETWORK -> 3
}

fun approvalEffect(mode: ApprovalMode, risk: ToolRisk): String =
    when (ApprovalPolicy.decide(mode, risk)) {
        Decision.AUTO -> "自动"
        Decision.PROMPT -> "询问"
        Decision.DENY -> "阻止"
    }
