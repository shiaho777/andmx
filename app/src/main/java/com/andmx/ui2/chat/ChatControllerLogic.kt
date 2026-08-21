package com.andmx.ui2.chat

import com.andmx.data.MessageEntity
import com.andmx.llm.ApiFunctionCall
import com.andmx.llm.ApiMessage
import com.andmx.llm.ApiToolCall

internal object ChatControllerLogic {
    const val SKILL_PAYLOAD_LIMIT = 14_000

    fun formatSkillPayload(
        name: String,
        path: String,
        body: String,
        scripts: List<String>,
        args: String?,
    ): String = buildString {
        appendLine("<command-name>$name</command-name>")
        appendLine("<command-message>Skill loaded into context. Follow the instructions below.</command-message>")
        if (!args.isNullOrBlank()) appendLine("<command-args>$args</command-args>")
        appendLine()
        appendLine("# Skill: $name")
        appendLine("Path: $path")
        if (scripts.isNotEmpty()) appendLine("Bundled files: ${scripts.joinToString()}")
        appendLine()
        append(body.ifBlank { "(skill at $path; empty body)" })
        appendLine()
        appendLine()
        appendLine("Treat the skill body as loaded instructions for this turn. Do not re-invoke Skill for the same name.")
    }.take(SKILL_PAYLOAD_LIMIT)

    fun rebuildHistory(msgs: List<MessageEntity>): List<ApiMessage> {
        val out = mutableListOf<ApiMessage>()
        var i = 0
        while (i < msgs.size) {
            val m = msgs[i]
            when (m.role) {
                "user" -> {
                    out += ApiMessage(role = "user", content = m.content)
                    i++
                }
                "assistant" -> {
                    out += ApiMessage(role = "assistant", content = m.content)
                    i++
                }
                "tool" -> {
                    val batch = mutableListOf<ApiToolCall>()
                    val results = mutableListOf<ApiMessage>()
                    while (i < msgs.size && msgs[i].role == "tool") {
                        val tm = msgs[i]
                        val callId = "hist-${tm.id}"
                        batch += ApiToolCall(
                            id = callId,
                            function = ApiFunctionCall(
                                name = tm.toolName ?: "tool",
                                arguments = tm.toolArgs.ifBlank { "{}" },
                            ),
                        )
                        results += ApiMessage(
                            role = "tool",
                            content = tm.content,
                            toolCallId = callId,
                            name = tm.toolName,
                        )
                        i++
                    }
                    out += ApiMessage(
                        role = "assistant",
                        content = null,
                        toolCalls = batch,
                    )
                    out += results
                }
                else -> i++
            }
        }
        return out
    }

    fun approvalSummary(toolName: String, preview: String, rawArgs: String): String = buildString {
        append(toolName)
        if (preview.isNotBlank()) append(" · ").append(preview.take(160))
        else {
            if (rawArgs.length > 2) append(" · ").append(rawArgs.take(160))
        }
    }
}
