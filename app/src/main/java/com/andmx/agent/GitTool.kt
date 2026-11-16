package com.andmx.agent

import android.content.Context
import com.andmx.workspace.WorkspaceAccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class GitTool(
    context: Context,
    private val cwdProvider: () -> String = { WorkspaceAccess(context).guestCwd() },
) : Tool {
    private val access = WorkspaceAccess(context)

    override val name = "git"
    override val description =
        "在当前工作区执行 git 子命令(如 status / diff / add / commit / log)。默认在项目目录执行。"
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("args") { put("type", "string"); put("description", "git 之后的参数,如 'status -s' 或 'commit -m \"msg\"'") }
            putJsonObject("cwd") { put("type", "string"); put("description", "工作目录(仓库路径),默认为当前项目目录") }
        }
        putJsonArray("required") { add("args") }
    }

    override val timeoutMs: Long = 120_000

    override val readOnly: Boolean get() = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val gitArgs = args["args"]?.jsonPrimitive?.content ?: return ToolResult("缺少参数 args", isError = true)
        val cwd = args["cwd"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: cwdProvider()
        GitSafety.check(gitArgs)?.let { return ToolResult(it, isError = true) }
        val script =
            "command -v git >/dev/null 2>&1 || " +
                "(command -v apk >/dev/null 2>&1 && apk add --no-cache git >/dev/null 2>&1) || true; " +
                "git -c core.pager=cat $gitArgs"
        val res = kotlinx.coroutines.withTimeout(timeoutMs) { access.executeShell(script, cwd = cwd) }
        if (res.error != null) return ToolResult("执行失败: ${res.error}", isError = true)
        val out = buildString {
            append(res.stdout.ifBlank { "(无输出)" })
            append("\n[exit=${res.exitCode}]")
        }
        return ToolResult(out.take(16_000), isError = res.exitCode != 0)
    }
}

object GitSafety {
    private val shellMeta = Regex("[;|&$`<>\\n]")
    private val destructiveSubcommands = setOf(
        "push", "reset", "clean", "checkout", "restore", "rebase", "merge", "cherry-pick",
        "revert", "am", "apply", "stash", "worktree", "gc", "prune", "filter-branch",
        "update-ref", "symbolic-ref", "replace", "fast-import",
    )
    private val dangerousConfigKeys = setOf(
        "core.sshcommand", "core.gitproxy", "core.editor", "core.pager", "core.hookspath",
        "core.askpass", "credential.helper", "diff.external", "merge.tool", "mergetool.cmd",
        "sequence.editor", "gpg.program", "alias",
    )

    fun check(gitArgs: String): String? {
        if (shellMeta.containsMatchIn(gitArgs.replace(Regex("\"[^\"]*\""), "").replace(Regex("'[^']*'"), ""))) {
            return "git 参数包含 shell 元字符，已拒绝。请只传 git 子命令与参数，不要拼接 shell 命令。"
        }
        val tokens = tokenize(gitArgs)
        if (tokens.isEmpty()) return "缺少 git 子命令"
        var i = 0
        while (i < tokens.size && tokens[i].startsWith("-")) {
            val flag = tokens[i]
            if (flag == "-c" || flag == "--config-env") {
                val kv = tokens.getOrNull(i + 1).orEmpty()
                val key = kv.substringBefore('=').lowercase()
                if (key == "pager" || key.startsWith("pager.") ||
                    dangerousConfigKeys.any { key == it || key.startsWith("$it.") }
                ) {
                    return "拒绝通过 -c 注入可执行配置: $key"
                }
                i += 2
                continue
            }
            if (flag in setOf("-C", "--git-dir", "--work-tree", "--namespace", "--exec-path") ||
                flag.startsWith("--git-dir=") || flag.startsWith("--work-tree=") ||
                flag.startsWith("--namespace=") || flag.startsWith("--exec-path=")
            ) {
                return "拒绝改写仓库定位参数: $flag（请用 cwd 参数指定目录）"
            }
            i++
        }
        val sub = tokens.getOrNull(i)?.lowercase() ?: return "缺少 git 子命令"
        val rest = tokens.drop(i + 1)
        if (sub == "config") {
            val writes = rest.any { it in setOf("--unset", "--unset-all", "--rename-section", "--remove-section", "--edit", "-e") } ||
                rest.count { !it.startsWith("-") } >= 2
            val readsGlobal = rest.any { it in setOf("--global", "--system", "--local", "--file", "-f") }
            if (writes || readsGlobal) return "git config 写操作或全局配置访问已拒绝，请改用只读查询。"
        }
        if (sub in destructiveSubcommands) {
            val force = rest.any { it == "--force" || it == "-f" || it.startsWith("--force-") }
            val hard = sub == "reset" && rest.contains("--hard")
            val cleanFd = sub == "clean" && rest.any { it.contains("f") && it.startsWith("-") && !it.startsWith("--") }
            if (force || hard || cleanFd) {
                return "危险 git 操作（$sub 强制/破坏性旗标）已拒绝。请经 Bash 工具走审批执行。"
            }
        }
        if (sub == "branch" && rest.any { it == "-D" || it == "--delete" && rest.contains("--force") }) {
            return "强制删除分支已拒绝。请经 Bash 工具走审批执行。"
        }
        if (sub == "tag" && rest.contains("-d")) {
            return "删除标签已拒绝。请经 Bash 工具走审批执行。"
        }
        if (rest.any { it.startsWith("--upload-pack") || it.startsWith("--receive-pack") || it.startsWith("--ext-cmd") }) {
            return "拒绝可执行注入旗标。"
        }
        if (rest.any { it.contains("%G") && it.startsWith("--") }) {
            return "拒绝签名验证格式旗标（可能触发外部程序）。"
        }
        return null
    }

    fun isReadOnlyArgs(gitArgs: String): Boolean =
        BashReadonlyPolicy.isReadOnly("git $gitArgs")

    private fun tokenize(input: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        for (ch in input) {
            when {
                quote != null -> if (ch == quote) quote = null else cur.append(ch)
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> {
                    if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
                }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }
}
