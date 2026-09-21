package com.andmx.agent

/**
 * ZCode `system-reminder` 框架对齐（core/src/system-reminder/source.ts）：
 * 所有模型可见的旁路注入都走统一的 `<system-reminder>` 包装，并按来源
 * （[Source]）携带投递通道与生命周期语义。
 *
 * 上游按 channel 决定落点（request_prefix 进提示词前缀、current_turn 挂在
 * 当轮、mid_turn_event 插队、tool_result 内联、history_continuity 只在恢复时
 * 回放、real_user 走真实 user 消息）。AndMX 的 history 是单一 ApiMessage
 * 序列，channel/lifecycle 目前作为描述符保留——用于一致性分类与后续
 * per-request 不落盘通道（上游 runtime_mode 即 per_current_turn）。
 */
object SystemReminder {

    enum class Channel {
        REQUEST_PREFIX, CURRENT_TURN, TOOL_RESULT,
        HISTORY_CONTINUITY, MID_TURN_EVENT, REAL_USER,
    }

    enum class Lifecycle {
        REQUEST_PREFIX, PER_CURRENT_TURN, RUNTIME_LOCAL,
        TOOL_RESULT, RESUME_HISTORY, MID_TURN_EVENT, REAL_USER,
    }

    /** 与上游 source 字符串一一对应（evidenceLabel 取 `sr.<id>`）。 */
    enum class Source(val id: String) {
        // prefix 通道（meta_user 首条注入）
        CONTEXT_PREFIX("context_prefix"),
        SKILLS_LISTING("skills_listing"),
        // 持久化类
        TODO_REMINDER("todo_reminder"),
        TASK_STATUS("task_status"),
        TOOL_RESULT_WARNING("tool_result_warning"),
        RESUME_REFERENCED_SESSION_CONTEXT("resume_referenced_session_context"),
        PLAN_FILE_REFERENCE("plan_file_reference"),
        RESUME_GOAL_STATE("resume_goal_state"),
        GOAL_STATE_CHANGE("goal_state_change"),
        PLUGIN_REFERENCE("plugin_reference"),
        TARGET_CONTINUATION("target_continuation"),
        GOAL_COMPLETION_VERIFICATION("goal_completion_verification"),
        REWIND_NOTICE("rewind_notice"),
        CONVERSATION_FORK("conversation_fork"),
        SELECTION_SIDE_CHAT("selection_side_chat"),
        QUEUED_SYSTEM_NOTIFICATION("queued_system_notification"),
        SHELL_ENVIRONMENT_CHANGE("shell_environment_change"),
        // per-request 类（上游不落盘；AndMX 暂持久化进 history）
        INCOMING_MESSAGE("incoming_message"),
        HOOK_CONTEXT("hook_context"),
        RUNTIME_MODE("runtime_mode"),
        PLAN_MODE_EXIT("plan_mode_exit"),
        OUTPUT_STYLE("output_style"),
        DATE_CHANGE("date_change"),
        REFERENCED_SESSION_CONTEXT("referenced_session_context"),
        MODEL_ANOMALY("model_anomaly"),
        PROMPT_ATTACHMENT("prompt_attachment"),
        DIAGNOSTICS("diagnostics"),
    }

    data class Descriptor(
        val source: Source,
        val channel: Channel,
        val lifecycle: Lifecycle,
        val isMeta: Boolean = true,
        val providerVisible: Boolean = true,
        val evidenceLabel: String = "sr.${source.id}",
    )

    private val DESCRIPTORS: Map<Source, Descriptor> = mapOf(
        Source.INCOMING_MESSAGE to
            Descriptor(Source.INCOMING_MESSAGE, Channel.MID_TURN_EVENT, Lifecycle.MID_TURN_EVENT),
        Source.CONTEXT_PREFIX to
            Descriptor(Source.CONTEXT_PREFIX, Channel.REQUEST_PREFIX, Lifecycle.REQUEST_PREFIX),
        Source.SKILLS_LISTING to
            Descriptor(Source.SKILLS_LISTING, Channel.REQUEST_PREFIX, Lifecycle.REQUEST_PREFIX),
        Source.HOOK_CONTEXT to
            Descriptor(Source.HOOK_CONTEXT, Channel.CURRENT_TURN, Lifecycle.PER_CURRENT_TURN),
        Source.RUNTIME_MODE to
            Descriptor(Source.RUNTIME_MODE, Channel.CURRENT_TURN, Lifecycle.PER_CURRENT_TURN),
        Source.PLAN_MODE_EXIT to
            Descriptor(Source.PLAN_MODE_EXIT, Channel.CURRENT_TURN, Lifecycle.RUNTIME_LOCAL),
        Source.OUTPUT_STYLE to
            Descriptor(Source.OUTPUT_STYLE, Channel.CURRENT_TURN, Lifecycle.PER_CURRENT_TURN),
        Source.DATE_CHANGE to
            Descriptor(Source.DATE_CHANGE, Channel.CURRENT_TURN, Lifecycle.RUNTIME_LOCAL),
        Source.REFERENCED_SESSION_CONTEXT to
            Descriptor(Source.REFERENCED_SESSION_CONTEXT, Channel.CURRENT_TURN, Lifecycle.PER_CURRENT_TURN),
        Source.PLUGIN_REFERENCE to
            Descriptor(Source.PLUGIN_REFERENCE, Channel.CURRENT_TURN, Lifecycle.PER_CURRENT_TURN),
        Source.TODO_REMINDER to
            Descriptor(Source.TODO_REMINDER, Channel.CURRENT_TURN, Lifecycle.PER_CURRENT_TURN),
        Source.TASK_STATUS to
            Descriptor(Source.TASK_STATUS, Channel.MID_TURN_EVENT, Lifecycle.MID_TURN_EVENT),
        Source.TOOL_RESULT_WARNING to
            Descriptor(Source.TOOL_RESULT_WARNING, Channel.TOOL_RESULT, Lifecycle.TOOL_RESULT),
        Source.RESUME_REFERENCED_SESSION_CONTEXT to
            Descriptor(Source.RESUME_REFERENCED_SESSION_CONTEXT, Channel.HISTORY_CONTINUITY, Lifecycle.RESUME_HISTORY),
        Source.PLAN_FILE_REFERENCE to
            Descriptor(Source.PLAN_FILE_REFERENCE, Channel.HISTORY_CONTINUITY, Lifecycle.RESUME_HISTORY),
        Source.RESUME_GOAL_STATE to
            Descriptor(Source.RESUME_GOAL_STATE, Channel.HISTORY_CONTINUITY, Lifecycle.RESUME_HISTORY),
        Source.GOAL_STATE_CHANGE to
            Descriptor(Source.GOAL_STATE_CHANGE, Channel.MID_TURN_EVENT, Lifecycle.MID_TURN_EVENT),
        Source.TARGET_CONTINUATION to
            Descriptor(Source.TARGET_CONTINUATION, Channel.REAL_USER, Lifecycle.REAL_USER, isMeta = false),
        Source.GOAL_COMPLETION_VERIFICATION to
            Descriptor(Source.GOAL_COMPLETION_VERIFICATION, Channel.TOOL_RESULT, Lifecycle.TOOL_RESULT),
        Source.MODEL_ANOMALY to
            Descriptor(Source.MODEL_ANOMALY, Channel.MID_TURN_EVENT, Lifecycle.MID_TURN_EVENT),
        Source.REWIND_NOTICE to
            Descriptor(Source.REWIND_NOTICE, Channel.HISTORY_CONTINUITY, Lifecycle.RESUME_HISTORY),
        Source.CONVERSATION_FORK to
            Descriptor(Source.CONVERSATION_FORK, Channel.HISTORY_CONTINUITY, Lifecycle.RESUME_HISTORY),
        Source.SELECTION_SIDE_CHAT to
            Descriptor(Source.SELECTION_SIDE_CHAT, Channel.HISTORY_CONTINUITY, Lifecycle.RESUME_HISTORY),
        Source.PROMPT_ATTACHMENT to
            Descriptor(Source.PROMPT_ATTACHMENT, Channel.CURRENT_TURN, Lifecycle.PER_CURRENT_TURN),
        Source.QUEUED_SYSTEM_NOTIFICATION to
            Descriptor(Source.QUEUED_SYSTEM_NOTIFICATION, Channel.MID_TURN_EVENT, Lifecycle.MID_TURN_EVENT),
        Source.SHELL_ENVIRONMENT_CHANGE to
            Descriptor(Source.SHELL_ENVIRONMENT_CHANGE, Channel.MID_TURN_EVENT, Lifecycle.MID_TURN_EVENT),
        Source.DIAGNOSTICS to
            Descriptor(Source.DIAGNOSTICS, Channel.MID_TURN_EVENT, Lifecycle.MID_TURN_EVENT),
    )

    fun descriptor(source: Source): Descriptor =
        DESCRIPTORS.getValue(source)

    /**
     * 上游 NON_MID_CONVERSATION_SYSTEM_SOURCES：这些来源的消息位置有语义
     * （如 fork 边界必须在它界定的问题之前），不能交给「会话中部注入」通道。
     */
    private val NON_MID_CONVERSATION = setOf(
        Source.CONTEXT_PREFIX,
        Source.RESUME_REFERENCED_SESSION_CONTEXT,
        Source.CONVERSATION_FORK,
        Source.SELECTION_SIDE_CHAT,
        Source.PLAN_FILE_REFERENCE,
        Source.TARGET_CONTINUATION,
        Source.TOOL_RESULT_WARNING,
        Source.GOAL_COMPLETION_VERIFICATION,
    )

    fun isMidConversation(source: Source): Boolean = source !in NON_MID_CONVERSATION

    private val TAG_PATTERN = Regex("</?system-reminder\\b", RegexOption.IGNORE_CASE)

    /** context_prefix 在上游带尾随换行。 */
    private val TRAILING_NEWLINE_SOURCES = setOf(Source.CONTEXT_PREFIX)

    const val TAG_OPEN = "<system-reminder>"
    const val TAG_CLOSE = "</system-reminder>"

    fun wrap(body: String): String {
        require(body.isNotEmpty()) { "System reminder body cannot be empty" }
        require(!TAG_PATTERN.containsMatchIn(body)) {
            "System reminder body must not include nested system-reminder tags"
        }
        return "$TAG_OPEN\n$body\n$TAG_CLOSE"
    }

    /** 与上游 wrapSystemReminderForSource 对齐：先转义嵌套标签再包装。 */
    fun wrap(source: Source, body: String): String {
        val d = descriptor(source)
        check(d.providerVisible) { "System reminder source ${source.id} is not provider-visible" }
        val wrapped = wrap(escapeNestedTags(body))
        return if (source in TRAILING_NEWLINE_SOURCES) "$wrapped\n" else wrapped
    }

    fun wrap(source: Source, lines: List<String>): String = wrap(source, lines.joinToString("\n"))

    fun escapeNestedTags(body: String): String =
        TAG_PATTERN.replace(body) { "&lt;${it.value.drop(1)}" }

    fun isWrapped(content: String?): Boolean =
        content?.trimStart()?.startsWith(TAG_OPEN) == true

    /** upstream buildDateChangeReminderBody。 */
    fun buildDateChangeBody(currentDateIso: String): String =
        "The date has changed. Today's date is now $currentDateIso. " +
            "DO NOT mention this to the user explicitly because they are already aware."
}
