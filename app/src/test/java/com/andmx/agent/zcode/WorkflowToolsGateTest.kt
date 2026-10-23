package com.andmx.agent.zcode

import com.andmx.agent.LoadedSkills
import kotlinx.coroutines.test.runTest
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
        hasLoadedSkill = { loaded },
    )

    @Test
    fun evalRefusedWithoutSkill() = runTest {
        val eval = tools(loaded = false).all().first { it.name == "EvalWorkflowSnippet" }
        val r = eval.execute(buildJsonObject { put("spec", buildJsonObject {}) })
        assertTrue(r.isError)
        assertTrue(r.output.contains("before it accepts a script"))
        assertTrue(r.output.contains(LoadedSkills.DYNAMIC_WORKFLOWS))
        assertTrue(r.output.contains("Nothing was started."))
    }

    @Test
    fun evalProceedsWithSkillLoaded() = runTest {
        val eval = tools(loaded = true).all().first { it.name == "EvalWorkflowSnippet" }
        val r = eval.execute(buildJsonObject { put("spec", buildJsonObject {}) })
        // 通过门后落到 spec 校验，而不是技能门错误。
        assertTrue(r.isError)
        assertFalse(r.output.contains("before it accepts a script"))
    }

    @Test
    fun createWithSpecRefusedWithoutSkill() = runTest {
        val create = tools(loaded = false).all().first { it.name == "CreateWorkflow" }
        val r = create.execute(buildJsonObject {
            put("script", "export default {}")
        })
        assertTrue(r.isError)
        assertTrue(r.output.contains("before it accepts a script"))
        assertTrue(r.output.contains("Nothing was started."))
    }

    @Test
    fun createBySavedNameIsExempt() = runTest {
        val create = tools(loaded = false).all().first { it.name == "CreateWorkflow" }
        val r = create.execute(buildJsonObject {
            put("saved", buildJsonObject { put("name", "expert") })
        })
        assertTrue(r.isError)
        assertFalse(r.output.contains("before it accepts a script"))
        assertTrue(r.output.contains("NOT executed"))
        assertFalse(r.output.contains("Workflow run started"))
    }

    @Test
    fun amendWithoutSpecIsExempt() = runTest {
        val amend = tools(loaded = false).all().first { it.name == "AmendWorkflow" }
        val r = amend.execute(buildJsonObject {
            put("run_id", "run_1")
        })
        assertTrue(r.isError)
        assertFalse(r.output.contains("before it accepts a script"))
        assertTrue(r.output.contains("workflow_amend_unavailable"))
    }
}
