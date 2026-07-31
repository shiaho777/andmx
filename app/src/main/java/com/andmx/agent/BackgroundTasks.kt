package com.andmx.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object BackgroundTasks {
    enum class Kind { BASH, AGENT }

    data class Entry(
        val id: String,
        val kind: Kind,
        val label: String,
        val job: Job,
        val startedAt: Long = System.currentTimeMillis(),
        @Volatile var output: String = "",
        @Volatile var done: Boolean = false,
        @Volatile var exitCode: Int? = null,
        @Volatile var error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = ConcurrentHashMap<String, Entry>()

    fun newBashId(): String = "bash_${UUID.randomUUID()}"
    fun newAgentId(): String = "agent_${UUID.randomUUID()}"

    fun get(id: String): Entry? = entries[id]

    fun list(): List<Entry> = entries.values.sortedByDescending { it.startedAt }

    fun startBash(
        command: String,
        taskId: String = newBashId(),
        block: suspend () -> ToolResult,
    ): String {
        val job = scope.launch {
            try {
                val result = block()
                entries[taskId]?.let {
                    it.output = result.output
                    it.exitCode = if (result.isError) 1 else 0
                    it.error = if (result.isError) result.output else null
                    it.done = true
                }
            } catch (c: CancellationException) {
                entries[taskId]?.let {
                    it.done = true
                    it.error = "stopped"
                    it.exitCode = -1
                }
                throw c
            } catch (t: Throwable) {
                entries[taskId]?.let {
                    it.done = true
                    it.error = t.message
                    it.exitCode = -1
                    it.output = "failed: ${t.message}"
                }
            }
        }
        entries[taskId] = Entry(
            id = taskId,
            kind = Kind.BASH,
            label = command.take(200),
            job = job,
        )
        return taskId
    }

    fun registerAgentJob(agentId: String, label: String, job: Job) {
        entries[agentId] = Entry(
            id = agentId,
            kind = Kind.AGENT,
            label = label.take(200),
            job = job,
        )
        job.invokeOnCompletion { cause ->
            entries[agentId]?.let {
                it.done = true
                if (cause is CancellationException) {
                    it.error = "stopped"
                    it.exitCode = -1
                } else if (cause != null) {
                    it.error = cause.message
                    it.exitCode = -1
                } else {
                    it.exitCode = 0
                }
            }
        }
    }

    fun stop(taskId: String): Boolean {
        val entry = entries[taskId] ?: return false
        if (!entry.done) {
            entry.job.cancel()
            entry.done = true
            entry.error = "stopped"
            entry.exitCode = -1
        }
        return true
    }

    fun markAgentDone(agentId: String, result: String, failed: Boolean = false) {
        entries[agentId]?.let {
            it.output = result
            it.done = true
            it.exitCode = if (failed) 1 else 0
            if (failed) it.error = result
        }
    }
}
