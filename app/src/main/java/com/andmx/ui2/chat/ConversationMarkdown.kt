package com.andmx.ui2.chat

import com.andmx.data.ConversationEntity
import com.andmx.data.MessageEntity
import java.time.Instant

internal fun conversationMarkdown(
    conversation: ConversationEntity?,
    messages: List<MessageEntity>,
    exportedAt: Instant,
): String {
    return buildString {
        appendLine("# ${conversation?.title ?: "AndMX 对话"}")
        appendLine()
        appendLine("- 项目: `${conversation?.project.orEmpty()}`")
        appendLine("- 模型: `${conversation?.model.orEmpty()}`")
        appendLine("- 导出时间: ${exportedAt}")
        appendLine()
        messages.forEach { m ->
            when (m.role) {
                "user" -> {
                    appendLine("## User")
                    appendLine(m.content)
                    appendLine()
                }
                "assistant" -> {
                    appendLine("## Assistant")
                    appendLine(m.content)
                    appendLine()
                }
                "tool" -> {
                    appendLine("### Tool · ${m.toolName.orEmpty()}")
                    if (m.toolArgs.isNotBlank()) {
                        appendLine("```json")
                        appendLine(m.toolArgs.take(2000))
                        appendLine("```")
                    }
                    appendLine("```")
                    appendLine(m.content.take(4000))
                    appendLine("```")
                    appendLine()
                }
            }
        }
    }
}
