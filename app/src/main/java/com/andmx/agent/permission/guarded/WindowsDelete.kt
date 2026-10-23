package com.andmx.agent.permission.guarded

private val ATTR = Regex("^A(?::?[-RASHIO]*)$")
private val DRIVE_PATH = Regex("^/[a-z]/", RegexOption.IGNORE_CASE)

private val ROBOCOPY_FLAGS = (
    "/MIR /PURGE /L /S /E /Z /B /ZB /J /EFSRAW /SEC /COPYALL /NOCOPY /SECFIX /TIMFIX /CREATE /FAT /256 /PF " +
        "/SJ /SL /NODCOPY /NOOFFLOAD /COMPRESS /A /M /XC /XN /XO /XX /XL /IM /IS /IT /XJ /FFT /DST /XJD /XJF " +
        "/TBD /LFSM /X /V /TS /FP /BYTES /NS /NC /NFL /NDL /NP /ETA /TEE /NJH /NJS /UNICODE /NP /?"
    ).split(" ").toSet()

private val ROBOCOPY_VALUES = (
    "/LEV /COPY /DCOPY /A+ /A- /MON /MOT /RH /IPG /MT /IOMAXSIZE /IORATE /THRESHOLD /IA /XA /MAX /MIN " +
        "/MAXAGE /MINAGE /MAXLAD /MINLAD /R /W /LFSM /LOG /LOG+ /UNILOG /UNILOG+"
    ).split(" ").toSet()

fun matchCmdDelete(name: String, args: List<CommandWord>): RuleMatch {
    if (args.size == 1 && args[0].value == "/?" && !args[0].dynamic) return RuleMatch.Miss
    val tree = name == "rd" || name == "rmdir"
    var recursive = false
    var target = false
    var unsupported = false
    val allowed = buildList {
        add("Q")
        add("?")
        if (!tree) {
            add("P")
            add("F")
        }
    }
    for (word in args) {
        val value = word.value.uppercase()
        if (!value.startsWith("/") || word.dynamic) {
            target = true
            continue
        }
        for (option in value.substring(1).split("/")) {
            val attr = !tree && ATTR.matches(option)
            if (option == "S") recursive = true
            else if (option !in allowed && !attr) unsupported = true
        }
    }
    if (target && (!tree || recursive)) {
        return RuleMatch.Hit(if (tree) "cmd-remove-tree" else "cmd-delete-files")
    }
    return if (unsupported) RuleMatch.Unsupported else RuleMatch.Miss
}

fun matchRobocopyDelete(args: List<CommandWord>, dialect: ShellDialect): RuleMatch {
    if (args.size < 2) return RuleMatch.Miss
    var deletion = false
    var listOnly = false
    var paths = 0
    var exclusions = false
    for (word in args) {
        val value = word.value.uppercase()
        if (word.dynamic) return if (deletion) RuleMatch.Hit("robocopy-delete") else RuleMatch.Unsupported
        val drivePath = dialect == ShellDialect.GIT_BASH && DRIVE_PATH.containsMatchIn(word.value)
        if (!value.startsWith("/") || drivePath) {
            if (!exclusions) paths++
            continue
        }
        exclusions = value == "/XF" || value == "/XD"
        if (value == "/MIR" || value == "/PURGE") {
            deletion = true
        } else if (value == "/L") {
            listOnly = true
        } else if (value == "/XF" || value == "/XD" || value in ROBOCOPY_FLAGS) {
            continue
        } else if (value.contains(":") && value.substringBefore(":") in ROBOCOPY_VALUES) {
            continue
        } else {
            return if (deletion) RuleMatch.Hit("robocopy-delete") else RuleMatch.Unsupported
        }
    }
    return if (paths >= 2 && deletion && !listOnly) RuleMatch.Hit("robocopy-delete") else RuleMatch.Miss
}
