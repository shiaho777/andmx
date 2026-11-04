package com.andmx.agent.zcode

import com.andmx.agent.Tool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

const val CREATE_WORKFLOW_TOOL_NAME = "CreateWorkflow"
const val AMEND_WORKFLOW_TOOL_NAME = "AmendWorkflow"
const val SAVE_WORKFLOW_TOOL_NAME = "SaveWorkflow"
const val LIST_SAVED_WORKFLOWS_TOOL_NAME = "ListSavedWorkflows"
const val EVAL_WORKFLOW_SNIPPET_TOOL_NAME = "EvalWorkflowSnippet"
const val LIST_WORKFLOW_RUNS_TOOL_NAME = "ListWorkflowRuns"
const val GET_WORKFLOW_RUN_TOOL_NAME = "GetWorkflowRun"
const val RESUME_WORKFLOW_RUN_TOOL_NAME = "ResumeWorkflowRun"
const val RESOLVE_WORKFLOW_QUESTION_TOOL_NAME = "ResolveWorkflowQuestion"
const val FILL_WORKFLOW_HOLE_TOOL_NAME = "FillWorkflowHole"
const val SUBMIT_RESULT_TOOL_NAME = "submit_result"
const val ESCALATE_TOOL_NAME = "escalate"

val DYNAMIC_WORKFLOW_TOOL_NAMES: Set<String> = setOf(
    CREATE_WORKFLOW_TOOL_NAME,
    AMEND_WORKFLOW_TOOL_NAME,
    SAVE_WORKFLOW_TOOL_NAME,
    LIST_SAVED_WORKFLOWS_TOOL_NAME,
    "ListModels",
    EVAL_WORKFLOW_SNIPPET_TOOL_NAME,
    LIST_WORKFLOW_RUNS_TOOL_NAME,
    GET_WORKFLOW_RUN_TOOL_NAME,
    RESUME_WORKFLOW_RUN_TOOL_NAME,
    RESOLVE_WORKFLOW_QUESTION_TOOL_NAME,
    FILL_WORKFLOW_HOLE_TOOL_NAME,
)

/**
 * 动态工作流工具面的默认值。
 *
 * ZCode 的 `includeDynamicWorkflow` 是 host 运行时偏好：`registerBuiltInTools` 只有收到
 * 显式 `false` 才下架这 11 个工具，缺省值由协议服务端的 `appRuntimePreferences` 决定
 * （fail-closed）。所以「默认关闭」本身与上游一致。
 *
 * 与上游的差异在于它必须是**运行时**可改的。这里曾是一个 `const val`，导致
 * `ChatController` 里 `if (DYNAMIC_WORKFLOW_TOOLS_ENABLED)` 被编译器折叠，工具面永远不可达。
 * 现在它只是默认值，真正的开关走 `ProviderSettings.dynamicWorkflowToolsEnabled`。
 */
const val DYNAMIC_WORKFLOW_TOOLS_DEFAULT = false

const val DYNAMIC_WORKFLOW_DEFAULT_PIN =
    "Upstream includeDynamicWorkflow defaults the tools on when the flag is absent; " +
        "the protocol server's appRuntimePreferences decides the effective default and is fail-closed. " +
        "AndMX keeps the same default but resolves it at runtime from settings, not at compile time."

const val CREATE_WORKFLOW_TIMEOUT_MS = 15_000L
const val AMEND_WORKFLOW_TIMEOUT_MS = 15_000L
const val SAVE_WORKFLOW_TIMEOUT_MS = 15_000L
const val EVAL_WORKFLOW_SNIPPET_TIMEOUT_MS = 660_000L
const val LIST_WORKFLOW_RUNS_TIMEOUT_MS = 10_000L
const val GET_WORKFLOW_RUN_TIMEOUT_MS = 10_000L
const val RESUME_WORKFLOW_RUN_TIMEOUT_MS = 15_000L
const val RESOLVE_WORKFLOW_QUESTION_TIMEOUT_MS = 15_000L
const val FILL_WORKFLOW_HOLE_TIMEOUT_MS = 15_000L
const val LIST_SAVED_WORKFLOWS_TIMEOUT_MS = 10_000L

const val WORKFLOW_MODEL_BYTES = 24_000
const val GET_WORKFLOW_RUN_MODEL_BYTES = 400_000
const val SUBMIT_RESULT_MODEL_BYTES = 16_000
const val ESCALATE_MODEL_BYTES = 16_000
const val SAVED_WORKFLOW_MAX_NAME_CHARS = 64

const val CREATE_WORKFLOW_SOURCE_ERROR =
    "Provide exactly one workflow source: `script` for a one-off script written inline, `saved` to run a workflow saved in this project, or `path` for a script file on disk (the file a previous result named). Passing more than one, or none, is ambiguous."

const val CREATE_WORKFLOW_ARGS_WITHOUT_PATH_ERROR =
    "`args` belongs to the `path` source: it carries values for the arguments a script file declares in its `/* zcode-workflow` block. For a saved workflow pass `saved.args`; an inline `script` declares no arguments, so it takes none."

const val AMEND_WORKFLOW_SOURCE_ERROR =
    "Provide at most one revised script: `path` for the script file you edited (the usual form), or `script` for the whole revised script inline. Passing both is ambiguous; omit both to keep the predecessor's script unchanged."

const val SAVE_WORKFLOW_SOURCE_ERROR =
    "Provide exactly one script source: `script` for the body inline, or `script_path` for the file holding it (a draft, usually). Passing both, or neither, is ambiguous."

const val ARG_DECLARATION_ERROR =
    "`args` must be an object whose values are `{ type, description?, required?, default? }`, " +
        "with type one of string, number, boolean, json."

const val EVAL_WORKFLOW_SNIPPET_SOURCE_ERROR =
    "Provide exactly one snippet source: `code` for the snippet inline, or `path` for a file holding it. Passing both, or neither, is ambiguous."

const val FILL_WORKFLOW_HOLE_SOURCE_ERROR =
    "Provide exactly one body source: `script` for the hole's statements written inline, or `path` for a file holding them (usually the fill file a rejected attempt was saved to). Passing both, or neither, is ambiguous."

const val SNIPPET_NOT_EXECUTED_NOTE =
    "NOTE: The snippet was NOT executed — snippet evaluation is not available in this session."

const val SNIPPET_FIX_ERRORS_NOTE =
    "NOTE: The snippet was NOT executed — fix the errors above and call the tool again."

const val EVAL_WORKFLOW_SNIPPET_DEFAULT_TIMEOUT_MS = 60_000L

const val WORKFLOW_INTROSPECTION_UNAVAILABLE =
    "workflow_introspection_unavailable: this session cannot read workflow runs — workflow execution is not available here, so no run history is reachable. This is a capability gap, not an empty project."

const val WORKFLOW_RESUME_UNAVAILABLE =
    "workflow_resume_unavailable: this session cannot resume workflow runs — workflow execution is not available here. This is a capability gap, not a status problem."

const val WORKFLOW_AMEND_UNAVAILABLE =
    "workflow_amend_unavailable: this session cannot amend workflow runs — workflow execution is not available here. This is a capability gap, not a status problem."

const val WORKFLOW_FILL_UNAVAILABLE =
    "workflow_fill_unavailable: this session cannot fill workflow holes — workflow execution is not available here. This is a capability gap, not a bad hole id."

const val WORKFLOW_QUESTION_UNAVAILABLE =
    "workflow_question_answering_unavailable: this session cannot answer workflow escalations — workflow execution is not available here. This is a capability gap, not a bad question ID."

const val WORKFLOW_SAVE_UNAVAILABLE =
    "workflow_save_unavailable: this session cannot save dynamic workflows — there is no embedded workflow runner. Nothing was written."

const val WORKFLOW_SAVED_LIST_UNAVAILABLE =
    "workflow_saved_unavailable: this session cannot list saved dynamic workflows — there is no embedded workflow runner. This is a capability gap, not an empty archive."

const val WORKFLOW_RUN_INTROSPECTION_STEERING =
    "Runs this session starts settle on their own: you receive a completion notification carrying the final output. Do NOT poll this tool while waiting for one — continue with other work.\n" +
        "Reach for it when: (a) the user asks how a workflow is going, (b) you want to review this project's earlier runs, including ones other sessions started, (c) a completion notification was truncated and you need the run's full record by ID."

private const val SKILL = "dynamic-workflows"

val CREATE_WORKFLOW_DESCRIPTION = listOf(
    "Create and run a dynamic workflow: a TypeScript script that orchestrates multiple model-driven subagents with plain control flow (loops, conditionals, fan-out) and typed intermediate results. The script is typechecked, the user is asked to confirm it, and the run starts in the background; you are notified with its final result when it settles. Compilation errors come back as diagnostics.",
    "",
    "When to use:",
    "- The user explicitly asks for a workflow — \"use a workflow\", \"with a workflow\", \"使用 workflow\", \"用工作流\", or any phrasing that names workflow/工作流 as the means: this tool is mandatory. Do not substitute the Agent/Task subagent tools, do not do the work inline yourself, and do not judge the task too small for a workflow — the user chose the tool, and that choice is theirs. Size only decides how many subagents the script gets, never whether it is written.",
    "- Without such an explicit request, do not start a workflow: delegate with the Agent tool or do the work yourself, even for multi-step or multi-subagent tasks.",
    "",
    "Before writing or revising a script, load the `$SKILL` skill with the Skill tool: it carries the facade declarations the script is checked against, the authoring rules, and this tool's full contract. A call that submits a script is refused until that skill has been loaded in this session; running a saved workflow by name is exempt.",
    "",
    "Pass exactly one source: `script` (a one-off script written inline; it is saved to a draft file the result names — revise that file and resubmit with `path`, never paste the script again), `saved` (a workflow saved in this project or globally, by name; check ListSavedWorkflows before writing one from scratch), or `path` (a script file on disk, normally the file a previous result named). To change a run that already exists — errored, completed, stopped or still running — call AmendWorkflow instead of starting over.",
).joinToString("\n")

val AMEND_WORKFLOW_DESCRIPTION = listOf(
    "Amend an existing dynamic-workflow run with a revised script or revised settings. Starts a NEW run that supersedes the old one and imports its finished work as a cache, so only what you changed is paid for again. Works on ANY run of this project: completed, errored, stopped — or still running.",
    "",
    "When to use:",
    "- The run errored, or completed but needs one more stage: fix or extend the script and amend. Never rewrite the workflow from scratch with CreateWorkflow.",
    "- The run is STILL RUNNING and is visibly going wrong: amend it NOW, in one call. Do not TaskStop it first and do not wait for it to finish — this tool stops the running predecessor and starts the revision; the earlier you amend, the less is re-paid.",
    "- The user wants the same workflow with fewer subagents at once, its subagents on another model, or another name: amend with only that field and neither `path` nor `script`.",
    "- To continue a stopped run unchanged, use ResumeWorkflowRun instead.",
    "",
    "Load the `$SKILL` skill with the Skill tool before revising a script: it carries the cache rules, what each omitted field keeps, and the confirmation rule. A call that passes `path` or `script` is refused until that skill has been loaded in this session; a settings-only call is not. Pass `path` (the run's script file, edited in place — the usual form) or `script` (the whole revised script inline), never both.",
).joinToString("\n")

val SAVE_WORKFLOW_DESCRIPTION = listOf(
    "Save a dynamic-workflow script with its metadata so it can be run again later by name (CreateWorkflow's `saved` source; ListSavedWorkflows lists them). The required `scope` decides whether it lives in this project or globally.",
    "",
    "NEVER call this tool unsolicited: saving writes a file into the user's repository, and that is their decision. When a workflow you just built looks reusable, suggest saving it in one sentence and wait; call SaveWorkflow only after the user agrees, or when the user asks directly.",
    "",
    "Load the `$SKILL` skill with the Skill tool first: it carries the file format, the argument declarations and the authoring rules. The call is refused until that skill has been loaded in this session. Pass `script` (the body only) or `script_path` (a draft file, saved without re-emitting it), never both.",
).joinToString("\n")

val EVAL_WORKFLOW_SNIPPET_DESCRIPTION = listOf(
    "Compile and run a small dynamic-workflow TypeScript snippet synchronously, against the same compiler, sandbox and world-read execution path a real run uses. It is the test bench for workflow authoring: check a parser against real command output, see what a glob actually returns, or exercise a gate predicate on real repository state before putting it in a CreateWorkflow script. No agent()/report(); nothing persists; the result comes back in this call.",
    "",
    "Load the `$SKILL` skill with the Skill tool first: it carries the snippet facade and the rules. The call is refused until that skill has been loaded in this session. Pass `code` (inline) or `path` (a file holding the snippet), never both.",
).joinToString("\n")

val FILL_WORKFLOW_HOLE_DESCRIPTION = listOf(
    "Supplies the body of a hole that a RUNNING dynamic-workflow run has reached and is waiting at. Starts nothing: the run keeps its id, journal and subagents, and grows by the statements you write.",
    "",
    "- Takes run_id and hole_id — both from the hole notification (or from GetWorkflowRun's holes if that notification was lost). Only the branch parked at the hole waits; the rest of the run keeps going, and nothing times out.",
    "- Read the run's draft at the path the notification names for context: the body is compiled WHERE THE HOLE STANDS and sees every binding declared before it. Write only the statements of the hole's function — not the `hole(...)` call, not an arrow, not the script around it — and return a value of the hole's type, or end with a new tail hole named for the next step when the workflow's shape is not known yet (the skill's §15).",
    "- Rehearse fixed logic in EvalWorkflowSnippet first. A hole is filled once: a body under a loop runs on every iteration with that iteration's bindings.",
    "- On diagnostics the fill is saved to a fill file; edit that file in place and resubmit with `path` instead of pasting the body again. To change anything OUTSIDE the hole, use AmendWorkflow.",
    "",
    "Load the `$SKILL` skill with the Skill tool before writing a body: it carries the facade the body is checked against and the hole rules. A call is refused until that skill has been loaded in this session. Pass `script` (the statements inline) or `path` (a file holding them), never both.",
).joinToString("\n")

val LIST_WORKFLOW_RUNS_DESCRIPTION = listOf(
    "Lists this project's dynamic-workflow runs (the session's working directory is the project key), most recently updated first. Includes runs started by other sessions — the run journal is per-project, not per-session.",
    "",
    WORKFLOW_RUN_INTROSPECTION_STEERING,
    "",
    "- Each row gives the run ID, its label, lifecycle status, whether this session started it, tokens spent, and timestamps.",
    "- `possibly_interrupted=\"true\"` means this session cannot confirm the run is still alive: it may be a leftover from a process that exited, or a sibling session's run still in flight. It is an annotation, not a verdict — do not report it as a failure.",
    "- Pass a run ID to GetWorkflowRun for progress detail, the log tail, the final result, or the failure.",
    "- Three terminal states: `completed`; `errored` (the script itself failed); `stopped` with `stop_reason` — `user` (cancelled on purpose: resume only when the user asks), `model` (your own TaskStop), `provider` (a provider-side error stopped it: read GetWorkflowRun for the cause, resolve it with the user, then resume), `interrupted` (the owning process exited: continuing it is usually what the user wants), `superseded` (an AmendWorkflow replaced it; `superseded_by` names the live successor — never resume a superseded run).",
    "- Any stopped run other than a superseded one can be continued with ResumeWorkflowRun — no rebuild needed, same run ID, same script. An errored run cannot.",
    "- ANY run here — completed, stopped, errored, or still running — can instead be revised with AmendWorkflow: pass its run ID and the corrected script; the new run imports the old one's finished work as a warm cache (and stops it first if it is still running). A run whose script errored is the case to reach for it — fix the script instead of rewriting the workflow from scratch. `resumed_from` on a row names the run it was amended from.",
).joinToString("\n")

val GET_WORKFLOW_RUN_DESCRIPTION = listOf(
    "Returns the current state of one dynamic-workflow run: progress, token usage and the tail of its log() narration while it runs; the final result once it completed; the structured failure if it errored or was stopped.",
    "",
    WORKFLOW_RUN_INTROSPECTION_STEERING,
    "",
    "- Takes run_id — from CreateWorkflow's or AmendWorkflow's result, from a completion notification, or from ListWorkflowRuns.",
    "- This is an instant snapshot and never waits. To block until a run THIS session started finishes, use TaskOutput instead: that is the waiting tool. GetWorkflowRun is the right tool when you must not wait, or when the run belongs to another session (TaskOutput cannot see those).",
    "- The `artifacts` section lists what the run published for the user — files, documents and live dashboards that are ALREADY shown to them as cards. Refer to one by its title; do not paste its contents back. The one marked `primary` is the deliverable: point the user to it first.",
    "- Three terminal states: `completed`; `errored` (the script itself failed — not resumable, amend it); `stopped` with a stop reason — `user` (cancelled on purpose: resume only when the user asks), `model` (your own TaskStop), `provider` (a provider-side error such as an expired sign-in, a model missing from the plan or a quota cap — the `<error>` block names the cause and the fix; resolve it with the user, then resume), `interrupted` (the process that owned the run exited — continuing it is usually what the user wants), `superseded` (an AmendWorkflow replaced it; `<superseded_by>` names the successor — read that run instead, never resume this one).",
    "- A stopped run (other than a superseded one) can be continued with ResumeWorkflowRun — no rebuild needed, same run ID, same script.",
    "- ANY run — completed, stopped, errored, or still running — can instead be revised with AmendWorkflow: pass its run ID and the corrected script, and the finished work is imported as cache. When the script itself errored, that is the move — fix the script and keep the work that already succeeded, rather than rewriting from scratch.",
).joinToString("\n")

val RESUME_WORKFLOW_RUN_DESCRIPTION = listOf(
    "Resumes a dynamic-workflow run whose status is `stopped` — the user cancelled it, you stopped it with TaskStop, a provider-side error stopped it (expired sign-in, model not in the plan, quota cap), or the process that owned it exited (`interrupted`). The run continues under the same run ID: finished steps are replayed from the journal without spending tokens, unfinished steps are dispatched again. The one stopped run that is NOT resumable is a `superseded` one: an AmendWorkflow replaced it, and its successor is the live run.",
    "",
    "- Takes run_id — from CreateWorkflow's or AmendWorkflow's result, from a completion notification, or from GetWorkflowRun / ListWorkflowRuns.",
    "- The resumed run is backgrounded: you will be notified with the final output when it completes. Do not wait for it or poll it with TaskOutput; continue with other work unless the user asked you to wait.",
    "- An `errored` run (the script itself failed) is NOT resumable — replaying it would fail the same way. Fix the script and submit it with AmendWorkflow instead. A completed run is not resumable either; a `superseded` run is refused with the successor's ID.",
    "- Stop reason `user` means the user stopped it on purpose: resume it only when the user asks you to; never resume a run the user just cancelled on your own initiative. Reason `model` is your own TaskStop. Reason `provider` means a provider-side error stopped it: resolve the cause with the user first (the stop notification names it), then resume. Reason `interrupted` (the process died) is different: continuing it is usually what the user wants.",
).joinToString("\n")

val RESOLVE_WORKFLOW_QUESTION_DESCRIPTION = listOf(
    "Answers a blocking question that a subagent escalated from inside a RUNNING dynamic-workflow run.",
    "",
    "- Takes question_id — the ID from the escalation notification (it looks like `dwfq-...`). If that notification was lost, GetWorkflowRun lists the questions a run still owes an answer to.",
    "- The run keeps running the whole time: only the subagent that asked is parked on its call, while every other subagent and the script's control flow keep going. Your answer becomes that call's result verbatim and the subagent continues from there.",
    "- Answer directly and actionably. If you are not sure, look at the run first with GetWorkflowRun, or ask the user with AskUserQuestion, then come back and answer — nothing answers on your behalf, and the subagent waits indefinitely.",
    "- If the question reveals the SCRIPT is structurally broken (a broken gate, wrong control flow), a sentence cannot fix that: cancel the run and continue with a revised script via CreateWorkflow's `resume_from`.",
).joinToString("\n")

val LIST_SAVED_WORKFLOWS_DESCRIPTION = listOf(
    "Lists the dynamic workflows saved in this project (`.zcode/workflows/`, keyed on the session's working directory) and the global archive (`~/.zcode/workflows`, available from every project). These are workflow DEFINITIONS you can run, not past runs — for the run history use ListWorkflowRuns instead.",
    "",
    "- Each row gives the name, what the workflow does, when to reach for it, and the arguments it takes.",
    "- Run one by passing its name to CreateWorkflow as `saved: { name, args }`. The user still confirms the run.",
    "- Check here before writing a workflow from scratch: if the project already saved one that fits, running it beats rebuilding it.",
    "- `invalid` lists saved files that could not be read (usually a hand-edited metadata block). They are named so they can be fixed, not silently skipped.",
).joinToString("\n")

val SUBMIT_RESULT_DESCRIPTION =
    "Submit the structured result for the current ask. The required JSON shape is described in the ask instructions."

const val SUBMIT_RESULT_ACCEPTED = "The result was accepted."

val ESCALATE_DESCRIPTION = listOf(
    "Escalates a question that is BLOCKING you to the main agent that created this workflow, and waits here for the answer.",
    "",
    "This is a LAST RESORT, for when you are genuinely stuck on something outside your reach:",
    "- a gate that is broken or impossible to pass (a check capped at 95 when the threshold is 96);",
    "- instructions that contradict each other, so no output can satisfy both;",
    "- a fact you cannot obtain (a tradeoff, an external convention, what 'good' means here) that only whoever started this run knows.",
    "",
    "Do NOT use it for curiosity, progress reports, asking permission, confirming a conclusion you could verify yourself, or thinking out loud. None of those are blocked — keep working.",
    "",
    "Ask ONE focused question that can be answered in a sentence, and put your evidence in `context`: what you already tried, and exactly where you are stuck. The quality of the answer depends on it.",
    "",
    "The cost: this call BLOCKS until the main agent answers, which may take a long time. You get at most 3 escalations per ask; the 4th tells you the budget is spent and to proceed on your own best judgement. Do not spend them on questions not worth waiting for.",
).joinToString("\n")

fun workflowSkillRefusal(toolName: String): String =
    "$toolName needs the `$SKILL` skill loaded in this session before it accepts a script. " +
        "Call the Skill tool with skill \"$SKILL\" first — it carries the arguments this tool accepts and states that nothing is typechecked or started — " +
        "then call $toolName again. Nothing was started."

fun applyDynamicWorkflowGate(
    tools: List<Tool>,
    enabled: Boolean = DYNAMIC_WORKFLOW_TOOLS_DEFAULT,
): List<Tool> {
    if (enabled) return tools
    return tools.filter { it.name !in DYNAMIC_WORKFLOW_TOOL_NAMES }
}

private fun dwfSchema(
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

private fun str(description: String, min: Int? = null, max: Int? = null): JsonObject = buildJsonObject {
    put("type", "string")
    if (min != null) put("minLength", min)
    if (max != null) put("maxLength", max)
    put("description", description)
}

private fun positiveInt(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("exclusiveMinimum", 0)
    put("description", description)
}

private fun nullablePositiveInt(description: String): JsonObject = buildJsonObject {
    put("description", description)
    put("oneOf", buildJsonArray {
        add(buildJsonObject {
            put("type", "integer")
            put("exclusiveMinimum", 0)
        })
        add(buildJsonObject { put("type", "null") })
    })
}

private fun nullableMinString(description: String): JsonObject = buildJsonObject {
    put("description", description)
    put("oneOf", buildJsonArray {
        add(buildJsonObject {
            put("type", "string")
            put("minLength", 1)
        })
        add(buildJsonObject { put("type", "null") })
    })
}

private fun recordUnknown(description: String): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", buildJsonObject { })
    putJsonObject("propertyNames") { put("type", "string") }
    put("description", description)
}

private fun scopeEnum(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    putJsonArray("enum") {
        add("project")
        add("global")
    }
    put("description", description)
}

private val savedSourceSchema = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put(
        "description",
        "A saved workflow to run, by name. Exactly one of `script`, `saved` and `path`.",
    )
    putJsonObject("properties") {
        put(
            "name",
            str("Name of a saved workflow (see ListSavedWorkflows).", min = 1, max = SAVED_WORKFLOW_MAX_NAME_CHARS),
        )
        put("args", recordUnknown("Values for the arguments the saved workflow declares."))
        put("path", str("Resolved path of the saved file.", min = 1))
        put("scope", scopeEnum("Which archive to take it from; omit for the normal lookup order."))
    }
    putJsonArray("required") { add("name") }
}

val CREATE_WORKFLOW_PARAMETERS = dwfSchema {
    put(
        "name",
        str("Short run label in the user's language. Always pass it for an inline script; defaults to the saved workflow's name.", min = 1),
    )
    put("script", str("The whole workflow script, inline. Exactly one of `script`, `saved` and `path`."))
    put("saved", savedSourceSchema)
    put(
        "path",
        str(
            "A script file on disk (relative or absolute), usually the file a previous result named. Exactly one of `script`, `saved` and `path`.",
            min = 1,
        ),
    )
    put("args", recordUnknown("Values for the arguments a `path` file declares. Only with `path`."))
    put(
        "max_concurrency",
        positiveInt("Subagents working at once, above or below the default. Only when the user asks for more or less parallelism; never as a reaction to provider errors."),
    )
    put(
        "subagent_model",
        str(
            "Model for the subagents (`providerId/modelId` or a model id). Only when the user asks, and never a model under another provider the user did not name — ask them first if one would help; you stay on the session model.",
            min = 1,
        ),
    )
}

val AMEND_WORKFLOW_PARAMETERS = dwfSchema(required = listOf("run_id")) {
    put(
        "run_id",
        str("ID of the run to amend (from a result, a notification, GetWorkflowRun or ListWorkflowRuns).", min = 1),
    )
    put(
        "script",
        str("The whole revised script, inline. This OR `path`, never both; omit both to keep the predecessor's script.", min = 1),
    )
    put(
        "path",
        str("The revised script's file, usually the predecessor's own script file edited in place. This OR `script`, never both.", min = 1),
    )
    put("name", str("Display label for the new run; defaults to the predecessor's.", min = 1))
    put(
        "max_concurrency",
        nullablePositiveInt("Omit to keep the predecessor's limit, null to return to the default, a number to set one above or below it (only when the user asks)."),
    )
    put(
        "subagent_model",
        nullableMinString("Omit to keep the predecessor's choice, null for the session model, a model id to set one (only when the user asks, never a model under another provider the user did not name — ask them first if one would help)."),
    )
}

private val argDeclarationSchema = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    putJsonObject("properties") {
        put(
            "type",
            buildJsonObject {
                put("type", "string")
                putJsonArray("enum") {
                    add("string")
                    add("number")
                    add("boolean")
                    add("json")
                }
                put("description", "Value type. \"json\" accepts any JSON value without further checking.")
            },
        )
        put("description", str("What this argument means, for whoever calls the workflow later."))
        putJsonObject("required") {
            put("type", "boolean")
            put("description", "When true the workflow cannot run without this argument.")
        }
        putJsonObject("default") {
            put("description", "Value used when the caller omits this argument.")
        }
    }
    putJsonArray("required") { add("type") }
}

val SAVE_WORKFLOW_PARAMETERS = dwfSchema(required = listOf("name", "description", "scope")) {
    put("name", str("File-safe identifier: letters, digits, dot, dash and underscore.", min = 1, max = SAVED_WORKFLOW_MAX_NAME_CHARS))
    put("description", str("One line saying what the workflow does.", min = 1))
    put("whenToUse", str("When this workflow is the right answer.", min = 1))
    put(
        "args",
        buildJsonObject {
            put("type", "object")
            put("additionalProperties", argDeclarationSchema)
            putJsonObject("propertyNames") { put("type", "string") }
            put("description", "Argument declarations the script reads off `args`.")
        },
    )
    put("script", str("The script body, inline, without a metadata block. This OR `script_path`, never both."))
    put(
        "script_path",
        str("A file holding the script body, usually a draft a result named. This OR `script`, never both.", min = 1),
    )
    put(
        "scope",
        scopeEnum("\"project\" when the script depends on this repository; \"global\" when it depends on nothing in it. Required, no default."),
    )
    put(
        "run_id",
        str("Provenance only: the id of the run this workflow was distilled from, when saving a workflow that just ran. Pass it whenever you know it; it does not change what is saved.", min = 1),
    )
    putJsonObject("path") {
        put("type", "string")
        put("minLength", 1)
    }
    putJsonObject("overwrite") { put("type", "boolean") }
    putJsonObject("shadowing") {
        put("type", "string")
        putJsonArray("enum") {
            add("hides_global")
            add("hidden_by_project")
        }
    }
}

val EVAL_WORKFLOW_SNIPPET_PARAMETERS = dwfSchema {
    put("code", str("The snippet, inline. This OR `path`, never both.", min = 1))
    put("path", str("A file holding the snippet, read whole. This OR `code`, never both.", min = 1))
    putJsonObject("timeoutMs") {
        put("type", "integer")
        put("minimum", 1_000)
        put("maximum", 600_000)
        put("description", "Wall clock for the whole snippet, in ms. Default 60000, at most 600000.")
    }
}

val LIST_WORKFLOW_RUNS_PARAMETERS = dwfSchema {
    putJsonObject("limit") {
        put("type", "integer")
        put("minimum", 1)
        put("maximum", 50)
        put("default", 20)
        put(
            "description",
            "Maximum number of runs to return (1-50, default 20). Most recently updated runs come first.",
        )
    }
    putJsonObject("statuses") {
        put("type", "array")
        put(
            "description",
            "Optional status filter. Omit to see every run in this project. Pass [\"running\", \"pending\"] to see only what is still in flight.",
        )
        putJsonObject("items") {
            put("type", "string")
            putJsonArray("enum") {
                add("completed")
                add("errored")
                add("pending")
                add("running")
                add("stopped")
            }
        }
    }
}

val GET_WORKFLOW_RUN_PARAMETERS = dwfSchema(required = listOf("run_id")) {
    put("run_id", str("The workflow run ID to inspect (as returned by CreateWorkflow or ListWorkflowRuns)", min = 1))
}

val RESUME_WORKFLOW_RUN_PARAMETERS = dwfSchema(required = listOf("run_id")) {
    put(
        "run_id",
        str(
            "The workflow run ID to resume — a cancelled run or one that failed with code `Interrupted`, as seen with GetWorkflowRun or ListWorkflowRuns",
            min = 1,
        ),
    )
}

val RESOLVE_WORKFLOW_QUESTION_PARAMETERS = dwfSchema(required = listOf("question_id", "answer")) {
    put(
        "question_id",
        str("The question ID from the escalation notification, or from GetWorkflowRun's pending questions (looks like `dwfq-...`)", min = 1),
    )
    put("answer", str("The answer text, delivered verbatim to the subagent that asked", min = 1))
}

val FILL_WORKFLOW_HOLE_PARAMETERS = dwfSchema(required = listOf("run_id", "hole_id")) {
    put("run_id", str("ID of the run that is waiting at the hole (from the hole notification).", min = 1))
    put(
        "hole_id",
        str("The hole's site id from the notification or from GetWorkflowRun's holes, e.g. `hole#21b40fca`.", min = 1),
    )
    put(
        "script",
        str(
            "The body: only the statements of the hole's function, inline. This OR `path`, never both. It is compiled where the hole stands and sees every binding declared before it; it must return the hole's type.",
            min = 1,
        ),
    )
    put(
        "path",
        str(
            "A file holding those statements — usually the fill file named by a rejected attempt, edited in place. This OR `script`, never both.",
            min = 1,
        ),
    )
}

val LIST_SAVED_WORKFLOWS_PARAMETERS = dwfSchema { }

val SUBMIT_RESULT_PARAMETERS = dwfSchema(required = listOf("result")) {
    putJsonObject("result") {
        put(
            "description",
            "The structured result for this ask, matching the JSON schema given in the ask instructions.",
        )
    }
}

val ESCALATE_PARAMETERS = dwfSchema(required = listOf("question")) {
    put("question", str("The single focused question that unblocks you. One question per call, answerable in a sentence.", min = 1))
    put("context", str("What you already tried and where exactly you are stuck — the evidence the answerer needs."))
}
