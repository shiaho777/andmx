package com.andmx.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "The user said no" and "nobody was there to answer" are different failures,
 * and the model can only act on the difference if the text it sees states it.
 */
class ApprovalOutcomeTest {

    @Test
    fun onlyAnExplicitGrantRunsTheCall() {
        assertTrue(ApprovalOutcome.isAllowed(ApprovalOutcome.AllowedOnce))
        assertFalse(ApprovalOutcome.isAllowed(ApprovalOutcome.Rejected()))
        assertFalse(ApprovalOutcome.isAllowed(ApprovalOutcome.Cancelled))
        assertFalse(ApprovalOutcome.isAllowed(ApprovalOutcome.Unavailable()))
    }

    @Test
    fun aRefusalAndAMissingAnswererReadDifferently() {
        val refused = ApprovalOutcome.denialText(ApprovalOutcome.Rejected("只读模式"))
        val unavailable = ApprovalOutcome.denialText(ApprovalOutcome.Unavailable("会话不可用"))

        assertTrue(refused.contains("拒绝"))
        assertTrue(refused.contains("只读模式"))
        assertTrue(unavailable.contains("无法获得执行授权"))
        assertTrue(unavailable.contains("会话不可用"))
    }

    @Test
    fun cancellationsKeepTheirOwnWording() {
        assertTrue(ApprovalOutcome.denialText(ApprovalOutcome.Cancelled).contains("取消"))
    }

    @Test
    fun everyRefusalStillReadsAsARefusalWithoutAReason() {
        assertTrue(ApprovalOutcome.denialText(ApprovalOutcome.Rejected()).contains("拒绝"))
        assertTrue(ApprovalOutcome.denialText(ApprovalOutcome.Unavailable()).contains("拒绝"))
    }
}
