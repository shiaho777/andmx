package com.andmx.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.andmx.agent.ToolSearchRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PersistenceProtocolTest {

    @Test
    fun discoveredToolsRoundTripThroughRealRoom() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("persistence-protocol.db")
        val database = Room.databaseBuilder(context, AndmxDatabase::class.java, "persistence-protocol.db").build()
        try {
            val dao = database.dao()
            val registry = ToolSearchRegistry(
                conversationId = 11L,
                persistDiscovered = { convId, name ->
                    dao.upsertDiscoveredTool(DiscoveredToolEntity(conversationId = convId, toolName = name))
                },
                loadDiscovered = { convId -> dao.discoveredTools(convId).map { it.toolName } },
            )

            registry.discover(listOf("TodoWrite", "CronCreate"))
            registry.discover(listOf("TodoWrite"))
            assertEquals(listOf("CronCreate", "TodoWrite"), dao.discoveredTools(11L).map { it.toolName }.sorted())
            assertTrue(dao.discoveredTools(12L).isEmpty())

            val reopened = ToolSearchRegistry(
                conversationId = 11L,
                loadDiscovered = { convId -> dao.discoveredTools(convId).map { it.toolName } },
            )
            reopened.restore()
            assertTrue(reopened.isDiscovered("TodoWrite"))
            assertTrue(reopened.isDiscovered("CronCreate"))

            dao.deleteDiscoveredTool(11L, "CronCreate")
            assertEquals(listOf("TodoWrite"), dao.discoveredTools(11L).map { it.toolName })
            dao.deleteDiscoveredTools(11L)
            assertTrue(dao.discoveredTools(11L).isEmpty())
        } finally {
            database.close()
            context.deleteDatabase("persistence-protocol.db")
        }
    }

    @Test
    fun backgroundTaskRowsRoundTripAndInterruptOnReload() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("persistence-protocol.db")
        val database = Room.databaseBuilder(context, AndmxDatabase::class.java, "persistence-protocol.db").build()
        try {
            val dao = database.dao()
            dao.upsertBackgroundTask(
                BackgroundTaskEntity(
                    id = "bash-1",
                    conversationId = 21L,
                    command = "sleep 100",
                    cwd = "/root",
                    status = "running",
                    outputPath = "/root/.andmx/tasks/bash-1.output",
                    startedAt = 1_000L,
                ),
            )
            dao.upsertBackgroundTask(
                BackgroundTaskEntity(
                    id = "bash-1",
                    conversationId = 21L,
                    command = "sleep 100",
                    cwd = "/root",
                    status = "completed",
                    outputPath = "/root/.andmx/tasks/bash-1.output",
                    startedAt = 1_000L,
                    finishedAt = 5_000L,
                    exitCode = 0,
                ),
            )
            val stored = dao.backgroundTasksFor(21L).single()
            assertEquals("completed", stored.status)
            assertEquals(5_000L, stored.finishedAt)
            assertEquals(0, stored.exitCode)

            dao.upsertBackgroundTask(
                BackgroundTaskEntity(
                    id = "bash-2",
                    conversationId = 21L,
                    command = "long job",
                    cwd = "/root",
                    status = "running",
                    outputPath = "/root/.andmx/tasks/bash-2.output",
                    startedAt = 2_000L,
                ),
            )
            assertEquals(listOf("bash-2"), dao.backgroundTasksByStatus("running").map { it.id })
            dao.deleteBackgroundTask("bash-2")
            assertTrue(dao.backgroundTasksByStatus("running").isEmpty())
            assertEquals(1, dao.backgroundTasksFor(21L).size)
        } finally {
            database.close()
            context.deleteDatabase("persistence-protocol.db")
        }
    }
}