package com.andmx.ui2.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSearchTest {

    private val groups = listOf(
        "基础设置" to listOf(SettingsPage.GENERAL, SettingsPage.MODEL, SettingsPage.CODE_PREVIEW),
        "Agent 能力" to listOf(SettingsPage.MEMORY, SettingsPage.SUBAGENT),
    )
    private val descriptions = mapOf(
        SettingsPage.GENERAL to "语言与当前窗口体验",
        SettingsPage.MODEL to "模型供应商与默认模型",
        SettingsPage.CODE_PREVIEW to "代码主题与字号",
        SettingsPage.MEMORY to "长期上下文与 Token 成本",
        SettingsPage.SUBAGENT to "子智能体目录",
    )

    @Test
    fun blankQueryKeepsEverything() {
        assertEquals(groups, filterSettingsGroups("", groups, descriptions) { it })
    }

    @Test
    fun matchesTitleAndDropsEmptyGroups() {
        val result = filterSettingsGroups("模型", groups, descriptions) { it }
        assertEquals(listOf("基础设置"), result.map { it.first })
        assertEquals(listOf(SettingsPage.MODEL), result.single().second)
    }

    @Test
    fun matchesSubtitle() {
        val result = filterSettingsGroups("token", groups, descriptions) { it }
        assertEquals(listOf(SettingsPage.MEMORY), result.single().second)
    }

    @Test
    fun matchesGroupNameAndKeepsWholeGroup() {
        val result = filterSettingsGroups("agent", groups, descriptions) { it }
        assertEquals(listOf("Agent 能力"), result.map { it.first })
        assertEquals(listOf(SettingsPage.MEMORY, SettingsPage.SUBAGENT), result.single().second)
    }

    @Test
    fun matchesEnumNameAsFallback() {
        val result = filterSettingsGroups("code_preview", groups, descriptions) { it }
        assertEquals(listOf(SettingsPage.CODE_PREVIEW), result.single().second)
    }

    @Test
    fun noMatchReturnsEmpty() {
        assertEquals(emptyList<Pair<String, List<SettingsPage>>>(), filterSettingsGroups("nope", groups, descriptions) { it })
    }
}
