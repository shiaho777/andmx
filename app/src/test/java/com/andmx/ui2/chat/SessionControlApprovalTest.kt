package com.andmx.ui2.chat

import com.andmx.agent.AgentEngine
import com.andmx.agent.AllowedPrompts
import com.andmx.agent.ApprovalOutcome
import com.andmx.agent.ApprovalRuleStore
import com.andmx.agent.GoalToolState
import com.andmx.agent.Tool
import com.andmx.agent.ToolResult
import com.andmx.agent.ToolRisk
import com.andmx.agent.UpdatePlanTool
import com.andmx.agent.zcode.AskQuestion
import com.andmx.llm.ApiMessage
import com.andmx.llm.ChatRequest
import com.andmx.llm.LlmApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionControlApprovalTest {

    private fun engine(): AgentEngine = AgentEngine(
        tools = emptyList(),
        client = object : LlmApi {
            override suspend fun chat(request: ChatRequest): Result<ApiMessage> =
                Result.success(ApiMessage(role = "assistant", content = "ok"))
        },
    )

    private fun session(conversationId: Long): Session = Session(
        conversationId = conversationId,
        engine = engine(),
        providerId = "provider-$conversationId",
        model = "model-$conversationId",
        planTool = UpdatePlanTool(),
        goalState = GoalToolState(),
        approvalMode = ExecMode.BUILD,
    )

    private fun tool(name: String = "Bash", risk: ToolRisk = ToolRisk.EXECUTE): Tool = object : Tool {
        override val name: String = name
        override val description: String = "test tool"
        override val parameters: JsonObject = JsonObject(emptyMap())
        override val risk: ToolRisk = risk
        override suspend fun execute(args: JsonObject): ToolResult = ToolResult("ok")
    }

    private fun args(command: String): JsonObject =
        JsonObject(mapOf("command" to JsonPrimitive(command)))

    private fun resolve(
        control: SessionControl,
        allow: Boolean,
        scope: ApprovalScope,
        projectRules: MutableList<Triple<String, String, ApprovalRuleStore.RuleBehavior>> = mutableListOf(),
        persistedGrants: MutableList<Long> = mutableListOf(),
    ) {
        control.resolveApprovalScoped(
            control.pendingApprovals.value.values.single(),
            allow,
            scope,
            persistProjectRule = { toolCanonical, ruleKey, behavior ->
                projectRules += Triple(toolCanonical, ruleKey, behavior)
            },
            persistGrants = { persistedGrants += it },
        )
    }

    @Test
    fun promptBuildsApprovalRequestAndCompletesOnAllow() = runBlocking {
        val control = SessionControl()
        control.sessions[1L] = session(1L)
        val notifications = mutableListOf<Triple<Long, String, String>>()
        val hooks = mutableListOf<Pair<String, String>>()
        val callbacksDone = CompletableDeferred<Unit>()
        val outcome = async(Dispatchers.Default) {
            control.prompt(
                conversationId = 1L,
                tool = tool(),
                args = args("npm test"),
                mode = ExecMode.BUILD,
                reason = "需要确认",
                notify = { id, title, body -> notifications += Triple(id, title, body) },
                launchPermissionHook = { name, raw ->
                    hooks += name to raw
                    callbacksDone.complete(Unit)
                },
            )
        }
        withTimeout(5_000) { callbacksDone.await() }
        val req = control.pendingApprovals.value.values.singleOrNull()!!
        assertEquals(1L, req.conversationId)
        assertEquals("Bash", req.toolName)
        assertEquals(ToolRisk.EXECUTE, req.risk)
        assertEquals(ExecMode.BUILD.label, req.modeLabel)
        assertTrue(req.summary.contains("需要确认"))
        assertEquals("需要确认", req.reason)
        assertEquals("npm test", req.inputPreview)
        assertEquals("命令前缀: npm …", req.rulePreview)
        assertTrue(req.suggestions.isNotEmpty())
        assertEquals("tool", req.kind)
        assertEquals("""{"command":"npm test"}""", control.sessions[1L]?.pendingArgs)
        assertNotNull(control.sessions[1L]?.pending)
        assertEquals(listOf(Triple(1L, "等待确认", req.summary)), notifications)
        assertEquals(1, hooks.size)
        assertEquals("Bash", hooks.first().first)

        resolve(control, allow = true, scope = ApprovalScope.ONCE)
        assertEquals(ApprovalOutcome.AllowedOnce, outcome.await())
        assertNull(control.pendingApprovals.value.values.singleOrNull())
        assertNull(control.sessions[1L]?.pending)
        assertNull(control.sessions[1L]?.pendingArgs)
    }

    @Test
    fun promptRejectedAndUnavailableSemantics() = runBlocking {
        val control = SessionControl()
        val unavailable = control.prompt(
            conversationId = 9L,
            tool = tool(),
            args = args("ls"),
            mode = ExecMode.BUILD,
            notify = { _, _, _ -> },
            launchPermissionHook = { _, _ -> },
        )
        assertEquals(ApprovalOutcome.Unavailable("会话不可用"), unavailable)

        control.sessions[2L] = session(2L)
        val outcome = async(Dispatchers.Default) {
            control.prompt(
                conversationId = 2L,
                tool = tool(),
                args = args("ls"),
                mode = ExecMode.BUILD,
                notify = { _, _, _ -> },
                launchPermissionHook = { _, _ -> },
            )
        }
        withTimeout(5_000) {
            withContext(Dispatchers.Default) {
                while (control.pendingApprovals.value.values.singleOrNull() == null) kotlinx.coroutines.yield()
            }
        }
        resolve(control, allow = false, scope = ApprovalScope.ONCE)
        assertTrue(outcome.await() is ApprovalOutcome.Rejected)
    }

    @Test
    fun sessionAllowRegistersRuleAndGrant() {
        val control = SessionControl()
        val s = session(1L)
        control.sessions[1L] = s
        val pending = CompletableDeferred<Boolean>()
        s.pending = pending
        s.pendingArgs = """{"command":"npm test"}"""
        control.publishApproval(ApprovalRequest(
            conversationId = 1L,
            toolName = "Bash",
            risk = ToolRisk.EXECUTE,
            summary = "Bash · npm test",
            modeLabel = ExecMode.BUILD.label,
        ))
        val projectRules = mutableListOf<Triple<String, String, ApprovalRuleStore.RuleBehavior>>()
        val persisted = mutableListOf<Long>()
        resolve(control, allow = true, scope = ApprovalScope.SESSION_ALLOW, projectRules, persisted)

        assertCompleted(true, pending)
        assertEquals(
            ApprovalScope.SESSION_ALLOW,
            control.approvalRulesByConversation[1L]?.get("shell:prefix:npm"),
        )
        assertTrue(projectRules.isEmpty())
        assertEquals(listOf(1L), persisted)
        val grants = s.permissions.sessionGrants()
        assertEquals(1, grants.size)
        assertEquals("Bash", grants.first().toolName)
        assertEquals("npm:*", grants.first().ruleContent)
        assertTrue(grants.first().allowed)
        assertEquals(
            ApprovalScope.SESSION_ALLOW,
            control.sessionRuleFor(1L, "Bash", """{"command":"npm run build"}"""),
        )
    }

    private fun assertCompleted(expected: Boolean, deferred: CompletableDeferred<Boolean>) {
        assertTrue(deferred.isCompleted)
        assertEquals(expected, deferred.getCompleted())
    }

    private fun assertCompleted(expected: String, deferred: CompletableDeferred<String>) {
        assertTrue(deferred.isCompleted)
        assertEquals(expected, deferred.getCompleted())
    }

    @Test
    fun projectScopesPersistBehaviorWithoutSessionGrant() {
        val control = SessionControl()
        val s = session(1L)
        control.sessions[1L] = s
        val pending = CompletableDeferred<Boolean>()
        s.pending = pending
        s.pendingArgs = """{"command":"npm test"}"""
        control.publishApproval(ApprovalRequest(
            conversationId = 1L,
            toolName = "Bash",
            risk = ToolRisk.EXECUTE,
            summary = "Bash · npm test",
            modeLabel = ExecMode.BUILD.label,
        ))
        val projectRules = mutableListOf<Triple<String, String, ApprovalRuleStore.RuleBehavior>>()
        val persisted = mutableListOf<Long>()
        resolve(control, allow = true, scope = ApprovalScope.PROJECT_ALLOW, projectRules, persisted)

        assertCompleted(true, pending)
        assertEquals(
            listOf(Triple("shell", "shell:prefix:npm", ApprovalRuleStore.RuleBehavior.ALLOW)),
            projectRules,
        )
        assertTrue(persisted.isEmpty())
        assertTrue(s.permissions.sessionGrants().isEmpty())
    }

    @Test
    fun denyScopesCompleteWithFalseAndRecordRule() {
        val control = SessionControl()
        val s = session(1L)
        control.sessions[1L] = s
        val pending = CompletableDeferred<Boolean>()
        s.pending = pending
        s.pendingArgs = """{"command":"rm -rf build"}"""
        control.publishApproval(ApprovalRequest(
            conversationId = 1L,
            toolName = "Bash",
            risk = ToolRisk.EXECUTE,
            summary = "Bash · rm",
            modeLabel = ExecMode.BUILD.label,
        ))
        val projectRules = mutableListOf<Triple<String, String, ApprovalRuleStore.RuleBehavior>>()
        val persisted = mutableListOf<Long>()
        resolve(control, allow = true, scope = ApprovalScope.SESSION_DENY, projectRules, persisted)

        assertCompleted(false, pending)
        assertEquals(
            ApprovalScope.SESSION_DENY,
            control.approvalRulesByConversation[1L]?.get("shell:prefix:rm"),
        )
        assertEquals(listOf(1L), persisted)
        val grants = s.permissions.sessionGrants()
        assertEquals(1, grants.size)
        assertFalse(grants.first().allowed)
        assertEquals("rm:*", grants.first().ruleContent)
    }

    @Test
    fun approvalsAreIsolatedPerConversation() {
        val control = SessionControl()
        val a = session(1L)
        val b = session(2L)
        control.sessions[1L] = a
        control.sessions[2L] = b
        val pendingA = CompletableDeferred<Boolean>()
        val pendingB = CompletableDeferred<Boolean>()
        a.pending = pendingA
        a.pendingArgs = """{"command":"npm test"}"""
        b.pending = pendingB
        b.pendingArgs = """{"command":"npm test"}"""
        control.publishApproval(ApprovalRequest(
            conversationId = 1L,
            toolName = "Bash",
            risk = ToolRisk.EXECUTE,
            summary = "Bash",
            modeLabel = ExecMode.BUILD.label,
        ))
        resolve(control, allow = true, scope = ApprovalScope.SESSION_ALLOW)

        assertCompleted(true, pendingA)
        assertNull(control.sessions[1L]?.pending)
        assertNotNull(control.sessions[2L]?.pending)
        assertFalse(pendingB.isCompleted)
        assertNull(control.approvalRulesByConversation[2L])
        assertNull(control.sessionRuleFor(2L, "Bash", """{"command":"npm test"}"""))
        assertNotNull(control.sessionRuleFor(1L, "Bash", """{"command":"npm test"}"""))
    }

    @Test
    fun staleDecisionCannotConsumeAnotherRequestOrPersistARule() {
        val control = SessionControl()
        val first = session(1L)
        val second = session(2L)
        control.sessions[1L] = first
        control.sessions[2L] = second
        first.pending = CompletableDeferred()
        second.pending = CompletableDeferred()
        val request = ApprovalRequest(1L, "Bash", ToolRisk.EXECUTE, "first", ExecMode.BUILD.label)
        val other = request.copy(conversationId = 2L, id = "other")
        val replacement = request.copy(id = "replacement")
        control.publishApproval(request)
        control.publishApproval(other)
        control.publishApproval(replacement)

        val rules = mutableListOf<String>()
        val stale = control.resolveApprovalScoped(
            request, true, ApprovalScope.PROJECT_ALLOW,
            persistProjectRule = { _, key, _ -> rules += key },
            persistGrants = {},
        )
        assertFalse(stale)
        assertTrue(rules.isEmpty())
        assertEquals(mapOf(1L to replacement, 2L to other), control.pendingApprovals.value)
        assertTrue(control.resolveApprovalScoped(replacement, false, ApprovalScope.ONCE, { _, _, _ -> }, {}))
        assertEquals(mapOf(2L to other), control.pendingApprovals.value)
        assertFalse(second.pending!!.isCompleted)
    }

    @Test
    fun resolveApprovalCompletesPendingAnswerJson() {
        val control = SessionControl()
        val s = session(1L)
        control.sessions[1L] = s
        val answer = CompletableDeferred<String>()
        s.pendingAnswer = answer
        control.publishApproval(ApprovalRequest(
            conversationId = 1L,
            toolName = "AskUserQuestion",
            risk = ToolRisk.READ,
            summary = "问题",
            modeLabel = ExecMode.BUILD.label,
            kind = "ask_user",
        ))
        resolve(control, allow = true, scope = ApprovalScope.ONCE)
        assertCompleted("""{"answers":{"__default__":"approved"}}""", answer)
        assertNull(control.sessions[1L]?.pendingAnswer)
    }

    @Test
    fun resolveUserQuestionCompletesAnswerAndPending() {
        val control = SessionControl()
        val s = session(1L)
        control.sessions[1L] = s
        val pending = CompletableDeferred<Boolean>()
        val answer = CompletableDeferred<String>()
        s.pending = pending
        s.pendingAnswer = answer
        control.publishApproval(ApprovalRequest(
            conversationId = 1L,
            toolName = "AskUserQuestion",
            risk = ToolRisk.READ,
            summary = "问题",
            modeLabel = ExecMode.BUILD.label,
            kind = "ask_user",
        ))
        control.resolveUserQuestion(control.pendingApprovals.value.values.single(), """{"answers":{"q":"a"}}""")
        assertCompleted("""{"answers":{"q":"a"}}""", answer)
        assertCompleted(true, pending)
        assertNull(control.sessions[1L]?.pendingAnswer)
        assertNull(control.sessions[1L]?.pending)
        assertNull(control.pendingApprovals.value.values.singleOrNull())
    }

    @Test
    fun ruleKeyAndPreviewNormalization() {
        val control = SessionControl()
        assertEquals("shell:prefix:npm", control.approvalRuleKey("Bash", """{"command":"npm test"}"""))
        assertEquals("shell:prefix:npm", control.approvalRuleKey("run_shell", """{"command":"NPM ci"}"""))
        assertNull(control.approvalRuleKey("Bash", """{"command":""}"""))
        assertEquals("edit:file:app/src/Main.kt", control.approvalRuleKey("Edit", """{"path":"app/src/Main.kt"}"""))
        assertEquals("webfetch:any", control.approvalRuleKey("WebFetch", """{"url":"https://x"}"""))
        assertEquals("命令前缀: npm …", control.approvalRulePreview("Bash", """{"command":"npm test"}"""))
        assertEquals("文件: app/src/Main.kt", control.approvalRulePreview("Edit", """{"path":"app/src/Main.kt"}"""))
        assertEquals("所有 webfetch 调用", control.approvalRulePreview("WebFetch", """{"url":"https://x"}"""))
        assertEquals("", control.approvalRulePreview("Bash", "{}"))
    }

    @Test
    fun beginAskApprovalSetsDeadlinesAndSummary() {
        val control = SessionControl()
        val s = session(1L)
        control.sessions[1L] = s
        s.answerSnoozed = true
        val questions = listOf(
            AskQuestion(header = "选择", question = "用哪个方案?", options = emptyList()),
            AskQuestion(header = "", question = "第二个很长的问题文本内容超过二十四个字符长度限制", options = emptyList()),
        )
        val (deferred, summary) = control.beginAskApproval(
            session = s,
            questions = questions,
            autoResolve = true,
            hiddenGraceMs = 60_000L,
            autoResolveMs = 300_000L,
        )
        assertSame(deferred, s.pendingAnswer)
        assertFalse(s.answerSnoozed)
        assertTrue(summary.startsWith("选择 · "))
        assertTrue(summary.contains("第二个很长的问题文本内容"))
        val req = control.pendingApprovals.value.values.singleOrNull()!!
        assertEquals("ask_user", req.kind)
        assertEquals("AskUserQuestion", req.toolName)
        assertEquals(questions, req.questions)
        assertNotNull(req.autoDeadlineAt)
        assertNotNull(req.countdownVisibleAt)
        assertEquals(240_000L, req.autoDeadlineAt!! - req.countdownVisibleAt!!)

        val (_, blankSummary) = control.beginAskApproval(
            session = s,
            questions = emptyList(),
            autoResolve = false,
            hiddenGraceMs = 60_000L,
            autoResolveMs = 300_000L,
        )
        assertEquals("需要你的决定", blankSummary)
        val req2 = control.pendingApprovals.value.values.singleOrNull()!!
        assertNull(req2.autoDeadlineAt)
        assertNull(req2.countdownVisibleAt)
    }

    @Test
    fun snoozeClearsCountdownAndKeepsQuestionPending() {
        val control = SessionControl()
        val s = session(1L)
        control.sessions[1L] = s
        val (deferred, _) = control.beginAskApproval(
            session = s,
            questions = listOf(AskQuestion(header = "h", question = "q", options = emptyList())),
            autoResolve = true,
            hiddenGraceMs = 60_000L,
            autoResolveMs = 300_000L,
        )
        val timer = kotlinx.coroutines.Job()
        s.answerTimer = timer
        control.snoozeUserQuestion(control.pendingApprovals.value.values.single())
        assertTrue(s.answerSnoozed)
        assertNull(s.answerTimer)
        assertTrue(timer.isCancelled)
        assertFalse(deferred.isCompleted)
        val req = control.pendingApprovals.value.values.singleOrNull()!!
        assertNull(req.autoDeadlineAt)
        assertNull(req.countdownVisibleAt)
        assertEquals("ask_user", req.kind)
    }

    @Test
    fun autoResolveCompletesEmptyAnswersAfterDelay() = runBlocking {
        val control = SessionControl()
        val s = session(1L)
        control.sessions[1L] = s
        val (deferred, _) = control.beginAskApproval(
            session = s,
            questions = listOf(AskQuestion(header = "h", question = "q", options = emptyList())),
            autoResolve = true,
            hiddenGraceMs = 10L,
            autoResolveMs = 20L,
        )
        control.autoResolveAnswer(1L, s, deferred, delayMs = 1L)
        assertTrue(deferred.isCompleted)
        assertEquals("""{"answers":{}}""", deferred.getCompleted())
        assertNull(control.pendingApprovals.value.values.singleOrNull())
    }

    @Test
    fun snoozedAnswerIsNotAutoResolved() = runBlocking {
        val control = SessionControl()
        val s = session(1L)
        control.sessions[1L] = s
        val (deferred, _) = control.beginAskApproval(
            session = s,
            questions = listOf(AskQuestion(header = "h", question = "q", options = emptyList())),
            autoResolve = true,
            hiddenGraceMs = 10L,
            autoResolveMs = 20L,
        )
        control.snoozeUserQuestion(control.pendingApprovals.value.values.single())
        control.autoResolveAnswer(1L, s, deferred, delayMs = 1L)
        assertFalse(deferred.isCompleted)
        assertNotNull(control.pendingApprovals.value.values.singleOrNull())
    }

    @Test
    fun planApprovalRequestsCarryKindAndPlanText() {
        val control = SessionControl()
        val s = session(1L)
        control.sessions[1L] = s
        val enter = control.beginPlanApproval(s, "EnterPlanMode", "进入计划模式", "enter_plan")
        assertSame(enter, s.pending)
        val enterReq = control.pendingApprovals.value.values.singleOrNull()!!
        assertEquals("enter_plan", enterReq.kind)
        assertEquals("进入计划模式", enterReq.summary)
        assertEquals("", enterReq.planText)

        val plan = "步骤一\n步骤二"
        val exit = control.beginPlanApproval(s, "ExitPlanMode", plan.take(240), "exit_plan", planText = plan)
        assertSame(exit, s.pending)
        val exitReq = control.pendingApprovals.value.values.singleOrNull()!!
        assertEquals("exit_plan", exitReq.kind)
        assertEquals(plan, exitReq.planText)
        assertFalse(exit.isCompleted)
        resolve(control, allow = true, scope = ApprovalScope.ONCE)
        assertCompleted(true, exit)
    }

    @Test
    fun cancelSessionApprovalRejectsPendingAndCancelsAnswer() {
        val control = SessionControl()
        val s = session(1L)
        control.sessions[1L] = s
        val pending = CompletableDeferred<Boolean>()
        val answer = CompletableDeferred<String>()
        s.pending = pending
        s.pendingAnswer = answer
        val timer = kotlinx.coroutines.Job()
        s.answerTimer = timer
        control.cancelSessionApproval(s)
        assertCompleted(false, pending)
        assertCompleted("""{"answers":{"__default__":"cancelled"}}""", answer)
        assertNull(control.sessions[1L]?.pending)
        assertNull(control.sessions[1L]?.pendingAnswer)
        assertNull(s.answerTimer)
        assertTrue(timer.isCancelled)
    }

    @Test
    fun cancelPendingApprovalOnlyClearsMatchingConversation() {
        val control = SessionControl()
        val s = session(1L)
        control.sessions[1L] = s
        s.pending = CompletableDeferred()
        control.publishApproval(ApprovalRequest(
            conversationId = 2L,
            toolName = "Bash",
            risk = ToolRisk.EXECUTE,
            summary = "other",
            modeLabel = ExecMode.BUILD.label,
        ))
        control.cancelPendingApproval(1L, s)
        assertNotNull(control.pendingApprovals.value.values.singleOrNull())
        assertFalse(s.pending!!.isCompleted)
        assertNotNull(s.pending)

        val other = control.pendingApprovals.value.getValue(2L)
        control.publishApproval(other.copy(conversationId = 1L))
        val pending = s.pending!!
        control.cancelPendingApproval(1L, s)
        assertEquals(mapOf(2L to other), control.pendingApprovals.value)
        assertTrue(pending.isCompleted)
        assertEquals(false, pending.getCompleted())
        assertNull(control.sessions[1L]?.pending)
    }

    @Test
    fun allowedPromptsGrantsAreIsolatedAndMatchBash() {
        val control = SessionControl()
        control.addAllowedPrompts(1L, listOf(AllowedPrompts.Entry("Bash", "run tests")))
        assertTrue(control.allowedPromptsByConversation.containsKey(1L))
        assertFalse(control.allowedPromptsByConversation.containsKey(2L))
        assertEquals(
            listOf(com.andmx.agent.permission.PermissionRuleValue("Bash", "gradle test")),
            control.bashGrantRules(1L, "gradle test"),
        )
        assertTrue(control.bashGrantRules(1L, "cat README.md").isEmpty())
        assertTrue(control.bashGrantRules(2L, "gradle test").isEmpty())
        assertTrue(AllowedPrompts.grantsFor("gradle test", control.allowedPromptsByConversation[1L]!!.promptsFor("Bash")))
    }

    @Test
    fun liveBusIsStablePerConversationAndIsolated() {
        val control = SessionControl()
        val bus1 = control.liveBus(1L)
        assertSame(bus1, control.liveBus(1L))
        assertSame(bus1, control.sideEvents(1L))
        val bus2 = control.liveBus(2L)
        assertFalse(bus1 === bus2)
        assertTrue(bus1.tryEmit(ChatEvent.AssistantChunk("buffered")))
        assertTrue(bus1.replayCache.isEmpty())
    }

    @Test
    fun emitLiveEventDeliversToSubscribers() {
        runBlocking {
            val control = SessionControl()
            val received = mutableListOf<ChatEvent>()
            val collector = launch(Dispatchers.Default) {
                control.sideEvents(1L).collect { ev ->
                    received += ev
                }
            }
            kotlinx.coroutines.delay(100)
            assertTrue(control.emitLiveEvent(1L, ChatEvent.AssistantChunk("hello")))
            assertTrue(control.emitLiveEvent(1L, ChatEvent.Done))
            assertTrue(control.emitLiveEvent(2L, ChatEvent.AssistantChunk("other")))
            withTimeout(5_000) {
                while (received.size < 2) kotlinx.coroutines.delay(10)
            }
            collector.cancel()
            assertEquals(listOf(ChatEvent.AssistantChunk("hello"), ChatEvent.Done), received)
        }
    }

    @Test
    fun clearLiveEventsReplacesBusAndDropsSubscribers() = runBlocking {
        val control = SessionControl()
        val old = control.liveBus(1L)
        val collector = launch(Dispatchers.Default) {
            control.sideEvents(1L).collect { }
        }
        withTimeout(5_000) {
            withContext(Dispatchers.Default) {
                while (old.subscriptionCount.value == 0) kotlinx.coroutines.yield()
            }
        }
        control.clearLiveEvents(1L)
        assertFalse(control.liveChatEvents.containsKey(1L))
        val fresh = control.liveBus(1L)
        assertFalse(old === fresh)
        collector.cancel()
    }

    @Test
    fun pendingApprovalStateFlowReflectsLatestRequest() = runBlocking {
        val control = SessionControl()
        assertNull(control.pendingApprovals.value.values.singleOrNull())
        val req = ApprovalRequest(
            conversationId = 1L,
            toolName = "Bash",
            risk = ToolRisk.EXECUTE,
            summary = "s",
            modeLabel = ExecMode.BUILD.label,
        )
        control.publishApproval(req)
        assertSame(req, control.pendingApprovals.value.values.singleOrNull())
        val first = async(Dispatchers.Default) { control.pendingApprovals.first { it.isEmpty() } }
        kotlinx.coroutines.delay(100)
        control.clearPendingApprovalFor(1L)
        withTimeout(5_000) { assertTrue(first.await().isEmpty()) }
    }
}
