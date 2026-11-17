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

class VoiceInputManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeRecorder(
        var startResult: Boolean = true,
        var stopResult: Boolean = true,
        var writeBytes: Boolean = true,
    ) : VoiceRecorderBackend {
        var started: File? = null
        var cancels = 0
        var level = 0.5f

        override fun start(output: File): Boolean {
            if (!startResult) return false
            started = output
            if (writeBytes) output.writeBytes(byteArrayOf(1, 2, 3))
            return true
        }

        override fun stop(): Boolean = stopResult

        override fun cancel() {
            cancels++
        }

        override fun amplitudeLevel(): Float = level
    }

    private class FakeSystem(var startResult: Boolean = true) : SystemEngineBackend {
        var listener: VoiceInputManager.SystemListener? = null
        var stops = 0
        var cancels = 0

        override fun start(listener: VoiceInputManager.SystemListener): Boolean {
            if (!startResult) return false
            this.listener = listener
            return true
        }

        override fun stop() {
            stops++
        }

        override fun cancel() {
            cancels++
        }
    }

    private fun contextFor(dir: File): Context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }

    private fun manager(
        recorder: VoiceRecorderBackend = FakeRecorder(),
        system: SystemEngineBackend = FakeSystem(),
        systemAvailable: Boolean = false,
        providerAvailable: Boolean = false,
        mic: Boolean = true,
        transcribe: (suspend (ByteArray) -> String?)? = null,
        nowMs: Long = 1_000L,
    ): VoiceInputManager = VoiceInputManager(
        context = contextFor(tmp.root),
        transcribe = transcribe,
        localeTag = { "zh-CN" },
        systemAvailable = { systemAvailable },
        hasMicrophone = { mic },
        recorderBackend = recorder,
        systemBackend = system,
        nowMs = { nowMs },
    )

    @Test
    fun engineReflectsInjectedCapabilities() {
        assertEquals(VoiceEngine.SYSTEM, manager(systemAvailable = true).currentEngine())
        assertEquals(
            VoiceEngine.PROVIDER,
            manager(providerAvailable = true, transcribe = { "x" }).currentEngine(),
        )
        assertEquals(VoiceEngine.SAVE_ONLY, manager().currentEngine())
        assertEquals(VoiceEngine.NONE, manager(mic = false).currentEngine())
    }

    @Test
    fun fallbackEngineIgnoresSystem() {
        assertEquals(
            VoiceEngine.PROVIDER,
            manager(systemAvailable = true, providerAvailable = true, transcribe = { "x" })
                .fallbackEngine(),
        )
    }

    @Test
    fun startRecordingCreatesNamedFile() {
        val recorder = FakeRecorder()
        val file = manager(recorder = recorder).startRecording()
        assertNotNull(file)
        assertTrue(file!!.name.startsWith("voice-"))
        assertTrue(file.name.endsWith(".m4a"))
        assertEquals(file, recorder.started)
    }

    @Test
    fun startRecordingReturnsNullWhenBackendFails() {
        val file = manager(recorder = FakeRecorder(startResult = false)).startRecording()
        assertNull(file)
    }

    @Test
    fun stopRecordingReturnsFileWhenNonEmpty() {
        val m = manager()
        m.startRecording()
        val stopped = m.stopRecording()
        assertNotNull(stopped)
        assertTrue(stopped!!.exists())
    }

    @Test
    fun stopRecordingDeletesEmptyFile() {
        val m = manager(recorder = FakeRecorder(writeBytes = false))
        m.startRecording()
        assertNull(m.stopRecording())
    }

    @Test
    fun stopRecordingDeletesWhenStopFails() {
        val m = manager(recorder = FakeRecorder(stopResult = false))
        val started = m.startRecording()!!
        assertNull(m.stopRecording())
        assertFalse(started.exists())
    }

    @Test
    fun stopRecordingWithoutStartReturnsNull() {
        assertNull(manager().stopRecording())
    }

    @Test
    fun cancelRecordingDeletesFileAndCancelsBackend() {
        val recorder = FakeRecorder()
        val m = manager(recorder = recorder)
        val started = m.startRecording()!!
        m.cancelRecording()
        assertFalse(started.exists())
        assertTrue(recorder.cancels >= 1)
    }

    @Test
    fun amplitudeLevelComesFromBackend() {
        val recorder = FakeRecorder().apply { level = 0.75f }
        val m = manager(recorder = recorder)
        m.startRecording()
        assertEquals(0.75f, m.maxAmplitudeLevel(), 0.0001f)
    }

    @Test
    fun transcribeFileInvokesInjectedLambda() = runTest {
        val m = manager(transcribe = { bytes -> "got:${bytes.size}" })
        val file = m.startRecording()!!
        assertEquals("got:3", m.transcribeFile(file))
    }

    @Test
    fun transcribeFileNullWhenNoProvider() = runTest {
        val m = manager()
        val file = m.startRecording()!!
        assertNull(m.transcribeFile(file))
    }

    @Test
    fun transcribeFileFiltersBlankResult() = runTest {
        val m = manager(transcribe = { "   " })
        val file = m.startRecording()!!
        assertNull(m.transcribeFile(file))
    }

    @Test
    fun transcribeFileSwallowsErrors() = runTest {
        val m = manager(transcribe = { error("boom") })
        val file = m.startRecording()!!
        assertNull(m.transcribeFile(file))
    }

    @Test
    fun startSystemDelegatesToBackend() {
        val system = FakeSystem()
        val m = manager(system = system)
        val listener = object : VoiceInputManager.SystemListener {
            override fun onPartial(text: String) {}
            override fun onRms(level: Float) {}
            override fun onFinal(text: String) {}
            override fun onError(message: String) {}
        }
        assertTrue(m.startSystem(listener))
        assertEquals(listener, system.listener)
    }

    @Test
    fun startSystemFalseWhenBackendFails() {
        val m = manager(system = FakeSystem(startResult = false))
        val listener = object : VoiceInputManager.SystemListener {
            override fun onPartial(text: String) {}
            override fun onRms(level: Float) {}
            override fun onFinal(text: String) {}
            override fun onError(message: String) {}
        }
        assertFalse(m.startSystem(listener))
    }

    @Test
    fun releaseCancelsBothBackends() {
        val recorder = FakeRecorder()
        val system = FakeSystem()
        val m = manager(recorder = recorder, system = system)
        m.startRecording()
        m.release()
        assertTrue(recorder.cancels >= 1)
        assertTrue(system.cancels >= 1)
    }

    @Test
    fun savedRecordingNoticeIsHonestOfflineMessage() {
        assertEquals("离线转写未安装，录音已保存，可手动发送音频附件", manager().savedRecordingNotice())
    }
}
