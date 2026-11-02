package com.andmx.agent.zcode

import com.andmx.agent.LoadedSkills
import com.andmx.agent.Tool
import com.andmx.agent.ToolResult
import com.andmx.agent.ToolRisk
import com.andmx.agent.workflow.CreateWorkflowSource
import com.andmx.agent.workflow.SavedWorkflowStore
import com.andmx.agent.workflow.ScriptLocation
import com.andmx.agent.workflow.WorkflowNodeStatus
import com.andmx.agent.workflow.WorkflowRunSnapshot
import com.andmx.agent.workflow.WorkflowRunStatus
import com.andmx.agent.workflow.WorkflowScriptFrontmatter
import com.andmx.agent.workflow.WorkflowScriptNotes
import com.andmx.agent.workflow.WorkflowService
import com.andmx.agent.workflow.script.WorkflowAnalyzerUnavailableException
import com.andmx.agent.workflow.script.WorkflowScriptAnalyzer
import com.andmx.data.WorkflowStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

class WorkflowTools(
    private val isAutomationTurn: suspend () -> Boolean = { false },
    private val hasLoadedSkill: suspend (String) -> Boolean = { true },
    /** dwf run 端口。为 null 时只有声明可用，内省类工具返回不可用。 */
    private val service: WorkflowService? = null,
    /** 保存的定义库，落在 `.zcode/workflows` 下的 `.dwf.ts` 文件。 */
    private val savedWorkflows: SavedWorkflowStore? = null,
    /** 读一个 guest 文件；`script_path` 源用它。 */
    private val readText: suspend (String) -> String? = { null },
    /** 脚本静态分析器。为 null 时 `CreateWorkflow` 拒绝执行——编不过的检查不能省。 */
    private val analyzer: WorkflowScriptAnalyzer? = null,
    private val conversationId: Long = 0L,
    private val cwd: () -> String = { "/" },
) {
    abstract class Declaration(
        override val name: String,
        override val description: String,
        override val parameters: JsonObject,
        override val readOnly: Boolean,
        override val concurrentSafe: Boolean,
        override val needsApproval: Boolean,
        override val timeoutMs: Long?,
        override val maxOutputBytes: Int,
        override val sideEffectScope: String,
        override val riskLevel: String,
        override val permissionName: String,
        override val alwaysAsk: Boolean = false,
        override val stopTurnOnSuccess: Boolean = false,
        override val destructive: Boolean = false,
    ) : Tool {
        override val risk: ToolRisk = if (readOnly) ToolRisk.READ else ToolRisk.EXECUTE
        override val shouldDefer = true
    }

    private fun absent(message: String) = ToolResult(message, isError = true)

    /**
     * 三源合流点。上游 `create-workflow-source.ts` 的 Kotlin 对应物——唯一一次读盘就在它里面，
     * 所以它必须是单一入口：放回 handler 里，确认窗迟早会开始展示与将要执行的东西不同的字节。
     */
    private val createSource: CreateWorkflowSource by lazy {
        CreateWorkflowSource(store = savedWorkflows, readText = readText, cwd = cwd)
    }

    private suspend fun automationBlocked(toolName: String): ToolResult? {
        if (!isAutomationTurn()) return null
        return absent("$toolName is not allowed while running a scheduled automation.")
    }

    private suspend fun skillRefusal(toolName: String): ToolResult? {
        if (hasLoadedSkill(LoadedSkills.DYNAMIC_WORKFLOWS)) return null
        return absent(workflowSkillRefusal(toolName))
    }

    private fun present(args: JsonObject, key: String): Boolean {
        val value = args[key] ?: return false
        if (value is JsonNull) return false
        if (value is JsonPrimitive && value.contentOrNull.isNullOrBlank()) return false
        return true
    }

    private fun text(args: JsonObject, key: String): String? =
        args[key]?.let { it as? JsonPrimitive }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    /** 不 trim 的版本：脚本正文要逐字节落盘，尾部换行是有意义的。 */
    private fun rawText(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    inner class Create : Declaration(
        name = CREATE_WORKFLOW_TOOL_NAME,
        description = CREATE_WORKFLOW_DESCRIPTION,
        parameters = CREATE_WORKFLOW_PARAMETERS,
        readOnly = false,
        concurrentSafe = true,
        needsApproval = true,
        timeoutMs = CREATE_WORKFLOW_TIMEOUT_MS,
        maxOutputBytes = WORKFLOW_MODEL_BYTES,
        sideEffectScope = "none",
        riskLevel = "low",
        permissionName = "createWorkflow",
        alwaysAsk = true,
    ) {
        override suspend fun execute(args: JsonObject): ToolResult {
            automationBlocked(name)?.let { return it }
            val savedOnly = present(args, "saved") && !present(args, "script") && !present(args, "path")
            if (!savedOnly) skillRefusal(name)?.let { return it }
            createSource.validateSource(args)?.let { return absent(it) }

            val input = when (val resolved = createSource.resolve(args)) {
                is CreateWorkflowSource.Result.Failure -> return absent(resolved.message)
                is CreateWorkflowSource.Result.Ok -> resolved.input
            }

            val scriptAnalyzer = analyzer
                ?: return absent(
                    "This session has no workflow script analyzer, so the script cannot be typechecked. " +
                        "Nothing was executed.",
                )

            val analysis = try {
                scriptAnalyzer.analyze(input.script)
            } catch (e: WorkflowAnalyzerUnavailableException) {
                return absent("The workflow script could not be typechecked: ${e.message} Nothing was executed.")
            }

            // 诊断按**文件**行报出来时行号要能直接粘进一次 Edit；`path` 来源是唯一有文件的来源
            // （saved 的工作副本尚未落盘，内联脚本没有文件）。
            val location = input.path?.let {
                ScriptLocation(
                    kind = ScriptLocation.Kind.PATH,
                    described = WorkflowScriptNotes.describePath(it, cwd()),
                    lineOffset = input.scriptLineOffset,
                )
            }

            if (!analysis.ok) {
                val saved = input.saved
                val headline = if (saved == null) {
                    "The workflow script has errors:"
                } else {
                    "The saved workflow '${saved.name}' (${saved.path}) has errors:"
                }
                val note = when {
                    location != null -> WorkflowScriptNotes.scriptFileNote(location)
                    saved != null -> WorkflowScriptNotes.SAVED_FILE_NEEDS_FIXING
                    else -> WorkflowScriptNotes.DIAGNOSTICS_NOT_EXECUTED
                }
                return ToolResult(
                    buildString {
                        append(headline)
                        WorkflowScriptNotes.formatDiagnosticLines(analysis.diagnostics, location)
                            .forEach { append('\n').append(it) }
                        append("\n\n").append(note)
                    },
                    isError = true,
                )
            }

            return ToolResult(
                buildString {
                    append("The workflow script compiles: no diagnostics.")
                    append(
                        when {
                            input.saved != null -> " Source: saved workflow '${input.saved.name}' (${input.saved.path})."
                            input.path != null -> " Source: ${location?.described ?: input.path}."
                            else -> " Source: inline script."
                        },
                    )
                    append(WorkflowScriptNotes.concurrencyLimitSentence(input.maxConcurrency, null))
                    analysis.declaredArtifacts.takeIf { it.isNotEmpty() }?.let { artifacts ->
                        append("\nArtifacts declared: ")
                        append(artifacts.joinToString(", ") { "${it.id} (${it.kind})" })
                    }
                    analysis.modelReferences.takeIf { it.isNotEmpty() }?.let { models ->
                        val names = models.map { it.name }.distinct()
                        append("\nModels named by the script: ")
                        append(names.joinToString(", "))
                    }
                    analysis.siteGraph?.let { graph ->
                        append("\nSites: ${graph.nodes.size} node(s) across ${graph.actors.size} actor(s).")
                    }
                    append("\n\n").append(WorkflowScriptNotes.EXECUTION_UNAVAILABLE)
                },
            )
        }
    }

    inner class Amend : Declaration(
        name = AMEND_WORKFLOW_TOOL_NAME,
        description = AMEND_WORKFLOW_DESCRIPTION,
        parameters = AMEND_WORKFLOW_PARAMETERS,
        readOnly = false,
        concurrentSafe = true,
        needsApproval = true,
        timeoutMs = AMEND_WORKFLOW_TIMEOUT_MS,
        maxOutputBytes = WORKFLOW_MODEL_BYTES,
        sideEffectScope = "none",
        riskLevel = "low",
        permissionName = "createWorkflow",
        alwaysAsk = true,
    ) {
        override suspend fun execute(args: JsonObject): ToolResult {
            automationBlocked(name)?.let { return it }
            if (present(args, "script") || present(args, "path")) skillRefusal(name)?.let { return it }
            if (text(args, "run_id") == null) return absent("run_id is required")
            if (present(args, "script") && present(args, "path")) return absent(AMEND_WORKFLOW_SOURCE_ERROR)
            return absent(WORKFLOW_AMEND_UNAVAILABLE)
        }
    }

    inner class Save : Declaration(
        name = SAVE_WORKFLOW_TOOL_NAME,
        description = SAVE_WORKFLOW_DESCRIPTION,
        parameters = SAVE_WORKFLOW_PARAMETERS,
        readOnly = false,
        concurrentSafe = false,
        needsApproval = true,
        timeoutMs = SAVE_WORKFLOW_TIMEOUT_MS,
        maxOutputBytes = WORKFLOW_MODEL_BYTES,
        sideEffectScope = "workspace",
        riskLevel = "medium",
        permissionName = "saveWorkflow",
        alwaysAsk = true,
    ) {
        override suspend fun execute(args: JsonObject): ToolResult {
            automationBlocked(name)?.let { return it }
            skillRefusal(name)?.let { return it }
            val workflowName = text(args, "name")
                ?: return absent("name is required")
            if (!workflowName.matches(Regex("^[A-Za-z0-9_.-]{1,$SAVED_WORKFLOW_MAX_NAME_CHARS}$"))) {
                return absent(
                    "'$workflowName' is not a usable workflow name: names may only contain letters, digits, '.', '-' and '_', and must be 1-$SAVED_WORKFLOW_MAX_NAME_CHARS characters.",
                )
            }
            val description = text(args, "description")
                ?: return absent("description is required")
            val scopeText = text(args, "scope")
                ?: return absent("scope is required")
            val store = savedWorkflows ?: return absent(WORKFLOW_SAVE_UNAVAILABLE)
            val scope = SavedWorkflowStore.Scope.from(scopeText)
                ?: return absent("scope must be \"project\" or \"global\", not \"$scopeText\"")

            val sources = listOf("script", "script_path").count { present(args, it) }
            if (sources != 1) return absent(SAVE_WORKFLOW_SOURCE_ERROR)
            val script = if (present(args, "script")) {
                rawText(args, "script") ?: return absent("`script` must be a non-empty string")
            } else {
                val scriptPath = text(args, "script_path")
                    ?: return absent("`script_path` must be a non-empty string")
                readText(scriptPath)
                    ?: return absent("could not read the script at `$scriptPath`")
            }

            val declarations = parseArgDeclarations(args["args"])
                ?: return absent(ARG_DECLARATION_ERROR)

            if (!present(args, "overwrite") && store.exists(workflowName, scope)) {
                return absent(
                    "A workflow named `$workflowName` already exists at " +
                        "${store.path(store.root(scope), workflowName)}. " +
                        "Pass `overwrite: true` to replace it.",
                )
            }

            val meta = WorkflowScriptFrontmatter.Meta(
                description = description,
                whenToUse = text(args, "whenToUse"),
                args = declarations,
            )
            val saved = store.save(workflowName, meta, script, scope)
            val shadowing = store.shadowing(workflowName, scope)
            return ToolResult(
                buildString {
                    append("Saved workflow `$workflowName` to ${saved.path} (${saved.scope.wire}, ")
                    append(if (saved.overwritten) "replaced" else "created").append(").\n")
                    when (shadowing) {
                        SavedWorkflowStore.Shadowing.HIDES_GLOBAL ->
                            append("This project-scoped definition hides the global one of the same name.\n")
                        SavedWorkflowStore.Shadowing.HIDDEN_BY_PROJECT ->
                            append("A project-scoped definition of the same name hides this one in this project.\n")
                        null -> Unit
                    }
                    append("Nothing was typechecked or started; run it with CreateWorkflow `saved`.")
                },
            )
        }
    }

    inner class Eval : Declaration(
        name = EVAL_WORKFLOW_SNIPPET_TOOL_NAME,
        description = EVAL_WORKFLOW_SNIPPET_DESCRIPTION,
        parameters = EVAL_WORKFLOW_SNIPPET_PARAMETERS,
        readOnly = false,
        concurrentSafe = true,
        needsApproval = true,
        timeoutMs = EVAL_WORKFLOW_SNIPPET_TIMEOUT_MS,
        maxOutputBytes = WORKFLOW_MODEL_BYTES,
        sideEffectScope = "workspace",
        riskLevel = "low",
        permissionName = "evalWorkflowSnippet",
    ) {
        override suspend fun execute(args: JsonObject): ToolResult {
            skillRefusal(name)?.let { return it }
            val sources = listOf("code", "path").count { present(args, it) }
            if (sources != 1) return absent(EVAL_WORKFLOW_SNIPPET_SOURCE_ERROR)
            return ToolResult(SNIPPET_NOT_EXECUTED_NOTE, isError = false)
        }
    }

    inner class ListRuns : Declaration(
        name = LIST_WORKFLOW_RUNS_TOOL_NAME,
        description = LIST_WORKFLOW_RUNS_DESCRIPTION,
        parameters = LIST_WORKFLOW_RUNS_PARAMETERS,
        readOnly = true,
        concurrentSafe = true,
        needsApproval = false,
        timeoutMs = LIST_WORKFLOW_RUNS_TIMEOUT_MS,
        maxOutputBytes = WORKFLOW_MODEL_BYTES,
        sideEffectScope = "none",
        riskLevel = "low",
        permissionName = "listWorkflowRuns",
    ) {
        override suspend fun execute(args: JsonObject): ToolResult {
            val svc = service ?: return absent(WORKFLOW_INTROSPECTION_UNAVAILABLE)
            val runs = svc.listRuns()
            if (runs.isEmpty()) return ToolResult("No workflow runs in this project yet.")
            return ToolResult(renderRuns(runs))
        }
    }

    inner class GetRun : Declaration(
        name = GET_WORKFLOW_RUN_TOOL_NAME,
        description = GET_WORKFLOW_RUN_DESCRIPTION,
        parameters = GET_WORKFLOW_RUN_PARAMETERS,
        readOnly = true,
        concurrentSafe = true,
        needsApproval = false,
        timeoutMs = GET_WORKFLOW_RUN_TIMEOUT_MS,
        maxOutputBytes = GET_WORKFLOW_RUN_MODEL_BYTES,
        sideEffectScope = "none",
        riskLevel = "low",
        permissionName = "getWorkflowRun",
    ) {
        override suspend fun execute(args: JsonObject): ToolResult {
            val runId = text(args, "run_id") ?: return absent("run_id is required")
            val svc = service ?: return absent(WORKFLOW_INTROSPECTION_UNAVAILABLE)
            val snapshot = svc.getRun(runId)
                ?: return absent("Workflow run $runId was not found.")
            return ToolResult(renderRun(snapshot))
        }
    }

    inner class Resume : Declaration(
        name = RESUME_WORKFLOW_RUN_TOOL_NAME,
        description = RESUME_WORKFLOW_RUN_DESCRIPTION,
        parameters = RESUME_WORKFLOW_RUN_PARAMETERS,
        readOnly = false,
        concurrentSafe = true,
        needsApproval = false,
        timeoutMs = RESUME_WORKFLOW_RUN_TIMEOUT_MS,
        maxOutputBytes = WORKFLOW_MODEL_BYTES,
        sideEffectScope = "none",
        riskLevel = "low",
        permissionName = "resumeWorkflowRun",
    ) {
        override suspend fun execute(args: JsonObject): ToolResult {
            automationBlocked(name)?.let { return it }
            if (text(args, "run_id") == null) return absent("run_id is required")
            return absent(WORKFLOW_RESUME_UNAVAILABLE)
        }
    }

    inner class Resolve : Declaration(
        name = RESOLVE_WORKFLOW_QUESTION_TOOL_NAME,
        description = RESOLVE_WORKFLOW_QUESTION_DESCRIPTION,
        parameters = RESOLVE_WORKFLOW_QUESTION_PARAMETERS,
        readOnly = false,
        concurrentSafe = true,
        needsApproval = false,
        timeoutMs = RESOLVE_WORKFLOW_QUESTION_TIMEOUT_MS,
        maxOutputBytes = WORKFLOW_MODEL_BYTES,
        sideEffectScope = "none",
        riskLevel = "low",
        permissionName = "resolveWorkflowQuestion",
    ) {
        override suspend fun execute(args: JsonObject): ToolResult {
            if (text(args, "question_id") == null) return absent("question_id is required")
            if (text(args, "answer") == null) return absent("answer is required")
            return absent(WORKFLOW_QUESTION_UNAVAILABLE)
        }
    }

    inner class Fill : Declaration(
        name = FILL_WORKFLOW_HOLE_TOOL_NAME,
        description = FILL_WORKFLOW_HOLE_DESCRIPTION,
        parameters = FILL_WORKFLOW_HOLE_PARAMETERS,
        readOnly = false,
        concurrentSafe = true,
        needsApproval = true,
        timeoutMs = FILL_WORKFLOW_HOLE_TIMEOUT_MS,
        maxOutputBytes = WORKFLOW_MODEL_BYTES,
        sideEffectScope = "none",
        riskLevel = "low",
        permissionName = "createWorkflow",
        alwaysAsk = true,
    ) {
        override suspend fun execute(args: JsonObject): ToolResult {
            automationBlocked(name)?.let { return it }
            skillRefusal(name)?.let { return it }
            if (text(args, "run_id") == null) return absent("run_id is required")
            if (text(args, "hole_id") == null) return absent("hole_id is required")
            val sources = listOf("script", "path").count { present(args, it) }
            if (sources != 1) return absent(FILL_WORKFLOW_HOLE_SOURCE_ERROR)
            return absent(WORKFLOW_FILL_UNAVAILABLE)
        }
    }

    inner class ListSaved : Declaration(
        name = LIST_SAVED_WORKFLOWS_TOOL_NAME,
        description = LIST_SAVED_WORKFLOWS_DESCRIPTION,
        parameters = LIST_SAVED_WORKFLOWS_PARAMETERS,
        readOnly = true,
        concurrentSafe = true,
        needsApproval = false,
        timeoutMs = LIST_SAVED_WORKFLOWS_TIMEOUT_MS,
        maxOutputBytes = WORKFLOW_MODEL_BYTES,
        sideEffectScope = "none",
        riskLevel = "low",
        permissionName = "listSavedWorkflows",
    ) {
        override suspend fun execute(args: JsonObject): ToolResult {
            val store = savedWorkflows ?: return absent(WORKFLOW_SAVED_LIST_UNAVAILABLE)
            val listed = store.list()
            if (listed.entries.isEmpty() && listed.invalid.isEmpty()) {
                return ToolResult("No saved workflows in this project yet.")
            }
            return ToolResult(renderSaved(listed))
        }
    }

    private fun renderRuns(runs: List<WorkflowStore.RunListItem>): String = buildString {
        append("Workflow runs (${runs.size}):\n")
        runs.forEach { run ->
            append("- ${run.runId} | ${run.status} | ${run.kind} | updated ${iso(run.updatedAtMs)}\n")
            append("  task: ${run.task.lineSequence().first().take(160)}\n")
            run.definitionId.takeIf { it.isNotBlank() }?.let { append("  definition: $it\n") }
        }
    }

    private fun renderRun(s: WorkflowRunSnapshot): String = buildString {
        append("Workflow run ${s.runId}\n")
        append("Status: ${s.status}\n")
        append("Kind: ${s.kind}\n")
        append("Task: ${s.task}\n")
        append("Directory: ${s.cwd}\n")
        s.definitionId?.let { append("Definition: $it${s.definitionVersion?.let { v -> " (v$v)" } ?: ""}\n") }
        s.currentPhase?.let { append("Current phase: $it\n") }
        append("Updated: ${s.updatedAt}\n")

        if (s.phaseOrder.isNotEmpty()) {
            val byPhase = s.phases.associateBy { it.phase }
            append("\nPhases:\n")
            s.phaseOrder.forEach { id ->
                val phase = byPhase[id]
                val marker = when (phase?.status) {
                    WorkflowNodeStatus.completed -> "[x]"
                    WorkflowNodeStatus.active -> ">"
                    WorkflowNodeStatus.failed -> "[!]"
                    WorkflowNodeStatus.cancelled -> "[~]"
                    else -> "-"
                }
                append("  $marker $id: ${phase?.status ?: WorkflowNodeStatus.pending}")
                phase?.error?.let { append(" ($it)") }
                append("\n")
            }
        }

        s.failure?.let {
            append("\nFailure: ${it.kind} — ${it.message}")
            append(" (recoverable=${it.recoverable}, retryable=${it.retryable})\n")
        }
        s.pauseReason?.let { append("\nPaused: $it\n") }
        if (s.recoveryActions.isNotEmpty()) {
            append("\nRecovery actions:\n")
            s.recoveryActions.forEach { append("  - ${it.action}: ${it.label}\n") }
        }
        if (s.artifacts.isNotEmpty()) {
            append("\nArtifacts:\n")
            s.artifacts.forEach { append("  - ${it.label}: ${it.path}\n") }
        }
        s.reportPath?.let { append("\nReport: $it\n") }
    }

    private fun renderSaved(listed: SavedWorkflowStore.ListResult): String = buildString {
        if (listed.entries.isNotEmpty()) {
            append("Saved workflows (${listed.entries.size}):\n")
            listed.entries.forEach { entry ->
                append("- ${entry.name} | ${entry.scope.wire} | ${entry.path}\n")
                append("  ${entry.description}\n")
                entry.whenToUse?.let { append("  when to use: $it\n") }
                if (entry.args.isNotEmpty()) {
                    append("  args: ")
                    append(
                        entry.args.entries.joinToString(", ") { (argName, spec) ->
                            val flags = buildList {
                                add(spec.type)
                                if (spec.required == true) add("required")
                                if (spec.default != null) add("default ${spec.default}")
                            }
                            "$argName (${flags.joinToString(" ")})"
                        },
                    )
                    append('\n')
                }
            }
        }
        if (listed.invalid.isNotEmpty()) {
            append("\nUnusable files (${listed.invalid.size}):\n")
            listed.invalid.forEach { append("- ${it.path}: ${it.reason}\n") }
        }
    }

    /** `args` 声明 → store 的声明模型。任何形状不对都返回 null，由调用方给出统一错误。 */
    private fun parseArgDeclarations(element: JsonElement?): Map<String, WorkflowScriptFrontmatter.ArgSpec>? {
        if (element == null || element is JsonNull) return emptyMap()
        val objectValue = element as? JsonObject ?: return null
        val out = LinkedHashMap<String, WorkflowScriptFrontmatter.ArgSpec>()
        for ((argName, raw) in objectValue) {
            val spec = raw as? JsonObject ?: return null
            val type = (spec["type"] as? JsonPrimitive)?.contentOrNull ?: return null
            if (type !in WorkflowScriptFrontmatter.ARG_TYPES) return null
            val required = spec["required"]?.let { (it as? JsonPrimitive)?.booleanOrNull ?: return null }
            val argDescription = spec["description"]?.let { (it as? JsonPrimitive)?.contentOrNull ?: return null }
            out[argName] = WorkflowScriptFrontmatter.ArgSpec(
                type = type,
                required = required,
                description = argDescription,
                default = spec["default"],
            )
        }
        return out
    }

    private fun iso(epochMs: Long): String =
        runCatching { java.time.Instant.ofEpochMilli(epochMs).toString() }.getOrDefault("$epochMs")

    fun all(): List<Tool> = listOf(
        Create(),
        Amend(),
        Save(),
        Eval(),
        ListRuns(),
        GetRun(),
        Resume(),
        Resolve(),
        Fill(),
        ListSaved(),
    )
}
