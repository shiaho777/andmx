---
name: dynamic-workflows
description: "Argument contract for the dynamic workflow tools when they are in the tool list. A call does not typecheck a script and does not start a run. If a name is not in the tool list, do not call it."
when_to_use: "Only after you have checked the tool list. If CreateWorkflow is absent, do not call it and do not load this skill in order to launch a run. A single delegation belongs to the Agent tool."
---

# Dynamic workflow tools

This file is the argument contract for the dynamic workflow tools. It is not a TypeScript facade and not a JSON WorkflowDefinition. AndMX has no embedded dynamic-workflow runner. Calling one of these tools does not typecheck a script and does not start a run. There is no run id to wait on, and no background run to poll.

Call a tool only when its name is in the tool list. If it is absent, it is not registered in this session. Do not call it. Do not invent a substitute name.

## CreateWorkflow

Pass exactly one source:

- `script` — the script text, inline.
- `saved` — an object `{ "name": "<saved name>" }`, with optional `args` and `scope` (`project` or `global`).
- `path` — a script file. `args` belongs only to this source.

Optional `name` is a display label, not a source. Optional `max_concurrency` is a positive integer. Optional `subagent_model` is a non-empty string.

Do not pass `spec`, `task`, or a bare `name` as the workflow source. A `saved`-only call does not need this skill. A `script` or `path` call does. The result says the workflow was NOT executed: nothing was typechecked and nothing was started.

## AmendWorkflow

Required `run_id`. Pass at most one of `script` or `path`. Omit both to leave the previous script unchanged. Optional `name`, `max_concurrency` (number or null), and `subagent_model` (string or null). A `script` or `path` call needs this skill. The result is `workflow_amend_unavailable`. No revised run is started.

## SaveWorkflow

Required `name` (letters, digits, `.`, `-`, `_`, at most 64 characters), `description`, and `scope` (`project` or `global`). Pass exactly one of `script` or `script_path`. Optional `whenToUse`, `args`, and `run_id`. This skill is required. Nothing is written. The result is `workflow_save_unavailable`.

## EvalWorkflowSnippet

Pass exactly one of `code` or `path`. Optional `timeoutMs` is an integer from 1000 to 600000. This skill is required. The result says the snippet was NOT executed.

## ListWorkflowRuns

Optional `limit` (integer 1–50, default 20) and `statuses` (`completed`, `errored`, `pending`, `running`, `stopped`). This does not list runs. The result is `workflow_introspection_unavailable`, which means the runner is absent, not that the project is empty.

## GetWorkflowRun

Required `run_id`. Same unavailable result. It is not a snapshot and not proof that a run exists.

## ResumeWorkflowRun

Required `run_id`. The result is `workflow_resume_unavailable`. The run is not resumed.

## ResolveWorkflowQuestion

Required `question_id` and `answer`. The result is `workflow_question_answering_unavailable`. The question is not answered.

## FillWorkflowHole

Required `run_id` and `hole_id`. Pass exactly one of `script` or `path`. This skill is required. The result is `workflow_fill_unavailable`. Nothing is spliced into a script.

## ListSavedWorkflows

No arguments. The result is `workflow_saved_unavailable`, which means the runner is absent, not that the archive is empty.

## Actor tools

`submit_result` takes `result` and, on success, ends the turn. `escalate` takes `question` and optional `context` and returns answer text. Neither starts a workflow. Do not use them from the main session unless they are in the tool list.

Do not use `/expert` in place of these tools. Do not report that a run was typechecked or started.
