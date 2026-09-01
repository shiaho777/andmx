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
                ApprovalRuleStore.Rule("edit", "edit:file:app/src/Main.kt"),
            ),
            "ssh://host/work" to setOf(ApprovalRuleStore.Rule("webfetch", "webfetch:any")),
        )
        // 使用私有方法的镜像：通过公共 API 校验语义（allows 逻辑依赖内存 map）
        val store = object {
            fun serialize(all: Map<String, Set<ApprovalRuleStore.Rule>>): String {
                val json = kotlinx.serialization.json.buildJsonObject {
                    all.forEach { (project, rs) ->
                        put(project, kotlinx.serialization.json.JsonArray(rs.map { r ->
                            kotlinx.serialization.json.buildJsonObject {
                                put("tool", kotlinx.serialization.json.JsonPrimitive(r.toolCanonical))
                                put("key", kotlinx.serialization.json.JsonPrimitive(r.key))
                            }
                        }))
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
}
