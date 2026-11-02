package com.andmx.computeruse

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostNavigationBlockTest {
    @Test
    fun backOnHostIsBlocked() {
        val message = hostNavigationBlock("BACK", "com.andmx", "com.andmx")
        assertTrue(message!!.contains("关掉这个对话"))
    }

    @Test
    fun backOnAnotherAppIsAllowed() {
        assertNull(hostNavigationBlock("back", "com.android.settings", "com.andmx"))
    }

    @Test
    fun unknownForegroundStillBlocksBack() {
        assertTrue(hostNavigationBlock("返回", null, "com.andmx")!!.contains("返回"))
    }

    @Test
    fun enterIsNotANavigationBlock() {
        assertNull(hostNavigationBlock("enter", "com.andmx", "com.andmx"))
    }
}
