package com.andmx.ui2.chat

import com.andmx.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatControllerLogicTest {

    private fun msg(
        id: Long,
        role: String,
        content: String = "",
        toolName: String? = null,
        toolArgs: String = "",
    ) = MessageEntity(id = id, conversationId = 1, role = role, content = content, toolName = toolName, toolArgs = toolArgs)

    @Test
    fun rebuildPassesUserAndAssistantThrough() {
        val history = ChatControllerLogic.rebuildHistory(
            listOf(
                msg(1, "user", "hi"),
                msg(2, "assistant", "hello"),
            ),
        )
        assertEquals(2, history.size)
        assertEquals("user" to "hi", history[0].role to history[0].content)
        assertEquals("assistant" to "hello", history[1].role to history[1].content)
        assertNull(history[1].toolCalls)
    }

    @Test
    fun consecutiveToolMessagesBatchIntoOneAssistantToolCallsMessage() {
        val history = ChatControllerLogic.rebuildHistory(
            listOf(
                msg(1, "user", "do both"),
                msg(2, "tool", "out-a", toolName = "read", toolArgs = "{\"path\":\"a\"}"),
                msg(3, "tool", "out-b", toolName = "grep", toolArgs = "{\"pattern\":\"p\"}"),
            ),
        )
        assertEquals(4, history.size)

        val calls = history[1]
        assertEquals("assistant", calls.role)
        assertNull(calls.content)
        val batch = calls.toolCalls ?: error("missing toolCalls")
        assertEquals(2, batch.size)
        assertEquals("hist-2", batch[0].id)
        assertEquals("read", batch[0].function.name)
        assertEquals("{\"path\":\"a\"}", batch[0].function.arguments)
        assertEquals("hist-3", batch[1].id)
        assertEquals("grep", batch[1].function.name)

        assertEquals("tool", history[2].role)
        assertEquals("out-a", history[2].content)
        assertEquals("hist-2", history[2].toolCallId)
        assertEquals("hist-3", history[3].toolCallId)
        assertEquals("out-b", history[3].content)
    }

    @Test
    fun blankToolArgsFallBackToEmptyJsonObject() {
        val history = ChatControllerLogic.rebuildHistory(
            listOf(msg(7, "tool", "out", toolName = null)),
        )
        val batch = history[0].toolCalls ?: error("missing toolCalls")
        assertEquals("tool", batch.single().function.name)
        assertEquals("{}", batch.single().function.arguments)
    }

    @Test
    fun unknownRolesAreSkipped() {
        val history = ChatControllerLogic.rebuildHistory(
            listOf(
                msg(1, "approval", "please allow"),
                msg(2, "user", "hi"),
            ),
        )
        assertEquals(1, history.size)
        assertEquals("user", history[0].role)
    }

    @Test
    fun userMessageBreaksToolBatching() {
        val history = ChatControllerLogic.rebuildHistory(
            listOf(
                msg(1, "tool", "out-a", toolName = "read"),
                msg(2, "user", "next"),
                msg(3, "tool", "out-b", toolName = "grep"),
            ),
        )
        assertEquals(5, history.size)
        assertTrue(history[0].toolCalls?.size == 1)
        assertTrue(history[3].toolCalls?.size == 1)
        assertEquals("hist-1", history[0].toolCalls?.single()?.id)
        assertEquals("hist-3", history[3].toolCalls?.single()?.id)
        assertEquals("next", history[2].content)
    }

    @Test
    fun emptyInputYieldsEmptyHistory() {
        assertTrue(ChatControllerLogic.rebuildHistory(emptyList()).isEmpty())
    }

    @Test
    fun skillPayloadStructure() {
        val payload = ChatControllerLogic.formatSkillPayload(
            name = "my-skill",
            path = "/skills/my-skill/SKILL.md",
            body = "Do the thing.",
            scripts = listOf("helper.py"),
            args = "extra",
        )
        listOf(
            "<command-name>my-skill</command-name>",
            "<command-args>extra</command-args>",
            "# Skill: my-skill",
            "Path: /skills/my-skill/SKILL.md",
            "Bundled files: helper.py",
            "Do the thing.",
        ).forEach { expected -> assertTrue(expected, payload.contains(expected)) }
    }

    @Test
    fun skillPayloadOmitsAbsentOptionalsAndFallsBackForEmptyBody() {
        val payload = ChatControllerLogic.formatSkillPayload("s", "/p", "", emptyList(), null)
        assertTrue(!payload.contains("<command-args>"))
        assertTrue(!payload.contains("Bundled files"))
        assertTrue(payload.contains("(skill at /p; empty body)"))
    }

    @Test
    fun skillPayloadIsCappedAt14kChars() {
        val payload = ChatControllerLogic.formatSkillPayload("s", "/p", "x".repeat(50_000), emptyList(), null)
        assertEquals(ChatControllerLogic.SKILL_PAYLOAD_LIMIT, payload.length)
    }

    @Test
    fun approvalSummaryPrefersPreview() {
        assertEquals("Bash · ls -la", ChatControllerLogic.approvalSummary("Bash", "ls -la", "{}"))
    }

    @Test
    fun approvalSummaryFallsBackToRawArgsWhenPreviewBlank() {
        assertEquals("Bash · {\"cmd\":1}", ChatControllerLogic.approvalSummary("Bash", "", "{\"cmd\":1}"))
    }

    @Test
    fun approvalSummaryNameOnlyWhenNothingElse() {
        assertEquals("Read", ChatControllerLogic.approvalSummary("Read", "", "{}"))
        assertEquals("Read", ChatControllerLogic.approvalSummary("Read", "", ""))
    }
}
