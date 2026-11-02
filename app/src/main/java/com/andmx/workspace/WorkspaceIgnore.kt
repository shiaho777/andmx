package com.andmx.workspace

import java.io.File

class WorkspaceIgnore private constructor(
    private val rules: List<Rule>,
) {
    fun ignores(relPath: String, isDirectory: Boolean): Boolean {
        val path = relPath.trim('/').replace('\\', '/')
        if (path.isEmpty() || rules.isEmpty()) return false
        var ignored = false
        for (rule in rules) {
            if (matches(rule, path, isDirectory)) ignored = !rule.negated
        }
        return ignored
    }

    private fun matches(rule: Rule, path: String, isDirectory: Boolean): Boolean {
        if (rule.dirOnly) {
            if (isDirectory && globMatch(rule.body, path, rule.anywhere)) return true
            var acc = ""
            for (seg in path.split('/').dropLast(1)) {
                acc = if (acc.isEmpty()) seg else "$acc/$seg"
                if (globMatch(rule.body, acc, rule.anywhere)) return true
            }
            return false
        }
        return globMatch(rule.body, path, rule.anywhere)
    }

    private data class Rule(
        val negated: Boolean,
        val dirOnly: Boolean,
        val anywhere: Boolean,
        val body: String,
    )

    companion object {
        fun load(root: File): WorkspaceIgnore {
            val file = File(root, ".zcodeignore")
            if (!file.isFile) return WorkspaceIgnore(emptyList())
            return fromLines(runCatching { file.readLines() }.getOrDefault(emptyList()))
        }

        fun fromLines(lines: List<String>): WorkspaceIgnore {
            return WorkspaceIgnore(lines.mapNotNull { parse(it) })
        }

        fun rgIgnoreFileArg(hostRoot: String?): String {
            if (hostRoot.isNullOrBlank()) return ""
            val file = File(hostRoot, ".zcodeignore")
            if (!file.isFile) return ""
            return " --ignore-file '/root/project/.zcodeignore'"
        }

        private fun parse(raw: String): Rule? {
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return null
            val negated = line.startsWith("!")
            if (negated) line = line.removePrefix("!").trim()
            if (line.isEmpty()) return null
            val dirOnly = line.endsWith("/")
            if (dirOnly) line = line.removeSuffix("/")
            val anchored = line.startsWith("/")
            if (anchored) line = line.removePrefix("/")
            if (line.isEmpty()) return null
            val anywhere = !anchored && !line.contains('/')
            return Rule(negated, dirOnly, anywhere, line)
        }

        private fun globMatch(pattern: String, path: String, anywhere: Boolean): Boolean {
            val regex = globToRegex(pattern)
            if (regex.matches(path)) return true
            if (!anywhere) return false
            var rest = path
            while (true) {
                if (regex.matches(rest)) return true
                val slash = rest.indexOf('/')
                if (slash < 0) return false
                rest = rest.substring(slash + 1)
            }
        }

        private fun globToRegex(pattern: String): Regex {
            val out = StringBuilder()
            var i = 0
            while (i < pattern.length) {
                if (pattern.startsWith("**", i)) {
                    if (i + 2 < pattern.length && pattern[i + 2] == '/') {
                        out.append("(?:.*/)?")
                        i += 3
                    } else {
                        out.append(".*")
                        i += 2
                    }
                    continue
                }
                when (val c = pattern[i]) {
                    '*' -> out.append("[^/]*")
                    '?' -> out.append("[^/]")
                    '.', '(', ')', '+', '|', '^', '$', '{', '}', '[', ']', '\\' -> {
                        out.append('\\')
                        out.append(c)
                    }
                    else -> out.append(c)
                }
                i++
            }
            return Regex("^$out$")
        }
    }
}
