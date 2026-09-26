package com.andmx.agent

import com.andmx.llm.ApiFunctionCall
import com.andmx.llm.ApiMessage
import com.andmx.llm.ApiToolCall
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadedSkillsTest {

    private fun skillCall(id: String, args: String) = ApiMessage(
        role = "assistant",
        toolCalls = listOf(
            ApiToolCall(id = id, function = ApiFunctionCall(name = "Skill", arguments = args)),
        ),
    )

    private fun toolResult(callId: String, content: String) = ApiMessage(
        role = "tool", content = content, toolCallId = callId, name = "Skill",
    )

    private val loadedPayload =
        "<command-name>dynamic-workflows</command-name>\n<command-message>Skill loaded into context. Follow the instructions below.</command-message>\n\n# Skill: dynamic-workflows"

    @Test
    fun emptyHistoryIsNotLoaded() {
        assertFalse(LoadedSkills.sessionHasLoadedSkill(emptyList(), LoadedSkills.DYNAMIC_WORKFLOWS))
    }

    @Test
    fun successfulSkillCallCounts() {
        val history = listOf(
            skillCall("c1", """{"skill":"dynamic-workflows"}"""),
            toolResult("c1", loadedPayload),
        )
        assertTrue(LoadedSkills.sessionHasLoadedSkill(history, LoadedSkills.DYNAMIC_WORKFLOWS))
    }

    @Test
    fun legacyNameArgCounts() {
        val history = listOf(
            skillCall("c1", """{"name":"dynamic-workflows"}"""),
            toolResult("c1", loadedPayload),
        )
        assertTrue(LoadedSkills.sessionHasLoadedSkill(history, LoadedSkills.DYNAMIC_WORKFLOWS))
    }

    @Test
    fun failedResultDoesNotCount() {
        val history = listOf(
            skillCall("c1", """{"skill":"dynamic-workflows"}"""),
            toolResult("c1", "技能未找到: dynamic-workflows。可用: foo"),
        )
        assertFalse(LoadedSkills.sessionHasLoadedSkill(history, LoadedSkills.DYNAMIC_WORKFLOWS))
    }

    @Test
    fun callWithoutResultDoesNotCount() {
        val history = listOf(skillCall("c1", """{"skill":"dynamic-workflows"}"""))
        assertFalse(LoadedSkills.sessionHasLoadedSkill(history, LoadedSkills.DYNAMIC_WORKFLOWS))
    }

    @Test
    fun otherSkillDoesNotCount() {
        val history = listOf(
            skillCall("c1", """{"skill":"andmx-storage-cleanup"}"""),
            toolResult("c1", loadedPayload),
        )
        assertFalse(LoadedSkills.sessionHasLoadedSkill(history, LoadedSkills.DYNAMIC_WORKFLOWS))
    }

    @Test
    fun compactionDropsTheAnswer() {
        // compact 之后 Skill 调用与载荷一起离开可见历史——门随之重新关上。
        val compacted = listOf(
            ApiMessage(role = "system", content = "summary of earlier work"),
        )
        assertFalse(LoadedSkills.sessionHasLoadedSkill(compacted, LoadedSkills.DYNAMIC_WORKFLOWS))
    }
}
