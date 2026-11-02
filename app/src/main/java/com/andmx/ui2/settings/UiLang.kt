package com.andmx.ui2.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

object UiLang {
    var tag by mutableStateOf("zh")
        private set

    fun apply(locale: String) {
        val next = when (locale) {
            "en", "en-US" -> "en"
            "zh", "zh-CN" -> "zh"
            else -> if (Locale.getDefault().language.startsWith("zh")) "zh" else "en"
        }
        if (tag != next) tag = next
    }
}

fun tr(text: String): String {
    if (UiLang.tag != "en") return text
    settingsEn[text]?.let { return it }
    for (phrase in phraseTemplates) {
        val matched = phrase.regex.matchEntire(text) ?: continue
        val args = matched.groupValues.drop(1).map { settingsEn[it] ?: it }.toTypedArray()
        return runCatching { phrase.english.format(*args) }.getOrDefault(text)
    }
    return text
}
