package com.andmx.agent.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollaborationModeTest {
    @Test
    fun subagentPermissionModeMapping() {
        assertEquals(CollaborationMode.EDIT, CollaborationMode.fromSubagentPermissionMode("acceptEdits"))
        assertEquals(CollaborationMode.AUTO, CollaborationMode.fromSubagentPermissionMode("auto"))
        assertEquals(CollaborationMode.YOLO, CollaborationMode.fromSubagentPermissionMode("bypassPermissions"))
        assertEquals(CollaborationMode.BUILD, CollaborationMode.fromSubagentPermissionMode("dontAsk"))
        assertEquals(CollaborationMode.PLAN, CollaborationMode.fromSubagentPermissionMode("plan"))
        assertNull(CollaborationMode.fromSubagentPermissionMode("default"))
        assertNull(CollaborationMode.fromSubagentPermissionMode(""))
        assertNull(CollaborationMode.fromSubagentPermissionMode(null))
    }
}
