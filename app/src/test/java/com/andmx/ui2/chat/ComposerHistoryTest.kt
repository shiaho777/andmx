package com.andmx.ui2.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerHistoryTest {

    private val history = listOf("newest", "middle", "oldest")

    @Test
    fun firstUpFromEmptyStartsAtNewest() {
        val result = historyUp(history, null, "")

        assertEquals(HistoryBrowseState(0, ""), result?.first)
        assertEquals("newest", result?.second)
    }

    @Test
    fun upDoesNotTakeOverNonEmptyDraft() {
        assertNull(historyUp(history, null, "typing"))
    }

    @Test
    fun repeatedUpWalksOlderAndClampsAtOldest() {
        val first = historyUp(history, null, "")!!
        val second = historyUp(history, first.first, "newest")!!
        val third = historyUp(history, second.first, "middle")!!
        val fourth = historyUp(history, third.first, "oldest")!!

        assertEquals("middle", second.second)
        assertEquals("oldest", third.second)
        assertEquals("oldest", fourth.second)
        assertEquals(2, fourth.first.index)
    }

    @Test
    fun downWalksNewerThenRestoresDraft() {
        val browse = HistoryBrowseState(1, "draft")
        val newer = historyDown(history, browse)!!
        val restored = historyDown(history, newer.first)!!

        assertEquals("newest", newer.second)
        assertEquals("draft", restored.second)
        assertNull(restored.first.index)
    }

    @Test
    fun emptyHistoryNeverBrowses() {
        assertNull(historyUp(emptyList(), null, ""))
        assertNull(historyDown(emptyList(), HistoryBrowseState(0, "")))
    }

    @Test
    fun downWithoutBrowsingDoesNothing() {
        assertNull(historyDown(history, null))
    }
}
