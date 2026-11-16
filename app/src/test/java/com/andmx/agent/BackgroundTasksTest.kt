package com.andmx.agent

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BackgroundTasksTest {

    private fun record(
        id: String,
        status: String,
        finishedAt: Long? = null,
        exitCode: Int? = null,
    ) = BackgroundTasks.TaskRecord(
        id = id,
        conversationId = 7L,
        command = "echo hi",
        cwd = "/root",
        status = status,
        outputPath = "/root/.andmx/tasks/$id.output",
        startedAt = 1_000L,
        finishedAt = finishedAt,
        exitCode = exitCode,
    )

    @Test
    fun interruptRunningOnlyTouchesRunningRecords() {
        val records = listOf(
            record("a", "running"),
            record("b", "completed", finishedAt = 2_000L, exitCode = 0),
            record("c", "failed", finishedAt = 2_000L),
            record("d", "killed", finishedAt = 2_000L),
            record("e", "interrupted", finishedAt = 2_000L),
        )

        val restored = BackgroundTasks.interruptRunning(records, now = 9_000L)

        assertEquals("interrupted", restored[0].status)
        assertEquals(9_000L, restored[0].finishedAt)
        assertEquals(records[1], restored[1])
        assertEquals(records[2], restored[2])
        assertEquals(records[3], restored[3])
        assertEquals(records[4], restored[4])
    }

    @Test
    fun stateFromStatusMapsKnownWordsAndDefaultsToInterrupted() {
        assertEquals(BackgroundTasks.TaskState.RUNNING, BackgroundTasks.stateFromStatus("running"))
        assertEquals(BackgroundTasks.TaskState.COMPLETED, BackgroundTasks.stateFromStatus("completed"))
        assertEquals(BackgroundTasks.TaskState.FAILED, BackgroundTasks.stateFromStatus("failed"))
        assertEquals(BackgroundTasks.TaskState.KILLED, BackgroundTasks.stateFromStatus("killed"))
        assertEquals(BackgroundTasks.TaskState.INTERRUPTED, BackgroundTasks.stateFromStatus("interrupted"))
        assertEquals(BackgroundTasks.TaskState.INTERRUPTED, BackgroundTasks.stateFromStatus("garbage"))
    }

    @Test
    fun statusWordCoversInterruptedState() {
        val task = BackgroundTasks.BgTask(
            id = "x",
            command = "sleep 1",
            cwd = "/root",
            outputPath = "/root/.andmx/tasks/x.output",
            state = BackgroundTasks.TaskState.INTERRUPTED,
        )
        assertEquals("interrupted", task.statusWord)
        assertEquals("interrupted", task.toRecord().status)
    }

    @Test
    fun restoreMarksStoredRunningTasksInterruptedAndKeepsOutputPath() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stored = mutableListOf(
            record("bash-live", "running"),
            record("bash-done", "completed", finishedAt = 2_000L, exitCode = 0),
        )
        val written = mutableListOf<BackgroundTasks.TaskRecord>()
        val tasks = BackgroundTasks(
            context,
            conversationId = 7L,
            persistTask = { written += it },
            loadTasks = { convId -> stored.filter { it.conversationId == convId } },
        )

        val restored = tasks.restore()

        assertEquals(2, restored.size)
        assertEquals("interrupted", restored.first { it.id == "bash-live" }.status)
        assertNotNull(restored.first { it.id == "bash-live" }.finishedAt)
        assertEquals("completed", restored.first { it.id == "bash-done" }.status)
        assertEquals(listOf("bash-live"), written.map { it.id })

        val live = tasks.get("bash-live")
        assertNotNull(live)
        assertEquals(BackgroundTasks.TaskState.INTERRUPTED, live!!.state)
        assertEquals("/root/.andmx/tasks/bash-live.output", live.outputPath)
        assertFalse(tasks.stop("bash-live"))
        val snapshots = tasks.listSnapshots()
        assertEquals(2, snapshots.size)
        assertTrue(snapshots.any { it.state == BackgroundTasks.TaskState.INTERRUPTED })
    }

    @Test
    fun restoreWithoutLoaderReturnsEmpty() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tasks = BackgroundTasks(context)
        assertTrue(tasks.restore().isEmpty())
    }

    @Test
    fun secondRestoreDoesNotDuplicateRecords() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stored = mutableListOf(record("bash-once", "running"))
        val tasks = BackgroundTasks(
            context,
            conversationId = 7L,
            persistTask = { r ->
                val idx = stored.indexOfFirst { it.id == r.id }
                if (idx >= 0) stored[idx] = r else stored += r
            },
            loadTasks = { convId -> stored.filter { it.conversationId == convId } },
        )

        val first = tasks.restore()
        val second = tasks.restore()

        assertEquals(1, first.size)
        assertTrue(second.isEmpty())
        assertEquals(1, tasks.listSnapshots().size)
        assertEquals("interrupted", stored.single().status)
    }

    @Test
    fun restoredRecordKeepsExitCodeAndNullForInterrupted() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stored = listOf(
            record("bash-crash", "running", exitCode = null),
            record("bash-ok", "completed", finishedAt = 2_000L, exitCode = 3),
        )
        val tasks = BackgroundTasks(
            context,
            conversationId = 7L,
            loadTasks = { convId -> stored.filter { it.conversationId == convId } },
        )

        tasks.restore()

        assertNull(tasks.get("bash-crash")!!.exitCode)
        assertEquals(3, tasks.get("bash-ok")!!.exitCode)
    }
}