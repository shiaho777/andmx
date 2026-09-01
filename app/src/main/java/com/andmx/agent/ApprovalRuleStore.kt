package com.andmx.agent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 「始终允许本项目」的持久审批规则（ZCode chat.permission.allowForProject 对齐）。
 *
 * 规则按项目键控（hostPath 或远程 workspace id），以 JSON 存进 SharedPreferences：
 * `{"<projectKey>": {"<toolCanonical>": ["<ruleKey>", ...]}}`。仅存 ALLOW 语义——
 * 持久拒绝容易把模型锁死在难排查的状态，保持会话级即可。
 *
 * ruleKey 与 ChatController.approvalRuleKey 同构：命令前缀 / 文件路径 / 工具名。
 */
class ApprovalRuleStore(context: Context) {

    data class Rule(val toolCanonical: String, val key: String) {
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

    fun allows(projectKey: String, toolCanonical: String, ruleKey: String): Boolean =
        _rules.value[projectKey].orEmpty().any { it.toolCanonical == toolCanonical && it.key == ruleKey }

    fun add(projectKey: String, toolCanonical: String, ruleKey: String) {
        if (ruleKey.isBlank() || projectKey.isBlank()) return
        val all = _rules.value.toMutableMap()
        val set = all[projectKey].orEmpty().toMutableSet()
        set += Rule(toolCanonical, ruleKey)
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
            putJsonArray(project) {
                rules.forEach { rule ->
                    add(buildJsonObject {
                        put("tool", rule.toolCanonical)
                        put("key", rule.key)
                    })
                }
            }
        }
    }.toString()

    private fun deserialize(raw: String): Map<String, Set<Rule>> {
        val obj = json.parseToJsonElement(raw).jsonObject
        return obj.entries.associate { (project, value) ->
            project to (value as? JsonArray).orEmpty()
                .mapNotNull { el ->
                    val o = el.jsonObject
                    val tool = o["tool"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val key = o["key"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    Rule(tool, key)
                }.toSet()
        }
    }

    companion object {
        private const val PREFS = "andmx_approval_rules"
        private const val KEY_RULES = "rules_json"

        /** 项目键：本地取 hostPath，远程取 workspaceUri，与 ProjectManager 一致。 */
        fun projectKeyOf(hostPath: String?): String = hostPath?.trim().orEmpty()
    }
}
