package com.andmx.ui2.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloModeConfirmationTest {

    @Test
    fun enteringYoloFromAnotherModeNeedsConfirmation() {
        assertTrue(requiresYoloConfirmation(ExecMode.BUILD, ExecMode.YOLO))
        assertTrue(requiresYoloConfirmation(ExecMode.PLAN, ExecMode.YOLO))
        assertTrue(requiresYoloConfirmation(ExecMode.AUTO, ExecMode.YOLO))
    }

    @Test
    fun alreadyInYoloDoesNotAskAgain() {
        assertFalse(requiresYoloConfirmation(ExecMode.YOLO, ExecMode.YOLO))
    }

    @Test
    fun leavingYoloAndOtherModesNeverAsk() {
        assertFalse(requiresYoloConfirmation(ExecMode.YOLO, ExecMode.BUILD))
        assertFalse(requiresYoloConfirmation(ExecMode.BUILD, ExecMode.AUTO))
        assertFalse(requiresYoloConfirmation(ExecMode.GUARDED, ExecMode.PLAN))
    }
}
