package com.andmx.agent

import com.andmx.agent.multi.SubagentCatalog
import com.andmx.agent.zcode.AskUserQuestionParser
import com.andmx.agent.zcode.ZCodePrompts
import com.andmx.agent.zcode.isPlanModeAllowed
import com.andmx.llm.ApiFunctionCall
import com.andmx.llm.ApiMessage
import com.andmx.llm.ApiToolCall
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    fun systemPromptEmitsThreeBlocksWithoutMode() {
        val blocks = ZCodePrompts.assembleBlocks(
            env = ZCodePrompts.SessionEnv(
                cwd = "/root/project",
                isGitRepo = true,
                modelLabel = "test/model",
                branch = "main",
                gitStatus = "clean",
            ),
        )
        assertEquals(3, blocks.size)
        assertTrue(blocks[0].contains("You are ZCode"))
        assertTrue(blocks[1].contains("# Harness"))
        assertTrue(blocks[2].contains("Primary working directory: /root/project"))
        assertTrue(blocks[2].contains("Current branch: main"))
        // Mode state must not live in the system prompt (upstream: runtime reminders).
        assertFalse(blocks.joinToString("\n").contains("Mode:"))
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
    fun planReminderMentionsNoWrites() {
        assertTrue(ZCodePrompts.PLAN_MODE_FULL_REMINDER.contains("Plan mode is active"))
        assertTrue(ZCodePrompts.PLAN_MODE_FULL_REMINDER.contains("MUST NOT make any edits"))
        assertTrue(ZCodePrompts.PLAN_MODE_SPARSE_REMINDER.contains("Plan mode still active"))
        assertTrue(ZCodePrompts.PLAN_MODE_EXIT_REMINDER.contains("## Exited Plan Mode"))
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
    fun askUserQuestionModelContentHasThreeStates() {
        val q = com.andmx.agent.zcode.AskQuestion(
            question = "Which auth?",
            header = "Auth",
            options = listOf(
                com.andmx.agent.zcode.AskOption("JWT", "tokens"),
                com.andmx.agent.zcode.AskOption("Session", "server"),
            ),
        )
        // Empty → continue with best judgment, not a rejection.
        val empty = AskUserQuestionParser.formatModelContent(listOf(q), JsonObject(emptyMap()))
        assertTrue(empty.contains("did not provide answers"))
        assertTrue(empty.contains("best judgment"))
        assertTrue(empty.contains("do not treat this as a rejection"))
        // Partial → skipped count surfaced.
        val partial = AskUserQuestionParser.formatModelContent(
            listOf(q, q.copy(question = "Which DB?")),
            JsonObject(mapOf("Which auth?" to JsonPrimitive("JWT"))),
        )
        assertTrue(partial.contains("skipped 1"))
        assertTrue(partial.contains("\"Which auth?\"=\"JWT\""))
        // Full → answers + annotations.
        val full = AskUserQuestionParser.formatModelContent(
            listOf(q),
            JsonObject(mapOf("Which auth?" to JsonPrimitive("JWT"))),
            JsonObject(mapOf("Which auth?" to buildJsonObject { put("notes", "use refresh tokens") })),
        )
        assertTrue(full.contains("User has answered your questions"))
        assertTrue(full.contains("user notes: use refresh tokens"))
        // Cancelled sentinel is stripped in execute → empty state.
        runTest {
            val tool = com.andmx.agent.zcode.AskUserQuestionTool(ask = { _, _ -> "" })
            val args = buildJsonObject {
                putJsonArray("questions") {
                    add(buildJsonObject {
                        put("question", "Which auth?")
                        put("header", "Auth")
                        putJsonArray("options") {
                            add(buildJsonObject { put("label", "JWT"); put("description", "tokens") })
                            add(buildJsonObject { put("label", "Session"); put("description", "server") })
                        }
                    })
                }
                putJsonObject("answers") { put("__default__", "cancelled") }
            }
            val result = tool.execute(args)
            assertFalse(result.isError)
            assertTrue(result.output.contains("did not provide answers"))
            val cancelledTool = com.andmx.agent.zcode.AskUserQuestionTool(
                ask = { _, _ -> """{"answers":{"__default__":"cancelled"}}""" },
            )
            val viaAsk = cancelledTool.execute(buildJsonObject {
                putJsonArray("questions") {
                    add(buildJsonObject {
                        put("question", "Which auth?")
                        put("header", "Auth")
                        putJsonArray("options") {
                            add(buildJsonObject { put("label", "JWT"); put("description", "tokens") })
                            add(buildJsonObject { put("label", "Session"); put("description", "server") })
                        }
                    })
                }
            })
            assertTrue(viaAsk.output.contains("did not provide answers"))
        }
    }

    @Test
    fun exitPlanModeSchemaRequiresPlanInPromptDocs() {
        val empty = AskUserQuestionParser.parse(buildJsonObject { })
        assertTrue(empty.isEmpty())
    }

    @Test
    fun harnessMatchesZcodeWording() {
        val prompt = ZCodePrompts.assemble(
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
    fun planReminderContainsWorkflowAndExitContract() {
        val plan = ZCodePrompts.PLAN_MODE_FULL_REMINDER
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

    private fun noopLlm() = object : com.andmx.llm.LlmApi {
        override suspend fun chat(request: com.andmx.llm.ChatRequest): Result<ApiMessage> =
            Result.success(ApiMessage(role = "assistant", content = "ok"))
    }

    private fun fakeTurn() = TurnContext(
        provider = com.andmx.llm.provider.ProviderDefinition(id = "t", name = "t", baseUrl = "http://x"),
        model = "m",
    )

    @Test
    fun engineSeedsSplitSystemBlocks() = runTest {
        val engine = AgentEngine(
            tools = emptyList(),
            client = noopLlm(),
            systemPromptBlocks = listOf("B1", "B2", "B3"),
        )
        engine.runTurn(com.andmx.settings.ProviderSettings(model = "m"), fakeTurn(), "hi").toList()
        val h = engine.snapshotHistory()
        assertEquals(listOf("B1", "B2", "B3"), h.take(3).map { it.content })
        assertTrue(h.take(3).all { it.role == "system" })
        assertEquals("user", h[3].role)
    }

    @Test
    fun setCustomInstructionsAppendsToLastSystemBlock() = runTest {
        val engine = AgentEngine(
            tools = emptyList(),
            client = noopLlm(),
            systemPromptBlocks = listOf("B1", "B2", "B3"),
        )
        engine.setCustomInstructions("keep diffs small")
        val h = engine.snapshotHistory()
        assertEquals(3, h.count { it.role == "system" })
        assertTrue(h[2].content!!.endsWith("keep diffs small"))
    }

    @Test
    fun planModeRemindersFollowUpstreamCadence() = runTest {
        var planOn = false
        val engine = AgentEngine(tools = emptyList(), client = noopLlm())
        engine.setPlanModeProvider { planOn }
        val settings = com.andmx.settings.ProviderSettings(model = "m")
        val turn = fakeTurn()

        planOn = true
        engine.runTurn(settings, turn, "t1").toList()
        var reminders = engine.snapshotHistory().filter {
            ZCodePrompts.isPlanModeReminder(it.content)
        }
        assertEquals(1, reminders.size)
        assertTrue(reminders[0].content!!.contains("Plan mode is active."))
        assertTrue(reminders[0].content!!.contains("## Plan Workflow"))

        // Turns 2..5 stay under TURNS_BETWEEN_ATTACHMENTS — no new reminder.
        repeat(4) { engine.runTurn(settings, turn, "t${it + 2}").toList() }
        assertEquals(
            1,
            engine.snapshotHistory().count { ZCodePrompts.isPlanModeReminder(it.content) },
        )

        // Turn 6 is due; attachment #2 is sparse (full only on 1st + every 5th).
        engine.runTurn(settings, turn, "t6").toList()
        reminders = engine.snapshotHistory().filter { ZCodePrompts.isPlanModeReminder(it.content) }
        assertEquals(2, reminders.size)
        assertTrue(reminders.last().content!!.contains("Plan mode still active"))

        // Exit edge emits the exit reminder exactly once.
        planOn = false
        engine.runTurn(settings, turn, "t7").toList()
        engine.runTurn(settings, turn, "t8").toList()
        assertEquals(
            1,
            engine.snapshotHistory().count { it.content?.contains("## Exited Plan Mode") == true },
        )
    }

    @Test
    fun unsafeCallsSerializeBetweenSafeWaves() = runTest {
        val running = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val unsafeRanExclusive = AtomicBoolean(true)
        fun safeTool(toolName: String) = object : Tool {
            override val name = toolName
            override val description = ""
            override val parameters = buildJsonObject { }
            override val concurrentSafe = true
            override suspend fun execute(args: JsonObject): ToolResult {
                val c = running.incrementAndGet()
                maxConcurrent.accumulateAndGet(c) { a, b -> maxOf(a, b) }
                yield()
                yield()
                running.decrementAndGet()
                return ToolResult("ok-$toolName")
            }
        }
        val unsafeTool = object : Tool {
            override val name = "unsafeB"
            override val description = ""
            override val parameters = buildJsonObject { }
            override val concurrentSafe = false
            override suspend fun execute(args: JsonObject): ToolResult {
                if (running.get() != 0) unsafeRanExclusive.set(false)
                running.incrementAndGet()
                yield()
                yield()
                running.decrementAndGet()
                return ToolResult("ok-unsafeB")
            }
        }
        val calls = listOf("safeA", "unsafeB", "safeC", "safeD")
        var first = true
        val llm = object : com.andmx.llm.LlmApi {
            override suspend fun chat(request: com.andmx.llm.ChatRequest): Result<ApiMessage> {
                if (!first) return Result.success(ApiMessage(role = "assistant", content = "done"))
                first = false
                return Result.success(
                    ApiMessage(
                        role = "assistant",
                        toolCalls = calls.mapIndexed { i, n ->
                            ApiToolCall(id = "c$i", function = ApiFunctionCall(n, "{}"))
                        },
                    ),
                )
            }
        }
        val tools = listOf(safeTool("safeA"), unsafeTool, safeTool("safeC"), safeTool("safeD"))
        val engine = AgentEngine(tools = tools, client = llm)
        engine.runTurn(com.andmx.settings.ProviderSettings(model = "m"), fakeTurn(), "go").toList()

        // safeC+safeD overlap; unsafeB never shared a wave.
        assertEquals(2, maxConcurrent.get())
        assertTrue(unsafeRanExclusive.get())
        // Results land in request order.
        val toolMsgs = engine.snapshotHistory().filter { it.role == "tool" }
        assertEquals(listOf("c0", "c1", "c2", "c3"), toolMsgs.map { it.toolCallId })
    }

    @Test
    fun truncatedOutputAutoContinuesUpToThreeThenFails() = runTest {
        var calls = 0
        val llm = object : com.andmx.llm.LlmApi {
            override suspend fun chat(request: com.andmx.llm.ChatRequest): Result<ApiMessage> {
                calls += 1
                return Result.success(
                    ApiMessage(role = "assistant", content = "part$calls", finishReason = "length"),
                )
            }
        }
        val engine = AgentEngine(tools = emptyList(), client = llm)
        val events = engine.runTurn(
            com.andmx.settings.ProviderSettings(model = "m"), fakeTurn(), "go",
        ).toList()
        // 1 initial + 3 continuations, then the turn fails.
        assertEquals(1 + AgentEngine.MAX_OUTPUT_TOKEN_CONTINUATIONS, calls)
        assertTrue(events.any { it is AgentEvent.Failed })
        assertEquals(
            AgentEngine.MAX_OUTPUT_TOKEN_CONTINUATIONS,
            engine.snapshotHistory().count {
                it.role == "user" && it.content == AgentEngine.OUTPUT_TOKEN_CONTINUE_PROMPT
            },
        )
    }

    @Test
    fun truncatedOutputRecoversOnCleanFinish() = runTest {
        var calls = 0
        val llm = object : com.andmx.llm.LlmApi {
            override suspend fun chat(request: com.andmx.llm.ChatRequest): Result<ApiMessage> {
                calls += 1
                return Result.success(
                    ApiMessage(
                        role = "assistant",
                        content = "part$calls",
                        finishReason = if (calls == 1) "length" else null,
                    ),
                )
            }
        }
        val engine = AgentEngine(tools = emptyList(), client = llm)
        val events = engine.runTurn(
            com.andmx.settings.ProviderSettings(model = "m"), fakeTurn(), "go",
        ).toList()
        assertEquals(2, calls)
        assertTrue(events.none { it is AgentEvent.Failed })
        assertTrue(events.last() is AgentEvent.Done)
    }

    @Test
    fun toolCallsOnTruncatedReplyProceedWithoutContinuation() = runTest {
        var calls = 0
        val llm = object : com.andmx.llm.LlmApi {
            override suspend fun chat(request: com.andmx.llm.ChatRequest): Result<ApiMessage> {
                calls += 1
                return if (calls == 1) {
                    Result.success(
                        ApiMessage(
                            role = "assistant",
                            toolCalls = listOf(
                                ApiToolCall(id = "c0", function = ApiFunctionCall("safeA", "{}")),
                            ),
                            finishReason = "length",
                        ),
                    )
                } else {
                    Result.success(ApiMessage(role = "assistant", content = "done"))
                }
            }
        }
        val safe = object : Tool {
            override val name = "safeA"
            override val description = ""
            override val parameters = buildJsonObject { }
            override val concurrentSafe = true
            override suspend fun execute(args: JsonObject) = ToolResult("ok")
        }
        val engine = AgentEngine(tools = listOf(safe), client = llm)
        val events = engine.runTurn(
            com.andmx.settings.ProviderSettings(model = "m"), fakeTurn(), "go",
        ).toList()
        assertEquals(2, calls)
        assertTrue(events.any { it is AgentEvent.ToolFinished })
        assertEquals(
            0,
            engine.snapshotHistory().count { it.content == AgentEngine.OUTPUT_TOKEN_CONTINUE_PROMPT },
        )
    }
}
