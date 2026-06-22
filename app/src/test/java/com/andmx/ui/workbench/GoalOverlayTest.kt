package com.andmx.ui.workbench

import com.andmx.ui.conversation.ConversationGoal
import com.andmx.ui.conversation.GoalPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalOverlayTest {

    @Test
    fun emptyGoalCanOnlyBeEditedWhenIdle() {
        val idle = goalOverlayActionState(ConversationGoal(), busy = false)
        assertEquals(GoalOverlayPrimaryAction.NONE, idle.primaryAction)
        assertTrue(idle.canEdit)
        assertFalse(idle.canPause)
        assertFalse(idle.canClear)

        val busy = goalOverlayActionState(ConversationGoal(), busy = true)
        assertFalse(busy.canEdit)
        assertFalse(busy.canPause)
        assertFalse(busy.canClear)
    }

    @Test
    fun readyGoalCanContinuePauseEditAndClear() {
        val state = goalOverlayActionState(goal(GoalPhase.READY), busy = false)

        assertEquals(GoalOverlayPrimaryAction.CONTINUE, state.primaryAction)
        assertTrue(state.canEdit)
        assertTrue(state.canPause)
        assertTrue(state.canClear)
    }

    @Test
    fun pausedGoalResumesInsteadOfPausingAgain() {
        val state = goalOverlayActionState(goal(GoalPhase.PAUSED), busy = false)

        assertEquals(GoalOverlayPrimaryAction.RESUME, state.primaryAction)
        assertTrue(state.canEdit)
        assertFalse(state.canPause)
        assertTrue(state.canClear)
    }

    @Test
    fun setupGoalOpensSettings() {
        val state = goalOverlayActionState(goal(GoalPhase.NEEDS_SETUP), busy = false)

        assertEquals(GoalOverlayPrimaryAction.OPEN_SETTINGS, state.primaryAction)
        assertTrue(state.canPause)
        assertTrue(state.canClear)
    }

    @Test
    fun busyGoalDisablesActions() {
        val state = goalOverlayActionState(goal(GoalPhase.RUNNING), busy = true)

        assertEquals(GoalOverlayPrimaryAction.NONE, state.primaryAction)
        assertFalse(state.canEdit)
        assertFalse(state.canPause)
        assertFalse(state.canClear)
    }

    private fun goal(phase: GoalPhase) = ConversationGoal(
        text = "复刻 Codex 工作台",
        phase = phase,
        startedAt = 1L,
        updatedAt = 2L,
    )
}
