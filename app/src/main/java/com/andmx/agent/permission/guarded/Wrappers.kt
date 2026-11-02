package com.andmx.agent.permission.guarded

private val WRAPPERS = mapOf(
    "env" to optionTable("-i --ignore-environment -0 --null -v --debug", "-u --unset -C --chdir"),
    "time" to optionTable(
        "-a --append -p --portability -v --verbose -q --quiet",
        "-o --output -f --format",
    ),
    "sudo" to optionTable(
        "-A --askpass -b --background -E --preserve-env -H --set-home -K --remove-timestamp -k --reset-timestamp -n --non-interactive -S --stdin",
        "-u --user -g --group -h --host -p --prompt -C --close-from -T --command-timeout -D --chdir -R --chroot",
    ),
)

private val ASSIGNMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*=")

fun unwrapCommand(input: List<CommandWord>, windows: Boolean): List<CommandWord>? {
    var words = input
    while (words.isNotEmpty()) {
        val name = commandBasename(words[0], windows) ?: return null
        val table = WRAPPERS[name] ?: return words
        val parsed = parseOptions(words.subList(1, words.size), table, WRAPPER_SCAN)
        if (parsed.unsupported) return null
        words = parsed.operands
        if (name == "env" || name == "sudo") {
            while (words.isNotEmpty() && ASSIGNMENT.containsMatchIn(words[0].value)) {
                words = words.subList(1, words.size)
            }
        }
    }
    return null
}
