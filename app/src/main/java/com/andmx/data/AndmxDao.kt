package com.andmx.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AndmxDao {

    @Insert
    suspend fun insertConversation(c: ConversationEntity): Long

    @Update
    suspend fun updateConversation(c: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE archived = 0 ORDER BY updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAllConversations(): Flow<List<ConversationEntity>>

    @Query(
        "SELECT DISTINCT c.* FROM conversations c " +
            "LEFT JOIN messages m ON m.conversationId = c.id " +
            "WHERE c.title LIKE '%' || :q || '%' OR m.content LIKE '%' || :q || '%' " +
            "ORDER BY c.updatedAt DESC LIMIT 50",
    )
    suspend fun search(q: String): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: Long): ConversationEntity?

    @Query("UPDATE conversations SET title = :title, updatedAt = :ts WHERE id = :id")
    suspend fun touchConversation(id: Long, title: String, ts: Long)

    @Query(
        "UPDATE conversations SET goalText = :text, goalPhase = :phase, " +
            "goalStartedAt = :startedAt, goalUpdatedAt = :updatedAt, goalNote = :note, " +
            "updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateGoal(
        id: Long,
        text: String,
        phase: String,
        startedAt: Long,
        updatedAt: Long,
        note: String,
    )

    @Query(
        "UPDATE conversations SET workPaneTab = :workPaneTab, workPaneVisible = :workPaneVisible, " +
            "terminalDockVisible = :terminalDockVisible, terminalDockTall = :terminalDockTall, " +
            "selectedFilePath = :selectedFilePath, selectedDiffPath = :selectedDiffPath, " +
            "browserUrl = :browserUrl, fileCurrentGuestPath = :fileCurrentGuestPath, " +
            "fileViewingGuestPath = :fileViewingGuestPath WHERE id = :id",
    )
    suspend fun updateWorkbenchState(
        id: Long,
        workPaneTab: String,
        workPaneVisible: Boolean,
        terminalDockVisible: Boolean,
        terminalDockTall: Boolean,
        selectedFilePath: String,
        selectedDiffPath: String,
        browserUrl: String,
        fileCurrentGuestPath: String,
        fileViewingGuestPath: String,
    )

    @Insert
    suspend fun insertMessage(m: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun messagesFor(conversationId: Long): List<MessageEntity>

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    // ── v7: Rollout / session metadata ──

    @Query(
        "UPDATE conversations SET rolloutPath = :path, sessionId = :sessionId, " +
            "sandboxPolicy = :sandboxPolicy, model = :model, " +
            "reasoningEffort = :reasoningEffort, memoryMode = :memoryMode, " +
            "firstUserMessage = :firstUserMessage WHERE id = :id",
    )
    suspend fun updateSessionMetadata(
        id: Long,
        path: String,
        sessionId: String,
        sandboxPolicy: String,
        model: String,
        reasoningEffort: String,
        memoryMode: String,
        firstUserMessage: String,
    )

    @Query("UPDATE conversations SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    @Query("SELECT * FROM conversations WHERE archived = :archived ORDER BY updatedAt DESC")
    suspend fun conversationsByArchived(archived: Boolean): List<ConversationEntity>

    // ── v7: Thread goals ──

    @Insert
    suspend fun insertGoal(goal: ThreadGoalEntity): Long

    @Update
    suspend fun updateGoal(goal: ThreadGoalEntity)

    @Query("SELECT * FROM thread_goals WHERE conversationId = :conversationId ORDER BY createdAtMs ASC")
    suspend fun goalsFor(conversationId: Long): List<ThreadGoalEntity>

    @Query("UPDATE thread_goals SET status = :status, tokensUsed = :tokensUsed, timeUsedSeconds = :timeUsed, updatedAtMs = :updatedAtMs WHERE id = :id")
    suspend fun updateGoalProgress(id: Long, status: String, tokensUsed: Int, timeUsed: Int, updatedAtMs: Long)

    @Query("DELETE FROM thread_goals WHERE id = :id")
    suspend fun deleteGoal(id: Long)

    // ── v7: Thread spawn edges ──

    @Insert
    suspend fun insertSpawnEdge(edge: ThreadSpawnEdgeEntity): Long

    @Query("SELECT * FROM thread_spawn_edges WHERE parentConversationId = :parentId ORDER BY createdAtMs ASC")
    suspend fun childEdges(parentId: Long): List<ThreadSpawnEdgeEntity>

    @Query("UPDATE thread_spawn_edges SET status = :status WHERE id = :id")
    suspend fun updateSpawnEdgeStatus(id: Long, status: String)

    // ── v7: Logs ──

    @Insert
    suspend fun insertLog(log: LogEntity): Long

    @Query("SELECT * FROM logs WHERE conversationId = :conversationId ORDER BY ts DESC, tsNanos DESC, id DESC LIMIT :limit")
    suspend fun logsFor(conversationId: Long, limit: Int = 200): List<LogEntity>

    @Query(
        "SELECT * FROM logs WHERE conversationId = :conversationId AND content LIKE '%' || :q || '%' " +
            "ORDER BY ts DESC, tsNanos DESC, id DESC LIMIT 100",
    )
    suspend fun searchLogs(conversationId: Long, q: String): List<LogEntity>

    @Query("DELETE FROM logs WHERE conversationId = :conversationId")
    suspend fun deleteLogs(conversationId: Long)

    // ── v8: Providers ──

    @Query("SELECT * FROM providers ORDER BY isPrimary DESC, name ASC")
    fun observeProviders(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers WHERE isPrimary = 1 LIMIT 1")
    fun observePrimaryProvider(): Flow<ProviderEntity?>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun getProvider(id: String): ProviderEntity?

    @Query("SELECT * FROM providers")
    suspend fun allProviders(): List<ProviderEntity>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertProvider(p: ProviderEntity)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun deleteProvider(id: String)

    @Query("UPDATE providers SET isPrimary = 0")
    suspend fun clearPrimary()

    @Query("UPDATE providers SET isPrimary = 1 WHERE id = :id")
    suspend fun setPrimary(id: String)
}
