package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadPathHintTest {
    @Test
    fun prefersSameStem() {
        val hint = ReadPathHint.suggest(listOf("notes.md", "other.txt", "src/"), "notes.txt")
        assertEquals("notes.md", hint)
    }

    @Test
    fun fallsBackToSmallEditDistance() {
        val hint = ReadPathHint.suggest(listOf("MainActivity.kt", "README.md"), "MainActivty.kt")
        assertEquals("MainActivity.kt", hint)
    }

    @Test
    fun ignoresDirectoriesAndExactName() {
        assertNull(ReadPathHint.suggest(listOf("gone/", "exact.kt"), "exact.kt"))
    }
}
