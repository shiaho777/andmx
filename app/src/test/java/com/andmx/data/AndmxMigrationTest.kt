package com.andmx.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AndmxMigrationTest {
    @Test
    fun productionMigrationRegistrationPreservesVersionOneRowsThroughVersionThirteen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("andmx.db")
        context.openOrCreateDatabase("andmx.db", Context.MODE_PRIVATE, null).use { legacy ->
            legacy.execSQL(
                "CREATE TABLE conversations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "project TEXT NOT NULL, title TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
            )
            legacy.execSQL(
                "CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "conversationId INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, " +
                    "toolName TEXT, createdAt INTEGER NOT NULL, " +
                    "FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)"
            )
            legacy.execSQL("CREATE INDEX index_messages_conversationId ON messages(conversationId)")
            legacy.execSQL("INSERT INTO conversations VALUES (41, '/offline/project', '保留的任务', 100, 200)")
            legacy.execSQL("INSERT INTO messages VALUES (51, 41, 'tool', 'preserved output', 'Read', 150)")
            legacy.version = 1
        }

        val database = AndmxDatabase.get(context)
        try {
            val sqlite = database.openHelper.writableDatabase
            assertEquals(13, sqlite.version)
            val dao = database.dao()
            val conversation = requireNotNull(dao.getConversation(41))
            assertEquals("/offline/project", conversation.project)
            assertEquals("保留的任务", conversation.title)
            assertEquals(100L, conversation.createdAt)
            assertEquals(200L, conversation.updatedAt)
            assertEquals("", conversation.goalText)
            assertEquals(0, conversation.goalTokenBudget)
            assertEquals(0, conversation.goalTokensUsed)
            assertEquals("TERMINAL", conversation.workPaneTab)
            assertTrue(conversation.workPaneVisible)
            assertFalse(conversation.terminalDockVisible)
            assertEquals("/", conversation.fileCurrentGuestPath)
            assertEquals("enabled", conversation.memoryMode)
            assertFalse(conversation.archived)
            assertFalse(conversation.pinned)
            assertEquals("", conversation.groupId)

            val message = dao.messagesFor(41).single()
            assertEquals(51L, message.id)
            assertEquals("preserved output", message.content)
            assertEquals("Read", message.toolName)
            assertEquals(150L, message.createdAt)
            assertEquals("", message.toolArgs)
            assertFalse(message.toolError)
            assertEquals("", message.approvalRisk)
            assertEquals("", message.imageUrlsJson)
            assertTrue(dao.allProviders().isEmpty())
            assertTrue(dao.allTaskGroups().isEmpty())
            assertTrue(dao.goalsFor(41).isEmpty())
            assertTrue(dao.childEdges(41).isEmpty())
            assertTrue(dao.logsFor(41).isEmpty())

            val newId = dao.insertConversation(ConversationEntity(project = "/offline/new", title = "new"))
            assertTrue(newId > 41)
            dao.deleteConversation(41)
            assertTrue(dao.messagesFor(41).isEmpty())
            sqlite.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            database.close()
            context.deleteDatabase("andmx.db")
        }
    }
}
