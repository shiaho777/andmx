package com.andmx.agent.zcode

import com.andmx.agent.LoadedSkills
import com.andmx.agent.Tool
import com.andmx.agent.ToolResult
import com.andmx.agent.ToolRisk
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class WorkflowTools(
    private val isAutomationTurn: suspend () -> Boolean = { false },
    private val hasLoadedSkill: suspend (String) -> Boolean = { true },
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
    }

    private fun absent(message: String) = ToolResult(message, isError = true)

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
            val sources = listOf("script", "saved", "path").count { present(args, it) }
            if (sources != 1) return absent(CREATE_WORKFLOW_SOURCE_ERROR)
            if (present(args, "args") && !present(args, "path")) return absent(CREATE_WORKFLOW_ARGS_WITHOUT_PATH_ERROR)
            return absent(WORKFLOW_NOT_EXECUTED_NOTE)
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
            if (workflowName == null) return absent("name is required")
            if (!workflowName.matches(Regex("^[A-Za-z0-9_.-]{1,$SAVED_WORKFLOW_MAX_NAME_CHARS}$"))) {
                return absent(
                    "'$workflowName' is not a usable workflow name: names may only contain letters, digits, '.', '-' and '_', and must be 1-$SAVED_WORKFLOW_MAX_NAME_CHARS characters.",
                )
            }
            if (text(args, "description") == null) return absent("description is required")
            if (text(args, "scope") == null) return absent("scope is required")
            val sources = listOf("script", "script_path").count { present(args, it) }
            if (sources != 1) return absent(SAVE_WORKFLOW_SOURCE_ERROR)
            return absent(WORKFLOW_SAVE_UNAVAILABLE)
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
        override suspend fun execute(args: JsonObject): ToolResult = absent(WORKFLOW_INTROSPECTION_UNAVAILABLE)
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
            if (text(args, "run_id") == null) return absent("run_id is required")
            return absent(WORKFLOW_INTROSPECTION_UNAVAILABLE)
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
        override suspend fun execute(args: JsonObject): ToolResult = absent(WORKFLOW_SAVED_LIST_UNAVAILABLE)
    }

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
