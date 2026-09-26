package com.andmx.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowPromptTest {

    @Test
    fun expandsTaskAndSkillReference() {
        val prompt = WorkflowPrompt.expand("review this PR")
        assertTrue(prompt.contains("review this PR"))
        assertTrue(prompt.contains(LoadedSkills.DYNAMIC_WORKFLOWS))
        assertTrue(prompt.contains("CreateWorkflow"))
        assertTrue(prompt.contains("Skill"))
    }

    @Test
    fun blankArgsStillProduceActionablePrompt() {
        val prompt = WorkflowPrompt.expand("")
        assertTrue(prompt.contains(LoadedSkills.DYNAMIC_WORKFLOWS))
        assertTrue(prompt.contains("CreateWorkflow"))
    }
}
