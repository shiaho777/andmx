package com.andmx.agent.multi

import com.andmx.agent.TurnContext
import com.andmx.llm.ApiMessage
import com.andmx.llm.ChatRequest
import com.andmx.llm.LlmApi
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.settings.ProviderSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SubAgentOrchestratorLockTest {

    @Test
    fun queueCloseAndResumeStayConsistentWhileSnapshotsAreRead() {
        val llm = GateLlm()
        val orch = orchestrator(llm)
        val errors = CopyOnWriteArrayList<Throwable>()
        val stop = AtomicBoolean(false)
        val readers = startReaders(orch, stop, errors)
        try {
            assertFalse(orch.queueAgentMessage("missing", "x"))
            assertFalse(orch.close("missing"))
            assertFalse(orch.resumeAsync("missing", "x"))
            val spawned = AtomicBoolean(false)
            val spawnError = arrayOfNulls<Throwable>(1)
            val spawner = Thread {
                try {
                    runBlocking {
                        val result = orch.spawn(
                            SubAgentOrchestrator.SpawnSpec(task = "hold", agentId = "hold-1", agentName = "worker"),
                        )
                        spawned.set(result.contains("steered"))
                    }
                } catch (t: Throwable) {
                    spawnError[0] = t
                }
            }
            spawner.start()
            assertTrue(llm.started.await(5, TimeUnit.SECONDS))
            assertEquals(SubAgentOrchestrator.AgentState.RUNNING, orch.getState("hold-1"))
            assertTrue(orch.queueAgentMessage("hold-1", "steer-please"))
            llm.releaseFirst.set(true)
            spawner.join(10_000)
            assertFalse(spawner.isAlive)
            assertNull(spawnError[0]?.toString(), spawnError[0])
            assertTrue(spawned.get())
            val done = orch.listAgentSnapshots().single()
            assertEquals("hold-1", done.id)
            assertEquals("hold", done.task)
            assertEquals(SubAgentOrchestrator.AgentState.COMPLETED, done.state)
            assertEquals("worker", done.agentType)
            assertFalse(done.background)
            assertTrue(done.result.contains("steered"))

            llm.resumePhase.set(true)
            assertTrue(orch.resumeAsync("hold-1", "again"))
            assertTrue(llm.resumeEntered.await(5, TimeUnit.SECONDS))
            assertEquals(SubAgentOrchestrator.AgentState.RUNNING, orch.getState("hold-1"))
            assertTrue(orch.listAgentSnapshots().single().background)
            llm.releaseResume.set(true)
            assertTrue(awaitState(orch, "hold-1", SubAgentOrchestrator.AgentState.COMPLETED))
            assertEquals("resumed", orch.getResult("hold-1"))
            Thread.sleep(50)
            assertTrue(orch.close("hold-1"))
            assertEquals(SubAgentOrchestrator.AgentState.CLOSED, orch.getState("hold-1"))
            assertFalse(orch.resumeAsync("hold-1", "nope"))
            assertEquals(
                SubAgentOrchestrator.AgentState.CLOSED,
                orch.listAgentSnapshots().single().state,
            )
        } finally {
            stop.set(true)
            readers.forEach { it.join(5_000) }
            orch.shutdown()
        }
        assertTrue(errors.toList().joinToString { it.stackTraceToString() }, errors.isEmpty())
    }

    @Test
    fun listingSnapshotsWhileAgentsStartAndFinishDoesNotThrow() {
        val orch = orchestrator(ImmediateLlm())
        val errors = CopyOnWriteArrayList<Throwable>()
        val stop = AtomicBoolean(false)
        val readers = startReaders(orch, stop, errors)
        val spawnError = arrayOfNulls<Throwable>(1)
        val spawner = Thread {
            try {
                runBlocking {
                    repeat(24) { i ->
                        orch.spawn(
                            SubAgentOrchestrator.SpawnSpec(
                                task = "task-$i",
                                agentId = "a$i",
                                agentName = "worker",
                            ),
                        )
                    }
                }
            } catch (t: Throwable) {
                spawnError[0] = t
            }
        }
        try {
            spawner.start()
            spawner.join(30_000)
            assertFalse(spawner.isAlive)
            assertNull(spawnError[0]?.stackTraceToString(), spawnError[0])
            repeat(24) { i ->
                if (i % 2 == 0) assertTrue(orch.close("a$i"))
            }
            val snaps = orch.listAgentSnapshots()
            assertEquals((0 until 24).map { "a$it" }.toSet(), snaps.map { it.id }.toSet())
            snaps.forEach { snap ->
                val index = snap.id.removePrefix("a").toInt()
                val expected = if (index % 2 == 0) {
                    SubAgentOrchestrator.AgentState.CLOSED
                } else {
                    SubAgentOrchestrator.AgentState.COMPLETED
                }
                assertEquals(expected, snap.state)
                assertEquals(expected, orch.getState(snap.id))
            }
        } finally {
            stop.set(true)
            readers.forEach { it.join(5_000) }
            orch.shutdown()
        }
        assertTrue(errors.toList().joinToString { it.stackTraceToString() }, errors.isEmpty())
    }

    private fun startReaders(
        orch: SubAgentOrchestrator,
        stop: AtomicBoolean,
        errors: CopyOnWriteArrayList<Throwable>,
    ): List<Thread> {
        val queued = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        return List(4) { index ->
            Thread({
                try {
                    while (!stop.get()) {
                        val snaps = orch.listAgentSnapshots()
                        orch.listAgents()
                        snaps.forEach { snap ->
                            orch.getState(snap.id)
                            orch.getResult(snap.id)
                            orch.outputFileFor(snap.id)
                            if (snap.state == SubAgentOrchestrator.AgentState.RUNNING && queued.add(snap.id)) {
                                orch.queueAgentMessage(snap.id, "ping-$index")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                    stop.set(true)
                }
            }, "snap-$index")
        }.onEach { it.start() }
    }

    private fun awaitState(
        orch: SubAgentOrchestrator,
        agentId: String,
        state: SubAgentOrchestrator.AgentState,
    ): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (orch.getState(agentId) == state) return true
            Thread.sleep(5)
        }
        return orch.getState(agentId) == state
    }

    private fun orchestrator(llm: LlmApi): SubAgentOrchestrator {
        val provider = ProviderDefinition(
            id = "p",
            name = "p",
            baseUrl = "https://example.com",
            apiKey = "k",
            models = emptyMap(),
        )
        return SubAgentOrchestrator(
            toolsFactory = { emptyList() },
            settings = ProviderSettings(),
            client = llm,
            turnProvider = { TurnContext(provider, "m") },
            maxConcurrentThreads = 4,
        )
    }

    private class ImmediateLlm : LlmApi {
        override suspend fun chat(request: ChatRequest): Result<ApiMessage> {
            delay(1)
            return Result.success(ApiMessage(role = "assistant", content = "ok"))
        }
    }

    private class GateLlm : LlmApi {
        val started = CountDownLatch(1)
        val resumeEntered = CountDownLatch(1)
        val releaseFirst = AtomicBoolean(false)
        val releaseResume = AtomicBoolean(false)
        val resumePhase = AtomicBoolean(false)
        private val calls = AtomicInteger(0)

        override suspend fun chat(request: ChatRequest): Result<ApiMessage> {
            if (resumePhase.get()) {
                resumeEntered.countDown()
                while (!releaseResume.get()) delay(5)
                return Result.success(ApiMessage(role = "assistant", content = "resumed"))
            }
            val call = calls.incrementAndGet()
            if (call == 1) {
                started.countDown()
                while (!releaseFirst.get()) delay(5)
                return Result.success(ApiMessage(role = "assistant", content = "done"))
            }
            val steered = request.messages.any { it.content?.contains("steer-please") == true }
            return Result.success(ApiMessage(role = "assistant", content = if (steered) "steered" else "done"))
        }
    }
}
