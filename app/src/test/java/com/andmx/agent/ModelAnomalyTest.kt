package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAnomalyTest {
    @Test
    fun repeatedIdenticalCallWarnsOnceAtTheThresholdAndDoesNotStop() {
        val state = ModelAnomalyState()
        assertNull(detectRepeatedToolCallWarnings("Bash", "{\"command\":\"ls\"}", state))
        assertNull(detectRepeatedToolCallWarnings("Bash", "{\"command\":\"ls\"}", state))
        val warning = detectRepeatedToolCallWarnings("Bash", "{\"command\":\"ls\"}", state)
        assertEquals(3, warning?.observedCount)
        assertTrue(warning?.warningInjected == true)
        assertEquals(1, state.warnings)
        assertNull(detectRepeatedToolCallWarnings("Bash", "{\"command\":\"ls\"}", state))
        val body = repeatedToolCallReminder("Bash", 3)
        assertTrue(body.contains("same input 3 times"))
    }
}
