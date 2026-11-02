package com.andmx.agent.permission.guarded

private val HELP = "-h --help"
private val COMMON = "$HELP -q --quiet -v --verbose"
private val FRONT = optionTable(
    "--no-pager -P --paginate -p --literal-pathspecs --glob-pathspecs --noglob-pathspecs --icase-pathspecs --bare --no-replace-objects --no-optional-locks",
    "-C --git-dir --work-tree --namespace -c --config-env --super-prefix",
    "--exec-path",
)
private val RESET = optionTable(
    "$COMMON --hard --soft --mixed --merge --keep -p --patch -N --intent-to-add --no-refresh --refresh --recurse-submodules --no-recurse-submodules",
)
private val CLEAN = optionTable(
    "$COMMON -f --force -d -x -X -n --dry-run --no-dry-run -i --interactive",
    "-e --exclude",
)
private val PUSH = optionTable(
    "$COMMON -f --force --no-force --mirror --no-mirror -n --dry-run --no-dry-run --no-force-with-lease --force-if-includes --no-force-if-includes --all --branches --tags --delete -d --prune --atomic --no-atomic --porcelain --follow-tags --no-follow-tags --set-upstream -u --thin --no-thin --progress --no-progress --ipv4 -4 --ipv6 -6",
    "--repo --receive-pack --exec -o --push-option",
    "--force-with-lease --signed --recurse-submodules",
)
private val CHECKOUT = optionTable(
    "$COMMON -f --force --no-force --detach --ours --theirs -m --merge --conflict -p --patch --ignore-other-worktrees --recurse-submodules --no-recurse-submodules --no-overlay --overlay",
    "-b -B --orphan --conflict",
)
private val RESTORE = optionTable(
    "$COMMON -W --worktree --no-worktree -S --staged --no-staged --ours --theirs -m --merge -p --patch --ignore-unmerged --overlay --no-overlay --recurse-submodules --no-recurse-submodules",
    "-s --source --conflict",
)
private val FORCE = listOf("-f", "--force")
private val TREE = setOf(".", "./", ":/")
private val HARD_OFF = listOf("--soft", "--mixed", "--merge", "--keep", "-p", "--patch")

fun matchGit(words: List<CommandWord>): RuleMatch {
    val front = parseOptions(words, FRONT, WRAPPER_SCAN)
    if (front.unsupported || front.operands.firstOrNull()?.dynamic == true) return RuleMatch.Unsupported
    val head = front.operands.firstOrNull() ?: return RuleMatch.Miss
    val args = front.operands.drop(1)
    when (head.value) {
        "stash" -> {
            if (args.firstOrNull()?.dynamic == true) return RuleMatch.Unsupported
            if (args.firstOrNull()?.value != "clear") return RuleMatch.Miss
            val parsed = parseOptions(args.drop(1), optionTable(HELP), COMMAND_SCAN)
            return settleRule(if (!hasHelp(parsed.flags)) "git-stash-clear" else null, parsed)
        }
        "worktree" -> {
            if (args.firstOrNull()?.dynamic == true) return RuleMatch.Unsupported
            if (args.firstOrNull()?.value != "remove") return RuleMatch.Miss
            val parsed = parseOptions(args.drop(1), optionTable("$HELP -f --force --no-force"), COMMAND_SCAN)
            val hit = !hasHelp(parsed.flags) && enabled(parsed.flags, FORCE, listOf("--no-force"))
            return settleRule(if (hit) "git-worktree-force-remove" else null, parsed)
        }
        "reset", "clean", "push", "checkout", "restore" -> Unit
        else -> return RuleMatch.Unsupported
    }
    val table = when (head.value) {
        "reset" -> RESET
        "clean" -> CLEAN
        "push" -> PUSH
        "checkout" -> CHECKOUT
        else -> RESTORE
    }
    val parsed = parseOptions(args, table, COMMAND_SCAN)
    val flags = parsed.flags
    val operands = parsed.operands
    if (hasHelp(flags)) return RuleMatch.Miss
    val dryRun = enabled(flags, listOf("-n", "--dry-run"), listOf("--no-dry-run"))
    val force = enabled(flags, FORCE, listOf("--no-force"))
    return when (head.value) {
        "reset" -> settleRule(if (enabled(flags, listOf("--hard"), HARD_OFF)) "git-reset-hard" else null, parsed)
        "clean" -> settleRule(if (!dryRun && force) "git-clean-force" else null, parsed)
        "push" -> {
            val refspecs = if ("--repo" in flags) operands else operands.drop(1)
            val hit = !dryRun && (
                force ||
                    enabled(flags, listOf("--force-with-lease"), listOf("--no-force-with-lease")) ||
                    enabled(flags, listOf("--mirror"), listOf("--no-mirror")) ||
                    refspecs.any { it.value.startsWith("+") }
                )
            settleRule(if (hit) "git-push-force" else null, parsed)
        }
        "checkout" -> {
            val tree = operands.any { !it.dynamic && it.value in TREE }
            settleRule(if (force || tree) "git-discard-tree" else null, parsed)
        }
        "restore" -> {
            val stagedOnly = enabled(flags, listOf("-S", "--staged"), listOf("--no-staged")) &&
                !enabled(flags, listOf("-W", "--worktree"), listOf("--no-worktree"))
            val tree = operands.any { !it.dynamic && it.value in TREE }
            settleRule(if (!stagedOnly && tree) "git-discard-tree" else null, parsed)
        }
        else -> RuleMatch.Miss
    }
}

private fun hasHelp(flags: List<String>): Boolean = "--help" in flags || "-h" in flags
