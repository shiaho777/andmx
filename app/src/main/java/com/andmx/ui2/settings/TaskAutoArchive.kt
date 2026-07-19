package com.andmx.ui2.settings

import android.content.Context
import com.andmx.data.AndmxDatabase
import com.andmx.settings.ProviderSettings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TaskAutoArchive {
    suspend fun runIfEnabled(context: Context, settings: ProviderSettings) {
        if (!settings.taskAutoArchive) return
        val days = settings.taskAutoArchiveDays.coerceIn(1, 365)
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        val dao = AndmxDatabase.get(context).dao()
        withContext(Dispatchers.IO) {
            val active = dao.conversationsByArchived(false)
            active.forEach { conv ->
                if (conv.pinned) return@forEach
                if (conv.updatedAt >= cutoff) return@forEach
                val count = dao.messageCount(conv.id)
                if (count <= 0) return@forEach
                dao.setArchived(conv.id, true)
            }
        }
    }
}
