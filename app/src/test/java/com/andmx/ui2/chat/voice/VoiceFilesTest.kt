package com.andmx.ui2.chat.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceFilesTest {

    @Test
    fun fileNameHasVoicePrefixAndM4aSuffix() {
        val name = VoiceFiles.fileName(0L)
        assertTrue(name.startsWith("voice-"))
        assertTrue(name.endsWith(".m4a"))
    }

    @Test
    fun fileNameEncodesTimestamp() {
        val name = VoiceFiles.fileName(0L)
        assertTrue(name.matches(Regex("voice-\\d{8}-\\d{6}\\.m4a")))
    }

    @Test
    fun cleanupKeepsNewestWhenUnderLimit() {
        val now = 1_000_000L
        val entries = listOf(
            VoiceFiles.Entry("a.m4a", now - 10),
            VoiceFiles.Entry("b.m4a", now - 20),
        )
        assertTrue(VoiceFiles.cleanupTargets(entries, now).isEmpty())
    }

    @Test
    fun cleanupDropsOldBeyondKeepLatest() {
        val now = 1_000_000L
        val entries = (0 until 5).map { VoiceFiles.Entry("v$it.m4a", now - it.toLong()) }
        val targets = VoiceFiles.cleanupTargets(entries, now, keepLatest = 2)
        assertEquals(setOf("v2.m4a", "v3.m4a", "v4.m4a"), targets.toSet())
        assertFalse(targets.contains("v0.m4a"))
        assertFalse(targets.contains("v1.m4a"))
    }

    @Test
    fun cleanupDropsExpiredButAlwaysKeepsNewest() {
        val now = VoiceFiles.MAX_AGE_MS + 100
        val entries = listOf(
            VoiceFiles.Entry("new.m4a", now),
            VoiceFiles.Entry("old.m4a", 0L),
        )
        val targets = VoiceFiles.cleanupTargets(entries, now)
        assertEquals(listOf("old.m4a"), targets)
    }

    @Test
    fun cleanupNeverRemovesSoleFileEvenIfExpired() {
        val entries = listOf(VoiceFiles.Entry("only.m4a", 0L))
        assertTrue(VoiceFiles.cleanupTargets(entries, VoiceFiles.MAX_AGE_MS + 1).isEmpty())
    }

    @Test
    fun cleanupKeepLatestFloorIsOne() {
        val now = 1_000_000L
        val entries = listOf(
            VoiceFiles.Entry("a.m4a", now),
            VoiceFiles.Entry("b.m4a", now - 1),
        )
        val targets = VoiceFiles.cleanupTargets(entries, now, keepLatest = 0)
        assertEquals(listOf("b.m4a"), targets)
    }
}
