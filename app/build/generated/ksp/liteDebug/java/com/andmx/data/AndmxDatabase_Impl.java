package com.andmx.data;

import androidx.annotation.NonNull;
import androidx.room.DatabaseConfiguration;
import androidx.room.InvalidationTracker;
import androidx.room.RoomDatabase;
import androidx.room.RoomOpenHelper;
import androidx.room.migration.AutoMigrationSpec;
import androidx.room.migration.Migration;
import androidx.room.util.DBUtil;
import androidx.room.util.TableInfo;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class AndmxDatabase_Impl extends AndmxDatabase {
  private volatile AndmxDao _andmxDao;

  @Override
  @NonNull
  protected SupportSQLiteOpenHelper createOpenHelper(@NonNull final DatabaseConfiguration config) {
    final SupportSQLiteOpenHelper.Callback _openCallback = new RoomOpenHelper(config, new RoomOpenHelper.Delegate(8) {
      @Override
      public void createAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `conversations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `project` TEXT NOT NULL, `title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `goalText` TEXT NOT NULL, `goalPhase` TEXT NOT NULL, `goalStartedAt` INTEGER NOT NULL, `goalUpdatedAt` INTEGER NOT NULL, `goalNote` TEXT NOT NULL, `workPaneTab` TEXT NOT NULL, `workPaneVisible` INTEGER NOT NULL, `terminalDockVisible` INTEGER NOT NULL, `terminalDockTall` INTEGER NOT NULL, `selectedFilePath` TEXT NOT NULL, `selectedDiffPath` TEXT NOT NULL, `browserUrl` TEXT NOT NULL, `fileCurrentGuestPath` TEXT NOT NULL, `fileViewingGuestPath` TEXT NOT NULL, `rolloutPath` TEXT NOT NULL, `sandboxPolicy` TEXT NOT NULL, `model` TEXT NOT NULL, `reasoningEffort` TEXT NOT NULL, `memoryMode` TEXT NOT NULL, `firstUserMessage` TEXT NOT NULL, `archived` INTEGER NOT NULL, `sessionId` TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `conversationId` INTEGER NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `toolName` TEXT, `toolArgs` TEXT NOT NULL, `toolError` INTEGER NOT NULL, `approvalRisk` TEXT NOT NULL, `approvalModeLabel` TEXT NOT NULL, `approvalRiskDescription` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_conversationId` ON `messages` (`conversationId`)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `thread_goals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `conversationId` INTEGER NOT NULL, `goalId` TEXT NOT NULL, `objective` TEXT NOT NULL, `status` TEXT NOT NULL, `tokenBudget` INTEGER, `tokensUsed` INTEGER NOT NULL, `timeUsedSeconds` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_thread_goals_conversationId` ON `thread_goals` (`conversationId`)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `thread_spawn_edges` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `parentConversationId` INTEGER NOT NULL, `childConversationId` INTEGER NOT NULL, `status` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, FOREIGN KEY(`parentConversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`childConversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_thread_spawn_edges_parentConversationId` ON `thread_spawn_edges` (`parentConversationId`)");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_thread_spawn_edges_childConversationId` ON `thread_spawn_edges` (`childConversationId`)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `conversationId` INTEGER NOT NULL, `ts` INTEGER NOT NULL, `tsNanos` INTEGER NOT NULL, `processUuid` TEXT, `estimatedBytes` INTEGER NOT NULL, `content` TEXT NOT NULL, `level` TEXT NOT NULL, FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_logs_conversationId` ON `logs` (`conversationId`)");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_logs_ts` ON `logs` (`ts`)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `providers` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `baseUrl` TEXT NOT NULL, `apiKey` TEXT NOT NULL, `apiKeyRequired` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `source` TEXT NOT NULL, `requestMaxRetries` INTEGER NOT NULL, `streamMaxRetries` INTEGER NOT NULL, `streamIdleTimeoutMs` INTEGER NOT NULL, `httpHeadersJson` TEXT NOT NULL, `modelsJson` TEXT NOT NULL, `isPrimary` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`id`))");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_providers_isPrimary` ON `providers` (`isPrimary`)");
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)");
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '3fc9f5abd02d80743695c48e36271605')");
      }

      @Override
      public void dropAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS `conversations`");
        db.execSQL("DROP TABLE IF EXISTS `messages`");
        db.execSQL("DROP TABLE IF EXISTS `thread_goals`");
        db.execSQL("DROP TABLE IF EXISTS `thread_spawn_edges`");
        db.execSQL("DROP TABLE IF EXISTS `logs`");
        db.execSQL("DROP TABLE IF EXISTS `providers`");
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onDestructiveMigration(db);
          }
        }
      }

      @Override
      public void onCreate(@NonNull final SupportSQLiteDatabase db) {
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onCreate(db);
          }
        }
      }

      @Override
      public void onOpen(@NonNull final SupportSQLiteDatabase db) {
        mDatabase = db;
        db.execSQL("PRAGMA foreign_keys = ON");
        internalInitInvalidationTracker(db);
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onOpen(db);
          }
        }
      }

      @Override
      public void onPreMigrate(@NonNull final SupportSQLiteDatabase db) {
        DBUtil.dropFtsSyncTriggers(db);
      }

      @Override
      public void onPostMigrate(@NonNull final SupportSQLiteDatabase db) {
      }

      @Override
      @NonNull
      public RoomOpenHelper.ValidationResult onValidateSchema(
          @NonNull final SupportSQLiteDatabase db) {
        final HashMap<String, TableInfo.Column> _columnsConversations = new HashMap<String, TableInfo.Column>(27);
        _columnsConversations.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("project", new TableInfo.Column("project", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("title", new TableInfo.Column("title", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("createdAt", new TableInfo.Column("createdAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("updatedAt", new TableInfo.Column("updatedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("goalText", new TableInfo.Column("goalText", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("goalPhase", new TableInfo.Column("goalPhase", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("goalStartedAt", new TableInfo.Column("goalStartedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("goalUpdatedAt", new TableInfo.Column("goalUpdatedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("goalNote", new TableInfo.Column("goalNote", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("workPaneTab", new TableInfo.Column("workPaneTab", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("workPaneVisible", new TableInfo.Column("workPaneVisible", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("terminalDockVisible", new TableInfo.Column("terminalDockVisible", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("terminalDockTall", new TableInfo.Column("terminalDockTall", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("selectedFilePath", new TableInfo.Column("selectedFilePath", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("selectedDiffPath", new TableInfo.Column("selectedDiffPath", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("browserUrl", new TableInfo.Column("browserUrl", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("fileCurrentGuestPath", new TableInfo.Column("fileCurrentGuestPath", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("fileViewingGuestPath", new TableInfo.Column("fileViewingGuestPath", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("rolloutPath", new TableInfo.Column("rolloutPath", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("sandboxPolicy", new TableInfo.Column("sandboxPolicy", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("model", new TableInfo.Column("model", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("reasoningEffort", new TableInfo.Column("reasoningEffort", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("memoryMode", new TableInfo.Column("memoryMode", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("firstUserMessage", new TableInfo.Column("firstUserMessage", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("archived", new TableInfo.Column("archived", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversations.put("sessionId", new TableInfo.Column("sessionId", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysConversations = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesConversations = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoConversations = new TableInfo("conversations", _columnsConversations, _foreignKeysConversations, _indicesConversations);
        final TableInfo _existingConversations = TableInfo.read(db, "conversations");
        if (!_infoConversations.equals(_existingConversations)) {
          return new RoomOpenHelper.ValidationResult(false, "conversations(com.andmx.data.ConversationEntity).\n"
                  + " Expected:\n" + _infoConversations + "\n"
                  + " Found:\n" + _existingConversations);
        }
        final HashMap<String, TableInfo.Column> _columnsMessages = new HashMap<String, TableInfo.Column>(11);
        _columnsMessages.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("conversationId", new TableInfo.Column("conversationId", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("role", new TableInfo.Column("role", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("content", new TableInfo.Column("content", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("toolName", new TableInfo.Column("toolName", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("toolArgs", new TableInfo.Column("toolArgs", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("toolError", new TableInfo.Column("toolError", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("approvalRisk", new TableInfo.Column("approvalRisk", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("approvalModeLabel", new TableInfo.Column("approvalModeLabel", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("approvalRiskDescription", new TableInfo.Column("approvalRiskDescription", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessages.put("createdAt", new TableInfo.Column("createdAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysMessages = new HashSet<TableInfo.ForeignKey>(1);
        _foreignKeysMessages.add(new TableInfo.ForeignKey("conversations", "CASCADE", "NO ACTION", Arrays.asList("conversationId"), Arrays.asList("id")));
        final HashSet<TableInfo.Index> _indicesMessages = new HashSet<TableInfo.Index>(1);
        _indicesMessages.add(new TableInfo.Index("index_messages_conversationId", false, Arrays.asList("conversationId"), Arrays.asList("ASC")));
        final TableInfo _infoMessages = new TableInfo("messages", _columnsMessages, _foreignKeysMessages, _indicesMessages);
        final TableInfo _existingMessages = TableInfo.read(db, "messages");
        if (!_infoMessages.equals(_existingMessages)) {
          return new RoomOpenHelper.ValidationResult(false, "messages(com.andmx.data.MessageEntity).\n"
                  + " Expected:\n" + _infoMessages + "\n"
                  + " Found:\n" + _existingMessages);
        }
        final HashMap<String, TableInfo.Column> _columnsThreadGoals = new HashMap<String, TableInfo.Column>(10);
        _columnsThreadGoals.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsThreadGoals.put("conversationId", new TableInfo.Column("conversationId", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsThreadGoals.put("goalId", new TableInfo.Column("goalId", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsThreadGoals.put("objective", new TableInfo.Column("objective", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsThreadGoals.put("status", new TableInfo.Column("status", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsThreadGoals.put("tokenBudget", new TableInfo.Column("tokenBudget", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsThreadGoals.put("tokensUsed", new TableInfo.Column("tokensUsed", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsThreadGoals.put("timeUsedSeconds", new TableInfo.Column("timeUsedSeconds", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsThreadGoals.put("createdAtMs", new TableInfo.Column("createdAtMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsThreadGoals.put("updatedAtMs", new TableInfo.Column("updatedAtMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysThreadGoals = new HashSet<TableInfo.ForeignKey>(1);
        _foreignKeysThreadGoals.add(new TableInfo.ForeignKey("conversations", "CASCADE", "NO ACTION", Arrays.asList("conversationId"), Arrays.asList("id")));
        final HashSet<TableInfo.Index> _indicesThreadGoals = new HashSet<TableInfo.Index>(1);
        _indicesThreadGoals.add(new TableInfo.Index("index_thread_goals_conversationId", false, Arrays.asList("conversationId"), Arrays.asList("ASC")));
        final TableInfo _infoThreadGoals = new TableInfo("thread_goals", _columnsThreadGoals, _foreignKeysThreadGoals, _indicesThreadGoals);
        final TableInfo _existingThreadGoals = TableInfo.read(db, "thread_goals");
        if (!_infoThreadGoals.equals(_existingThreadGoals)) {
          return new RoomOpenHelper.ValidationResult(false, "thread_goals(com.andmx.data.ThreadGoalEntity).\n"
                  + " Expected:\n" + _infoThreadGoals + "\n"
                  + " Found:\n" + _existingThreadGoals);
        }
        final HashMap<String, TableInfo.Column> _columnsThreadSpawnEdges = new HashMap<String, TableInfo.Column>(5);
        _columnsThreadSpawnEdges.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsThreadSpawnEdges.put("parentConversationId", new TableInfo.Column("parentConversationId", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsThreadSpawnEdges.put("childConversationId", new TableInfo.Column("childConversationId", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsThreadSpawnEdges.put("status", new TableInfo.Column("status", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsThreadSpawnEdges.put("createdAtMs", new TableInfo.Column("createdAtMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysThreadSpawnEdges = new HashSet<TableInfo.ForeignKey>(2);
        _foreignKeysThreadSpawnEdges.add(new TableInfo.ForeignKey("conversations", "CASCADE", "NO ACTION", Arrays.asList("parentConversationId"), Arrays.asList("id")));
        _foreignKeysThreadSpawnEdges.add(new TableInfo.ForeignKey("conversations", "CASCADE", "NO ACTION", Arrays.asList("childConversationId"), Arrays.asList("id")));
        final HashSet<TableInfo.Index> _indicesThreadSpawnEdges = new HashSet<TableInfo.Index>(2);
        _indicesThreadSpawnEdges.add(new TableInfo.Index("index_thread_spawn_edges_parentConversationId", false, Arrays.asList("parentConversationId"), Arrays.asList("ASC")));
        _indicesThreadSpawnEdges.add(new TableInfo.Index("index_thread_spawn_edges_childConversationId", false, Arrays.asList("childConversationId"), Arrays.asList("ASC")));
        final TableInfo _infoThreadSpawnEdges = new TableInfo("thread_spawn_edges", _columnsThreadSpawnEdges, _foreignKeysThreadSpawnEdges, _indicesThreadSpawnEdges);
        final TableInfo _existingThreadSpawnEdges = TableInfo.read(db, "thread_spawn_edges");
        if (!_infoThreadSpawnEdges.equals(_existingThreadSpawnEdges)) {
          return new RoomOpenHelper.ValidationResult(false, "thread_spawn_edges(com.andmx.data.ThreadSpawnEdgeEntity).\n"
                  + " Expected:\n" + _infoThreadSpawnEdges + "\n"
                  + " Found:\n" + _existingThreadSpawnEdges);
        }
        final HashMap<String, TableInfo.Column> _columnsLogs = new HashMap<String, TableInfo.Column>(8);
        _columnsLogs.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsLogs.put("conversationId", new TableInfo.Column("conversationId", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsLogs.put("ts", new TableInfo.Column("ts", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsLogs.put("tsNanos", new TableInfo.Column("tsNanos", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsLogs.put("processUuid", new TableInfo.Column("processUuid", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsLogs.put("estimatedBytes", new TableInfo.Column("estimatedBytes", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsLogs.put("content", new TableInfo.Column("content", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsLogs.put("level", new TableInfo.Column("level", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysLogs = new HashSet<TableInfo.ForeignKey>(1);
        _foreignKeysLogs.add(new TableInfo.ForeignKey("conversations", "CASCADE", "NO ACTION", Arrays.asList("conversationId"), Arrays.asList("id")));
        final HashSet<TableInfo.Index> _indicesLogs = new HashSet<TableInfo.Index>(2);
        _indicesLogs.add(new TableInfo.Index("index_logs_conversationId", false, Arrays.asList("conversationId"), Arrays.asList("ASC")));
        _indicesLogs.add(new TableInfo.Index("index_logs_ts", false, Arrays.asList("ts"), Arrays.asList("ASC")));
        final TableInfo _infoLogs = new TableInfo("logs", _columnsLogs, _foreignKeysLogs, _indicesLogs);
        final TableInfo _existingLogs = TableInfo.read(db, "logs");
        if (!_infoLogs.equals(_existingLogs)) {
          return new RoomOpenHelper.ValidationResult(false, "logs(com.andmx.data.LogEntity).\n"
                  + " Expected:\n" + _infoLogs + "\n"
                  + " Found:\n" + _existingLogs);
        }
        final HashMap<String, TableInfo.Column> _columnsProviders = new HashMap<String, TableInfo.Column>(16);
        _columnsProviders.put("id", new TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsProviders.put("name", new TableInfo.Column("name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsProviders.put("kind", new TableInfo.Column("kind", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsProviders.put("baseUrl", new TableInfo.Column("baseUrl", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsProviders.put("apiKey", new TableInfo.Column("apiKey", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsProviders.put("apiKeyRequired", new TableInfo.Column("apiKeyRequired", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsProviders.put("enabled", new TableInfo.Column("enabled", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsProviders.put("source", new TableInfo.Column("source", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsProviders.put("requestMaxRetries", new TableInfo.Column("requestMaxRetries", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsProviders.put("streamMaxRetries", new TableInfo.Column("streamMaxRetries", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsProviders.put("streamIdleTimeoutMs", new TableInfo.Column("streamIdleTimeoutMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsProviders.put("httpHeadersJson", new TableInfo.Column("httpHeadersJson", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsProviders.put("modelsJson", new TableInfo.Column("modelsJson", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsProviders.put("isPrimary", new TableInfo.Column("isPrimary", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsProviders.put("createdAtMs", new TableInfo.Column("createdAtMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsProviders.put("updatedAtMs", new TableInfo.Column("updatedAtMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysProviders = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesProviders = new HashSet<TableInfo.Index>(1);
        _indicesProviders.add(new TableInfo.Index("index_providers_isPrimary", false, Arrays.asList("isPrimary"), Arrays.asList("ASC")));
        final TableInfo _infoProviders = new TableInfo("providers", _columnsProviders, _foreignKeysProviders, _indicesProviders);
        final TableInfo _existingProviders = TableInfo.read(db, "providers");
        if (!_infoProviders.equals(_existingProviders)) {
          return new RoomOpenHelper.ValidationResult(false, "providers(com.andmx.data.ProviderEntity).\n"
                  + " Expected:\n" + _infoProviders + "\n"
                  + " Found:\n" + _existingProviders);
        }
        return new RoomOpenHelper.ValidationResult(true, null);
      }
    }, "3fc9f5abd02d80743695c48e36271605", "722e6fa1ef56950a939f698104b912e0");
    final SupportSQLiteOpenHelper.Configuration _sqliteConfig = SupportSQLiteOpenHelper.Configuration.builder(config.context).name(config.name).callback(_openCallback).build();
    final SupportSQLiteOpenHelper _helper = config.sqliteOpenHelperFactory.create(_sqliteConfig);
    return _helper;
  }

  @Override
  @NonNull
  protected InvalidationTracker createInvalidationTracker() {
    final HashMap<String, String> _shadowTablesMap = new HashMap<String, String>(0);
    final HashMap<String, Set<String>> _viewTables = new HashMap<String, Set<String>>(0);
    return new InvalidationTracker(this, _shadowTablesMap, _viewTables, "conversations","messages","thread_goals","thread_spawn_edges","logs","providers");
  }

  @Override
  public void clearAllTables() {
    super.assertNotMainThread();
    final SupportSQLiteDatabase _db = super.getOpenHelper().getWritableDatabase();
    final boolean _supportsDeferForeignKeys = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP;
    try {
      if (!_supportsDeferForeignKeys) {
        _db.execSQL("PRAGMA foreign_keys = FALSE");
      }
      super.beginTransaction();
      if (_supportsDeferForeignKeys) {
        _db.execSQL("PRAGMA defer_foreign_keys = TRUE");
      }
      _db.execSQL("DELETE FROM `conversations`");
      _db.execSQL("DELETE FROM `messages`");
      _db.execSQL("DELETE FROM `thread_goals`");
      _db.execSQL("DELETE FROM `thread_spawn_edges`");
      _db.execSQL("DELETE FROM `logs`");
      _db.execSQL("DELETE FROM `providers`");
      super.setTransactionSuccessful();
    } finally {
      super.endTransaction();
      if (!_supportsDeferForeignKeys) {
        _db.execSQL("PRAGMA foreign_keys = TRUE");
      }
      _db.query("PRAGMA wal_checkpoint(FULL)").close();
      if (!_db.inTransaction()) {
        _db.execSQL("VACUUM");
      }
    }
  }

  @Override
  @NonNull
  protected Map<Class<?>, List<Class<?>>> getRequiredTypeConverters() {
    final HashMap<Class<?>, List<Class<?>>> _typeConvertersMap = new HashMap<Class<?>, List<Class<?>>>();
    _typeConvertersMap.put(AndmxDao.class, AndmxDao_Impl.getRequiredConverters());
    return _typeConvertersMap;
  }

  @Override
  @NonNull
  public Set<Class<? extends AutoMigrationSpec>> getRequiredAutoMigrationSpecs() {
    final HashSet<Class<? extends AutoMigrationSpec>> _autoMigrationSpecsSet = new HashSet<Class<? extends AutoMigrationSpec>>();
    return _autoMigrationSpecsSet;
  }

  @Override
  @NonNull
  public List<Migration> getAutoMigrations(
      @NonNull final Map<Class<? extends AutoMigrationSpec>, AutoMigrationSpec> autoMigrationSpecs) {
    final List<Migration> _autoMigrations = new ArrayList<Migration>();
    return _autoMigrations;
  }

  @Override
  public AndmxDao dao() {
    if (_andmxDao != null) {
      return _andmxDao;
    } else {
      synchronized(this) {
        if(_andmxDao == null) {
          _andmxDao = new AndmxDao_Impl(this);
        }
        return _andmxDao;
      }
    }
  }
}
