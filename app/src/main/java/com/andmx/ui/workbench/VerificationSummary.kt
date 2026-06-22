package com.andmx.ui.workbench

import com.andmx.agent.ToolArgs
import com.andmx.ui.conversation.ChatItem

enum class VerificationState { PASSED, FAILED, RUNNING }

internal data class VerificationEntry(
    val key: Long,
    val command: String,
    val state: VerificationState,
    val detail: String,
)

internal fun verificationEntries(items: List<ChatItem>, limit: Int = 6): List<VerificationEntry> =
    items.mapNotNull { item ->
        when (item) {
            is ChatItem.ToolUse -> item.toVerificationEntry()
            is ChatItem.Assistant -> item.toLocalDiagnosticEntry()
            else -> null
        }
    }.takeLast(limit).asReversed()

internal fun verificationHandoffLines(entries: List<VerificationEntry>): List<String> =
    entries.map { entry ->
        "${entry.state.verificationStateLabel()} · ${entry.command}: ${entry.detail.ifBlank { "(无输出摘要)" }}"
    }

private fun ChatItem.ToolUse.toVerificationEntry(): VerificationEntry? {
    if (name != "run_shell") return null
    val command = ToolArgs.value(args, "command").trim()
    if (!command.looksLikeVerificationCommand()) return null
    return VerificationEntry(
        key = key,
        command = command,
        state = when {
            running -> VerificationState.RUNNING
            error -> VerificationState.FAILED
            else -> VerificationState.PASSED
        },
        detail = output.orEmpty().verificationPreview(),
    )
}

private fun ChatItem.Assistant.toLocalDiagnosticEntry(): VerificationEntry? {
    val text = text
    if (!text.contains("## 执行环境摘要") && !text.contains("# AndMX 执行环境探针")) return null
    return VerificationEntry(
        key = key,
        command = "/diag",
        state = if (text.contains("✗") || text.contains("失败")) VerificationState.FAILED else VerificationState.PASSED,
        detail = text.verificationPreview(),
    )
}

private fun String.looksLikeVerificationCommand(): Boolean {
    val command = lowercase()
    return listOf(
        "test",
        "assemble",
        "compile",
        "check",
        "lint",
        "gradlew",
        "pytest",
        "npm run build",
        "npm test",
        "pnpm test",
        "pnpm build",
        "yarn test",
        "yarn build",
    ).any { command.contains(it) }
}

private fun String.verificationPreview(limit: Int = 140): String =
    lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .filterNot { it.startsWith("> Task ") }
        .lastOrNull { line ->
            line.contains("BUILD", ignoreCase = true) ||
                line.contains("SUCCESS", ignoreCase = true) ||
                line.contains("FAIL", ignoreCase = true) ||
                line.contains("error", ignoreCase = true) ||
                line.contains("passed", ignoreCase = true) ||
                line.contains("failed", ignoreCase = true) ||
                line.contains("结论")
        }
        ?.take(limit)
        ?: lineSequence().map { it.trim() }.filter { it.isNotBlank() }.lastOrNull()?.take(limit).orEmpty()

fun VerificationState.verificationStateLabel(): String = when (this) {
    VerificationState.PASSED -> "通过"
    VerificationState.FAILED -> "失败"
    VerificationState.RUNNING -> "运行中"
}
