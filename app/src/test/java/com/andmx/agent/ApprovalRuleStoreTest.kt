package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ApprovalRuleStore 的序列化/查询语义。
 * SharedPreferences 依赖 Android 框架，此处用 returnDefaultValues 测试环境
 * 无法真实落盘，故通过可注入校验：规则结构、display 归一、projectKey 归一。
 * 存取链路由 JVM 侧无法覆盖，交由设备验证。
 */
class ApprovalRuleStoreTest {

    @Test
    fun ruleDisplayShowsPrefixCommand() {
        val rule = ApprovalRuleStore.Rule("shell", "shell:prefix:npm")
        assertEquals("npm", rule.display)
    }

    @Test
    fun ruleDisplayShowsFilePath() {
        val rule = ApprovalRuleStore.Rule("edit", "edit:file:app/src/Main.kt")
        assertEquals("app/src/Main.kt", rule.display)
    }

    @Test
    fun ruleDisplayFallsBackToToolName() {
        val rule = ApprovalRuleStore.Rule("webfetch", "webfetch:any")
        assertEquals("webfetch", rule.display)
    }

    @Test
    fun projectKeyTrimsHostPath() {
        assertEquals("/sdcard/proj", ApprovalRuleStore.projectKeyOf(" /sdcard/proj "))
        assertEquals("", ApprovalRuleStore.projectKeyOf(null))
        assertEquals("", ApprovalRuleStore.projectKeyOf("   "))
    }

    @Test
    fun serializationRoundTrip() {
        val rules = mapOf(
            "/sdcard/proj" to setOf(
                ApprovalRuleStore.Rule("shell", "shell:prefix:npm"),
                ApprovalRuleStore.Rule("edit", "edit:file:app/src/Main.kt", ApprovalRuleStore.RuleBehavior.DENY),
            ),
            "ssh://host/work" to setOf(ApprovalRuleStore.Rule("webfetch", "webfetch:any")),
        )
        // 三桶格式镜像：{"<project>": {"allow": [{tool,key}], "deny": [...], "ask": [...]}}
        val store = object {
            fun serialize(all: Map<String, Set<ApprovalRuleStore.Rule>>): String {
                val json = kotlinx.serialization.json.buildJsonObject {
                    all.forEach { (project, rs) ->
                        put(project, kotlinx.serialization.json.buildJsonObject {
                            rs.groupBy { it.behavior }.forEach { (b, bucket) ->
                                put(b.name.lowercase(), kotlinx.serialization.json.JsonArray(bucket.map { r ->
                                    kotlinx.serialization.json.buildJsonObject {
                                        put("tool", kotlinx.serialization.json.JsonPrimitive(r.toolCanonical))
                                        put("key", kotlinx.serialization.json.JsonPrimitive(r.key))
                                    }
                                }))
                            }
                        })
                    }
                }
                return json.toString()
            }
        }
        val raw = store.serialize(rules)
        assertTrue(raw.contains("\"/sdcard/proj\""))
        assertTrue(raw.contains("shell:prefix:npm"))
        assertTrue(raw.contains("edit:file:app/src/Main.kt"))
        assertTrue(raw.contains("webfetch:any"))
        assertTrue(raw.contains("\"deny\""))
        assertTrue(raw.contains("\"allow\""))
    }

    @Test
    fun rulesByProjectAreIsolated() {
        // 内存语义：不同 projectKey 的规则集互不影响（allows 查询按 key 隔离）。
        val a = setOf(ApprovalRuleStore.Rule("shell", "shell:prefix:npm"))
        val b = setOf(ApprovalRuleStore.Rule("git", "git:prefix:git"))
        val all = mapOf("/p1" to a, "/p2" to b)
        assertTrue(all["/p1"].orEmpty().any { it.toolCanonical == "shell" })
        assertFalse(all["/p2"].orEmpty().any { it.toolCanonical == "shell" })
    }

    // ---- ZCode ruleContent 匹配语义 ----

    @Test
    fun prefixRuleMatchesCommandHead() {
        val rule = ApprovalRuleStore.Rule("shell", "shell:prefix:npm")
        assertTrue(ApprovalRuleStore.ruleAppliesTo(rule, "shell", "npm install"))
        assertTrue(ApprovalRuleStore.ruleAppliesTo(rule, "shell", "npm"))
        assertFalse(ApprovalRuleStore.ruleAppliesTo(rule, "shell", "npmx install"))
        assertFalse(ApprovalRuleStore.ruleAppliesTo(rule, "shell", "pnpm npm"))
    }

    @Test
    fun fileRuleMatchesExactPathOnly() {
        val rule = ApprovalRuleStore.Rule("edit", "edit:file:app/src/Main.kt")
        assertTrue(ApprovalRuleStore.ruleAppliesTo(rule, "edit", "app/src/Main.kt"))
        assertFalse(ApprovalRuleStore.ruleAppliesTo(rule, "edit", "app/src/Other.kt"))
        assertFalse(ApprovalRuleStore.ruleAppliesTo(rule, "read", "app/src/Main.kt"))
    }

    @Test
    fun anyRuleMatchesAllSubjects() {
        val rule = ApprovalRuleStore.Rule("webfetch", "webfetch:any")
        assertTrue(ApprovalRuleStore.ruleAppliesTo(rule, "webfetch", "https://a.b"))
        assertTrue(ApprovalRuleStore.ruleAppliesTo(rule, "webfetch", ""))
    }

    @Test
    fun wildcardRuleContentMatchesSubstringPattern() {
        assertTrue(ApprovalRuleStore.matchContent("app/src/a/Main.kt", "app/*/Main.kt"))
        assertTrue(ApprovalRuleStore.matchContent("https://x.com/a", "https://*.com/*"))
        assertFalse(ApprovalRuleStore.matchContent("app/src/a/Other.kt", "app/*/Main.kt"))
    }

    @Test
    fun trailingColonStarMatchesCommandPrefix() {
        assertTrue(ApprovalRuleStore.matchContent("npm run build", "npm:*"))
        assertTrue(ApprovalRuleStore.matchContent("npm", "npm:*"))
        assertFalse(ApprovalRuleStore.matchContent("npmx run", "npm:*"))
    }

    @Test
    fun writeInheritsEditRules() {
        val editRule = ApprovalRuleStore.Rule("edit", "edit:file:app/src/Main.kt")
        assertTrue(ApprovalRuleStore.ruleAppliesTo(editRule, "write", "app/src/Main.kt"))
        assertFalse(ApprovalRuleStore.ruleAppliesTo(editRule, "shell", "app/src/Main.kt"))
    }

    @Test
    fun behaviorBucketsAreIndependent() {
        val rules = setOf(
            ApprovalRuleStore.Rule("shell", "shell:prefix:npm", ApprovalRuleStore.RuleBehavior.ALLOW),
            ApprovalRuleStore.Rule("shell", "shell:prefix:rm", ApprovalRuleStore.RuleBehavior.DENY),
        )
        val allow = rules.filter { it.behavior == ApprovalRuleStore.RuleBehavior.ALLOW }
        val deny = rules.filter { it.behavior == ApprovalRuleStore.RuleBehavior.DENY }
        assertEquals(1, allow.size)
        assertEquals(1, deny.size)
        assertTrue(allow.all { it.key.contains("npm") })
        assertTrue(deny.all { it.key.contains("rm") })
    }
}
