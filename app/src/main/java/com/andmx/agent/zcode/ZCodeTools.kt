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
import com.andmx.agent.WebSearchTool
import com.andmx.agent.WriteFileTool
import com.andmx.agent.GoalToolState
import com.andmx.agent.CreateGoalTool
import com.andmx.agent.UpdateGoalTool
import com.andmx.agent.GetGoalTool
import com.andmx.agent.ApplyPatchTool
import com.andmx.agent.GitTool
import com.andmx.exec.policy.NetworkPolicy
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
    override val concurrentSafe get() = inner.concurrentSafe

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

private class AliasedTool(
    private val inner: Tool,
    override val name: String,
    override val description: String,
    override val parameters: JsonObject,
    override val risk: ToolRisk = inner.risk,
    override val timeoutMs: Long? = inner.timeoutMs,
    override val concurrentSafe: Boolean = inner.concurrentSafe,
    private val mapArgs: (JsonObject) -> JsonObject = { it },
) : Tool, ExecutionAwareTool {
    override suspend fun execute(args: JsonObject): ToolResult = execute("", args)
    override suspend fun execute(callId: String, args: JsonObject): ToolResult {
        val mapped = mapArgs(args)
        return if (inner is ExecutionAwareTool && callId.isNotBlank()) {
            inner.execute(callId, mapped)
        } else {
            inner.execute(mapped)
        }
    }
}

class PlanModeState {
    private val _inPlan = MutableStateFlow(false)
    val inPlan: StateFlow<Boolean> = _inPlan
    fun enter() { _inPlan.value = true }
    fun exit() { _inPlan.value = false }
    val active: Boolean get() = _inPlan.value
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
    override val description =
        "Update the structured todo list for the current session. " +
            "At most one item may be in_progress at a time. Pass the complete updated list."
    override val risk = ToolRisk.READ
    override val concurrentSafe = false
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("todos") {
                put("type", "array")
                put("description", "The complete updated todo list. At most one item may be in_progress at a time.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("content") {
                            put("type", "string")
                            put("description", "Brief description of the task")
                        }
                        putJsonObject("status") {
                            put("type", "string")
                            putJsonArray("enum") {
                                add("pending"); add("in_progress"); add("completed")
                            }
                        }
                        putJsonObject("priority") {
                            put("type", "string")
                            putJsonArray("enum") {
                                add("high"); add("medium"); add("low")
                            }
                        }
                    }
                    putJsonArray("required") {
                        add("content"); add("status"); add("priority")
                    }
                }
            }
        }
        putJsonArray("required") { add("todos") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val arr = args["todos"] as? JsonArray
            ?: return ToolResult("todos is required", isError = true)
        val items = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val content = o.str("content")?.trim().orEmpty()
            if (content.isBlank()) return@mapNotNull null
            TodoState.Item(
                content = content,
                status = o.str("status") ?: "pending",
                priority = o.str("priority") ?: "medium",
            )
        }
        if (items.isEmpty()) return ToolResult("todos is empty", isError = true)
        if (items.count { it.status.equals("in_progress", true) } > 1) {
            return ToolResult("at most one todo may be in_progress", isError = true)
        }
        todo.set(items)
        // Keep legacy plan panel in sync.
        val planArgs = buildJsonObject {
            putJsonArray("steps") {
                items.forEach { item ->
                    add(buildJsonObject {
                        put("content", item.content.take(48))
                        put("status", item.status.lowercase())
                    })
                }
            }
        }
        planTool.execute(planArgs)
        return ToolResult("Todos updated (${items.size})")
    }
}

class TodoReadTool(private val todo: TodoState) : Tool {
    override val name = "TodoRead"
    override val description = "Read the current session todo list."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val items = todo.items.value
        if (items.isEmpty()) return ToolResult("(no todos)")
        return ToolResult(items.joinToString("\n") {
            val mark = when (it.status.lowercase()) {
                "completed" -> "x"
                "in_progress" -> "~"
                else -> " "
            }
            "[$mark] (${it.priority}) ${it.content}"
        })
    }
}

class EnterPlanModeTool(
    private val planMode: PlanModeState,
    private val onMode: (ExecMode) -> Unit = {},
    private val requestApproval: (suspend (String) -> Boolean)? = null,
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
    override val concurrentSafe = false
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val ok = if (requestApproval != null) {
            requestApproval("进入计划模式，先探索并设计实现方案，再开始写代码。")
        } else true
        if (!ok) return ToolResult("User declined plan mode.", isError = true)
        planMode.enter()
        onMode(ExecMode.PLAN)
        return ToolResult(
            "Entered plan mode. You should now focus on exploring the codebase and designing an implementation approach.\n\n" +
                "In plan mode, you should:\n" +
                "1. Thoroughly explore the codebase to understand existing patterns\n" +
                "2. Identify similar features and architectural approaches\n" +
                "3. Consider multiple approaches and their trade-offs\n" +
                "4. Use AskUserQuestion if you need to clarify the approach\n" +
                "5. Design a concrete implementation strategy\n" +
                "6. When ready, use ExitPlanMode to present your plan for approval\n\n" +
                "Remember: DO NOT write or edit any files yet. This is a read-only exploration and planning phase.",
        )
    }
}

class ExitPlanModeTool(
    private val planMode: PlanModeState,
    private val onMode: (ExecMode) -> Unit = {},
    private val requestPlanApproval: suspend (String) -> Boolean = { true },
    /** Receives the plan's allowedPrompts when approved, for session pre-grants. */
    private val onAllowedPrompts: (List<com.andmx.agent.AllowedPrompts.Entry>) -> Unit = {},
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
    override val concurrentSafe = false
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("plan") {
                put("type", "string")
                put("description", "The implementation plan to present to the user for approval.")
                put("minLength", 1)
                put("maxLength", 20000)
            }
            putJsonObject("allowedPrompts") {
                put("type", "array")
                put("description", "Prompt-based permissions needed to implement the plan.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("tool") {
                            put("type", "string")
                            putJsonArray("enum") { add("Bash") }
                        }
                        putJsonObject("prompt") { put("type", "string") }
                    }
                    putJsonArray("required") { add("tool"); add("prompt") }
                }
            }
            putJsonObject("summary") {
                put("type", "string")
                put("description", "Deprecated alias for plan")
            }
        }
        putJsonArray("required") { add("plan") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!planMode.active) {
            return ToolResult(
                "You are not in plan mode. This tool is only for exiting plan mode after writing a plan. " +
                    "If your plan was already approved, continue with implementation.",
                isError = true,
            )
        }
        val plan = (args.str("plan") ?: args.str("summary")).orEmpty().trim()
        if (plan.isBlank()) {
            return ToolResult("plan is required (1..20000 chars)", isError = true)
        }
        if (plan.length > 20000) {
            return ToolResult("plan exceeds 20000 characters", isError = true)
        }
        val approved = requestPlanApproval(plan)
        if (!approved) {
            return ToolResult(
                "User rejected the plan. Stay in plan mode, revise the plan, and call ExitPlanMode again when ready.",
                isError = true,
            )
        }
        planMode.exit()
        onMode(ExecMode.AUTO_EDIT)
        onAllowedPrompts(com.andmx.agent.AllowedPrompts.parse(args))
        return ToolResult(
            "User has approved your plan. You can now start coding. Start with updating your todo list if applicable.\n\n" +
                "## Approved Plan:\n$plan",
        )
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
    override val description =
        "Use this tool only when you are blocked on a decision that is genuinely the user's to make: " +
            "one you cannot resolve from the request, the code, or sensible defaults.\n\n" +
            "Usage notes:\n" +
            "- Users will always be able to select \"Other\" to provide custom text input\n" +
            "- Use multiSelect: true to allow multiple answers to be selected for a question\n" +
            "- If you recommend a specific option, make that the first option in the list and add \"(Recommended)\" at the end of the label\n\n" +
            "Plan mode note: To switch into plan mode, use EnterPlanMode (not this tool). Once in plan mode, " +
            "use this tool to clarify requirements or choose between approaches BEFORE finalizing your plan. " +
            "Do NOT use this tool to ask \"Is my plan ready?\", \"Should I proceed?\", or otherwise reference " +
            "\"the plan\" in questions — the user cannot see the plan until you call ExitPlanMode for approval.\n\n" +
            "Reserve this for decisions where the user's answer changes what you do next — not for choices " +
            "with a conventional default or facts you can verify in the codebase yourself. In those cases " +
            "pick the obvious option, mention it in your response, and proceed.\n\n" +
            "Preview feature:\n" +
            "Use the optional `preview` field on options when presenting concrete artifacts that users need " +
            "to visually compare:\n" +
            "- ASCII mockups of UI layouts or components\n" +
            "- Code snippets showing different implementations\n" +
            "- Diagram variations\n" +
            "- Configuration examples\n\n" +
            "Preview content is rendered as markdown in a monospace box. Multi-line text with newlines is " +
            "supported. When any option has a preview, the UI switches to a side-by-side layout with a " +
            "vertical option list on the left and preview on the right. Do not use previews for simple " +
            "preference questions where labels and descriptions suffice. Note: previews are only supported " +
            "for single-select questions (not multiSelect)."
    override val risk = ToolRisk.READ
    // 与上游 concurrentSafe=true 有意不同：AndMX 的问答是单卡 UI
    // （session.pendingAnswer 单槽位），并发两个 ask 会互相覆盖 deferred。
    override val concurrentSafe = false
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("questions") {
                put("type", "array")
                put("description", "Questions to ask the user (1-4 questions)")
                put("minItems", 1)
                put("maxItems", 4)
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("question") {
                            put("type", "string")
                            put("description", "Complete question ending with ?")
                        }
                        putJsonObject("header") {
                            put("type", "string")
                            put("description", "Short chip label (max 12 chars)")
                        }
                        putJsonObject("options") {
                            put("type", "array")
                            put("minItems", 2)
                            put("maxItems", 4)
                            put("description", "2-4 options; do not include Other")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("label") { put("type", "string") }
                                    putJsonObject("description") { put("type", "string") }
                                    putJsonObject("preview") { put("type", "string") }
                                }
                                putJsonArray("required") { add("label"); add("description") }
                            }
                        }
                        putJsonObject("multiSelect") {
                            put("type", "boolean")
                            put("default", false)
                        }
                    }
                    putJsonArray("required") { add("question"); add("header"); add("options") }
                }
            }
            putJsonObject("answers") {
                put("type", "object")
                put("description", "User answers collected by the permission component")
            }
            putJsonObject("metadata") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("source") { put("type", "string") }
                }
            }
        }
        putJsonArray("required") { add("questions") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val questions = AskUserQuestionParser.parse(args)
        val existing = args["answers"] as? JsonObject
        if (existing != null && existing.isNotEmpty()) {
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
    private val resolve: suspend (sessionId: String, query: String, strategy: String, maxTokens: Int) -> String,
) : Tool {
    override val name = "ReadSessionContext"
    override val description =
        "Read relevant or handoff context from another persisted session. " +
            "Use when the user references #sess_* or asks to continue from a prior session."
    override val risk = ToolRisk.READ
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") {
                put("type", "string")
                put("description", "Target session id")
            }
            putJsonObject("query") {
                put("type", "string")
                put("description", "What context is needed")
            }
            putJsonObject("strategy") {
                put("type", "string")
                putJsonArray("enum") { add("relevant"); add("handoff") }
            }
            putJsonObject("maxTokens") { put("type", "integer") }
        }
        putJsonArray("required") { add("sessionId"); add("query") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val sid = args.str("sessionId") ?: return ToolResult("sessionId required", isError = true)
        val query = args.str("query") ?: return ToolResult("query required", isError = true)
        val strategy = args.str("strategy") ?: "relevant"
        val maxTokens = args.int("maxTokens") ?: 4000
        val text = runCatching { resolve(sid, query, strategy, maxTokens) }
            .getOrElse { "无法读取会话上下文: ${it.message}" }
        return ToolResult(
            com.andmx.agent.SystemReminder.wrap(
                com.andmx.agent.SystemReminder.Source.REFERENCED_SESSION_CONTEXT,
                text,
            ).trimEnd(),
        )
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
    override val risk = ToolRisk.EXECUTE
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("skill") {
                put("type", "string")
                put("description", "The name of a skill from the available-skills list. Do not guess names.")
            }
            putJsonObject("args") {
                put("type", "string")
                put("description", "Optional arguments for the skill")
            }
        }
        putJsonArray("required") { add("skill") }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val skill = args.str("skill")?.trim().orEmpty().removePrefix("/")
        if (skill.isBlank()) return ToolResult("skill required", isError = true)
        val skillArgs = args.str("args")
        val out = runCatching { invoke(skill, skillArgs) }
            .getOrElse { "Skill failed: ${it.message}" }
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
    askUser: suspend (List<AskQuestion>, JsonObject) -> String = { _, _ -> "用户未作答" },
    readSession: suspend (String, String, String, Int) -> String = { _, _, _, _ -> "会话上下文不可用" },
    invokeSkill: suspend (String, String?) -> String = { name, _ -> "技能未安装: $name" },
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
    val grep = GrepTool(context)
    val glob = GlobTool(context)
    val listDir = ListDirTool(context)
    val browse = BrowseTool(networkPolicy, summarizer = webFetchSummarizer)
    val search = WebSearchTool(networkPolicy)

    fun mapPath(args: JsonObject): JsonObject {
        val filePath = args.str("file_path") ?: args.str("path")
        if (filePath == null) return args
        return buildJsonObject {
            args.forEach { (k, v) ->
                if (k != "file_path") put(k, v)
            }
            put("path", filePath)
        }
    }

    val readZ = AliasedTool(
        inner = read,
        name = "Read",
        description = "Reads a file from the local filesystem. `file_path` may be absolute or workspace-relative. Optional offset/limit for large files.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("file_path") { put("type", "string"); put("description", "Path to the file to read") }
                putJsonObject("offset") { put("type", "integer"); put("description", "Start line (0-based)") }
                putJsonObject("limit") { put("type", "integer"); put("description", "Max lines") }
            }
            putJsonArray("required") { add("file_path") }
        },
        risk = ToolRisk.READ,
        mapArgs = ::mapPath,
    )
    val writeZ = AliasedTool(
        inner = write,
        name = "Write",
        description = "Writes a file, overwriting if it exists. Prefer Edit for partial changes.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("file_path") { put("type", "string") }
                putJsonObject("content") { put("type", "string") }
            }
            putJsonArray("required") { add("file_path"); add("content") }
        },
        risk = ToolRisk.WRITE,
        mapArgs = ::mapPath,
    )
    val editZ = AliasedTool(
        inner = edit,
        name = "Edit",
        description = "Exact string replacement in a file. Read first. old_string must be unique unless replace_all.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("file_path") { put("type", "string") }
                putJsonObject("old_string") { put("type", "string") }
                putJsonObject("new_string") { put("type", "string") }
                putJsonObject("replace_all") { put("type", "boolean") }
            }
            putJsonArray("required") { add("file_path"); add("old_string"); add("new_string") }
        },
        risk = ToolRisk.WRITE,
        mapArgs = { args ->
            val mapped = mapPath(args)
            buildJsonObject {
                mapped.forEach { (k, v) ->
                    when (k) {
                        "old_string" -> put("old_str", v)
                        "new_string" -> put("new_str", v)
                        "replace_all" -> put("replace_all", v)
                        else -> put(k, v)
                    }
                }
            }
        },
    )
    val bashZ = AliasedTool(
        inner = shellWithHint,
        name = "Bash",
        description = "Executes a bash command and returns its output.\n\n" +
            "- Working directory persists between calls, but prefer absolute paths — `cd` in a compound command can trigger a permission prompt. Shell state (env vars, functions) does not persist; the shell is initialized from the user's profile.\n" +
            "- IMPORTANT: Avoid using this tool to run `cat`, `head`, `tail`, `sed`, `awk`, or `echo` commands, unless explicitly instructed or after you have verified that a dedicated tool cannot accomplish your task. Instead, use the appropriate dedicated tool as this will provide a much better experience for the user.\n\n" +
            "# Git\n" +
            "- Interactive flags (`-i`, e.g. `git rebase -i`, `git add -i`) are not supported in this environment.\n" +
            "- Use the `gh` CLI for GitHub operations (PRs, issues, API).\n" +
            "- Commit or push only when the user asks. If on the default branch, branch first.\n" +
            "- On Android/AndMX the shell runs in a proot Alpine guest (or remote SSH); paths under /root/project map to the workspace.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("command") { put("type", "string") }
                putJsonObject("timeout") {
                    put("type", "number")
                    put("description", "Max execution time in ms (max 600000)")
                }
                putJsonObject("description") { put("type", "string") }
                putJsonObject("run_in_background") {
                    put("type", "boolean")
                    put("description", "Run without blocking; returns a task_id. Use TaskOutput/TaskStop to manage.")
                }
            }
            putJsonArray("required") { add("command") }
        },
        risk = ToolRisk.EXECUTE,
        mapArgs = { args ->
            buildJsonObject {
                args.forEach { (k, v) -> put(k, v) }
                args["timeout"]?.let { put("timeout_ms", it) }
            }
        },
    )
    val grepZ = AliasedTool(
        inner = grep,
        name = "Grep",
        description = "Content search built on ripgrep-compatible search. Prefer this over grep/rg via Bash. " +
            "Full regex; filter with glob or type; output_mode content|files_with_matches|count; multiline supported.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("pattern") {
                    put("type", "string")
                    put("description", "The ripgrep-compatible regular expression pattern")
                }
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Optional file or directory to search")
                }
                putJsonObject("glob") {
                    put("type", "string")
                    put("description", "Ripgrep-style glob to filter files, e.g. *.{ts,tsx}")
                }
                putJsonObject("output_mode") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("content"); add("files_with_matches"); add("count")
                    }
                }
                putJsonObject("-B") { put("type", "integer") }
                putJsonObject("-A") { put("type", "integer") }
                putJsonObject("-C") { put("type", "integer") }
                putJsonObject("context") { put("type", "integer") }
                putJsonObject("-n") { put("type", "boolean") }
                putJsonObject("-i") { put("type", "boolean") }
                putJsonObject("type") { put("type", "string") }
                putJsonObject("head_limit") { put("type", "integer") }
                putJsonObject("offset") { put("type", "integer") }
                putJsonObject("multiline") { put("type", "boolean") }
                putJsonObject("case_insensitive") { put("type", "boolean") }
                putJsonObject("max_results") { put("type", "integer") }
            }
            putJsonArray("required") { add("pattern") }
        },
        risk = ToolRisk.READ,
        mapArgs = { args ->
            buildJsonObject {
                args.forEach { (k, v) -> put(k, v) }
                val ci = args.bool("-i") || args.bool("case_insensitive")
                if (ci) put("case_insensitive", true)
                val head = args.int("head_limit") ?: args.int("max_results")
                if (head != null) put("max_results", head)
                args.str("output_mode")?.let { put("output_mode", it) }
                args.int("-B")?.let { put("before_context", it) }
                args.int("-A")?.let { put("after_context", it) }
                val ctx = args.int("-C") ?: args.int("context")
                if (ctx != null) put("context", ctx)
                args.str("type")?.let { put("file_type", it) }
                if (args.bool("multiline")) put("multiline", true)
            }
        },
    )
    val globZ = AliasedTool(
        inner = glob,
        name = "Glob",
        description = "Fast file pattern matching. Supports patterns like **/*.js or src/**/*.ts. " +
            "Returns matching file paths sorted by modification time.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("pattern") {
                    put("type", "string")
                    put("description", "The glob pattern to match files against")
                }
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Optional directory to search")
                }
            }
            putJsonArray("required") { add("pattern") }
        },
        risk = ToolRisk.READ,
    )
    val fetchZ = AliasedTool(
        inner = browse,
        name = "WebFetch",
        description = "Fetches a URL, converts the page to readable text, and answers prompt against it. " +
            "Fails on authenticated/private URLs. HTTP is upgraded to HTTPS.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("url") {
                    put("type", "string")
                    put("description", "The URL to fetch content from")
                }
                putJsonObject("prompt") {
                    put("type", "string")
                    put("description", "The prompt to run on the fetched content")
                }
            }
            putJsonArray("required") { add("url"); add("prompt") }
        },
        risk = ToolRisk.NETWORK,
        concurrentSafe = true,
        mapArgs = { args ->
            buildJsonObject {
                args.forEach { (k, v) -> put(k, v) }
                args.str("prompt")?.let { put("prompt", it) }
            }
        },
    )
    val webSearchZ = AliasedTool(
        inner = search,
        name = "WebSearch",
        description = "Search the web. Returns result blocks with titles and URLs. " +
            "allowed_domains / blocked_domains filter results. After answering, list Sources as markdown links.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "The search query to use")
                }
                putJsonObject("allowed_domains") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Only include results from these domains")
                }
                putJsonObject("blocked_domains") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Never include results from these domains")
                }
            }
            putJsonArray("required") { add("query") }
        },
        risk = ToolRisk.NETWORK,
        concurrentSafe = true,
    )

    val zcode = mutableListOf(
        readZ, writeZ, editZ, bashZ, grepZ, globZ, fetchZ, webSearchZ,
        TodoReadTool(todo),
        TodoWriteTool(todo, planTool),
        EnterPlanModeTool(planMode, onPlanModeChange, requestEnterPlanApproval),
        ExitPlanModeTool(
            planMode,
            onPlanModeChange,
            requestExitPlanApproval,
            onAllowedPrompts = allowedPromptsSink ?: {},
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
        // Keep snake_case aliases so older prompts/models still work.
        zcode += read
        zcode += write
        zcode += edit
        zcode += shell
        zcode += grep
        zcode += glob
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
