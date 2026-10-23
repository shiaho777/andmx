package com.andmx.ui2.chat

import com.andmx.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        )
        assertEquals(
            listOf(
                "<skill_content name=\"my-skill\">",
                "# Skill: my-skill",
                "Do the thing.",
                "Base directory for this skill: /skills/my-skill",
                "Relative paths in this skill are relative to this base directory.",
                "</skill_content>",
            ).joinToString("\n"),
            payload,
        )
        assertFalse(payload.contains("Bundled files"))
        assertFalse(payload.contains("\n\n"))
    }

    @Test
    fun skillPayloadOmitsEmptyBody() {
        val payload = ChatControllerLogic.formatSkillPayload("s", "/p/SKILL.md", "")
        assertEquals(
            listOf(
                "<skill_content name=\"s\">",
                "# Skill: s",
                "Base directory for this skill: /p",
                "Relative paths in this skill are relative to this base directory.",
                "</skill_content>",
            ).joinToString("\n"),
            payload,
        )
    }

    @Test
    fun skillPayloadExpandsSkillDirAndCapsBytes() {
        val payload = ChatControllerLogic.formatSkillPayload(
            "s",
            "/skills/s/SKILL.md",
            "see \${ZCODE_SKILL_DIR}/" + "x".repeat(200_000),
        )
        assertTrue(payload.contains("see /skills/s/"))
        assertTrue(payload.contains("[Skill content truncated]"))
        assertTrue(payload.contains("</skill_content>"))
        val small = ChatControllerLogic.formatSkillPayload("s", "/p/SKILL.md", "short")
        assertFalse(small.contains("truncated"))
        val workflow = "y".repeat(150_000)
        val wide = ChatControllerLogic.formatSkillPayload(
            ChatControllerLogic.DYNAMIC_WORKFLOW_SKILL_NAME,
            "/skills/dynamic-workflows/SKILL.md",
            workflow,
        )
        assertFalse(wide.contains("[Skill content truncated]"))
        assertTrue(wide.contains(workflow))
        val narrow = ChatControllerLogic.formatSkillPayload("s", "/p/SKILL.md", workflow)
        assertTrue(narrow.contains("[Skill content truncated]"))
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
