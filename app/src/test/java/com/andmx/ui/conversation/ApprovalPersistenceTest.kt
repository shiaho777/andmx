package com.andmx.ui.conversation

import com.andmx.agent.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApprovalPersistenceTest {

    @Test
    fun restoredApprovalRiskAcceptsEnumNamesCaseInsensitively() {
        assertEquals(ToolRisk.WRITE, restoredApprovalRisk("WRITE"))
        assertEquals(ToolRisk.NETWORK, restoredApprovalRisk("network"))
    }

    @Test
    fun restoredApprovalRiskReturnsNullForLegacyOrUnknownValues() {
        assertNull(restoredApprovalRisk(""))
        assertNull(restoredApprovalRisk("dangerous"))
    }
}
