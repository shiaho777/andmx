package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSchedulerTest {
    private val scheduler = ToolScheduler()

    @Test
    fun destructiveCallIsSerialEvenWhenMarkedConcurrent() {
        val schedule = scheduler.schedule(
            listOf(
                dep("a", "Read", concurrentSafe = true),
                dep("b", "Bash", concurrentSafe = true, destructive = true),
                dep("c", "Grep", concurrentSafe = true),
            ),
        )
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c")), schedule.parallelGroups)
    }

    @Test
    fun concurrentSafeFalseIsABarrierAndSafeCallsShareAWave() {
        val schedule = scheduler.schedule(
            listOf(
                dep("a", "Read", concurrentSafe = true),
                dep("b", "Edit", concurrentSafe = false),
                dep("c", "Grep", concurrentSafe = true),
                dep("d", "Glob", concurrentSafe = true),
            ),
        )
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c", "d")), schedule.parallelGroups)
        assertEquals(listOf("a", "b", "c", "d"), schedule.executionOrder)
    }

    @Test
    fun parallelWaveCapsAtMaxConcurrency() {
        val calls = (1..11).map { dep("c$it", "Read", concurrentSafe = true) }
        val groups = ToolScheduler(maxConcurrency = 10).schedule(calls).parallelGroups
        assertEquals(listOf(10, 1), groups.map { it.size })
    }

    @Test
    fun unknownReadOnlyNameRunsInParallel() {
        val schedule = scheduler.schedule(
            listOf(
                ToolDependency(toolCallId = "a", toolName = "Read"),
                ToolDependency(toolCallId = "c", toolName = "Grep"),
                ToolDependency(toolCallId = "b", toolName = "Bash"),
            ),
        )
        assertEquals(listOf(listOf("a", "c"), listOf("b")), schedule.parallelGroups)
    }

    @Test
    fun missingNameAndMetadataRunsInParallel() {
        val schedule = scheduler.schedule(
            listOf(
                ToolDependency(toolCallId = "a"),
                ToolDependency(toolCallId = "b"),
            ),
        )
        assertEquals(listOf(listOf("a", "b")), schedule.parallelGroups)
    }

    @Test
    fun explicitConcurrentSafeFalseBeatsReadOnlyName() {
        val schedule = scheduler.schedule(
            listOf(
                dep("a", "Read", concurrentSafe = false),
                dep("b", "Grep", concurrentSafe = true),
            ),
        )
        assertEquals(listOf(listOf("a"), listOf("b")), schedule.parallelGroups)
    }

    @Test
    fun unsetConcurrentSafeFallsThroughToReadOnlyThenSideEffectScope() {
        val readOnly = scheduler.schedule(
            listOf(ToolDependency(toolCallId = "a", toolName = "Other", readOnly = true)),
        )
        val scoped = scheduler.schedule(
            listOf(ToolDependency(toolCallId = "a", toolName = "Other", sideEffectScope = "none")),
        )
        val mutating = scheduler.schedule(
            listOf(ToolDependency(toolCallId = "a", toolName = "Other", sideEffectScope = "workspace")),
        )
        assertEquals(listOf(listOf("a")), readOnly.parallelGroups)
        assertEquals(listOf(listOf("a")), scoped.parallelGroups)
        assertEquals(listOf(listOf("a")), mutating.parallelGroups)
        val mixed = scheduler.schedule(
            listOf(
                ToolDependency(toolCallId = "a", toolName = "Other", readOnly = true),
                ToolDependency(toolCallId = "c", toolName = "Other", sideEffectScope = "none"),
                ToolDependency(toolCallId = "b", toolName = "Other", sideEffectScope = "workspace"),
            ),
        )
        assertEquals(listOf(listOf("a", "c"), listOf("b")), mixed.parallelGroups)
    }

    @Test
    fun dependenciesAdvanceTheParallelLevel() {
        val schedule = scheduler.schedule(
            listOf(
                dep("b", "Read", concurrentSafe = true, dependsOn = listOf("a")),
                dep("a", "Read", concurrentSafe = true),
            ),
        )
        assertEquals(listOf(listOf("a"), listOf("b")), schedule.parallelGroups)
    }

    @Test
    fun cycleThrows() {
        val error = runCatching {
            scheduler.schedule(
                listOf(
                    dep("a", "Read", concurrentSafe = true, dependsOn = listOf("b")),
                    dep("b", "Read", concurrentSafe = true, dependsOn = listOf("a")),
                ),
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("Circular dependency detected in tool scheduling", error?.message)
    }

    private fun dep(
        id: String,
        name: String,
        concurrentSafe: Boolean? = null,
        destructive: Boolean? = null,
        dependsOn: List<String> = emptyList(),
    ) = ToolDependency(
        toolCallId = id,
        toolName = name,
        dependsOn = dependsOn,
        concurrentSafe = concurrentSafe,
        destructive = destructive,
    )
}
