package com.andmx.ui2.chat.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.andmx.ui2.settings.tr
import java.io.File

enum class VoicePhase { IDLE, RECORDING, TRANSCRIBING }

class VoiceCapsuleState(
    private val manager: VoiceInputManager,
    private val onInsertText: (String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    var phase by mutableStateOf(VoicePhase.IDLE)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var messageIsError by mutableStateOf(false)
        private set
    var elapsedMs by mutableStateOf(0L)
        private set
    var level by mutableStateOf(0f)
        private set

    private var engine = VoiceEngine.NONE
    private var startedAtMs = 0L
    private var recordingFile: File? = null
    private var session = 0

    private val systemListener = object : VoiceInputManager.SystemListener {
        override fun onPartial(text: String) {}
        override fun onRms(level: Float) {
            if (phase == VoicePhase.RECORDING && engine == VoiceEngine.SYSTEM) {
                this@VoiceCapsuleState.level = level
            }
        }

        override fun onFinal(text: String) {
            if (phase != VoicePhase.RECORDING || engine != VoiceEngine.SYSTEM) return
            finishIdle()
            onInsertText(text)
        }

        override fun onError(message: String) {
            if (phase != VoicePhase.RECORDING || engine != VoiceEngine.SYSTEM) return
            finishIdle()
            showError(message)
        }
    }

    fun engine(): VoiceEngine = manager.currentEngine()

    fun begin(): Boolean {
        if (phase != VoicePhase.IDLE) return false
        message = null
        val available = manager.currentEngine()
        if (available == VoiceEngine.NONE) return false
        if (available == VoiceEngine.SYSTEM && manager.startSystem(systemListener)) {
            engine = VoiceEngine.SYSTEM
            recordingFile = null
        } else {
            val file = manager.startRecording()
            if (file == null) {
                showError(tr("录音启动失败，请检查麦克风权限"))
                return false
            }
            engine = if (available == VoiceEngine.SYSTEM) manager.fallbackEngine() else available
            recordingFile = file
        }
        startedAtMs = nowMs()
        elapsedMs = 0L
        level = 0f
        phase = VoicePhase.RECORDING
        return true
    }

    fun tick(now: Long = nowMs()) {
        if (phase != VoicePhase.RECORDING) return
        elapsedMs = (now - startedAtMs).coerceAtLeast(0L)
        if (recordingFile != null) level = manager.maxAmplitudeLevel()
    }

    fun stop() {
        if (phase != VoicePhase.RECORDING) return
        when (engine) {
            VoiceEngine.SYSTEM -> manager.stopSystem()
            VoiceEngine.PROVIDER -> {
                val file = manager.stopRecording()
                if (file == null) {
                    finishIdle()
                    showError(tr("录音失败，请重试"))
                } else {
                    recordingFile = file
                    phase = VoicePhase.TRANSCRIBING
                }
            }
            VoiceEngine.SAVE_ONLY -> {
                val file = manager.stopRecording()
                finishIdle()
                if (file == null) {
                    showError(tr("录音失败，请重试"))
                } else {
                    messageIsError = false
                    message = manager.savedRecordingNotice()
                }
            }
            VoiceEngine.NONE -> finishIdle()
        }
    }

    suspend fun runTranscription() {
        val file = recordingFile ?: return
        val mySession = session
        val text = manager.transcribeFile(file)
        if (mySession != session || phase != VoicePhase.TRANSCRIBING) return
        finishIdle()
        if (text.isNullOrBlank()) {
            messageIsError = false
            message = manager.savedRecordingNotice()
        } else {
            onInsertText(text)
        }
    }

    fun cancel() {
        session++
        manager.release()
        finishIdle()
    }

    fun permissionDenied() {
        showError(tr("麦克风权限被拒绝，无法录音"))
    }

    fun dismissMessage() {
        message = null
    }

    private fun finishIdle() {
        phase = VoicePhase.IDLE
        recordingFile = null
        elapsedMs = 0L
        level = 0f
    }

    private fun showError(text: String) {
        messageIsError = true
        message = text
    }
}
