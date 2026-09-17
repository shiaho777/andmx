package com.andmx.data

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AndmxDatabaseTest {
    @Test
    fun daoRoundTripPersistsConversationsMessagesProvidersAndGroups() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("andmx.db")
        val database = Room.databaseBuilder(context, AndmxDatabase::class.java, "andmx.db").build()
        try {
            val dao = database.dao()
            val conversationId = dao.insertConversation(
                ConversationEntity(
                    project = "/workspace/demo",
                    title = "轮次记录",
                    goalText = "发布版本",
                    goalTokenBudget = 9000,
                    pinned = true,
                    groupId = "g1",
                ),
            )
            assertTrue(conversationId > 0)
            val messageId = dao.insertMessage(
                MessageEntity(
                    conversationId = conversationId,
                    role = "tool",
                    content = "```kotlin\nval x = 1\n```",
                    toolName = "Edit",
                    toolArgs = """{"path":"a.kt"}""",
                    toolError = true,
                    approvalRisk = "WRITE",
                    imageUrlsJson = """["file:///a.png"]""",
                ),
            )
            assertTrue(messageId > 0)

            val stored = dao.messagesFor(conversationId).single()
            assertEquals("Edit", stored.toolName)
            assertEquals("""{"path":"a.kt"}""", stored.toolArgs)
            assertTrue(stored.toolError)
            assertEquals("WRITE", stored.approvalRisk)
            assertEquals("""["file:///a.png"]""", stored.imageUrlsJson)

            assertEquals(listOf(conversationId), dao.search("轮次").map { it.id })
            assertEquals(listOf(conversationId), dao.search("val x").map { it.id })
            assertEquals(1, dao.messageCount(conversationId))

            dao.setPinned(conversationId, false)
            dao.setArchived(conversationId, true)
            assertEquals(0, dao.observeConversations().first().size)
            assertEquals(conversationId, dao.observeArchived().first().single().id)
            dao.setArchived(conversationId, false)

            val provider = ProviderEntity(
                id = "openai",
                name = "OpenAI",
                kind = "OPENAI",
                baseUrl = "https://offline.invalid/v1",
                apiKey = "sk-test",
                apiKeyRequired = true,
                enabled = true,
                source = "builtin",
                requestMaxRetries = 2,
                streamMaxRetries = 3,
                streamIdleTimeoutMs = 30_000L,
                httpHeadersJson = "{}",
                modelsJson = "{}",
                claudeMappingJson = """{"small":"gpt-4o-mini"}""",
                isPrimary = true,
            )
            dao.upsertProvider(provider)
            dao.upsertTaskGroup(TaskGroupEntity(id = "g1", name = "重点", color = "green", sortOrder = 1))
            assertEquals(provider, dao.getProvider("openai"))
            assertEquals("openai", dao.observePrimaryProvider().first()?.id)

            dao.clearPrimary()
            dao.setPrimary("openai")
            assertTrue(dao.getProvider("openai")!!.isPrimary)

            assertEquals(1, dao.observeTaskGroups().first().size)
            assertEquals(conversationId, dao.observeConversations().first().single { it.groupId == "g1" }.id)

            dao.deleteMessagesFrom(conversationId, messageId)
            assertTrue(dao.messagesFor(conversationId).isEmpty())
            dao.deleteProvider("openai")
            dao.deleteTaskGroup("g1")
            dao.deleteConversation(conversationId)
            assertTrue(dao.observeAllConversations().first().isEmpty())
        } finally {
            database.close()
            context.deleteDatabase("andmx.db")
        }
    }

    @Test
    fun robolectricExecutesRealAndroidApisWhereUnitTestStubsReturnDefaults() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("andmx.db")
        val database = Room.databaseBuilder(context, AndmxDatabase::class.java, "andmx.db").build()
        try {
        assertNotNull(database.openHelper.writableDatabase)
            assertEquals("com.andmx://regression/entry", Uri.parse("com.andmx://regression/entry").toString())
            val prefs = context.getSharedPreferences("andmx_regression", Context.MODE_PRIVATE)
            prefs.edit().putString("surface", "real").commit()
            assertEquals("real", prefs.getString("surface", null))
            val dbFile = context.getDatabasePath("andmx.db")
            assertTrue(dbFile.exists() && dbFile.length() > 0)
            assertFalse(prefs.getString("surface", null)!!.isEmpty())
        } finally {
            database.close()
            context.deleteDatabase("andmx.db")
        }
    }
}
