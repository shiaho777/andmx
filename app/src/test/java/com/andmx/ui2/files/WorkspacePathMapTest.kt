package com.andmx.ui2.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePathMapTest {

    private val hostRoot = "/storage/emulated/0/Download/demo"
    private val guestRoot = "/root/project"

    @Test
    fun mapsHostFilesIntoTheGuestWorkspace() {
        assertEquals(
            "/root/project/app/src/Main.kt",
            WorkspacePathMap.toGuest("$hostRoot/app/src/Main.kt", hostRoot, guestRoot),
        )
        assertEquals(guestRoot, WorkspacePathMap.toGuest(hostRoot, hostRoot, guestRoot))
    }

    @Test
    fun rejectsPathsOutsideTheWorkspace() {
        assertNull(WorkspacePathMap.toGuest("/sdcard/other/file.txt", hostRoot, guestRoot))
        assertNull(WorkspacePathMap.toGuest("$hostRoot-other/file.txt", hostRoot, guestRoot))
        assertNull(WorkspacePathMap.toGuest(hostRoot, "", guestRoot))
    }

    @Test
    fun fileMustMatchExactlyAndDirectoryMatchesDescendants() {
        val changed = setOf("/root/project/app/Main.kt")

        assertTrue(
            WorkspacePathMap.isChanged(
                "$hostRoot/app/Main.kt", hostRoot, guestRoot, changed, directory = false,
            ),
        )
        assertFalse(
            WorkspacePathMap.isChanged(
                "$hostRoot/app/Other.kt", hostRoot, guestRoot, changed, directory = false,
            ),
        )
        assertTrue(
            WorkspacePathMap.isChanged(
                "$hostRoot/app", hostRoot, guestRoot, changed, directory = true,
            ),
        )
        assertFalse(
            WorkspacePathMap.isChanged(
                "$hostRoot/docs", hostRoot, guestRoot, changed, directory = true,
            ),
        )
    }

    @Test
    fun trailingSlashesDoNotSplitEquivalentGuestPaths() {
        assertTrue(WorkspacePathMap.sameGuest("/root/project/a.kt/", "/root/project/a.kt"))
    }
}
