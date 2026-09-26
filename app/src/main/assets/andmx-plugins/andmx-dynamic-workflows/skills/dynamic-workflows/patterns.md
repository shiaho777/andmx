# Workflow spec patterns

Adapted topology patterns for `WorkflowDefinition` specs. All phase ids are arbitrary
strings — pick ones that read well in `phaseOrder` and in the run UI.

## Pattern: linear review (small)

Three phases, no graph. Use for audits, reviews, and surveys where the deliverable is a
verified judgment.

```
scan  (agent)   — gather the facts, write artifacts/01-scan.md
check (critic)  — re-verify every claim the user will act on
done  (complete)— final report
```

Size the critic by stakes: a bug the user will fix on your word gets a real critic pass;
a brainstorm gets at most a reader.

## Pattern: decompose → execute → critic (the expert shape)

The general NL→code shape. An `agent` planning phase emits the graph JSON contract; the
`scheduled_graph` phase executes the DAG with bounded concurrency.

```
clarify (agent)        → artifacts/01-clarify.md
plan    (agent)        → artifacts/02-plan.md   emits the seeded graph JSON,
                         phase carries seedGraphFromArtifact {targetPhase:"exec"}
exec    (scheduled_graph) — consumes the seeded DAG; nodes run as child sessions
critic  (critic)       → artifacts/04-critic.md  reopens failed nodes within maxIterations
done    (complete)     → report
```

Add `nodePromptsFromArtifact {targetPhase:"exec"}` to the planning phase when node prompts
should come from a separate meta-prompt artifact rather than the graph JSON itself.

## Pattern: fan-out sweep

One node per independent unit — per file, per service, per endpoint. The planning phase
enumerates units into the graph JSON; `executor.frontierTarget` / `maxConcurrentLoops`
bound how many run at once.

```
enumerate (agent)           — list the units, emit graph JSON with one node each
sweep     (scheduled_graph) — one child session per unit
done      (complete)
```

## Pattern: staged rollout

Sequential `agent` phases where each gates the next through its artifact. Use when later
work is meaningless without the previous stage's output on disk.

```
provision → migrate → verify (critic) → complete
```

## Sizing notes

- `executor.maxConcurrentLoops`: parallelism inside `scheduled_graph`. Default 2. Raise
  for wide fan-out; leave alone for staged work.
- `executor.frontierTarget`: how far ahead the scheduler keeps ready nodes. Default 3.
- `finalCritic.maxIterations`: default 3 — the critic can reopen failed nodes this many
  times before the run fails.
- `clarify.*` and `reactLoop.maxRounds`: bounds for interactive clarification and the
  per-node react loop. Defaults are sane; touch them only when the task demands.
