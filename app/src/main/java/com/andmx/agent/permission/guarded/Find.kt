package com.andmx.agent.permission.guarded

private val FIND_FLAGS = (
    "-H -L -P -O0 -O1 -O2 -O3 -depth -d -x -xdev -mount -ignore_readdir_race -noignore_readdir_race " +
        "-daystart -follow -noleaf -true -false -empty -readable -writable -executable -print -print0 -ls " +
        "-prune -quit -a -and -o -or -not ! ( ) ,"
    ).split(" ").toSet()

private val FIND_VALUES = (
    "-D -f -name -iname -path -ipath -wholename -iwholename -regex -iregex -regextype -type -xtype -size " +
        "-user -group -uid -gid -perm -links -inum -samefile -newer -anewer -cnewer -atime -ctime -mtime " +
        "-amin -cmin -mmin -used -maxdepth -mindepth -printf -fprint -fprint0 -fls -files0-from"
    ).split(" ").toSet()

private val FIND_EXEC = setOf("-exec", "-execdir", "-ok", "-okdir")
private val NEWER_PAIR = Regex("^-newer[acmBt][acmBt]$")
private val PRE_EXPR = setOf("-H", "-L", "-P", "-O0", "-O1", "-O2", "-O3", "-d", "-x")

fun matchFindDelete(args: List<CommandWord>): RuleMatch {
    var expression = false
    var i = 0
    while (i < args.size) {
        val word = args[i]
        val value = word.value
        if (!expression && !value.startsWith("-") && value != "!" && value != "(") {
            i++
            continue
        }
        if (word.dynamic) return RuleMatch.Unsupported
        if (!expression && value in PRE_EXPR) {
            i++
            continue
        }
        if (!expression && (value == "-D" || value == "-f")) {
            i++
            if (i >= args.size) return RuleMatch.Unsupported
            i++
            continue
        }
        expression = true
        if (value == "-delete") return RuleMatch.Hit("find-delete")
        if (value in FIND_EXEC) {
            var closed = false
            while (true) {
                i++
                if (i >= args.size) break
                val token = args[i]
                val plusOk = token.value == "+" &&
                    args.getOrNull(i - 1)?.value == "{}" &&
                    (value == "-exec" || value == "-execdir")
                if (!token.dynamic && (token.value == ";" || plusOk)) {
                    closed = true
                    break
                }
            }
            if (!closed) return RuleMatch.Unsupported
        } else if (value == "-fprintf" || value in FIND_VALUES || NEWER_PAIR.matches(value)) {
            i += if (value == "-fprintf") 2 else 1
            if (i >= args.size) return RuleMatch.Unsupported
        } else if (value !in FIND_FLAGS) {
            return RuleMatch.Unsupported
        }
        i++
    }
    return RuleMatch.Miss
}
