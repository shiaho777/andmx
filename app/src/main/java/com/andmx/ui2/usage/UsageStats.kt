package com.andmx.ui2.usage

import com.andmx.data.AndmxDatabase
import com.andmx.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

data class DayBucket(val dayStart: Long, val tokens: Int, val turns: Int)

data class ModelUsage(val model: String, val tokens: Int, val share: Float)

data class UsageStats(
    val totalTokens: Int = 0,
    val sessions: Int = 0,
    val messages: Int = 0,
    val activeDays: Int = 0,
    val favoriteModel: String = "",
    val favoriteShare: Float = 0f,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val peakHour: Int = -1,
    val peakHourTokens: Int = 0,
    val daily: List<DayBucket> = emptyList(),
    val models: List<ModelUsage> = emptyList()
)

enum class UsageRange(val label: String, val days: Int) {
    ALL("全部时间", Int.MAX_VALUE),
    D7("最近 7 天", 7),
    D30("最近 30 天", 30)
}

object UsageCalculator {
    private fun estimateTokens(text: String): Int = (text.length / 3).coerceAtLeast(1)

    private fun dayStart(ts: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = ts
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }

    private fun hourOf(ts: Long): Int =
        Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.HOUR_OF_DAY)

    suspend fun compute(context: android.content.Context, range: UsageRange): UsageStats =
        withContext(Dispatchers.IO) {
            val dao = AndmxDatabase.get(context).dao()
            val conversations = runCatching {
                dao.conversationsByArchived(false) + dao.conversationsByArchived(true)
            }.getOrDefault(emptyList())
            val cutoff = if (range.days == Int.MAX_VALUE) 0L
                else System.currentTimeMillis() - range.days * 86_400_000L

            val allMessages = mutableListOf<Pair<MessageEntity, String>>()
            var sessionCount = 0
            conversations.forEach { conv ->
                val msgs = runCatching { dao.messagesFor(conv.id) }.getOrDefault(emptyList())
                    .filter { it.createdAt >= cutoff }
                if (msgs.isNotEmpty()) {
                    sessionCount++
                    msgs.forEach { allMessages.add(it to conv.model) }
                }
            }

            if (allMessages.isEmpty()) return@withContext UsageStats(sessions = 0)

            val perDay = HashMap<Long, IntArray>()
            val perHour = IntArray(24)
            val perModel = HashMap<String, Int>()
            var totalTokens = 0

            allMessages.forEach { (msg, model) ->
                val tok = estimateTokens(msg.content)
                totalTokens += tok
                val d = dayStart(msg.createdAt)
                val bucket = perDay.getOrPut(d) { IntArray(2) }
                bucket[0] += tok
                bucket[1] += 1
                perHour[hourOf(msg.createdAt)] += tok
                val key = model.ifBlank { "未知模型" }
                perModel[key] = (perModel[key] ?: 0) + tok
            }

            val daily = perDay.entries.sortedBy { it.key }
                .map { DayBucket(it.key, it.value[0], it.value[1]) }

            val sortedModels = perModel.entries.sortedByDescending { it.value }
            val models = sortedModels.map {
                ModelUsage(it.key, it.value, if (totalTokens > 0) it.value.toFloat() / totalTokens else 0f)
            }

            val peakHour = perHour.indices.maxByOrNull { perHour[it] } ?: -1
            val activeDates = perDay.keys.sorted()
            val (cur, longest) = streaks(activeDates)

            UsageStats(
                totalTokens = totalTokens,
                sessions = sessionCount,
                messages = allMessages.size,
                activeDays = perDay.size,
                favoriteModel = models.firstOrNull()?.model ?: "",
                favoriteShare = models.firstOrNull()?.share ?: 0f,
                currentStreak = cur,
                longestStreak = longest,
                peakHour = peakHour,
                peakHourTokens = if (peakHour >= 0) perHour[peakHour] else 0,
                daily = daily,
                models = models
            )
        }

    private fun streaks(days: List<Long>): Pair<Int, Int> {
        if (days.isEmpty()) return 0 to 0
        val oneDay = 86_400_000L
        var longest = 1; var run = 1
        for (i in 1 until days.size) {
            if (days[i] - days[i - 1] == oneDay) run++ else run = 1
            if (run > longest) longest = run
        }
        val today = dayStart(System.currentTimeMillis())
        var current = 0
        if (days.last() == today || days.last() == today - oneDay) {
            current = 1
            for (i in days.size - 1 downTo 1) {
                if (days[i] - days[i - 1] == oneDay) current++ else break
            }
        }
        return current to longest
    }
}
