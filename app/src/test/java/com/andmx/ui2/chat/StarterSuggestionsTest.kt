package com.andmx.ui2.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StarterSuggestionsTest {

    @Test
    fun noWorkspaceReturnsGenericTrio() {
        val out = starterSuggestions(false, "", false, false, 0, 0)
        assertEquals(3, out.size)
        assertTrue(out.all { it.title.isNotBlank() && it.prompt.isNotBlank() })
    }

    @Test
    fun dirtyWorkspaceLeadsWithReview() {
        val out = starterSuggestions(true, "demo", true, true, 5, 0)
        assertEquals("Review 未提交改动", out.first().title)
        assertTrue(out.first().prompt.contains("5"))
    }

    @Test
    fun aheadLeadsWithPushCheckWhenClean() {
        val out = starterSuggestions(true, "demo", true, false, 0, 2)
        assertEquals("推送前检查", out.first().title)
        assertTrue(out.first().prompt.contains("2"))
    }

    @Test
    fun dirtyAndAheadBothFitBeforeExplain() {
        val out = starterSuggestions(true, "demo", true, true, 3, 1)
        assertEquals(3, out.size)
        assertEquals("Review 未提交改动", out[0].title)
        assertEquals("推送前检查", out[1].title)
        assertEquals("讲解这个项目", out[2].title)
    }

    @Test
    fun blankProjectFallsBackToWorkspace() {
        val out = starterSuggestions(true, "", true, false, 0, 0)
        assertTrue(out.any { it.prompt.contains("当前工作区") })
    }

    @Test
    fun nonGitWorkspaceSkipsGitCards() {
        val out = starterSuggestions(true, "demo", false, true, 9, 4)
        assertTrue(out.none { it.title == "Review 未提交改动" || it.title == "推送前检查" })
    }
}
