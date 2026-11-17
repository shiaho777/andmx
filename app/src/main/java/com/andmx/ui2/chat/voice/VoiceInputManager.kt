package com.andmx.ui2.chat.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.andmx.ui2.settings.UiLang
import com.andmx.ui2.settings.tr
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object VoiceFiles {
    const val KEEP_LATEST = 20
    const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    data class Entry(val name: String, val modifiedMs: Long)

    fun fileName(nowMs: Long): String =
        "voice-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs)) + ".m4a"

    fun dir(context: Context): File = File(File(context.filesDir, "cache"), "voice")

    fun cleanupTargets(
        entries: List<Entry>,
        nowMs: Long,
        keepLatest: Int = KEEP_LATEST,
        maxAgeMs: Long = MAX_AGE_MS,
    ): List<String> {
        val keep = keepLatest.coerceAtLeast(1)
        val sorted = entries.sortedByDescending { it.modifiedMs }
        return sorted.filterIndexed { index, entry ->
            index > 0 && (index >= keep || nowMs - entry.modifiedMs >= maxAgeMs)
        }.map { it.name }
    }
}

interface VoiceRecorderBackend {
    fun start(output: File): Boolean
    fun stop(): Boolean
    fun cancel()
    fun amplitudeLevel(): Float
}

interface SystemEngineBackend {
    fun start(listener: VoiceInputManager.SystemListener): Boolean
    fun stop()
    fun cancel()
}

private class MediaRecorderBackend : VoiceRecorderBackend {
    private var recorder: MediaRecorder? = null

    @Suppress("DEPRECATION")
    override fun start(output: File): Boolean {
        cancel()
        val rec = MediaRecorder()
        return runCatching {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(96_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(output.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            true
        }.getOrElse {
            runCatching { rec.release() }
            false
        }
    }

    override fun stop(): Boolean {
        val rec = recorder ?: return false
        recorder = null
        val stopped = runCatching { rec.stop() }.isSuccess
        runCatching { rec.release() }
        return stopped
    }

    override fun cancel() {
        recorder?.let { rec ->
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        recorder = null
    }

    override fun amplitudeLevel(): Float {
        val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        return (amp / 32767f).coerceIn(0f, 1f)
    }
}

private class SpeechRecognizerSystemBackend(
    private val context: Context,
    private val localeTag: () -> String,
) : SystemEngineBackend {
    private var recognizer: SpeechRecognizer? = null

    override fun start(listener: VoiceInputManager.SystemListener): Boolean {
        cancel()
        val rec = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
            ?: return false
        return runCatching {
            rec.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    listener.onRms(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    listener.onError(systemErrorMessage(error))
                    cancel()
                }
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    cancel()
                    if (text.isBlank()) {
                        listener.onError(systemErrorMessage(SpeechRecognizer.ERROR_NO_MATCH))
                    } else {
                        listener.onFinal(text)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?.let(listener::onPartial)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            rec.startListening(systemIntent())
            recognizer = rec
            true
        }.getOrElse {
            runCatching { rec.destroy() }
            false
        }
    }

    override fun stop() {
        runCatching { recognizer?.stopListening() }
    }

    override fun cancel() {
        recognizer?.let { rec ->
            runCatching { rec.cancel() }
            runCatching { rec.destroy() }
        }
        recognizer = null
    }

    private fun systemIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    private fun systemErrorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> tr("录音出错")
        SpeechRecognizer.ERROR_CLIENT -> tr("语音识别客户端错误")
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> tr("缺少麦克风权限")
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> tr("语音识别网络不可用")
        SpeechRecognizer.ERROR_NO_MATCH -> tr("未识别到语音")
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> tr("语音识别服务忙")
        SpeechRecognizer.ERROR_SERVER -> tr("语音识别服务出错")
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> tr("没有听到声音")
        else -> tr("语音识别失败")
    }
}

class VoiceInputManager(
    private val context: Context,
    private val transcribe: (suspend (ByteArray) -> String?)? = null,
    private val localeTag: () -> String = { recognizerLocaleFor(UiLang.tag) },
    private val systemAvailable: () -> Boolean = {
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
    },
    private val hasMicrophone: () -> Boolean = {
        runCatching {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        }.getOrDefault(false)
    },
    private val recorderBackend: VoiceRecorderBackend = MediaRecorderBackend(),
    private val systemBackend: SystemEngineBackend = SpeechRecognizerSystemBackend(context, localeTag),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    interface SystemListener {
        fun onPartial(text: String)
        fun onRms(level: Float)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    private var recordingFile: File? = null

    fun currentEngine(): VoiceEngine =
        chooseVoiceEngine(systemAvailable(), transcribe != null, hasMicrophone())

    fun fallbackEngine(): VoiceEngine =
        chooseVoiceEngine(false, transcribe != null, hasMicrophone())

    fun savedRecordingNotice(): String =
        tr("离线转写未安装，录音已保存，可手动发送音频附件")

    fun startSystem(listener: SystemListener): Boolean =
        runCatching { systemBackend.start(listener) }.getOrDefault(false)

    fun stopSystem() {
        runCatching { systemBackend.stop() }
    }

    fun cancelSystem() {
        runCatching { systemBackend.cancel() }
    }

    fun startRecording(): File? {
        cancelRecording()
        val dir = VoiceFiles.dir(context)
        if (!dir.exists() && !dir.mkdirs()) return null
        cleanup(dir)
        val file = File(dir, VoiceFiles.fileName(nowMs()))
        val started = runCatching { recorderBackend.start(file) }.getOrDefault(false)
        if (!started) {
            file.delete()
            return null
        }
        recordingFile = file
        return file
    }

    fun stopRecording(): File? {
        val file = recordingFile ?: return null
        recordingFile = null
        val stopped = runCatching { recorderBackend.stop() }.getOrDefault(false)
        if (!stopped || !file.exists() || file.length() <= 0L) {
            file.delete()
            return null
        }
        return file
    }

    fun cancelRecording() {
        runCatching { recorderBackend.cancel() }
        recordingFile?.delete()
        recordingFile = null
    }

    fun maxAmplitudeLevel(): Float =
        runCatching { recorderBackend.amplitudeLevel() }.getOrDefault(0f).coerceIn(0f, 1f)

    suspend fun transcribeFile(file: File): String? = withContext(Dispatchers.IO) {
        runCatching { transcribe?.invoke(file.readBytes()) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun release() {
        cancelSystem()
        cancelRecording()
    }

    private fun cleanup(dir: File) {
        val files = dir.listFiles()?.filter {
            it.isFile && it.name.startsWith("voice-") && it.name.endsWith(".m4a")
        } ?: return
        val entries = files.map { VoiceFiles.Entry(it.name, it.lastModified()) }
        val targets = VoiceFiles.cleanupTargets(entries, nowMs()).toSet()
        files.forEach { if (it.name in targets) it.delete() }
    }
}
