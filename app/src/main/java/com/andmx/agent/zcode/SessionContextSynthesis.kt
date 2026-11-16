package com.andmx.agent.zcode

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

object SessionContextSynthesis {

    data class Message(val role: String, val content: String, val toolName: String? = null)

    data class Result(val content: String, val truncated: Boolean, val fallback: Boolean)

    @Serializable
    data class HandoffCapsule(
        val objective: String = "",
        val decisions: List<String> = emptyList(),
        val filesTouched: List<String> = emptyList(),
        val openQuestions: List<String> = emptyList(),
        val nextSteps: List<String> = emptyList(),
    )

    const val TAIL_LIMIT = 40
    const val SNIPPET_CHARS = 800
    const val FALLBACK_NOTE = "[fallback: model synthesis unavailable; coarse-filtered transcript]"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun charBudget(maxTokens: Int): Int = maxTokens.coerceIn(1, 12_000) * 4

    fun coarseSelect(messages: List<Message>, query: String): List<Message> {
        val tail = messages.takeLast(TAIL_LIMIT)
        if (query.isBlank()) return tail
        val hits = tail.filter {
            it.content.contains(query, ignoreCase = true) ||
                it.toolName?.contains(query, ignoreCase = true) == true
        }
        return hits.ifEmpty { tail }
    }

    fun renderTranscript(messages: List<Message>): String =
        messages.joinToString("\n") { "[${it.role}] ${it.content.take(SNIPPET_CHARS)}" }

    fun clip(text: String, budget: Int): Pair<String, Boolean> =
        text.take(budget) to (text.length > budget)

    fun handoffPrompt(query: String, transcript: String): String = buildString {
        append("You are compacting a prior agent session into a handoff capsule so a new session can continue the work.\n")
        append("Focus query: ").append(query.ifBlank { "(continue the session)" }).append('\n')
        append("Transcript (most recent last):\n---\n")
        append(transcript)
        append("\n---\n")
        append("Return ONLY a JSON object with exactly these keys:\n")
        append("{\"objective\":\"string\",\"decisions\":[\"string\"],\"filesTouched\":[\"string\"],\"openQuestions\":[\"string\"],\"nextSteps\":[\"string\"]}\n")
        append("Base every entry on the transcript; use an empty string or array when unknown. ")
        append("No markdown fences, no extra text.")
    }

    fun relevantPrompt(query: String, transcript: String, budgetChars: Int): String = buildString {
        append("Summarize the session transcript excerpts that are relevant to the query.\n")
        append("Query: ").append(query.ifBlank { "(general context)" }).append('\n')
        append("Transcript (most recent last):\n---\n")
        append(transcript)
        append("\n---\n")
        append("Return a concise plain-text summary (no JSON, no markdown fences) within ")
        append(budgetChars)
        append(" characters. Keep concrete file paths, decisions, errors and open questions; drop chatter.")
    }

    fun parseCapsule(raw: String): HandoffCapsule? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching {
            json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
        }.getOrNull() ?: return null
        fun str(key: String): String =
            (obj[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        fun list(key: String): List<String> =
            (obj[key] as? JsonArray)?.mapNotNull { element ->
                (element as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            }.orEmpty()
        val capsule = HandoffCapsule(
            objective = str("objective"),
            decisions = list("decisions"),
            filesTouched = list("filesTouched"),
            openQuestions = list("openQuestions"),
            nextSteps = list("nextSteps"),
        )
        val empty = capsule.objective.isEmpty() && capsule.decisions.isEmpty() &&
            capsule.filesTouched.isEmpty() && capsule.openQuestions.isEmpty() &&
            capsule.nextSteps.isEmpty()
        return if (empty) null else capsule
    }

    fun formatCapsule(capsule: HandoffCapsule): String = json.encodeToString(capsule)

    suspend fun handoff(
        query: String,
        messages: List<Message>,
        maxTokens: Int,
        model: suspend (String) -> String?,
    ): Result {
        val transcript = renderTranscript(coarseSelect(messages, query))
        val budget = charBudget(maxTokens)
        if (transcript.isBlank()) return Result("", truncated = false, fallback = false)
        val raw = runCatching { model(handoffPrompt(query, transcript)) }.getOrNull()
        val capsule = raw?.let { parseCapsule(it) }
        if (capsule != null) {
            val (content, truncated) = clip(formatCapsule(capsule), budget)
            return Result(content, truncated, fallback = false)
        }
        val (content, truncated) = clip("$FALLBACK_NOTE\n$transcript", budget)
        return Result(content, truncated, fallback = true)
    }

    suspend fun relevant(
        query: String,
        messages: List<Message>,
        maxTokens: Int,
        summarize: Boolean = true,
        model: suspend (String) -> String?,
    ): Result {
        val transcript = renderTranscript(coarseSelect(messages, query))
        val budget = charBudget(maxTokens)
        if (transcript.isBlank()) return Result("", truncated = false, fallback = false)
        if (summarize) {
            val raw = runCatching { model(relevantPrompt(query, transcript, budget)) }.getOrNull()
            if (!raw.isNullOrBlank()) {
                val (content, truncated) = clip(raw.trim(), budget)
                return Result(content, truncated, fallback = false)
            }
            val (content, truncated) = clip("$FALLBACK_NOTE\n$transcript", budget)
            return Result(content, truncated, fallback = true)
        }
        val (content, truncated) = clip(transcript, budget)
        return Result(content, truncated, fallback = false)
    }
}