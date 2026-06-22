package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalPolicyTest {

    @Test
    fun fullAccessAutoRunsEverything() {
        for (r in ToolRisk.entries) {
            assertEquals(Decision.AUTO, ApprovalPolicy.decide(ApprovalMode.FULL, r))
        }
    }

    @Test
    fun readOnlyAllowsOnlyReads() {
        assertEquals(Decision.AUTO, ApprovalPolicy.decide(ApprovalMode.READ_ONLY, ToolRisk.READ))
        assertEquals(Decision.DENY, ApprovalPolicy.decide(ApprovalMode.READ_ONLY, ToolRisk.WRITE))
        assertEquals(Decision.DENY, ApprovalPolicy.decide(ApprovalMode.READ_ONLY, ToolRisk.EXECUTE))
        assertEquals(Decision.DENY, ApprovalPolicy.decide(ApprovalMode.READ_ONLY, ToolRisk.NETWORK))
    }

    @Test
    fun askPromptsForSideEffects() {
        assertEquals(Decision.AUTO, ApprovalPolicy.decide(ApprovalMode.ASK, ToolRisk.READ))
        assertEquals(Decision.PROMPT, ApprovalPolicy.decide(ApprovalMode.ASK, ToolRisk.WRITE))
        assertEquals(Decision.PROMPT, ApprovalPolicy.decide(ApprovalMode.ASK, ToolRisk.EXECUTE))
    }

    @Test
    fun cycleRotatesModes() {
        assertEquals(ApprovalMode.ASK, ApprovalMode.cycle(ApprovalMode.FULL))
        assertEquals(ApprovalMode.READ_ONLY, ApprovalMode.cycle(ApprovalMode.ASK))
        assertEquals(ApprovalMode.FULL, ApprovalMode.cycle(ApprovalMode.READ_ONLY))
    }
}
