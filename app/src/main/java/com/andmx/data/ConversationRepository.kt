package com.andmx.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Thin repository over the Room DAO for conversations and messages. */
class ConversationRepository(context: Context) {
    private val dao = AndmxDatabase.get(context).dao()

    fun observeConversations(): Flow<List<ConversationEntity>> = dao.observeConversations()

    suspend fun search(query: String): List<ConversationEntity> =
        if (query.isBlank()) emptyList() else dao.search(query.trim())

    suspend fun createConversation(project: String, title: String): Long =
        dao.insertConversation(ConversationEntity(project = project, title = title))

    suspend fun conversation(id: Long): ConversationEntity? = dao.getConversation(id)

    suspend fun addMessage(
        conversationId: Long,
        role: String,
        content: String,
        toolName: String? = null,
        toolArgs: String = "",
        toolError: Boolean = false,
        approvalRisk: String = "",
        approvalModeLabel: String = "",
        approvalRiskDescription: String = "",
        imageUrls: List<String> = emptyList(),
    ) {
        val safeId = if (conversationId > 0 && dao.getConversation(conversationId) != null) {
            conversationId
        } else {
            dao.insertConversation(
                ConversationEntity(
                    project = "/root",
                    title = content.take(24).ifBlank { "新任务" },
                ),
            )
        }
        dao.insertMessage(
            MessageEntity(
                conversationId = safeId,
                role = role,
                content = content,
                toolName = toolName,
                toolArgs = toolArgs,
                toolError = toolError,
                approvalRisk = approvalRisk,
                approvalModeLabel = approvalModeLabel,
                approvalRiskDescription = approvalRiskDescription,
                imageUrlsJson = Json.encodeToString(imageUrls),
            ),
        )
        dao.touchConversation(safeId, titleKeep(safeId), System.currentTimeMillis())
    }

    private suspend fun titleKeep(id: Long): String = dao.getConversation(id)?.title ?: "对话"

    suspend fun messages(conversationId: Long): List<MessageEntity> = dao.messagesFor(conversationId)

    suspend fun rename(conversationId: Long, title: String) =
        dao.touchConversation(conversationId, title, System.currentTimeMillis())

    suspend fun updateGoal(
        conversationId: Long,
        text: String,
        phase: String,
        startedAt: Long,
        updatedAt: Long,
        note: String,
        tokenBudget: Int = 0,
        tokensUsed: Int = 0,
    ) = dao.updateGoal(
        conversationId,
        text,
        phase,
        startedAt,
        updatedAt,
        note,
        tokenBudget,
        tokensUsed,
    )

    suspend fun updateWorkbenchState(
        conversationId: Long,
        workPaneTab: String,
        workPaneVisible: Boolean,
        terminalDockVisible: Boolean,
        terminalDockTall: Boolean,
        selectedFilePath: String,
        selectedDiffPath: String,
        browserUrl: String,
        fileCurrentGuestPath: String,
        fileViewingGuestPath: String,
    ) = dao.updateWorkbenchState(
        id = conversationId,
        workPaneTab = workPaneTab,
        workPaneVisible = workPaneVisible,
        terminalDockVisible = terminalDockVisible,
        terminalDockTall = terminalDockTall,
        selectedFilePath = selectedFilePath,
        selectedDiffPath = selectedDiffPath,
        browserUrl = browserUrl,
        fileCurrentGuestPath = fileCurrentGuestPath,
        fileViewingGuestPath = fileViewingGuestPath,
    )

    suspend fun delete(conversationId: Long) = dao.deleteConversation(conversationId)

    /**
     * Truncate a conversation: delete every message whose DB id >= [fromDbId]
     * (inclusive). Used by re-edit to roll the transcript back to a given point.
     */
    suspend fun truncateFrom(conversationId: Long, fromDbId: Long) =
        dao.deleteMessagesFrom(conversationId, fromDbId)

    /** Delete all messages in a conversation (keeps the conversation row itself). */
    suspend fun clearMessages(conversationId: Long) {
        dao.deleteMessagesFrom(conversationId, 0)
    }

    // ── v7: Session metadata ──

    suspend fun updateSessionMetadata(
        conversationId: Long,
        rolloutPath: String,
        sessionId: String,
        sandboxPolicy: String,
        model: String,
        reasoningEffort: String,
        memoryMode: String,
        firstUserMessage: String,
    ) = dao.updateSessionMetadata(
        conversationId, rolloutPath, sessionId, sandboxPolicy,
        model, reasoningEffort, memoryMode, firstUserMessage,
    )

    suspend fun setArchived(conversationId: Long, archived: Boolean) =
        dao.setArchived(conversationId, archived)

    suspend fun setPinned(conversationId: Long, pinned: Boolean) =
        dao.setPinned(conversationId, pinned)

    fun observeArchived(): Flow<List<ConversationEntity>> = dao.observeArchived()

    suspend fun conversationsByArchived(archived: Boolean): List<ConversationEntity> =
        dao.conversationsByArchived(archived)

    // ── v7: Thread goals ──

    suspend fun createGoal(
        conversationId: Long,
        goalId: String,
        objective: String,
        tokenBudget: Int? = null,
    ): Long = dao.insertGoal(
        ThreadGoalEntity(
            conversationId = conversationId,
            goalId = goalId,
            objective = objective,
            tokenBudget = tokenBudget,
        )
    )

    suspend fun goals(conversationId: Long): List<ThreadGoalEntity> = dao.goalsFor(conversationId)

    suspend fun updateGoalProgress(
        goalId: Long,
        status: String,
        tokensUsed: Int,
        timeUsedSeconds: Int,
    ) = dao.updateGoalProgress(goalId, status, tokensUsed, timeUsedSeconds, System.currentTimeMillis())

    suspend fun deleteGoal(goalId: Long) = dao.deleteGoal(goalId)

    // ── v7: Thread spawn edges ──

    suspend fun recordSpawnEdge(parentId: Long, childId: Long, status: String = "pending"): Long =
        dao.insertSpawnEdge(ThreadSpawnEdgeEntity(parentConversationId = parentId, childConversationId = childId, status = status))

    suspend fun childEdges(parentId: Long): List<ThreadSpawnEdgeEntity> = dao.childEdges(parentId)

    suspend fun updateSpawnEdgeStatus(edgeId: Long, status: String) = dao.updateSpawnEdgeStatus(edgeId, status)

    // ── v7: Logs ──

    suspend fun addLog(
        conversationId: Long,
        content: String,
        level: String = "info",
        processUuid: String? = null,
    ): Long {
        val now = System.currentTimeMillis()
        return dao.insertLog(
            LogEntity(
                conversationId = conversationId,
                ts = now / 1000,
                tsNanos = (now % 1000).toInt(),
                processUuid = processUuid,
                content = content,
                level = level,
            )
        )
    }

    suspend fun logs(conversationId: Long, limit: Int = 200): List<LogEntity> =
        dao.logsFor(conversationId, limit)

    suspend fun searchLogs(conversationId: Long, query: String): List<LogEntity> =
        if (query.isBlank()) emptyList() else dao.searchLogs(conversationId, query.trim())

    suspend fun deleteLogs(conversationId: Long) = dao.deleteLogs(conversationId)
}
