package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RapidRefillTest {
    @Test
    fun firstCompactIsNotRapid() {
        val decision = RapidRefill.evaluate(false, 0, 0)
        assertEquals(0, decision.consecutiveRapidRefills)
        assertFalse(decision.shouldBlock)
    }

    @Test
    fun thirdRapidRefillBlocks() {
        var consecutive = 0
        var turns = 0
        var active = false
        repeat(3) {
            val decision = RapidRefill.evaluate(active, consecutive, turns)
            assertFalse(decision.shouldBlock)
            active = true
            consecutive = decision.consecutiveRapidRefills
            turns = 0
        }
        val blocked = RapidRefill.evaluate(active, consecutive, turns)
        assertTrue(blocked.shouldBlock)
        assertTrue(RapidRefill.blockedMessage().contains("3 times in a row"))
    }

    @Test
    fun enoughToolTurnsResetTheStreak() {
        val decision = RapidRefill.evaluate(true, 2, RapidRefill.TOOL_TURN_THRESHOLD)
        assertEquals(0, decision.consecutiveRapidRefills)
        assertFalse(decision.shouldBlock)
    }
}
