package com.andmx.agent.zcode

import com.andmx.agent.LoadedSkills
import com.andmx.agent.Tool
import com.andmx.agent.ToolResult
import com.andmx.agent.ToolRisk
import com.andmx.agent.workflow.ExpertWorkflow
import com.andmx.agent.workflow.WorkflowDefinition
import com.andmx.agent.workflow.WorkflowRunSnapshot
import com.andmx.agent.workflow.WorkflowService
import com.andmx.agent.workflow.workflowJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * ZCode dwf 工具面对齐。差异：上游脚本是 TS DSL（agent()/parallel()/pipeline()），
 * AndMX 无 JS runtime——authoring 改为结构化 spec JSON（WorkflowDefinition schema），
 * 语义等价（phase 管线 + DAG 图 + 子代理 actor），由 graph 引擎执行。
 */
class WorkflowTools(
    private val service: WorkflowService?,
    private val conversationId: suspend () -> Long,
    private val cwd: suspend () -> String,
    private val isAutomationTurn: suspend () -> Boolean = { false },
    /**
     * 上游 3.14.3 workflow-skill-gate 移植：「这个会话此刻加载着某技能吗」的探针，
     * 由 provider 可见历史回答（compact 后自动回到未加载）。默认放行=调用方不参与门。
     */
    private val hasLoadedSkill: suspend (String) -> Boolean = { true },
) {
    private fun noService() = ToolResult(
        "workflow_unavailable: this session cannot run workflows", isError = true,
    )

    /**
     * 没读过 `dynamic-workflows` 技能就拒绝创作类调用。上游用 errorCode 428 区分
     * 「参数错」与「先去读技能」；AndMX ToolResult 无错误码，用稳定前缀承担同一职责。
     */
    private suspend fun requireWorkflowSkill(toolName: String): ToolResult? {
        if (hasLoadedSkill(LoadedSkills.DYNAMIC_WORKFLOWS)) return null
        return ToolResult(
            "workflow_skill_not_loaded: $toolName needs the `${LoadedSkills.DYNAMIC_WORKFLOWS}` skill loaded in this session before it accepts a spec. " +
                "Call the Skill tool with skill \"${LoadedSkills.DYNAMIC_WORKFLOWS}\" first — it carries the spec schema, the authoring rules and this tool's full contract — " +
                "then call $toolName again. Nothing was started.",
            isError = true,
        )
    }

    private suspend fun parseSpec(args: JsonObject): Pair<WorkflowDefinition?, String?> {
        val specEl = args["spec"] ?: return null to "spec is required"
        val obj = specEl as? JsonObject
            ?: return null to "spec must be a JSON object matching the WorkflowDefinition schema"
        return runCatching {
            workflowJson.decodeFromJsonElement(WorkflowDefinition.serializer(), obj)
        }.fold(
            onSuccess = { def ->
                val errors = def.validate()
                if (errors.isEmpty()) def to null else null to errors.joinToString("\n")
            },
            onFailure = { null to "invalid workflow spec: ${it.message}" },
        )
    }

    private fun fmtRun(s: WorkflowRunSnapshot): String = buildString {
        append("runId: ${s.runId} | status: ${s.status} | kind: ${s.kind}")
        append(" | task: ${s.task.take(80)}")
        append(" | phases: ${s.phases.count { it.status == com.andmx.agent.workflow.WorkflowNodeStatus.completed }}/${s.phases.size}")
        s.currentPhase?.let { append(" | current: $it") }
        s.reportPath?.let { append(" | report: $it") }
    }

    inner class Create : Tool {
        override val name = "CreateWorkflow"
        override val description =
            "Create and launch a durable multi-agent workflow run. Provide exactly one source: `name` (a saved definition; see ListSavedWorkflows) or `spec` (an inline WorkflowDefinition JSON). " +
                "Writing a spec requires the `dynamic-workflows` skill — load it with the Skill tool first."
        override val risk = ToolRisk.EXECUTE
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("name") { put("type", "string") }
                putJsonObject("spec") { put("type", "object") }
                putJsonObject("task") { put("type", "string") }
                putJsonObject("title") { put("type", "string") }
            }
            putJsonArray("required") { add("task") }
        }

        override suspend fun execute(args: JsonObject): ToolResult {
            if (isAutomationTurn()) {
                return ToolResult("$name is not allowed while running a scheduled automation.", isError = true)
            }
            // 上游 createWorkflowNeedsSkill 例外：按名字跑 saved 定义不是写 spec，不需要技能。
            // 门在入参解析位（先于 service 触碰），与上游 resolveInput 同序。
            if (args["spec"] != null) requireWorkflowSkill(this.name)?.let { return it }
            val svc = service ?: return noService()
            val task = args["task"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (task.isBlank()) return ToolResult("task is required", isError = true)
            val name = args["name"]?.jsonPrimitive?.contentOrNull
            val hasSpec = args["spec"] != null
            if ((name == null) == !hasSpec) {
                return ToolResult("Provide exactly one workflow source: `name` for a saved definition or `spec` for an inline one.", isError = true)
            }
            val definition = if (name != null) {
                svc.readDefinition(name)
                    ?: return ToolResult("Workflow definition \"$name\" was not found. Call ListSavedWorkflows for available names.", isError = true)
            } else {
                val (def, err) = parseSpec(args)
                def ?: return ToolResult(err ?: "invalid spec", isError = true)
            }
            val snapshot = svc.start(
                definition = definition,
                task = task,
                conversationId = conversationId(),
                cwd = cwd(),
                parentSessionId = null,
            )
            return ToolResult(
                "Workflow run started.\n${fmtRun(snapshot)}\n" +
                    "It runs in the background; use GetWorkflowRun with runId=${snapshot.runId} for progress.",
            )
        }
    }

    inner class Amend : Tool {
        override val name = "AmendWorkflow"
        override val description =
            "Patch a saved workflow definition in place: `name` plus only the fields to change. Passing a replacement `spec` requires the `dynamic-workflows` skill."
        override val risk = ToolRisk.WRITE
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("name") { put("type", "string") }
                putJsonObject("title") { put("type", "string") }
                putJsonObject("description") { put("type", "string") }
                putJsonObject("spec") { put("type", "object") }
            }
            putJsonArray("required") { add("name") }
        }

        override suspend fun execute(args: JsonObject): ToolResult {
            // 上游 amendWorkflowNeedsSkill 例外：不带 spec 的纯元数据修补不是写 spec。
            if (args["spec"] != null) requireWorkflowSkill(this.name)?.let { return it }
            val svc = service ?: return noService()
            val name = args["name"]?.jsonPrimitive?.contentOrNull
                ?: return ToolResult("name is required", isError = true)
            val cur = svc.readDefinition(name)
                ?: return ToolResult("Workflow definition \"$name\" was not found.", isError = true)
            val patched = if (args["spec"] != null) {
                val (def, err) = parseSpec(args)
                def ?: return ToolResult(err ?: "invalid spec", isError = true)
                def.copy(definitionId = cur.definitionId, definitionVersion = cur.definitionVersion)
            } else {
                cur.copy(
                    title = args["title"]?.jsonPrimitive?.contentOrNull ?: cur.title,
                    description = args["description"]?.jsonPrimitive?.contentOrNull ?: cur.description,
                )
            }
            val (saved, err) = svc.saveDefinition(patched)
            return if (err != null) ToolResult(err, isError = true)
            else ToolResult("Amended workflow ${saved!!.definitionId} (${saved.title}).")
        }
    }

    inner class Save : Tool {
        override val name = "SaveWorkflow"
        override val description =
            "Save a workflow `spec` into the project workflow library, launchable later by `name` via CreateWorkflow. Requires the `dynamic-workflows` skill."
        override val risk = ToolRisk.WRITE
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("spec") { put("type", "object") }
            }
            putJsonArray("required") { add("spec") }
        }

        override suspend fun execute(args: JsonObject): ToolResult {
            requireWorkflowSkill(name)?.let { return it }
            val svc = service ?: return noService()
            val (def, err) = parseSpec(args)
            def ?: return ToolResult(err ?: "spec is required", isError = true)
            val (saved, saveErr) = svc.saveDefinition(def)
            return if (saveErr != null) ToolResult(saveErr, isError = true)
            else ToolResult("Saved workflow ${saved!!.definitionId} (${saved.title}). Launch it with CreateWorkflow name=\"${saved.definitionId}\".")
        }
    }

    inner class ListSaved : Tool {
        override val name = "ListSavedWorkflows"
        override val description = "List saved workflow definitions available to CreateWorkflow, including the built-in `expert` workflow."
        override val risk = ToolRisk.READ
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        }

        override suspend fun execute(args: JsonObject): ToolResult {
            val svc = service ?: return noService()
            val defs = svc.listDefinitions()
            if (defs.isEmpty()) return ToolResult("No saved workflows.")
            return ToolResult(
                defs.joinToString("\n") { d ->
                    "${d.definitionId}: ${d.title} | kind=${d.kind} | phases=${d.phaseOrder.joinToString(">")}${d.description?.let { " | $it" } ?: ""}"
                },
            )
        }
    }

    inner class ListRuns : Tool {
        override val name = "ListWorkflowRuns"
        override val description = "List workflow runs in this workspace, newest first, with status and progress."
        override val risk = ToolRisk.READ
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("status") {
                    put("type", "string")
                    put("description", "optional filter: pending|running|paused|completed|failed|cancelled")
                }
            }
        }

        override suspend fun execute(args: JsonObject): ToolResult {
            val svc = service ?: return noService()
            val status = args["status"]?.jsonPrimitive?.contentOrNull
            val runs = svc.listRuns().let { list ->
                if (status == null) list else list.filter { it.status.name == status }
            }
            if (runs.isEmpty()) return ToolResult("No workflow runs.")
            return ToolResult(
                runs.joinToString("\n") { r ->
                    "${r.runId} | ${r.status} | ${r.definitionId} | ${r.task.take(80)}"
                },
            )
        }
    }

    inner class GetRun : Tool {
        override val name = "GetWorkflowRun"
        override val description =
            "Read a workflow run snapshot: run status, per-phase status, graph node states, artifacts, activities and the last events. Use ListWorkflowRuns to find runIds."
        override val risk = ToolRisk.READ
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("runId") { put("type", "string") }
            }
            putJsonArray("required") { add("runId") }
        }

        override suspend fun execute(args: JsonObject): ToolResult {
            val svc = service ?: return noService()
            val runId = args["runId"]?.jsonPrimitive?.contentOrNull
                ?: return ToolResult("runId is required", isError = true)
            val s = svc.getRun(runId)
                ?: return ToolResult("Workflow run $runId was not found.", isError = true)
            return ToolResult(
                buildString {
                    append(fmtRun(s)).append('\n')
                    append("Phases:\n")
                    s.phases.forEach { p ->
                        append("  [${p.status}] ${p.phase}${p.artifactPath?.let { " -> $it" } ?: ""}${p.error?.let { " ($it)" } ?: ""}\n")
                    }
                    val nodes = s.graph.nodes.filter { it.kind == "task" }
                    if (nodes.isNotEmpty()) {
                        append("Nodes:\n")
                        nodes.forEach { n ->
                            append("  [${n.status}] ${n.id} ${n.title}${n.error?.let { " ($it)" } ?: ""}\n")
                        }
                    }
                    if (s.artifacts.isNotEmpty()) {
                        append("Artifacts:\n")
                        s.artifacts.forEach { append("  - ${it.label}: ${it.path}\n") }
                    }
                    s.failure?.let { append("Failure: ${it.kind} ${it.message}\n") }
                    s.pauseReason?.let { append("Paused: $it\n") }
                },
            )
        }
    }

    inner class GetRoster : Tool {
        override val name = "GetWorkflowRunRoster"
        override val description =
            "Get the scheduler roster for a workflow run: ready/active/blocked nodes, per-collection frontier state and derived scheduler counts."
        override val risk = ToolRisk.READ
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("runId") { put("type", "string") }
            }
            putJsonArray("required") { add("runId") }
        }

        override suspend fun execute(args: JsonObject): ToolResult {
            val svc = service ?: return noService()
            val runId = args["runId"]?.jsonPrimitive?.contentOrNull
                ?: return ToolResult("runId is required", isError = true)
            val state = svc.schedulerState(runId)
                ?: return ToolResult("Workflow run $runId was not found.", isError = true)
            return ToolResult(
                buildString {
                    append("nodes=${state.total} ready=${state.ready} active=${state.active} pending=${state.pending} blocked=${state.blocked} completed=${state.completed} failed=${state.failed}\n")
                    if (state.readyNodeIds.isNotEmpty()) append("ready: ${state.readyNodeIds.joinToString(", ")}\n")
                    if (state.activeNodeIds.isNotEmpty()) append("active: ${state.activeNodeIds.joinToString(", ")}\n")
                    state.blockedNodes.forEach { (id, by) -> append("blocked: $id <- ${by.joinToString(", ")}\n") }
                    state.collectionStates.forEach { c ->
                        append("collection ${c.collection.collectionId}: status=${c.status} frontier=${c.frontier}/${c.frontierTarget ?: "-"} plannerRuns=${c.plannerRuns} exhausted=${c.exhausted}\n")
                    }
                },
            )
        }
    }

    inner class Resume : Tool {
        override val name = "ResumeWorkflowRun"
        override val description =
            "Resume a paused or failed workflow run. Active nodes are reset to pending and the scheduler continues from the persisted snapshot. Completed or cancelled runs cannot be resumed."
        override val risk = ToolRisk.EXECUTE
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("runId") { put("type", "string") }
            }
            putJsonArray("required") { add("runId") }
        }

        override suspend fun execute(args: JsonObject): ToolResult {
            val svc = service ?: return noService()
            val runId = args["runId"]?.jsonPrimitive?.contentOrNull
                ?: return ToolResult("runId is required", isError = true)
            val (snapshot, err) = svc.resume(runId)
            if (err != null) return ToolResult(err, isError = true)
            return ToolResult("Resumed workflow run $runId.\n${fmtRun(snapshot!!)}")
        }
    }

    inner class Cancel : Tool {
        override val name = "CancelWorkflowRun"
        override val description = "Cancel a running or paused workflow run. Active nodes and phases are marked cancelled; the snapshot is kept for inspection."
        override val risk = ToolRisk.EXECUTE
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("runId") { put("type", "string") }
            }
            putJsonArray("required") { add("runId") }
        }

        override suspend fun execute(args: JsonObject): ToolResult {
            val svc = service ?: return noService()
            val runId = args["runId"]?.jsonPrimitive?.contentOrNull
                ?: return ToolResult("runId is required", isError = true)
            val (found, _) = svc.cancel(runId)
            return ToolResult(
                if (found) "Cancelled workflow run $runId."
                else "Workflow run $runId was not found.",
            )
        }
    }

    inner class EvalSpec : Tool {
        override val name = "EvalWorkflowSnippet"
        override val description =
            "Validate a workflow `spec` without running it and report the phase graph it would execute. Requires the `dynamic-workflows` skill."
        override val risk = ToolRisk.READ
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("spec") { put("type", "object") }
            }
            putJsonArray("required") { add("spec") }
        }

        override suspend fun execute(args: JsonObject): ToolResult {
            requireWorkflowSkill(name)?.let { return it }
            val (def, err) = parseSpec(args)
            def ?: return ToolResult(err ?: "spec is required", isError = true)
            val graph = com.andmx.agent.workflow.WorkflowPrompts.createPhaseGraph(def)
            val state = com.andmx.agent.workflow.deriveWorkflowSchedulerState(graph)
            return ToolResult(
                buildString {
                    append("spec valid: ${def.definitionId} v${def.definitionVersion} (${def.title})\n")
                    append("phases: ${def.phaseOrder.joinToString(" -> ")}\n")
                    append("graph: ${state.total} phase nodes, ${graph.edges.size} edges\n")
                    def.phases.forEach { p ->
                        append("  ${p.phase} [${p.behavior}] ${p.title}${p.artifactPath?.let { " -> $it" } ?: ""}\n")
                    }
                },
            )
        }
    }

    fun all(): List<Tool> = listOf(
        Create(), Amend(), Save(), ListSaved(), ListRuns(), GetRun(),
        GetRoster(), Resume(), Cancel(), EvalSpec(),
    )
}
