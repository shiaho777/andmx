package com.andmx.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeTrackerTest {

    @Test
    fun keepsExistingEmptyFileDistinctFromNewFile() {
        ChangeTracker.clear(1L)

        ChangeTracker.record(1L, "/root/existing.txt", oldContent = "", newContent = "filled", existedBefore = true)
        ChangeTracker.record(1L, "/root/new.txt", oldContent = "", newContent = "created", existedBefore = false)

        val changes = ChangeTracker.changesFor(1L).value.associateBy { it.path }
        assertFalse(changes.getValue("/root/existing.txt").isNew)
        assertTrue(changes.getValue("/root/new.txt").isNew)
    }

    @Test
    fun preservesOriginalContentAcrossRepeatedEdits() {
        ChangeTracker.clear(2L)

        ChangeTracker.record(2L, "/root/app.kt", oldContent = "one", newContent = "two", existedBefore = true)
        ChangeTracker.record(2L, "/root/app.kt", oldContent = "two", newContent = "three", existedBefore = true)

        val change = ChangeTracker.changesFor(2L).value.single()
        assertEquals("one", change.oldContent)
        assertEquals("three", change.newContent)
        assertFalse(change.isNew)
    }

    @Test
    fun conversationsAreIsolated() {
        ChangeTracker.clear(3L)
        ChangeTracker.clear(4L)

        ChangeTracker.record(3L, "/root/a.txt", oldContent = "a", newContent = "b", existedBefore = true)
        ChangeTracker.record(4L, "/root/c.txt", oldContent = "c", newContent = "d", existedBefore = true)

        assertEquals(listOf("/root/a.txt"), ChangeTracker.changesFor(3L).value.map { it.path })
        assertEquals(listOf("/root/c.txt"), ChangeTracker.changesFor(4L).value.map { it.path })
    }

    @Test
    fun acceptRemovesReviewedChange() {
        ChangeTracker.clear(5L)

        ChangeTracker.record(5L, "/root/a.txt", oldContent = "a", newContent = "b", existedBefore = true)
        ChangeTracker.record(5L, "/root/c.txt", oldContent = "c", newContent = "d", existedBefore = true)

        ChangeTracker.remove(5L, "/root/a.txt")

        assertEquals(listOf("/root/c.txt"), ChangeTracker.changesFor(5L).value.map { it.path })
    }

    @Test
    fun equivalentGuestPathsCollapseIntoOneReview() {
        ChangeTracker.clear(6L)

        ChangeTracker.record(6L, "/root/app.kt", oldContent = "one", newContent = "two", existedBefore = true)
        ChangeTracker.record(6L, "app.kt", oldContent = "two", newContent = "three", existedBefore = true)

        val change = ChangeTracker.changesFor(6L).value.single()
        assertEquals("one", change.oldContent)
        assertEquals("three", change.newContent)

        ChangeTracker.remove(6L, "/root/app.kt")

        assertTrue(ChangeTracker.changesFor(6L).value.isEmpty())
    }

    @Test
    fun clearRemovesOnlyTargetConversation() {
        ChangeTracker.clear(7L)
        ChangeTracker.clear(8L)

        ChangeTracker.record(7L, "/root/a.txt", oldContent = "a", newContent = "b", existedBefore = true)
        ChangeTracker.record(8L, "/root/c.txt", oldContent = "c", newContent = "d", existedBefore = true)

        ChangeTracker.clear(7L)

        assertTrue(ChangeTracker.changesFor(7L).value.isEmpty())
        assertEquals(listOf("/root/c.txt"), ChangeTracker.changesFor(8L).value.map { it.path })
    }
}
