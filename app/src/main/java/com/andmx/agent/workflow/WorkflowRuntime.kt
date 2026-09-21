package com.andmx.agent.workflow

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException

// ZCode workflow/{definition,expert/run-loop,phase-runner,scheduled-phase,critic-loop,failures} 对齐。
// agentRunner 抽象子代理回合；调度器/phase/critic 都经它跑子会话。

object ExpertWorkflow {
    const val KIND = "expert"
    const val DEFINITION_ID = "expert"
    const val DEFINITION_VERSION = "2"

    fun definition(): WorkflowDefinition = WorkflowDefinition(
        definitionId = DEFINITION_ID,
        definitionVersion = DEFINITION_VERSION,
        description = "Durable long-task workflow for large NL->Code work. Child agent sessions perform each phase.",
        kind = KIND,
        phaseOrder = listOf(
            "clarify", "task_analysis", "arch_decompose", "env_setup",
            "meta_prompt", "exec", "final_critic", "complete",
        ),
        phases = listOf(
            WorkflowPhaseDefinition(
                artifactPath = "artifacts/01-clarify.md",
                description = "Refine the user goal, assumptions, acceptance criteria, and unresolved questions. Ask only if blocking.",
                phase = "clarify",
                title = "Clarify",
            ),
            WorkflowPhaseDefinition(
                artifactPath = "artifacts/02-task-analysis.md",
                description = "Map the task to repo context, constraints, risks, likely files, validation, and failure paths.",
                phase = "task_analysis",
                title = "Task Analysis",
            ),
            WorkflowPhaseDefinition(
                artifactPath = "artifacts/03-architecture-decompose.md",
                description = "Decompose the work into a dependency graph of implementation and verification nodes.",
                phase = "arch_decompose",
                seedGraphFromArtifact = WorkflowGraphSeedSource(
                    gateAfterPhase = "meta_prompt",
                    targetPhase = "exec",
                ),
                title = "Architecture Decompose",
            ),
            WorkflowPhaseDefinition(
                artifactPath = "artifacts/04-env-setup.md",
                description = "Check whether environment setup, dependencies, credentials, or local services are required.",
                phase = "env_setup",
                title = "Environment Setup",
            ),
            WorkflowPhaseDefinition(
                artifactPath = "artifacts/05-meta-prompt.md",
                description = "Create the execution instructions, constraints, and node prompts needed for downstream work.",
                nodePromptsFromArtifact = WorkflowNodePromptsFromArtifact(targetPhase = "exec"),
                phase = "meta_prompt",
                title = "Meta Prompt",
            ),
            WorkflowPhaseDefinition(
                artifactPath = "artifacts/06-exec.md",
                behavior = WorkflowPhaseBehavior.scheduled_graph,
                description = "Execute the planned work through the normal agent runtime. Preserve small steps and validation.",
                phase = "exec",
                title = "Execute",
            ),
            WorkflowPhaseDefinition(
                artifactPath = "artifacts/07-final-critic.md",
                behavior = WorkflowPhaseBehavior.critic,
                description = "Review results against acceptance criteria, identify regressions, missing tests, and residual risk.",
                phase = "final_critic",
                title = "Final Critic",
            ),
            WorkflowPhaseDefinition(
                behavior = WorkflowPhaseBehavior.complete,
                description = "Write final report and mark the run completed.",
                phase = "complete",
                title = "Complete",
            ),
        ),
        strategy = WorkflowStrategy.DEFAULT,
        title = "Expert Workflow",
    )
}

class WorkflowRuntime(
    private val store: WorkflowStoreSink,
    private val agentRunner: WorkflowAgentRunner,
    private val plannerRunner: WorkflowAgentRunner = agentRunner,
    private val now: () -> String = { Instant.now().toString() },
    private val createActivityId: () -> String = { "act_${UUID.randomUUID().toString().take(12)}" },
) {
    data class CommandResult(
        val response: String,
        val runId: String,
        val snapshot: WorkflowRunSnapshot,
        val status: WorkflowRunStatus,
    )

    fun newSnapshot(
        definition: WorkflowDefinition,
        task: String,
        cwd: String,
        runId: String = "wf_${UUID.randomUUID().toString().replace("-", "").take(10)}",
        sessionId: String? = null,
    ): WorkflowRunSnapshot {
        val ts = now()
        return WorkflowRunSnapshot(
            artifacts = emptyList(),
            createdAt = ts,
            cwd = cwd,
            definitionId = definition.definitionId,
            definitionVersion = definition.definitionVersion,
            graph = WorkflowPrompts.createPhaseGraph(definition),
            kind = definition.kind,
            phaseOrder = definition.phaseOrder,
            phases = definition.phaseOrder.map {
                WorkflowPhaseSnapshot(phase = it, status = WorkflowNodeStatus.pending)
            },
            runId = runId,
            sessionId = sessionId,
            status = WorkflowRunStatus.pending,
            strategy = definition.strategy,
            task = task,
            updatedAt = ts,
        )
    }

    suspend fun continueRun(
        definition: WorkflowDefinition,
        initialSnapshot: WorkflowRunSnapshot,
        parentSessionId: String? = null,
    ): CommandResult {
        var snapshot = initialSnapshot.copy(
            startedAt = initialSnapshot.startedAt ?: now(),
            status = WorkflowRunStatus.running,
            updatedAt = now(),
        )
        store.writeSnapshot(snapshot)
        emit(snapshot, "run_started", "${definition.title} started.")
        try {
            for (phaseId in definition.phaseOrder) {
                val phaseDef = definition.phaseMap()[phaseId]
                    ?: error("${definition.title} definition is missing phase: $phaseId")
                if (phaseDef.behavior == WorkflowPhaseBehavior.complete) {
                    snapshot = completeRun(definition, snapshot, phaseDef)
                    break
                }
                val phaseSnap = snapshot.phases.find { it.phase == phaseDef.phase }
                if (phaseSnap?.status == WorkflowNodeStatus.completed) continue
                snapshot = when (phaseDef.behavior) {
                    WorkflowPhaseBehavior.scheduled_graph ->
                        runScheduledPhase(definition, snapshot, phaseDef, parentSessionId)
                    WorkflowPhaseBehavior.critic ->
                        runFinalCriticLoop(definition, snapshot, phaseDef, parentSessionId)
                    WorkflowPhaseBehavior.agent -> {
                        val run = runPhase(definition, snapshot, phaseDef, parentSessionId)
                        var next = seedGraphFromPhaseArtifact(snapshot = run.snapshot, definition = phaseDef, response = run.response)
                        next = updateNodePromptsFromPhaseArtifact(next, phaseDef, run.response)
                        next
                    }
                    WorkflowPhaseBehavior.complete -> snapshot
                }
            }
            return CommandResult(
                response = WorkflowPrompts.formatCompletion(snapshot),
                runId = snapshot.runId,
                snapshot = snapshot,
                status = snapshot.status,
            )
        } catch (e: CancellationException) {
            val cancelled = WorkflowLifecycle.cancel(snapshot, e.message ?: "Workflow cancelled.", now()).snapshot
            store.writeSnapshot(cancelled)
            emit(cancelled, "run_cancelled", e.message)
            return CommandResult(
                response = WorkflowPrompts.formatStatus(cancelled),
                runId = cancelled.runId,
                snapshot = cancelled,
                status = cancelled.status,
            )
        } catch (e: Exception) {
            val latest = runCatching { store.readSnapshot(snapshot.runId) }.getOrNull() ?: snapshot
            val failure = WorkflowFailure(
                activityId = latest.activities.lastOrNull { it.status == WorkflowNodeStatus.active }?.activityId,
                kind = "unknown",
                message = e.message ?: e.toString(),
                phase = latest.currentPhase,
                recoverable = true,
                retryable = true,
            )
            val paused = latest.copy(
                failure = failure,
                pauseReason = failure.message,
                recoveryActions = listOf(
                    WorkflowRecoveryAction(action = "retry", label = "Retry"),
                    WorkflowRecoveryAction(action = "cancel", label = "Cancel run"),
                ),
                status = WorkflowRunStatus.paused,
                updatedAt = now(),
            )
            store.writeSnapshot(paused)
            emit(paused, "workflow_paused", failure.message, paused.currentPhase)
            return CommandResult(
                response = "${WorkflowPrompts.formatStatus(paused)}\n\nPaused: ${failure.message}",
                runId = paused.runId,
                snapshot = paused,
                status = paused.status,
            )
        }
    }

    // ── agent phase ────────────────────────────────────────────

    private data class PhaseRun(val response: String, val snapshot: WorkflowRunSnapshot)

    private suspend fun runPhase(
        definition: WorkflowDefinition,
        snapshot: WorkflowRunSnapshot,
        phaseDef: WorkflowPhaseDefinition,
        parentSessionId: String?,
    ): PhaseRun {
        val activityId = createActivityId()
        val inputArtifactPaths = snapshot.artifacts.map { it.path }
        var running = WorkflowGraphOps.upsertActivity(
            WorkflowGraphOps.updatePhase(snapshot, phaseDef.phase, {
                it.copy(activityId = activityId, error = null, startedAt = now(), status = WorkflowNodeStatus.active)
            }, now()),
            WorkflowActivitySnapshot(
                activityId = activityId,
                inputArtifactPaths = inputArtifactPaths,
                kind = WorkflowActivityKind.agent_session,
                nodeId = WorkflowPrompts.phaseNodeId(phaseDef.phase),
                parentSessionId = parentSessionId,
                phase = phaseDef.phase,
                startedAt = now(),
                status = WorkflowNodeStatus.active,
            ),
            now(),
        )
        running = running.copy(
            graph = updatePhaseGraphStatus(running.graph, phaseDef.phase, WorkflowNodeStatus.active),
        )
        store.writeSnapshot(running)
        emit(running, "phase_started", "${phaseDef.title} started.", phaseDef.phase)
        return try {
            val result = agentRunner.run(
                WorkflowAgentInput(
                    activityId = activityId,
                    phase = phaseDef.phase,
                    prompt = WorkflowPrompts.buildPhasePrompt(running, phaseDef),
                    runId = running.runId,
                    task = running.task,
                    parentSessionId = parentSessionId,
                    onChildSessionStarted = { sessionId, model ->
                        val activity = running.activities.find { it.activityId == activityId }
                        if (activity?.status == WorkflowNodeStatus.active) {
                            running = WorkflowGraphOps.upsertActivity(
                                WorkflowGraphOps.updatePhase(running, phaseDef.phase, {
                                    it.copy(sessionId = sessionId)
                                }, now()),
                                activity.copy(model = model, sessionId = sessionId),
                                now(),
                            )
                            store.writeSnapshot(running)
                            emit(running, "workflow_session_linked", "Workflow session linked: $sessionId", phaseDef.phase, WorkflowPrompts.phaseNodeId(phaseDef.phase))
                        }
                    },
                ),
            )
            val artifactPath = phaseDef.artifactPath ?: "artifacts/${phaseDef.phase}.md"
            val rel = store.writeArtifact(running.runId, artifactPath, result.response)
            val completed = WorkflowGraphOps.addArtifact(
                WorkflowGraphOps.upsertActivity(
                    WorkflowGraphOps.updatePhase(running, phaseDef.phase, {
                        it.copy(
                            activityId = activityId, artifactPath = rel, completedAt = now(),
                            sessionId = result.sessionId, status = WorkflowNodeStatus.completed,
                        )
                    }, now()),
                    WorkflowActivitySnapshot(
                        activityId = activityId, artifactPath = rel, completedAt = now(),
                        inputArtifactPaths = inputArtifactPaths,
                        kind = WorkflowActivityKind.agent_session, model = result.model,
                        nodeId = WorkflowPrompts.phaseNodeId(phaseDef.phase),
                        outputArtifactPaths = listOf(rel), parentSessionId = parentSessionId,
                        phase = phaseDef.phase, sessionId = result.sessionId,
                        startedAt = running.activities.find { it.activityId == activityId }?.startedAt ?: now(),
                        status = WorkflowNodeStatus.completed,
                    ),
                    now(),
                ),
                WorkflowArtifact(
                    contentType = "text/markdown", createdAt = now(),
                    label = phaseDef.title, path = rel, phase = phaseDef.phase,
                ),
                now(),
            ).let { s ->
                s.copy(graph = updatePhaseGraphStatus(s.graph, phaseDef.phase, WorkflowNodeStatus.completed))
            }
            store.writeSnapshot(completed)
            emit(completed, "artifact_written", "Artifact written: $rel", phaseDef.phase)
            emit(completed, "phase_completed", "${phaseDef.title} completed.", phaseDef.phase)
            PhaseRun(result.response, completed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failed = WorkflowGraphOps.upsertActivity(
                WorkflowGraphOps.updatePhase(running, phaseDef.phase, {
                    it.copy(activityId = activityId, completedAt = now(), error = e.message, status = WorkflowNodeStatus.failed)
                }, now()),
                WorkflowActivitySnapshot(
                    activityId = activityId, completedAt = now(), error = e.message,
                    inputArtifactPaths = inputArtifactPaths,
                    kind = WorkflowActivityKind.agent_session,
                    nodeId = WorkflowPrompts.phaseNodeId(phaseDef.phase),
                    parentSessionId = parentSessionId, phase = phaseDef.phase,
                    startedAt = now(), status = WorkflowNodeStatus.failed,
                ),
                now(),
            ).let { s ->
                s.copy(graph = updatePhaseGraphStatus(s.graph, phaseDef.phase, WorkflowNodeStatus.failed))
            }
            store.writeSnapshot(failed)
            emit(failed, "phase_failed", e.message, phaseDef.phase)
            throw e
        }
    }

    // ── scheduled_graph phase ──────────────────────────────────

    private suspend fun runScheduledPhase(
        definition: WorkflowDefinition,
        snapshot: WorkflowRunSnapshot,
        phaseDef: WorkflowPhaseDefinition,
        parentSessionId: String?,
    ): WorkflowRunSnapshot {
        val active = WorkflowGraphOps.updatePhase(snapshot, phaseDef.phase, {
            it.copy(error = null, startedAt = now(), status = WorkflowNodeStatus.active)
        }, now())
        store.writeSnapshot(active)
        emit(active, "phase_started", "${phaseDef.title} started.", phaseDef.phase)
        val scheduler = WorkflowGraphScheduler(
            store = store,
            runner = agentRunner,
            plannerRunner = plannerRunner,
            createActivityId = createActivityId,
            now = now,
        )
        val result = scheduler.run(
            SchedulerRunOptions(
                artifactDirectory = "artifacts/${WorkflowPrompts.safeArtifactName(phaseDef.phase)}",
                buildPrompt = { node, snap -> WorkflowPrompts.buildScheduledNodePrompt(snap, phaseDef, node) },
                plannerPrompt = { col, snap -> WorkflowPrompts.buildDefaultPlannerPrompt(snap, col, phaseDef.phase) },
                cwd = active.cwd,
                executableNodeIds = WorkflowPrompts.executableNodeIdsForPhase(active.graph, phaseDef.phase),
                parentSessionId = parentSessionId,
                phase = phaseDef.phase,
                snapshot = active,
            ),
        )
        if (result.status != "completed") {
            error("Workflow ${phaseDef.phase} scheduler paused: ${result.reason}")
        }
        val summary = WorkflowPrompts.buildScheduledPhaseSummary(result.snapshot, phaseDef.phase)
        val rel = store.writeArtifact(
            result.snapshot.runId,
            phaseDef.artifactPath ?: "artifacts/${phaseDef.phase}.md",
            summary,
        )
        val phaseActivity = result.snapshot.activities
            .lastOrNull { it.phase == phaseDef.phase && it.status == WorkflowNodeStatus.completed }
        val completed = WorkflowGraphOps.addArtifact(
            WorkflowGraphOps.updatePhase(result.snapshot, phaseDef.phase, {
                it.copy(
                    activityId = phaseActivity?.activityId, artifactPath = rel,
                    completedAt = now(), sessionId = phaseActivity?.sessionId,
                    status = WorkflowNodeStatus.completed,
                )
            }, now()),
            WorkflowArtifact(
                contentType = "text/markdown", createdAt = now(),
                label = phaseDef.title, path = rel, phase = phaseDef.phase,
            ),
            now(),
        ).let { s ->
            s.copy(graph = updatePhaseGraphStatus(s.graph, phaseDef.phase, WorkflowNodeStatus.completed))
        }
        store.writeSnapshot(completed)
        emit(completed, "artifact_written", "Artifact written: $rel", phaseDef.phase)
        emit(completed, "phase_completed", "${phaseDef.title} completed.", phaseDef.phase)
        return completed
    }

    // ── critic phase ───────────────────────────────────────────

    private suspend fun runFinalCriticLoop(
        definition: WorkflowDefinition,
        snapshot: WorkflowRunSnapshot,
        phaseDef: WorkflowPhaseDefinition,
        parentSessionId: String?,
    ): WorkflowRunSnapshot {
        var current = snapshot
        val execDef = definition.phases.find { it.behavior == WorkflowPhaseBehavior.scheduled_graph }
            ?: error("${definition.title} definition is missing a scheduled graph phase")
        for (iteration in 1..current.strategy.finalCritic.maxIterations) {
            emit(current, "critic_started", "Final critic iteration $iteration started.", phaseDef.phase)
            val run = runPhase(definition, current, phaseDef, parentSessionId)
            current = run.snapshot
            val critic = WorkflowParsers.parseCriticResult(run.response)
            if (critic.verdict == "pass") {
                emit(current, "critic_passed", critic.reasoning.ifBlank { "Final critic iteration $iteration passed." }, phaseDef.phase)
                return current
            }
            val proposals = WorkflowParsers.dedupeReopenProposals(critic.reopenProposals)
            emit(current, "critic_failed", critic.reasoning.ifBlank { "Final critic iteration $iteration failed." }, phaseDef.phase)
            if (proposals.isEmpty()) return current
            val reopenedIds = mutableListOf<String>()
            for (proposal in proposals) {
                val reopen = runCatching {
                    WorkflowLifecycle.reopenNode(current, proposal.nodeId, 2, proposal.reason, now())
                }.getOrNull() ?: continue
                current = reopen.first
                store.writeSnapshot(current)
                emit(current, "node_reopened", "Node reopened by final critic: ${proposal.nodeId}", phaseDef.phase, proposal.nodeId)
                reopenedIds += proposal.nodeId
            }
            if (reopenedIds.isEmpty()) return current
            val retryReason = "Final critic reopened node(s): ${reopenedIds.joinToString(", ")}"
            current = resetPhaseForRetry(current, execDef.phase, retryReason)
            current = resetPhaseForRetry(current, phaseDef.phase, retryReason)
            store.writeSnapshot(current)
            current = runScheduledPhase(definition, current, execDef, parentSessionId)
        }
        current = WorkflowGraphOps.updatePhase(current, phaseDef.phase, {
            it.copy(completedAt = now(), error = "Final critic iteration limit reached.", status = WorkflowNodeStatus.failed)
        }, now())
        store.writeSnapshot(current)
        emit(current, "critic_iteration_limit_reached", "Final critic iteration limit reached.", phaseDef.phase)
        return current
    }

    // ── complete phase ─────────────────────────────────────────

    private suspend fun completeRun(
        definition: WorkflowDefinition,
        snapshot: WorkflowRunSnapshot,
        phaseDef: WorkflowPhaseDefinition,
    ): WorkflowRunSnapshot {
        val report = WorkflowPrompts.buildReport(snapshot)
        val rel = store.writeReport(snapshot.runId, report)
        val completed = WorkflowGraphOps.addArtifact(
            WorkflowGraphOps.updatePhase(snapshot, phaseDef.phase, {
                it.copy(artifactPath = rel, completedAt = now(), startedAt = now(), status = WorkflowNodeStatus.completed)
            }, now()).copy(
                completedAt = now(),
                reportPath = rel,
                status = WorkflowRunStatus.completed,
                updatedAt = now(),
            ),
            WorkflowArtifact(
                contentType = "text/markdown", createdAt = now(),
                label = "Report", path = rel, phase = phaseDef.phase,
            ),
            now(),
        ).let { s ->
            s.copy(graph = updatePhaseGraphStatus(s.graph, phaseDef.phase, WorkflowNodeStatus.completed))
        }
        store.writeSnapshot(completed)
        emit(completed, "run_completed", "${definition.title} completed.")
        return completed
    }

    // ── helpers ────────────────────────────────────────────────

    private suspend fun emit(
        snapshot: WorkflowRunSnapshot,
        type: String,
        message: String? = null,
        phase: String? = null,
        nodeId: String? = null,
    ) {
        store.appendEvent(
            WorkflowEvent(
                kind = snapshot.kind, message = message, nodeId = nodeId,
                phase = phase, runId = snapshot.runId, timestamp = now(), type = type,
            ),
        )
    }

    private fun updatePhaseGraphStatus(
        graph: WorkflowGraph,
        phase: String,
        status: WorkflowNodeStatus,
    ): WorkflowGraph {
        val target = WorkflowPrompts.phaseNodeId(phase)
        return graph.copy(
            nodes = graph.nodes.map { node ->
                if (node.id == target && node.kind == "phase") node.copy(status = status) else node
            },
        )
    }

    private fun resetPhaseForRetry(
        snapshot: WorkflowRunSnapshot,
        phase: String,
        reason: String,
    ): WorkflowRunSnapshot {
        val nodeId = WorkflowPrompts.phaseNodeId(phase)
        return snapshot.copy(
            currentPhase = phase,
            graph = snapshot.graph.copy(
                nodes = snapshot.graph.nodes.map { node ->
                    if (node.id == nodeId && node.kind == "phase") {
                        node.copy(error = reason, status = WorkflowNodeStatus.pending)
                    } else node
                },
            ),
            phases = snapshot.phases.map {
                if (it.phase == phase) WorkflowPhaseSnapshot(error = reason, phase = phase, status = WorkflowNodeStatus.pending)
                else it
            },
            updatedAt = now(),
        )
    }

    private suspend fun seedGraphFromPhaseArtifact(
        snapshot: WorkflowRunSnapshot,
        definition: WorkflowPhaseDefinition,
        response: String,
    ): WorkflowRunSnapshot {
        val seedSource = definition.seedGraphFromArtifact ?: return snapshot
        val targetPhase = seedSource.targetPhase
        val raw = WorkflowParsers.parseWorkflowGraphSeed(response, targetPhase) ?: return snapshot
        if (raw.nodes.isEmpty() && raw.collections.isEmpty()) return snapshot
        val seed = seedSource.gateAfterPhase?.let {
            WorkflowParsers.gateRootSeedNodes(raw, WorkflowPrompts.phaseNodeId(it))
        } ?: raw
        val applied = WorkflowLifecycle.applyGraphSeed(snapshot, seed, targetPhase, now())
        if (!applied.changed) return snapshot
        store.writeSnapshot(applied.snapshot)
        emit(applied.snapshot, "graph_expanded", "Workflow graph seeded from ${definition.title}.", definition.phase)
        return applied.snapshot
    }

    private suspend fun updateNodePromptsFromPhaseArtifact(
        snapshot: WorkflowRunSnapshot,
        definition: WorkflowPhaseDefinition,
        response: String,
    ): WorkflowRunSnapshot {
        val source = definition.nodePromptsFromArtifact ?: return snapshot
        val updateSet = WorkflowParsers.parseWorkflowNodePromptUpdateSet(response) ?: return snapshot
        if (updateSet.nodes.isEmpty()) return snapshot
        val applied = WorkflowLifecycle.applyNodePromptUpdates(
            snapshot, updateSet.nodes, source.targetPhase, now(),
        )
        if (!applied.changed) return snapshot
        store.writeSnapshot(applied.snapshot)
        emit(applied.snapshot, "graph_updated", "Workflow node prompts updated from ${definition.title}.", definition.phase)
        return applied.snapshot
    }
}
