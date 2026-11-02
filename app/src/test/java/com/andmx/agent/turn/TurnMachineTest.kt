package com.andmx.agent.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TurnMachineTest {
    @Test
    fun startsIdleThenProcessesInput() {
        val machine = TurnMachine.create("sess_123", 1, "Hello", "trace", "turn")
        assertEquals(TurnPhase.IDLE, machine.state.phase)
        assertEquals("Hello", machine.state.input)
        assertEquals(TurnPhase.PROCESSING_INPUT, machine.start().phase)
    }

    @Test
    fun awaitsModelResponseAfterStart() {
        val machine = started()
        assertEquals(TurnPhase.AWAITING_MODEL_RESPONSE, machine.beginModelRequest("claude").phase)
    }

    @Test
    fun drivesModelToolModelLoopAndKeepsToolContent() {
        val machine = awaiting()
        machine.receiveModelResponse("")
        machine.scheduleTools(listOf("call-1" to "Read"))
        machine.startToolExecution()
        val completed = machine.completeTool("call-1", success = true, content = "ok")
        assertEquals("ok", completed.toolCalls.last().content)
        machine.aggregateResults()
        assertEquals(TurnPhase.AWAITING_MODEL_RESPONSE, machine.beginModelRequest("claude").phase)
    }

    @Test
    fun completesFromAwaitingModelResponse() {
        val machine = awaiting()
        val next = machine.complete("Goodbye")
        assertEquals(TurnPhase.COMPLETING, next.phase)
        assertEquals("Goodbye", next.finalResponse)
        assertEquals("success", next.resultType)
    }

    @Test
    fun invalidTransitionThrows() {
        val machine = TurnMachine.create("sess_123", 1, "Hello", "trace", "turn")
        val error = assertThrows(InvalidTurnPhase::class.java) { machine.complete("test") }
        assertEquals("Cannot transition from idle to completing", error.message)
    }

    @Test
    fun pendingInputsDrainInOrderAndLeaveTheQueueEmpty() {
        val machine = started()
        machine.queuePendingInput("one")
        machine.queuePendingInput("two")
        assertEquals(listOf("one", "two"), machine.drainPendingInputs())
        assertEquals(emptyList<String>(), machine.state.pendingInputs)
        assertEquals(emptyList<String>(), machine.drainPendingInputs())
    }

    @Test
    fun permissionStaysAwaitingUntilResolvedAndDoesNotRunTheTool() {
        val machine = awaiting()
        machine.receiveModelResponse("")
        machine.scheduleTools(listOf("call-1" to "Bash"))
        machine.requestPermission("call-1")
        assertEquals(TurnPhase.AWAITING_PERMISSION, machine.state.phase)
        assertEquals("waiting_permission", machine.state.toolCalls.single().status)
        machine.resolvePermission("call-1", allowed = false)
        assertEquals(TurnPhase.AWAITING_PERMISSION, machine.state.phase)
        assertEquals("permission_denied", machine.state.toolCalls.single().status)
        machine.startToolExecution()
        assertEquals(TurnPhase.EXECUTING_TOOLS, machine.state.phase)
        assertEquals("permission_denied", machine.state.toolCalls.single().status)
    }

    @Test
    fun cancelAndFailureKeepStableResultTypes() {
        val cancelled = awaiting()
        cancelled.complete("", "cancelled")
        assertEquals(TurnPhase.COMPLETING, cancelled.state.phase)
        assertEquals("cancelled", cancelled.state.resultType)
        val failed = awaiting()
        failed.fail("model_error", "多次重试后仍无响应", "error_during_execution")
        assertEquals(TurnPhase.ERROR, failed.state.phase)
        assertEquals("model_error", failed.state.errorType)
        assertEquals("error_during_execution", failed.state.resultType)
    }

    private fun started(): TurnMachine {
        val machine = TurnMachine.create("sess_123", 1, "Hello", "trace", "turn")
        machine.start()
        return machine
    }

    private fun awaiting(): TurnMachine {
        val machine = started()
        machine.beginModelRequest("claude")
        return machine
    }
}
