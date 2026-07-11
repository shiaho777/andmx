package com.andmx.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A conversation, scoped to a project working directory (guest absolute path,
 * e.g. /root/my-app). Mirrors Codex's project → conversation hierarchy where
 * a project is the user-chosen cwd (typically a git repo root).
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val project: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val goalText: String = "",
    val goalPhase: String = "",
    val goalStartedAt: Long = 0L,
    val goalUpdatedAt: Long = 0L,
    val goalNote: String = "",
    val goalTokenBudget: Int = 0,
    val goalTokensUsed: Int = 0,
    val workPaneTab: String = "TERMINAL",
    val workPaneVisible: Boolean = true,
    val terminalDockVisible: Boolean = false,
    val terminalDockTall: Boolean = false,
    val selectedFilePath: String = "",
    val selectedDiffPath: String = "",
    val browserUrl: String = "",
    val fileCurrentGuestPath: String = "/",
    val fileViewingGuestPath: String = "",

    // ── v7: Rollout + thread metadata (mirrors Codex threads table) ──
    /** Path to the rollout JSONL file for full session replay. */
    val rolloutPath: String = "",
    /** Sandbox policy at session creation: "read-only" | "workspace-write" | "danger-full-access". */
    val sandboxPolicy: String = "",
    /** Model used for this conversation. */
    val model: String = "",
    /** Reasoning effort setting. */
    val reasoningEffort: String = "",
    /** Memory mode: "enabled" | "disabled". */
    val memoryMode: String = "enabled",
    /** First user message (for preview without joining messages table). */
    val firstUserMessage: String = "",
    /** Whether the conversation is archived. */
    val archived: Boolean = false,
    /** Session ID from the rollout writer. */
    val sessionId: String = "",
    /** Whether the conversation is pinned to the top of the sidebar. */
    val pinned: Boolean = false,
    /** Custom task group id (empty = no custom group, grouped by project instead). */
    val groupId: String = "",
)

@Entity(tableName = "task_groups")
data class TaskGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Color key: gray/red/orange/yellow/green/blue/purple */
    val color: String = "blue",
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /** "user" | "assistant" | "tool" | "approval" */
    val role: String,
    val content: String,
    /** for tool messages: the tool name; null otherwise */
    val toolName: String? = null,
    /** Original JSON arguments for tool messages, used to restore source/file links. */
    val toolArgs: String = "",
    /** Whether a persisted tool message represents a failed tool call. */
    val toolError: Boolean = false,
    /** Snapshot for approval messages: ToolRisk enum name at the time of the prompt. */
    val approvalRisk: String = "",
    /** Snapshot for approval messages: user-facing approval mode label at the time of the prompt. */
    val approvalModeLabel: String = "",
    /** Snapshot for approval messages: user-facing risk explanation at the time of the prompt. */
    val approvalRiskDescription: String = "",
    val imageUrlsJson: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

// ── v7: Thread goals (mirrors Codex thread_goals table) ──

@Entity(
    tableName = "thread_goals",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class ThreadGoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val goalId: String,
    val objective: String,
    /** active | paused | blocked | usage_limited | budget_limited | complete */
    val status: String = "active",
    val tokenBudget: Int? = null,
    val tokensUsed: Int = 0,
    val timeUsedSeconds: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
)

// ── v7: Thread spawn edges (sub-agent relationship graph) ──

@Entity(
    tableName = "thread_spawn_edges",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentConversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["childConversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("parentConversationId"), Index("childConversationId")],
)
data class ThreadSpawnEdgeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val parentConversationId: Long,
    val childConversationId: Long,
    /** pending | running | completed | failed */
    val status: String = "pending",
    val createdAtMs: Long = System.currentTimeMillis(),
)

// ── v7: Logs table (mirrors Codex logs table with FTS) ──

@Entity(
    tableName = "logs",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId"), Index("ts")],
)
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val ts: Long,
    val tsNanos: Int = 0,
    val processUuid: String? = null,
    val estimatedBytes: Int = 0,
    val content: String,
    /** "info" | "warn" | "error" | "debug" | "trace" */
    val level: String = "info",
)

// ── v8: Model providers (mirrors Codex model_providers + ZCode provider map) ──

/**
 * A persisted model provider. Multiple rows coexist; the one with
 * [isPrimary] = true is the active provider. Complex fields (httpHeaders,
 * models) are stored as JSON columns.
 */
@Entity(tableName = "providers", indices = [Index("isPrimary")])
data class ProviderEntity(
    /** Stable id: "openai" for presets, a UUID for user-created providers. */
    @PrimaryKey val id: String,
    val name: String,
    /** ProviderKind.name: OPENAI | OPENAI_RESPONSES | ANTHROPIC */
    val kind: String,
    val baseUrl: String,
    val apiKey: String,
    val apiKeyRequired: Boolean,
    val enabled: Boolean,
    /** "builtin" | "custom" */
    val source: String,
    val requestMaxRetries: Int,
    val streamMaxRetries: Int,
    val streamIdleTimeoutMs: Long,
    /** JSON-serialized Map<String,String>. */
    val httpHeadersJson: String,
    /** JSON-serialized Map<String, ModelDefinition>. */
    val modelsJson: String,
    /** Whether this is the currently-selected provider. */
    val isPrimary: Boolean,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
)
