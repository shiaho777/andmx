package com.andmx.agent.zcode

import com.andmx.agent.Tool
import com.andmx.data.CronAutomationEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal const val AGENT_MODEL_BYTES = 120_000
internal const val TASK_OUTPUT_MODEL_BYTES = 400_000
internal const val TASK_STOP_MODEL_BYTES = 100_000
internal const val TASK_STOP_TIMEOUT_MS = 10_000L
internal const val SEND_MESSAGE_MODEL_BYTES = 4_096
internal const val SEND_MESSAGE_TIMEOUT_MS = 10_000L
internal const val SEND_MESSAGE_MAX_CONTENT_CHARS = 20_000
internal const val CRON_MODEL_BYTES = 32_000
internal const val CRON_TIMEOUT_MS = 30_000L
internal const val TASK_OUTPUT_DEFAULT_LENGTH = 32_000
internal const val TASK_OUTPUT_MAX_LENGTH = 160_000

internal const val TASK_STOP_LOOKUP_FAILURE_GUIDANCE =
    "This lookup failure does not mean the task has completed or stopped; use the full task_id returned when the task started to stop it."

internal fun agentProviderDescription(
    dynamicWorkflowEnabled: Boolean = DYNAMIC_WORKFLOW_TOOLS_ENABLED,
): String {
    val lines = mutableListOf(
        "Launch a new agent to handle complex, multi-step tasks. Each agent type has specific capabilities and tools available to it.",
        "",
        "Available agent types are listed in <system-reminder> messages in the conversation.",
        "",
        "When using the Agent tool, specify a subagent_type parameter to select which agent type to use. If omitted, the general-purpose agent is used.",
        "",
        "## When to use",
        "",
        "Reach for this when the task matches an available agent type, when you have independent work to run in parallel, or when answering would mean reading across several files — delegate it and you keep the conclusion, not the file dumps. For a single-fact lookup where you already know the file, symbol, or value, search directly. Once you've delegated a search, don't also run it yourself — wait for the result.",
        "",
        "- The agent's final message is returned to you as the tool result; it is not shown to the user — relay what matters.",
        "- Use SendMessage with the agent's ID to continue a previously spawned agent with its context intact; a new Agent call starts fresh.",
        "- Each agent type's model, reasoning effort, and tools come from its definition (`.zcode/agents/*.md` frontmatter).",
        "- `run_in_background: true` runs the agent asynchronously; you'll be notified when it completes.",
    )
    if (dynamicWorkflowEnabled) {
        lines += "- If the user explicitly asks for a workflow (\"use a workflow\", \"使用 workflow\", \"用工作流\", or any phrasing naming workflow/工作流 as the means), the CreateWorkflow tool is mandatory: do not use this tool instead, however small the task."
    }
    return lines.joinToString("\n")
}

internal val AGENT_PROVIDER_DESCRIPTION = agentProviderDescription()

internal val TASK_PROVIDER_DESCRIPTION = listOf(
    "Claude Code-compatible alias for the Agent tool. Use this when plugin instructions ask for the Task tool.",
    "",
    AGENT_PROVIDER_DESCRIPTION,
).joinToString("\n")

internal val TASK_OUTPUT_PROVIDER_DESCRIPTION =
    "DEPRECATED: Background tasks return their output file path in the tool result, and you receive a <task-notification> with the same path when the task completes.\n" +
        "- For bash tasks: prefer using the Read tool on that output file path — it contains stdout/stderr.\n" +
        "- For local_agent tasks: use the Agent tool result directly. Do NOT Read the .output file — it is a symlink to the full subagent conversation transcript (JSONL) and will overflow your context window.\n" +
        "- For remote_agent tasks: prefer using the Read tool on the output file path — it contains the streamed remote session output (same as bash).\n" +
        "\n" +
        "- Retrieves output from a running or completed task (background shell, agent, or remote session)\n" +
        "- Takes a task_id parameter identifying the task\n" +
        "- Returns the task output along with status information\n" +
        "- Use block=true (default) to wait for task completion\n" +
        "- Use block=false for non-blocking check of current status\n" +
        "- Task IDs can be found using the /tasks command\n" +
        "- Works with all task types: background shells, async agents, and remote sessions"

internal val TASK_STOP_PROVIDER_DESCRIPTION = listOf(
    "",
    "- Stops a running background task by its ID",
    "- Takes a task_id parameter identifying the task to stop",
    "- Returns a success or failure status",
    "- Use this tool when you need to terminate a long-running task",
    "",
).joinToString("\n")

internal val SEND_MESSAGE_PROVIDER_DESCRIPTION = listOf(
    "# SendMessage",
    "",
    "Send a message to another agent.",
    "",
    "```json",
    "{\"to\": \"agent_<uuid>\", \"summary\": \"assign task 1\", \"message\": \"start on task #1\"}",
    "```",
    "",
    "Your plain text output is NOT visible to other agents — to communicate, you MUST call this tool. Messages from agents are delivered automatically; you don't check an inbox. Refer to local agents by the `agentId` returned in the Agent spawn result. To resume a completed agent, use its `agentId`; it resumes in the background and you'll be notified when it finishes.",
).joinToString("\n")

private val CRON_CREATE_INSTRUCTIONS = listOf(
    "Use this only when the user explicitly asks to schedule future automatic work.",
    "Interpret cron in the user's local timezone using fields: minute hour day-of-month month day-of-week. Do not convert to UTC.",
    "For any schedule expressed as a delay from now — 'in 3 minutes' sets delayMinutes=3, '8分钟后' sets delayMinutes=8, 'in 2 hours' sets delayMinutes=120, 'later'/'稍后' — set delayMinutes to the total whole minutes, omit cron, set recurring=false, and omit maxRuns. The host anchors to its real current clock; never infer the current time or convert a relative delay into a cron or clock time yourself.",
    "Use '*/20 * * * *' for every 20 minutes, '0 * * * *' for hourly, and '0 9 * * 1-5' for weekdays at 09:00.",
    "For every N minutes/hours/days/weeks/months/years, always set intervalUnit (minute|hourly|daily|weekly|monthly|yearly) and interval together. interval must be an integer from 1 to 200, including values cron could express directly. Supply a legal 5-field compatible cron only for time-of-day/day/weekday/month slots; never put an out-of-range step in cron. Examples: every 20 minutes -> intervalUnit='minute', interval=20, cron='* * * * *'; every 31 hours at minute 49 -> intervalUnit='hourly', interval=31, cron='49 * * * *'; every 40 days at 09:00 -> intervalUnit='daily', interval=40, cron='0 9 * * *'. Omit intervalUnit/interval only for ordinary calendar cron schedules, such as weekdays at 09:00.",
    "Pin minute, hour, day-of-month, and month in cron only for an absolute wall-clock date the user names outright, such as 'tomorrow at 9am' or 'on July 30 at 20:00'; set recurring=false and omit maxRuns (the default limit is 1). A relative one-shot such as '8分钟后' or 'in 2 hours' must use delayMinutes instead, because a self-computed one-shot time that has just passed silently rolls a full year forward.",
    "For exactly N scheduled runs, set recurring=false and maxRuns=N. recurring=true is indefinite and must not be combined with maxRuns.",
    "Automations persist in the current workspace until the user deletes them. Finite automations become completed and retain their history; they are not session-only or auto-deleted.",
    "Honor exact user-provided times without adding jitter or shifting the schedule.",
    "Do not include workspace paths or identities in the input; the current session workspace is used.",
    "Always set title and preserve the user's natural-language schedule phrase verbatim in it. The title may be concise, but must not omit timing such as '每20分钟', '每天早上9点', or 'every Friday'.",
    "Write prompt as a complete instruction that can run later without relying on unstated conversation context.",
    "Write the final work directly in prompt. Never ask the scheduled run to create, schedule, or configure another automation, and never ask it to call CronCreate.",
)

private val CRON_UPDATE_INSTRUCTIONS = listOf(
    "Use CronList first when the automation id is not already known. Never guess an automation id.",
    "Always pass title on every CronUpdate. Rewrite it so it describes the task after the update and keeps the user's natural-language schedule phrase consistent with cron; for example, changing every 5 minutes to every 6 minutes must also update the title.",
    "Apart from the required synchronized title, only pass fields the user asked to change. Omitted fields preserve their existing values.",
    "Interpret cron in the user's local timezone using five fields: minute hour day-of-month month day-of-week. Do not convert to UTC.",
    "To create or change every N minutes/hours/days/weeks/months/years, pass intervalUnit and interval together for every N-unit schedule. interval must be an integer from 1 to 200 even when cron could express N. Also pass a legal compatible cron with only the time/day/weekday/month slot; for example, every 40 days at 09:00 uses intervalUnit='daily', interval=40, cron='0 9 * * *'. Omit the pair only when preserving or using an ordinary calendar cron schedule.",
    "Use numeric maxRuns only with recurring=false. Setting recurring=true clears any old finite limit automatically; never combine recurring=true with a numeric maxRuns.",
    "CronUpdate cannot change workspace, session binding, model, provider, mode, thought level, run count, history, or enabled state.",
    "Do not simulate an update by deleting and recreating the automation.",
    "After a successful update, reply with only a brief confirmation. Do not restate the automation fields in a fenced code block or simulate a text file because the UI renders the updated automation card.",
)

internal val CRON_CREATE_DESCRIPTION = providerDescription(
    "Create a persistent scheduled automation in the current workspace. It uses the host's real current clock for relative delayMinutes schedules, or a standard 5-field cron expression in the user's local timezone for absolute/recurring schedules, and survives app restarts. The prompt must describe the final scheduled work directly and must never ask the run to create, schedule, or configure another automation or call CronCreate.",
    CRON_CREATE_INSTRUCTIONS,
)

internal val CRON_LIST_DESCRIPTION = "List scheduled automations in the current workspace."

internal val CRON_UPDATE_DESCRIPTION = providerDescription(
    "Update selected definition fields of an existing scheduled automation in the current workspace while preserving its id and run history.",
    CRON_UPDATE_INSTRUCTIONS,
)

internal val CRON_DELETE_DESCRIPTION =
    "Delete a scheduled automation from the current workspace by automation id."

internal val AGENT_PARAMETERS = strictToolObject(
    required = listOf("description", "prompt"),
) {
    putJsonObject("description") {
        put("type", "string")
        put("description", "A short (3-5 word) description of the task")
    }
    putJsonObject("prompt") {
        put("type", "string")
        put("description", "The task for the agent to perform")
    }
    putJsonObject("subagent_type") {
        put("type", "string")
        put("description", "The type of specialized agent to use for this task")
    }
    putJsonObject("run_in_background") {
        put("type", "boolean")
        put("description", "Set to true to run this agent in the background. You will be notified when it completes.")
    }
}

internal val TASK_OUTPUT_PARAMETERS = strictToolObject(
    required = listOf("task_id", "block", "timeout"),
) {
    putJsonObject("task_id") {
        put("type", "string")
        put("description", "The task ID to get output from")
    }
    putJsonObject("block") {
        put("type", "boolean")
        put("default", true)
        put("description", "Whether to wait for completion")
    }
    putJsonObject("timeout") {
        put("type", "number")
        put("minimum", 0)
        put("maximum", 600_000)
        put("default", 30_000)
        put("description", "Max wait time in ms")
    }
}

internal val TASK_STOP_PARAMETERS = strictToolObject {
    putJsonObject("task_id") {
        put("type", "string")
        put("description", "The ID of the background task to stop")
    }
    putJsonObject("shell_id") {
        put("type", "string")
        put("description", "Deprecated: use task_id instead")
    }
}

internal val SEND_MESSAGE_PARAMETERS = strictToolObject(
    required = listOf("to", "summary", "message"),
) {
    putJsonObject("to") {
        put("type", "string")
        put("minLength", 1)
        put("maxLength", 200)
        put("description", "Recipient: local agent ID returned by Agent (format agent_<uuid>).")
    }
    putJsonObject("summary") {
        put("type", "string")
        put("minLength", 1)
        put("maxLength", 200)
        put("description", "A 5-10 word summary shown as a preview in the UI.")
    }
    putJsonObject("message") {
        put("type", "string")
        put("minLength", 1)
        put("maxLength", SEND_MESSAGE_MAX_CONTENT_CHARS)
        put("description", "Plain text message content")
    }
}

private const val CRON_DELAY_DESCRIPTION =
    "For any relative delay from now — 'in 3 minutes' (3), '8分钟后' (8), 'in 2 hours' (120), 'later'/'稍后' — set the exact positive delay in whole minutes and omit cron. The host calculates the future local schedule from its real current clock, so never compute an absolute time or cron yourself. For an absolute named date/time or a recurring schedule, omit it (or set null) and provide cron."

internal val CRON_CREATE_PARAMETERS = strictToolObject(
    required = listOf("prompt", "title"),
) {
    putJsonObject("cron") {
        put("type", "string")
        put("minLength", 1)
        put(
            "description",
            "Standard 5-field cron expression in the user's local timezone: minute hour day-of-month month day-of-week. Use it only for an absolute named date/time or a recurring schedule; required unless delayMinutes is set. For any relative delay such as 'in 8 minutes'/'8分钟后' or 'in 2 hours'/'2小时后', omit cron and use delayMinutes instead — never convert a relative phrase into a fixed clock time or calendar date, because a just-passed one-shot time silently rolls a full year forward. Examples: '*/20 * * * *' means every 20 minutes, '0 * * * *' means hourly, and '0 9 * * 1-5' means weekdays at 09:00. Do not convert to UTC.",
        )
    }
    put("delayMinutes", nullablePositiveInt(525_600, CRON_DELAY_DESCRIPTION))
    putJsonObject("prompt") {
        put("type", "string")
        put("minLength", 1)
        put(
            "description",
            "Complete prompt to send at every scheduled fire. Include all instructions needed when the automation runs. Describe the final work directly; do not ask it to create or schedule another automation or call CronCreate.",
        )
    }
    putJsonObject("title") {
        put("type", "string")
        put("minLength", 1)
        put(
            "description",
            "Concise automation title that preserves the user's natural-language schedule phrase verbatim. For example, for '每20分钟提醒我喝水', use '每20分钟喝水提醒', not '喝水提醒'.",
        )
    }
    putJsonObject("recurring") {
        put("type", "boolean")
        put(
            "description",
            "true (default) repeats until paused or deleted. false creates a finite automation; without maxRuns it runs once.",
        )
    }
    putJsonObject("maxRuns") {
        put("type", "integer")
        put("exclusiveMinimum", 0)
        put(
            "description",
            "Maximum successful scheduled dispatch count. Use only with recurring=false; omit for a one-shot automation (defaults to 1).",
        )
    }
    put("intervalUnit", intervalUnitProperty(
        "Custom recurring interval unit for every N minutes/hours/days/weeks/months/years. Pair with interval (1-200) for every N-unit request, even if cron can express N; submit a legal compatible cron whose time/day slots are used by the scheduleRule. Omit both for ordinary calendar cron schedules.",
    ))
    putJsonObject("interval") {
        put("type", "integer")
        put("minimum", 1)
        put("maximum", 200)
        put(
            "description",
            "Integer interval from 1 to 200 paired with intervalUnit. The host carries the real interval via scheduleRule; the compatible cron is only a legal display expression. Must be set together with intervalUnit.",
        )
    }
}

internal val CRON_UPDATE_PARAMETERS = strictToolObject(
    required = listOf("id", "title"),
) {
    putJsonObject("id") {
        put("type", "string")
        put("minLength", 1)
        put("description", "Automation id returned by CronCreate or CronList")
    }
    putJsonObject("cron") {
        put("type", "string")
        put("minLength", 1)
        put(
            "description",
            "Replacement standard 5-field cron expression in the user's local timezone. Omit to preserve the existing schedule. Do not convert to UTC.",
        )
    }
    putJsonObject("prompt") {
        put("type", "string")
        put("minLength", 1)
        put("description", "Replacement prompt for future scheduled fires. Omit to preserve the existing prompt.")
    }
    putJsonObject("title") {
        put("type", "string")
        put("minLength", 1)
        put(
            "description",
            "Required synchronized automation title describing the task after this update. Keep the user's natural-language schedule phrase consistent with cron (for example, changing every 5 minutes to every 6 minutes must also change the title), and update the title when the prompt meaning changes.",
        )
    }
    putJsonObject("recurring") {
        put("type", "boolean")
        put(
            "description",
            "Replacement recurrence mode. true repeats indefinitely and clears any old finite maxRuns limit; false is finite. Do not combine true with a numeric maxRuns.",
        )
    }
    put(
        "maxRuns",
        nullablePositiveInt(
            max = null,
            description = "Replacement maximum successful scheduled dispatch count for recurring=false. null clears the existing limit and is valid only when recurring=true is included in the same update; when recurring=true is supplied without maxRuns, the service clears the old limit automatically.",
        ),
    )
    put("intervalUnit", intervalUnitProperty(
        "Switch this automation to a long recurring interval whose step exceeds a cron field ceiling (hourly N>24, daily N>31, etc.). Pair with interval and submit a legal compatible cron (omit cron to keep the existing schedule's minute).",
    ))
    putJsonObject("interval") {
        put("type", "integer")
        put("minimum", 1)
        put("maximum", 200)
        put("description", "Positive integer interval paired with intervalUnit. Must be set together with intervalUnit.")
    }
}

internal val CRON_LIST_PARAMETERS = strictToolObject { }

internal val CRON_DELETE_PARAMETERS = strictToolObject(required = listOf("id")) {
    putJsonObject("id") {
        put("type", "string")
        put("minLength", 1)
        put("description", "Automation id returned by CronCreate or CronList")
    }
}

internal fun toolUseError(tool: String, issues: List<String>): String {
    val noun = if (issues.size > 1) "issues" else "issue"
    return "<tool_use_error>InputValidationError: $tool failed due to the following $noun:\n" +
        issues.joinToString("\n") +
        "</tool_use_error>"
}

internal fun strictIssues(
    args: JsonObject,
    allowed: List<String>,
    required: List<String>,
    expect: (key: String, value: JsonElement) -> String?,
): List<String> {
    val issues = mutableListOf<String>()
    for (name in required) {
        val value = args[name]
        if (value == null || value is JsonNull) {
            issues += "The required parameter `$name` is missing"
        }
    }
    for (key in args.keys) {
        if (key !in allowed) issues += "An unexpected parameter `$key` was provided"
    }
    for ((key, value) in args) {
        if (key !in allowed || value is JsonNull) continue
        val expected = expect(key, value) ?: continue
        issues += "The parameter `$key` type is expected as `$expected` but provided as `${jsonReceived(value)}`"
    }
    return issues
}

internal fun formatAgentCompletedModelContent(
    text: String,
    agentId: String,
    toolUses: Int,
    durationMs: Long,
    totalTokens: Int? = null,
): String {
    val child = if (text.trim().isEmpty()) "(Subagent completed but returned no output.)" else text
    val usage = buildList {
        if (totalTokens != null) add("subagent_tokens: $totalTokens")
        add("tool_uses: $toolUses")
        add("duration_ms: $durationMs")
    }
    return listOf(
        child,
        "agentId: $agentId (use SendMessage with to: '$agentId' to continue this agent)",
        "<usage>${usage.joinToString("\n")}</usage>",
    ).joinToString("\n")
}

internal fun formatAgentBackgroundModelContent(
    agentId: String,
    outputFile: String?,
    canReadOutputFile: Boolean,
): String {
    val launch = listOf(
        "Async agent launched successfully.",
        "agentId: $agentId (internal ID - do not mention to user. Use SendMessage with to: '$agentId' to continue this agent.)",
        "The agent is working in the background. You will be notified automatically when it completes.",
    )
    return if (canReadOutputFile) {
        (launch + listOf(
            "Do not duplicate this agent's work - avoid working with the same files or topics it is using. Work on non-overlapping tasks, or briefly tell the user what you launched and end your response.",
            "output_file: ${outputFile.orEmpty()}",
            "Do NOT Read or tail this file via the shell tool. If the user asks for progress, say the agent is still running; you'll get a completion notification.",
        )).joinToString("\n")
    } else {
        (launch + listOf(
            "Briefly tell the user what you launched and end your response. Do not generate any other text - agent results will arrive in a subsequent message.",
        )).joinToString("\n")
    }
}

internal sealed class ModelValidationIssue {
    abstract fun json(): String

    data class TooSmall(
        val origin: String,
        val minimum: Int,
        val inclusive: Boolean,
        val path: List<String>,
    ) : ModelValidationIssue() {
        override fun json(): String = boundObject(
            origin = origin,
            code = "too_small",
            limitKey = "minimum",
            limit = minimum,
            inclusive = inclusive,
            path = path,
            message = sizeMessage("Too small", origin, if (inclusive) ">=" else ">", minimum),
        )
    }

    data class TooBig(
        val origin: String,
        val maximum: Int,
        val inclusive: Boolean,
        val path: List<String>,
    ) : ModelValidationIssue() {
        override fun json(): String = boundObject(
            origin = origin,
            code = "too_big",
            limitKey = "maximum",
            limit = maximum,
            inclusive = inclusive,
            path = path,
            message = sizeMessage("Too big", origin, if (inclusive) "<=" else "<", maximum),
        )
    }

    data class Custom(
        val path: List<String>,
        val message: String,
    ) : ModelValidationIssue() {
        override fun json(): String = """
            {
              "code": "custom",
              "path": [
                ${path.joinToString(",\n                ") { jsonString(it) }}
              ],
              "message": ${jsonString(message)}
            }
        """.trimIndent()
    }
}

internal fun validationIssueContent(issues: List<ModelValidationIssue>): String {
    val body = issues.joinToString(separator = ",\n", prefix = "[\n", postfix = "\n]") { issue ->
        issue.json().lineSequence().joinToString("\n") { "  $it" }
    }
    return "<tool_use_error>InputValidationError: $body</tool_use_error>"
}

internal fun handlerFailureContent(message: String): String = "<tool_use_error>$message</tool_use_error>"

private fun boundObject(
    origin: String,
    code: String,
    limitKey: String,
    limit: Int,
    inclusive: Boolean,
    path: List<String>,
    message: String,
): String = """
    {
      "origin": ${jsonString(origin)},
      "code": ${jsonString(code)},
      "$limitKey": $limit,
      "inclusive": $inclusive,
      "path": [
        ${path.joinToString(",\n        ") { jsonString(it) }}
      ],
      "message": ${jsonString(message)}
    }
""".trimIndent()

private fun sizeMessage(label: String, origin: String, comparison: String, limit: Int): String {
    val unit = when (origin) {
        "string" -> "characters"
        "array" -> "items"
        else -> null
    }
    return if (unit == null) {
        "$label: expected $origin to be $comparison$limit"
    } else {
        "$label: expected $origin to have $comparison$limit $unit"
    }
}

private fun jsonString(value: String): String {
    val escaped = buildString {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
    return "\"$escaped\""
}

internal fun unknownAgentType(type: String, available: List<String>): String =
    "Agent type '$type' not found. Available agents: ${available.joinToString(", ")}"

internal fun ambiguousAgentType(type: String, matches: List<String>): String =
    "Agent type '$type' is ambiguous — matches ${matches.joinToString(", ")} — Use the exact name: ${matches.joinToString(" or ")}"

internal fun formatTaskOutputModelContent(
    retrievalStatus: String,
    taskId: String? = null,
    taskType: String? = null,
    status: String? = null,
    output: String? = null,
    exitCode: Int? = null,
    error: String? = null,
    outputFile: String? = null,
): String {
    val blocks = mutableListOf("<retrieval_status>$retrievalStatus</retrieval_status>")
    if (taskId != null && taskType != null && status != null) {
        blocks += "<task_id>$taskId</task_id>"
        blocks += "<task_type>$taskType</task_type>"
        blocks += "<status>$status</status>"
        if (exitCode != null) blocks += "<exit_code>$exitCode</exit_code>"
        val body = output?.trim().orEmpty()
        if (body.isNotEmpty()) {
            val content = if (outputFile.isNullOrBlank()) body else truncateTaskOutput(body, outputFile)
            blocks += "<output>\n${content.trimEnd()}\n</output>"
        }
        if (!error.isNullOrBlank()) blocks += "<error>$error</error>"
    }
    return blocks.joinToString("\n\n")
}

internal fun truncateTaskOutput(output: String, outputPath: String, maxLength: Int = TASK_OUTPUT_DEFAULT_LENGTH): String {
    if (output.length <= maxLength) return output
    val prefix = "[Truncated. Full output: $outputPath]\n\n"
    val tailLength = (maxLength - prefix.length).coerceAtLeast(0)
    return prefix + output.takeLast(tailLength)
}

internal fun formatTaskStopModelContent(
    taskId: String,
    taskType: String,
    command: String?,
): String {
    val message = "Successfully stopped task: $taskId (${command ?: taskType})"
    return buildJsonObject {
        put("message", message)
        put("task_id", taskId)
        put("task_type", taskType)
        if (!command.isNullOrBlank()) put("command", command)
    }.toString()
}

internal fun invalidTaskId(taskId: String): String =
    "Invalid task ID \"$taskId\". $TASK_STOP_LOOKUP_FAILURE_GUIDANCE"

internal fun formatSendMessageModelContent(
    status: String,
    messageId: String,
    agentId: String?,
    delivery: String?,
    error: String?,
    message: String?,
    taskId: String?,
): String {
    if (message != null) return message
    val who = agentId ?: taskId ?: "unknown"
    if (status == "success") {
        if (delivery != null) return "Message $messageId was $delivery for local agent $who."
        return "Message $messageId was queued for local agent $who."
    }
    return "Message $messageId failed to send to local agent $who: ${error ?: "unknown error"}."
}

internal fun queuedSendMessage(agentId: String): String =
    "Message queued for delivery to $agentId at its next tool round."

internal fun missingLocalAgent(agentId: String): String =
    "No active local_agent task found for target $agentId."

internal fun cronAutomationJson(automation: CronAutomationEntity): JsonObject = buildJsonObject {
    put("automationId", automation.id)
    put("title", automation.title)
    put("cronExpr", automation.cronExpr)
    put("prompt", automation.prompt)
    put("enabled", automation.enabled)
    put("lifecycleStatus", automation.lifecycleStatus)
    if (automation.nextRunAt > 0L) put("nextRunAt", automation.nextRunAt)
    if (automation.lastRunAt > 0L) put("lastRunAt", automation.lastRunAt)
    put("runCount", automation.runCount)
    put("recurring", automation.recurring)
    if (automation.maxRuns > 0) put("maxRuns", automation.maxRuns)
    scheduleRuleJson(automation)?.let { put("scheduleRule", it) }
}

internal fun cronCreateModelContent(automation: CronAutomationEntity): String = buildJsonObject {
    put("automation", cronAutomationJson(automation))
    put("message", "Created automation ${automation.id}.")
}.toString()

internal fun cronUpdateModelContent(automation: CronAutomationEntity): String = buildJsonObject {
    put("automation", cronAutomationJson(automation))
    put("message", "Updated automation ${automation.id}.")
}.toString()

internal fun cronListModelContent(automations: List<CronAutomationEntity>): String = buildJsonObject {
    put("automations", JsonArray(automations.map { cronAutomationJson(it) }))
}.toString()

internal fun cronDeleteModelContent(id: String, deleted: Boolean): String = buildJsonObject {
    put("deleted", deleted)
    put("id", id)
    put(
        "message",
        if (deleted) "Deleted automation $id."
        else "Automation $id was not found in the current workspace.",
    )
}.toString()

internal fun automationPortMissing(tool: String): String =
    "AutomationPort is not configured for $tool"

internal fun toolClaimsNodeRepl(tool: Tool): Boolean {
    val name = tool.name.trim()
    if (
        name.equals("js", ignoreCase = true) ||
        name.equals("NodeRepl", ignoreCase = true) ||
        name.equals("node_repl", ignoreCase = true) ||
        name.equals("node-repl", ignoreCase = true)
    ) {
        return true
    }
    val text = "${tool.name}\n${tool.description}"
    return text.contains("Node REPL", ignoreCase = true) ||
        text.contains("node_repl") ||
        text.contains("node-repl") ||
        text.contains("packages/node-repl-host")
}

private fun scheduleRuleJson(automation: CronAutomationEntity): JsonObject? {
    val unit = automation.intervalUnit.trim().lowercase()
    if (unit.isEmpty() || automation.interval <= 0) return null
    val canonical = when (unit) {
        "minute", "hourly", "daily", "weekly", "monthly", "yearly" -> unit
        else -> return null
    }
    val fields = automation.cronExpr.trim().split(Regex("\\s+"))
    return buildJsonObject {
        put("unit", canonical)
        put("interval", automation.interval)
        put("hour", fields.getOrNull(1)?.toIntOrNull() ?: 0)
        put("minute", fields.getOrNull(0)?.toIntOrNull() ?: 0)
        put("anchorAt", automation.anchorAt)
    }
}

private fun providerDescription(description: String, instructions: List<String>): String {
    val usage = buildString {
        append("Usage:")
        for (instruction in instructions) {
            val line = instruction.trim()
            if (line.isEmpty()) continue
            append("\n- ")
            append(line)
        }
    }
    return if (description.isNotEmpty()) "$description\n\n$usage" else usage
}

private fun strictToolObject(
    required: List<String> = emptyList(),
    properties: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
): JsonObject = buildJsonObject {
    put("\$schema", TOOL_JSON_SCHEMA)
    put("type", "object")
    put("additionalProperties", false)
    putJsonObject("properties", properties)
    if (required.isNotEmpty()) {
        putJsonArray("required") { required.forEach { add(it) } }
    }
}

private fun nullablePositiveInt(max: Int?, description: String): JsonObject = buildJsonObject {
    put("description", description)
    put("oneOf", buildJsonArray {
        add(buildJsonObject {
            put("type", "integer")
            put("exclusiveMinimum", 0)
            if (max != null) put("maximum", max)
        })
        add(buildJsonObject { put("type", "null") })
    })
}

private fun intervalUnitProperty(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    putJsonArray("enum") {
        add("minute")
        add("hourly")
        add("daily")
        add("weekly")
        add("monthly")
        add("yearly")
    }
    put("description", description)
}

private fun jsonReceived(value: JsonElement): String = when (value) {
    is JsonNull -> "null"
    is JsonArray -> "array"
    is JsonObject -> "object"
    is JsonPrimitive -> when {
        value.isString -> "string"
        value.content == "true" || value.content == "false" -> "boolean"
        else -> "number"
    }
}
