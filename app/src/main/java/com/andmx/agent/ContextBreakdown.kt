package com.andmx.agent

import com.andmx.llm.ApiMessage

/**
 * ZCode 对齐的上下文来源分解（chat.contextUsage.breakdown.*）。
 *
 * ZCode 把当前上下文按 7 类来源统计字符占比：
 * messages / system_prompt / meta_user_context / skills / tool_prompt /
 * system_tool_schemas / mcp_tool_schemas。
 *
 * AndMX 引擎侧的对应关系：
 * - system_prompt  → history[0]（composedSystem）
 * - meta_user_context / skills / tool_prompt → 注入 system-reminder 的 meta 块
 *   （META_USER_REMINDER_PREFIX / Agent Instructions / tool schemas 由工具
 *   列表单独估算）
 * - system_tool_schemas → 内置工具 JSON schema
 * - mcp_tool_schemas → MCP/插件工具 JSON schema
 * - messages → 其余 user/assistant/tool 消息
 */
object ContextBreakdown {

    enum class Source(val label: String) {
        MESSAGES("消息"),
        SYSTEM_PROMPT("系统提示词"),
        META_USER_CONTEXT("其他"),
        SKILLS("技能"),
        TOOL_PROMPT("工具提示词"),
        SYSTEM_TOOL_SCHEMAS("系统工具"),
        MCP_TOOL_SCHEMAS("MCP 工具"),
    }

    data class Item(val source: Source, val chars: Int) {
        val percent: Double get() = 0.0
    }

    data class Result(
        val items: List<Item>,
        val totalChars: Int,
    ) {
        fun charsFor(source: Source): Int = items.firstOrNull { it.source == source }?.chars ?: 0

        /** ZCode 对齐：percent 归一化到 0..1，按 chars 降序。 */
        fun withPercent(): List<Pair<Item, Double>> {
            if (totalChars <= 0) return emptyList()
            return items.sortedByDescending { it.chars }
                .map { it to it.chars.toDouble() / totalChars }
        }
    }

    private const val TOOL_SCHEMA_PER_TOOL_CHARS = 220

    fun compute(
        history: List<ApiMessage>,
        systemToolCount: Int = 0,
        mcpToolCount: Int = 0,
        toolSchemaChars: Int = 0,
    ): Result {
        val bySource = mutableMapOf<Source, Int>()
        history.forEachIndexed { index, msg ->
            val source = when {
                index == 0 && msg.role == "system" -> Source.SYSTEM_PROMPT
                msg.role == "system" -> classifySystemReminder(msg.content.orEmpty())
                else -> Source.MESSAGES
            }
            var chars = msg.content?.length ?: 0
            msg.toolCalls?.forEach { call ->
                chars += call.function.name.length + call.function.arguments.length
            }
            bySource.merge(source, chars, Int::plus)
        }
        if (systemToolCount > 0) {
            bySource.merge(Source.SYSTEM_TOOL_SCHEMAS, schemaChars(systemToolCount, toolSchemaChars), Int::plus)
        }
        if (mcpToolCount > 0) {
            bySource.merge(Source.MCP_TOOL_SCHEMAS, schemaChars(mcpToolCount, toolSchemaChars), Int::plus)
        }
        val items = bySource.filterValues { it > 0 }
            .map { (source, chars) -> Item(source, chars) }
            .sortedByDescending { it.chars }
        return Result(items, items.sumOf { it.chars })
    }

    private fun schemaChars(count: Int, explicit: Int): Int =
        if (explicit > 0) explicit else count * TOOL_SCHEMA_PER_TOOL_CHARS

    /**
     * system-reminder 块分类：meta user context（agentsMd/日期等）、skills、
     * 其余（todo reminder 等归 tool prompt 语义）。
     */
    private fun classifySystemReminder(content: String): Source {
        val lower = content.lowercase()
        return when {
            AgentEngine.META_USER_REMINDER_PREFIX.isNotBlank() &&
                lower.contains(AgentEngine.META_USER_REMINDER_PREFIX.lowercase()) -> Source.META_USER_CONTEXT
            lower.contains("<skills>") || lower.contains("available skills") ||
                lower.contains("# skills") -> Source.SKILLS
            else -> Source.TOOL_PROMPT
        }
    }
}
