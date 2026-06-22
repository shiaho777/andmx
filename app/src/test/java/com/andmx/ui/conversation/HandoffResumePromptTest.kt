package com.andmx.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffResumePromptTest {

    @Test
    fun extractsResumePromptFromHandoffMarkdown() {
        val markdown = """
            ## 线程交接
            内容

            ### 恢复提示
            ```
            继续这个 AndMX 线程。
            项目: andmx
            ```
        """.trimIndent()

        assertEquals(
            "继续这个 AndMX 线程。\n项目: andmx",
            extractHandoffResumePrompt(markdown),
        )
    }

    @Test
    fun ignoresMessagesWithoutResumeSection() {
        assertNull(extractHandoffResumePrompt("## 普通回复\n```kotlin\nval x = 1\n```"))
    }

    @Test
    fun ignoresBlankResumePrompt() {
        val markdown = """
            ### 恢复提示
            ```

            ```
        """.trimIndent()

        assertNull(extractHandoffResumePrompt(markdown))
    }

    @Test
    fun exposesResumeActionsOnlyForHandoffMessages() {
        val handoff = """
            ### 恢复提示
            ```
            继续这个 AndMX 线程。
            ```
        """.trimIndent()

        assertEquals(listOf("当前线程继续", "新线程继续", "复制恢复提示"), handoffResumeActionLabels(handoff))
        assertTrue(handoffResumeActionLabels("普通回复").isEmpty())
    }

    @Test
    fun parsesResumePromptProjectGoalStatusAndTitle() {
        val prompt = """
            继续这个 AndMX 线程。
            项目: openclaw-main
            目标: 复刻 Codex 工作台
            状态: 待继续 · 最近一轮已结束
            请先阅读上方交接摘要, 然后按当前计划继续:
        """.trimIndent()

        val snapshot = parseResumePrompt(prompt)

        assertEquals("openclaw-main", snapshot.project)
        assertEquals("复刻 Codex 工作台", snapshot.goal)
        assertEquals("待继续 · 最近一轮已结束", snapshot.status)
        assertEquals("(恢复) 复刻 Codex 工作台", resumePromptTitle(prompt))
    }

    @Test
    fun ignoresUnsetGoalInResumePrompt() {
        val prompt = """
            继续这个 AndMX 线程。
            目标: (未设置)
        """.trimIndent()

        assertEquals("", parseResumePrompt(prompt).goal)
        assertEquals("(恢复) 继续这个 AndMX 线程。", resumePromptTitle(prompt))
    }

    @Test
    fun choosesResumeGoalForCurrentThreadWithoutClobberingExistingGoal() {
        val promptWithGoal = """
            继续这个 AndMX 线程。
            目标: 复刻 Codex 工作台
        """.trimIndent()
        val unsetGoalPrompt = """
            继续这个 AndMX 线程。
            目标: (未设置)
        """.trimIndent()
        val promptWithoutGoal = "继续这个 AndMX 线程。"

        assertEquals("复刻 Codex 工作台", resumeGoalOverrideForCurrentThread(promptWithGoal, "优化 AndMX"))
        assertEquals("优化 AndMX", resumeGoalOverrideForCurrentThread(unsetGoalPrompt, " 优化 AndMX "))
        assertNull(resumeGoalOverrideForCurrentThread(promptWithoutGoal, ""))
    }
}
