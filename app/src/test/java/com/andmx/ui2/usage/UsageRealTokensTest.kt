package com.andmx.ui2.usage

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andmx.data.AndmxDatabase
import com.andmx.data.ConversationEntity
import com.andmx.data.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.nio.file.Files
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class UsageRealTokensTest {

    private lateinit var context: Context

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        AndmxDatabase.resetForTests()
        context.deleteDatabase("andmx.db")
        Unit
    }

    @After
    fun tearDown() {
        AndmxDatabase.resetForTests()
    }

    private fun seedConversation(
        title: String,
        model: String,
        rollout: File?,
        at: Long = System.currentTimeMillis(),
    ): Long = runBlocking {
        val dao = AndmxDatabase.get(context).dao()
        val id = dao.insertConversation(
            ConversationEntity(
                project = "/workspace/demo",
                title = title,
                model = model,
                rolloutPath = rollout?.absolutePath.orEmpty(),
                createdAt = at,
                updatedAt = at,
            ),
        )
        dao.insertMessage(
            MessageEntity(
                conversationId = id,
                role = "user",
                content = "请检查部署",
                createdAt = at,
            ),
        )
        dao.insertMessage(
            MessageEntity(
                conversationId = id,
                role = "assistant",
                content = "好的，这是检查结果。".repeat(20),
                createdAt = at + 1_000,
            ),
        )
        id
    }

    @Test
    fun realRolloutUsageWinsOverMessageLengthEstimate() = runBlocking {
        val now = System.currentTimeMillis()
        val rollout = Files.createTempFile("usage", ".jsonl").toFile().apply { deleteOnExit() }
        rollout.writeText(
            """
            {"timestamp":"${Instant.ofEpochMilli(now - 60_000)}","type":"turn_context","payload":{"turnId":"t1","model":"real-model"}}
            {"timestamp":"${Instant.ofEpochMilli(now - 59_000)}","type":"event_msg","payload":{"type":"token_usage","inputTokens":700,"outputTokens":300,"totalTokens":1000}}
            """.trimIndent(),
        )
        seedConversation("有 rollout", "conversation-model", rollout)

        val stats = UsageCalculator.compute(context, UsageRange.D7)

        assertEquals(1000L, stats.totalTokens)
        assertEquals(1000L, stats.lifetimeTotalTokens)
        assertEquals("real-model", stats.favoriteModel)
        assertEquals(2, stats.messages)
        assertEquals(1, stats.sessions)
        assertEquals(1000L, stats.peakDayTokens)
        assertTrue(stats.daily.any { it.tokens == 1000L && it.turns == 1 })
    }

    @Test
    fun conversationsWithoutRolloutStillUseTheEstimate() = runBlocking {
        val now = System.currentTimeMillis()
        seedConversation("没有 rollout", "legacy-model", rollout = null, at = now)

        val stats = UsageCalculator.compute(context, UsageRange.D7)

        assertTrue(stats.totalTokens > 0L)
        assertEquals("legacy-model", stats.favoriteModel)
        assertEquals(stats.totalTokens, stats.lifetimeTotalTokens)
    }
}
