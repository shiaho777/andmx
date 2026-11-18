package com.andmx.ui2.chat.voice

import android.app.Application
import android.content.ContextWrapper
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WhisperBridgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakePcmRecorder(
        private val samples: ShortArray = ShortArray(160) { (it * 100).toShort() },
    ) : PcmRecorderBackend {
        override val sampleRate = 16_000
        var started: File? = null
        override fun start(output: File): Boolean {
            started = output
            RandomAccessFile(output, "rw").use { raf ->
                raf.setLength(44)
                raf.seek(44)
                samples.forEach { s ->
                    raf.write(s.toInt() and 0xFF)
                    raf.write((s.toInt() shr 8) and 0xFF)
                }
            }
            return true
        }
        override fun stop(): Boolean = true
        override fun cancel() {}
        override fun amplitudeLevel(): Float = 0.3f
    }

    private class FakeWhisper(var result: String? = "离线转写结果") : WhisperBackend {
        var modelPath: String? = null
        var pcmSize = 0
        var language: String? = null
        var releases = 0
        override fun transcribe(modelPath: String, pcm: FloatArray, language: String): String? {
            this.modelPath = modelPath
            this.pcmSize = pcm.size
            this.language = language
            return result
        }
        override fun release() {
            releases++
        }
    }

    private class FakeAacRecorder : VoiceRecorderBackend {
        var started: File? = null
        override fun start(output: File): Boolean {
            started = output
            output.writeBytes(byteArrayOf(1, 2, 3))
            return true
        }
        override fun stop(): Boolean = true
        override fun cancel() {}
        override fun amplitudeLevel(): Float = 0.5f
    }

    private fun models(dir: File = tmp.newFolder()): WhisperModelManager {
        File(dir, WhisperModelManager.MODEL_NAME).writeBytes(ByteArray(8))
        return WhisperModelManager(
            dir = dir,
            downloader = object : WhisperModelDownloader {
                override fun download(url: String, dst: File, onProgress: (Long) -> Unit) = false
            },
            retryDelaysMs = listOf(0L),
            validator = { it.length() == 8L },
        )
    }

    private fun manager(
        whisperModel: WhisperModelManager? = null,
        whisperBackend: WhisperBackend? = null,
        nativeAvailable: Boolean = true,
        pcm: PcmRecorderBackend = FakePcmRecorder(),
        aac: VoiceRecorderBackend = FakeAacRecorder(),
        mic: Boolean = true,
        transcribe: (suspend (ByteArray) -> String?)? = null,
    ): VoiceInputManager = VoiceInputManager(
        context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = tmp.root
        },
        transcribe = transcribe,
        localeTag = { "zh-CN" },
        systemAvailable = { false },
        hasMicrophone = { mic },
        recorderBackend = aac,
        systemBackend = object : SystemEngineBackend {
            override fun start(listener: VoiceInputManager.SystemListener) = false
            override fun stop() {}
            override fun cancel() {}
        },
        whisperModel = whisperModel,
        whisperBackend = whisperBackend,
        pcmRecorderBackend = pcm,
        whisperNativeAvailable = { nativeAvailable },
    )

    @Test
    fun nativeLibraryUnavailableOnJvmButFlagIsSafe() {
        assertFalse(WhisperNative.isAvailable)
    }

    @Test
    fun engineIsWhisperWhenModelAndNativeReady() {
        val m = manager(whisperModel = models(), whisperBackend = FakeWhisper())
        assertEquals(VoiceEngine.WHISPER_OFFLINE, m.currentEngine())
        assertEquals(VoiceEngine.WHISPER_OFFLINE, m.fallbackEngine())
        assertTrue(m.whisperReady())
        assertFalse(m.canOfferWhisperDownload())
    }

    @Test
    fun engineFallsBackToSaveOnlyWithoutNative() {
        val m = manager(
            whisperModel = models(),
            whisperBackend = FakeWhisper(),
            nativeAvailable = false,
        )
        assertEquals(VoiceEngine.SAVE_ONLY, m.currentEngine())
        assertFalse(m.canOfferWhisperDownload())
    }

    @Test
    fun downloadOfferedWhenModelMissing() {
        val dir = tmp.newFolder()
        val missing = WhisperModelManager(
            dir = dir,
            downloader = object : WhisperModelDownloader {
                override fun download(url: String, dst: File, onProgress: (Long) -> Unit) = false
            },
            retryDelaysMs = listOf(0L),
            validator = { false },
        )
        val m = manager(whisperModel = missing, whisperBackend = FakeWhisper())
        assertEquals(VoiceEngine.SAVE_ONLY, m.currentEngine())
        assertTrue(m.canOfferWhisperDownload())
    }

    @Test
    fun whisperRecordingProducesWavAndUsesPcmBackend() {
        val pcm = FakePcmRecorder()
        val aac = FakeAacRecorder()
        val m = manager(whisperModel = models(), whisperBackend = FakeWhisper(), pcm = pcm, aac = aac)
        val file = m.startRecording(useWhisper = true)
        assertNotNull(file)
        assertTrue(file!!.name.endsWith(".wav"))
        assertEquals(file, pcm.started)
        assertNull(aac.started)
        val stopped = m.stopRecording()
        assertEquals(file, stopped)
    }

    @Test
    fun plainRecordingStillProducesM4a() {
        val aac = FakeAacRecorder()
        val m = manager(whisperModel = models(), whisperBackend = FakeWhisper(), aac = aac)
        val file = m.startRecording()
        assertNotNull(file)
        assertTrue(file!!.name.endsWith(".m4a"))
        assertEquals(file, aac.started)
    }

    @Test
    fun transcribeWhisperFeedsPcmAndLanguageToBackend() = runTest {
        val backend = FakeWhisper()
        val model = models()
        val m = manager(whisperModel = model, whisperBackend = backend)
        val file = m.startRecording(useWhisper = true)!!
        val stopped = m.stopRecording()!!
        val text = m.transcribeWhisper(stopped)
        assertEquals("离线转写结果", text)
        assertEquals(model.modelFile.absolutePath, backend.modelPath)
        assertEquals(160, backend.pcmSize)
        assertEquals("zh", backend.language)
        assertTrue(file.exists())
    }

    @Test
    fun transcribeWhisperNullWhenModelMissing() = runTest {
        val backend = FakeWhisper()
        val m = manager(whisperBackend = backend)
        val file = m.startRecording()!!
        assertNull(m.transcribeWhisper(file))
        assertNull(backend.modelPath)
    }

    @Test
    fun transcribeWhisperTrimsAndFiltersBlank() = runTest {
        val backend = FakeWhisper(result = "  ")
        val m = manager(whisperModel = models(), whisperBackend = backend)
        m.startRecording(useWhisper = true)
        val stopped = m.stopRecording()!!
        assertNull(m.transcribeWhisper(stopped))
        backend.result = "  你好  "
        assertEquals("你好", m.transcribeWhisper(stopped))
    }

    @Test
    fun releaseFreesWhisperBackend() {
        val backend = FakeWhisper()
        val m = manager(whisperModel = models(), whisperBackend = backend)
        m.release()
        assertEquals(1, backend.releases)
    }

    @Test
    fun wavHeaderWriterProducesRiffLayout() {
        val f = tmp.newFile()
        RandomAccessFile(f, "rw").use { it.setLength(44 + 320) }
        PcmWav.writeHeader(f, 16_000, 320)
        val bytes = f.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals("data", String(bytes, 36, 4))
        assertEquals(36 + 320, (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8))
        assertEquals(320, (bytes[40].toInt() and 0xFF) or ((bytes[41].toInt() and 0xFF) shl 8))
    }

    @Test
    fun capsuleWhisperStopEntersTranscribingAndInserts() = runTest {
        val backend = FakeWhisper(result = "本地转写")
        val model = models()
        val m = manager(whisperModel = model, whisperBackend = backend)
        val inserted = mutableListOf<String>()
        val state = VoiceCapsuleState(m, onInsertText = { inserted += it }, modelManager = model)
        assertTrue(state.begin())
        state.stop()
        assertEquals(VoicePhase.TRANSCRIBING, state.phase)
        state.runTranscription()
        assertEquals(VoicePhase.IDLE, state.phase)
        assertEquals(listOf("本地转写"), inserted)
    }

    @Test
    fun capsuleWhisperFailureShowsErrorNotInstallNotice() = runTest {
        val backend = FakeWhisper(result = null)
        val model = models()
        val m = manager(whisperModel = model, whisperBackend = backend)
        val state = VoiceCapsuleState(m, onInsertText = {}, modelManager = model)
        state.begin()
        state.stop()
        state.runTranscription()
        assertEquals(VoicePhase.IDLE, state.phase)
        assertTrue(state.messageIsError)
        assertEquals("离线转写失败，录音已保存", state.message)
    }

    @Test
    fun capsuleDownloadFlowUpdatesPhasesAndProgress() = runTest {
        val dir = tmp.newFolder()
        val payload = ByteArray(64)
        val model = WhisperModelManager(
            dir = dir,
            downloader = object : WhisperModelDownloader {
                override fun download(url: String, dst: File, onProgress: (Long) -> Unit): Boolean {
                    dst.writeBytes(payload)
                    onProgress(payload.size.toLong())
                    return true
                }
            },
            retryDelaysMs = listOf(0L),
            validator = { it.length() == payload.size.toLong() },
        )
        val m = manager(whisperModel = model, whisperBackend = FakeWhisper())
        val state = VoiceCapsuleState(m, onInsertText = {}, modelManager = model)
        assertTrue(state.canOfferModelDownload())
        state.startModelDownload()
        assertEquals(WhisperDownloadPhase.DOWNLOADING, state.downloadPhase)
        assertFalse(state.canOfferModelDownload())
        state.downloadModel()
        assertEquals(WhisperDownloadPhase.DONE, state.downloadPhase)
        assertEquals(1f, state.downloadProgress, 0.0001f)
        assertEquals(VoiceEngine.WHISPER_OFFLINE, m.currentEngine())
        assertFalse(state.canOfferModelDownload())
    }

    @Test
    fun capsuleDownloadFailureShowsRetryableError() = runTest {
        val dir = tmp.newFolder()
        val model = WhisperModelManager(
            dir = dir,
            downloader = object : WhisperModelDownloader {
                override fun download(url: String, dst: File, onProgress: (Long) -> Unit) = false
            },
            retryDelaysMs = listOf(0L),
            validator = { false },
        )
        val m = manager(whisperModel = model, whisperBackend = FakeWhisper())
        val state = VoiceCapsuleState(m, onInsertText = {}, modelManager = model)
        state.startModelDownload()
        state.downloadModel()
        assertEquals(WhisperDownloadPhase.FAILED, state.downloadPhase)
        assertTrue(state.messageIsError)
        assertEquals("离线模型下载失败，请检查网络后重试", state.message)
        assertTrue(state.canOfferModelDownload())
    }
}
