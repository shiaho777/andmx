package com.andmx.agent.permission.guarded

import com.andmx.agent.permission.analyzeBashCommand

enum class ShellDialect {
    POSIX,
    GIT_BASH,
    CMD,
    LEGACY_SHELL,
}

sealed class DangerousCommandResult {
    abstract val status: String

    data class Matched(val ruleId: String, val start: Int, val end: Int) : DangerousCommandResult() {
        override val status: String = "matched"
    }

    data object NotMatched : DangerousCommandResult() {
        override val status: String = "notMatched"
    }

    data object Unsupported : DangerousCommandResult() {
        override val status: String = "unsupported"
    }
}

private val RM = optionTable(
    "-r -R --recursive -f --force -i -I --one-file-system --preserve-root --no-preserve-root -v --verbose -d --dir --help --version",
    "",
    "--interactive",
)
private val RSYNC_DELETE = listOf(
    "--delete",
    "--del",
    "--delete-before",
    "--delete-during",
    "--delete-delay",
    "--delete-after",
    "--delete-excluded",
)
private val RSYNC = optionTable(
    "-a -r -v -q -z -h -P -p -t -g -o -D -l -H -A -X -u -c -n --dry-run --archive --recursive --verbose --quiet --compress --progress --partial --checksum --update --numeric-ids --ignore-errors --force --help --version ${RSYNC_DELETE.joinToString(" ")}",
    "-e --rsh -f --filter --include --exclude --include-from --exclude-from --files-from --rsync-path --log-file --out-format --backup-dir --suffix --chmod --chown --max-delete --timeout --bwlimit --compare-dest --copy-dest --link-dest",
)
private val INTERPRETERS = setOf(
    "bash",
    "sh",
    "zsh",
    "cmd",
    "powershell",
    "pwsh",
    "eval",
    "source",
    ".",
    "python",
    "python3",
    "node",
    "perl",
    "ruby",
)
private val MKFS = Regex("^mkfs\\.[a-zA-Z0-9_-]+$")
private val QUERY = setOf("--help", "--version")
private const val MAX_LENGTH = 10_000
private val BSD_TARGETS = OptionScanPolicy(OptionOrdering.STOP_AT_OPERAND, UnknownOption.CONTINUE)

fun matchDangerousCommand(command: String, dialect: ShellDialect): DangerousCommandResult {
    if (command.length > MAX_LENGTH || dialect == ShellDialect.LEGACY_SHELL) {
        return DangerousCommandResult.Unsupported
    }
    val windows = dialect == ShellDialect.CMD || dialect == ShellDialect.GIT_BASH
    val commands: List<List<CommandWord>>
    var unsupported: Boolean
    if (dialect == ShellDialect.CMD) {
        val analysis = analyzeCmdCommand(command)
        commands = analysis.commands
        unsupported = analysis.unsupported
    } else {
        val analysis = analyzeBashCommand(command)
        if (analysis.hasParseErrors) return DangerousCommandResult.Unsupported
        commands = analysis.commands.map { it.words }
        unsupported = analysis.hasUnsupportedSyntax
    }
    for (invocation in commands) {
        val words = unwrapCommand(invocation, windows)
        if (words.isNullOrEmpty()) {
            unsupported = true
            continue
        }
        val name = commandBasename(words[0], windows)
        val result = if (name != null) matchInvocation(name, words.subList(1, words.size), dialect) else null
        if (result is RuleMatch.Hit) {
            return DangerousCommandResult.Matched(
                "safety.bash.${result.id}",
                words[0].start,
                words.last().end,
            )
        }
        if (result == null || result is RuleMatch.Unsupported || words.any { it.dynamic }) unsupported = true
    }
    return if (unsupported) DangerousCommandResult.Unsupported else DangerousCommandResult.NotMatched
}

private fun matchInvocation(name: String, args: List<CommandWord>, dialect: ShellDialect): RuleMatch? {
    if (name == "git") return matchGit(args)
    if (name in INTERPRETERS) return null
    if (dialect == ShellDialect.CMD && name in listOf("rd", "rmdir", "del", "erase")) return matchCmdDelete(name, args)
    if (dialect != ShellDialect.CMD && name == "find") return matchFindDelete(args)
    if ((dialect == ShellDialect.CMD || dialect == ShellDialect.GIT_BASH) && name == "robocopy") {
        return matchRobocopyDelete(args, dialect)
    }
    if (name == "rm") {
        val parsed = parseOptions(args, RM, COMMAND_SCAN)
        val targets = parseOptions(args, RM, BSD_TARGETS)
        val hit = enabled(parsed.flags, listOf("-r", "-R", "--recursive")) ||
            targets.operands.size >= 2 ||
            targets.operands.any { it.hasUnquotedGlob }
        return settleRule(if (hit) "remove-critical-path" else null, parsed)
    }
    if (name in listOf("dd", "mount", "umount", "mkfs") || MKFS.matches(name)) {
        if (args.size == 1 && !args[0].dynamic && args[0].value in QUERY) return RuleMatch.Miss
        return if (!(name == "mount" && args.isEmpty())) RuleMatch.Hit("system-storage") else RuleMatch.Miss
    }
    if (name == "rsync") {
        val parsed = parseOptions(args, RSYNC, COMMAND_SCAN)
        val exempt = parsed.flags.any { it == "-n" || it == "--dry-run" || it == "--help" || it == "--version" }
        val hit = !exempt && parsed.flags.any { it in RSYNC_DELETE }
        return settleRule(if (hit) "rsync-delete" else null, parsed)
    }
    return RuleMatch.Miss
}
