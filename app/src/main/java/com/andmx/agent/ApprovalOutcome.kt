package com.andmx.agent

import kotlinx.serialization.json.JsonObject

/**
 * The closed answer to "may this specific call proceed?".
 *
 * A Boolean cannot carry the difference between *the user said no* and *nobody
 * was there to answer*, and the model sees both as the same failed call. The
 * engine treats only [AllowedOnce] as approval — everything else fails closed.
 */
sealed interface ApprovalOutcome {

    /** The asked-about call may run. A grant covers exactly one call. */
    data object AllowedOnce : ApprovalOutcome

    /** A policy or the user refused the call. */
    data class Rejected(val reason: String? = null) : ApprovalOutcome

    /** The question was withdrawn before anyone answered it. */
    data object Cancelled : ApprovalOutcome

    /**
     * No answerer could decide: no UI is attached, the session is gone, or the
     * answerer threw. Refusing is the only safe reading, so this never grants.
     */
    data class Unavailable(val reason: String? = null) : ApprovalOutcome

    companion object {
        /** Read any approval answer as the single question the engine asks. */
        fun isAllowed(outcome: ApprovalOutcome): Boolean = outcome is AllowedOnce

        /**
         * Text fed back to the model when a call is refused. A rejected and an
         * unavailable call are different failures, and the model can only act
         * on the difference if the text states it.
         */
        fun denialText(outcome: ApprovalOutcome): String = when (outcome) {
            is AllowedOnce -> ""
            is Rejected -> outcome.reason?.let { "已被拒绝执行: $it" } ?: "已被用户拒绝执行"
            is Cancelled -> "执行已被取消"
            is Unavailable -> outcome.reason?.let { "无法获得执行授权: $it" }
                ?: "无法获得执行授权: 没有可用的审批方,已按拒绝处理"
        }
    }
}

/** The gate consulted before running each tool call. */
typealias ApprovalGate = suspend (Tool, JsonObject) -> ApprovalOutcome
