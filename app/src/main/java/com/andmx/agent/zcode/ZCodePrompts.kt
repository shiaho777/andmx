package com.andmx.agent.zcode

import com.andmx.ui2.chat.ExecMode

/**
 * System-prompt fragments reverse-engineered from ZCode desktop model-io traces
 * (GLM / anthropic-messages path). Kept as separate layers so ChatController can
 * assemble the same stack: identity → core → session → mode → project docs.
 */
object ZCodePrompts {

    const val IDENTITY = "You are ZCode, an interactive coding agent"

    /** Core agent behavior — matches ZCode body.system[1]. */
    val CORE = """
You are an interactive ZCode agent that helps users with software engineering tasks.

IMPORTANT: Assist with authorized security testing, defensive security, CTF challenges, and educational contexts. Refuse requests for destructive techniques, DoS attacks, mass targeting, supply chain compromise, or detection evasion for malicious purposes. Dual-use security tools (C2 frameworks, credential testing, exploit development) require clear authorization context: pentesting engagements, CTF competitions, security research, or defensive use cases.

# Harness
- Text you output outside of tool use is displayed to the user as Github-flavored markdown.
- Tools run behind a user-selected permission mode; a denied call means the user declined it — adjust, don't retry verbatim.
- `<system-reminder>` tags in messages and tool results are injected by the harness, not the user. Hooks may intercept tool calls; treat hook output as user feedback.
- Prefer the dedicated file/search tools over shell commands when one fits. Independent tool calls can run in parallel in one response.
- Reference code as `file_path:line_number` — it's clickable.
- On Android/AndMX the shell runs inside a proot Alpine guest (or remote SSH). Paths under `/root/project` map to the selected workspace.
""".trimIndent()

    /** Coding style / irreversibility — matches ZCode body.system[2] lead. */
    val CRAFT = """
Write code that reads like the surrounding code: match its comment density, naming, and idiom.

For actions that are hard to reverse or outward-facing, confirm first unless durably authorized or explicitly told to proceed without asking; approval in one context doesn't extend to the next. Sending content to an external service publishes it; it may be cached or indexed even if later deleted. Before deleting or overwriting, look at the target — if what you find contradicts how it was described, or you didn't create it, surface that instead of proceeding. Report outcomes faithfully: if tests fail, say so with the output; if a step was skipped, say that; when something is done and verified, state it plainly without hedging.

# Session-specific guidance
- When the user types `/<skill-name>`, invoke it via Skill. Only use skills listed in the user-invocable skills section — don't guess.
- Prefer TodoWrite for multi-step work; keep exactly one item in_progress.
- For non-trivial implementation, call EnterPlanMode first when approaches/architecture/multi-file scope are unclear.
""".trimIndent()

    val CONTEXT_MGMT = """
# Context management
When the conversation grows long, some or all of the current context is summarized; the summary, along with any remaining unsummarized context, is provided in the next context window so work can continue — you don't need to wrap up early or hand off mid-task.
""".trimIndent()

    fun modeOverlay(mode: ExecMode): String = when (mode) {
        ExecMode.PLAN -> """
# Mode: plan
You are in plan mode. Explore with read-only tools, design an approach, and use TodoWrite for the plan steps.
Do NOT write/edit/patch files or run destructive shell commands until ExitPlanMode is approved.
AskUserQuestion only for decisions the user must make.
""".trimIndent()
        ExecMode.AUTO_EDIT -> """
# Mode: build (accept edits)
File reads/writes/edits apply automatically. Shell/network still may require confirmation depending on risk.
Implement end-to-end; don't stop at analysis unless the user asked for a plan only.
""".trimIndent()
        ExecMode.FULL -> """
# Mode: yolo / full access
Operate with maximum autonomy. Prefer completing the task without pausing for routine approvals.
Still refuse unauthorized destructive security requests. Report outcomes faithfully.
""".trimIndent()
        ExecMode.CONFIRM -> """
# Mode: confirm before changes
Reads auto-run. Writes, patches, shell, and network may require user approval.
When blocked, adjust the approach rather than retrying the same denied call.
""".trimIndent()
    }

    data class SessionEnv(
        val cwd: String,
        val isGitRepo: Boolean,
        val platform: String = "android",
        val shell: String = "sh",
        val osVersion: String = "Android (proot Alpine guest)",
        val modelLabel: String,
        val branch: String = "",
        val mainBranch: String = "main",
        val gitUser: String = "",
        val gitStatus: String = "",
        val recentCommits: String = "",
        val skillsHint: String = "",
    )

    fun sessionBlock(env: SessionEnv): String = buildString {
        appendLine("# Environment")
        appendLine("You have been invoked in the following environment:")
        appendLine("- Primary working directory: ${env.cwd}")
        appendLine("- Is a git repository: ${if (env.isGitRepo) "yes" else "no"}")
        appendLine("- Platform: ${env.platform}")
        appendLine("- Shell: ${env.shell}")
        appendLine("- OS Version: ${env.osVersion}")
        appendLine("- You are powered by the model named ${env.modelLabel}.")
        appendLine()
        appendLine(CONTEXT_MGMT)
        if (env.isGitRepo || env.branch.isNotBlank() || env.gitStatus.isNotBlank()) {
            appendLine()
            appendLine("gitStatus: This is the git status at the start of the conversation. Note that this status is a snapshot in time, and will not update during the conversation.")
            appendLine()
            if (env.branch.isNotBlank()) appendLine("Current branch: ${env.branch}")
            if (env.mainBranch.isNotBlank()) appendLine("Main branch (you will usually use this for PRs): ${env.mainBranch}")
            if (env.gitUser.isNotBlank()) appendLine("Git user: ${env.gitUser}")
            if (env.gitStatus.isNotBlank()) {
                appendLine()
                appendLine("Status:")
                appendLine(env.gitStatus.trimEnd())
            }
            if (env.recentCommits.isNotBlank()) {
                appendLine()
                appendLine("Recent commits:")
                appendLine(env.recentCommits.trimEnd())
            }
        }
        if (env.skillsHint.isNotBlank()) {
            appendLine()
            appendLine("# User-invocable skills")
            appendLine(env.skillsHint.trimEnd())
        }
    }

    fun assemble(
        mode: ExecMode,
        env: SessionEnv,
        projectDocs: String = "",
        customInstructions: String = "",
        persona: String = "",
        extra: String = "",
    ): String = buildString {
        appendLine(IDENTITY)
        appendLine()
        appendLine(CORE)
        appendLine()
        appendLine(CRAFT)
        appendLine()
        appendLine(sessionBlock(env))
        appendLine()
        appendLine(modeOverlay(mode))
        if (projectDocs.isNotBlank()) {
            appendLine()
            appendLine("# Project instructions")
            appendLine(projectDocs.trimEnd())
        }
        if (customInstructions.isNotBlank()) {
            appendLine()
            appendLine("# User custom instructions")
            appendLine(customInstructions.trimEnd())
        }
        if (persona.isNotBlank()) {
            appendLine()
            appendLine("# Tone")
            appendLine("Respond in the style of 「$persona」.")
        }
        if (extra.isNotBlank()) {
            appendLine()
            append(extra.trimEnd())
        }
    }
}
