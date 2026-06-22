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
    ],
    version = 8,
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
    }
}
