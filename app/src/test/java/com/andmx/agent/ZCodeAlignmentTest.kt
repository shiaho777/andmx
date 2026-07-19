package com.andmx.agent

import com.andmx.agent.zcode.AskUserQuestionParser
import com.andmx.agent.zcode.ZCodePrompts
import com.andmx.agent.zcode.isPlanModeAllowed
import com.andmx.ui2.chat.ExecMode
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZCodeAlignmentTest {
    @Test
    fun systemPromptContainsZCodeIdentityAndHarness() {
        val prompt = ZCodePrompts.assemble(
            mode = ExecMode.AUTO_EDIT,
            env = ZCodePrompts.SessionEnv(
                cwd = "/root/project",
                isGitRepo = true,
                modelLabel = "test/model",
                branch = "main",
                gitStatus = "clean",
            ),
        )
        assertTrue(prompt.contains("You are ZCode"))
        assertTrue(prompt.contains("# Harness"))
        assertTrue(prompt.contains("Primary working directory: /root/project"))
        assertTrue(prompt.contains("Mode: build"))
        assertTrue(prompt.contains("Current branch: main"))
    }

    @Test
    fun planModeAllowsReadsAndTodosBlocksWrites() {
        assertTrue(isPlanModeAllowed("Read"))
        assertTrue(isPlanModeAllowed("read_file"))
        assertTrue(isPlanModeAllowed("Grep"))
        assertTrue(isPlanModeAllowed("TodoWrite"))
        assertTrue(isPlanModeAllowed("EnterPlanMode"))
        assertTrue(isPlanModeAllowed("ExitPlanMode"))
        assertTrue(isPlanModeAllowed("AskUserQuestion"))
        assertTrue(isPlanModeAllowed("Skill"))
        assertFalse(isPlanModeAllowed("Write"))
        assertFalse(isPlanModeAllowed("Edit"))
        assertFalse(isPlanModeAllowed("Bash"))
        assertFalse(isPlanModeAllowed("write_file"))
    }

    @Test
    fun planOverlayMentionsNoWrites() {
        val plan = ZCodePrompts.modeOverlay(ExecMode.PLAN)
        assertTrue(plan.contains("plan mode"))
        assertTrue(plan.lowercase().contains("do not write") || plan.contains("Do NOT write"))
    }

    @Test
    fun askUserQuestionParserReadsStructuredQuestions() {
        val args = buildJsonObject {
            putJsonArray("questions") {
                add(buildJsonObject {
                    put("question", "Which auth method?")
                    put("header", "Auth")
                    putJsonArray("options") {
                        add(buildJsonObject {
                            put("label", "JWT (Recommended)")
                            put("description", "Stateless tokens")
                            put("preview", "Authorization: Bearer …")
                        })
                        add(buildJsonObject {
                            put("label", "Session")
                            put("description", "Server sessions")
                        })
                    }
                    put("multiSelect", false)
                })
            }
        }
        val qs = AskUserQuestionParser.parse(args)
        assertEquals(1, qs.size)
        assertEquals("Auth", qs[0].header)
        assertEquals(2, qs[0].options.size)
        assertEquals("JWT (Recommended)", qs[0].options[0].label)
        val json = AskUserQuestionParser.formatAnswersJson(
            qs,
            mapOf("Which auth method?" to "JWT (Recommended)"),
            mapOf("Which auth method?" to ("Authorization: Bearer …" to null)),
        )
        assertTrue(json.contains("JWT (Recommended)"))
        assertTrue(json.contains("answers"))
    }

    @Test
    fun exitPlanModeSchemaRequiresPlanInPromptDocs() {
        val empty = AskUserQuestionParser.parse(buildJsonObject { })
        assertTrue(empty.isEmpty())
    }
}
