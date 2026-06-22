package com.andmx.ui.workbench

import com.andmx.data.ConversationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkbenchStateTest {

    @Test
    fun parsesKnownTabAndFallsBackForUnknownValues() {
        assertEquals(WorkPaneTab.DIFF, parseWorkPaneTab("DIFF"))
        assertEquals(WorkPaneTab.INSPECTOR, parseWorkPaneTab("INSPECTOR"))
        assertEquals(WorkPaneTab.TERMINAL, parseWorkPaneTab("NOPE"))
        assertEquals(WorkPaneTab.TERMINAL, parseWorkPaneTab(""))
    }

    @Test
    fun restoresConversationWorkbenchState() {
        val state = ConversationEntity(
            id = 42,
            project = "andmx",
            title = "thread",
            workPaneTab = "BROWSER",
            workPaneVisible = false,
            terminalDockVisible = true,
            terminalDockTall = true,
            selectedFilePath = "/root/app.kt",
            selectedDiffPath = "/root/app.kt",
            browserUrl = "https://example.com",
            fileCurrentGuestPath = "/root/src",
            fileViewingGuestPath = "/root/src/Main.kt",
        ).toWorkbenchState()

        assertEquals(WorkPaneTab.BROWSER, state.workPaneTab)
        assertFalse(state.workPaneVisible)
        assertTrue(state.terminalDockVisible)
        assertTrue(state.terminalDockTall)
        assertEquals("/root/app.kt", state.selectedFilePath)
        assertEquals("/root/app.kt", state.selectedDiffPath)
        assertEquals("https://example.com", state.browserUrl)
        assertEquals("/root/src", state.fileCurrentGuestPath)
        assertEquals("/root/src/Main.kt", state.fileViewingGuestPath)
    }

    @Test
    fun emptyFileCurrentPathFallsBackToGuestRoot() {
        val state = ConversationEntity(
            project = "andmx",
            title = "thread",
            fileCurrentGuestPath = "",
        ).toWorkbenchState()

        assertEquals("/", state.fileCurrentGuestPath)
    }
}
