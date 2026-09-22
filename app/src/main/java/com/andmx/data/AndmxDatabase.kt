package com.andmx.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ThreadGoalEntity::class,
        ThreadSpawnEdgeEntity::class,
        LogEntity::class,
        ProviderEntity::class,
        TaskGroupEntity::class,
        CronAutomationEntity::class,
        WorkflowDefinitionEntity::class,
        WorkflowRunEntity::class,
        WorkflowEventEntity::class,
    ],
    version = 17,
    exportSchema = false,
)
abstract class AndmxDatabase : RoomDatabase() {
    abstract fun dao(): AndmxDao

    companion object {
        @Volatile private var instance: AndmxDatabase? = null

        fun get(context: Context): AndmxDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AndmxDatabase::class.java,
                "andmx.db",
            )
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                    MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                )
                .build()
                .also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN goalText TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN goalPhase TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN goalStartedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN goalUpdatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN goalNote TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN toolArgs TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN toolError INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN workPaneTab TEXT NOT NULL DEFAULT 'TERMINAL'")
                db.execSQL("ALTER TABLE conversations ADD COLUMN workPaneVisible INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE conversations ADD COLUMN terminalDockVisible INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN terminalDockTall INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN selectedFilePath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN selectedDiffPath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN browserUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN fileCurrentGuestPath TEXT NOT NULL DEFAULT '/'")
                db.execSQL("ALTER TABLE conversations ADD COLUMN fileViewingGuestPath TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN approvalRisk TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN approvalModeLabel TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN approvalRiskDescription TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v6 → v7: Adds rollout/session metadata columns to conversations,
         * plus new tables for thread goals, spawn edges, and logs.
         * Mirrors Codex's thread store schema.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // conversations table: new columns
                db.execSQL("ALTER TABLE conversations ADD COLUMN rolloutPath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN sandboxPolicy TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN model TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN reasoningEffort TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN memoryMode TEXT NOT NULL DEFAULT 'enabled'")
                db.execSQL("ALTER TABLE conversations ADD COLUMN firstUserMessage TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN sessionId TEXT NOT NULL DEFAULT ''")

                // thread_goals table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS thread_goals (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        conversationId INTEGER NOT NULL,
                        goalId TEXT NOT NULL,
                        objective TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'active',
                        tokenBudget INTEGER,
                        tokensUsed INTEGER NOT NULL DEFAULT 0,
                        timeUsedSeconds INTEGER NOT NULL DEFAULT 0,
                        createdAtMs INTEGER NOT NULL,
                        updatedAtMs INTEGER NOT NULL,
                        FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_thread_goals_conversationId ON thread_goals(conversationId)")

                // thread_spawn_edges table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS thread_spawn_edges (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        parentConversationId INTEGER NOT NULL,
                        childConversationId INTEGER NOT NULL,
                        status TEXT NOT NULL DEFAULT 'pending',
                        createdAtMs INTEGER NOT NULL,
                        FOREIGN KEY(parentConversationId) REFERENCES conversations(id) ON DELETE CASCADE,
                        FOREIGN KEY(childConversationId) REFERENCES conversations(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_thread_spawn_edges_parentConversationId ON thread_spawn_edges(parentConversationId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_thread_spawn_edges_childConversationId ON thread_spawn_edges(childConversationId)")

                // logs table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        conversationId INTEGER NOT NULL,
                        ts INTEGER NOT NULL,
                        tsNanos INTEGER NOT NULL DEFAULT 0,
                        processUuid TEXT,
                        estimatedBytes INTEGER NOT NULL DEFAULT 0,
                        content TEXT NOT NULL,
                        level TEXT NOT NULL DEFAULT 'info',
                        FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_logs_conversationId ON logs(conversationId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_logs_ts ON logs(ts)")
            }
        }

        /**
         * v7 → v8: Adds the `providers` table for multi-provider support.
         *
         * The table starts empty; [com.andmx.settings.ProviderStore] seeds it
         * from legacy DataStore preferences on first access when no rows exist,
         * so existing users keep their configured endpoint/key.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS providers (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        baseUrl TEXT NOT NULL,
                        apiKey TEXT NOT NULL,
                        apiKeyRequired INTEGER NOT NULL,
                        enabled INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        requestMaxRetries INTEGER NOT NULL,
                        streamMaxRetries INTEGER NOT NULL,
                        streamIdleTimeoutMs INTEGER NOT NULL,
                        httpHeadersJson TEXT NOT NULL,
                        modelsJson TEXT NOT NULL,
                        isPrimary INTEGER NOT NULL,
                        createdAtMs INTEGER NOT NULL,
                        updatedAtMs INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_providers_isPrimary ON providers(isPrimary)")
            }
        }

        /**
         * v8 → v9: Adds goalTokenBudget / goalTokensUsed to conversations
         * for Codex-style goal token budget tracking.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN goalTokenBudget INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN goalTokensUsed INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN imageUrlsJson TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v12 → v13: Adds the `claudeMappingJson` column to providers for
         * ZCode's `providerMappings.claude` model-slot mapping.
         *
         * Empty string means "never configured", which deserializes to null —
         * matching ZCode's optional `claude` block.
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE providers ADD COLUMN claudeMappingJson TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v13 → v14: `cron_automations` 定时任务表（ZCode automations 对齐）。
         * intervalUnit+interval+anchorAt 承载「每 N 单位」scheduleRule，
         * cronExpr 兼容展示与日历槽位。
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS cron_automations (
                        id TEXT NOT NULL PRIMARY KEY,
                        conversationId INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        prompt TEXT NOT NULL,
                        cronExpr TEXT NOT NULL,
                        intervalUnit TEXT NOT NULL DEFAULT '',
                        interval INTEGER NOT NULL DEFAULT 0,
                        anchorAt INTEGER NOT NULL DEFAULT 0,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        recurring INTEGER NOT NULL DEFAULT 1,
                        maxRuns INTEGER NOT NULL DEFAULT 0,
                        runCount INTEGER NOT NULL DEFAULT 0,
                        nextRunAt INTEGER NOT NULL DEFAULT 0,
                        lastRunAt INTEGER NOT NULL DEFAULT 0,
                        lifecycleStatus TEXT NOT NULL DEFAULT 'active',
                        model TEXT NOT NULL DEFAULT '',
                        createdAtMs INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cron_automations_conversationId ON cron_automations(conversationId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cron_automations_nextRunAt ON cron_automations(nextRunAt)")
            }
        }

        /**
         * v14 → v15: dwf 工作流三表——definition 库、run 快照（JSON）、事件流水。
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS workflow_definitions (
                        id TEXT NOT NULL PRIMARY KEY,
                        version TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        enabled INTEGER NOT NULL DEFAULT 1,
                        source TEXT NOT NULL DEFAULT 'user',
                        definitionJson TEXT NOT NULL,
                        createdAtMs INTEGER NOT NULL,
                        updatedAtMs INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS workflow_runs (
                        runId TEXT NOT NULL PRIMARY KEY,
                        conversationId INTEGER NOT NULL,
                        definitionId TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        task TEXT NOT NULL,
                        status TEXT NOT NULL,
                        cwd TEXT NOT NULL,
                        snapshotJson TEXT NOT NULL,
                        createdAtMs INTEGER NOT NULL,
                        updatedAtMs INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workflow_runs_conversationId ON workflow_runs(conversationId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workflow_runs_status ON workflow_runs(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workflow_runs_updatedAtMs ON workflow_runs(updatedAtMs)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS workflow_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        runId TEXT NOT NULL,
                        seq INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        phase TEXT NOT NULL DEFAULT '',
                        nodeId TEXT NOT NULL DEFAULT '',
                        message TEXT NOT NULL DEFAULT '',
                        payloadJson TEXT NOT NULL DEFAULT '',
                        timestamp TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workflow_events_runId ON workflow_events(runId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_workflow_events_runId_seq ON workflow_events(runId, seq)")
            }
        }

        /** v15 → v16: pendingQueueJson——busy 期排队输入的持久化，重启后可恢复。 */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN pendingQueueJson TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v16 → v17: messages.feedback——assistant 消息赞/踩（上游 assistant-feedback）。 */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN feedback INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN groupId TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS task_groups (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        color TEXT NOT NULL DEFAULT 'blue',
                        sortOrder INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )"""
                )
            }
        }
    }
}
