package com.andmx.ui2.chat

import com.andmx.agent.UpdatePlanTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanFocusWindowTest {

    private fun step(index: Int, status: UpdatePlanTool.StepStatus) =
        UpdatePlanTool.PlanStep("step-$index", status)

    private fun steps(count: Int, status: UpdatePlanTool.StepStatus) =
        (1..count).map { step(it, status) }

    @Test
    fun sixOrFewerStepsAreShownInFull() {
        val window = planFocusWindow(
            steps(6, UpdatePlanTool.StepStatus.PENDING),
        )

        assertEquals(6, window.visible.size)
        assertEquals(0, window.before.size)
        assertEquals(0, window.after.size)
        assertNull(window.beforeLabel)
        assertNull(window.afterLabel)
    }

    @Test
    fun inProgressStepIsCenteredWithBuckets() {
        val input = steps(12, UpdatePlanTool.StepStatus.COMPLETED).toMutableList()
        input[8] = step(9, UpdatePlanTool.StepStatus.IN_PROGRESS)
        input[9] = step(10, UpdatePlanTool.StepStatus.PENDING)

        val window = planFocusWindow(input)

        assertEquals(listOf("step-8", "step-9", "step-10"), window.visible.map { it.content })
        assertEquals("已完成 7 项", window.beforeLabel)
        assertEquals("后面 2 项", window.afterLabel)
    }

    @Test
    fun firstIncompleteStepIsUsedWhenNothingIsInProgress() {
        val input = steps(10, UpdatePlanTool.StepStatus.COMPLETED).toMutableList()
        input[7] = step(8, UpdatePlanTool.StepStatus.PENDING)

        val window = planFocusWindow(input)

        assertEquals(listOf("step-7", "step-8", "step-9"), window.visible.map { it.content })
        assertEquals("已完成 6 项", window.beforeLabel)
        assertEquals("后面 1 项", window.afterLabel)
    }

    @Test
    fun allCompletedFocusesTheEnd() {
        val window = planFocusWindow(steps(10, UpdatePlanTool.StepStatus.COMPLETED))

        assertEquals(listOf("step-8", "step-9", "step-10"), window.visible.map { it.content })
        assertEquals("已完成 7 项", window.beforeLabel)
        assertNull(window.afterLabel)
    }

    @Test
    fun mixedBeforeBucketUsesEarlierLabel() {
        val input = steps(10, UpdatePlanTool.StepStatus.PENDING).toMutableList()
        input[0] = step(1, UpdatePlanTool.StepStatus.COMPLETED)
        input[7] = step(8, UpdatePlanTool.StepStatus.IN_PROGRESS)

        val window = planFocusWindow(input)

        assertEquals("前面 6 项", window.beforeLabel)
        assertEquals("待处理 1 项", window.afterLabel)
    }
}
