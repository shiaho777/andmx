package com.andmx.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeTrackerTest {

    @Test
    fun keepsExistingEmptyFileDistinctFromNewFile() {
        ChangeTracker.clear()

        ChangeTracker.record("/root/existing.txt", oldContent = "", newContent = "filled", existedBefore = true)
        ChangeTracker.record("/root/new.txt", oldContent = "", newContent = "created", existedBefore = false)

        val changes = ChangeTracker.changes.value.associateBy { it.path }
        assertFalse(changes.getValue("/root/existing.txt").isNew)
        assertTrue(changes.getValue("/root/new.txt").isNew)
    }

    @Test
    fun preservesOriginalContentAcrossRepeatedEdits() {
        ChangeTracker.clear()

        ChangeTracker.record("/root/app.kt", oldContent = "one", newContent = "two", existedBefore = true)
        ChangeTracker.record("/root/app.kt", oldContent = "two", newContent = "three", existedBefore = true)

        val change = ChangeTracker.changes.value.single()
        assertEquals("one", change.oldContent)
        assertEquals("three", change.newContent)
        assertFalse(change.isNew)
    }

    @Test
    fun acceptRemovesReviewedChange() {
        ChangeTracker.clear()

        ChangeTracker.record("/root/a.txt", oldContent = "a", newContent = "b", existedBefore = true)
        ChangeTracker.record("/root/c.txt", oldContent = "c", newContent = "d", existedBefore = true)

        ChangeTracker.accept("/root/a.txt")

        assertEquals(listOf("/root/c.txt"), ChangeTracker.changes.value.map { it.path })
    }

    @Test
    fun equivalentGuestPathsCollapseIntoOneReview() {
        ChangeTracker.clear()

        ChangeTracker.record("/root/app.kt", oldContent = "one", newContent = "two", existedBefore = true)
        ChangeTracker.record("app.kt", oldContent = "two", newContent = "three", existedBefore = true)

        val change = ChangeTracker.changes.value.single()
        assertEquals("one", change.oldContent)
        assertEquals("three", change.newContent)

        ChangeTracker.accept("/root/app.kt")

        assertTrue(ChangeTracker.changes.value.isEmpty())
    }

    @Test
    fun acceptAllClearsReviewedChanges() {
        ChangeTracker.clear()

        ChangeTracker.record("/root/a.txt", oldContent = "a", newContent = "b", existedBefore = true)
        ChangeTracker.record("/root/c.txt", oldContent = "c", newContent = "d", existedBefore = true)

        ChangeTracker.acceptAll()

        assertTrue(ChangeTracker.changes.value.isEmpty())
    }
}
