package com.andmx.agent

import com.andmx.agent.multi.SubagentCatalog
import com.andmx.agent.zcode.AskUserQuestionParser
import com.andmx.agent.zcode.ZCodePrompts
import com.andmx.agent.zcode.isPlanModeAllowed
import com.andmx.llm.ApiMessage
import com.andmx.ui2.chat.ExecMode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
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

    @Test
    fun harnessMatchesZcodeWording() {
        val prompt = ZCodePrompts.assemble(
            mode = ExecMode.AUTO_EDIT,
            env = ZCodePrompts.SessionEnv(cwd = "/root/project", isGitRepo = false, modelLabel = "m"),
        )
        assertTrue(prompt.contains("displayed to the user as Github-flavored markdown in a terminal"))
        assertTrue(prompt.contains("mid-conversation system turns"))
        assertTrue(prompt.contains("file_path:line_number"))
    }

    @Test
    fun craftCarriesCommunicatingAndCommentRule() {
        assertTrue(ZCodePrompts.CRAFT.contains("# Communicating with the user"))
        assertTrue(ZCodePrompts.CRAFT.contains("Lead with the outcome"))
        assertTrue(ZCodePrompts.CRAFT.contains("Only write a code comment to state a constraint"))
        assertTrue(ZCodePrompts.CRAFT.contains("For actions that are hard to reverse or outward-facing"))
    }

    @Test
    fun contextManagementIncludesAutonomyAndStateCheckParagraphs() {
        assertTrue(ZCodePrompts.CONTEXT_MGMT.contains("You are operating autonomously"))
        assertTrue(ZCodePrompts.CONTEXT_MGMT.contains("pattern-matches to a known failure may have a different cause"))
    }

    @Test
    fun planOverlayContainsWorkflowAndExitContract() {
        val plan = ZCodePrompts.modeOverlay(ExecMode.PLAN)
        assertTrue(plan.contains("## Plan Workflow"))
        assertTrue(plan.contains("### Phase 4: Call ExitPlanMode"))
        assertTrue(plan.contains("MUST use ExitPlanMode"))
    }

    @Test
    fun explorePromptIsReadOnlyContract() {
        assertTrue(SubagentCatalog.EXPLORE_READONLY_PROMPT.contains("READ-ONLY MODE - NO FILE MODIFICATIONS"))
        assertTrue(SubagentCatalog.EXPLORE_READONLY_PROMPT.contains("Bash ONLY for read-only operations"))
        val explore = SubagentCatalog.createBuiltIns().first { it.name == "Explore" }
        assertEquals(SubagentCatalog.EXPLORE_READONLY_PROMPT, explore.systemPrompt)
    }

    @Test
    fun metaUserContextWrapsAgentsMdSkillsAndDate() {
        val meta = ZCodePrompts.metaUserContext(
            instructionSources = listOf(
                ZCodePrompts.InstructionSource(path = "/root/project/AGENTS.md", content = "keep minimal diffs"),
            ),
            skills = listOf(
                ZCodePrompts.SkillEntry(name = "explore", description = "Read-only search agent", path = "/s/explore"),
                ZCodePrompts.SkillEntry(
                    name = "explore",
                    description = "Read-only search agent",
                    path = "/p/explore",
                    qualifiedName = "zcode:explore",
                ),
            ),
            dateIso = "2026-08-27",
        )
        assertTrue(meta.startsWith("# agentsMd\nCodebase and user instructions are shown below"))
        assertTrue(meta.contains("Contents of /root/project/AGENTS.md (workspace instructions):"))
        assertTrue(meta.contains("The following skills are available for use with the Skill tool:"))
        assertTrue(meta.contains("- zcode:explore: Read-only search agent (also loadable as explore) (file: /p/explore)"))
        assertTrue(meta.endsWith("# currentDate\nToday's date is 2026-08-27."))
    }

    @Test
    fun skillBudgetFallbackDropsDescriptions() {
        val skills = List(400) { i ->
            ZCodePrompts.SkillEntry(name = "skill-$i", description = "d".repeat(300), path = "/x/$i")
        }
        val block = requireNotNull(ZCodePrompts.skillsBlock(skills))
        assertFalse(block.contains("dddddddddd"))
        assertTrue(block.contains("(file: /x/399)"))
    }

    @Test
    fun engineInjectsMetaUserIntoFirstUserMessageOnly() = kotlinx.coroutines.test.runTest {
        val requests = mutableListOf<com.andmx.llm.ChatRequest>()
        val llm = object : com.andmx.llm.LlmApi {
            override suspend fun chat(request: com.andmx.llm.ChatRequest): Result<com.andmx.llm.ApiMessage> {
                requests += request
                return Result.success(com.andmx.llm.ApiMessage(role = "assistant", content = "ok"))
            }
        }
        val engine = AgentEngine(tools = emptyList(), client = llm)
        engine.setMetaUserContext("# agentsMd\nkeep minimal diffs")
        val turn = TurnContext(
            provider = com.andmx.llm.provider.ProviderDefinition(id = "t", name = "t", baseUrl = "http://x"),
            model = "m",
        )
        val settings = com.andmx.settings.ProviderSettings(model = "m")

        engine.runTurn(settings, turn, "first").toList()
        engine.runTurn(settings, turn, "second").toList()

        val firstUser = requests[0].messages.first { it.role == "user" }
        val secondUser = requests[1].messages.last { it.role == "user" }
        assertTrue(firstUser.content!!.startsWith("<system-reminder>\n# agentsMd\n"))
        assertTrue(firstUser.content!!.endsWith("</system-reminder>\n\nfirst"))
        assertEquals("second", secondUser.content)
    }

    @Test
    fun seedWithResumedHistoryInjectsReminderBeforeFirstUserMessage() = kotlinx.coroutines.test.runTest {
        val llm = object : com.andmx.llm.LlmApi {
            override suspend fun chat(request: com.andmx.llm.ChatRequest): Result<com.andmx.llm.ApiMessage> =
                Result.success(com.andmx.llm.ApiMessage(role = "assistant", content = "ok"))
        }
        val engine = AgentEngine(tools = emptyList(), client = llm)
        engine.setMetaUserContext("# agentsMd\nrestored instructions")
        engine.seed(
            listOf(
                ApiMessage(role = "assistant", content = "hi"),
                ApiMessage(role = "user", content = "continue"),
            ),
        )
        val injected = engine.snapshotHistory().first { it.role == "system" && it.content?.contains("<system-reminder>") == true }
        assertTrue(injected.content!!.contains("restored instructions"))

        engine.runTurn(
            com.andmx.settings.ProviderSettings(model = "m"),
            TurnContext(
                provider = com.andmx.llm.provider.ProviderDefinition(id = "t", name = "t", baseUrl = "http://x"),
                model = "m",
            ),
            "later",
        ).toList()

        val reInjected = engine.snapshotHistory().filter {
            it.role == "user" && it.content?.contains("<system-reminder>") == true
        }
        assertTrue(reInjected.isEmpty())
    }
}
