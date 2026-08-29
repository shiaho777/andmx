package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatCallGuardTest {

    @Test
    fun staysQuietUntilAConfiguredThreshold() {
        val guard = RepeatCallGuard()
        assertNull(guard.onCall("grep", "{\"pattern\":\"a\"}"))
        assertNull(guard.onCall("grep", "{\"pattern\":\"a\"}"))
        assertNotNull("第三次重复应提醒", guard.onCall("grep", "{\"pattern\":\"a\"}"))
    }

    @Test
    fun countsResetWhenTheCallChanges() {
        val guard = RepeatCallGuard()
        guard.onCall("grep", "{\"pattern\":\"a\"}")
        guard.onCall("grep", "{\"pattern\":\"a\"}")
        guard.onCall("grep", "{\"pattern\":\"b\"}")
        assertNull("换参数后计数应归零", guard.onCall("grep", "{\"pattern\":\"b\"}"))
    }

    @Test
    fun argumentOrderDoesNotMakeADifferentCall() {
        val guard = RepeatCallGuard()
        guard.onCall("read", "{\"path\":\"a\",\"limit\":1}")
        guard.onCall("read", "{\"limit\":1,\"path\":\"a\"}")
        assertNotNull("键顺序不同仍是同一次调用", guard.onCall("read", "{\"path\":\"a\",\"limit\":1}"))
    }

    @Test
    fun excludedToolsAreTransparentToTheChain() {
        val guard = RepeatCallGuard()
        guard.onCall("grep", "{\"pattern\":\"a\"}")
        guard.onCall("TodoWrite", "{\"todos\":[]}")
        guard.onCall("grep", "{\"pattern\":\"a\"}")
        // The bookkeeping call neither advanced nor reset the chain.
        assertEquals(2, guard.currentCount)
        assertNotNull(guard.onCall("grep", "{\"pattern\":\"a\"}"))
    }

    @Test
    fun includeOnlyRestrictsTracking() {
        val guard = RepeatCallGuard(includeOnly = setOf("grep"))
        repeat(5) { guard.onCall("read", "{\"path\":\"x\"}") }
        assertEquals("未纳入跟踪的工具不应计数", 0, guard.currentCount)
    }

    @Test
    fun unparseableArgumentsFallBackToTheRawText() {
        val guard = RepeatCallGuard()
        guard.onCall("bash", "not json at all")
        guard.onCall("bash", "not json at all")
        assertNotNull(guard.onCall("bash", "not json at all"))
    }

    @Test
    fun theFirstReminderIsGentleAndLaterOnesNameTheCall() {
        val guard = RepeatCallGuard()
        guard.onCall("grep", "{\"pattern\":\"a\"}")
        guard.onCall("grep", "{\"pattern\":\"a\"}")
        val first = guard.onCall("grep", "{\"pattern\":\"a\"}")
        assertNotNull(first)
        assertTrue(first!!.contains("repeating the exact same tool call"))

        guard.onCall("grep", "{\"pattern\":\"a\"}")
        val later = guard.onCall("grep", "{\"pattern\":\"a\"}")
        assertNotNull(later)
        assertTrue("第五次应点名工具与参数", later!!.contains("- tool: grep"))
        assertTrue(later.contains("consecutive_calls: 5"))
    }

    @Test
    fun theDetailedReminderCapsItsArgumentPreview() {
        val guard = RepeatCallGuard(argumentsPreviewChars = 20)
        val longArgs = "{\"pattern\":\"" + "x".repeat(200) + "\"}"
        var reminder: String? = null
        repeat(5) { reminder = guard.onCall("grep", longArgs) }
        assertNotNull("第五次重复应提醒", reminder)
        assertTrue("超长参数应被截断", reminder!!.contains("more chars"))
    }

    @Test
    fun aFreshTurnClearsTheChain() {
        val guard = RepeatCallGuard()
        repeat(3) { guard.onCall("grep", "{\"pattern\":\"a\"}") }
        guard.reset()
        assertEquals(0, guard.currentCount)
        assertNull(guard.onCall("grep", "{\"pattern\":\"a\"}"))
    }

    @Test
    fun pastTheHighestThresholdTheChainGoesSilent() {
        val guard = RepeatCallGuard(thresholds = listOf(3))
        repeat(3) { guard.onCall("grep", "{\"pattern\":\"a\"}") }
        assertNull("超过最高阈值后不再重复提醒", guard.onCall("grep", "{\"pattern\":\"a\"}"))
    }
}
