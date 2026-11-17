package com.andmx.ui2.chat.voice

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceCapsuleStateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeRecorder : VoiceRecorderBackend {
        var started: File? = null
        var level = 0.4f
        override fun start(output: File): Boolean {
            started = output
            output.writeBytes(byteArrayOf(9))
            return true
        }
        override fun stop(): Boolean = true
        override fun cancel() {}
        override fun amplitudeLevel(): Float = level
    }

    private class FakeSystem(var startResult: Boolean = true) : SystemEngineBackend {
        var listener: VoiceInputManager.SystemListener? = null
        var stops = 0
        override fun start(listener: VoiceInputManager.SystemListener): Boolean {
            if (!startResult) return false
            this.listener = listener
            return true
        }
        override fun stop() {
            stops++
        }
        override fun cancel() {
            listener = null
        }
    }

    private var clock = 0L

    private fun state(
        systemAvailable: Boolean = false,
        mic: Boolean = true,
        transcribe: (suspend (ByteArray) -> String?)? = null,
        recorder: VoiceRecorderBackend = FakeRecorder(),
        system: SystemEngineBackend = FakeSystem(),
        inserted: MutableList<String> = mutableListOf(),
    ): VoiceCapsuleState {
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = tmp.root
        }
        val manager = VoiceInputManager(
            context = context,
            transcribe = transcribe,
            localeTag = { "zh-CN" },
            systemAvailable = { systemAvailable },
            hasMicrophone = { mic },
            recorderBackend = recorder,
            systemBackend = system,
            nowMs = { clock },
        )
        return VoiceCapsuleState(
            manager = manager,
            onInsertText = { inserted += it },
            nowMs = { clock },
        )
    }

    @Test
    fun beginHiddenEngineDoesNothing() {
        val s = state(mic = false)
        assertFalse(s.begin())
        assertEquals(VoicePhase.IDLE, s.phase)
    }

    @Test
    fun systemResultInsertsWithoutSending() {
        val inserted = mutableListOf<String>()
        val system = FakeSystem()
        val s = state(systemAvailable = true, system = system, inserted = inserted)
        assertTrue(s.begin())
        assertEquals(VoicePhase.RECORDING, s.phase)
        system.listener!!.onFinal("你好")
        assertEquals(VoicePhase.IDLE, s.phase)
        assertEquals(listOf("你好"), inserted)
        assertNull(s.message)
    }

    @Test
    fun systemErrorShowsInlineError() {
        val system = FakeSystem()
        val s = state(systemAvailable = true, system = system)
        s.begin()
        system.listener!!.onError("未识别到语音")
        assertEquals(VoicePhase.IDLE, s.phase)
        assertEquals("未识别到语音", s.message)
        assertTrue(s.messageIsError)
    }

    @Test
    fun systemStartFailureFallsBackToRecording() {
        val recorder = FakeRecorder()
        val s = state(
            systemAvailable = true,
            system = FakeSystem(startResult = false),
            recorder = recorder,
        )
        assertTrue(s.begin())
        assertEquals(VoicePhase.RECORDING, s.phase)
        assertNotNull(recorder.started)
    }

    @Test
    fun tickUpdatesElapsedAndLevel() {
        val recorder = FakeRecorder()
        clock = 1_000L
        val s = state(recorder = recorder)
        s.begin()
        clock = 4_500L
        s.tick()
        assertEquals(3_500L, s.elapsedMs)
        assertEquals(0.4f, s.level, 0.0001f)
    }

    @Test
    fun providerStopEntersTranscribingThenInserts() = runTest {
        val inserted = mutableListOf<String>()
        val s = state(transcribe = { "转写结果" }, inserted = inserted)
        s.begin()
        s.stop()
        assertEquals(VoicePhase.TRANSCRIBING, s.phase)
        s.runTranscription()
        assertEquals(VoicePhase.IDLE, s.phase)
        assertEquals(listOf("转写结果"), inserted)
    }

    @Test
    fun providerBlankResultShowsSavedNotice() = runTest {
        val s = state(transcribe = { " " })
        s.begin()
        s.stop()
        s.runTranscription()
        assertEquals(VoicePhase.IDLE, s.phase)
        assertFalse(s.messageIsError)
        assertEquals("离线转写未安装，录音已保存，可手动发送音频附件", s.message)
    }

    @Test
    fun saveOnlyStopKeepsFileAndShowsNotice() {
        val recorder = FakeRecorder()
        val s = state(recorder = recorder)
        s.begin()
        s.stop()
        assertEquals(VoicePhase.IDLE, s.phase)
        assertFalse(s.messageIsError)
        assertEquals("离线转写未安装，录音已保存，可手动发送音频附件", s.message)
        assertTrue(recorder.started!!.exists())
    }

    @Test
    fun cancelDuringTranscriptionDropsResult() = runTest {
        val inserted = mutableListOf<String>()
        val s = state(transcribe = { "迟到结果" }, inserted = inserted)
        s.begin()
        s.stop()
        assertEquals(VoicePhase.TRANSCRIBING, s.phase)
        s.cancel()
        s.runTranscription()
        assertEquals(VoicePhase.IDLE, s.phase)
        assertTrue(inserted.isEmpty())
    }

    @Test
    fun cancelDuringRecordingDeletesFile() {
        val recorder = FakeRecorder()
        val s = state(recorder = recorder)
        s.begin()
        val file = recorder.started!!
        s.cancel()
        assertEquals(VoicePhase.IDLE, s.phase)
        assertFalse(file.exists())
    }

    @Test
    fun permissionDeniedShowsErrorWithoutCrash() {
        val s = state()
        s.permissionDenied()
        assertEquals(VoicePhase.IDLE, s.phase)
        assertTrue(s.messageIsError)
        assertEquals("麦克风权限被拒绝，无法录音", s.message)
    }

    @Test
    fun dismissMessageClearsNotice() {
        val s = state()
        s.permissionDenied()
        s.dismissMessage()
        assertNull(s.message)
    }

    @Test
    fun beginIgnoredWhileBusy() {
        val s = state()
        assertTrue(s.begin())
        assertFalse(s.begin())
    }

    @Test
    fun staleSystemFinalAfterCancelIsIgnored() {
        val inserted = mutableListOf<String>()
        val system = FakeSystem()
        val s = state(systemAvailable = true, system = system, inserted = inserted)
        s.begin()
        val listener = system.listener!!
        s.cancel()
        listener.onFinal("过期结果")
        assertTrue(inserted.isEmpty())
        assertEquals(VoicePhase.IDLE, s.phase)
    }
}
