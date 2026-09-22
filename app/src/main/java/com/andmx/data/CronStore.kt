package com.andmx.data

import com.andmx.agent.CronSchedule
import java.util.UUID

/** ZCode automation port 对齐：cron 定时任务的持久化与调度推进。 */
class CronStore(context: android.content.Context) {
    private val dao = AndmxDatabase.get(context).dao()

    suspend fun create(
        conversationId: Long,
        title: String,
        prompt: String,
        cron: String?,
        delayMinutes: Int?,
        recurring: Boolean,
        maxRuns: Int?,
        intervalUnit: String?,
        interval: Int?,
        model: String,
        now: Long = System.currentTimeMillis(),
    ): Pair<CronAutomationEntity?, String?> {
        val (spec, err) = CronSchedule.buildSpec(
            title, prompt, cron, delayMinutes, recurring, maxRuns,
            intervalUnit, interval, now,
        )
        if (err != null || spec == null) return null to (err ?: "invalid schedule")
        val entity = CronAutomationEntity(
            id = "auto_${UUID.randomUUID().toString().take(12)}",
            conversationId = conversationId,
            title = spec.title,
            prompt = spec.prompt,
            cronExpr = spec.cronExpr,
            intervalUnit = spec.intervalUnit,
            interval = spec.interval,
            anchorAt = spec.anchorAt,
            enabled = true,
            recurring = spec.recurring,
            maxRuns = spec.maxRuns,
            runCount = 0,
            nextRunAt = spec.nextRunAt,
            lastRunAt = 0L,
            lifecycleStatus = "active",
            model = model,
        )
        dao.upsertAutomation(entity)
        return entity to null
    }

    /** 闲时延迟任务（上游 off-peak port 等价）：固定 nextRunAt 触发一次。 */
    suspend fun createDeferred(
        conversationId: Long,
        title: String,
        prompt: String,
        model: String,
        runAtMs: Long,
    ): CronAutomationEntity {
        val entity = CronAutomationEntity(
            id = "auto_${UUID.randomUUID().toString().take(12)}",
            conversationId = conversationId,
            title = "[闲时] $title",
            prompt = prompt,
            cronExpr = "",
            recurring = false,
            maxRuns = 1,
            nextRunAt = runAtMs,
            model = model,
        )
        dao.upsertAutomation(entity)
        return entity
    }

    suspend fun list(): List<CronAutomationEntity> = dao.allAutomations()

    suspend fun get(id: String): CronAutomationEntity? = dao.automation(id)

    suspend fun update(
        id: String,
        title: String?,
        prompt: String?,
        cron: String?,
        recurring: Boolean?,
        maxRuns: Int?,
        intervalUnit: String?,
        interval: Int?,
        enabled: Boolean?,
        now: Long = System.currentTimeMillis(),
    ): Pair<CronAutomationEntity?, String?> {
        val cur = dao.automation(id) ?: return null to "Automation $id was not found in the current workspace."
        val effRecurring = recurring ?: cur.recurring
        val effMaxRuns = when {
            effRecurring -> 0
            maxRuns != null -> maxRuns
            cur.maxRuns > 0 -> cur.maxRuns
            else -> 0
        }
        val scheduleChanged = cron != null || intervalUnit != null || interval != null
        var cronExpr = cur.cronExpr
        var ruleUnit = cur.intervalUnit
        var ruleInterval = cur.interval
        var anchor = cur.anchorAt
        if (scheduleChanged) {
            val (spec, err) = CronSchedule.buildSpec(
                title = title ?: cur.title,
                prompt = prompt ?: cur.prompt,
                cron = cron ?: cur.cronExpr,
                delayMinutes = null,
                recurring = effRecurring,
                maxRuns = effMaxRuns.takeIf { it > 0 },
                intervalUnit = intervalUnit ?: cur.intervalUnit.ifBlank { null },
                interval = interval ?: cur.interval.takeIf { it > 0 },
                now = now,
            )
            if (err != null || spec == null) return null to (err ?: "invalid schedule")
            cronExpr = spec.cronExpr
            ruleUnit = spec.intervalUnit
            ruleInterval = spec.interval
            anchor = spec.anchorAt
        }
        val next = if (scheduleChanged || enabled == true) {
            if (ruleUnit.isNotBlank() && ruleInterval > 0) {
                runCatching { CronSchedule.IntervalUnit.valueOf(ruleUnit) }.getOrNull()?.let { u ->
                    val f = cronExpr.trim().split(Regex("\\s+"))
                    CronSchedule.ruleNextAfter(
                        CronSchedule.ScheduleRule(
                            u, ruleInterval,
                            hour = f.getOrNull(1)?.toIntOrNull() ?: 0,
                            minute = f.getOrNull(0)?.toIntOrNull() ?: 0,
                            anchorAt = anchor,
                        ),
                        now,
                    )
                }
            } else {
                CronSchedule.nextAfter(cronExpr, now)
            }
        } else {
            cur.nextRunAt
        }
        val updated = cur.copy(
            title = (title ?: cur.title).trim().ifBlank { cur.title },
            prompt = prompt ?: cur.prompt,
            cronExpr = cronExpr,
            intervalUnit = ruleUnit,
            interval = ruleInterval,
            anchorAt = anchor,
            enabled = enabled ?: cur.enabled,
            recurring = effRecurring,
            maxRuns = effMaxRuns,
            nextRunAt = next ?: 0L,
            lifecycleStatus = if (next == null && cur.lifecycleStatus == "active") {
                cur.lifecycleStatus
            } else if (next == null) "completed" else "active",
        )
        dao.upsertAutomation(updated)
        return updated to null
    }

    suspend fun delete(id: String): Boolean = dao.deleteAutomation(id) > 0

    suspend fun due(now: Long): List<CronAutomationEntity> = dao.dueAutomations(now)

    /** 触发一次后推进 runCount/nextRunAt；到限自动 completed。 */
    suspend fun markRan(id: String, now: Long = System.currentTimeMillis()) {
        val cur = dao.automation(id) ?: return
        val (count, next, status) = CronSchedule.advanceAfterRun(
            cronExpr = cur.cronExpr,
            intervalUnit = cur.intervalUnit,
            interval = cur.interval,
            anchorAt = cur.anchorAt,
            recurring = cur.recurring,
            maxRuns = cur.maxRuns,
            runCount = cur.runCount,
            now = now,
        )
        dao.upsertAutomation(
            cur.copy(runCount = count, lastRunAt = now, nextRunAt = next, lifecycleStatus = status),
        )
    }

    /** 会话忙时把触发点后移，避免每 tick 重试。 */
    suspend fun defer(id: String, delayMs: Long) {
        val cur = dao.automation(id) ?: return
        dao.upsertAutomation(cur.copy(nextRunAt = System.currentTimeMillis() + delayMs))
    }
}
