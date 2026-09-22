package com.andmx.agent

/**
 * 上游 plan 模式 Bash readonly-policy 的 argv 级对齐（其 fig 注册表过大，
 * 这里是等价的白名单分类器）：命令按管道/链式操作符切段，逐段取 argv[0]
 * 查只读表；重定向写、命令替换递归校验；未知命令一律拒绝（fail closed）。
 */
object BashReadonlyPolicy {

    fun isShellTool(toolName: String): Boolean =
        toolName.equals("run_shell", true) || toolName.equals("bash", true) ||
            toolName.equals("shell", true)

    fun isReadOnly(command: String): Boolean {
        val segments = splitSegments(command) ?: return false
        if (segments.isEmpty() || segments.any { it.isBlank() }) return false
        return segments.all { segmentReadOnly(it) }
    }

    private fun segmentReadOnly(segment: String): Boolean {
        val argv = tokenize(segment)
        if (argv.isEmpty()) return false
        var i = 0
        while (i < argv.size && ENV_ASSIGN.matches(argv[i])) i++
        if (i >= argv.size) return true
        var cmd = basename(unquote(argv[i])); i++
        // env/time/nice/timeout 等包装器：跳过自身选项与首个位置参数后重新判定。
        while (i < argv.size && cmd.lowercase() in WRAPPERS) {
            while (i < argv.size && (argv[i].startsWith("-") || ENV_ASSIGN.matches(argv[i]))) i++
            if (i >= argv.size) return true
            // timeout/stdbuf 的时长、nice -n 的数值等首个位置参数跳过。
            if (argv[i].matches(WRAPPER_NUM)) i++
            if (i >= argv.size) return true
            cmd = basename(unquote(argv[i])); i++
        }
        val args = argv.subList(i, argv.size).map { unquote(it) }
        return commandReadOnly(cmd, args, segment)
    }

    private fun commandReadOnly(cmd: String, args: List<String>, raw: String): Boolean {
        val c = cmd.lowercase()
        if (c in ALWAYS_READ) return true
        if (c == "find") return args.none { it in FIND_WRITE_FLAGS }
        if (c == "sed") return args.none { it == "--in-place" || it.matches(SED_IN_PLACE) }
        if (c == "awk" || c == "gawk" || c == "mawk" || c == "nawk") {
            return !raw.contains("system(") && args.none { it.startsWith("-i") || it.startsWith("--inplace") }
        }
        if (c == "git") return gitReadOnly(args)
        if (c == "gh") return ghReadOnly(args)
        if (c == "npm" || c == "pnpm" || c == "yarn") {
            return args.firstOrNull() in NPM_READ
        }
        if (c == "pip" || c == "pip3") return args.firstOrNull() in PIP_READ
        if (c == "cargo") return args.firstOrNull() in CARGO_READ
        if (c == "go") return args.firstOrNull() in GO_READ
        if (c == "docker") return args.firstOrNull() in DOCKER_READ
        if (c == "kubectl") return args.firstOrNull() in KUBE_READ
        if (c == "pm") return args.firstOrNull() in PM_READ
        if (c == "settings") return args.firstOrNull() == "get"
        if (c == "logcat") return false
        if (c == "am" || c == "input") return false
        if (c == "xargs" || c == "sh" || c == "bash" || c == "dash" || c == "zsh" ||
            c == "eval" || c == "source" || c == "." || c == "sudo" || c == "su" ||
            c == "curl" || c == "wget" || c == "ssh" || c == "scp" || c == "rsync" ||
            c == "tee" || c == "dd" || c == "install" || c == "watch"
        ) return false
        if (c in VERSION_ONLY) return args.isEmpty() || args.all { it in SAFE_META_FLAGS }
        return false
    }

    private fun gitReadOnly(args: List<String>): Boolean {
        val sub = args.firstOrNull { !it.startsWith("-") } ?: return true
        if (sub in GIT_READ_NOARGS) return true
        return when (sub) {
            "branch" -> args.drop(1).all { it in GIT_BRANCH_READ_FLAGS || !it.startsWith("-") && args.any { a -> a == "--list" || a == "-l" } }
            "tag" -> args.drop(1).isEmpty() ||
                (("-l" in args || "--list" in args) && args.drop(1).all { !it.startsWith("-") || it in GIT_TAG_READ_FLAGS })
            "remote" -> args.drop(1).isEmpty() || args.drop(1).all { it == "-v" || it == "--verbose" }
            "config" -> {
                val rest = args.drop(1)
                rest.any { it == "--get" || it == "--list" || it == "-l" || it == "--get-all" } ||
                    (rest.none { it.startsWith("--") && it != "--null" } && rest.count { !it.startsWith("-") } <= 1)
            }
            "stash" -> args.getOrNull(1) in setOf("list", "show")
            else -> false
        }
    }

    private fun ghReadOnly(args: List<String>): Boolean {
        val sub = args.take(2).joinToString(" ")
        return args.firstOrNull() in GH_READ_TOP ||
            sub in GH_READ_TWO ||
            (args.firstOrNull() == "api" && args.none { it in GH_API_WRITE_FLAGS })
    }

    /** 段切分：引号与 $( )/` `/（ ）保护；写重定向与子命令内嵌递归校验。 */
    private fun splitSegments(command: String): List<String>? {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        var single = false
        var double = false
        var depth = 0
        while (i < command.length) {
            val ch = command[i]
            when {
                single -> { cur.append(ch); if (ch == '\'') single = false }
                double -> {
                    cur.append(ch)
                    if (ch == '"') double = false
                    else if (ch == '\\' && i + 1 < command.length) { i++; cur.append(command[i]) }
                }
                ch == '\'' -> { single = true; cur.append(ch) }
                ch == '"' -> { double = true; cur.append(ch) }
                ch == '\\' -> { cur.append(ch); if (i + 1 < command.length) { i++; cur.append(command[i]) } }
                ch == '`' -> {
                    val end = command.indexOf('`', i + 1)
                    if (end < 0) return null
                    val inner = command.substring(i + 1, end)
                    if (!isReadOnly(inner)) return null
                    cur.append(" "); i = end
                }
                ch == '$' && i + 1 < command.length && command[i + 1] == '(' -> {
                    var d = 1; var j = i + 2
                    while (j < command.length && d > 0) {
                        if (command[j] == '(') d++ else if (command[j] == ')') d--
                        j++
                    }
                    if (d != 0) return null
                    val inner = command.substring(i + 2, j - 1)
                    if (!isReadOnly(inner)) return null
                    cur.append(" "); i = j - 1
                }
                ch == '(' -> { depth++; cur.append(ch) }
                ch == ')' -> { depth--; if (depth < 0) return null; cur.append(ch) }
                depth == 0 && ch == '>' -> {
                    if (i + 1 < command.length && command[i + 1] == '(') { cur.append(ch); i++; cur.append(command[i]) }
                    else return null
                }
                depth == 0 && (ch == '|' || ch == ';' || ch == '\n') -> {
                    if (ch == '|' && i + 1 < command.length && command[i + 1] == '|') i++
                    out += cur.toString(); cur.setLength(0)
                }
                depth == 0 && ch == '&' -> {
                    if (i + 1 < command.length && command[i + 1] == '&') { out += cur.toString(); cur.setLength(0); i++ }
                    else return null
                }
                else -> cur.append(ch)
            }
            i++
        }
        if (single || double || depth != 0) return null
        out += cur.toString()
        return out
    }

    private fun tokenize(segment: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var single = false
        var double = false
        var has = false
        var i = 0
        while (i < segment.length) {
            val ch = segment[i]
            when {
                single -> { cur.append(ch); if (ch == '\'') single = false }
                double -> {
                    cur.append(ch)
                    if (ch == '"') double = false
                    else if (ch == '\\' && i + 1 < segment.length) { i++; cur.append(segment[i]) }
                }
                ch == '\'' -> { single = true; has = true; cur.append(ch) }
                ch == '"' -> { double = true; has = true; cur.append(ch) }
                ch == '\\' -> { has = true; cur.append(ch); if (i + 1 < segment.length) { i++; cur.append(segment[i]) } }
                ch.isWhitespace() -> { if (has) { out += cur.toString(); cur.setLength(0); has = false } }
                else -> { has = true; cur.append(ch) }
            }
            i++
        }
        if (has) out += cur.toString()
        return out
    }

    private fun unquote(t: String): String =
        t.trim().removeSurrounding("'").removeSurrounding("\"")

    private fun basename(path: String): String = path.substringAfterLast('/')

    private val ENV_ASSIGN = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*")
    private val WRAPPER_NUM = Regex("^[0-9.]+[smhd]?$")

    private val WRAPPERS = setOf("env", "time", "nice", "nohup", "command", "builtin", "timeout", "stdbuf", "exec")

    private val ALWAYS_READ = setOf(
        "ls", "ll", "la", "pwd", "cat", "head", "tail", "file", "stat", "du", "df",
        "wc", "echo", "printf", "true", "false", "test", "[", "printenv", "env", "which",
        "type", "uname", "whoami", "id", "groups", "date", "hostname", "uptime",
        "grep", "egrep", "fgrep", "rg", "sort", "uniq", "cut", "tr", "comm", "cmp",
        "diff", "md5sum", "sha1sum", "sha256sum", "sha512sum", "basename", "dirname",
        "realpath", "readlink", "xxd", "od", "strings", "less", "more", "tree",
        "ps", "free", "lscpu", "lsof", "netstat", "ss", "ifconfig", "ping", "seq",
        "jobs", "alias", "unalias", "cd", "pushd", "popd", "dirs", "export",
        "history", "help", "man", "info", "apropos", "whatis", "tty", "cal",
        "lsusb", "lspci", "getprop", "wm", "dumpsys", "apropos", "column", "paste",
        "join", "nl", "rev", "tac", "fold", "fmt", "expand", "unexpand", "look",
        "zcat", "zless", "zgrep", "bzcat", "xzcat", "ip",
    )

    private val SED_IN_PLACE = Regex("^-[a-zA-Z]*i[a-zA-Z]*(\\..*)?$|^-[a-zA-Z]*i\\..*")

    private val FIND_WRITE_FLAGS = setOf(
        "-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprintf", "-fls",
    )

    private val GIT_READ_NOARGS = setOf(
        "status", "log", "diff", "show", "blame", "annotate", "ls-files", "ls-tree",
        "rev-parse", "describe", "shortlog", "reflog", "grep", "count-objects",
        "verify-commit", "verify-tag", "whatchanged", "name-rev", "name-only",
        "rev-list", "show-ref", "for-each-ref", "cat-file", "merge-base", "cherry",
        "range-diff", "interpret-trailers", "check-ignore", "check-attr",
        "check-ref-format", "var", "version", "help", "branch--list",
    )

    private val GIT_BRANCH_READ_FLAGS = setOf(
        "-a", "--all", "-r", "--remotes", "-v", "-vv", "--verbose", "--list", "-l",
        "--show-current", "--sort", "--contains", "--merged", "--no-merged",
        "--points-at", "--format", "--column", "--no-column", "--color",
    )

    private val GIT_TAG_READ_FLAGS = setOf(
        "-l", "--list", "-n", "--contains", "--points-at", "--sort", "--format",
    )

    private val PM_READ = setOf("list", "path", "dump", "dumpsys")

    private val NPM_READ = setOf("list", "ls", "outdated", "view", "info", "search", "why", "explain", "audit", "doctor", "ping", "root", "prefix", "bin")
    private val PIP_READ = setOf("list", "show", "freeze", "check", "inspect", "--version", "-V", "--help", "-h")
    private val CARGO_READ = setOf("tree", "metadata", "search", "verify-project", "pkgid", "--version", "-V", "--list", "--help", "-h", "version", "locate-project", "read-manifest")
    private val GO_READ = setOf("version", "env", "list", "doc", "--version", "--help", "-h", "help")
    private val DOCKER_READ = setOf("ps", "images", "version", "inspect", "logs", "stats", "info", "top", "history", "port", "diff")
    private val KUBE_READ = setOf("get", "describe", "logs", "api-resources", "api-versions", "version", "config", "explain", "top", "cluster-info")

    private val GH_READ_TOP = setOf("status", "search", "browse")
    private val GH_READ_TWO = setOf(
        "pr list", "pr view", "pr status", "pr checks", "pr diff",
        "issue list", "issue view", "issue status",
        "repo view", "repo list", "release list", "release view",
        "run list", "run view", "workflow list", "workflow view",
        "gist list", "gist view", "org list", "label list",
        "auth status", "extension list",
    )
    private val GH_API_WRITE_FLAGS = setOf("-X", "--method", "-f", "--field", "-F", "--raw-field", "--input", "-H", "--header")

    private val VERSION_ONLY = setOf(
        "node", "python", "python3", "python2", "ruby", "java", "javac", "kotlin",
        "perl", "php", "lua", "tsc", "deno", "bun", "gradle", "mvn", "adb",
        "fastboot", "cmake", "make", "gcc", "g++", "clang", "clang++", "ld",
        "tar", "zip", "unzip", "gzip", "bzip2", "xz", "7z", "ffmpeg",
    )

    private val SAFE_META_FLAGS = setOf(
        "--version", "-v", "-V", "-version", "version", "--help", "-h", "help", "--usage",
    )
}
