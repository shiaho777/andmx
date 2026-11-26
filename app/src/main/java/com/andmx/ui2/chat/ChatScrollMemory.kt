package com.andmx.ui2.chat

import android.content.Context

/** Per-task reading position; index 0 is the newest message in the reversed timeline. */
class ChatScrollMemory(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Position(
        val index: Int,
        val offset: Int,
        val itemId: String? = null,
        val sortKey: Long? = null,
    ) {
        fun resolve(items: List<TimelineItem>): Position {
            if ((index == 0 && offset == 0 && itemId == null) || items.isEmpty()) return Position(0, 0)
            val exact = itemId?.let { key -> items.indexOfFirst { it.stableId == key } } ?: -1
            if (exact >= 0) return copy(index = exact)
            val nearby = sortKey?.let { key ->
                items.indices.minByOrNull { kotlin.math.abs(items[it].sortKey - key) }
            }
            return if (nearby != null) Position(nearby, 0)
            else Position(index.coerceIn(0, items.lastIndex), if (index < items.size) offset else 0)
        }
    }

    fun load(conversationId: Long): Position? {
        if (conversationId <= 0L) return null
        if (!prefs.contains("${conversationId}_index")) return null
        return Position(
            index = prefs.getInt("${conversationId}_index", 0).coerceAtLeast(0),
            offset = prefs.getInt("${conversationId}_offset", 0).coerceAtLeast(0),
            itemId = prefs.getString("${conversationId}_item", null),
            sortKey = prefs.getLong("${conversationId}_sort", 0L).takeIf { it > 0L },
        )
    }

    fun save(conversationId: Long, index: Int, offset: Int) =
        save(conversationId, Position(index, offset))

    fun save(conversationId: Long, position: Position) {
        if (conversationId <= 0L) return
        prefs.edit()
            .putInt("${conversationId}_index", position.index.coerceAtLeast(0))
            .putInt("${conversationId}_offset", position.offset.coerceAtLeast(0))
            .putString("${conversationId}_item", position.itemId)
            .putLong("${conversationId}_sort", position.sortKey ?: 0L)
            .apply()
    }

    fun clear(conversationId: Long) {
        if (conversationId <= 0L) return
        prefs.edit()
            .remove("${conversationId}_index")
            .remove("${conversationId}_offset")
            .remove("${conversationId}_item")
            .remove("${conversationId}_sort")
            .apply()
    }

    private companion object {
        const val PREFS = "andmx_chat_scroll"
    }
}
