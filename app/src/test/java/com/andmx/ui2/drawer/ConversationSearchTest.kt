package com.andmx.ui2.drawer

import com.andmx.data.ConversationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSearchTest {
    private val conversations = listOf(
        ConversationEntity(id = 3, title = "Release NOTES", project = "/work/app"),
        ConversationEntity(id = 1, title = "Old task", project = "/work/AndMX"),
        ConversationEntity(id = 2, title = "New task", project = "", firstUserMessage = "Fix Search"),
    )

    @Test
    fun blankQueryRestoresOriginalListAndOrder() {
        assertEquals(listOf(3L), filterConversations(conversations, "release").map { it.id })
        assertSame(conversations, filterConversations(conversations, ""))
        assertSame(conversations, filterConversations(conversations, " \t\n"))
    }

    @Test
    fun matchesTitleProjectAndFirstMessageIgnoringCaseAndOuterWhitespace() {
        assertEquals(listOf(3L), filterConversations(conversations, " notes ").map { it.id })
        assertEquals(listOf(1L), filterConversations(conversations, "ANDMX").map { it.id })
        assertEquals(listOf(2L), filterConversations(conversations, "search").map { it.id })
    }

    @Test
    fun preservesSourceOrderAndSupportsArchivedSources() {
        assertEquals(listOf(1L, 2L), filterConversations(conversations, "task").map { it.id })
        val archived = conversations.map { it.copy(archived = true) }
        assertEquals(listOf(3L), filterConversations(archived, "release").map { it.id })
    }

    @Test
    fun unmatchedAndEmptySourcesProduceAnEmptyState() {
        assertTrue(filterConversations(conversations, "missing").isEmpty())
        assertTrue(filterConversations(emptyList(), "task").isEmpty())
        assertTrue(filterConversations(conversations, "%").isEmpty())
    }

    @Test
    fun searchesRevealGroupsWithoutDestroyingCollapsedPreferences() {
        val collapsed = setOf("proj_app", "grp_recent", "tl_TODAY")
        assertTrue(searchCollapsedGroups("task", collapsed).isEmpty())
        assertSame(collapsed, searchCollapsedGroups("", collapsed))
        assertSame(collapsed, searchCollapsedGroups(" \n", collapsed))
    }
}
