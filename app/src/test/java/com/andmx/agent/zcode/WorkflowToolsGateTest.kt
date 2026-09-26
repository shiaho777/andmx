package com.andmx.agent.zcode

import com.andmx.agent.LoadedSkills
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上游 3.14.3 workflow-skill-gate 对齐测试：创作类调用要求会话先加载 dynamic-workflows 技能。
 */
class WorkflowToolsGateTest {

    private fun tools(loaded: Boolean) = WorkflowTools(
        service = null,
        conversationId = { 1L },
        cwd = { "/" },
        hasLoadedSkill = { loaded },
    )

    private val specArgs: JsonObject = buildJsonObject {
        put("task", "do the thing")
        put("spec", buildJsonObject {})
    }

    @Test
    fun evalRefusedWithoutSkill() = runTest {
        val eval = tools(loaded = false).all().first { it.name == "EvalWorkflowSnippet" }
        val r = eval.execute(buildJsonObject { put("spec", buildJsonObject {}) })
        assertTrue(r.isError)
        assertTrue(r.output.startsWith("workflow_skill_not_loaded"))
        assertTrue(r.output.contains(LoadedSkills.DYNAMIC_WORKFLOWS))
    }

    @Test
    fun evalProceedsWithSkillLoaded() = runTest {
        val eval = tools(loaded = true).all().first { it.name == "EvalWorkflowSnippet" }
        val r = eval.execute(buildJsonObject { put("spec", buildJsonObject {}) })
        // 通过门后落到 spec 校验，而不是技能门错误。
        assertTrue(r.isError)
        assertFalse(r.output.startsWith("workflow_skill_not_loaded"))
    }

    @Test
    fun createWithSpecRefusedWithoutSkill() = runTest {
        val create = tools(loaded = false).all().first { it.name == "CreateWorkflow" }
        val r = create.execute(specArgs)
        assertTrue(r.isError)
        assertTrue(r.output.startsWith("workflow_skill_not_loaded"))
    }

    @Test
    fun createBySavedNameIsExempt() = runTest {
        // 上游 createWorkflowNeedsSkill 例外：跑 saved 定义不写 spec，门不适用。
        // service=null 时越过门后报 workflow_unavailable——证明门没有拦它。
        val create = tools(loaded = false).all().first { it.name == "CreateWorkflow" }
        val r = create.execute(buildJsonObject {
            put("task", "do the thing")
            put("name", "expert")
        })
        assertTrue(r.isError)
        assertFalse(r.output.startsWith("workflow_skill_not_loaded"))
    }

    @Test
    fun amendWithoutSpecIsExempt() = runTest {
        // 上游 amendWorkflowNeedsSkill 例外：不带 spec 的元数据修补不是写 spec。
        val amend = tools(loaded = false).all().first { it.name == "AmendWorkflow" }
        val r = amend.execute(buildJsonObject {
            put("name", "expert")
            put("title", "new title")
        })
        assertTrue(r.isError)
        assertFalse(r.output.startsWith("workflow_skill_not_loaded"))
    }
}
