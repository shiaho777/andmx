package com.andmx.exec.policy

/**
 * Command-level policy engine — mirrors Codex's execpolicy.
 *
 * Before a shell command is executed, [check] classifies it against rules
 * to decide whether it should be auto-allowed, prompted, or denied.
 * After user approval, [proposeAmendment] generates a rule so similar
 * commands won't prompt again.
 */
class ExecPolicy(
    private val rules: MutableList<ExecRule> = DEFAULT_RULES.toMutableList(),
) {
    data class ExecRule(
        val pattern: String,          // regex matched against the full command
        val action: RuleAction,
        val reason: String? = null,
        val scope: RuleScope = RuleScope.SESSION,  // how long the rule persists
    )

    enum class RuleAction { ALLOW, DENY, PROMPT }
    enum class RuleScope { SESSION, FOREVER }

    data class Decision(
        val action: RuleAction,
        val matchedRule: ExecRule? = null,
        val reason: String? = null,
    ) {
        val isAllowed get() = action == RuleAction.ALLOW
        val needsPrompt get() = action == RuleAction.PROMPT
        val isDenied get() = action == RuleAction.DENY
    }

    fun check(command: String): Decision {
        for (rule in rules) {
            if (Regex(rule.pattern, RegexOption.IGNORE_CASE).containsMatchIn(command)) {
                return Decision(rule.action, rule, rule.reason)
            }
        }
        // Default: allow (the approval policy handles the broader mode)
        return Decision(RuleAction.ALLOW)
    }

    /**
     * After a user approves a command that was PROMPT, call this to generate
     * a rule that auto-allows similar commands in the future.
     */
    fun proposeAmendment(command: String): ExecRule? {
        val basename = command.trim().split(Regex("\\s+")).firstOrNull()?.substringBefore('/')
            ?: return null

        // Don't add rules for already-matched patterns
        val newPattern = "^${Regex.escape(basename)}\\b"
        if (rules.any { it.pattern == newPattern }) return null

        val rule = ExecRule(newPattern, RuleAction.ALLOW, "用户批准的命令: $basename", RuleScope.SESSION)
        rules.add(rule)
        return rule
    }

    /** Get a snapshot of current rules (for UI display). */
    fun rules(): List<ExecRule> = rules.toList()

    companion object {
        val DEFAULT_RULES = listOf(
            // ── Read-only commands (always safe) ──
            ExecRule("\\b(ls|cat|head|tail|wc|sort|uniq|file|stat|du|df|env|printenv|whoami|id|pwd|date|echo)\\b", RuleAction.ALLOW, "只读命令"),
            ExecRule("\\b(rg|grep|find|locate|which|whereis|type)\\b", RuleAction.ALLOW, "搜索命令"),
            ExecRule("\\b(git)\\s+(status|log|diff|show|branch|blame|remote|reflog|describe)\\b", RuleAction.ALLOW, "git 只读"),
            ExecRule("\\b(npm|pnpm|yarn)\\s+(list|ls|view|info|outdated)\\b", RuleAction.ALLOW, "包管理只读"),
            ExecRule("\\b(pip|pip3)\\s+(list|show)\\b", RuleAction.ALLOW, "pip 只读"),
            ExecRule("\\b(apk)\\s+(info|list|search)\\b", RuleAction.ALLOW, "apk 只读"),
            ExecRule("\\b(python3?|node|ruby|java)\\s+(-c|-e)\\b", RuleAction.ALLOW, "脚本求值"),
            ExecRule("\\b(uname|hostname|ifconfig|ip)\\b", RuleAction.ALLOW, "系统信息"),

            // ── Dangerous commands (always prompt) ──
            ExecRule("\\brm\\s+(-[a-z]*r[a-z]*f?|-[a-z]*f[a-z]*r?)\\b.*(/|\\*|~)", RuleAction.DENY, "递归删除危险路径"),
            ExecRule("\\b(mkfs|dd\\s+if=|shred|wipe)\\b", RuleAction.DENY, "磁盘破坏命令"),
            ExecRule(":\\(\\)\\s*\\{.*\\}\\s*;:", RuleAction.DENY, "fork bomb"),
            ExecRule("\\b(chmod|chown)\\s+(-R\\s+)?0?777\\b", RuleAction.DENY, "全权限修改"),
            ExecRule("\\b(kill|killall|pkill)\\s+-9\\b", RuleAction.PROMPT, "强制终止进程"),
            ExecRule("\\b(shutdown|reboot|halt|poweroff)\\b", RuleAction.DENY, "系统电源命令"),

            // ── Network commands (prompt) ──
            ExecRule("\\b(curl|wget|nc|netcat|telnet|ftp|scp|rsync)\\b", RuleAction.PROMPT, "网络命令"),
            ExecRule("\\b(ssh|mosh)\\b", RuleAction.PROMPT, "SSH 远程连接"),
            ExecRule("\\b(git)\\s+(push|pull|fetch|clone)\\b", RuleAction.PROMPT, "git 网络操作"),

            // ── Package installation (prompt) ──
            ExecRule("\\b(apk|apt|apt-get)\\s+(add|install|del|remove|upgrade)\\b", RuleAction.PROMPT, "包安装/卸载"),
            ExecRule("\\b(pip|pip3)\\s+install\\b", RuleAction.PROMPT, "pip 安装"),
            ExecRule("\\b(npm|pnpm|yarn)\\s+(install|add|remove|uninstall|i)\\b", RuleAction.PROMPT, "npm 安装"),
            ExecRule("\\b(gem)\\s+install\\b", RuleAction.PROMPT, "gem 安装"),

            // ── File modification (prompt in ask mode) ──
            ExecRule("\\b(rm|rmdir|mv)\\b", RuleAction.PROMPT, "文件删除/移动"),
            ExecRule("\\b(chmod|chown|chgrp)\\b", RuleAction.PROMPT, "权限修改"),
            ExecRule("\\b(git)\\s+(add|commit|merge|rebase|reset|checkout|stash)\\b", RuleAction.PROMPT, "git 写操作"),
        )
    }
}
