package com.andmx.ui2.chat.voice

import java.util.Locale

enum class VoiceEngine { SYSTEM, PROVIDER, WHISPER_OFFLINE, SAVE_ONLY, NONE }

fun chooseVoiceEngine(
    systemAvailable: Boolean,
    providerAvailable: Boolean,
    canRecord: Boolean,
    whisperReady: Boolean = false,
): VoiceEngine = when {
    systemAvailable -> VoiceEngine.SYSTEM
    !canRecord -> VoiceEngine.NONE
    providerAvailable -> VoiceEngine.PROVIDER
    whisperReady -> VoiceEngine.WHISPER_OFFLINE
    else -> VoiceEngine.SAVE_ONLY
}

fun recognizerLocaleFor(uiTag: String): String = if (uiTag == "en") "en-US" else "zh-CN"

fun formatVoiceDuration(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
    } else {
        "%d:%02d".format(Locale.US, minutes, seconds)
    }
}

fun appendVoiceText(current: String, spoken: String): String = when {
    spoken.isBlank() -> current
    current.isEmpty() -> spoken
    current.last().isWhitespace() -> current + spoken
    else -> "$current $spoken"
}
