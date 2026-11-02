package com.andmx.agent.zcode

import com.andmx.agent.Tool
import com.andmx.agent.ToolResult
import com.andmx.agent.ToolRisk
import com.andmx.data.CronAutomationEntity
import com.andmx.data.CronStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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

    inner class Create : Tool {
        override val name = "CronCreate"
        override val shouldDefer = true
        override val description = CRON_CREATE_DESCRIPTION
        override val risk = ToolRisk.WRITE
        override val readOnly = false
        override val destructive = false
        override val concurrentSafe = false
        override val needsApproval = true
        override val timeoutMs = CRON_TIMEOUT_MS
        override val maxOutputBytes = CRON_MODEL_BYTES
        override val sideEffectScope = "workspace"
        override val riskLevel = "medium"
        override val permissionName = "automation.create"
        override val parameters: JsonObject = CRON_CREATE_PARAMETERS

        override suspend fun execute(args: JsonObject): ToolResult {
            denyInAutomation(name)?.let { return it }
            cronIssues(name, args, CRON_CREATE_FIELDS, listOf("prompt", "title"))?.let { return it }
            val title = args.trimmedString("title").orEmpty()
            val prompt = args.trimmedString("prompt").orEmpty()
            val delay = args.optionalInt("delayMinutes")
            val cron = args.trimmedString("cron")
            val recurring = args.optionalBoolean("recurring")
            val maxRuns = args.optionalInt("maxRuns")
            val intervalUnit = args.trimmedString("intervalUnit")
            val interval = args.optionalInt("interval")
            val constraintIssues = mutableListOf<ModelValidationIssue>()
            constraintIssues += trimmedBlankIssues(args, listOf("cron", "prompt", "title"))
            numberBound(delay, "delayMinutes", exclusiveMinZero = true, minInclusive = null, maxInclusive = 525_600)?.let {
                constraintIssues += it
            }
            numberBound(maxRuns, "maxRuns", exclusiveMinZero = true, minInclusive = null, maxInclusive = null)?.let {
                constraintIssues += it
            }
            numberBound(interval, "interval", exclusiveMinZero = false, minInclusive = 1, maxInclusive = 200)?.let {
                constraintIssues += it
            }
            val hasDelay = delay != null
            if (!hasDelay && cron == null) {
                constraintIssues += ModelValidationIssue.Custom(
                    listOf("cron"),
                    "cron is required when delayMinutes is not set",
                )
            }
            if (hasDelay && cron != null) {
                constraintIssues += ModelValidationIssue.Custom(
                    listOf("cron"),
                    "a relative delay must omit cron; the host computes the schedule from its real clock",
                )
            }
            if (hasDelay && recurring == true) {
                constraintIssues += ModelValidationIssue.Custom(
                    listOf("recurring"),
                    "a relative delay creates a one-shot automation and cannot use recurring=true",
                )
            }
            if (hasDelay && maxRuns != null) {
                constraintIssues += ModelValidationIssue.Custom(
                    listOf("maxRuns"),
                    "a relative delay runs once and cannot use maxRuns",
                )
            }
            if (intervalUnit != null && hasDelay) {
                constraintIssues += ModelValidationIssue.Custom(
                    listOf("intervalUnit"),
                    "intervalUnit is a recurring carrier and cannot combine with a relative delayMinutes",
                )
            }
            if ((intervalUnit == null) != (interval == null)) {
                constraintIssues += ModelValidationIssue.Custom(
                    listOf("interval"),
                    "intervalUnit and interval must be set together",
                )
            }
            if (intervalUnit != null && recurring == false) {
                constraintIssues += ModelValidationIssue.Custom(
                    listOf("recurring"),
                    "intervalUnit is a recurring carrier and requires recurring=true",
                )
            }
            if (intervalUnit != null && maxRuns != null) {
                constraintIssues += ModelValidationIssue.Custom(
                    listOf("maxRuns"),
                    "intervalUnit is a recurring carrier and cannot combine with maxRuns",
                )
            }
            if (constraintIssues.isNotEmpty()) {
                return ToolResult(validationIssueContent(constraintIssues), isError = true)
            }
            val s = store ?: return ToolResult(automationPortMissing(name), isError = true)
            val (created, err) = s.create(
                conversationId = conversationId(),
                title = title,
                prompt = prompt,
                cron = cron,
                delayMinutes = delay,
                recurring = if (hasDelay) false else recurring ?: true,
                maxRuns = maxRuns,
                intervalUnit = intervalUnit,
                interval = interval,
                model = currentModel(),
            )
            if (err != null || created == null) {
                return ToolResult(err ?: "CronCreate failed: unknown", isError = true)
            }
            return ToolResult(cronCreateModelContent(created))
        }
    }

    inner class ListAll : Tool {
        override val name = "CronList"
        override val shouldDefer = true
        override val description = CRON_LIST_DESCRIPTION
        override val risk = ToolRisk.READ
        override val readOnly = true
        override val destructive = false
        override val concurrentSafe = true
        override val needsApproval = false
        override val timeoutMs = CRON_TIMEOUT_MS
        override val maxOutputBytes = CRON_MODEL_BYTES
        override val sideEffectScope = "none"
        override val riskLevel = "low"
        override val permissionName = "automation.read"
        override val parameters: JsonObject = CRON_LIST_PARAMETERS

        override suspend fun execute(args: JsonObject): ToolResult {
            cronIssues(name, args, emptyList(), emptyList())?.let { return it }
            val s = store ?: return ToolResult(automationPortMissing(name), isError = true)
            return ToolResult(cronListModelContent(s.list()))
        }
    }

    inner class Update : Tool {
        override val name = "CronUpdate"
        override val shouldDefer = true
        override val description = CRON_UPDATE_DESCRIPTION
        override val risk = ToolRisk.WRITE
        override val readOnly = false
        override val destructive = false
        override val concurrentSafe = false
        override val needsApproval = true
        override val timeoutMs = CRON_TIMEOUT_MS
        override val maxOutputBytes = CRON_MODEL_BYTES
        override val sideEffectScope = "workspace"
        override val riskLevel = "medium"
        override val permissionName = "automation.update"
        override val parameters: JsonObject = CRON_UPDATE_PARAMETERS

        override suspend fun execute(args: JsonObject): ToolResult {
            denyInAutomation(name)?.let { return it }
            cronIssues(name, args, CRON_UPDATE_FIELDS, listOf("id", "title"))?.let { return it }
            val id = args.trimmedString("id").orEmpty()
            val title = args.trimmedString("title").orEmpty()
            val recurring = args.optionalBoolean("recurring")
            val maxRunsPresent = args.containsKey("maxRuns")
            val maxRunsNull = maxRunsPresent && args["maxRuns"] is JsonNull
            val maxRuns = if (maxRunsPresent && !maxRunsNull) args.optionalInt("maxRuns") else null
            val intervalUnit = args.trimmedString("intervalUnit")
            val interval = args.optionalInt("interval")
            val constraintIssues = mutableListOf<ModelValidationIssue>()
            constraintIssues += trimmedBlankIssues(args, listOf("id", "cron", "prompt", "title"))
            numberBound(maxRuns, "maxRuns", exclusiveMinZero = true, minInclusive = null, maxInclusive = null)?.let {
                constraintIssues += it
            }
            numberBound(interval, "interval", exclusiveMinZero = false, minInclusive = 1, maxInclusive = 200)?.let {
                constraintIssues += it
            }
            if (maxRunsNull && recurring != true) {
                constraintIssues += ModelValidationIssue.Custom(
                    listOf("maxRuns"),
                    "Clearing maxRuns requires recurring=true in the same update",
                )
            }
            if (recurring == true && maxRuns != null) {
                constraintIssues += ModelValidationIssue.Custom(
                    listOf("maxRuns"),
                    "recurring=true cannot be combined with a numeric maxRuns",
                )
            }
            if ((intervalUnit == null) != (interval == null)) {
                constraintIssues += ModelValidationIssue.Custom(
                    listOf("interval"),
                    "intervalUnit and interval must be set together",
                )
            }
            if (intervalUnit != null && recurring == false) {
                constraintIssues += ModelValidationIssue.Custom(
                    listOf("recurring"),
                    "intervalUnit is a recurring carrier and cannot combine with recurring=false",
                )
            }
            if (intervalUnit != null && maxRuns != null && !(maxRunsNull && recurring == true)) {
                constraintIssues += ModelValidationIssue.Custom(
                    listOf("maxRuns"),
                    "intervalUnit is a recurring carrier and only allows maxRuns=null with recurring=true",
                )
            }
            if (constraintIssues.isNotEmpty()) {
                return ToolResult(validationIssueContent(constraintIssues), isError = true)
            }
            val s = store ?: return ToolResult(automationPortMissing(name), isError = true)
            val (updated, err) = s.update(
                id = id,
                title = title,
                prompt = args.trimmedString("prompt"),
                cron = args.trimmedString("cron"),
                recurring = recurring,
                maxRuns = if (maxRunsNull) 0 else maxRuns,
                intervalUnit = intervalUnit,
                interval = interval,
                enabled = null,
            )
            if (err != null || updated == null) {
                return ToolResult(err ?: "Automation $id was not found in the current workspace.", isError = true)
            }
            return ToolResult(cronUpdateModelContent(updated))
        }
    }

    inner class Delete : Tool {
        override val name = "CronDelete"
        override val shouldDefer = true
        override val description = CRON_DELETE_DESCRIPTION
        override val risk = ToolRisk.EXECUTE
        override val readOnly = false
        override val destructive = true
        override val concurrentSafe = false
        override val needsApproval = true
        override val timeoutMs = CRON_TIMEOUT_MS
        override val maxOutputBytes = CRON_MODEL_BYTES
        override val sideEffectScope = "workspace"
        override val riskLevel = "medium"
        override val permissionName = "automation.delete"
        override val parameters: JsonObject = CRON_DELETE_PARAMETERS

        override suspend fun execute(args: JsonObject): ToolResult {
            denyInAutomation(name)?.let { return it }
            cronIssues(name, args, listOf("id"), listOf("id"))?.let { return it }
            val id = args.trimmedString("id").orEmpty()
            val blankId = trimmedBlankIssues(args, listOf("id"))
            if (blankId.isNotEmpty()) return ToolResult(validationIssueContent(blankId), isError = true)
            val s = store ?: return ToolResult(automationPortMissing(name), isError = true)
            return ToolResult(cronDeleteModelContent(id, s.delete(id)))
        }
    }

    /** 上游 OffPeakCreate：把任务排进下一个闲时窗口（local offPeakStartHour:00）。 */
    inner class OffPeakCreate : Tool {
        override val name = "OffPeakCreate"
        override val shouldDefer = true
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
        override val shouldDefer = true
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

    private fun cronIssues(tool: String, args: JsonObject, allowed: List<String>, required: List<String>): ToolResult? {
        val issues = strictIssues(args, allowed, required) { key, value ->
            when (key) {
                "recurring" -> if (value.cronBoolean() == null) "boolean" else null
                "delayMinutes", "maxRuns", "interval" -> if (value.cronInt() == null) "integer" else null
                else -> if (value.cronString() == null) "string" else null
            }
        }
        return if (issues.isEmpty()) null else ToolResult(toolUseError(tool, issues), isError = true)
    }
}

private val CRON_CREATE_FIELDS = listOf(
    "cron",
    "delayMinutes",
    "prompt",
    "title",
    "recurring",
    "maxRuns",
    "intervalUnit",
    "interval",
)

private val CRON_UPDATE_FIELDS = listOf(
    "id",
    "cron",
    "prompt",
    "title",
    "recurring",
    "maxRuns",
    "intervalUnit",
    "interval",
)

private fun JsonObject.trimmedString(key: String): String? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val text = value.cronString() ?: return null
    return text.trim().ifEmpty { null }
}

private fun JsonObject.optionalInt(key: String): Int? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    return value.cronInt()
}

private fun JsonObject.optionalBoolean(key: String): Boolean? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    return value.cronBoolean()
}

private fun JsonElement.cronString(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.content
}

private fun JsonElement.cronBoolean(): Boolean? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return when (primitive.content) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

private fun trimmedBlankIssues(args: JsonObject, keys: List<String>): List<ModelValidationIssue> {
    val issues = mutableListOf<ModelValidationIssue>()
    for (key in keys) {
        val raw = args[key] ?: continue
        if (raw is JsonNull) continue
        val text = raw.cronString() ?: continue
        if (text.trim().isEmpty()) {
            issues += ModelValidationIssue.TooSmall("string", 1, true, listOf(key))
        }
    }
    return issues
}

private fun numberBound(
    value: Int?,
    key: String,
    exclusiveMinZero: Boolean,
    minInclusive: Int?,
    maxInclusive: Int?,
): ModelValidationIssue? {
    if (value == null) return null
    if (exclusiveMinZero && value <= 0) {
        return ModelValidationIssue.TooSmall("number", 0, false, listOf(key))
    }
    if (minInclusive != null && value < minInclusive) {
        return ModelValidationIssue.TooSmall("number", minInclusive, true, listOf(key))
    }
    if (maxInclusive != null && value > maxInclusive) {
        return ModelValidationIssue.TooBig("number", maxInclusive, true, listOf(key))
    }
    return null
}

private fun JsonElement.cronInt(): Int? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    val asLong = primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull() ?: return null
    if (asLong > Int.MAX_VALUE || asLong < Int.MIN_VALUE) return null
    return asLong.toInt()
}
