package com.andmx.ui2.chat

import android.content.Context

/** Per-task reading position; index 0 is the newest message in the reversed timeline. */
class ChatScrollMemory(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Position(val index: Int, val offset: Int)

    fun load(conversationId: Long): Position? {
        if (conversationId <= 0L) return null
        if (!prefs.contains("${conversationId}_index")) return null
        return Position(
            index = prefs.getInt("${conversationId}_index", 0).coerceAtLeast(0),
            offset = prefs.getInt("${conversationId}_offset", 0).coerceAtLeast(0),
        )
    }

    fun save(conversationId: Long, index: Int, offset: Int) {
        if (conversationId <= 0L) return
        prefs.edit()
            .putInt("${conversationId}_index", index.coerceAtLeast(0))
            .putInt("${conversationId}_offset", offset.coerceAtLeast(0))
            .apply()
    }

    fun clear(conversationId: Long) {
        if (conversationId <= 0L) return
        prefs.edit()
            .remove("${conversationId}_index")
            .remove("${conversationId}_offset")
            .apply()
    }

    private companion object {
        const val PREFS = "andmx_chat_scroll"
    }
}
