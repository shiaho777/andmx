package com.andmx.agent.zcode

import com.andmx.agent.Tool
import com.andmx.agent.ToolResult
import com.andmx.agent.ToolRisk
import com.andmx.data.CronAutomationEntity
import com.andmx.data.CronStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * ZCode cron.ts 对齐：CronCreate/List/Update/Delete。
 * automation 回合内禁写（上游 assertNotAutomationTurn）——定时任务自己不能改任务表。
 */
class CronTools(
    private val store: CronStore?,
    private val conversationId: suspend () -> Long,
    private val currentModel: suspend () -> String,
    private val isAutomationTurn: suspend () -> Boolean,
    /** 闲时窗口起始小时（本地 0-23）——上游 off-peak 窗口由套餐侧给定，AndMX 由设置决定。 */
    private val offPeakStartHour: suspend () -> Int = { 0 },
) {
    private fun noStore() = ToolResult(
        "automation_unavailable: this session cannot manage scheduled automations",
        isError = true,
    )

    private suspend fun denyInAutomation(name: String): ToolResult? =
        if (isAutomationTurn()) {
            ToolResult("$name is not allowed while running a scheduled automation.", isError = true)
        } else null

    private fun fmt(ts: Long): String =
        if (ts <= 0L) "—" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

    private fun describe(a: CronAutomationEntity): String = buildString {
        append("${a.id}: ${a.title}")
        append(" | schedule: ")
        if (a.intervalUnit.isNotBlank() && a.interval > 0) {
            append("every ${a.interval} ${a.intervalUnit.lowercase()}(s) [cron ${a.cronExpr}]")
        } else {
            append(a.cronExpr)
        }
        append(if (a.recurring) " | recurring" else " | once")
        if (a.maxRuns > 0) append(" (max ${a.maxRuns})")
        append(" | next: ${fmt(a.nextRunAt)} | runs: ${a.runCount}")
        if (!a.enabled) append(" | disabled")
        if (a.lifecycleStatus != "active") append(" | ${a.lifecycleStatus}")
    }

    inner class Create : Tool {
        override val name = "CronCreate"
        override val description =
            "Create a persistent scheduled automation in the current workspace. It uses the host's real current clock for relative delayMinutes schedules, or a standard 5-field cron expression in the user's local timezone for absolute/recurring schedules, and survives app restarts. The prompt must describe the final scheduled work directly and must never ask the run to create, schedule, or configure another automation or call CronCreate.\n\n" +
                "- Use this only when the user explicitly asks to schedule future automatic work.\n" +
                "- Interpret cron in the user's local timezone using fields: minute hour day-of-month month day-of-week. Do not convert to UTC.\n" +
                "- For any schedule expressed as a delay from now — 'in 3 minutes' sets delayMinutes=3, 'in 2 hours' sets delayMinutes=120, 'later' — set delayMinutes to the total whole minutes, omit cron, set recurring=false, and omit maxRuns. Never infer the current time or convert a relative delay into a cron yourself.\n" +
                "- For every N minutes/hours/days/weeks/months/years, always set intervalUnit (minute|hourly|daily|weekly|monthly|yearly) and interval together (integer 1..200), plus a legal 5-field cron carrying the time/day slot; e.g. every 40 days at 09:00 -> intervalUnit='daily', interval=40, cron='0 9 * * *'.\n" +
                "- Pin minute/hour/day/month in cron only for an absolute wall-clock date the user names outright; set recurring=false (default limit 1). A relative one-shot must use delayMinutes — a self-computed one-shot time that has just passed silently rolls a full year forward.\n" +
                "- For exactly N runs, set recurring=false and maxRuns=N. recurring=true is indefinite and must not combine with maxRuns.\n" +
                "- Always set title and preserve the user's natural-language schedule phrase verbatim in it.\n" +
                "- Write prompt as a complete instruction that can run later without relying on unstated conversation context. Never ask the scheduled run to call CronCreate."
        override val risk = ToolRisk.WRITE
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") { put("type", "string") }
                putJsonObject("prompt") { put("type", "string") }
                putJsonObject("cron") { put("type", "string") }
                putJsonObject("delayMinutes") { put("type", "integer") }
                putJsonObject("recurring") { put("type", "boolean") }
                putJsonObject("maxRuns") { put("type", "integer") }
                putJsonObject("intervalUnit") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("minute"); add("hourly"); add("daily"); add("weekly"); add("monthly"); add("yearly")
                    }
                }
                putJsonObject("interval") { put("type", "integer") }
            }
            putJsonArray("required") { add("title"); add("prompt") }
        }

        override suspend fun execute(args: JsonObject): ToolResult {
            denyInAutomation(name)?.let { return it }
            val s = store ?: return noStore()
            val (a, err) = s.create(
                conversationId = conversationId(),
                title = args["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                prompt = args["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                cron = args["cron"]?.jsonPrimitive?.contentOrNull,
                delayMinutes = args["delayMinutes"]?.jsonPrimitive?.intOrNull,
                recurring = args["recurring"]?.jsonPrimitive?.booleanOrNull ?: true,
                maxRuns = args["maxRuns"]?.jsonPrimitive?.intOrNull,
                intervalUnit = args["intervalUnit"]?.jsonPrimitive?.contentOrNull,
                interval = args["interval"]?.jsonPrimitive?.intOrNull,
                model = currentModel(),
            )
            if (err != null || a == null) {
                return ToolResult("CronCreate failed: ${err ?: "unknown"}", isError = true)
            }
            return ToolResult("Created automation ${a.id}.\n${describe(a)}")
        }
    }

    inner class ListAll : Tool {
        override val name = "CronList"
        override val description = "List scheduled automations in the current workspace."
        override val risk = ToolRisk.READ
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        }

        override suspend fun execute(args: JsonObject): ToolResult {
            val s = store ?: return noStore()
            val all = s.list()
            if (all.isEmpty()) return ToolResult("<automations count=\"0\">\nNo scheduled automations.\n</automations>")
            return ToolResult(
                "<automations count=\"${all.size}\">\n" +
                    all.joinToString("\n") { describe(it) } +
                    "\n</automations>",
            )
        }
    }

    inner class Update : Tool {
        override val name = "CronUpdate"
        override val description =
            "Update selected definition fields of an existing scheduled automation while preserving its id and run history.\n\n" +
                "- Use CronList first when the automation id is not already known. Never guess an automation id.\n" +
                "- Always pass title on every CronUpdate; rewrite it so the natural-language schedule phrase stays consistent with the schedule.\n" +
                "- Apart from title, only pass fields the user asked to change; omitted fields keep existing values.\n" +
                "- For every-N-unit changes pass intervalUnit and interval together, plus a compatible cron slot. Setting recurring=true clears any finite maxRuns automatically.\n" +
                "- Cannot change workspace, session binding, model, run count, or history. Do not simulate an update by deleting and recreating.\n" +
                "- After a successful update, reply with only a brief confirmation."
        override val risk = ToolRisk.WRITE
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("id") { put("type", "string") }
                putJsonObject("title") { put("type", "string") }
                putJsonObject("prompt") { put("type", "string") }
                putJsonObject("cron") { put("type", "string") }
                putJsonObject("recurring") { put("type", "boolean") }
                putJsonObject("maxRuns") { put("type", "integer") }
                putJsonObject("intervalUnit") { put("type", "string") }
                putJsonObject("interval") { put("type", "integer") }
                putJsonObject("enabled") { put("type", "boolean") }
            }
            putJsonArray("required") { add("id"); add("title") }
        }

        override suspend fun execute(args: JsonObject): ToolResult {
            denyInAutomation(name)?.let { return it }
            val s = store ?: return noStore()
            val id = args["id"]?.jsonPrimitive?.contentOrNull
                ?: return ToolResult("id is required", isError = true)
            val (a, err) = s.update(
                id = id,
                title = args["title"]?.jsonPrimitive?.contentOrNull,
                prompt = args["prompt"]?.jsonPrimitive?.contentOrNull,
                cron = args["cron"]?.jsonPrimitive?.contentOrNull,
                recurring = args["recurring"]?.jsonPrimitive?.booleanOrNull,
                maxRuns = args["maxRuns"]?.jsonPrimitive?.intOrNull,
                intervalUnit = args["intervalUnit"]?.jsonPrimitive?.contentOrNull,
                interval = args["interval"]?.jsonPrimitive?.intOrNull,
                enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull,
            )
            if (err != null || a == null) {
                return ToolResult(err ?: "Automation $id was not found in the current workspace.", isError = err == null)
            }
            return ToolResult("Updated automation ${a.id}.\n${describe(a)}")
        }
    }

    inner class Delete : Tool {
        override val name = "CronDelete"
        override val description = "Delete a scheduled automation from the current workspace by automation id."
        override val risk = ToolRisk.EXECUTE
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("id") { put("type", "string") }
            }
            putJsonArray("required") { add("id") }
        }

        override suspend fun execute(args: JsonObject): ToolResult {
            denyInAutomation(name)?.let { return it }
            val s = store ?: return noStore()
            val id = args["id"]?.jsonPrimitive?.contentOrNull
                ?: return ToolResult("id is required", isError = true)
            val deleted = s.delete(id)
            return ToolResult(
                if (deleted) "Deleted automation $id."
                else "Automation $id was not found in the current workspace.",
            )
        }
    }

    /** 上游 OffPeakCreate：把任务排进下一个闲时窗口（local offPeakStartHour:00）。 */
    inner class OffPeakCreate : Tool {
        override val name = "OffPeakCreate"
        override val description =
            "Queue an unattended task to run at the next off-peak window. The prompt continues THIS conversation later with full history, so it may reference established context; state the deliverable explicitly and never ask the run to schedule another task."
        override val risk = ToolRisk.WRITE
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") { put("type", "string") }
                putJsonObject("prompt") { put("type", "string") }
                putJsonObject("model") {
                    put("type", "string")
                    put("description", "Optional model id for the deferred run")
                }
            }
            putJsonArray("required") { add("title"); add("prompt") }
        }

        override suspend fun execute(args: JsonObject): ToolResult {
            denyInAutomation(name)?.let { return it }
            val s = store ?: return noStore()
            val title = args["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (title.isEmpty() || prompt.isEmpty()) {
                return ToolResult("title and prompt are required", isError = true)
            }
            val hour = offPeakStartHour().coerceIn(0, 23)
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, hour)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
            }
            val entity = s.createDeferred(
                conversationId = conversationId(),
                title = title,
                prompt = prompt,
                model = args["model"]?.jsonPrimitive?.contentOrNull ?: currentModel(),
                runAtMs = cal.timeInMillis,
            )
            return ToolResult(
                "{\"status\":\"queued\",\"id\":\"${entity.id}\",\"runAt\":${entity.nextRunAt},\"title\":\"${title.replace("\"", "'")}\"}",
            )
        }
    }

    /** 上游 OffPeakList：列出闲时任务与状态（queued/paused/completed/failed）。 */
    inner class OffPeakList : Tool {
        override val name = "OffPeakList"
        override val description = "List queued idle-time (off-peak) tasks in the current workspace."
        override val risk = ToolRisk.READ
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        }

        override suspend fun execute(args: JsonObject): ToolResult {
            val s = store ?: return noStore()
            val tasks = s.list().filter { it.title.startsWith("[闲时]") }
            if (tasks.isEmpty()) return ToolResult("{\"tasks\":[]}")
            val items = tasks.joinToString(",") { a ->
                val status = when {
                    a.lifecycleStatus == "completed" -> "completed"
                    a.lifecycleStatus == "failed" -> "failed"
                    !a.enabled -> "paused"
                    else -> "queued"
                }
                "{\"id\":\"${a.id}\",\"status\":\"$status\",\"title\":\"${a.title.removePrefix("[闲时] ").replace("\"", "'")}\",\"createdAt\":${a.anchorAt},\"runAt\":${a.nextRunAt}}"
            }
            return ToolResult("{\"tasks\":[$items]}")
        }
    }

    fun all(): List<Tool> = listOf(Create(), ListAll(), Update(), Delete(), OffPeakCreate(), OffPeakList())
}
