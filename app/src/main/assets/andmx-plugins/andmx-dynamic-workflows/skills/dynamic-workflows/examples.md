# Workflow spec examples

Complete `WorkflowDefinition` specs. Pass any of them to `EvalWorkflowSnippet` first,
then `CreateWorkflow` as `spec` with a standalone `task`.

## Minimal review run

```json
{
  "definitionId": "pr-review",
  "definitionVersion": "1",
  "kind": "custom",
  "title": "PR review",
  "phaseOrder": ["review", "critic", "complete"],
  "phases": [
    { "phase": "review", "title": "Review", "behavior": "agent",
      "description": "Review the diff: correctness, regressions, missing tests. Write findings to the artifact.",
      "artifactPath": "artifacts/01-review.md" },
    { "phase": "critic", "title": "Critic", "behavior": "critic",
      "description": "Independently confirm or reject each finding before it reaches the user.",
      "artifactPath": "artifacts/02-critic.md" },
    { "phase": "complete", "title": "Complete", "behavior": "complete",
      "description": "Write the final report: verified findings, what was not checked, residual risk." }
  ],
  "strategy": {
    "clarify": { "confidenceThreshold": 0.8, "maxRounds": 3, "minRounds": 1 },
    "executor": { "drainingChangeHours": 1.0, "frontierTarget": 3, "maxConcurrentLoops": 2, "maxConsecutiveErrors": 3, "maxPlannerRuns": 10 },
    "finalCritic": { "maxIterations": 3 },
    "reactLoop": { "maxRounds": 30 }
  }
}
```

## Decompose → execute → critic (NL→code)

```json
{
  "definitionId": "feature-build",
  "definitionVersion": "1",
  "kind": "custom",
  "title": "Feature build",
  "phaseOrder": ["plan", "exec", "critic", "complete"],
  "phases": [
    { "phase": "plan", "title": "Plan", "behavior": "agent",
      "description": "Decompose the task into a dependency graph of small executable nodes and emit the seeded graph JSON contract.",
      "artifactPath": "artifacts/01-plan.md",
      "seedGraphFromArtifact": { "targetPhase": "exec" } },
    { "phase": "exec", "title": "Execute", "behavior": "scheduled_graph",
      "description": "Execute the planned nodes. Small steps, validate each node before settling it.",
      "artifactPath": "artifacts/02-exec.md" },
    { "phase": "critic", "title": "Critic", "behavior": "critic",
      "description": "Verify results against the task's acceptance criteria; reopen nodes that are wrong or incomplete.",
      "artifactPath": "artifacts/03-critic.md" },
    { "phase": "complete", "title": "Complete", "behavior": "complete",
      "description": "Write the final report: what shipped, validation evidence, residual risk." }
  ],
  "strategy": {
    "clarify": { "confidenceThreshold": 0.8, "maxRounds": 3, "minRounds": 1 },
    "executor": { "drainingChangeHours": 1.0, "frontierTarget": 3, "maxConcurrentLoops": 4, "maxConsecutiveErrors": 3, "maxPlannerRuns": 10 },
    "finalCritic": { "maxIterations": 3 },
    "reactLoop": { "maxRounds": 30 }
  }
}
```

## Saved-library entry

`SaveWorkflow` takes the same `spec` object. Give it a stable `definitionId` — that is the
`name` callers pass to `CreateWorkflow` later — and bump `definitionVersion` on every
pipeline change so old runs keep pointing at the version they started on.
