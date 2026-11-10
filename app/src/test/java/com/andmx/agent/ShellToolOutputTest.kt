package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellToolOutputTest {

    private val marker = "\n…[截断] 完整输出: /root/.andmx/outputs/shell.log"

    @Test
    fun successfulOutputKeepsHead() {
        val rendered = "HEAD" + "x".repeat(500) + "TAIL"
        val out = truncateShellOutput(rendered, 200, marker, keepTail = false)
        assertTrue(out.startsWith("HEAD"))
        assertTrue(out.endsWith(marker))
        assertTrue(out.length <= 200)
    }

    @Test
    fun failedOutputKeepsHeadAndTail() {
        val rendered = "HEAD" + "x".repeat(500) + "TAIL_ERROR"
        val out = truncateShellOutput(rendered, 200, marker, keepTail = true)
        assertTrue(out.startsWith("HEAD"))
        assertTrue(out.endsWith("TAIL_ERROR"))
        assertTrue(out.contains(marker))
        assertTrue(out.length <= 200)
    }

    @Test
    fun shortOutputIsUntouched() {
        assertEquals("ok", truncateShellOutput("ok", 200, marker, keepTail = true))
    }
}
