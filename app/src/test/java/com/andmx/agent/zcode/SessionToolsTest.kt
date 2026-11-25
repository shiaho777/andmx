package com.andmx.agent.zcode

import com.andmx.agent.Tool
import com.andmx.agent.multi.SubagentCatalog
import com.andmx.settings.CustomSubAgent
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionToolsTest {

    private class FakeHost : SessionControlHost {
        var sessions: List<SessionSummary> = emptyList()
        var read: SessionContextRead = SessionContextRead(status = "not_found")
        var created: SessionCreateResult = SessionCreateResult(7L, "7", "t", "none")
        var sendResult: SessionSendResult = SessionSendResult("r", "7", "sent")
        var states: MutableMap<String, SessionWaitState?> = mutableMapOf()
        val sendCalls = mutableListOf<Triple<String, String, String>>()
        val createCalls = mutableListOf<Triple<String, String?, String?>>()
        var listCalls = 0
        var lastQuery: String? = null
        var lastIncludeArchived: Boolean = false
        var readCalls = 0
        var lastMaxTokens: Int = 0
        var waitCalls = 0
        var sendError: String? = null
        var createError: String? = null

        override suspend fun mutateSession(action: SessionMutation, sessionId: String, value: String?): String =
            "${action.toolName}/$sessionId/$value"

        override suspend fun listSessions(query: String?, includeArchived: Boolean): List<SessionSummary> {
            listCalls++
            lastQuery = query
            lastIncludeArchived = includeArchived
            return sessions
        }

        override suspend fun readSession(sessionId: String, maxTokens: Int): SessionContextRead {
            readCalls++
            lastMaxTokens = maxTokens
            return read
        }

        override suspend fun createSession(title: String, task: String?, workspace: String?): SessionCreateResult {
            createCalls += Triple(title, task, workspace)
            createError?.let { throw IllegalStateException(it) }
            return created
        }

        override suspend fun sendToSession(sessionId: String, message: String, requestId: String): SessionSendResult {
            sendCalls += Triple(sessionId, message, requestId)
            sendError?.let { throw IllegalStateException(it) }
            return sendResult.copy(requestId = requestId)
        }

        override suspend fun waitState(sessionId: String): SessionWaitState? {
            waitCalls++
            return states[sessionId]
        }
    }

    private fun host(): FakeHost = FakeHost()

    private fun tools(host: SessionControlHost = host()): List<Tool> = SessionTools(host).all()

    private fun tool(name: String, host: SessionControlHost = host()): Tool =
        tools(host).single { it.name == name }

    private fun required(schema: JsonObject): List<String> =
        schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun props(tool: Tool): JsonObject = tool.parameters["properties"]!!.jsonObject

    private fun parse(output: String): JsonObject = Json.parseToJsonElement(output).jsonObject

    private fun state(id: String, running: Boolean, queued: Int = 0) =
        SessionWaitState(id, running, queued, lastActivity = 100L, taskStatus = if (running) "running" else "completed")

    @Test
    fun toolNamesAndSchemas() {
        val all = tools()
        assertEquals(SESSION_CONTROL_TOOL_NAMES, all.map { it.name }.toSet())
        val list = tool("ListSessions")
        assertNull(list.parameters["required"])
        assertEquals(false, list.parameters["additionalProperties"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(setOf("query", "includeArchived"), props(list).keys)
        val read = tool("ReadSession")
        assertEquals(listOf("sessionId"), required(read.parameters))
        assertEquals(
            READ_SESSION_CONTEXT_MAX_TOKENS,
            props(read)["maxTokens"]!!.jsonObject["maximum"]!!.jsonPrimitive.int,
        )
        val create = tool("CreateSession")
        assertEquals(listOf("title"), required(create.parameters))
        assertEquals(setOf("title", "task", "workspace"), props(create).keys)
        val send = tool("SendToSession")
        assertEquals(listOf("sessionId", "message"), required(send.parameters))
        assertEquals(setOf("sessionId", "message", "retryOf"), props(send).keys)
        val wait = tool("WaitSessions")
        assertEquals(listOf("sessionIds"), required(wait.parameters))
        assertEquals(1, props(wait)["sessionIds"]!!.jsonObject["minItems"]!!.jsonPrimitive.int)
        assertEquals(16, props(wait)["sessionIds"]!!.jsonObject["maxItems"]!!.jsonPrimitive.int)
        assertEquals(
            SESSION_WAIT_MAX_TIMEOUT_MS.toInt(),
            props(wait)["timeoutMs"]!!.jsonObject["maximum"]!!.jsonPrimitive.int,
        )
        all.forEach { t ->
            assertEquals("https://json-schema.org/draft/2020-12/schema", t.parameters["\$schema"]!!.jsonPrimitive.content)
            assertTrue(t.shouldDefer)
            assertTrue(t.description.isNotBlank())
        }
    }

    @Test
    fun riskMetadata() {
        listOf("ListSessions", "ReadSession", "WaitSessions").forEach { name ->
            val t = tool(name)
            assertTrue(t.readOnly)
            assertFalse(t.needsApproval)
            assertTrue(t.concurrentSafe)
            assertEquals("none", t.sideEffectScope)
            assertEquals("low", t.riskLevel)
            assertTrue(t.permissionName!!.startsWith("session_control."))
        }
        listOf("CreateSession", "SendToSession").forEach { name ->
            val t = tool(name)
            assertFalse(t.readOnly)
            assertTrue(t.needsApproval)
            assertFalse(t.concurrentSafe)
            assertEquals("system", t.sideEffectScope)
            assertEquals("medium", t.riskLevel)
            assertTrue(t.permissionName!!.startsWith("session_control."))
        }
    }

    @Test
    fun listPassesFiltersAndFormatsRows() = runTest {
        val fake = host()
        fake.sessions = listOf(
            SessionSummary(1L, "sess_a", "Alpha", archived = false, running = true, lastActivity = 5L),
            SessionSummary(2L, "2", "Beta", archived = true, running = false, lastActivity = 9L),
        )
        val result = tool("ListSessions", fake).execute(buildJsonObject {
            put("query", "alp")
            put("includeArchived", true)
        })
        assertFalse(result.isError)
        assertEquals(1, fake.listCalls)
        assertEquals("alp", fake.lastQuery)
        assertTrue(fake.lastIncludeArchived)
        val rows = parse(result.output)["sessions"]!!.jsonArray
        assertEquals(2, rows.size)
        val first = rows[0].jsonObject
        assertEquals(1, first["id"]!!.jsonPrimitive.int)
        assertEquals("sess_a", first["sessionId"]!!.jsonPrimitive.content)
        assertEquals("Alpha", first["title"]!!.jsonPrimitive.content)
        assertFalse(first["archived"]!!.jsonPrimitive.boolean)
        assertTrue(first["running"]!!.jsonPrimitive.boolean)
        val blankQuery = tool("ListSessions", fake).execute(buildJsonObject { put("query", "  ") })
        assertFalse(blankQuery.isError)
        assertNull(fake.lastQuery)
        assertFalse(fake.lastIncludeArchived)
    }

    @Test
    fun listValidationAndHostFailure() = runTest {
        val fake = host()
        val tooLong = tool("ListSessions", fake).execute(buildJsonObject { put("query", "x".repeat(201)) })
        assertTrue(tooLong.isError)
        assertEquals(0, fake.listCalls)
    }

    @Test
    fun readValidationAndChainReuse() = runTest {
        val fake = host()
        val read = tool("ReadSession", fake)
        assertTrue(read.execute(buildJsonObject {}).isError)
        assertTrue(read.execute(buildJsonObject { put("sessionId", "  ") }).isError)
        assertTrue(read.execute(buildJsonObject {
            put("sessionId", "7")
            put("maxTokens", 0)
        }).isError)
        assertTrue(read.execute(buildJsonObject {
            put("sessionId", "7")
            put("maxTokens", READ_SESSION_CONTEXT_MAX_TOKENS + 1)
        }).isError)
        assertEquals(0, fake.readCalls)
        fake.read = SessionContextRead(
            status = "success",
            source = "local",
            sessionId = "7",
            title = "Alpha",
            content = "history body",
            truncated = true,
        )
        val ok = read.execute(buildJsonObject { put("sessionId", "7") })
        assertFalse(ok.isError)
        assertEquals(READ_SESSION_CONTEXT_DEFAULT_MAX_TOKENS, fake.lastMaxTokens)
        assertTrue(ok.output.contains("ReadSession returned local history for 7."))
        assertTrue(ok.output.contains("Title: Alpha"))
        assertTrue(ok.output.contains("truncated"))
        assertTrue(ok.output.contains("history body"))
        fake.read = SessionContextRead(status = "not_found", sessionId = "9")
        val missing = read.execute(buildJsonObject { put("sessionId", "9") })
        assertFalse(missing.isError)
        assertEquals("Session 9 was not found.", missing.output)
        fake.read = SessionContextRead(status = "failed", sessionId = "9", error = "boom")
        val failed = read.execute(buildJsonObject { put("sessionId", "9") })
        assertTrue(failed.isError)
        assertTrue(failed.output.contains("boom"))
    }

    @Test
    fun createValidationAndResult() = runTest {
        val fake = host()
        val create = tool("CreateSession", fake)
        assertTrue(create.execute(buildJsonObject {}).isError)
        assertTrue(create.execute(buildJsonObject { put("title", "  ") }).isError)
        assertTrue(create.execute(buildJsonObject {
            put("title", "t")
            put("task", "   ")
        }).isError)
        assertTrue(create.execute(buildJsonObject {
            put("title", "t")
            put("workspace", "w".repeat(513))
        }).isError)
        assertTrue(fake.createCalls.isEmpty())
        fake.created = SessionCreateResult(7L, "7", "New task", "sent")
        val ok = create.execute(buildJsonObject {
            put("title", "New task")
            put("task", "do it")
        })
        assertFalse(ok.isError)
        assertEquals(listOf(Triple("New task", "do it", null)), fake.createCalls)
        val json = parse(ok.output)
        assertEquals(7, json["id"]!!.jsonPrimitive.int)
        assertEquals("7", json["sessionId"]!!.jsonPrimitive.content)
        assertEquals("sent", json["firstMessage"]!!.jsonPrimitive.content)
        assertEquals("unchanged", json["focus"]!!.jsonPrimitive.content)
        fake.createError = "workspace mismatch"
        val rejected = create.execute(buildJsonObject {
            put("title", "t")
            put("workspace", "/elsewhere")
        })
        assertTrue(rejected.isError)
        assertTrue(rejected.output.contains("workspace mismatch"))
    }

    @Test
    fun sendValidation() = runTest {
        val fake = host()
        val send = tool("SendToSession", fake)
        assertTrue(send.execute(buildJsonObject {}).isError)
        assertTrue(send.execute(buildJsonObject { put("sessionId", "7") }).isError)
        assertTrue(send.execute(buildJsonObject {
            put("sessionId", "7")
            put("message", "  ")
        }).isError)
        assertTrue(send.execute(buildJsonObject {
            put("sessionId", "7")
            put("message", "m".repeat(20_001))
        }).isError)
        assertTrue(send.execute(buildJsonObject {
            put("sessionId", "7")
            put("message", "hi")
            put("retryOf", "r".repeat(129))
        }).isError)
        assertTrue(fake.sendCalls.isEmpty())
    }

    @Test
    fun sendBusyQueuesAndRequestIdentity() = runTest {
        val fake = host()
        fake.sendResult = SessionSendResult("", "7", "queued")
        val send = tool("SendToSession", fake) as SendToSessionTool
        val result = send.execute("call-1", buildJsonObject {
            put("sessionId", "7")
            put("message", "hello")
        })
        assertFalse(result.isError)
        val json = parse(result.output)
        assertEquals("queued", json["status"]!!.jsonPrimitive.content)
        assertEquals("call-1", json["requestId"]!!.jsonPrimitive.content)
        assertEquals(listOf(Triple("7", "hello", "call-1")), fake.sendCalls)
        val retried = send.execute("call-2", buildJsonObject {
            put("sessionId", "7")
            put("message", "hello")
            put("retryOf", "call-1")
        })
        assertEquals(result.output, retried.output)
        assertEquals(1, fake.sendCalls.size)
        val fresh = send.execute("call-3", buildJsonObject {
            put("sessionId", "7")
            put("message", "hello")
        })
        assertEquals("call-3", parse(fresh.output)["requestId"]!!.jsonPrimitive.content)
        assertEquals(2, fake.sendCalls.size)
    }

    @Test
    fun sendFailureRecordedAndRetryReconciles() = runTest {
        val fake = host()
        fake.sendError = "provider down"
        val send = tool("SendToSession", fake) as SendToSessionTool
        val failed = send.execute("call-1", buildJsonObject {
            put("sessionId", "7")
            put("message", "hello")
        })
        assertTrue(failed.isError)
        assertTrue(failed.output.contains("provider down"))
        assertEquals("call-1", parse(failed.output)["requestId"]!!.jsonPrimitive.content)
        fake.sendError = null
        val retry = send.execute("call-2", buildJsonObject {
            put("sessionId", "7")
            put("message", "hello")
            put("retryOf", "call-1")
        })
        assertEquals(failed.output, retry.output)
        assertEquals(1, fake.sendCalls.size)
    }

    @Test
    fun ledgerIsScopedPerSession() = runTest {
        val fake = host()
        val send = tool("SendToSession", fake) as SendToSessionTool
        send.execute("call-1", buildJsonObject {
            put("sessionId", "7")
            put("message", "hello")
        })
        send.execute("call-2", buildJsonObject {
            put("sessionId", "8")
            put("message", "hello")
            put("retryOf", "call-1")
        })
        assertEquals(2, fake.sendCalls.size)
        assertEquals("8", fake.sendCalls[1].first)
    }

    @Test
    fun requestIdentityPrefersRetryOf() {
        assertEquals("call-9", requestIdentity("call-9", null))
        assertEquals("call-9", requestIdentity("call-9", "  "))
        assertEquals("orig", requestIdentity("call-9", "orig"))
    }

    @Test
    fun busyDecisionPure() {
        assertEquals("sent", SessionToolLogic.busyDecision(running = false, queued = 0))
        assertEquals("queued", SessionToolLogic.busyDecision(running = true, queued = 0))
        assertEquals("queued", SessionToolLogic.busyDecision(running = false, queued = 2))
        assertEquals("queued", SessionToolLogic.busyDecision(running = true, queued = 3))
    }

    @Test
    fun ledgerEvictsOldest() {
        val ledger = SessionRequestLedger(maxEntries = 2)
        fun result(id: String) = SessionSendResult(id, "7", "sent")
        ledger.put("a", result("a"))
        ledger.put("b", result("b"))
        ledger.put("c", result("c"))
        assertNull(ledger.get("a"))
        assertEquals("b", ledger.get("b")!!.requestId)
        assertEquals("c", ledger.get("c")!!.requestId)
        ledger.put("b", result("b2"))
        assertEquals("b2", ledger.get("b")!!.requestId)
        ledger.put("d", result("d"))
        assertNull(ledger.get("c"))
    }

    @Test
    fun waitValidation() = runTest {
        val fake = host()
        val wait = tool("WaitSessions", fake)
        assertTrue(wait.execute(buildJsonObject {}).isError)
        assertTrue(wait.execute(buildJsonObject {
            put("sessionIds", kotlinx.serialization.json.JsonArray(emptyList()))
        }).isError)
        assertTrue(wait.execute(buildJsonObject {
            put("sessionIds", kotlinx.serialization.json.JsonArray(
                (1..17).map { kotlinx.serialization.json.JsonPrimitive(it.toString()) },
            ))
        }).isError)
        assertTrue(wait.execute(buildJsonObject {
            put("sessionIds", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(" "))))
        }).isError)
        assertEquals(0, fake.waitCalls)
    }

    @Test
    fun waitZeroTimeoutReportsNow() = runTest {
        val fake = host()
        fake.states["7"] = state("7", running = true)
        fake.states["8"] = state("8", running = false)
        val result = tool("WaitSessions", fake).execute(buildJsonObject {
            put("sessionIds", kotlinx.serialization.json.JsonArray(
                listOf("7", "8", "missing").map { kotlinx.serialization.json.JsonPrimitive(it) },
            ))
            put("timeoutMs", 0)
        })
        assertFalse(result.isError)
        val json = parse(result.output)
        assertFalse(json["allSettled"]!!.jsonPrimitive.boolean)
        assertTrue(json["timedOut"]!!.jsonPrimitive.boolean)
        val rows = json["sessions"]!!.jsonArray.map { it.jsonObject }
        assertEquals("timeout", rows[0]["status"]!!.jsonPrimitive.content)
        assertTrue(rows[0]["running"]!!.jsonPrimitive.boolean)
        assertEquals("completed", rows[1]["status"]!!.jsonPrimitive.content)
        assertEquals("not_found", rows[2]["status"]!!.jsonPrimitive.content)
        assertNull(rows[2]["running"])
    }

    @Test
    fun waitPollsUntilSettled() = runTest {
        val fake = host()
        fake.states["7"] = state("7", running = true, queued = 1)
        val wait = tool("WaitSessions", fake)
        var output = ""
        val waiter = launch {
            output = wait.execute(buildJsonObject {
                put("sessionIds", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("7"))))
                put("timeoutMs", 5_000)
            }).output
        }
        testScheduler.runCurrent()
        assertTrue(fake.waitCalls > 0)
        assertFalse(waiter.isCompleted)
        fake.states["7"] = state("7", running = false)
        testScheduler.advanceTimeBy(300)
        testScheduler.runCurrent()
        waiter.join()
        val json = parse(output)
        assertTrue(json["allSettled"]!!.jsonPrimitive.boolean)
        assertFalse(json["timedOut"]!!.jsonPrimitive.boolean)
        assertEquals("completed", json["sessions"]!!.jsonArray[0].jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun waitTimesOutHonestly() = runTest {
        val fake = host()
        fake.states["7"] = state("7", running = true)
        val result = tool("WaitSessions", fake).execute(buildJsonObject {
            put("sessionIds", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("7"))))
            put("timeoutMs", 600)
        })
        val json = parse(result.output)
        assertTrue(json["timedOut"]!!.jsonPrimitive.boolean)
        assertFalse(json["allSettled"]!!.jsonPrimitive.boolean)
        assertEquals("timeout", json["sessions"]!!.jsonArray[0].jsonObject["status"]!!.jsonPrimitive.content)
        assertTrue(json["waitedMs"]!!.jsonPrimitive.content.toLong() >= 600)
    }

    @Test
    fun waitStatusPure() {
        assertEquals("not_found", sessionWaitStatus(null, timedOut = false))
        assertEquals("not_found", sessionWaitStatus(null, timedOut = true))
        assertEquals("pending", sessionWaitStatus(state("7", running = true), timedOut = false))
        assertEquals("timeout", sessionWaitStatus(state("7", running = true), timedOut = true))
        assertEquals("pending", sessionWaitStatus(state("7", running = false, queued = 1), timedOut = false))
        assertEquals("completed", sessionWaitStatus(state("7", running = false), timedOut = true))
    }

    @Test
    fun waitSummaryPure() {
        val text = formatSessionWait(
            requested = listOf("7", "8"),
            states = mapOf("7" to state("7", running = false), "8" to null),
            timedOut = false,
            waitedMs = 12L,
        )
        val json = parse(text)
        assertFalse(json["allSettled"]!!.jsonPrimitive.boolean)
        assertFalse(json["timedOut"]!!.jsonPrimitive.boolean)
        assertEquals(12, json["waitedMs"]!!.jsonPrimitive.int)
        val rows = json["sessions"]!!.jsonArray.map { it.jsonObject }
        assertEquals("completed", rows[0]["status"]!!.jsonPrimitive.content)
        assertEquals("completed", rows[0]["taskStatus"]!!.jsonPrimitive.content)
        assertEquals("not_found", rows[1]["status"]!!.jsonPrimitive.content)
        val settled = formatSessionWait(
            requested = listOf("7"),
            states = mapOf("7" to state("7", running = false)),
            timedOut = false,
            waitedMs = 0L,
        )
        assertTrue(parse(settled)["allSettled"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun waitTimeoutClamped() {
        assertEquals(SESSION_WAIT_DEFAULT_TIMEOUT_MS, SessionToolLogic.clampWaitTimeout(null))
        assertEquals(0L, SessionToolLogic.clampWaitTimeout(0))
        assertEquals(SESSION_WAIT_MAX_TIMEOUT_MS, SessionToolLogic.clampWaitTimeout(999_999))
        assertEquals(0L, SessionToolLogic.clampWaitTimeout(-5))
        val wait = tool("WaitSessions")
        assertEquals(SESSION_WAIT_TIMEOUT_MS, wait.timeoutMs)
        assertEquals(10_000L, wait.resolveTimeoutMs(buildJsonObject { put("timeoutMs", 0) }))
        assertEquals(
            SESSION_WAIT_MAX_TIMEOUT_MS + 10_000L,
            wait.resolveTimeoutMs(buildJsonObject { put("timeoutMs", 999_999) }),
        )
    }

    @Test
    fun subagentsNeverSeeSessionControlTools() {
        val all = tools() + TodoReadTool(TodoState())
        val wildcard = CustomSubAgent(id = "a", name = "a", tools = listOf("*"))
        val filtered = SubagentCatalog.filterTools(all, wildcard)
        assertTrue(filtered.none { it.name in SESSION_CONTROL_TOOL_NAMES })
        assertTrue(filtered.any { it.name == "TodoRead" })
        val explicit = CustomSubAgent(
            id = "b",
            name = "b",
            tools = listOf("ListSessions", "SendToSession", "TodoRead"),
        )
        val explicitFiltered = SubagentCatalog.filterTools(all, explicit)
        assertEquals(listOf("TodoRead"), explicitFiltered.map { it.name })
        val empty = CustomSubAgent(id = "c", name = "c", tools = emptyList())
        assertTrue(SubagentCatalog.filterTools(all, empty).none { it.name in SESSION_CONTROL_TOOL_NAMES })
    }
}