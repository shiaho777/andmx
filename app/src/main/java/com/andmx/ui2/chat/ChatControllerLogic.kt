package com.andmx.ui2.chat

import com.andmx.data.MessageEntity
import com.andmx.llm.ApiFunctionCall
import com.andmx.llm.ApiMessage
import com.andmx.llm.ApiToolCall

internal object ChatControllerLogic {
    const val MAX_SKILL_BYTES = 100_000
    const val WORKFLOW_SKILL_MAX_BYTES = 200_000
    const val DYNAMIC_WORKFLOW_SKILL_NAME = "dynamic-workflows"

    fun formatSkillPayload(
        name: String,
        path: String,
        body: String,
    ): String {
        val maxBytes = if (name == DYNAMIC_WORKFLOW_SKILL_NAME) WORKFLOW_SKILL_MAX_BYTES else MAX_SKILL_BYTES
        val base = skillBaseDirectory(path)
        val expanded = body.replace(SKILL_DIR_VAR, base)
        val (content, truncated) = truncateUtf8(expanded, maxBytes)
        return listOf(
            """<skill_content name="$name">""",
            "# Skill: $name",
            "",
            content,
            "",
            "Base directory for this skill: $base",
            "Relative paths in this skill are relative to this base directory.",
            if (truncated) "[Skill content truncated]" else "",
            "</skill_content>",
        ).filter { it.isNotEmpty() }.joinToString("\n")
    }

    private val SKILL_DIR_VAR = Regex("\\$\\{(CLAUDE_SKILL_DIR|ZCODE_SKILL_DIR)\\}")

    private fun skillBaseDirectory(path: String): String = when {
        path.endsWith("/SKILL.md") -> path.substringBeforeLast('/')
        path.endsWith("SKILL.md") -> path.substringBeforeLast('/').ifBlank { path }
        else -> path.substringBeforeLast('/').ifBlank { path }
    }

    private fun truncateUtf8(text: String, maxBytes: Int): Pair<String, Boolean> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text to false
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        val lead = bytes[end].toInt() and 0xFF
        val need = when {
            lead and 0x80 == 0 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> 1
        }
        val cut = if (end + need <= maxBytes) end + need else end
        return bytes.copyOfRange(0, cut).toString(Charsets.UTF_8) to true
    }

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
                "reminder" -> {
                    out += ApiMessage(role = "system", content = m.content)
                    i++
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
