package com.andmx.ui2.usage

import com.andmx.data.AndmxDatabase
import com.andmx.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

data class DayBucket(
    val dayStart: Long,
    val tokens: Long,
    val turns: Int,
    val byModel: Map<String, Long> = emptyMap(),
)

data class ModelUsage(
    val model: String,
    val tokens: Long,
    val share: Float,
)

data class UsageStats(
    val totalTokens: Long = 0L,
    val sessions: Int = 0,
    val messages: Int = 0,
    val activeDays: Int = 0,
    val favoriteModel: String = "",
    val favoriteShare: Float = 0f,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val peakHour: Int = -1,
    val peakHourTokens: Long = 0L,
    val daily: List<DayBucket> = emptyList(),
    val models: List<ModelUsage> = emptyList(),
    val rangeDays: Int = 30,
    val loaded: Boolean = false,
)

enum class UsageRange(val label: String, val days: Int) {
    D7("最近 7 天", 7),
    D30("最近 30 天", 30),
}

object UsageCalculator {
    private fun estimateTokens(text: String): Long {
        val body = text.trim()
        if (body.isEmpty()) return 0L
        return (body.length / 3L).coerceAtLeast(1L)
    }

    private fun isUsageMessage(msg: MessageEntity): Boolean {
        if (msg.role != "user" && msg.role != "assistant") return false
        if (msg.content.isBlank()) return false
        if (msg.role == "assistant" && msg.toolName != null) return false
        return true
    }

    fun dayStart(ts: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = ts
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
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
            val now = System.currentTimeMillis()
            val cutoff = dayStart(now) - (range.days - 1L) * 86_400_000L

            val allMessages = mutableListOf<Pair<MessageEntity, String>>()
            var sessionCount = 0
            conversations.forEach { conv ->
                val msgs = runCatching { dao.messagesFor(conv.id) }.getOrDefault(emptyList())
                    .filter { it.createdAt >= cutoff && isUsageMessage(it) }
                if (msgs.isEmpty()) return@forEach
                val hasUser = msgs.any { it.role == "user" }
                if (!hasUser) return@forEach
                sessionCount++
                val model = conv.model.trim()
                msgs.forEach { allMessages.add(it to model) }
            }

            if (allMessages.isEmpty()) {
                return@withContext UsageStats(loaded = true, rangeDays = range.days)
            }

            val dayKeys = (0 until range.days).map { cutoff + it * 86_400_000L }
            val perDayTokens = LinkedHashMap<Long, Long>().also { m -> dayKeys.forEach { m[it] = 0L } }
            val perDayTurns = LinkedHashMap<Long, Int>().also { m -> dayKeys.forEach { m[it] = 0 } }
            val perDayModel = LinkedHashMap<Long, HashMap<String, Long>>().also { m ->
                dayKeys.forEach { m[it] = HashMap() }
            }
            val perHour = LongArray(24)
            val perModel = HashMap<String, Long>()
            var totalTokens = 0L

            allMessages.forEach { (msg, model) ->
                val tok = estimateTokens(msg.content)
                if (tok <= 0L) return@forEach
                totalTokens += tok
                val d = dayStart(msg.createdAt)
                if (d in perDayTokens) {
                    perDayTokens[d] = (perDayTokens[d] ?: 0L) + tok
                    perDayTurns[d] = (perDayTurns[d] ?: 0) + 1
                    if (model.isNotBlank()) {
                        val mm = perDayModel.getOrPut(d) { HashMap() }
                        mm[model] = (mm[model] ?: 0L) + tok
                    }
                }
                perHour[hourOf(msg.createdAt)] += tok
                if (model.isNotBlank()) {
                    perModel[model] = (perModel[model] ?: 0L) + tok
                }
            }

            val daily = dayKeys.map { d ->
                DayBucket(
                    dayStart = d,
                    tokens = perDayTokens[d] ?: 0L,
                    turns = perDayTurns[d] ?: 0,
                    byModel = (perDayModel[d] ?: emptyMap()).toMap(),
                )
            }

            val sortedModels = perModel.entries.sortedByDescending { it.value }
            val top = sortedModels.take(5)
            val other = sortedModels.drop(5).sumOf { it.value }
            val models = buildList {
                top.forEach {
                    add(
                        ModelUsage(
                            model = it.key,
                            tokens = it.value,
                            share = if (totalTokens > 0) it.value.toFloat() / totalTokens else 0f,
                        ),
                    )
                }
                if (other > 0L) {
                    add(
                        ModelUsage(
                            model = "其他模型",
                            tokens = other,
                            share = if (totalTokens > 0) other.toFloat() / totalTokens else 0f,
                        ),
                    )
                }
            }

            val peakHour = perHour.indices.maxByOrNull { perHour[it] } ?: -1
            val activeDates = daily.filter { it.tokens > 0L || it.turns > 0 }.map { it.dayStart }
            val (cur, longest) = streaks(activeDates)

            UsageStats(
                totalTokens = totalTokens,
                sessions = sessionCount,
                messages = allMessages.size,
                activeDays = activeDates.size,
                favoriteModel = models.firstOrNull { it.model.isNotBlank() && it.model != "其他模型" }?.model.orEmpty(),
                favoriteShare = models.firstOrNull { it.model.isNotBlank() && it.model != "其他模型" }?.share ?: 0f,
                currentStreak = cur,
                longestStreak = longest,
                peakHour = peakHour,
                peakHourTokens = if (peakHour >= 0) perHour[peakHour] else 0L,
                daily = daily,
                models = models,
                rangeDays = range.days,
                loaded = true,
            )
        }

    private fun streaks(days: List<Long>): Pair<Int, Int> {
        if (days.isEmpty()) return 0 to 0
        val sorted = days.distinct().sorted()
        val oneDay = 86_400_000L
        var longest = 1
        var run = 1
        for (i in 1 until sorted.size) {
            if (sorted[i] - sorted[i - 1] == oneDay) run++ else run = 1
            if (run > longest) longest = run
        }
        val today = dayStart(System.currentTimeMillis())
        var current = 0
        if (sorted.last() == today || sorted.last() == today - oneDay) {
            current = 1
            for (i in sorted.size - 1 downTo 1) {
                if (sorted[i] - sorted[i - 1] == oneDay) current++ else break
            }
        }
        return current to longest
    }

    fun formatCount(n: Long): String {
        if (n < 0) return "0"
        if (n >= 100_000_000L) {
            val v = n / 100_000_000.0
            return if (abs(v - v.roundToInt()) < 0.05) "${v.roundToInt()}亿" else trimDecimal(v) + "亿"
        }
        if (n >= 10_000L) {
            val v = n / 10_000.0
            return if (abs(v - v.roundToInt()) < 0.05 && v < 100) "${v.roundToInt()}万" else trimDecimal(v) + "万"
        }
        return n.toString()
    }

    fun formatShare(share: Float): String {
        val pct = share * 100f
        return when {
            pct >= 10f -> "${pct.roundToInt()}%"
            pct >= 1f -> "%.1f%%".format(pct)
            pct > 0f -> "%.1f%%".format(pct)
            else -> "0%"
        }
    }

    private fun trimDecimal(v: Double): String {
        val s = "%.1f".format(v)
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }
}
