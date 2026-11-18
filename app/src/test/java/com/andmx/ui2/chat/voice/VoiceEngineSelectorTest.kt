package com.andmx.ui2.chat.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceEngineSelectorTest {

    @Test
    fun systemAndProviderPrefersSystem() {
        assertEquals(VoiceEngine.SYSTEM, chooseVoiceEngine(true, true, true))
    }

    @Test
    fun systemOnlyUsesSystem() {
        assertEquals(VoiceEngine.SYSTEM, chooseVoiceEngine(true, false, true))
    }

    @Test
    fun providerOnlyUsesProvider() {
        assertEquals(VoiceEngine.PROVIDER, chooseVoiceEngine(false, true, true))
    }

    @Test
    fun neitherFallsBackToSaveOnly() {
        assertEquals(VoiceEngine.SAVE_ONLY, chooseVoiceEngine(false, false, true))
    }

    @Test
    fun whisperReadyInsertsOfflineTier() {
        assertEquals(
            VoiceEngine.WHISPER_OFFLINE,
            chooseVoiceEngine(false, false, true, whisperReady = true),
        )
    }

    @Test
    fun fullChainPriorityMatrix() {
        assertEquals(
            VoiceEngine.SYSTEM,
            chooseVoiceEngine(true, true, true, whisperReady = true),
        )
        assertEquals(
            VoiceEngine.PROVIDER,
            chooseVoiceEngine(false, true, true, whisperReady = true),
        )
        assertEquals(
            VoiceEngine.WHISPER_OFFLINE,
            chooseVoiceEngine(false, false, true, whisperReady = true),
        )
        assertEquals(VoiceEngine.SAVE_ONLY, chooseVoiceEngine(false, false, true))
        assertEquals(
            VoiceEngine.NONE,
            chooseVoiceEngine(false, true, false, whisperReady = true),
        )
        assertEquals(
            VoiceEngine.NONE,
            chooseVoiceEngine(false, false, false, whisperReady = true),
        )
    }

    @Test
    fun noMicrophoneHidesWhenNoSystem() {
        assertEquals(VoiceEngine.NONE, chooseVoiceEngine(false, true, false))
        assertEquals(VoiceEngine.NONE, chooseVoiceEngine(false, false, false))
    }

    @Test
    fun systemWinsEvenWithoutMicrophoneFlag() {
        assertEquals(VoiceEngine.SYSTEM, chooseVoiceEngine(true, false, false))
    }

    @Test
    fun localeMapping() {
        assertEquals("en-US", recognizerLocaleFor("en"))
        assertEquals("zh-CN", recognizerLocaleFor("zh"))
        assertEquals("zh-CN", recognizerLocaleFor("fr"))
    }

    @Test
    fun durationFormatsMinutesAndSeconds() {
        assertEquals("0:00", formatVoiceDuration(0))
        assertEquals("0:05", formatVoiceDuration(5_400))
        assertEquals("1:05", formatVoiceDuration(65_000))
    }

    @Test
    fun durationFormatsHours() {
        assertEquals("1:01:01", formatVoiceDuration(3_661_000))
    }

    @Test
    fun durationClampsNegative() {
        assertEquals("0:00", formatVoiceDuration(-5_000))
    }

    @Test
    fun appendVoiceTextHandlesSpacing() {
        assertEquals("hello", appendVoiceText("", "hello"))
        assertEquals("hi there", appendVoiceText("hi", "there"))
        assertEquals("hi there", appendVoiceText("hi ", "there"))
        assertEquals("hi", appendVoiceText("hi", "   "))
    }
}
