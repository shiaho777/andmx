---
name: dynamic-workflows
description: "Use when writing, validating, or revising a WorkflowDefinition spec for the CreateWorkflow tool: choosing the phase pipeline, picking phase behaviors (agent / scheduled_graph / critic / complete), seeding a task DAG from an artifact, sizing strategy limits, testing a spec with EvalWorkflowSnippet, and publishing artifacts the user can open."
when_to_use: "Only for workflow specs submitted to CreateWorkflow / SaveWorkflow / AmendWorkflow / EvalWorkflowSnippet. A single delegation or a few independent lookups belong to the Agent tool instead."
---

# Writing dynamic workflows

This skill is the whole authoring contract for `CreateWorkflow`. The tool descriptions are
short on purpose; the spec schema the runtime validates against and the rules a spec must
satisfy are in §4 of this file, and `CreateWorkflow`, `AmendWorkflow`, `SaveWorkflow` and
`EvalWorkflowSnippet` refuse to accept a spec until this skill has been loaded in the
session. The rest is the judgment layer: when a workflow is the right tool at all, how many
phases a request deserves, which phases should fan out into a scheduled graph, and how to
keep the run's work reviewable.

Two names collide here. `CreateWorkflow` is the dynamic-workflow tool and the only one this
skill is about. `/expert` and the built-in `expert` definition are a fixed pipeline that
happens to share the word "workflow".

## The bar: first-class, expert-level work

A workflow exists to produce first-class, expert-level work — the deliverable a senior
practitioner would hand over after doing the job properly, not a faster draft of what one
reply could have said. A user who asks for a workflow is paying for that depth, and every
choice in this skill serves it: enough phases to actually cover the ground, a deterministic
gate wherever a command can decide, fresh eyes on the result (a `critic` phase), and a
report that says what was verified, what was not, and what it all means.

Depth is measured by what is at stake, not by how many phases re-read the same work:
verification is spent where a wrong claim would cost the user something. When a shortcut
and the expert's way disagree, take the expert's way. Trim the work only when the task is
genuinely small — and then prefer not writing a workflow at all.

## 1. Is this the right tool, and how big should it be?

| The request | The tool |
| --- | --- |
| One thing delegated to one agent | `Agent` |
| A few independent lookups, nobody reading anybody's answer | `Agent`, in parallel |
| Anything else the user did not name a workflow for — however many steps it needs | `Agent`, or do it yourself |
| The user said "use a workflow" / "用工作流" — any phrasing naming workflow as the means | `CreateWorkflow`, mandatory, even if `Agent` would have done |
| "Run the expert pipeline" / `/expert` | the built-in `expert` definition via `CreateWorkflow` `name` |

**Only an explicit request starts a workflow.** A task looking orchestration-shaped is
never a reason by itself. But once the user names workflow as the means, that choice is
binding: not `Agent`, not doing it inline, not "too small for a workflow". What remains is
only how big the spec should be — the smallest task still gets a small spec rather than
something else.

## 2. Designing the phase pipeline

A run executes `phaseOrder` in order; each phase is a child agent session that writes its
Markdown artifact to `artifactPath`. Later phases read the earlier artifacts off disk —
the file, not a variable, is how phases hand work to each other.

- `agent` (default): one child session runs the phase prompt end to end.
- `scheduled_graph`: the phase executes a dependency DAG of task nodes with bounded
  concurrency. Pair it with an earlier `agent` phase that emits a graph JSON contract
  (`seedGraphFromArtifact`), or with `nodePromptsFromArtifact` to turn an earlier artifact
  into per-node prompts.
- `critic`: reviews the accumulated artifacts against the task and can reopen failed
  nodes; bounded by `strategy.finalCritic.maxIterations`.
- `complete`: terminal phase — writes the final report artifact and settles the run.

Canonical shapes:

- **Linear review**: `analyze` → `verify` (critic) → `complete`. Small, for audits and
  reviews.
- **Decompose → execute → critic** (the `expert` shape): clarify/analysis phases → an
  `agent` phase whose artifact is the seeded graph JSON → `scheduled_graph` exec →
  `critic` → `complete`. For NL→code work.
- **Fan-out report**: `agent` research phase → `scheduled_graph` with one node per
  independent unit → `complete`. For "check every file/service/endpoint" sweeps.

See `patterns.md` next to this file for worked topologies and `examples.md` for complete
specs you can adapt verbatim.

## 3. Rules a spec must satisfy

1. `phaseOrder` lists every phase exactly once, in execution order; every `phases[].phase`
   appears in `phaseOrder` and vice versa (`validate()` rejects otherwise).
2. Every phase needs a concrete `description` — it becomes the child agent's phase
   objective. Write what "done" means, not a label.
3. Give phases that produce hand-offs an `artifactPath` under `artifacts/` so downstream
   phases and the user can open them.
4. A `scheduled_graph` phase needs its DAG from somewhere: `seedGraphFromArtifact` (an
   earlier phase's artifact containing the graph JSON contract) or
   `nodePromptsFromArtifact` (an earlier artifact supplying per-node prompts). Without one
   it runs as a single phase node.
5. `task` in the `CreateWorkflow` call is passed verbatim to every child agent — write it
   as a complete standalone instruction that needs no conversation context.
6. `definitionId` is file-safe (letters, digits, dot, dash, underscore) and stable —
   `SaveWorkflow` recalls by it; bump `definitionVersion` when the pipeline changes.
7. `strategy` — omit it only if your tool path supplies the default; when writing it out,
   start from the defaults and change only what the task demands: raise
   `executor.maxConcurrentLoops` for wide fan-out, raise `finalCritic.maxIterations` when
   the critic is expected to actually reopen work.

## 4. Spec schema reference

`spec` is a single JSON object decoded as `WorkflowDefinition`:

```json
{
  "definitionId": "pr-review",
  "definitionVersion": "1",
  "kind": "custom",
  "title": "PR review",
  "description": "optional one-liner shown in listings",
  "phaseOrder": ["review", "critic", "complete"],
  "phases": [
    {
      "phase": "review",
      "title": "Review",
      "description": "what this phase must accomplish",
      "behavior": "agent",
      "artifactPath": "artifacts/01-review.md",
      "seedGraphFromArtifact": { "targetPhase": "exec", "gateAfterPhase": "plan" },
      "nodePromptsFromArtifact": { "targetPhase": "exec" }
    }
  ],
  "strategy": {
    "clarify":   { "confidenceThreshold": 0.8, "maxRounds": 3, "minRounds": 1 },
    "executor":  { "drainingChangeHours": 1.0, "frontierTarget": 3, "maxConcurrentLoops": 2, "maxConsecutiveErrors": 3, "maxPlannerRuns": 10 },
    "finalCritic": { "maxIterations": 3 },
    "reactLoop": { "maxRounds": 30 }
  }
}
```

- `behavior`: `agent` | `scheduled_graph` | `critic` | `complete` (default `agent`).
- `seedGraphFromArtifact.targetPhase`: which later `scheduled_graph` phase consumes the
  graph JSON this phase writes; `gateAfterPhase` optionally names the phase after which the
  seed is applied.
- `nodePromptsFromArtifact.targetPhase`: which later `scheduled_graph` phase takes its
  per-node prompts from this phase's artifact.
- The seeded graph contract a phase must emit (fenced ```json or raw):
  `{"nodes":[{"id","title","description","dependsOn":[],"collectionId","prompt"}],
  "edges":[{"from","to"}],"collections":[{"collectionId","title","nodeIds","explorable",
  "goal","metric"}],"reasoning"}` — unique node ids, edges only between real ids, no cycles.
- Runs settle to `completed` / `failed` / `cancelled`; a `paused` or `failed` run resumes
  via `ResumeWorkflowRun` from the persisted snapshot — completed nodes are not re-run.

## 5. The authoring loop

1. Draft the spec → `EvalWorkflowSnippet` to dry-run it (schema, phase-order consistency,
   the phase graph it would execute).
2. Submit via `CreateWorkflow` with `spec` + `task` + optional `title`. The run goes to the
   background; the result names its `runId`.
3. Monitor with `GetWorkflowRun` / `ListWorkflowRuns`; inspect the scheduler with
   `GetWorkflowRunRoster`; stop with `CancelWorkflowRun`.
4. To keep a spec for later, `SaveWorkflow` it — then anyone can launch it with
   `CreateWorkflow` `name` (which does **not** require this skill: running a saved
   workflow is not authoring one).
5. To fix a saved spec, `AmendWorkflow` with `name` + replacement `spec` (requires this
   skill). Title/description-only patches do not.

Do not paste the same rejected spec again — read the error, fix the named field, resubmit.
A validation error names the phase or field at fault.
