package com.andmx.ui2.chat

import com.andmx.data.ConversationEntity
import com.andmx.data.MessageEntity
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMarkdownTest {
    private val exportedAt = Instant.parse("2026-09-17T00:00:00Z")

    @Test
    fun preservesHeaderRolesAndToolFormatting() {
        val conversation = ConversationEntity(project = "/root/app", title = "Notes", model = "model")
        val messages = listOf(
            message("user", "question"),
            message("assistant", "answer"),
            message("tool", "result").copy(toolName = "Read", toolArgs = "{}"),
            message("reasoning", "not exported"),
        )
        assertEquals(
            "# Notes\n\n- 项目: `/root/app`\n- 模型: `model`\n" +
                "- 导出时间: 2026-09-17T00:00:00Z\n\n" +
                "## User\nquestion\n\n## Assistant\nanswer\n\n" +
                "### Tool · Read\n```json\n{}\n```\n```\nresult\n```\n\n",
            conversationMarkdown(conversation, messages, exportedAt),
        )
    }

    @Test
    fun retainsToolTruncationLimitsAndOptionalArguments() {
        val tool = message("tool", "x".repeat(4001)).copy(toolArgs = "a".repeat(2001))
        val markdown = conversationMarkdown(null, listOf(tool), exportedAt)
        assertTrue(markdown.startsWith("# AndMX 对话\n"))
        assertTrue(markdown.contains("a".repeat(2000)))
        assertFalse(markdown.contains("a".repeat(2001)))
        assertTrue(markdown.contains("x".repeat(4000)))
        assertFalse(markdown.contains("x".repeat(4001)))
        assertFalse(conversationMarkdown(null, listOf(tool.copy(toolArgs = " ")), exportedAt).contains("```json"))
    }

    private fun message(role: String, content: String) =
        MessageEntity(conversationId = 1, role = role, content = content)
}
