package com.andmx.agent.turn

class InvalidTurnPhase(message: String) : IllegalStateException(message)

object TurnPhase {
    const val IDLE = "idle"
    const val PROCESSING_INPUT = "processing_input"
    const val AWAITING_MODEL_RESPONSE = "awaiting_model_response"
    const val STREAMING = "streaming"
    const val SCHEDULING_TOOLS = "scheduling_tools"
    const val EXECUTING_TOOLS = "executing_tools"
    const val AGGREGATING_RESULTS = "aggregating_results"
    const val AWAITING_PERMISSION = "awaiting_permission"
    const val COMPLETING = "completing"
    const val ERROR = "error"
}

data class TurnToolState(
    val id: String,
    val name: String,
    val status: String,
    val content: Any? = null,
)

data class TurnState(
    val id: String,
    val sessionId: String,
    val turnNumber: Int,
    val phase: String,
    val traceId: String,
    val input: String,
    val streamingContent: String = "",
    val finalResponse: String? = null,
    val toolCalls: List<TurnToolState> = emptyList(),
    val pendingInputs: List<String> = emptyList(),
    val acceptsPendingInput: Boolean = true,
    val resultType: String = "success",
    val errorType: String? = null,
    val errorMessage: String? = null,
)

fun isTerminalPhase(phase: String): Boolean =
    phase == TurnPhase.COMPLETING || phase == TurnPhase.ERROR

fun canTransitionTo(current: String, next: String): Boolean {
    val allowed = when (current) {
        TurnPhase.IDLE -> setOf(TurnPhase.PROCESSING_INPUT)
        TurnPhase.PROCESSING_INPUT -> setOf(TurnPhase.AWAITING_MODEL_RESPONSE, TurnPhase.COMPLETING)
        TurnPhase.AWAITING_MODEL_RESPONSE -> setOf(TurnPhase.STREAMING, TurnPhase.COMPLETING, TurnPhase.ERROR)
        TurnPhase.STREAMING -> setOf(
            TurnPhase.SCHEDULING_TOOLS,
            TurnPhase.AGGREGATING_RESULTS,
            TurnPhase.COMPLETING,
            TurnPhase.ERROR,
        )
        TurnPhase.SCHEDULING_TOOLS -> setOf(TurnPhase.EXECUTING_TOOLS, TurnPhase.AWAITING_PERMISSION, TurnPhase.ERROR)
        TurnPhase.EXECUTING_TOOLS -> setOf(TurnPhase.AGGREGATING_RESULTS, TurnPhase.AWAITING_PERMISSION, TurnPhase.ERROR)
        TurnPhase.AGGREGATING_RESULTS -> setOf(
            TurnPhase.AWAITING_MODEL_RESPONSE,
            TurnPhase.SCHEDULING_TOOLS,
            TurnPhase.COMPLETING,
            TurnPhase.ERROR,
        )
        TurnPhase.AWAITING_PERMISSION -> setOf(TurnPhase.EXECUTING_TOOLS, TurnPhase.ERROR)
        TurnPhase.COMPLETING -> setOf(TurnPhase.IDLE)
        TurnPhase.ERROR -> setOf(TurnPhase.IDLE)
        else -> emptySet()
    }
    return next in allowed
}

class TurnMachine(initial: TurnState) {
    var state: TurnState = initial
        private set

    fun start(): TurnState = transition(TurnPhase.PROCESSING_INPUT)

    fun beginModelRequest(model: String): TurnState {
        when (state.phase) {
            TurnPhase.PROCESSING_INPUT, TurnPhase.AGGREGATING_RESULTS -> Unit
            TurnPhase.STREAMING, TurnPhase.EXECUTING_TOOLS -> transition(TurnPhase.AGGREGATING_RESULTS)
            TurnPhase.AWAITING_MODEL_RESPONSE -> return state
            TurnPhase.COMPLETING, TurnPhase.ERROR -> {
                transition(TurnPhase.IDLE)
                start()
            }
            TurnPhase.IDLE -> start()
            else -> throw InvalidTurnPhase("Cannot start a model request from ${state.phase}")
        }
        return transition(TurnPhase.AWAITING_MODEL_RESPONSE).also {
            state = it.copy()
        }.let { state }
    }

    fun receiveModelResponse(content: String): TurnState {
        val next = if (state.phase == TurnPhase.STREAMING) state else transition(TurnPhase.STREAMING)
        state = next.copy(streamingContent = next.streamingContent + content)
        return state
    }

    fun scheduleTools(calls: List<Pair<String, String>>): TurnState {
        if (state.phase == TurnPhase.AWAITING_MODEL_RESPONSE) {
            receiveModelResponse("")
        }
        val next = transition(TurnPhase.SCHEDULING_TOOLS)
        state = next.copy(toolCalls = calls.map { (id, name) -> TurnToolState(id, name, "scheduled") })
        return state
    }

    fun requestPermission(toolCallId: String): TurnState {
        val next = transition(TurnPhase.AWAITING_PERMISSION)
        state = next.copy(
            toolCalls = next.toolCalls.map {
                if (it.id == toolCallId) it.copy(status = "waiting_permission") else it
            },
        )
        return state
    }

    fun resolvePermission(toolCallId: String, allowed: Boolean): TurnState {
        state = state.copy(
            toolCalls = state.toolCalls.map {
                if (it.id == toolCallId) {
                    it.copy(status = if (allowed) "scheduled" else "permission_denied")
                } else {
                    it
                }
            },
        )
        return state
    }

    fun startToolExecution(): TurnState {
        val waiting = state.toolCalls.any { it.status == "waiting_permission" }
        val phase = if (waiting) TurnPhase.AWAITING_PERMISSION else TurnPhase.EXECUTING_TOOLS
        if (state.phase == phase) return state
        val next = transition(phase)
        state = next.copy(
            toolCalls = next.toolCalls.map {
                if (it.status == "waiting_permission" || it.status == "permission_denied") it
                else it.copy(status = "running")
            },
        )
        return state
    }

    fun completeTool(toolCallId: String, success: Boolean, content: Any? = null): TurnState {
        state = state.copy(
            toolCalls = state.toolCalls.map {
                if (it.id == toolCallId) {
                    it.copy(status = if (success) "completed" else "failed", content = content)
                } else {
                    it
                }
            },
        )
        return state
    }

    fun aggregateResults(): TurnState = transition(TurnPhase.AGGREGATING_RESULTS)

    fun queuePendingInput(text: String): TurnState {
        state = state.copy(pendingInputs = state.pendingInputs + text)
        return state
    }

    fun drainPendingInputs(): List<String> {
        val drained = state.pendingInputs
        state = state.copy(pendingInputs = emptyList())
        return drained
    }

    fun complete(response: String, resultType: String = "success"): TurnState {
        val next = transition(TurnPhase.COMPLETING)
        state = next.copy(finalResponse = response, resultType = resultType)
        return state
    }

    fun fail(errorType: String, message: String, resultType: String): TurnState {
        val phase = if (canTransitionTo(state.phase, TurnPhase.ERROR)) {
            transition(TurnPhase.ERROR)
        } else {
            state
        }
        state = phase.copy(errorType = errorType, errorMessage = message, resultType = resultType)
        return state
    }

    private fun transition(phase: String): TurnState {
        if (!canTransitionTo(state.phase, phase)) {
            throw InvalidTurnPhase("Cannot transition from ${state.phase} to $phase")
        }
        state = state.copy(phase = phase)
        return state
    }

    companion object {
        fun create(sessionId: String, turnNumber: Int, input: String, traceId: String, turnId: String): TurnMachine =
            TurnMachine(
                TurnState(
                    id = turnId,
                    sessionId = sessionId,
                    turnNumber = turnNumber,
                    phase = TurnPhase.IDLE,
                    traceId = traceId,
                    input = input,
                ),
            )
    }
}
