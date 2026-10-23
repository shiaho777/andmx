package com.andmx.agent.zcode

import android.content.Context
import com.andmx.agent.ComputerUseTool
import com.andmx.agent.BrowseTool
import com.andmx.agent.EditFileTool
import com.andmx.agent.ExecutionAwareTool
import com.andmx.agent.GhRateLimitHint
import com.andmx.agent.GlobTool
import com.andmx.agent.GrepTool
import com.andmx.agent.ListDirTool
import com.andmx.agent.ReadFileTool
import com.andmx.agent.ShellTool
import com.andmx.agent.Tool
import com.andmx.agent.ToolResult
import com.andmx.agent.ToolRisk
import com.andmx.agent.UpdatePlanTool
import com.andmx.agent.LegacyWebSearchTool
import com.andmx.agent.WebFetchTool
import com.andmx.agent.WebSearchTool
import com.andmx.agent.WriteFileTool
import com.andmx.agent.GoalToolState
import com.andmx.agent.CreateGoalTool
import com.andmx.agent.UpdateGoalTool
import com.andmx.agent.GetGoalTool
import com.andmx.agent.ApplyPatchTool
import com.andmx.agent.GitTool
import com.andmx.exec.policy.NetworkPolicy
import com.andmx.agent.permission.CollaborationMode
import com.andmx.ui2.chat.ExecMode
import com.andmx.workspace.WorkspaceAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private fun JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
    this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: default

private fun JsonObject.int(key: String): Int? =
    this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

/** Renames/adapts an existing tool to ZCode's wire name + schema surface. */
private class GhRateLimitHintTool(
    private val inner: Tool,
) : Tool, ExecutionAwareTool {
    override val name get() = inner.name
    override val description get() = inner.description
    override val parameters get() = inner.parameters
    override val risk get() = inner.risk
    override val timeoutMs get() = inner.timeoutMs
    override val concurrentSafe get() = inner.concurrentSafe
    override val destructive get() = inner.destructive
    override val readOnly get() = inner.readOnly
    override val needsApproval get() = inner.needsApproval
    override val allowedInPlanMode get() = inner.allowedInPlanMode
    override val permissionName get() = inner.permissionName
    override val riskLevel get() = inner.riskLevel
    override val sideEffectScope get() = inner.sideEffectScope
    override val alwaysAsk get() = inner.alwaysAsk
    override val userApprovalRule get() = inner.userApprovalRule
    override val stopTurnOnSuccess get() = inner.stopTurnOnSuccess
    override val maxOutputBytes get() = inner.maxOutputBytes
    override fun resolveTimeoutMs(args: JsonObject): Long? = inner.resolveTimeoutMs(args)

    override suspend fun execute(args: JsonObject): ToolResult = execute("", args)

    override suspend fun execute(callId: String, args: JsonObject): ToolResult {
        val result = if (inner is ExecutionAwareTool && callId.isNotBlank()) {
            inner.execute(callId, args)
        } else {
            inner.execute(args)
        }
        val command = args["command"]?.jsonPrimitive?.content.orEmpty()
        if (!result.isError && result.output.length > 1_000_000) return result
        return if (GhRateLimitHint.shouldHint(command, result.output)) {
            ToolResult(result.output + "\n\n" + GhRateLimitHint.HINT, isError = result.isError, imageUrls = result.imageUrls)
        } else {
            result
        }
    }
}

class PlanModeState(initial: ExecMode = ExecMode.BUILD) {
    var mode: ExecMode = initial
        private set
    var previous: ExecMode = if (initial.collaboration == CollaborationMode.PLAN) ExecMode.BUILD else initial
        private set
    private var captured: Boolean = initial.collaboration == CollaborationMode.PLAN

    val collaboration: CollaborationMode
        get() = mode.collaboration

    fun enter(from: ExecMode? = null) {
        if (collaboration == CollaborationMode.PLAN) {
            if (!captured) {
                previous = from?.takeIf { it.collaboration != CollaborationMode.PLAN } ?: ExecMode.BUILD
                captured = true
            }
            return
        }
        if (!captured) {
            previous = from?.takeIf { it.collaboration != CollaborationMode.PLAN } ?: mode
            captured = true
        }
        mode = ExecMode.PLAN
    }

    fun exit(): ExecMode {
        val back = previous.takeIf { it.collaboration != CollaborationMode.PLAN } ?: ExecMode.BUILD
        mode = back
        captured = false
        return back
    }

    fun adopt(next: ExecMode) {
        if (next.collaboration == CollaborationMode.PLAN) {
            enter(null)
            return
        }
        mode = next
        previous = next
        captured = false
    }

    fun syncTo(next: ExecMode) {
        if (next.collaboration == CollaborationMode.PLAN) {
            if (collaboration != CollaborationMode.PLAN) enter(ExecMode.BUILD)
        } else {
            adopt(next)
        }
    }
}

class TodoState {
    data class Item(val content: String, val status: String, val priority: String)
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items
    fun set(items: List<Item>) { _items.value = items }
    fun clear() { _items.value = emptyList() }
    fun asPlanSteps(): List<UpdatePlanTool.PlanStep> = _items.value.map {
        UpdatePlanTool.PlanStep(
            content = it.content,
            status = when (it.status.lowercase()) {
                "completed" -> UpdatePlanTool.StepStatus.COMPLETED
                "in_progress" -> UpdatePlanTool.StepStatus.IN_PROGRESS
                else -> UpdatePlanTool.StepStatus.PENDING
            },
        )
    }
}

class TodoWriteTool(
    private val todo: TodoState,
    private val planTool: UpdatePlanTool,
) : Tool {
    override val name = "TodoWrite"
    override val description = TODO_WRITE_DESCRIPTION
    override val risk = ToolRisk.READ
    override val readOnly = true
    override val destructive = false
    override val concurrentSafe = false
    override val needsApproval = false
    override val timeoutMs = SESSION_TOOL_TIMEOUT_MS
    override val maxOutputBytes = TODO_MODEL_BYTES
    override val sideEffectScope = "session"
    override val riskLevel = "low"
    override val permissionName = "todo.write"
    override val parameters = TODO_WRITE_PARAMETERS

    override suspend fun execute(args: JsonObject): ToolResult {
        val arr = args["todos"] as? JsonArray
            ?: return ToolResult(missingParameter("TodoWrite", "todos"), isError = true)
        val items = ArrayList<TodoState.Item>(arr.size)
        for (el in arr) {
            val o = el as? JsonObject ?: return ToolResult(missingParameter("TodoWrite", "todos"), isError = true)
            val content = o.str("content").orEmpty()
            val status = o.str("status").orEmpty()
            val priority = o.str("priority").orEmpty()
            if (content.isEmpty() || status !in TODO_STATUS || priority !in TODO_PRIORITY) {
                return ToolResult("TodoWrite input failed inputSchema validation", isError = true)
            }
            items += TodoState.Item(content = content, status = status, priority = priority)
        }
        val old = todo.items.value
        todo.set(items)
        if (items.isEmpty()) {
            planTool.clear()
        } else {
            val planArgs = buildJsonObject {
                putJsonArray("steps") {
                    items.forEach { item ->
                        add(buildJsonObject {
                            put("content", item.content.take(48))
                            put("status", item.status)
                        })
                    }
                }
            }
            planTool.execute(planArgs)
        }
        return ToolResult(formatTodoWriteModelContent(old, items))
    }
}

private val TODO_STATUS = setOf("pending", "in_progress", "completed")
private val TODO_PRIORITY = setOf("high", "medium", "low")

class TodoReadTool(private val todo: TodoState) : Tool {
    override val name = "TodoRead"
    override val description = TODO_READ_DESCRIPTION
    override val risk = ToolRisk.READ
    override val readOnly = true
    override val destructive = false
    override val concurrentSafe = true
    override val needsApproval = false
    override val timeoutMs = SESSION_TOOL_TIMEOUT_MS
    override val maxOutputBytes = TODO_MODEL_BYTES
    override val sideEffectScope = "none"
    override val riskLevel = "low"
    override val permissionName = "todo.read"
    override val parameters = TODO_READ_PARAMETERS

    override suspend fun execute(args: JsonObject): ToolResult =
        ToolResult(formatTodoReadModelContent(todo.items.value))
}

class EnterPlanModeTool(
    private val planMode: PlanModeState,
    private val onMode: (ExecMode) -> Unit = {},
    private val requestApproval: (suspend (String) -> Boolean)? = null,
    private val currentMode: () -> ExecMode = { planMode.mode },
) : Tool {
    override val name = "EnterPlanMode"
    override val description = """
Use this tool proactively when you're about to start a non-trivial implementation task. Getting user sign-off on your approach before writing code prevents wasted effort and ensures alignment. This tool transitions you into plan mode where you can explore the codebase and design an implementation approach for user approval.

## When to Use This Tool

**Prefer using EnterPlanMode** for implementation tasks unless they're simple. Use it when ANY of these conditions apply:

1. **New Feature Implementation**: Adding meaningful new functionality
   - Example: "Add a logout button" - where should it go? What should happen on click?
   - Example: "Add form validation" - what rules? What error messages?

2. **Multiple Valid Approaches**: The task can be solved in several different ways
   - Example: "Add caching to the API" - could use Redis, in-memory, file-based, etc.
   - Example: "Improve performance" - many optimization strategies possible

3. **Code Modifications**: Changes that affect existing behavior or structure
   - Example: "Update the login flow" - what exactly should change?
   - Example: "Refactor this component" - what's the target architecture?

4. **Architectural Decisions**: The task requires choosing between patterns or technologies
   - Example: "Add real-time updates" - WebSockets vs SSE vs polling
   - Example: "Implement state management" - Redux vs Context vs custom solution

5. **Multi-File Changes**: The task will likely touch more than 2-3 files
   - Example: "Refactor the authentication system"
   - Example: "Add a new API endpoint with tests"

6. **Unclear Requirements**: You need to explore before understanding the full scope
   - Example: "Make the app faster" - need to profile and identify bottlenecks
   - Example: "Fix the bug in checkout" - need to investigate root cause

7. **User Preferences Matter**: The implementation could reasonably go multiple ways
   - If you would use AskUserQuestion to clarify the approach, use EnterPlanMode instead
   - Plan mode lets you explore first, then present options with context

## When NOT to Use This Tool

Only skip EnterPlanMode for simple tasks:
- Single-line or few-line fixes (typos, obvious bugs, small tweaks)
- Adding a single function with clear requirements
- Tasks where the user has given very specific, detailed instructions
- Pure research/exploration tasks (use the Agent tool instead)

## What Happens in Plan Mode

In plan mode, you'll:
1. Thoroughly explore the codebase using Glob, Grep, and Read
2. Understand existing patterns and architecture
3. Design an implementation approach
4. Present your plan to the user for approval
5. Use AskUserQuestion if you need to clarify approaches
6. Exit plan mode with ExitPlanMode when ready to implement

## Examples

### GOOD - Use EnterPlanMode:
User: "Add user authentication to the app"
- Requires architectural decisions (session vs JWT, where to store tokens, middleware structure)

User: "Optimize the database queries"
- Multiple approaches possible, need to profile first, significant impact

User: "Implement dark mode"
- Architectural decision on theme system, affects many components

User: "Add a delete button to the user profile"
- Seems simple but involves: where to place it, confirmation dialog, API call, error handling, state updates

User: "Update the error handling in the API"
- Affects multiple files, user should approve the approach

### BAD - Don't use EnterPlanMode:
User: "Fix the typo in the README"
- Straightforward, no planning needed

User: "Add a console.log to debug this function"
- Simple, obvious implementation

User: "What files handle routing?"
- Research task, not implementation planning

## Important Notes

- This tool REQUIRES user approval - they must consent to entering plan mode
- If unsure whether to use it, err on the side of planning - it's better to get alignment upfront than to redo work
- Users appreciate being consulted before significant changes are made to their codebase
""".trimIndent()
    override val risk = ToolRisk.READ
    override val readOnly = false
    override val destructive = false
    override val concurrentSafe = false
    override val requiresUserInteraction = false
    override val needsApproval = false
    override val timeoutMs = SESSION_TOOL_TIMEOUT_MS
    override val maxOutputBytes = PLAN_MODE_MODEL_BYTES
    override val sideEffectScope = "session"
    override val riskLevel = "low"
    override val permissionName = "plan.enter"
    override val parameters = ENTER_PLAN_PARAMETERS

    override suspend fun execute(args: JsonObject): ToolResult {
        val ok = if (requestApproval != null) {
            requestApproval("进入计划模式，先探索并设计实现方案，再开始写代码。")
        } else true
        if (!ok) return ToolResult("User declined plan mode.", isError = true)
        val from = currentMode()
        planMode.enter(from)
        onMode(ExecMode.PLAN)
        return ToolResult(ENTER_PLAN_MODEL_CONTENT)
    }
}

class ExitPlanModeTool(
    private val planMode: PlanModeState,
    private val onMode: (ExecMode) -> Unit = {},
    private val requestPlanApproval: suspend (String) -> Boolean = { true },
    /** Receives the plan's allowedPrompts when approved, for session pre-grants. */
    private val onAllowedPrompts: (List<com.andmx.agent.AllowedPrompts.Entry>) -> Unit = {},
    private val currentMode: () -> ExecMode = { planMode.mode },
) : Tool {
    override val name = "ExitPlanMode"
    override val description = """
Use this tool when you are in plan mode and have finished writing your plan and are ready for user approval.

## How This Tool Works
- You should have already explored the codebase and finalized the plan you want the user to review
- This tool DOES take the plan content as the required plan parameter in ZCode
- Pass the complete plan in the plan field; the user will review that content before approving implementation
- This tool simply signals that you're done planning and ready for the user to review and approve
- The user will see the contents of the plan parameter when they review it

## When to Use This Tool
IMPORTANT: Only use this tool when the task requires planning the implementation steps of a task that requires writing code. For research tasks where you're gathering information, searching files, reading files or in general trying to understand the codebase - do NOT use this tool.

## Before Using This Tool
Ensure your plan is complete and unambiguous:
- If you have unresolved questions about requirements or approach, use AskUserQuestion before finalizing your plan
- Once your plan is finalized, use THIS tool to request approval

**Important:** Do NOT use AskUserQuestion to ask "Is this plan okay?" or "Should I proceed?" - that's exactly what THIS tool does. ExitPlanMode inherently requests user approval of your plan.

## Examples

1. Initial task: "Search for and understand the implementation of vim mode in the codebase" - Do not use the exit plan mode tool because you are not planning the implementation steps of a task.
2. Initial task: "Help me implement yank mode for vim" - Use the exit plan mode tool after you have finished planning the implementation steps of the task.
3. Initial task: "Add a new feature to handle user authentication" - If unsure about auth method (OAuth, JWT, etc.), use AskUserQuestion first, then use exit plan mode tool after clarifying the approach.
""".trimIndent()
    override val risk = ToolRisk.READ
    override val readOnly = false
    override val destructive = false
    override val concurrentSafe = false
    override val requiresUserInteraction = true
    override val needsApproval = true
    override val timeoutMs = SESSION_TOOL_TIMEOUT_MS
    override val maxOutputBytes = PLAN_MODE_MODEL_BYTES
    override val sideEffectScope = "session"
    override val riskLevel = "low"
    override val permissionName = "plan.exit"
    override val parameters = EXIT_PLAN_PARAMETERS

    override suspend fun execute(args: JsonObject): ToolResult {
        if (currentMode().collaboration != CollaborationMode.PLAN) {
            return ToolResult(NOT_IN_PLAN_MODE_ERROR, isError = true)
        }
        if (planMode.collaboration != CollaborationMode.PLAN) {
            planMode.enter(null)
        }
        val plan = args.str("plan")
            ?: return ToolResult(missingParameter("ExitPlanMode", "plan"), isError = true)
        if (plan.length > PLAN_MODE_MAX_PLAN_CHARS) {
            return ToolResult("String must contain at most 20000 character(s)", isError = true)
        }
        if (plan.trim().isEmpty()) {
            return ToolResult("String must contain at least 1 character(s)", isError = true)
        }
        val approved = requestPlanApproval(plan)
        if (!approved) {
            return ToolResult(
                "User rejected the plan. Stay in plan mode, revise the plan, and call ExitPlanMode again when ready.",
                isError = true,
            )
        }
        val back = planMode.exit()
        onMode(back)
        onAllowedPrompts(com.andmx.agent.AllowedPrompts.parse(args))
        return ToolResult(formatExitPlanModeModelContent(plan))
    }
}

data class AskOption(
    val label: String,
    val description: String,
    val preview: String? = null,
)

data class AskQuestion(
    val question: String,
    val header: String,
    val options: List<AskOption>,
    val multiSelect: Boolean = false,
)

object AskUserQuestionParser {
    fun parse(args: JsonObject): List<AskQuestion> {
        val arr = args["questions"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val question = o.str("question")?.trim().orEmpty()
            val header = o.str("header")?.trim().orEmpty()
            if (question.isBlank() || header.isBlank()) return@mapNotNull null
            val optsArr = o["options"] as? JsonArray ?: return@mapNotNull null
            val options = optsArr.mapNotNull { oe ->
                val oo = oe as? JsonObject ?: return@mapNotNull null
                val label = oo.str("label")?.trim().orEmpty()
                val desc = oo.str("description")?.trim().orEmpty()
                if (label.isBlank()) return@mapNotNull null
                AskOption(label = label, description = desc, preview = oo.str("preview"))
            }
            if (options.size < 2) return@mapNotNull null
            AskQuestion(
                question = question,
                header = header.take(12),
                options = options.take(4),
                multiSelect = o.bool("multiSelect", false) || o.bool("multi_select", false),
            )
        }.take(4)
    }

    fun formatAnswersJson(
        questions: List<AskQuestion>,
        answers: Map<String, String>,
        annotations: Map<String, Pair<String?, String?>> = emptyMap(),
    ): String = buildJsonObject {
        putJsonObject("answers") {
            questions.forEach { q ->
                val a = answers[q.question] ?: answers[q.header]
                if (a != null) put(q.question, a)
            }
            answers.forEach { (k, v) ->
                if (!questions.any { it.question == k }) put(k, v)
            }
        }
        if (annotations.isNotEmpty()) {
            putJsonObject("annotations") {
                annotations.forEach { (k, pair) ->
                    putJsonObject(k) {
                        pair.first?.let { put("preview", it) }
                        pair.second?.let { put("notes", it) }
                    }
                }
            }
        }
    }.toString()

    private val answersJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** Parse the UI-collected `{"answers":{...},"annotations":{...}}` payload. */
    fun parseAnswersJson(text: String): Pair<JsonObject, JsonObject> {
        val root = runCatching { answersJson.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return JsonObject(emptyMap()) to JsonObject(emptyMap())
        val answers = (root["answers"] as? JsonObject)
            ?.filterKeys { it != "__default__" }
            ?.let { JsonObject(it) }
            ?: JsonObject(emptyMap())
        val annotations = root["annotations"] as? JsonObject ?: JsonObject(emptyMap())
        return answers to annotations
    }

    /**
     * ZCode `formatAskUserQuestionModelContent` 对齐：未作答/部分作答/全部作答
     * 三态文案——空答案明确「不是拒绝，按最佳判断继续」。
     */
    fun formatModelContent(
        questions: List<AskQuestion>,
        answers: JsonObject,
        annotations: JsonObject = JsonObject(emptyMap()),
    ): String {
        if (answers.isEmpty()) {
            return "The user did not provide answers to these questions. Continue using your " +
                "best judgment; do not treat this as a rejection or invent a user preference."
        }
        val answersText = answers.entries.joinToString(", ") { (q, a) ->
            val value = (a as? JsonPrimitive)?.contentOrNull ?: a.toString()
            val parts = mutableListOf("\"$q\"=\"$value\"")
            val ann = annotations[q] as? JsonObject
            (ann?.get("preview") as? JsonPrimitive)?.contentOrNull?.let {
                parts += "selected preview:\n$it"
            }
            (ann?.get("notes") as? JsonPrimitive)?.contentOrNull?.let {
                parts += "user notes: $it"
            }
            parts.joinToString(" ")
        }
        val unanswered = questions.count { it.question !in answers }
        if (unanswered > 0) {
            return "The user answered some questions and skipped $unanswered. " +
                "Provided answers: $answersText. Continue with the provided answers and " +
                "use your best judgment for the unanswered questions; do not invent user preferences."
        }
        return "User has answered your questions: $answersText. " +
            "You can now continue with the user's answers in mind."
    }
}

class AskUserQuestionTool(
    private val ask: suspend (List<AskQuestion>, JsonObject) -> String,
) : Tool {
    override val name = "AskUserQuestion"
    override val description = ASK_USER_DESCRIPTION
    override val risk = ToolRisk.READ
    override val readOnly = true
    override val destructive = false
    override val concurrentSafe = true
    override val requiresUserInteraction = true
    override val needsApproval = true
    override val timeoutMs = SESSION_TOOL_TIMEOUT_MS
    override val maxOutputBytes = ASK_USER_MODEL_BYTES
    override val sideEffectScope = "userInteraction"
    override val riskLevel = "low"
    override val permissionName = "userInteraction.askQuestion"
    override val parameters = ASK_USER_PARAMETERS

    override suspend fun execute(args: JsonObject): ToolResult {
        val questions = AskUserQuestionParser.parse(args)
        val existing = args["answers"] as? JsonObject
        if (args.containsKey("answers") && existing != null) {
            val cleaned = JsonObject(existing.filterKeys { it != "__default__" })
            return ToolResult(
                AskUserQuestionParser.formatModelContent(
                    questions,
                    cleaned,
                    args["annotations"] as? JsonObject ?: JsonObject(emptyMap()),
                ),
            )
        }
        if (questions.isEmpty()) {
            return ToolResult("questions required: 1-4 items with header/options", isError = true)
        }
        val answer = runCatching { ask(questions, args) }.getOrElse { "" }
        val (answers, annotations) = AskUserQuestionParser.parseAnswersJson(answer)
        return ToolResult(AskUserQuestionParser.formatModelContent(questions, answers, annotations))
    }
}

class ReadSessionContextTool(
    private val resolve: suspend (sessionId: String, query: String, strategy: String, maxTokens: Int) -> SessionContextRead,
) : Tool {
    override val name = "ReadSessionContext"
    override val description = READ_SESSION_DESCRIPTION
    override val risk = ToolRisk.READ
    override val readOnly = true
    override val destructive = false
    override val concurrentSafe = true
    override val needsApproval = false
    override val timeoutMs = READ_SESSION_TIMEOUT_MS
    override val maxOutputBytes = READ_SESSION_MODEL_BYTES
    override val sideEffectScope = "session"
    override val riskLevel = "low"
    override val permissionName = "session.context.read"
    override val parameters = READ_SESSION_PARAMETERS

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = args.str("query")
            ?: return ToolResult(missingParameter("ReadSessionContext", "query"), isError = true)
        if (query.isEmpty() || query.length > 4000) {
            return ToolResult("ReadSessionContext input failed inputSchema validation", isError = true)
        }
        val strategy = args.str("strategy") ?: "relevant"
        if (strategy !in setOf("relevant", "handoff", "topic")) {
            return ToolResult("ReadSessionContext input failed inputSchema validation", isError = true)
        }
        val sessionId = args.str("sessionId") ?: "current"
        if (sessionId == "current" && strategy != "topic") {
            return ToolResult("Non-topic strategies require an explicit session id", isError = true)
        }
        if (args["attachment"] != null && strategy != "topic") {
            return ToolResult("Attachments require topic strategy", isError = true)
        }
        if (strategy == "topic") {
            if (sessionId != "current") {
                return ToolResult("Topic history can only be read from the current session", isError = true)
            }
            if (args["attachment"] != null) {
                val failed = SessionContextRead(
                    status = "failed",
                    source = "none",
                    content = "Topic attachment retrieval is unavailable on this runtime",
                    error = "Topic attachment retrieval is unavailable on this runtime",
                )
                return ToolResult(formatReadSessionContextModelContent(failed, sessionId), isError = true)
            }
            val missing = SessionContextRead(
                status = "not_found",
                source = "local",
                content = TOPIC_ARCHIVE_EMPTY_CONTENT,
            )
            return ToolResult(formatReadSessionContextModelContent(missing, sessionId))
        }
        val maxTokens = args.int("maxTokens") ?: READ_SESSION_CONTEXT_DEFAULT_MAX_TOKENS
        if (maxTokens !in 1..READ_SESSION_CONTEXT_MAX_TOKENS) {
            return ToolResult("ReadSessionContext input failed inputSchema validation", isError = true)
        }
        val read = runCatching { resolve(sessionId, query, strategy, maxTokens) }.getOrElse {
            SessionContextRead(
                status = "failed",
                source = "none",
                content = "Failed to read persisted session history.",
                error = it.message,
            )
        }
        val shownId = read.sessionId ?: sessionId
        val text = formatReadSessionContextModelContent(read, shownId)
        return ToolResult(text, isError = read.status == "failed")
    }
}

class SkillTool(
    private val invoke: suspend (skill: String, args: String?) -> String,
) : Tool {
    override val name = "Skill"
    override val description =
        "Execute a skill within the main conversation\n\n" +
            "When users ask you to perform tasks, check if any of the available skills match. Skills provide specialized capabilities and domain knowledge.\n\n" +
            "When users reference a \"slash command\" or \"/<something>\", they are referring to a skill. Use this tool to invoke it.\n\n" +
            "How to invoke:\n" +
            "- Set `skill` to the exact name of an available skill (no leading slash). For plugin-namespaced skills use the fully qualified `plugin:skill` form.\n" +
            "- Set `args` to pass optional arguments.\n\n" +
            "Important:\n" +
            "- Available skills are listed in system-reminder messages in the conversation\n" +
            "- Only invoke a skill that appears in that list, or one the user explicitly typed as `/<name>` in their message. Never guess or invent a skill name from training data; otherwise do not call this tool\n" +
            "- When a skill matches the user's request, this is a BLOCKING REQUIREMENT: invoke the relevant Skill tool BEFORE generating any other response about the task\n" +
            "- NEVER mention a skill without actually calling this tool\n" +
            "- Do not invoke a skill that is already running\n" +
            "- Do not use this tool for built-in CLI commands (like /help, /clear, etc.)\n" +
            "- If you see a <command-name> tag in the current conversation turn, the skill has ALREADY been loaded - follow the instructions directly instead of calling this tool again"
    override val risk = ToolRisk.READ
    override val readOnly = true
    override val destructive = false
    override val concurrentSafe = true
    override val needsApproval = false
    override val timeoutMs = SESSION_TOOL_TIMEOUT_MS
    override val maxOutputBytes = SKILL_RESULT_BYTES
    override val sideEffectScope = "session"
    override val riskLevel = "low"
    override val permissionName = "skill"
    override val parameters = SKILL_PARAMETERS

    override suspend fun execute(args: JsonObject): ToolResult {
        val raw = when {
            args.containsKey("skill") -> args.str("skill")
            args.containsKey("name") -> args.str("name")
            else -> null
        }
        val skill = raw?.trim().orEmpty().removePrefix("/")
        if (skill.isEmpty()) return ToolResult(SKILL_MISSING_NAME_ERROR, isError = true)
        val skillArgs = args.str("args")
        val out = runCatching { invoke(skill, skillArgs) }.getOrElse { error ->
            val message = error.message?.takeIf { it.isNotBlank() } ?: "Skill failed"
            return ToolResult(message, isError = true)
        }
        if (out.startsWith("Skill not found:")) return ToolResult(out, isError = true)
        return ToolResult(out)
    }
}

/**
 * Build the ZCode-aligned tool surface. Keeps AndMX implementations under
 * ZCode wire names (Read/Write/Edit/Bash/…) so the model prompt + tool list match traces.
 */
fun buildZCodeToolSurface(
    context: Context,
    networkPolicy: NetworkPolicy,
    planTool: UpdatePlanTool,
    goalState: GoalToolState,
    todo: TodoState,
    planMode: PlanModeState,
    cwdProvider: () -> String,
    onPlanModeChange: (ExecMode) -> Unit = {},
    currentMode: () -> ExecMode = { planMode.mode },
    askUser: suspend (List<AskQuestion>, JsonObject) -> String = { _, _ -> "用户未作答" },
    readSession: suspend (String, String, String, Int) -> SessionContextRead = { id, _, _, _ ->
        SessionContextRead(status = "not_found", content = "No persisted session was found for $id.")
    },
    invokeSkill: suspend (String, String?) -> String = { name, _ ->
        throw IllegalStateException("Skill not found: $name")
    },
    requestEnterPlanApproval: (suspend (String) -> Boolean)? = null,
    requestExitPlanApproval: suspend (String) -> Boolean = { true },
    allowedPromptsSink: ((List<com.andmx.agent.AllowedPrompts.Entry>) -> Unit)? = null,
    backgroundTasks: com.andmx.agent.BackgroundTasks? = null,
    includeGoals: Boolean = true,
    includeLegacyAliases: Boolean = true,
    listModelsProviders: (suspend () -> List<com.andmx.llm.provider.ProviderDefinition>)? = null,
    listModelsCurrent: (suspend () -> Pair<String, String>)? = null,
    readFileState: com.andmx.agent.ReadFileState? = null,
    webFetchSummarizer: (suspend (content: String, prompt: String) -> String?)? = null,
): List<Tool> {
    val access = WorkspaceAccess(context)
    val shell = ShellTool(context, cwdProvider = cwdProvider, backgroundTasks = backgroundTasks)
    val shellWithHint = GhRateLimitHintTool(shell)
    val read = ReadFileTool(context, readFileState)
    val write = WriteFileTool(context, readFileState)
    val edit = EditFileTool(context, readFileState)
    val grep = GrepTool(context, cwdProvider)
    val glob = GlobTool(context, cwdProvider)
    val listDir = ListDirTool(context)
    val browse = BrowseTool(networkPolicy, summarizer = webFetchSummarizer)
    val search = LegacyWebSearchTool(networkPolicy)

    val fetchZ = WebFetchTool(
        networkPolicy = networkPolicy,
        summarizer = webFetchSummarizer,
    )
    val webSearchZ = WebSearchTool(networkPolicy = networkPolicy)

    val zcode = mutableListOf(
        read, write, edit, shellWithHint, grep, glob, fetchZ, webSearchZ,
        TodoReadTool(todo),
        TodoWriteTool(todo, planTool),
        EnterPlanModeTool(planMode, onPlanModeChange, requestEnterPlanApproval, currentMode),
        ExitPlanModeTool(
            planMode,
            onPlanModeChange,
            requestExitPlanApproval,
            onAllowedPrompts = allowedPromptsSink ?: {},
            currentMode = currentMode,
        ),
        AskUserQuestionTool(askUser),
        ReadSessionContextTool(readSession),
        SkillTool(invokeSkill),
        listDir,
        ApplyPatchTool(context),
        GitTool(context, cwdProvider = cwdProvider),
        ComputerUseTool(context),
    )
    if (includeGoals) {
        zcode += CreateGoalTool(goalState)
        zcode += UpdateGoalTool(goalState)
        zcode += GetGoalTool(goalState)
    }
    if (listModelsProviders != null) {
        zcode += ListModelsTool(
            providers = listModelsProviders,
            current = listModelsCurrent ?: { "" to "" },
        )
    }
    if (includeLegacyAliases) {
        zcode += browse
        zcode += search
        zcode += planTool
    }
    return zcode
}

/** Plan-mode write gate: only allow read-like tools while plan mode is active. */
fun isPlanModeAllowed(toolName: String): Boolean {
    val n = toolName.lowercase()
    if (n.startsWith("mcp_")) return true
    if (n in setOf(
        "read", "read_file", "grep", "glob", "list_dir", "listdir",
        "webfetch", "browse", "websearch", "web_search",
        "todoread", "todowrite", "update_plan",
        "enterplanmode", "exitplanmode", "askuserquestion",
        "readsessioncontext", "skill", "agent", "task", "taskoutput", "taskstop", "spawn_agent", "multi_agent",
        "listmodels", "cronlist",
        "listsavedworkflows", "listworkflowruns", "getworkflowrun",
        "getworkflowrunroster", "evalworkflowsnippet",
        "get_goal", "create_goal", "update_goal",
        "android_preflight", "android_discover_project", "android_list_devices",
        "android_list_avds", "android_ui_status", "android_ui_describe", "android_ui_resolve",
        "android_logs", "storage_overview", "storage_scan", "storage_find_large",
        "storage_find_junk", "storage_find_duplicates", "storage_app_usage",
        "storage_preview_delete", "storage_compare",
        "html_video_workspace_scan", "html_video_deliver",
        "forge_env_scan", "forge_list_profiles", "forge_recipe_recommend",
        "forge_recipe_plan", "forge_check_step", "forge_project_status",
    )) return true
    return false
}
