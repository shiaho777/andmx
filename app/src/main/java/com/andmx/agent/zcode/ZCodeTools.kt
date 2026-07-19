package com.andmx.agent.zcode

import android.content.Context
import com.andmx.agent.BrowseTool
import com.andmx.agent.EditFileTool
import com.andmx.agent.ExecutionAwareTool
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
import com.andmx.ui.conversation.ConversationGoal
import com.andmx.ui.conversation.GoalStatus
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
private class AliasedTool(
    private val inner: Tool,
    override val name: String,
    override val description: String,
    override val parameters: JsonObject,
    override val risk: ToolRisk = inner.risk,
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
    override val description =
        "Use this tool proactively when you're about to start a non-trivial implementation task. " +
            "Getting user sign-off on your approach before writing code prevents wasted effort. " +
            "Transitions into plan mode to explore and design an implementation approach."
    override val risk = ToolRisk.READ
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
        return ToolResult("Entered plan mode. Explore with Read/Grep/Glob, design the approach, use AskUserQuestion if needed, then ExitPlanMode with the full plan.")
    }
}

class ExitPlanModeTool(
    private val planMode: PlanModeState,
    private val onMode: (ExecMode) -> Unit = {},
    private val requestPlanApproval: suspend (String) -> Boolean = { true },
) : Tool {
    override val name = "ExitPlanMode"
    override val description =
        "Use when in plan mode and the plan is ready for user approval. " +
            "Pass the complete plan in the plan field; the user reviews that content before approving implementation. " +
            "Do NOT use AskUserQuestion to ask if the plan is ready — that is what this tool does."
    override val risk = ToolRisk.READ
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
        return ToolResult("Plan approved. Exited plan mode; implementation may proceed.\n\nApproved plan:\n$plan")
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
}

class AskUserQuestionTool(
    private val ask: suspend (List<AskQuestion>, JsonObject) -> String,
) : Tool {
    override val name = "AskUserQuestion"
    override val description =
        "Use only when blocked on a decision that is genuinely the user's to make. " +
            "Users can always select Other for custom text. Prefer multiSelect when choices are not exclusive. " +
            "If recommending an option, put it first and append (Recommended) to the label. " +
            "Do not use this to ask whether a plan is ready — use ExitPlanMode. " +
            "Optional preview on options enables side-by-side comparison (single-select only)."
    override val risk = ToolRisk.READ
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
        val existing = args["answers"] as? JsonObject
        if (existing != null && existing.isNotEmpty()) {
            return ToolResult(buildJsonObject { put("answers", existing) }.toString())
        }
        val questions = AskUserQuestionParser.parse(args)
        if (questions.isEmpty()) {
            return ToolResult("questions required: 1-4 items with header/options", isError = true)
        }
        val answer = runCatching { ask(questions, args) }.getOrElse { "用户未作答: ${it.message}" }
        return ToolResult(answer)
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
        return ToolResult(text)
    }
}

class SkillTool(
    private val invoke: suspend (skill: String, args: String?) -> String,
) : Tool {
    override val name = "Skill"
    override val description =
        "Execute a skill within the main conversation. When users ask to perform tasks, " +
            "check if any available skills match. When users reference a slash command or /<something>, " +
            "invoke it via this tool. Set skill to the exact available name (no leading slash). " +
            "Only invoke skills listed as available or explicitly typed by the user. " +
            "When a skill matches, invoke it BEFORE generating other response text about the task. " +
            "If <command-name> already appears in the turn, the skill is loaded — follow it without re-invoking."
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
    includeGoals: Boolean = true,
    includeLegacyAliases: Boolean = true,
): List<Tool> {
    val access = WorkspaceAccess(context)
    val shell = ShellTool(context, cwdProvider = cwdProvider)
    val read = ReadFileTool(context)
    val write = WriteFileTool(context)
    val edit = EditFileTool(context)
    val grep = GrepTool(context)
    val glob = GlobTool(context)
    val listDir = ListDirTool(context)
    val browse = BrowseTool(networkPolicy)
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
        inner = shell,
        name = "Bash",
        description = "Executes a bash command in the workspace shell (proot guest or remote SSH). Prefer dedicated file/search tools over cat/grep/sed.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("command") { put("type", "string") }
                putJsonObject("timeout") { put("type", "number") }
                putJsonObject("description") { put("type", "string") }
            }
            putJsonArray("required") { add("command") }
        },
        risk = ToolRisk.EXECUTE,
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
    )

    val zcode = mutableListOf(
        readZ, writeZ, editZ, bashZ, grepZ, globZ, fetchZ, webSearchZ,
        TodoReadTool(todo),
        TodoWriteTool(todo, planTool),
        EnterPlanModeTool(planMode, onPlanModeChange, requestEnterPlanApproval),
        ExitPlanModeTool(planMode, onPlanModeChange, requestExitPlanApproval),
        AskUserQuestionTool(askUser),
        ReadSessionContextTool(readSession),
        SkillTool(invokeSkill),
        listDir,
        ApplyPatchTool(context),
        GitTool(context, cwdProvider = cwdProvider),
    )
    if (includeGoals) {
        zcode += CreateGoalTool(goalState)
        zcode += UpdateGoalTool(goalState)
        zcode += GetGoalTool(goalState)
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
        "readsessioncontext", "skill", "agent", "spawn_agent",
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
