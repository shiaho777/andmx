package com.andmx.agent

import com.andmx.agent.zcode.workflowSkillRefusal
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowPromptTest {

    @Test
    fun hiddenSwitchDoesNotNameAWorkflowTool() {
        val prompt = WorkflowPrompt.expand("review this PR")
        assertTrue(prompt.contains("review this PR"))
        assertTrue(prompt.contains("not registered"))
        assertTrue(prompt.contains("Nothing will be typechecked"))
        assertTrue(prompt.contains("nothing will be started"))
        assertFalse(prompt.contains("CreateWorkflow"))
        assertFalse(prompt.contains("GetWorkflowRunRoster"))
        assertFalse(prompt.contains("CancelWorkflowRun"))
        assertFalse(prompt.contains("EvalWorkflowSnippet"))
        assertFalse(prompt.contains("`spec`"))
        val blank = WorkflowPrompt.expand("")
        assertTrue(blank.contains("用户未附描述"))
        assertFalse(blank.contains("CreateWorkflow"))
        assertFalse(WorkflowPrompt.blankArgsNotice().contains("CreateWorkflow"))
        assertTrue(WorkflowPrompt.blankArgsNotice().contains("未注册"))
    }

    @Test
    fun enabledPromptUsesHandlerArgumentsAndDoesNotClaimARun() {
        val prompt = WorkflowPrompt.expand("review this PR", toolsEnabled = true)
        assertTrue(prompt.contains("review this PR"))
        assertTrue(prompt.contains(LoadedSkills.DYNAMIC_WORKFLOWS))
        assertTrue(prompt.contains("Skill"))
        assertTrue(prompt.contains("`script`"))
        assertTrue(prompt.contains("`saved`"))
        assertTrue(prompt.contains("`path`"))
        assertTrue(prompt.contains("run_id"))
        assertTrue(prompt.contains("script_path"))
        assertTrue(prompt.contains("does not typecheck"))
        assertTrue(prompt.contains("does not start a run"))
        assertTrue(prompt.contains("Do not pass `spec`"))
        assertFalse(prompt.contains("GetWorkflowRunRoster"))
        assertFalse(prompt.contains("CancelWorkflowRun"))
        assertFalse(prompt.contains("phase pipeline"))
        val notice = WorkflowPrompt.blankArgsNotice(toolsEnabled = true)
        assertTrue(notice.contains("script、saved 或 path"))
        assertFalse(notice.contains("用 CreateWorkflow 启动"))
        assertTrue(notice.contains("也不会启动运行"))
    }

    @Test
    fun skillTextMatchesTheHandlerContract() {
        val root = skillDir()
        val skill = File(root, "SKILL.md").readText()
        val examples = File(root, "examples.md").readText()
        val patterns = File(root, "patterns.md").readText()
        for (text in listOf(skill, examples, patterns)) {
            assertFalse(text.contains("GetWorkflowRunRoster"))
            assertFalse(text.contains("CancelWorkflowRun"))
            assertFalse(text.contains("`spec` + `task`"))
            assertFalse(text.contains("names its `runId`"))
        }
        assertTrue(skill.contains("exactly one source"))
        assertTrue(skill.contains("`script`"))
        assertTrue(skill.contains("`saved`"))
        assertTrue(skill.contains("`path`"))
        assertTrue(skill.contains("run_id"))
        assertTrue(skill.contains("script_path"))
        assertTrue(skill.contains("NOT executed"))
        assertTrue(skill.contains("does not typecheck a script and does not start a run"))
        assertTrue(skill.contains("Do not pass `spec`"))
        assertTrue(skill.contains("not in the tool list"))
        assertFalse(skill.contains("WorkflowDefinition spec"))
        assertFalse(skill.contains("scheduled_graph"))
        val refusal = workflowSkillRefusal("CreateWorkflow")
        assertFalse(refusal.contains("facade"))
        assertTrue(refusal.contains("dynamic-workflows"))
        assertTrue(refusal.contains("Nothing was started."))
        assertTrue(refusal.contains("nothing is typechecked or started"))
    }

    private fun skillDir(): File {
        val relative = "src/main/assets/andmx-plugins/andmx-dynamic-workflows/skills/dynamic-workflows"
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { File(it, "SKILL.md").isFile }
            ?: error("dynamic-workflows skill not found from ${File(".").absolutePath}")
    }
}
