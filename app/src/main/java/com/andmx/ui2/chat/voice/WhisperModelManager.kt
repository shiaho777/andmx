package com.andmx.ui2.chat.voice

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class WhisperModelState { ABSENT, DOWNLOADING, READY, FAILED }

data class WhisperModelProgress(val downloadedBytes: Long, val totalBytes: Long)

interface WhisperModelDownloader {
    fun download(url: String, dst: File, onProgress: (Long) -> Unit): Boolean
}

class HttpWhisperModelDownloader : WhisperModelDownloader {
    override fun download(url: String, dst: File, onProgress: (Long) -> Unit): Boolean {
        var current = url
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
            }
            when (conn.responseCode) {
                in 300..399 -> {
                    current = conn.getHeaderField("Location") ?: return false
                    conn.disconnect()
                }
                200 -> {
                    val total = conn.contentLengthLong
                    var written = 0L
                    conn.inputStream.use { input ->
                        dst.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                output.write(buf, 0, n)
                                written += n
                                onProgress(if (total > 0) written.coerceAtMost(total) else written)
                            }
                        }
                    }
                    conn.disconnect()
                    return true
                }
                else -> {
                    conn.disconnect()
                    return false
                }
            }
        }
        return false
    }
}

class WhisperModelManager(
    private val dir: File,
    private val downloader: WhisperModelDownloader = HttpWhisperModelDownloader(),
    private val retryDelaysMs: List<Long> = listOf(1_000L, 2_000L),
    private val maxAttempts: Int = 3,
    private val validator: (File) -> Boolean = Companion::isValidModel,
) {
    constructor(context: Context) : this(File(File(context.filesDir, "models"), "whisper"))

    val modelFile: File get() = File(dir, MODEL_NAME)

    private val mutex = Mutex()

    @Volatile
    var state: WhisperModelState =
        if (validator(modelFile)) WhisperModelState.READY else WhisperModelState.ABSENT
        private set

    fun isReady(): Boolean = state == WhisperModelState.READY && validator(modelFile)

    suspend fun download(onProgress: (WhisperModelProgress) -> Unit = {}): Boolean =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (isReady()) return@withContext true
                state = WhisperModelState.DOWNLOADING
                dir.mkdirs()
                val part = File(dir, "$MODEL_NAME.part")
                var ok = false
                for (attempt in 0 until maxAttempts) {
                    part.delete()
                    val downloaded = runCatching {
                        downloader.download(MODEL_URL, part) { bytes ->
                            onProgress(WhisperModelProgress(bytes, EXPECTED_SIZE_BYTES))
                        }
                    }.getOrDefault(false)
                    if (downloaded && validator(part)) {
                        ok = true
                        break
                    }
                    part.delete()
                    if (attempt < maxAttempts - 1) {
                        Thread.sleep(retryDelaysMs.getOrElse(attempt) { 1_000L })
                    }
                }
                if (ok && part.renameTo(modelFile)) {
                    state = WhisperModelState.READY
                    true
                } else {
                    part.delete()
                    state = WhisperModelState.FAILED
                    false
                }
            }
        }

    companion object {
        const val MODEL_NAME = "ggml-tiny.bin"
        const val MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
        const val EXPECTED_SIZE_BYTES = 77_691_713L
        const val EXPECTED_SHA256 =
            "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"
        const val GGML_MAGIC = 0x67676D6C

        fun hasGgmlMagic(file: File): Boolean {
            if (!file.isFile || file.length() < 4) return false
            return runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    val b = ByteArray(4)
                    raf.readFully(b)
                    val magic = (b[0].toInt() and 0xFF) or
                        ((b[1].toInt() and 0xFF) shl 8) or
                        ((b[2].toInt() and 0xFF) shl 16) or
                        ((b[3].toInt() and 0xFF) shl 24)
                    magic == GGML_MAGIC
                }
            }.getOrDefault(false)
        }

        fun isValidModel(file: File): Boolean {
            if (!file.isFile || file.length() != EXPECTED_SIZE_BYTES) return false
            if (!hasGgmlMagic(file)) return false
            return sha256(file).equals(EXPECTED_SHA256, ignoreCase = true)
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
