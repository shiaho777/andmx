package com.andmx.agent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 项目级持久权限 ruleset（ZCode PermissionRuleset 对齐）：
 * 每个项目分 allow / ask / deny 三桶，规则 = {toolName, ruleContent}。
 *
 * ruleContent 匹配语义对齐上游 matchesRuleContent：
 * - `xxx:*` 前缀：subject == xxx 或以 "xxx "/"xxx\t" 开头（命令首 token）
 * - 含 `*`：通配符匹配
 * - 否则：精确相等
 *
 * 存储格式 `{"<projectKey>": {"allow": [...], "ask": [...], "deny": [...]}}`；
 * 旧格式 `{"<projectKey>": [{"tool","key"}]}` 迁移为 allow 桶。
 *
 * 判定顺序由调用方保证（deny → ask → 会话规则 → allow），与上游
 * checkPermission 的 deny→ask→plan→allow 位次一致。
 */
class ApprovalRuleStore(context: Context) {

    enum class RuleBehavior { ALLOW, ASK, DENY }

    data class Rule(
        val toolCanonical: String,
        val key: String,
        val behavior: RuleBehavior = RuleBehavior.ALLOW,
    ) {
        val display: String
            get() = when {
                key.startsWith("$toolCanonical:prefix:") -> key.removePrefix("$toolCanonical:prefix:")
                key.startsWith("$toolCanonical:file:") -> key.removePrefix("$toolCanonical:file:")
                else -> toolCanonical
            }
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _rules = MutableStateFlow(loadAll())
    val rules: StateFlow<Map<String, Set<Rule>>> = _rules.asStateFlow()

    /** 当前项目的规则集（key = projectKey）。 */
    fun rulesFor(projectKey: String): Set<Rule> = _rules.value[projectKey].orEmpty()

    fun rulesFor(projectKey: String, behavior: RuleBehavior): Set<Rule> =
        rulesFor(projectKey).filterTo(mutableSetOf()) { it.behavior == behavior }

    /** 旧接口兼容：allow 桶精确 key 命中。 */
    fun allows(projectKey: String, toolCanonical: String, ruleKey: String): Boolean =
        rulesFor(projectKey, RuleBehavior.ALLOW).any { it.toolCanonical == toolCanonical && it.key == ruleKey }

    /**
     * ruleContent 级匹配：rule.key 形如 `tool:prefix:<token>` / `tool:file:<path>` /
     * `tool:any`；subject 为命令原文 / 文件路径等输入主体。
     * 上游 Write 继承 Edit 规则（matchesRuleToolName）。
     */
    fun matches(
        projectKey: String,
        toolCanonical: String,
        subject: String,
        behavior: RuleBehavior,
    ): Boolean = findMatch(projectKey, toolCanonical, subject, behavior) != null

    /** 返回命中的规则（ruleId 透出用），未命中为 null。 */
    fun findMatch(
        projectKey: String,
        toolCanonical: String,
        subject: String,
        behavior: RuleBehavior,
    ): Rule? = rulesFor(projectKey, behavior).firstOrNull { rule ->
        ruleAppliesTo(rule, toolCanonical, subject)
    }

    fun add(projectKey: String, toolCanonical: String, ruleKey: String, behavior: RuleBehavior = RuleBehavior.ALLOW) {
        if (ruleKey.isBlank() || projectKey.isBlank()) return
        val all = _rules.value.toMutableMap()
        val set = all[projectKey].orEmpty().toMutableSet()
        set += Rule(toolCanonical, ruleKey, behavior)
        all[projectKey] = set
        persist(all)
    }

    fun remove(projectKey: String, rule: Rule) {
        val all = _rules.value.toMutableMap()
        val set = all[projectKey].orEmpty().toMutableSet()
        set -= rule
        if (set.isEmpty()) all.remove(projectKey) else all[projectKey] = set
        persist(all)
    }

    fun clear(projectKey: String) {
        val all = _rules.value.toMutableMap()
        all.remove(projectKey)
        persist(all)
    }

    private fun persist(all: Map<String, Set<Rule>>) {
        _rules.value = all
        prefs.edit().putString(KEY_RULES, serialize(all)).apply()
    }

    private fun loadAll(): Map<String, Set<Rule>> = runCatching {
        val raw = prefs.getString(KEY_RULES, null) ?: return emptyMap()
        deserialize(raw)
    }.getOrDefault(emptyMap())

    private fun serialize(all: Map<String, Set<Rule>>): String = buildJsonObject {
        all.forEach { (project, rules) ->
            putJsonObject(project) {
                RuleBehavior.entries.forEach { behavior ->
                    val bucket = rules.filter { it.behavior == behavior }
                    if (bucket.isNotEmpty()) {
                        putJsonArray(behavior.name.lowercase()) {
                            bucket.forEach { rule ->
                                add(buildJsonObject {
                                    put("tool", rule.toolCanonical)
                                    put("key", rule.key)
                                })
                            }
                        }
                    }
                }
            }
        }
    }.toString()

    private fun deserialize(raw: String): Map<String, Set<Rule>> {
        val obj = json.parseToJsonElement(raw).jsonObject
        return obj.entries.associate { (project, value) ->
            val rules = when (value) {
                is JsonArray -> value.mapNotNull { el -> parseRule(el, RuleBehavior.ALLOW) }
                is JsonObject -> RuleBehavior.entries.flatMap { behavior ->
                    (value[behavior.name.lowercase()] as? JsonArray).orEmpty()
                        .mapNotNull { el -> parseRule(el, behavior) }
                }
                else -> emptyList()
            }
            project to rules.toSet()
        }
    }

    private fun parseRule(el: kotlinx.serialization.json.JsonElement, behavior: RuleBehavior): Rule? {
        val o = el as? JsonObject ?: return null
        val tool = o["tool"]?.jsonPrimitive?.content ?: return null
        val key = o["key"]?.jsonPrimitive?.content ?: return null
        return Rule(tool, key, behavior)
    }

    companion object {
        private const val PREFS = "andmx_approval_rules"
        private const val KEY_RULES = "rules_json"

        /** 项目键：本地取 hostPath，远程取 workspaceUri，与 ProjectManager 一致。 */
        fun projectKeyOf(hostPath: String?): String = hostPath?.trim().orEmpty()

        /** 上游 Write 继承 Edit 规则（matchesRuleToolName）。 */
        fun ruleAppliesTo(rule: Rule, toolCanonical: String, subject: String): Boolean {
            if (rule.toolCanonical != toolCanonical &&
                !(toolCanonical == "write" && rule.toolCanonical == "edit")
            ) return false
            val content = rule.key.removePrefix("${rule.toolCanonical}:")
            return when {
                content == "any" -> true
                content.startsWith("prefix:") ->
                    matchContent(subject, content.removePrefix("prefix:") + ":*")
                content.startsWith("file:") ->
                    matchContent(subject, content.removePrefix("file:"))
                else -> matchContent(subject, content)
            }
        }

        /** 上游 matchesRuleContent 语义：`x:*` 前缀 / `*` 通配 / 精确相等。 */
        fun matchContent(subject: String, ruleContent: String): Boolean {
            if (ruleContent.endsWith(":*")) {
                val prefix = ruleContent.dropLast(2)
                return subject == prefix ||
                    subject.startsWith("$prefix ") || subject.startsWith("$prefix\t")
            }
            if (ruleContent.contains("*")) {
                return wildcardToRegex(ruleContent).matches(subject)
            }
            return subject == ruleContent
        }

        private fun wildcardToRegex(pattern: String): Regex =
            Regex(pattern.split("*").joinToString(".*") { Regex.escape(it) })

        /** 从审批规则 key 反推工具 + 匹配主体种类（UI 新增规则时复用）。 */
        fun ruleKeyFor(toolCanonical: String, content: String): String {
            val c = content.trim()
            return when {
                c.isBlank() -> "$toolCanonical:any"
                toolCanonical == "shell" || toolCanonical == "git" -> "$toolCanonical:prefix:$c"
                else -> "$toolCanonical:file:$c"
            }
        }
    }
}
