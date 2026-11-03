package com.andmx.agent.workflow.dynamic

const val MAX_ESCALATIONS_PER_ASK = 3

const val ESCALATION_BUDGET_EXHAUSTED =
    "Escalation budget exhausted: at most $MAX_ESCALATIONS_PER_ASK escalations per task. " +
        "Do not call escalate again. Proceed on your best judgement with the information you have, " +
        "and state in your final result the assumptions you relied on and the doubts that remain."

const val RESOLVE_WORKFLOW_QUESTION_DELIVERED =
    "The subagent that asked has resumed its turn with your answer; the run keeps going as before."

data class PendingWorkflowQuestion(
    val qid: String,
    val runId: String,
    val actor: String,
    val actorName: String?,
    val question: String,
    val context: String?,
    val askedAt: Long,
)

sealed interface ResolveQuestionResult {
    data class Delivered(val qid: String) : ResolveQuestionResult
    data class Rejected(val reason: String, val message: String) : ResolveQuestionResult
}

class WorkflowEscalationRegistry(private val retiredLimit: Int = RETIRED_HISTORY_LIMIT) {
    private class Parked(val question: PendingWorkflowQuestion, val settle: (String) -> Unit)

    private enum class Retired { RESOLVED, WITHDRAWN }

    private val lock = Any()
    private val parked = LinkedHashMap<String, Parked>()
    private val retired = LinkedHashMap<String, Retired>()
    private val seqs = HashMap<String, Int>()

    fun mintQuestionId(runId: String): String = synchronized(lock) {
        val seq = (seqs[runId] ?: 0) + 1
        seqs[runId] = seq
        questionIdFragments(runId)
            .map { "dwfq-$it-$seq" }
            .firstOrNull { it !in parked && it !in retired }
            ?: throw IllegalStateException(
                "Cannot mint a free escalation question id for run $runId (seq=$seq): " +
                    "every candidate is already taken in the registry. Two drivers may be sharing one runId.",
            )
    }

    fun park(question: PendingWorkflowQuestion, settle: (String) -> Unit) {
        synchronized(lock) { parked[question.qid] = Parked(question, settle) }
    }

    fun isTaken(qid: String): Boolean = synchronized(lock) { qid in parked || qid in retired }

    fun withdraw(qid: String): Boolean = synchronized(lock) {
        if (qid in parked) {
            retire(qid, Retired.WITHDRAWN)
            true
        } else {
            false
        }
    }

    fun resolve(qid: String, answer: String): ResolveQuestionResult {
        val entry = synchronized(lock) {
            val found = parked[qid]
            if (found == null) {
                return when (retired[qid]) {
                    Retired.RESOLVED -> ResolveQuestionResult.Rejected(
                        "already_resolved",
                        "Question $qid was already answered and the subagent has moved on with that " +
                            "answer. No need to answer again; if you have more to add, wait for its next " +
                            "escalation.",
                    )
                    Retired.WITHDRAWN -> ResolveQuestionResult.Rejected(
                        "run_not_in_flight",
                        "The ask that raised question $qid is no longer in flight (the run was " +
                            "cancelled, or that ask already failed), so nobody is waiting for this answer. " +
                            "Use GetWorkflowRun to see the run's current state before deciding what to do next.",
                    )
                    null -> ResolveQuestionResult.Rejected(
                        "unknown_question",
                        "Unknown question id $qid. It may be misspelled, or it may come from a process " +
                            "that is gone: parked questions are not persisted, so they vanish on restart (a " +
                            "resume makes the subagent ask again under a new id). Use GetWorkflowRun to read " +
                            "the run's pendingQuestions for the ids that are actually awaiting an answer.",
                    )
                }
            }
            retire(qid, Retired.RESOLVED)
            found
        }
        entry.settle(answer)
        return ResolveQuestionResult.Delivered(qid)
    }

    fun pendingFor(runId: String): List<PendingWorkflowQuestion> = synchronized(lock) {
        parked.values.map { it.question }.filter { it.runId == runId }
    }

    private fun retire(qid: String, reason: Retired) {
        parked.remove(qid)
        retired.remove(qid)
        retired[qid] = reason
        while (retired.size > retiredLimit) {
            val oldest = retired.keys.firstOrNull() ?: break
            retired.remove(oldest)
        }
    }

    companion object {
        const val RETIRED_HISTORY_LIMIT = 256
    }
}

internal fun sanitizeIdSegment(value: String): String =
    value.replace("_", "__").replace(Regex("[^A-Za-z0-9._-]"), "_")

internal fun questionIdFragments(runId: String): List<String> {
    val body = runId.removePrefix("dwfrun-")
    val full = sanitizeIdSegment(body)
    return listOf(full.take(8), full.take(16), full).distinct()
}

private const val WORKFLOW_NOTIFICATION_MAX_CHARS = 120_000

private val SYSTEM_NOTIFICATION_BANNER = listOf(
    "[SYSTEM NOTIFICATION - NOT USER INPUT]",
    "This is an automated workflow event, NOT a message from the user.",
    "Do NOT interpret this as user acknowledgement, confirmation, or response to any pending question.",
    "",
)

private fun escapeXml(value: String): String = buildString(value.length) {
    value.forEach { c ->
        when (c) {
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '&' -> append("&amp;")
            '\'' -> append("&apos;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }
}

fun formatWorkflowEscalationNotification(
    runLabel: String,
    runId: String,
    qid: String,
    actor: String,
    question: String,
    context: String?,
): String {
    val lines = SYSTEM_NOTIFICATION_BANNER.toMutableList()
    lines += "<workflow-escalation>"
    lines += "  <run-id>${escapeXml(runId)}</run-id>"
    lines += "  <run>${escapeXml(runLabel)}</run>"
    lines += "  <question-id>${escapeXml(qid)}</question-id>"
    lines += "  <subagent>${escapeXml(actor)}</subagent>"
    lines += "  <question>${escapeXml(question)}</question>"
    if (!context.isNullOrEmpty()) lines += "  <context>${escapeXml(context)}</context>"
    lines += "</workflow-escalation>"
    lines += ""
    lines += "Subagent $actor in workflow run $runLabel ($runId) escalated a blocking question and is parked on that call waiting for your answer."
    lines += "Next step: call ResolveWorkflowQuestion with question_id=\"$qid\" and your answer."
    lines += "The run is still running: only the subagent that asked is parked — every other subagent and the script's control flow keep going. So do not drop what you are doing, but do not leave it unanswered either: nothing times out on its behalf."
    lines += "If this notification is ever lost, GetWorkflowRun lists the questions this run still owes an answer to."
    val text = lines.joinToString("\n")
    return if (text.length <= WORKFLOW_NOTIFICATION_MAX_CHARS) text
    else text.take(WORKFLOW_NOTIFICATION_MAX_CHARS) + "\n[truncated]"
}
