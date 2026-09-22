package com.andmx.agent

/** 上游 builtin-prompt-command 的 /init 移植：展开为一条用户 prompt 交给 agent 执行。 */
object InitPrompt {

    fun build(args: String, workingDirectory: String): String {
        val targetPath = "$workingDirectory/AGENTS.md"
        val additional = if (args.isNotBlank()) {
            "\nAdditional user instructions supplied with /init:\n```text\n$args\n```"
        } else ""
        return """
            You are running AndMX's built-in /init command.

            Your task is to create or update a concise workspace instruction file for future AndMX agents.

            Target:
            - Workspace directory: $workingDirectory
            - Instruction file: $targetPath
            - Existing hidden instruction candidates: $workingDirectory/.andmx/AGENTS.md and $workingDirectory/.agents/AGENTS.md
            - File name must be exactly AGENTS.md.
            - This command targets the current workspace only. Do not write ~/.andmx/AGENTS.md.$additional

            Process:
            1. First check whether .andmx/AGENTS.md or .agents/AGENTS.md exists in the workspace. If either exists, tell the user they already have an instructions file, mention the path found, and stop without creating a new AGENTS.md.
            2. Inspect the repository before writing. Prefer Read, Glob, Grep, and safe Bash commands such as ls, find, git status, and package-manager script inspection.
            3. If AGENTS.md already exists, read it first and update it with Edit instead of replacing it wholesale.
            4. If AGENTS.md does not exist, create it at the workspace root.
            5. Keep the file practical and short enough for future agents to read quickly.
            6. Include only project-specific facts future AndMX agents would otherwise miss.
            7. Ask the user only if a repository-specific decision cannot be inferred and would materially change the file.

            Recommended AGENTS.md content:
            - Repository purpose and major directories.
            - Build, typecheck, lint, and focused test commands discovered from the repo.
            - Architecture boundaries and layer rules that matter for edits.
            - Coding conventions, import/path rules, logging rules, UI/design rules, and platform compatibility constraints if present.
            - Any documentation files that agents should read before changing sensitive areas.

            After creating or editing AGENTS.md, summarize the main sections you wrote and mention the file path.
        """.trimIndent()
    }
}
