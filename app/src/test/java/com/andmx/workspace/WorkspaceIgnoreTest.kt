package com.andmx.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceIgnoreTest {
    @Test
    fun unanchoredNameMatchesAnywhere() {
        val ignore = WorkspaceIgnore.fromLines(listOf("secret.txt", "*.log"))
        assertTrue(ignore.ignores("secret.txt", false))
        assertTrue(ignore.ignores("src/secret.txt", false))
        assertTrue(ignore.ignores("build/app.log", false))
        assertFalse(ignore.ignores("src/App.kt", false))
    }

    @Test
    fun directoryRuleSkipsChildren() {
        val ignore = WorkspaceIgnore.fromLines(listOf("tmp/"))
        assertTrue(ignore.ignores("tmp", true))
        assertTrue(ignore.ignores("src/tmp", true))
        assertTrue(ignore.ignores("tmp/a.txt", false))
        assertFalse(ignore.ignores("tmpfile.txt", false))
    }

    @Test
    fun negationWinsLast() {
        val ignore = WorkspaceIgnore.fromLines(listOf("*.log", "!keep.log"))
        assertTrue(ignore.ignores("a.log", false))
        assertFalse(ignore.ignores("keep.log", false))
    }

    @Test
    fun anchoredPatternStaysAtRoot() {
        val ignore = WorkspaceIgnore.fromLines(listOf("/build"))
        assertTrue(ignore.ignores("build", false))
        assertFalse(ignore.ignores("src/build", false))
    }
}
