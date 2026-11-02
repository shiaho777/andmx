package com.andmx.ui2.chat

import com.andmx.llm.provider.ModelDefinition
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.provider.ReasoningConfig
import com.andmx.llm.provider.ReasoningStyle

/**
 * 持久化到 [com.andmx.settings.ProviderSettings.approvalMode]。
 * 六个值与 ZCode CollaborationMode 相同。
 */
enum class ExecMode(
    val id: String,
    val collaboration: com.andmx.agent.permission.CollaborationMode,
    private val labelText: String,
    private val descriptionText: String,
) {
    BUILD(
        id = "build",
        collaboration = com.andmx.agent.permission.CollaborationMode.BUILD,
        labelText = "构建",
        descriptionText = "只读直接执行，改文件和跑命令前要确认",
    ),
    EDIT(
        id = "edit",
        collaboration = com.andmx.agent.permission.CollaborationMode.EDIT,
        labelText = "编辑",
        descriptionText = "工作区文件编辑直接应用，其它副作用仍要确认",
    ),
    PLAN(
        id = "plan",
        collaboration = com.andmx.agent.permission.CollaborationMode.PLAN,
        labelText = "计划",
        descriptionText = "只做只读探索，不改文件、不跑有副作用的命令",
    ),
    YOLO(
        id = "yolo",
        collaboration = com.andmx.agent.permission.CollaborationMode.YOLO,
        labelText = "Yolo",
        descriptionText = "跳过权限确认，直接执行",
    ),
    GUARDED(
        id = "guarded",
        collaboration = com.andmx.agent.permission.CollaborationMode.GUARDED,
        labelText = "Guarded",
        descriptionText = "默认直接执行，危险命令仍要单独确认一次",
    ),
    AUTO(
        id = "auto",
        collaboration = com.andmx.agent.permission.CollaborationMode.AUTO,
        labelText = "自动",
        descriptionText = "低/中风险直接执行，高风险与破坏性操作仍要确认",
    );

    val label: String get() = com.andmx.ui2.settings.tr(labelText)
    val description: String get() = com.andmx.ui2.settings.tr(descriptionText)

    companion object {
        fun from(stored: String): ExecMode {
            val mode = com.andmx.agent.permission.CollaborationMode.fromWire(stored)
            return entries.first { it.collaboration == mode }
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
    MESSAGE,    // 对话引用（ZCode chat.selections 对齐）
    PASTE,      // 长文本粘贴转附件（ZCode paste-as-attachment 对齐）
    AGENT,      // @ 提及的子代理（ZCode @agents 对齐）
}

/** 粘贴转附件阈值：单次插入超过该字符数的长文本收进 chip 而非塞进输入框。 */
const val PASTE_AS_CHIP_MIN_CHARS = 800

/**
 * 提取一次输入变化中插入的片段（最长公共前后缀）。返回 null 表示非插入或太短。
 * ZCode 对齐：粘贴大段文本转附件 chip，保持输入框可读。
 */
data class PastedSegment(val text: String, val start: Int, val endExclusive: Int)

fun extractPastedSegment(old: String, new: String, minChars: Int = PASTE_AS_CHIP_MIN_CHARS): PastedSegment? {
    if (new.length - old.length < minChars) return null
    var pre = 0
    while (pre < old.length && pre < new.length && old[pre] == new[pre]) pre++
    var suf = 0
    while (suf < old.length - pre && old[old.length - 1 - suf] == new[new.length - 1 - suf]) suf++
    val seg = new.substring(pre, new.length - suf)
    return if (seg.length >= minChars) PastedSegment(seg, pre, new.length - suf) else null
}

/**
 * 对话引用上限（ZCode chat.selections.limit 对齐）：
 * 单条 ≤ 8,000 字符、最多 8 条、总计 ≤ 16,000 字符。
 */
object MessageSelections {
    const val MAX_SINGLE_CHARS = 8_000
    const val MAX_COUNT = 8
    const val MAX_TOTAL_CHARS = 16_000

    sealed class AddResult {
        data object Ok : AddResult()
        data class Rejected(val reason: String) : AddResult()
    }

    /** 校验能否把一条消息引用加入 [current]；重复 id 视为已存在直接放行。 */
    fun validate(current: List<ContextChip>, content: String, id: String): AddResult = when {
        current.any { it.id == id } -> AddResult.Ok
        current.count { it.kind == ContextChipKind.MESSAGE } >= MAX_COUNT ->
            AddResult.Rejected("最多可添加 $MAX_COUNT 条对话引用。")
        content.length > MAX_SINGLE_CHARS -> AddResult.Rejected("单条引用最多 8,000 个字符。")
        current.filter { it.kind == ContextChipKind.MESSAGE }.sumOf { it.payload.length } + content.length >
            MAX_TOTAL_CHARS -> AddResult.Rejected("对话引用总计最多 16,000 个字符。")
        else -> AddResult.Ok
    }
}

/**
 * 思考级别选项。展示层对标 ZCode Thought Level；
 * [wireValue] 写入 [com.andmx.settings.ProviderSettings.reasoningEffort]。
 */
data class ThoughtOption(
    val wireValue: String,
    val label: String,
)

private val THOUGHT_OFF = setOf("off", "disabled", "none")

fun isThoughtOff(value: String): Boolean = value.trim().lowercase() in THOUGHT_OFF

/** 根据当前模型的 [ReasoningConfig] 生成可选思考级别；NONE 时返回空。 */
fun thoughtOptionsFor(reasoning: ReasoningConfig?): List<ThoughtOption> {
    if (reasoning == null || reasoning.style == ReasoningStyle.NONE) return emptyList()
    return when (reasoning.style) {
        ReasoningStyle.NONE -> emptyList()
        ReasoningStyle.EFFORT -> {
            val levels = reasoning.effortLevels.ifEmpty {
                listOf("low", "medium", "high")
            }
            val offWire = levels.firstOrNull { isThoughtOff(it) } ?: "off"
            buildList {
                add(ThoughtOption(offWire, effortLabel("off")))
                levels.forEach { lv ->
                    if (isThoughtOff(lv)) return@forEach
                    add(ThoughtOption(lv, effortLabel(lv)))
                }
            }.distinctBy { it.wireValue.lowercase() }
        }
        ReasoningStyle.THINKING -> listOf(
            ThoughtOption("off", effortLabel("off")),
            ThoughtOption("high", effortLabel("high")),
            ThoughtOption("max", effortLabel("max")),
        )
    }
}

internal fun effortLabel(level: String): String = com.andmx.ui2.settings.tr(when (level.lowercase()) {
    "off", "disabled", "none" -> "关闭"
    "enabled", "on" -> "开启"
    "minimal", "min" -> "最低"
    "low" -> "低"
    "medium", "med" -> "中"
    "high" -> "高"
    "max", "xhigh", "extra_high" -> "最高"
    else -> level.replaceFirstChar { it.uppercase() }
})

fun thoughtLabel(effort: String, options: List<ThoughtOption>): String =
    options.firstOrNull { it.wireValue.equals(effort, ignoreCase = true) }?.label
        ?: options.firstOrNull { isThoughtOff(it.wireValue) && isThoughtOff(effort) }?.label
        ?: com.andmx.ui2.settings.tr(when (effort.lowercase()) {
            "", "off", "disabled", "none" -> "关闭"
            "enabled", "on" -> "开启"
            "minimal", "min" -> "最低"
            "low" -> "低"
            "medium", "med" -> "中"
            "high" -> "高"
            "max", "xhigh", "extra_high" -> "最高"
            else -> effort.replaceFirstChar { it.uppercase() }
        })

/**
 * ZCode 默认「最高」：模型支持推理时，首次默认选最高可用档。
 * THINKING → max；EFFORT → 该模型声明的 defaultEffort（通常 high），或最高可用档。
 */
fun defaultEffortFor(reasoning: ReasoningConfig?): String {
    if (reasoning == null || reasoning.style == ReasoningStyle.NONE) return "off"
    val options = thoughtOptionsFor(reasoning)
    // 优先选 "max"，其次 "high"，再次最后一个非 off 选项；都找不到才 off。
    return options.lastOrNull { !isThoughtOff(it.wireValue) }?.wireValue
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
