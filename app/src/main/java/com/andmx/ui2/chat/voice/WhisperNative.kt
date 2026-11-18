package com.andmx.ui2.chat.voice

object WhisperNative {
    val isAvailable: Boolean = runCatching {
        System.loadLibrary("andmxwhisper")
    }.isSuccess

    external fun init(modelPath: String): Long
    external fun transcribe(handle: Long, pcm: FloatArray, language: String): String?
    external fun release(handle: Long)
}

interface WhisperBackend {
    fun transcribe(modelPath: String, pcm: FloatArray, language: String): String?
    fun release()
}

object NativeWhisperBackend : WhisperBackend {
    private var handle = 0L
    private var loadedModel: String? = null

    @Synchronized
    override fun transcribe(modelPath: String, pcm: FloatArray, language: String): String? {
        if (!WhisperNative.isAvailable || pcm.isEmpty()) return null
        if (handle == 0L || loadedModel != modelPath) {
            releaseLocked()
            handle = WhisperNative.init(modelPath)
            if (handle == 0L) return null
            loadedModel = modelPath
        }
        return runCatching { WhisperNative.transcribe(handle, pcm, language) }.getOrNull()
    }

    @Synchronized
    override fun release() {
        releaseLocked()
    }

    private fun releaseLocked() {
        if (handle != 0L) {
            runCatching { WhisperNative.release(handle) }
            handle = 0L
        }
        loadedModel = null
    }
}
