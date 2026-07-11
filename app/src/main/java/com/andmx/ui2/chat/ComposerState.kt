package com.andmx.ui2.chat

import com.andmx.llm.provider.ModelDefinition
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.provider.ReasoningConfig
import com.andmx.llm.provider.ReasoningStyle

/**
 * ZCode 对齐的执行模式。
 * 持久化到 [com.andmx.settings.ProviderSettings.approvalMode]。
 *
 * 与引擎 [com.andmx.agent.ApprovalMode] 的映射见 [toEngineApproval]。
 */
enum class ExecMode(
    val id: String,
    val label: String,
    val description: String,
) {
    CONFIRM(
        id = "confirm",
        label = "改前确认",
        description = "每次改文件或跑命令前都请求确认",
    ),
    AUTO_EDIT(
        id = "auto_edit",
        label = "自动编辑",
        description = "文件编辑自动应用，命令仍需确认",
    ),
    PLAN(
        id = "plan",
        label = "计划模式",
        description = "先出计划，确认后再动手",
    ),
    FULL(
        id = "full",
        label = "完全访问",
        description = "减少打断，让 Agent 更连续推进",
    );

    companion object {
        /** ZCode 对齐：4 档。旧值/未知值统一回落到 CONFIRM（改前确认）。 */
        fun from(stored: String): ExecMode {
            val s = stored.lowercase().trim()
            return entries.firstOrNull { it.id == s }
                ?: when (s) {
                    "auto", "auto_edit" -> AUTO_EDIT
                    "plan" -> PLAN
                    "yolo", "full", "full_access" -> FULL
                    "readonly", "read_only" -> CONFIRM
                    // default / ask / 空 / 未知 → 改前确认（ZCode 无"默认模式"）
                    else -> CONFIRM
                }
        }
    }
}

/** 输入框上方的上下文 chip（@ 文件 / # 会话 等）。 */
data class ContextChip(
    val id: String,
    val kind: ContextChipKind,
    val label: String,
    val payload: String,
)

enum class ContextChipKind {
    FILE,       // @path
    CONVERSATION, // #id
    COMMAND,    // /cmd
    SKILL,      // $skill
    ATTACHMENT, // 附件
}

/**
 * 思考级别选项。展示层对标 ZCode Thought Level；
 * [wireValue] 写入 [com.andmx.settings.ProviderSettings.reasoningEffort]。
 */
data class ThoughtOption(
    val wireValue: String,
    val label: String,
)

/** 根据当前模型的 [ReasoningConfig] 生成可选思考级别；NONE 时返回空。 */
fun thoughtOptionsFor(reasoning: ReasoningConfig?): List<ThoughtOption> {
    if (reasoning == null || reasoning.style == ReasoningStyle.NONE) return emptyList()
    return when (reasoning.style) {
        ReasoningStyle.NONE -> emptyList()
        ReasoningStyle.EFFORT -> {
            val levels = reasoning.effortLevels.ifEmpty {
                listOf("low", "medium", "high")
            }
            buildList {
                add(ThoughtOption("off", "不思考"))
                levels.forEach { lv ->
                    add(ThoughtOption(lv, effortLabel(lv)))
                }
            }
        }
        // ZCode 对齐：不思考 / 高 / 最高
        ReasoningStyle.THINKING -> listOf(
            ThoughtOption("off", "不思考"),
            ThoughtOption("high", "高"),
            ThoughtOption("max", "最高"),
        )
    }
}

private fun effortLabel(level: String): String = when (level.lowercase()) {
    "off" -> "不思考"
    "minimal", "min" -> "最低"
    "low" -> "低"
    "medium", "med" -> "中"
    "high" -> "高"
    "max", "xhigh", "extra_high" -> "最高"
    else -> level.replaceFirstChar { it.uppercase() }
}

fun thoughtLabel(effort: String, options: List<ThoughtOption>): String =
    options.firstOrNull { it.wireValue.equals(effort, ignoreCase = true) }?.label
        ?: when (effort.lowercase()) {
            "", "off" -> "不思考"
            "minimal", "min" -> "最低"
            "low" -> "低"
            "medium", "med" -> "中"
            "high" -> "高"
            "max", "xhigh", "extra_high" -> "最高"
            else -> effort.replaceFirstChar { it.uppercase() }
        }

/**
 * ZCode 默认「最高」：模型支持推理时，首次默认选最高可用档。
 * THINKING → max；EFFORT → 该模型声明的 defaultEffort（通常 high），或最高可用档。
 */
fun defaultEffortFor(reasoning: ReasoningConfig?): String {
    if (reasoning == null || reasoning.style == ReasoningStyle.NONE) return "off"
    val options = thoughtOptionsFor(reasoning)
    // 优先选 "max"，其次 "high"，再次最后一个非 off 选项；都找不到才 off。
    return options.lastOrNull { it.wireValue != "off" }?.wireValue
        ?: options.firstOrNull()?.wireValue
        ?: "off"
}

/** 模型选择器行。 */
data class ModelPick(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val displayName: String,
)

fun flattenModels(providers: List<ProviderDefinition>): List<ModelPick> =
    providers.filter { it.enabled }.flatMap { p ->
        p.models.keys.map { mid ->
            val def = p.models[mid]
            ModelPick(
                providerId = p.id,
                providerName = p.name,
                modelId = mid,
                displayName = def?.displayName?.takeIf { it.isNotBlank() } ?: mid,
            )
        }
    }

fun resolveModelMeta(
    providers: List<ProviderDefinition>,
    providerId: String,
    modelId: String,
): ModelDefinition? =
    providers.firstOrNull { it.id == providerId }?.models?.get(modelId)
        ?: providers.firstOrNull { it.models.containsKey(modelId) }?.models?.get(modelId)
