package com.andmx.agent.automations

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Automations system — mirrors Codex's automations directory.
 *
 * Provides user-defined automated actions that trigger on specific conditions:
 * - Schedule-based (interval, cron-like)
 * - Event-based (file change, git commit, build failure, etc.)
 * - State-based (context pressure, idle time, etc.)
 *
 * Automations are stored as JSON files and can be created/edited/deleted
 * at runtime. Each automation has a trigger condition and an action prompt
 * that gets sent to the agent when triggered.
 */
class AutomationSystem(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = false; explicitNulls = false },
) {
    companion object {
        private const val TAG = "AutomationSystem"
        private const val AUTOMATIONS_DIR = "automations"
        private const val CHECK_INTERVAL_MS = 60_000L  // 1 minute
    }

    private val automationsDir = File(context.filesDir, AUTOMATIONS_DIR).apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Serializable
    data class Automation(
        val id: String,
        val name: String,
        val enabled: Boolean = true,
        val trigger: Trigger,
        val actionPrompt: String,
        val lastTriggered: Long = 0,
        val triggerCount: Int = 0,
        val createdAt: Long = System.currentTimeMillis(),
    )

    @Serializable
    data class Trigger(
        val type: TriggerType,
        /** For INTERVAL: milliseconds between triggers. */
        val intervalMs: Long = 0,
        /** For SCHEDULE: cron-like expression (simplified, e.g., "0 9 * * 1-5" for 9am weekdays). */
        val schedule: String = "",
        /** For EVENT: the event name to match. */
        val eventName: String = "",
        /** For CONTEXT_PRESSURE: threshold fraction (0-1). */
        val contextPressureThreshold: Float = 0.8f,
        /** For IDLE: idle seconds threshold. */
        val idleThresholdSeconds: Long = 600,
        /** Maximum triggers per day (0 = unlimited). */
        val maxPerDay: Int = 0,
    )

    enum class TriggerType {
        INTERVAL,       // periodic timer
        SCHEDULE,       // cron-like schedule
        EVENT,          // event-driven (file change, git commit, etc.)
        CONTEXT_PRESSURE,  // when context window gets full
        IDLE,           // when session is idle
    }

    private val _automations = MutableStateFlow<List<Automation>>(emptyList())
    val automations: StateFlow<List<Automation>> = _automations.asStateFlow()

    /** Callback invoked when an automation triggers. */
    var onTrigger: (suspend (Automation) -> Unit)? = null

    init {
        loadAll()
        startMonitorLoop()
    }

    /** Create a new automation. */
    fun create(automation: Automation): Boolean {
        val automations = _automations.value.toMutableList()
        if (automations.any { it.id == automation.id }) return false
        automations.add(automation)
        _automations.value = automations
        return save(automation)
    }

    /** Update an existing automation. */
    fun update(automation: Automation): Boolean {
        val automations = _automations.value.toMutableList()
        val idx = automations.indexOfFirst { it.id == automation.id }
        if (idx < 0) return false
        automations[idx] = automation
        _automations.value = automations
        return save(automation)
    }

    /** Delete an automation. */
    fun delete(id: String): Boolean {
        val automations = _automations.value.toMutableList()
        val removed = automations.removeAll { it.id == id }
        if (removed) {
            _automations.value = automations
            File(automationsDir, "$id.json").delete()
        }
        return removed
    }

    /** Enable/disable an automation. */
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val automations = _automations.value.toMutableList()
        val idx = automations.indexOfFirst { it.id == id }
        if (idx < 0) return false
        automations[idx] = automations[idx].copy(enabled = enabled)
        _automations.value = automations
        return save(automations[idx])
    }

    /** Trigger an event-based automation manually. */
    suspend fun triggerEvent(eventName: String, eventData: Map<String, String> = emptyMap()) {
        val matching = _automations.value.filter {
            it.enabled && it.trigger.type == TriggerType.EVENT && it.trigger.eventName == eventName
        }
        for (auto in matching) {
            if (shouldTrigger(auto)) {
                executeTrigger(auto)
            }
        }
    }

    /** Check context pressure and trigger if threshold exceeded. */
    suspend fun checkContextPressure(currentFraction: Float) {
        val matching = _automations.value.filter {
            it.enabled && it.trigger.type == TriggerType.CONTEXT_PRESSURE &&
                currentFraction >= it.trigger.contextPressureThreshold
        }
        for (auto in matching) {
            if (shouldTrigger(auto)) {
                executeTrigger(auto)
            }
        }
    }

    private fun shouldTrigger(auto: Automation): Boolean {
        // Check max per day
        if (auto.trigger.maxPerDay > 0) {
            val todayStart = getTodayStartMs()
            // Simple heuristic: if last triggered today and count exceeds max
            if (auto.lastTriggered > todayStart && auto.triggerCount >= auto.trigger.maxPerDay) {
                return false
            }
        }
        return true
    }

    private suspend fun executeTrigger(auto: Automation) {
        val updated = auto.copy(
            lastTriggered = System.currentTimeMillis(),
            triggerCount = auto.triggerCount + 1,
        )
        update(updated)
        Log.i(TAG, "Automation triggered: ${auto.name}")
        onTrigger?.invoke(updated)
    }

    private fun startMonitorLoop() {
        scope.launch {
            while (true) {
                delay(CHECK_INTERVAL_MS)
                checkIntervalAndScheduleTriggers()
            }
        }
    }

    private suspend fun checkIntervalAndScheduleTriggers() {
        val now = System.currentTimeMillis()
        for (auto in _automations.value) {
            if (!auto.enabled) continue
            when (auto.trigger.type) {
                TriggerType.INTERVAL -> {
                    if (now - auto.lastTriggered >= auto.trigger.intervalMs) {
                        if (shouldTrigger(auto)) executeTrigger(auto)
                    }
                }
                TriggerType.SCHEDULE -> {
                    if (matchesSchedule(auto.trigger.schedule, now) &&
                        now - auto.lastTriggered >= 60_000) {  // at most once per minute
                        if (shouldTrigger(auto)) executeTrigger(auto)
                    }
                }
                TriggerType.IDLE -> {
                    // Idle check is done externally via checkContextPressure equivalent
                }
                else -> {}  // EVENT and CONTEXT_PRESSURE are checked externally
            }
        }
    }

    /** Very simplified cron matcher: "minute hour day month weekday" */
    private fun matchesSchedule(schedule: String, timeMs: Long): Boolean {
        val parts = schedule.trim().split(Regex("\\s+"))
        if (parts.size != 5) return false
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = timeMs }
        val minute = cal.get(java.util.Calendar.MINUTE)
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val month = cal.get(java.util.Calendar.MONTH) + 1
        val weekday = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1  // 0=Sunday

        return matchesCronPart(parts[0], minute) &&
            matchesCronPart(parts[1], hour) &&
            matchesCronPart(parts[2], day) &&
            matchesCronPart(parts[3], month) &&
            matchesCronPart(parts[4], weekday)
    }

    private fun matchesCronPart(pattern: String, value: Int): Boolean {
        if (pattern == "*") return true
        // Handle ranges (e.g., "1-5")
        if (pattern.contains("-")) {
            val range = pattern.split("-")
            val start = range[0].toIntOrNull() ?: return false
            val end = range[1].toIntOrNull() ?: return false
            return value in start..end
        }
        // Handle lists (e.g., "1,3,5")
        if (pattern.contains(",")) {
            return pattern.split(",").any { it.toIntOrNull() == value }
        }
        // Handle step (e.g., "*/15")
        if (pattern.startsWith("*/")) {
            val step = pattern.removePrefix("*/").toIntOrNull() ?: return false
            return value % step == 0
        }
        return pattern.toIntOrNull() == value
    }

    private fun getTodayStartMs(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun save(automation: Automation): Boolean {
        return runCatching {
            val file = File(automationsDir, "${automation.id}.json")
            file.writeText(json.encodeToString(Automation.serializer(), automation))
            true
        }.getOrElse {
            Log.e(TAG, "Failed to save automation: ${it.message}")
            false
        }
    }

    private fun loadAll() {
        val loaded = automationsDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                runCatching {
                    json.decodeFromString(Automation.serializer(), f.readText())
                }.getOrElse {
                    Log.w(TAG, "Failed to load automation ${f.name}: ${it.message}")
                    null
                }
            }
            ?: emptyList()
        _automations.value = loaded
    }

    fun shutdown() {
        scope.cancel()
    }
}
