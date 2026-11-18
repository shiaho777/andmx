package com.andmx.ui2.chat.voice

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WhisperModelManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeDownloader(
        private val attempts: Int = 1,
        private val payload: (File) -> Unit = {},
    ) : WhisperModelDownloader {
        var calls = 0
        var progressCalls = 0
        override fun download(url: String, dst: File, onProgress: (Long) -> Unit): Boolean {
            calls++
            if (calls < attempts) return false
            payload(dst)
            onProgress(dst.length())
            progressCalls++
            return true
        }
    }

    private fun manager(
        downloader: WhisperModelDownloader,
        dir: File = tmp.newFolder(),
        validator: (File) -> Boolean = WhisperModelManager.Companion::isValidModel,
    ) = WhisperModelManager(
        dir = dir,
        downloader = downloader,
        retryDelaysMs = listOf(0L, 0L),
        validator = validator,
    )

    @Test
    fun absentWhenNoModelFile() {
        val m = manager(FakeDownloader())
        assertEquals(WhisperModelState.ABSENT, m.state)
        assertFalse(m.isReady())
    }

    @Test
    fun magicCheckAcceptsGgmlHeader() {
        val f = tmp.newFile()
        f.writeBytes(byteArrayOf(0x6C, 0x6D, 0x67, 0x67, 0x01))
        assertTrue(WhisperModelManager.hasGgmlMagic(f))
    }

    @Test
    fun magicCheckRejectsWrongOrShortFiles() {
        val bad = tmp.newFile()
        bad.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        assertFalse(WhisperModelManager.hasGgmlMagic(bad))
        val short = tmp.newFile()
        short.writeBytes(byteArrayOf(0x6C, 0x6D))
        assertFalse(WhisperModelManager.hasGgmlMagic(short))
        assertFalse(WhisperModelManager.hasGgmlMagic(File(tmp.root, "missing.bin")))
    }

    @Test
    fun validationRejectsWrongSizeEvenWithMagic() {
        val f = tmp.newFile()
        f.writeBytes(byteArrayOf(0x6C, 0x6D, 0x67, 0x67))
        assertFalse(WhisperModelManager.isValidModel(f))
    }

    @Test
    fun sha256MatchesKnownVector() {
        val f = tmp.newFile()
        f.writeText("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            WhisperModelManager.sha256(f),
        )
    }

    @Test
    fun downloadRejectsInvalidPayloadAndFailsAfterRetries() = runTest {
        val downloader = FakeDownloader(attempts = 1) { dst ->
            dst.writeBytes(byteArrayOf(0x6C, 0x6D, 0x67, 0x67))
        }
        val m = manager(downloader)
        assertFalse(m.download())
        assertEquals(WhisperModelState.FAILED, m.state)
        assertEquals(3, downloader.calls)
        assertFalse(m.modelFile.exists())
        assertFalse(File(m.modelFile.parentFile, "ggml-tiny.bin.part").exists())
    }

    @Test
    fun downloadRetriesThenSucceedsValidationFailureFirst() = runTest {
        var call = 0
        val downloader = object : WhisperModelDownloader {
            override fun download(url: String, dst: File, onProgress: (Long) -> Unit): Boolean {
                call++
                if (call == 1) {
                    dst.writeBytes(byteArrayOf(1, 2, 3))
                } else {
                    dst.writeBytes(ByteArray(16))
                }
                return true
            }
        }
        val m = manager(downloader)
        assertFalse(m.download())
        assertEquals(3, call)
        assertEquals(WhisperModelState.FAILED, m.state)
    }

    @Test
    fun downloadFailureKeepsRetryAvailable() = runTest {
        val downloader = FakeDownloader(attempts = Int.MAX_VALUE)
        val m = manager(downloader)
        assertFalse(m.download())
        assertEquals(WhisperModelState.FAILED, m.state)
        assertFalse(m.download())
        assertEquals(6, downloader.calls)
    }

    @Test
    fun readyWhenValidModelExistsAtStartup() {
        val dir = tmp.newFolder()
        val model = File(dir, WhisperModelManager.MODEL_NAME)
        model.writeBytes(byteArrayOf(0x6C, 0x6D, 0x67, 0x67))
        val m = manager(FakeDownloader(), dir, validator = { it.length() == 4L })
        assertEquals(WhisperModelState.READY, m.state)
        assertTrue(m.isReady())
    }

    @Test
    fun absentWhenExistingFileFailsValidation() {
        val dir = tmp.newFolder()
        val model = File(dir, WhisperModelManager.MODEL_NAME)
        model.writeBytes(byteArrayOf(0x6C, 0x6D, 0x67, 0x67))
        val m = manager(FakeDownloader(), dir)
        assertEquals(WhisperModelState.ABSENT, m.state)
        assertFalse(m.isReady())
    }

    @Test
    fun downloadSuccessMovesPartToFinalAndReportsProgress() = runTest {
        val dir = tmp.newFolder()
        val payload = ByteArray(1024) { it.toByte() }
        val downloader = FakeDownloader(attempts = 2) { dst -> dst.writeBytes(payload) }
        val progress = mutableListOf<WhisperModelProgress>()
        val m = manager(downloader, dir, validator = { it.length() == payload.size.toLong() })
        assertTrue(m.download { progress += it })
        assertEquals(WhisperModelState.READY, m.state)
        assertTrue(m.isReady())
        assertTrue(m.modelFile.exists())
        assertEquals(payload.size.toLong(), m.modelFile.length())
        assertFalse(File(dir, "ggml-tiny.bin.part").exists())
        assertEquals(2, downloader.calls)
        assertEquals(1, progress.size)
        assertEquals(payload.size.toLong(), progress[0].downloadedBytes)
        assertEquals(WhisperModelManager.EXPECTED_SIZE_BYTES, progress[0].totalBytes)
    }

    @Test
    fun downloadShortCircuitsWhenAlreadyReady() = runTest {
        val dir = tmp.newFolder()
        File(dir, WhisperModelManager.MODEL_NAME).writeBytes(ByteArray(8))
        val downloader = FakeDownloader()
        val m = manager(downloader, dir, validator = { it.length() == 8L })
        assertTrue(m.download())
        assertEquals(0, downloader.calls)
    }
}
