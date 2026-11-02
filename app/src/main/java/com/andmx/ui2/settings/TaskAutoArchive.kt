package com.andmx.ui2.settings

import android.content.Context
import com.andmx.data.AndmxDatabase
import com.andmx.settings.ProviderSettings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TaskAutoArchive {
    private val ACTIVE_PHASES = setOf(
        "RUNNING",
        "PAUSED",
        "WAITING_APPROVAL",
        "NEEDS_SETUP",
        "FAILED",
    )
    suspend fun runIfEnabled(context: Context, settings: ProviderSettings) {
        if (!settings.taskAutoArchive) return
        val days = settings.taskAutoArchiveDays.coerceIn(1, 365)
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        val dao = AndmxDatabase.get(context).dao()
        withContext(Dispatchers.IO) {
            val active = dao.conversationsByArchived(false)
            active.forEach { conv ->
                if (!eligible(conv, cutoff, dao.messageCount(conv.id))) return@forEach
                dao.setArchived(conv.id, true)
            }
        }
    }

    fun eligible(conv: com.andmx.data.ConversationEntity, cutoff: Long, messageCount: Int): Boolean {
        if (conv.pinned) return false
        val activity = if (conv.updatedAt > 0L) conv.updatedAt else conv.createdAt
        if (activity >= cutoff) return false
        if (conv.unreadAt != null) return false
        if (conv.taskStatus != "completed") return false
        if (conv.goalPhase in ACTIVE_PHASES) return false
        return messageCount > 0
    }
}
