package com.andmx.ui2.chat

import android.content.Context

/**
 * 任务级 Composer 草稿。桌面端 `task-scoped-composer-drafts` 的移动端对应物：
 * 切会话/被电话打断后，输入框内容不会丢。
 */
class DraftStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(conversationId: Long): String {
        if (conversationId <= 0L) return ""
        return prefs.getString(key(conversationId), "").orEmpty()
    }

    fun save(conversationId: Long, text: String) {
        if (conversationId <= 0L) return
        val trimmed = text.take(MAX_CHARS)
        if (trimmed.isBlank()) {
            prefs.edit().remove(key(conversationId)).apply()
        } else {
            prefs.edit().putString(key(conversationId), trimmed).apply()
        }
    }

    fun clear(conversationId: Long) {
        if (conversationId <= 0L) return
        prefs.edit().remove(key(conversationId)).apply()
    }

    private fun key(id: Long) = "draft_$id"

    private companion object {
        const val PREFS = "andmx_composer_drafts"
        const val MAX_CHARS = 100_000
    }
}
