package com.andmx.data;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class AndmxDao_Impl implements AndmxDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<ConversationEntity> __insertionAdapterOfConversationEntity;

  private final EntityInsertionAdapter<MessageEntity> __insertionAdapterOfMessageEntity;

  private final EntityInsertionAdapter<ThreadGoalEntity> __insertionAdapterOfThreadGoalEntity;

  private final EntityInsertionAdapter<ThreadSpawnEdgeEntity> __insertionAdapterOfThreadSpawnEdgeEntity;

  private final EntityInsertionAdapter<LogEntity> __insertionAdapterOfLogEntity;

  private final EntityInsertionAdapter<ProviderEntity> __insertionAdapterOfProviderEntity;

  private final EntityDeletionOrUpdateAdapter<ConversationEntity> __updateAdapterOfConversationEntity;

  private final EntityDeletionOrUpdateAdapter<ThreadGoalEntity> __updateAdapterOfThreadGoalEntity;

  private final SharedSQLiteStatement __preparedStmtOfTouchConversation;

  private final SharedSQLiteStatement __preparedStmtOfUpdateGoal;

  private final SharedSQLiteStatement __preparedStmtOfUpdateWorkbenchState;

  private final SharedSQLiteStatement __preparedStmtOfDeleteConversation;

  private final SharedSQLiteStatement __preparedStmtOfUpdateSessionMetadata;

  private final SharedSQLiteStatement __preparedStmtOfSetArchived;

  private final SharedSQLiteStatement __preparedStmtOfUpdateGoalProgress;

  private final SharedSQLiteStatement __preparedStmtOfDeleteGoal;

  private final SharedSQLiteStatement __preparedStmtOfUpdateSpawnEdgeStatus;

  private final SharedSQLiteStatement __preparedStmtOfDeleteLogs;

  private final SharedSQLiteStatement __preparedStmtOfDeleteProvider;

  private final SharedSQLiteStatement __preparedStmtOfClearPrimary;

  private final SharedSQLiteStatement __preparedStmtOfSetPrimary;

  public AndmxDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfConversationEntity = new EntityInsertionAdapter<ConversationEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR ABORT INTO `conversations` (`id`,`project`,`title`,`createdAt`,`updatedAt`,`goalText`,`goalPhase`,`goalStartedAt`,`goalUpdatedAt`,`goalNote`,`workPaneTab`,`workPaneVisible`,`terminalDockVisible`,`terminalDockTall`,`selectedFilePath`,`selectedDiffPath`,`browserUrl`,`fileCurrentGuestPath`,`fileViewingGuestPath`,`rolloutPath`,`sandboxPolicy`,`model`,`reasoningEffort`,`memoryMode`,`firstUserMessage`,`archived`,`sessionId`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final ConversationEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindString(2, entity.getProject());
        statement.bindString(3, entity.getTitle());
        statement.bindLong(4, entity.getCreatedAt());
        statement.bindLong(5, entity.getUpdatedAt());
        statement.bindString(6, entity.getGoalText());
        statement.bindString(7, entity.getGoalPhase());
        statement.bindLong(8, entity.getGoalStartedAt());
        statement.bindLong(9, entity.getGoalUpdatedAt());
        statement.bindString(10, entity.getGoalNote());
        statement.bindString(11, entity.getWorkPaneTab());
        final int _tmp = entity.getWorkPaneVisible() ? 1 : 0;
        statement.bindLong(12, _tmp);
        final int _tmp_1 = entity.getTerminalDockVisible() ? 1 : 0;
        statement.bindLong(13, _tmp_1);
        final int _tmp_2 = entity.getTerminalDockTall() ? 1 : 0;
        statement.bindLong(14, _tmp_2);
        statement.bindString(15, entity.getSelectedFilePath());
        statement.bindString(16, entity.getSelectedDiffPath());
        statement.bindString(17, entity.getBrowserUrl());
        statement.bindString(18, entity.getFileCurrentGuestPath());
        statement.bindString(19, entity.getFileViewingGuestPath());
        statement.bindString(20, entity.getRolloutPath());
        statement.bindString(21, entity.getSandboxPolicy());
        statement.bindString(22, entity.getModel());
        statement.bindString(23, entity.getReasoningEffort());
        statement.bindString(24, entity.getMemoryMode());
        statement.bindString(25, entity.getFirstUserMessage());
        final int _tmp_3 = entity.getArchived() ? 1 : 0;
        statement.bindLong(26, _tmp_3);
        statement.bindString(27, entity.getSessionId());
      }
    };
    this.__insertionAdapterOfMessageEntity = new EntityInsertionAdapter<MessageEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR ABORT INTO `messages` (`id`,`conversationId`,`role`,`content`,`toolName`,`toolArgs`,`toolError`,`approvalRisk`,`approvalModeLabel`,`approvalRiskDescription`,`createdAt`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final MessageEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindLong(2, entity.getConversationId());
        statement.bindString(3, entity.getRole());
        statement.bindString(4, entity.getContent());
        if (entity.getToolName() == null) {
          statement.bindNull(5);
        } else {
          statement.bindString(5, entity.getToolName());
        }
        statement.bindString(6, entity.getToolArgs());
        final int _tmp = entity.getToolError() ? 1 : 0;
        statement.bindLong(7, _tmp);
        statement.bindString(8, entity.getApprovalRisk());
        statement.bindString(9, entity.getApprovalModeLabel());
        statement.bindString(10, entity.getApprovalRiskDescription());
        statement.bindLong(11, entity.getCreatedAt());
      }
    };
    this.__insertionAdapterOfThreadGoalEntity = new EntityInsertionAdapter<ThreadGoalEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR ABORT INTO `thread_goals` (`id`,`conversationId`,`goalId`,`objective`,`status`,`tokenBudget`,`tokensUsed`,`timeUsedSeconds`,`createdAtMs`,`updatedAtMs`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final ThreadGoalEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindLong(2, entity.getConversationId());
        statement.bindString(3, entity.getGoalId());
        statement.bindString(4, entity.getObjective());
        statement.bindString(5, entity.getStatus());
        if (entity.getTokenBudget() == null) {
          statement.bindNull(6);
        } else {
          statement.bindLong(6, entity.getTokenBudget());
        }
        statement.bindLong(7, entity.getTokensUsed());
        statement.bindLong(8, entity.getTimeUsedSeconds());
        statement.bindLong(9, entity.getCreatedAtMs());
        statement.bindLong(10, entity.getUpdatedAtMs());
      }
    };
    this.__insertionAdapterOfThreadSpawnEdgeEntity = new EntityInsertionAdapter<ThreadSpawnEdgeEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR ABORT INTO `thread_spawn_edges` (`id`,`parentConversationId`,`childConversationId`,`status`,`createdAtMs`) VALUES (nullif(?, 0),?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final ThreadSpawnEdgeEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindLong(2, entity.getParentConversationId());
        statement.bindLong(3, entity.getChildConversationId());
        statement.bindString(4, entity.getStatus());
        statement.bindLong(5, entity.getCreatedAtMs());
      }
    };
    this.__insertionAdapterOfLogEntity = new EntityInsertionAdapter<LogEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR ABORT INTO `logs` (`id`,`conversationId`,`ts`,`tsNanos`,`processUuid`,`estimatedBytes`,`content`,`level`) VALUES (nullif(?, 0),?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final LogEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindLong(2, entity.getConversationId());
        statement.bindLong(3, entity.getTs());
        statement.bindLong(4, entity.getTsNanos());
        if (entity.getProcessUuid() == null) {
          statement.bindNull(5);
        } else {
          statement.bindString(5, entity.getProcessUuid());
        }
        statement.bindLong(6, entity.getEstimatedBytes());
        statement.bindString(7, entity.getContent());
        statement.bindString(8, entity.getLevel());
      }
    };
    this.__insertionAdapterOfProviderEntity = new EntityInsertionAdapter<ProviderEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `providers` (`id`,`name`,`kind`,`baseUrl`,`apiKey`,`apiKeyRequired`,`enabled`,`source`,`requestMaxRetries`,`streamMaxRetries`,`streamIdleTimeoutMs`,`httpHeadersJson`,`modelsJson`,`isPrimary`,`createdAtMs`,`updatedAtMs`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final ProviderEntity entity) {
        statement.bindString(1, entity.getId());
        statement.bindString(2, entity.getName());
        statement.bindString(3, entity.getKind());
        statement.bindString(4, entity.getBaseUrl());
        statement.bindString(5, entity.getApiKey());
        final int _tmp = entity.getApiKeyRequired() ? 1 : 0;
        statement.bindLong(6, _tmp);
        final int _tmp_1 = entity.getEnabled() ? 1 : 0;
        statement.bindLong(7, _tmp_1);
        statement.bindString(8, entity.getSource());
        statement.bindLong(9, entity.getRequestMaxRetries());
        statement.bindLong(10, entity.getStreamMaxRetries());
        statement.bindLong(11, entity.getStreamIdleTimeoutMs());
        statement.bindString(12, entity.getHttpHeadersJson());
        statement.bindString(13, entity.getModelsJson());
        final int _tmp_2 = entity.isPrimary() ? 1 : 0;
        statement.bindLong(14, _tmp_2);
        statement.bindLong(15, entity.getCreatedAtMs());
        statement.bindLong(16, entity.getUpdatedAtMs());
      }
    };
    this.__updateAdapterOfConversationEntity = new EntityDeletionOrUpdateAdapter<ConversationEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE OR ABORT `conversations` SET `id` = ?,`project` = ?,`title` = ?,`createdAt` = ?,`updatedAt` = ?,`goalText` = ?,`goalPhase` = ?,`goalStartedAt` = ?,`goalUpdatedAt` = ?,`goalNote` = ?,`workPaneTab` = ?,`workPaneVisible` = ?,`terminalDockVisible` = ?,`terminalDockTall` = ?,`selectedFilePath` = ?,`selectedDiffPath` = ?,`browserUrl` = ?,`fileCurrentGuestPath` = ?,`fileViewingGuestPath` = ?,`rolloutPath` = ?,`sandboxPolicy` = ?,`model` = ?,`reasoningEffort` = ?,`memoryMode` = ?,`firstUserMessage` = ?,`archived` = ?,`sessionId` = ? WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final ConversationEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindString(2, entity.getProject());
        statement.bindString(3, entity.getTitle());
        statement.bindLong(4, entity.getCreatedAt());
        statement.bindLong(5, entity.getUpdatedAt());
        statement.bindString(6, entity.getGoalText());
        statement.bindString(7, entity.getGoalPhase());
        statement.bindLong(8, entity.getGoalStartedAt());
        statement.bindLong(9, entity.getGoalUpdatedAt());
        statement.bindString(10, entity.getGoalNote());
        statement.bindString(11, entity.getWorkPaneTab());
        final int _tmp = entity.getWorkPaneVisible() ? 1 : 0;
        statement.bindLong(12, _tmp);
        final int _tmp_1 = entity.getTerminalDockVisible() ? 1 : 0;
        statement.bindLong(13, _tmp_1);
        final int _tmp_2 = entity.getTerminalDockTall() ? 1 : 0;
        statement.bindLong(14, _tmp_2);
        statement.bindString(15, entity.getSelectedFilePath());
        statement.bindString(16, entity.getSelectedDiffPath());
        statement.bindString(17, entity.getBrowserUrl());
        statement.bindString(18, entity.getFileCurrentGuestPath());
        statement.bindString(19, entity.getFileViewingGuestPath());
        statement.bindString(20, entity.getRolloutPath());
        statement.bindString(21, entity.getSandboxPolicy());
        statement.bindString(22, entity.getModel());
        statement.bindString(23, entity.getReasoningEffort());
        statement.bindString(24, entity.getMemoryMode());
        statement.bindString(25, entity.getFirstUserMessage());
        final int _tmp_3 = entity.getArchived() ? 1 : 0;
        statement.bindLong(26, _tmp_3);
        statement.bindString(27, entity.getSessionId());
        statement.bindLong(28, entity.getId());
      }
    };
    this.__updateAdapterOfThreadGoalEntity = new EntityDeletionOrUpdateAdapter<ThreadGoalEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE OR ABORT `thread_goals` SET `id` = ?,`conversationId` = ?,`goalId` = ?,`objective` = ?,`status` = ?,`tokenBudget` = ?,`tokensUsed` = ?,`timeUsedSeconds` = ?,`createdAtMs` = ?,`updatedAtMs` = ? WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final ThreadGoalEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindLong(2, entity.getConversationId());
        statement.bindString(3, entity.getGoalId());
        statement.bindString(4, entity.getObjective());
        statement.bindString(5, entity.getStatus());
        if (entity.getTokenBudget() == null) {
          statement.bindNull(6);
        } else {
          statement.bindLong(6, entity.getTokenBudget());
        }
        statement.bindLong(7, entity.getTokensUsed());
        statement.bindLong(8, entity.getTimeUsedSeconds());
        statement.bindLong(9, entity.getCreatedAtMs());
        statement.bindLong(10, entity.getUpdatedAtMs());
        statement.bindLong(11, entity.getId());
      }
    };
    this.__preparedStmtOfTouchConversation = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE conversations SET title = ?, updatedAt = ? WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfUpdateGoal = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE conversations SET goalText = ?, goalPhase = ?, goalStartedAt = ?, goalUpdatedAt = ?, goalNote = ?, updatedAt = ? WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfUpdateWorkbenchState = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE conversations SET workPaneTab = ?, workPaneVisible = ?, terminalDockVisible = ?, terminalDockTall = ?, selectedFilePath = ?, selectedDiffPath = ?, browserUrl = ?, fileCurrentGuestPath = ?, fileViewingGuestPath = ? WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfDeleteConversation = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM conversations WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfUpdateSessionMetadata = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE conversations SET rolloutPath = ?, sessionId = ?, sandboxPolicy = ?, model = ?, reasoningEffort = ?, memoryMode = ?, firstUserMessage = ? WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfSetArchived = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE conversations SET archived = ? WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfUpdateGoalProgress = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE thread_goals SET status = ?, tokensUsed = ?, timeUsedSeconds = ?, updatedAtMs = ? WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfDeleteGoal = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM thread_goals WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfUpdateSpawnEdgeStatus = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE thread_spawn_edges SET status = ? WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfDeleteLogs = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM logs WHERE conversationId = ?";
        return _query;
      }
    };
    this.__preparedStmtOfDeleteProvider = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM providers WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfClearPrimary = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE providers SET isPrimary = 0";
        return _query;
      }
    };
    this.__preparedStmtOfSetPrimary = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE providers SET isPrimary = 1 WHERE id = ?";
        return _query;
      }
    };
  }

  @Override
  public Object insertConversation(final ConversationEntity c,
      final Continuation<? super Long> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Long>() {
      @Override
      @NonNull
      public Long call() throws Exception {
        __db.beginTransaction();
        try {
          final Long _result = __insertionAdapterOfConversationEntity.insertAndReturnId(c);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object insertMessage(final MessageEntity m, final Continuation<? super Long> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Long>() {
      @Override
      @NonNull
      public Long call() throws Exception {
        __db.beginTransaction();
        try {
          final Long _result = __insertionAdapterOfMessageEntity.insertAndReturnId(m);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object insertGoal(final ThreadGoalEntity goal,
      final Continuation<? super Long> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Long>() {
      @Override
      @NonNull
      public Long call() throws Exception {
        __db.beginTransaction();
        try {
          final Long _result = __insertionAdapterOfThreadGoalEntity.insertAndReturnId(goal);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object insertSpawnEdge(final ThreadSpawnEdgeEntity edge,
      final Continuation<? super Long> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Long>() {
      @Override
      @NonNull
      public Long call() throws Exception {
        __db.beginTransaction();
        try {
          final Long _result = __insertionAdapterOfThreadSpawnEdgeEntity.insertAndReturnId(edge);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object insertLog(final LogEntity log, final Continuation<? super Long> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Long>() {
      @Override
      @NonNull
      public Long call() throws Exception {
        __db.beginTransaction();
        try {
          final Long _result = __insertionAdapterOfLogEntity.insertAndReturnId(log);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object upsertProvider(final ProviderEntity p,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfProviderEntity.insert(p);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object updateConversation(final ConversationEntity c,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __updateAdapterOfConversationEntity.handle(c);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object updateGoal(final ThreadGoalEntity goal,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __updateAdapterOfThreadGoalEntity.handle(goal);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object touchConversation(final long id, final String title, final long ts,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfTouchConversation.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, title);
        _argIndex = 2;
        _stmt.bindLong(_argIndex, ts);
        _argIndex = 3;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfTouchConversation.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object updateGoal(final long id, final String text, final String phase,
      final long startedAt, final long updatedAt, final String note,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateGoal.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, text);
        _argIndex = 2;
        _stmt.bindString(_argIndex, phase);
        _argIndex = 3;
        _stmt.bindLong(_argIndex, startedAt);
        _argIndex = 4;
        _stmt.bindLong(_argIndex, updatedAt);
        _argIndex = 5;
        _stmt.bindString(_argIndex, note);
        _argIndex = 6;
        _stmt.bindLong(_argIndex, updatedAt);
        _argIndex = 7;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUpdateGoal.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object updateWorkbenchState(final long id, final String workPaneTab,
      final boolean workPaneVisible, final boolean terminalDockVisible,
      final boolean terminalDockTall, final String selectedFilePath, final String selectedDiffPath,
      final String browserUrl, final String fileCurrentGuestPath, final String fileViewingGuestPath,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateWorkbenchState.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, workPaneTab);
        _argIndex = 2;
        final int _tmp = workPaneVisible ? 1 : 0;
        _stmt.bindLong(_argIndex, _tmp);
        _argIndex = 3;
        final int _tmp_1 = terminalDockVisible ? 1 : 0;
        _stmt.bindLong(_argIndex, _tmp_1);
        _argIndex = 4;
        final int _tmp_2 = terminalDockTall ? 1 : 0;
        _stmt.bindLong(_argIndex, _tmp_2);
        _argIndex = 5;
        _stmt.bindString(_argIndex, selectedFilePath);
        _argIndex = 6;
        _stmt.bindString(_argIndex, selectedDiffPath);
        _argIndex = 7;
        _stmt.bindString(_argIndex, browserUrl);
        _argIndex = 8;
        _stmt.bindString(_argIndex, fileCurrentGuestPath);
        _argIndex = 9;
        _stmt.bindString(_argIndex, fileViewingGuestPath);
        _argIndex = 10;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUpdateWorkbenchState.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteConversation(final long id, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteConversation.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteConversation.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object updateSessionMetadata(final long id, final String path, final String sessionId,
      final String sandboxPolicy, final String model, final String reasoningEffort,
      final String memoryMode, final String firstUserMessage,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateSessionMetadata.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, path);
        _argIndex = 2;
        _stmt.bindString(_argIndex, sessionId);
        _argIndex = 3;
        _stmt.bindString(_argIndex, sandboxPolicy);
        _argIndex = 4;
        _stmt.bindString(_argIndex, model);
        _argIndex = 5;
        _stmt.bindString(_argIndex, reasoningEffort);
        _argIndex = 6;
        _stmt.bindString(_argIndex, memoryMode);
        _argIndex = 7;
        _stmt.bindString(_argIndex, firstUserMessage);
        _argIndex = 8;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUpdateSessionMetadata.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object setArchived(final long id, final boolean archived,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfSetArchived.acquire();
        int _argIndex = 1;
        final int _tmp = archived ? 1 : 0;
        _stmt.bindLong(_argIndex, _tmp);
        _argIndex = 2;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfSetArchived.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object updateGoalProgress(final long id, final String status, final int tokensUsed,
      final int timeUsed, final long updatedAtMs, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateGoalProgress.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, status);
        _argIndex = 2;
        _stmt.bindLong(_argIndex, tokensUsed);
        _argIndex = 3;
        _stmt.bindLong(_argIndex, timeUsed);
        _argIndex = 4;
        _stmt.bindLong(_argIndex, updatedAtMs);
        _argIndex = 5;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUpdateGoalProgress.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteGoal(final long id, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteGoal.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteGoal.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object updateSpawnEdgeStatus(final long id, final String status,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateSpawnEdgeStatus.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, status);
        _argIndex = 2;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUpdateSpawnEdgeStatus.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteLogs(final long conversationId,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteLogs.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, conversationId);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteLogs.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteProvider(final String id, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteProvider.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteProvider.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object clearPrimary(final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfClearPrimary.acquire();
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfClearPrimary.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object setPrimary(final String id, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfSetPrimary.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfSetPrimary.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<ConversationEntity>> observeConversations() {
    final String _sql = "SELECT * FROM conversations WHERE archived = 0 ORDER BY updatedAt DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"conversations"}, new Callable<List<ConversationEntity>>() {
      @Override
      @NonNull
      public List<ConversationEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfProject = CursorUtil.getColumnIndexOrThrow(_cursor, "project");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updatedAt");
          final int _cursorIndexOfGoalText = CursorUtil.getColumnIndexOrThrow(_cursor, "goalText");
          final int _cursorIndexOfGoalPhase = CursorUtil.getColumnIndexOrThrow(_cursor, "goalPhase");
          final int _cursorIndexOfGoalStartedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "goalStartedAt");
          final int _cursorIndexOfGoalUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "goalUpdatedAt");
          final int _cursorIndexOfGoalNote = CursorUtil.getColumnIndexOrThrow(_cursor, "goalNote");
          final int _cursorIndexOfWorkPaneTab = CursorUtil.getColumnIndexOrThrow(_cursor, "workPaneTab");
          final int _cursorIndexOfWorkPaneVisible = CursorUtil.getColumnIndexOrThrow(_cursor, "workPaneVisible");
          final int _cursorIndexOfTerminalDockVisible = CursorUtil.getColumnIndexOrThrow(_cursor, "terminalDockVisible");
          final int _cursorIndexOfTerminalDockTall = CursorUtil.getColumnIndexOrThrow(_cursor, "terminalDockTall");
          final int _cursorIndexOfSelectedFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "selectedFilePath");
          final int _cursorIndexOfSelectedDiffPath = CursorUtil.getColumnIndexOrThrow(_cursor, "selectedDiffPath");
          final int _cursorIndexOfBrowserUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "browserUrl");
          final int _cursorIndexOfFileCurrentGuestPath = CursorUtil.getColumnIndexOrThrow(_cursor, "fileCurrentGuestPath");
          final int _cursorIndexOfFileViewingGuestPath = CursorUtil.getColumnIndexOrThrow(_cursor, "fileViewingGuestPath");
          final int _cursorIndexOfRolloutPath = CursorUtil.getColumnIndexOrThrow(_cursor, "rolloutPath");
          final int _cursorIndexOfSandboxPolicy = CursorUtil.getColumnIndexOrThrow(_cursor, "sandboxPolicy");
          final int _cursorIndexOfModel = CursorUtil.getColumnIndexOrThrow(_cursor, "model");
          final int _cursorIndexOfReasoningEffort = CursorUtil.getColumnIndexOrThrow(_cursor, "reasoningEffort");
          final int _cursorIndexOfMemoryMode = CursorUtil.getColumnIndexOrThrow(_cursor, "memoryMode");
          final int _cursorIndexOfFirstUserMessage = CursorUtil.getColumnIndexOrThrow(_cursor, "firstUserMessage");
          final int _cursorIndexOfArchived = CursorUtil.getColumnIndexOrThrow(_cursor, "archived");
          final int _cursorIndexOfSessionId = CursorUtil.getColumnIndexOrThrow(_cursor, "sessionId");
          final List<ConversationEntity> _result = new ArrayList<ConversationEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final ConversationEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpProject;
            _tmpProject = _cursor.getString(_cursorIndexOfProject);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            final long _tmpUpdatedAt;
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
            final String _tmpGoalText;
            _tmpGoalText = _cursor.getString(_cursorIndexOfGoalText);
            final String _tmpGoalPhase;
            _tmpGoalPhase = _cursor.getString(_cursorIndexOfGoalPhase);
            final long _tmpGoalStartedAt;
            _tmpGoalStartedAt = _cursor.getLong(_cursorIndexOfGoalStartedAt);
            final long _tmpGoalUpdatedAt;
            _tmpGoalUpdatedAt = _cursor.getLong(_cursorIndexOfGoalUpdatedAt);
            final String _tmpGoalNote;
            _tmpGoalNote = _cursor.getString(_cursorIndexOfGoalNote);
            final String _tmpWorkPaneTab;
            _tmpWorkPaneTab = _cursor.getString(_cursorIndexOfWorkPaneTab);
            final boolean _tmpWorkPaneVisible;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfWorkPaneVisible);
            _tmpWorkPaneVisible = _tmp != 0;
            final boolean _tmpTerminalDockVisible;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfTerminalDockVisible);
            _tmpTerminalDockVisible = _tmp_1 != 0;
            final boolean _tmpTerminalDockTall;
            final int _tmp_2;
            _tmp_2 = _cursor.getInt(_cursorIndexOfTerminalDockTall);
            _tmpTerminalDockTall = _tmp_2 != 0;
            final String _tmpSelectedFilePath;
            _tmpSelectedFilePath = _cursor.getString(_cursorIndexOfSelectedFilePath);
            final String _tmpSelectedDiffPath;
            _tmpSelectedDiffPath = _cursor.getString(_cursorIndexOfSelectedDiffPath);
            final String _tmpBrowserUrl;
            _tmpBrowserUrl = _cursor.getString(_cursorIndexOfBrowserUrl);
            final String _tmpFileCurrentGuestPath;
            _tmpFileCurrentGuestPath = _cursor.getString(_cursorIndexOfFileCurrentGuestPath);
            final String _tmpFileViewingGuestPath;
            _tmpFileViewingGuestPath = _cursor.getString(_cursorIndexOfFileViewingGuestPath);
            final String _tmpRolloutPath;
            _tmpRolloutPath = _cursor.getString(_cursorIndexOfRolloutPath);
            final String _tmpSandboxPolicy;
            _tmpSandboxPolicy = _cursor.getString(_cursorIndexOfSandboxPolicy);
            final String _tmpModel;
            _tmpModel = _cursor.getString(_cursorIndexOfModel);
            final String _tmpReasoningEffort;
            _tmpReasoningEffort = _cursor.getString(_cursorIndexOfReasoningEffort);
            final String _tmpMemoryMode;
            _tmpMemoryMode = _cursor.getString(_cursorIndexOfMemoryMode);
            final String _tmpFirstUserMessage;
            _tmpFirstUserMessage = _cursor.getString(_cursorIndexOfFirstUserMessage);
            final boolean _tmpArchived;
            final int _tmp_3;
            _tmp_3 = _cursor.getInt(_cursorIndexOfArchived);
            _tmpArchived = _tmp_3 != 0;
            final String _tmpSessionId;
            _tmpSessionId = _cursor.getString(_cursorIndexOfSessionId);
            _item = new ConversationEntity(_tmpId,_tmpProject,_tmpTitle,_tmpCreatedAt,_tmpUpdatedAt,_tmpGoalText,_tmpGoalPhase,_tmpGoalStartedAt,_tmpGoalUpdatedAt,_tmpGoalNote,_tmpWorkPaneTab,_tmpWorkPaneVisible,_tmpTerminalDockVisible,_tmpTerminalDockTall,_tmpSelectedFilePath,_tmpSelectedDiffPath,_tmpBrowserUrl,_tmpFileCurrentGuestPath,_tmpFileViewingGuestPath,_tmpRolloutPath,_tmpSandboxPolicy,_tmpModel,_tmpReasoningEffort,_tmpMemoryMode,_tmpFirstUserMessage,_tmpArchived,_tmpSessionId);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Flow<List<ConversationEntity>> observeAllConversations() {
    final String _sql = "SELECT * FROM conversations ORDER BY updatedAt DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"conversations"}, new Callable<List<ConversationEntity>>() {
      @Override
      @NonNull
      public List<ConversationEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfProject = CursorUtil.getColumnIndexOrThrow(_cursor, "project");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updatedAt");
          final int _cursorIndexOfGoalText = CursorUtil.getColumnIndexOrThrow(_cursor, "goalText");
          final int _cursorIndexOfGoalPhase = CursorUtil.getColumnIndexOrThrow(_cursor, "goalPhase");
          final int _cursorIndexOfGoalStartedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "goalStartedAt");
          final int _cursorIndexOfGoalUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "goalUpdatedAt");
          final int _cursorIndexOfGoalNote = CursorUtil.getColumnIndexOrThrow(_cursor, "goalNote");
          final int _cursorIndexOfWorkPaneTab = CursorUtil.getColumnIndexOrThrow(_cursor, "workPaneTab");
          final int _cursorIndexOfWorkPaneVisible = CursorUtil.getColumnIndexOrThrow(_cursor, "workPaneVisible");
          final int _cursorIndexOfTerminalDockVisible = CursorUtil.getColumnIndexOrThrow(_cursor, "terminalDockVisible");
          final int _cursorIndexOfTerminalDockTall = CursorUtil.getColumnIndexOrThrow(_cursor, "terminalDockTall");
          final int _cursorIndexOfSelectedFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "selectedFilePath");
          final int _cursorIndexOfSelectedDiffPath = CursorUtil.getColumnIndexOrThrow(_cursor, "selectedDiffPath");
          final int _cursorIndexOfBrowserUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "browserUrl");
          final int _cursorIndexOfFileCurrentGuestPath = CursorUtil.getColumnIndexOrThrow(_cursor, "fileCurrentGuestPath");
          final int _cursorIndexOfFileViewingGuestPath = CursorUtil.getColumnIndexOrThrow(_cursor, "fileViewingGuestPath");
          final int _cursorIndexOfRolloutPath = CursorUtil.getColumnIndexOrThrow(_cursor, "rolloutPath");
          final int _cursorIndexOfSandboxPolicy = CursorUtil.getColumnIndexOrThrow(_cursor, "sandboxPolicy");
          final int _cursorIndexOfModel = CursorUtil.getColumnIndexOrThrow(_cursor, "model");
          final int _cursorIndexOfReasoningEffort = CursorUtil.getColumnIndexOrThrow(_cursor, "reasoningEffort");
          final int _cursorIndexOfMemoryMode = CursorUtil.getColumnIndexOrThrow(_cursor, "memoryMode");
          final int _cursorIndexOfFirstUserMessage = CursorUtil.getColumnIndexOrThrow(_cursor, "firstUserMessage");
          final int _cursorIndexOfArchived = CursorUtil.getColumnIndexOrThrow(_cursor, "archived");
          final int _cursorIndexOfSessionId = CursorUtil.getColumnIndexOrThrow(_cursor, "sessionId");
          final List<ConversationEntity> _result = new ArrayList<ConversationEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final ConversationEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpProject;
            _tmpProject = _cursor.getString(_cursorIndexOfProject);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            final long _tmpUpdatedAt;
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
            final String _tmpGoalText;
            _tmpGoalText = _cursor.getString(_cursorIndexOfGoalText);
            final String _tmpGoalPhase;
            _tmpGoalPhase = _cursor.getString(_cursorIndexOfGoalPhase);
            final long _tmpGoalStartedAt;
            _tmpGoalStartedAt = _cursor.getLong(_cursorIndexOfGoalStartedAt);
            final long _tmpGoalUpdatedAt;
            _tmpGoalUpdatedAt = _cursor.getLong(_cursorIndexOfGoalUpdatedAt);
            final String _tmpGoalNote;
            _tmpGoalNote = _cursor.getString(_cursorIndexOfGoalNote);
            final String _tmpWorkPaneTab;
            _tmpWorkPaneTab = _cursor.getString(_cursorIndexOfWorkPaneTab);
            final boolean _tmpWorkPaneVisible;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfWorkPaneVisible);
            _tmpWorkPaneVisible = _tmp != 0;
            final boolean _tmpTerminalDockVisible;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfTerminalDockVisible);
            _tmpTerminalDockVisible = _tmp_1 != 0;
            final boolean _tmpTerminalDockTall;
            final int _tmp_2;
            _tmp_2 = _cursor.getInt(_cursorIndexOfTerminalDockTall);
            _tmpTerminalDockTall = _tmp_2 != 0;
            final String _tmpSelectedFilePath;
            _tmpSelectedFilePath = _cursor.getString(_cursorIndexOfSelectedFilePath);
            final String _tmpSelectedDiffPath;
            _tmpSelectedDiffPath = _cursor.getString(_cursorIndexOfSelectedDiffPath);
            final String _tmpBrowserUrl;
            _tmpBrowserUrl = _cursor.getString(_cursorIndexOfBrowserUrl);
            final String _tmpFileCurrentGuestPath;
            _tmpFileCurrentGuestPath = _cursor.getString(_cursorIndexOfFileCurrentGuestPath);
            final String _tmpFileViewingGuestPath;
            _tmpFileViewingGuestPath = _cursor.getString(_cursorIndexOfFileViewingGuestPath);
            final String _tmpRolloutPath;
            _tmpRolloutPath = _cursor.getString(_cursorIndexOfRolloutPath);
            final String _tmpSandboxPolicy;
            _tmpSandboxPolicy = _cursor.getString(_cursorIndexOfSandboxPolicy);
            final String _tmpModel;
            _tmpModel = _cursor.getString(_cursorIndexOfModel);
            final String _tmpReasoningEffort;
            _tmpReasoningEffort = _cursor.getString(_cursorIndexOfReasoningEffort);
            final String _tmpMemoryMode;
            _tmpMemoryMode = _cursor.getString(_cursorIndexOfMemoryMode);
            final String _tmpFirstUserMessage;
            _tmpFirstUserMessage = _cursor.getString(_cursorIndexOfFirstUserMessage);
            final boolean _tmpArchived;
            final int _tmp_3;
            _tmp_3 = _cursor.getInt(_cursorIndexOfArchived);
            _tmpArchived = _tmp_3 != 0;
            final String _tmpSessionId;
            _tmpSessionId = _cursor.getString(_cursorIndexOfSessionId);
            _item = new ConversationEntity(_tmpId,_tmpProject,_tmpTitle,_tmpCreatedAt,_tmpUpdatedAt,_tmpGoalText,_tmpGoalPhase,_tmpGoalStartedAt,_tmpGoalUpdatedAt,_tmpGoalNote,_tmpWorkPaneTab,_tmpWorkPaneVisible,_tmpTerminalDockVisible,_tmpTerminalDockTall,_tmpSelectedFilePath,_tmpSelectedDiffPath,_tmpBrowserUrl,_tmpFileCurrentGuestPath,_tmpFileViewingGuestPath,_tmpRolloutPath,_tmpSandboxPolicy,_tmpModel,_tmpReasoningEffort,_tmpMemoryMode,_tmpFirstUserMessage,_tmpArchived,_tmpSessionId);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object search(final String q,
      final Continuation<? super List<ConversationEntity>> $completion) {
    final String _sql = "SELECT DISTINCT c.* FROM conversations c LEFT JOIN messages m ON m.conversationId = c.id WHERE c.title LIKE '%' || ? || '%' OR m.content LIKE '%' || ? || '%' ORDER BY c.updatedAt DESC LIMIT 50";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 2);
    int _argIndex = 1;
    _statement.bindString(_argIndex, q);
    _argIndex = 2;
    _statement.bindString(_argIndex, q);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<ConversationEntity>>() {
      @Override
      @NonNull
      public List<ConversationEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfProject = CursorUtil.getColumnIndexOrThrow(_cursor, "project");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updatedAt");
          final int _cursorIndexOfGoalText = CursorUtil.getColumnIndexOrThrow(_cursor, "goalText");
          final int _cursorIndexOfGoalPhase = CursorUtil.getColumnIndexOrThrow(_cursor, "goalPhase");
          final int _cursorIndexOfGoalStartedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "goalStartedAt");
          final int _cursorIndexOfGoalUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "goalUpdatedAt");
          final int _cursorIndexOfGoalNote = CursorUtil.getColumnIndexOrThrow(_cursor, "goalNote");
          final int _cursorIndexOfWorkPaneTab = CursorUtil.getColumnIndexOrThrow(_cursor, "workPaneTab");
          final int _cursorIndexOfWorkPaneVisible = CursorUtil.getColumnIndexOrThrow(_cursor, "workPaneVisible");
          final int _cursorIndexOfTerminalDockVisible = CursorUtil.getColumnIndexOrThrow(_cursor, "terminalDockVisible");
          final int _cursorIndexOfTerminalDockTall = CursorUtil.getColumnIndexOrThrow(_cursor, "terminalDockTall");
          final int _cursorIndexOfSelectedFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "selectedFilePath");
          final int _cursorIndexOfSelectedDiffPath = CursorUtil.getColumnIndexOrThrow(_cursor, "selectedDiffPath");
          final int _cursorIndexOfBrowserUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "browserUrl");
          final int _cursorIndexOfFileCurrentGuestPath = CursorUtil.getColumnIndexOrThrow(_cursor, "fileCurrentGuestPath");
          final int _cursorIndexOfFileViewingGuestPath = CursorUtil.getColumnIndexOrThrow(_cursor, "fileViewingGuestPath");
          final int _cursorIndexOfRolloutPath = CursorUtil.getColumnIndexOrThrow(_cursor, "rolloutPath");
          final int _cursorIndexOfSandboxPolicy = CursorUtil.getColumnIndexOrThrow(_cursor, "sandboxPolicy");
          final int _cursorIndexOfModel = CursorUtil.getColumnIndexOrThrow(_cursor, "model");
          final int _cursorIndexOfReasoningEffort = CursorUtil.getColumnIndexOrThrow(_cursor, "reasoningEffort");
          final int _cursorIndexOfMemoryMode = CursorUtil.getColumnIndexOrThrow(_cursor, "memoryMode");
          final int _cursorIndexOfFirstUserMessage = CursorUtil.getColumnIndexOrThrow(_cursor, "firstUserMessage");
          final int _cursorIndexOfArchived = CursorUtil.getColumnIndexOrThrow(_cursor, "archived");
          final int _cursorIndexOfSessionId = CursorUtil.getColumnIndexOrThrow(_cursor, "sessionId");
          final List<ConversationEntity> _result = new ArrayList<ConversationEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final ConversationEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpProject;
            _tmpProject = _cursor.getString(_cursorIndexOfProject);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            final long _tmpUpdatedAt;
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
            final String _tmpGoalText;
            _tmpGoalText = _cursor.getString(_cursorIndexOfGoalText);
            final String _tmpGoalPhase;
            _tmpGoalPhase = _cursor.getString(_cursorIndexOfGoalPhase);
            final long _tmpGoalStartedAt;
            _tmpGoalStartedAt = _cursor.getLong(_cursorIndexOfGoalStartedAt);
            final long _tmpGoalUpdatedAt;
            _tmpGoalUpdatedAt = _cursor.getLong(_cursorIndexOfGoalUpdatedAt);
            final String _tmpGoalNote;
            _tmpGoalNote = _cursor.getString(_cursorIndexOfGoalNote);
            final String _tmpWorkPaneTab;
            _tmpWorkPaneTab = _cursor.getString(_cursorIndexOfWorkPaneTab);
            final boolean _tmpWorkPaneVisible;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfWorkPaneVisible);
            _tmpWorkPaneVisible = _tmp != 0;
            final boolean _tmpTerminalDockVisible;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfTerminalDockVisible);
            _tmpTerminalDockVisible = _tmp_1 != 0;
            final boolean _tmpTerminalDockTall;
            final int _tmp_2;
            _tmp_2 = _cursor.getInt(_cursorIndexOfTerminalDockTall);
            _tmpTerminalDockTall = _tmp_2 != 0;
            final String _tmpSelectedFilePath;
            _tmpSelectedFilePath = _cursor.getString(_cursorIndexOfSelectedFilePath);
            final String _tmpSelectedDiffPath;
            _tmpSelectedDiffPath = _cursor.getString(_cursorIndexOfSelectedDiffPath);
            final String _tmpBrowserUrl;
            _tmpBrowserUrl = _cursor.getString(_cursorIndexOfBrowserUrl);
            final String _tmpFileCurrentGuestPath;
            _tmpFileCurrentGuestPath = _cursor.getString(_cursorIndexOfFileCurrentGuestPath);
            final String _tmpFileViewingGuestPath;
            _tmpFileViewingGuestPath = _cursor.getString(_cursorIndexOfFileViewingGuestPath);
            final String _tmpRolloutPath;
            _tmpRolloutPath = _cursor.getString(_cursorIndexOfRolloutPath);
            final String _tmpSandboxPolicy;
            _tmpSandboxPolicy = _cursor.getString(_cursorIndexOfSandboxPolicy);
            final String _tmpModel;
            _tmpModel = _cursor.getString(_cursorIndexOfModel);
            final String _tmpReasoningEffort;
            _tmpReasoningEffort = _cursor.getString(_cursorIndexOfReasoningEffort);
            final String _tmpMemoryMode;
            _tmpMemoryMode = _cursor.getString(_cursorIndexOfMemoryMode);
            final String _tmpFirstUserMessage;
            _tmpFirstUserMessage = _cursor.getString(_cursorIndexOfFirstUserMessage);
            final boolean _tmpArchived;
            final int _tmp_3;
            _tmp_3 = _cursor.getInt(_cursorIndexOfArchived);
            _tmpArchived = _tmp_3 != 0;
            final String _tmpSessionId;
            _tmpSessionId = _cursor.getString(_cursorIndexOfSessionId);
            _item = new ConversationEntity(_tmpId,_tmpProject,_tmpTitle,_tmpCreatedAt,_tmpUpdatedAt,_tmpGoalText,_tmpGoalPhase,_tmpGoalStartedAt,_tmpGoalUpdatedAt,_tmpGoalNote,_tmpWorkPaneTab,_tmpWorkPaneVisible,_tmpTerminalDockVisible,_tmpTerminalDockTall,_tmpSelectedFilePath,_tmpSelectedDiffPath,_tmpBrowserUrl,_tmpFileCurrentGuestPath,_tmpFileViewingGuestPath,_tmpRolloutPath,_tmpSandboxPolicy,_tmpModel,_tmpReasoningEffort,_tmpMemoryMode,_tmpFirstUserMessage,_tmpArchived,_tmpSessionId);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object getConversation(final long id,
      final Continuation<? super ConversationEntity> $completion) {
    final String _sql = "SELECT * FROM conversations WHERE id = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, id);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<ConversationEntity>() {
      @Override
      @Nullable
      public ConversationEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfProject = CursorUtil.getColumnIndexOrThrow(_cursor, "project");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updatedAt");
          final int _cursorIndexOfGoalText = CursorUtil.getColumnIndexOrThrow(_cursor, "goalText");
          final int _cursorIndexOfGoalPhase = CursorUtil.getColumnIndexOrThrow(_cursor, "goalPhase");
          final int _cursorIndexOfGoalStartedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "goalStartedAt");
          final int _cursorIndexOfGoalUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "goalUpdatedAt");
          final int _cursorIndexOfGoalNote = CursorUtil.getColumnIndexOrThrow(_cursor, "goalNote");
          final int _cursorIndexOfWorkPaneTab = CursorUtil.getColumnIndexOrThrow(_cursor, "workPaneTab");
          final int _cursorIndexOfWorkPaneVisible = CursorUtil.getColumnIndexOrThrow(_cursor, "workPaneVisible");
          final int _cursorIndexOfTerminalDockVisible = CursorUtil.getColumnIndexOrThrow(_cursor, "terminalDockVisible");
          final int _cursorIndexOfTerminalDockTall = CursorUtil.getColumnIndexOrThrow(_cursor, "terminalDockTall");
          final int _cursorIndexOfSelectedFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "selectedFilePath");
          final int _cursorIndexOfSelectedDiffPath = CursorUtil.getColumnIndexOrThrow(_cursor, "selectedDiffPath");
          final int _cursorIndexOfBrowserUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "browserUrl");
          final int _cursorIndexOfFileCurrentGuestPath = CursorUtil.getColumnIndexOrThrow(_cursor, "fileCurrentGuestPath");
          final int _cursorIndexOfFileViewingGuestPath = CursorUtil.getColumnIndexOrThrow(_cursor, "fileViewingGuestPath");
          final int _cursorIndexOfRolloutPath = CursorUtil.getColumnIndexOrThrow(_cursor, "rolloutPath");
          final int _cursorIndexOfSandboxPolicy = CursorUtil.getColumnIndexOrThrow(_cursor, "sandboxPolicy");
          final int _cursorIndexOfModel = CursorUtil.getColumnIndexOrThrow(_cursor, "model");
          final int _cursorIndexOfReasoningEffort = CursorUtil.getColumnIndexOrThrow(_cursor, "reasoningEffort");
          final int _cursorIndexOfMemoryMode = CursorUtil.getColumnIndexOrThrow(_cursor, "memoryMode");
          final int _cursorIndexOfFirstUserMessage = CursorUtil.getColumnIndexOrThrow(_cursor, "firstUserMessage");
          final int _cursorIndexOfArchived = CursorUtil.getColumnIndexOrThrow(_cursor, "archived");
          final int _cursorIndexOfSessionId = CursorUtil.getColumnIndexOrThrow(_cursor, "sessionId");
          final ConversationEntity _result;
          if (_cursor.moveToFirst()) {
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpProject;
            _tmpProject = _cursor.getString(_cursorIndexOfProject);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            final long _tmpUpdatedAt;
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
            final String _tmpGoalText;
            _tmpGoalText = _cursor.getString(_cursorIndexOfGoalText);
            final String _tmpGoalPhase;
            _tmpGoalPhase = _cursor.getString(_cursorIndexOfGoalPhase);
            final long _tmpGoalStartedAt;
            _tmpGoalStartedAt = _cursor.getLong(_cursorIndexOfGoalStartedAt);
            final long _tmpGoalUpdatedAt;
            _tmpGoalUpdatedAt = _cursor.getLong(_cursorIndexOfGoalUpdatedAt);
            final String _tmpGoalNote;
            _tmpGoalNote = _cursor.getString(_cursorIndexOfGoalNote);
            final String _tmpWorkPaneTab;
            _tmpWorkPaneTab = _cursor.getString(_cursorIndexOfWorkPaneTab);
            final boolean _tmpWorkPaneVisible;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfWorkPaneVisible);
            _tmpWorkPaneVisible = _tmp != 0;
            final boolean _tmpTerminalDockVisible;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfTerminalDockVisible);
            _tmpTerminalDockVisible = _tmp_1 != 0;
            final boolean _tmpTerminalDockTall;
            final int _tmp_2;
            _tmp_2 = _cursor.getInt(_cursorIndexOfTerminalDockTall);
            _tmpTerminalDockTall = _tmp_2 != 0;
            final String _tmpSelectedFilePath;
            _tmpSelectedFilePath = _cursor.getString(_cursorIndexOfSelectedFilePath);
            final String _tmpSelectedDiffPath;
            _tmpSelectedDiffPath = _cursor.getString(_cursorIndexOfSelectedDiffPath);
            final String _tmpBrowserUrl;
            _tmpBrowserUrl = _cursor.getString(_cursorIndexOfBrowserUrl);
            final String _tmpFileCurrentGuestPath;
            _tmpFileCurrentGuestPath = _cursor.getString(_cursorIndexOfFileCurrentGuestPath);
            final String _tmpFileViewingGuestPath;
            _tmpFileViewingGuestPath = _cursor.getString(_cursorIndexOfFileViewingGuestPath);
            final String _tmpRolloutPath;
            _tmpRolloutPath = _cursor.getString(_cursorIndexOfRolloutPath);
            final String _tmpSandboxPolicy;
            _tmpSandboxPolicy = _cursor.getString(_cursorIndexOfSandboxPolicy);
            final String _tmpModel;
            _tmpModel = _cursor.getString(_cursorIndexOfModel);
            final String _tmpReasoningEffort;
            _tmpReasoningEffort = _cursor.getString(_cursorIndexOfReasoningEffort);
            final String _tmpMemoryMode;
            _tmpMemoryMode = _cursor.getString(_cursorIndexOfMemoryMode);
            final String _tmpFirstUserMessage;
            _tmpFirstUserMessage = _cursor.getString(_cursorIndexOfFirstUserMessage);
            final boolean _tmpArchived;
            final int _tmp_3;
            _tmp_3 = _cursor.getInt(_cursorIndexOfArchived);
            _tmpArchived = _tmp_3 != 0;
            final String _tmpSessionId;
            _tmpSessionId = _cursor.getString(_cursorIndexOfSessionId);
            _result = new ConversationEntity(_tmpId,_tmpProject,_tmpTitle,_tmpCreatedAt,_tmpUpdatedAt,_tmpGoalText,_tmpGoalPhase,_tmpGoalStartedAt,_tmpGoalUpdatedAt,_tmpGoalNote,_tmpWorkPaneTab,_tmpWorkPaneVisible,_tmpTerminalDockVisible,_tmpTerminalDockTall,_tmpSelectedFilePath,_tmpSelectedDiffPath,_tmpBrowserUrl,_tmpFileCurrentGuestPath,_tmpFileViewingGuestPath,_tmpRolloutPath,_tmpSandboxPolicy,_tmpModel,_tmpReasoningEffort,_tmpMemoryMode,_tmpFirstUserMessage,_tmpArchived,_tmpSessionId);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object messagesFor(final long conversationId,
      final Continuation<? super List<MessageEntity>> $completion) {
    final String _sql = "SELECT * FROM messages WHERE conversationId = ? ORDER BY id ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, conversationId);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<MessageEntity>>() {
      @Override
      @NonNull
      public List<MessageEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfConversationId = CursorUtil.getColumnIndexOrThrow(_cursor, "conversationId");
          final int _cursorIndexOfRole = CursorUtil.getColumnIndexOrThrow(_cursor, "role");
          final int _cursorIndexOfContent = CursorUtil.getColumnIndexOrThrow(_cursor, "content");
          final int _cursorIndexOfToolName = CursorUtil.getColumnIndexOrThrow(_cursor, "toolName");
          final int _cursorIndexOfToolArgs = CursorUtil.getColumnIndexOrThrow(_cursor, "toolArgs");
          final int _cursorIndexOfToolError = CursorUtil.getColumnIndexOrThrow(_cursor, "toolError");
          final int _cursorIndexOfApprovalRisk = CursorUtil.getColumnIndexOrThrow(_cursor, "approvalRisk");
          final int _cursorIndexOfApprovalModeLabel = CursorUtil.getColumnIndexOrThrow(_cursor, "approvalModeLabel");
          final int _cursorIndexOfApprovalRiskDescription = CursorUtil.getColumnIndexOrThrow(_cursor, "approvalRiskDescription");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final List<MessageEntity> _result = new ArrayList<MessageEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final MessageEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final long _tmpConversationId;
            _tmpConversationId = _cursor.getLong(_cursorIndexOfConversationId);
            final String _tmpRole;
            _tmpRole = _cursor.getString(_cursorIndexOfRole);
            final String _tmpContent;
            _tmpContent = _cursor.getString(_cursorIndexOfContent);
            final String _tmpToolName;
            if (_cursor.isNull(_cursorIndexOfToolName)) {
              _tmpToolName = null;
            } else {
              _tmpToolName = _cursor.getString(_cursorIndexOfToolName);
            }
            final String _tmpToolArgs;
            _tmpToolArgs = _cursor.getString(_cursorIndexOfToolArgs);
            final boolean _tmpToolError;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfToolError);
            _tmpToolError = _tmp != 0;
            final String _tmpApprovalRisk;
            _tmpApprovalRisk = _cursor.getString(_cursorIndexOfApprovalRisk);
            final String _tmpApprovalModeLabel;
            _tmpApprovalModeLabel = _cursor.getString(_cursorIndexOfApprovalModeLabel);
            final String _tmpApprovalRiskDescription;
            _tmpApprovalRiskDescription = _cursor.getString(_cursorIndexOfApprovalRiskDescription);
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            _item = new MessageEntity(_tmpId,_tmpConversationId,_tmpRole,_tmpContent,_tmpToolName,_tmpToolArgs,_tmpToolError,_tmpApprovalRisk,_tmpApprovalModeLabel,_tmpApprovalRiskDescription,_tmpCreatedAt);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object conversationsByArchived(final boolean archived,
      final Continuation<? super List<ConversationEntity>> $completion) {
    final String _sql = "SELECT * FROM conversations WHERE archived = ? ORDER BY updatedAt DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    final int _tmp = archived ? 1 : 0;
    _statement.bindLong(_argIndex, _tmp);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<ConversationEntity>>() {
      @Override
      @NonNull
      public List<ConversationEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfProject = CursorUtil.getColumnIndexOrThrow(_cursor, "project");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updatedAt");
          final int _cursorIndexOfGoalText = CursorUtil.getColumnIndexOrThrow(_cursor, "goalText");
          final int _cursorIndexOfGoalPhase = CursorUtil.getColumnIndexOrThrow(_cursor, "goalPhase");
          final int _cursorIndexOfGoalStartedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "goalStartedAt");
          final int _cursorIndexOfGoalUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "goalUpdatedAt");
          final int _cursorIndexOfGoalNote = CursorUtil.getColumnIndexOrThrow(_cursor, "goalNote");
          final int _cursorIndexOfWorkPaneTab = CursorUtil.getColumnIndexOrThrow(_cursor, "workPaneTab");
          final int _cursorIndexOfWorkPaneVisible = CursorUtil.getColumnIndexOrThrow(_cursor, "workPaneVisible");
          final int _cursorIndexOfTerminalDockVisible = CursorUtil.getColumnIndexOrThrow(_cursor, "terminalDockVisible");
          final int _cursorIndexOfTerminalDockTall = CursorUtil.getColumnIndexOrThrow(_cursor, "terminalDockTall");
          final int _cursorIndexOfSelectedFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "selectedFilePath");
          final int _cursorIndexOfSelectedDiffPath = CursorUtil.getColumnIndexOrThrow(_cursor, "selectedDiffPath");
          final int _cursorIndexOfBrowserUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "browserUrl");
          final int _cursorIndexOfFileCurrentGuestPath = CursorUtil.getColumnIndexOrThrow(_cursor, "fileCurrentGuestPath");
          final int _cursorIndexOfFileViewingGuestPath = CursorUtil.getColumnIndexOrThrow(_cursor, "fileViewingGuestPath");
          final int _cursorIndexOfRolloutPath = CursorUtil.getColumnIndexOrThrow(_cursor, "rolloutPath");
          final int _cursorIndexOfSandboxPolicy = CursorUtil.getColumnIndexOrThrow(_cursor, "sandboxPolicy");
          final int _cursorIndexOfModel = CursorUtil.getColumnIndexOrThrow(_cursor, "model");
          final int _cursorIndexOfReasoningEffort = CursorUtil.getColumnIndexOrThrow(_cursor, "reasoningEffort");
          final int _cursorIndexOfMemoryMode = CursorUtil.getColumnIndexOrThrow(_cursor, "memoryMode");
          final int _cursorIndexOfFirstUserMessage = CursorUtil.getColumnIndexOrThrow(_cursor, "firstUserMessage");
          final int _cursorIndexOfArchived = CursorUtil.getColumnIndexOrThrow(_cursor, "archived");
          final int _cursorIndexOfSessionId = CursorUtil.getColumnIndexOrThrow(_cursor, "sessionId");
          final List<ConversationEntity> _result = new ArrayList<ConversationEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final ConversationEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpProject;
            _tmpProject = _cursor.getString(_cursorIndexOfProject);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            final long _tmpUpdatedAt;
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
            final String _tmpGoalText;
            _tmpGoalText = _cursor.getString(_cursorIndexOfGoalText);
            final String _tmpGoalPhase;
            _tmpGoalPhase = _cursor.getString(_cursorIndexOfGoalPhase);
            final long _tmpGoalStartedAt;
            _tmpGoalStartedAt = _cursor.getLong(_cursorIndexOfGoalStartedAt);
            final long _tmpGoalUpdatedAt;
            _tmpGoalUpdatedAt = _cursor.getLong(_cursorIndexOfGoalUpdatedAt);
            final String _tmpGoalNote;
            _tmpGoalNote = _cursor.getString(_cursorIndexOfGoalNote);
            final String _tmpWorkPaneTab;
            _tmpWorkPaneTab = _cursor.getString(_cursorIndexOfWorkPaneTab);
            final boolean _tmpWorkPaneVisible;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfWorkPaneVisible);
            _tmpWorkPaneVisible = _tmp_1 != 0;
            final boolean _tmpTerminalDockVisible;
            final int _tmp_2;
            _tmp_2 = _cursor.getInt(_cursorIndexOfTerminalDockVisible);
            _tmpTerminalDockVisible = _tmp_2 != 0;
            final boolean _tmpTerminalDockTall;
            final int _tmp_3;
            _tmp_3 = _cursor.getInt(_cursorIndexOfTerminalDockTall);
            _tmpTerminalDockTall = _tmp_3 != 0;
            final String _tmpSelectedFilePath;
            _tmpSelectedFilePath = _cursor.getString(_cursorIndexOfSelectedFilePath);
            final String _tmpSelectedDiffPath;
            _tmpSelectedDiffPath = _cursor.getString(_cursorIndexOfSelectedDiffPath);
            final String _tmpBrowserUrl;
            _tmpBrowserUrl = _cursor.getString(_cursorIndexOfBrowserUrl);
            final String _tmpFileCurrentGuestPath;
            _tmpFileCurrentGuestPath = _cursor.getString(_cursorIndexOfFileCurrentGuestPath);
            final String _tmpFileViewingGuestPath;
            _tmpFileViewingGuestPath = _cursor.getString(_cursorIndexOfFileViewingGuestPath);
            final String _tmpRolloutPath;
            _tmpRolloutPath = _cursor.getString(_cursorIndexOfRolloutPath);
            final String _tmpSandboxPolicy;
            _tmpSandboxPolicy = _cursor.getString(_cursorIndexOfSandboxPolicy);
            final String _tmpModel;
            _tmpModel = _cursor.getString(_cursorIndexOfModel);
            final String _tmpReasoningEffort;
            _tmpReasoningEffort = _cursor.getString(_cursorIndexOfReasoningEffort);
            final String _tmpMemoryMode;
            _tmpMemoryMode = _cursor.getString(_cursorIndexOfMemoryMode);
            final String _tmpFirstUserMessage;
            _tmpFirstUserMessage = _cursor.getString(_cursorIndexOfFirstUserMessage);
            final boolean _tmpArchived;
            final int _tmp_4;
            _tmp_4 = _cursor.getInt(_cursorIndexOfArchived);
            _tmpArchived = _tmp_4 != 0;
            final String _tmpSessionId;
            _tmpSessionId = _cursor.getString(_cursorIndexOfSessionId);
            _item = new ConversationEntity(_tmpId,_tmpProject,_tmpTitle,_tmpCreatedAt,_tmpUpdatedAt,_tmpGoalText,_tmpGoalPhase,_tmpGoalStartedAt,_tmpGoalUpdatedAt,_tmpGoalNote,_tmpWorkPaneTab,_tmpWorkPaneVisible,_tmpTerminalDockVisible,_tmpTerminalDockTall,_tmpSelectedFilePath,_tmpSelectedDiffPath,_tmpBrowserUrl,_tmpFileCurrentGuestPath,_tmpFileViewingGuestPath,_tmpRolloutPath,_tmpSandboxPolicy,_tmpModel,_tmpReasoningEffort,_tmpMemoryMode,_tmpFirstUserMessage,_tmpArchived,_tmpSessionId);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object goalsFor(final long conversationId,
      final Continuation<? super List<ThreadGoalEntity>> $completion) {
    final String _sql = "SELECT * FROM thread_goals WHERE conversationId = ? ORDER BY createdAtMs ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, conversationId);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<ThreadGoalEntity>>() {
      @Override
      @NonNull
      public List<ThreadGoalEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfConversationId = CursorUtil.getColumnIndexOrThrow(_cursor, "conversationId");
          final int _cursorIndexOfGoalId = CursorUtil.getColumnIndexOrThrow(_cursor, "goalId");
          final int _cursorIndexOfObjective = CursorUtil.getColumnIndexOrThrow(_cursor, "objective");
          final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
          final int _cursorIndexOfTokenBudget = CursorUtil.getColumnIndexOrThrow(_cursor, "tokenBudget");
          final int _cursorIndexOfTokensUsed = CursorUtil.getColumnIndexOrThrow(_cursor, "tokensUsed");
          final int _cursorIndexOfTimeUsedSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "timeUsedSeconds");
          final int _cursorIndexOfCreatedAtMs = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAtMs");
          final int _cursorIndexOfUpdatedAtMs = CursorUtil.getColumnIndexOrThrow(_cursor, "updatedAtMs");
          final List<ThreadGoalEntity> _result = new ArrayList<ThreadGoalEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final ThreadGoalEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final long _tmpConversationId;
            _tmpConversationId = _cursor.getLong(_cursorIndexOfConversationId);
            final String _tmpGoalId;
            _tmpGoalId = _cursor.getString(_cursorIndexOfGoalId);
            final String _tmpObjective;
            _tmpObjective = _cursor.getString(_cursorIndexOfObjective);
            final String _tmpStatus;
            _tmpStatus = _cursor.getString(_cursorIndexOfStatus);
            final Integer _tmpTokenBudget;
            if (_cursor.isNull(_cursorIndexOfTokenBudget)) {
              _tmpTokenBudget = null;
            } else {
              _tmpTokenBudget = _cursor.getInt(_cursorIndexOfTokenBudget);
            }
            final int _tmpTokensUsed;
            _tmpTokensUsed = _cursor.getInt(_cursorIndexOfTokensUsed);
            final int _tmpTimeUsedSeconds;
            _tmpTimeUsedSeconds = _cursor.getInt(_cursorIndexOfTimeUsedSeconds);
            final long _tmpCreatedAtMs;
            _tmpCreatedAtMs = _cursor.getLong(_cursorIndexOfCreatedAtMs);
            final long _tmpUpdatedAtMs;
            _tmpUpdatedAtMs = _cursor.getLong(_cursorIndexOfUpdatedAtMs);
            _item = new ThreadGoalEntity(_tmpId,_tmpConversationId,_tmpGoalId,_tmpObjective,_tmpStatus,_tmpTokenBudget,_tmpTokensUsed,_tmpTimeUsedSeconds,_tmpCreatedAtMs,_tmpUpdatedAtMs);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object childEdges(final long parentId,
      final Continuation<? super List<ThreadSpawnEdgeEntity>> $completion) {
    final String _sql = "SELECT * FROM thread_spawn_edges WHERE parentConversationId = ? ORDER BY createdAtMs ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, parentId);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<ThreadSpawnEdgeEntity>>() {
      @Override
      @NonNull
      public List<ThreadSpawnEdgeEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfParentConversationId = CursorUtil.getColumnIndexOrThrow(_cursor, "parentConversationId");
          final int _cursorIndexOfChildConversationId = CursorUtil.getColumnIndexOrThrow(_cursor, "childConversationId");
          final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
          final int _cursorIndexOfCreatedAtMs = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAtMs");
          final List<ThreadSpawnEdgeEntity> _result = new ArrayList<ThreadSpawnEdgeEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final ThreadSpawnEdgeEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final long _tmpParentConversationId;
            _tmpParentConversationId = _cursor.getLong(_cursorIndexOfParentConversationId);
            final long _tmpChildConversationId;
            _tmpChildConversationId = _cursor.getLong(_cursorIndexOfChildConversationId);
            final String _tmpStatus;
            _tmpStatus = _cursor.getString(_cursorIndexOfStatus);
            final long _tmpCreatedAtMs;
            _tmpCreatedAtMs = _cursor.getLong(_cursorIndexOfCreatedAtMs);
            _item = new ThreadSpawnEdgeEntity(_tmpId,_tmpParentConversationId,_tmpChildConversationId,_tmpStatus,_tmpCreatedAtMs);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object logsFor(final long conversationId, final int limit,
      final Continuation<? super List<LogEntity>> $completion) {
    final String _sql = "SELECT * FROM logs WHERE conversationId = ? ORDER BY ts DESC, tsNanos DESC, id DESC LIMIT ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 2);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, conversationId);
    _argIndex = 2;
    _statement.bindLong(_argIndex, limit);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<LogEntity>>() {
      @Override
      @NonNull
      public List<LogEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfConversationId = CursorUtil.getColumnIndexOrThrow(_cursor, "conversationId");
          final int _cursorIndexOfTs = CursorUtil.getColumnIndexOrThrow(_cursor, "ts");
          final int _cursorIndexOfTsNanos = CursorUtil.getColumnIndexOrThrow(_cursor, "tsNanos");
          final int _cursorIndexOfProcessUuid = CursorUtil.getColumnIndexOrThrow(_cursor, "processUuid");
          final int _cursorIndexOfEstimatedBytes = CursorUtil.getColumnIndexOrThrow(_cursor, "estimatedBytes");
          final int _cursorIndexOfContent = CursorUtil.getColumnIndexOrThrow(_cursor, "content");
          final int _cursorIndexOfLevel = CursorUtil.getColumnIndexOrThrow(_cursor, "level");
          final List<LogEntity> _result = new ArrayList<LogEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final LogEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final long _tmpConversationId;
            _tmpConversationId = _cursor.getLong(_cursorIndexOfConversationId);
            final long _tmpTs;
            _tmpTs = _cursor.getLong(_cursorIndexOfTs);
            final int _tmpTsNanos;
            _tmpTsNanos = _cursor.getInt(_cursorIndexOfTsNanos);
            final String _tmpProcessUuid;
            if (_cursor.isNull(_cursorIndexOfProcessUuid)) {
              _tmpProcessUuid = null;
            } else {
              _tmpProcessUuid = _cursor.getString(_cursorIndexOfProcessUuid);
            }
            final int _tmpEstimatedBytes;
            _tmpEstimatedBytes = _cursor.getInt(_cursorIndexOfEstimatedBytes);
            final String _tmpContent;
            _tmpContent = _cursor.getString(_cursorIndexOfContent);
            final String _tmpLevel;
            _tmpLevel = _cursor.getString(_cursorIndexOfLevel);
            _item = new LogEntity(_tmpId,_tmpConversationId,_tmpTs,_tmpTsNanos,_tmpProcessUuid,_tmpEstimatedBytes,_tmpContent,_tmpLevel);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object searchLogs(final long conversationId, final String q,
      final Continuation<? super List<LogEntity>> $completion) {
    final String _sql = "SELECT * FROM logs WHERE conversationId = ? AND content LIKE '%' || ? || '%' ORDER BY ts DESC, tsNanos DESC, id DESC LIMIT 100";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 2);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, conversationId);
    _argIndex = 2;
    _statement.bindString(_argIndex, q);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<LogEntity>>() {
      @Override
      @NonNull
      public List<LogEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfConversationId = CursorUtil.getColumnIndexOrThrow(_cursor, "conversationId");
          final int _cursorIndexOfTs = CursorUtil.getColumnIndexOrThrow(_cursor, "ts");
          final int _cursorIndexOfTsNanos = CursorUtil.getColumnIndexOrThrow(_cursor, "tsNanos");
          final int _cursorIndexOfProcessUuid = CursorUtil.getColumnIndexOrThrow(_cursor, "processUuid");
          final int _cursorIndexOfEstimatedBytes = CursorUtil.getColumnIndexOrThrow(_cursor, "estimatedBytes");
          final int _cursorIndexOfContent = CursorUtil.getColumnIndexOrThrow(_cursor, "content");
          final int _cursorIndexOfLevel = CursorUtil.getColumnIndexOrThrow(_cursor, "level");
          final List<LogEntity> _result = new ArrayList<LogEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final LogEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final long _tmpConversationId;
            _tmpConversationId = _cursor.getLong(_cursorIndexOfConversationId);
            final long _tmpTs;
            _tmpTs = _cursor.getLong(_cursorIndexOfTs);
            final int _tmpTsNanos;
            _tmpTsNanos = _cursor.getInt(_cursorIndexOfTsNanos);
            final String _tmpProcessUuid;
            if (_cursor.isNull(_cursorIndexOfProcessUuid)) {
              _tmpProcessUuid = null;
            } else {
              _tmpProcessUuid = _cursor.getString(_cursorIndexOfProcessUuid);
            }
            final int _tmpEstimatedBytes;
            _tmpEstimatedBytes = _cursor.getInt(_cursorIndexOfEstimatedBytes);
            final String _tmpContent;
            _tmpContent = _cursor.getString(_cursorIndexOfContent);
            final String _tmpLevel;
            _tmpLevel = _cursor.getString(_cursorIndexOfLevel);
            _item = new LogEntity(_tmpId,_tmpConversationId,_tmpTs,_tmpTsNanos,_tmpProcessUuid,_tmpEstimatedBytes,_tmpContent,_tmpLevel);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<ProviderEntity>> observeProviders() {
    final String _sql = "SELECT * FROM providers ORDER BY isPrimary DESC, name ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"providers"}, new Callable<List<ProviderEntity>>() {
      @Override
      @NonNull
      public List<ProviderEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfKind = CursorUtil.getColumnIndexOrThrow(_cursor, "kind");
          final int _cursorIndexOfBaseUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "baseUrl");
          final int _cursorIndexOfApiKey = CursorUtil.getColumnIndexOrThrow(_cursor, "apiKey");
          final int _cursorIndexOfApiKeyRequired = CursorUtil.getColumnIndexOrThrow(_cursor, "apiKeyRequired");
          final int _cursorIndexOfEnabled = CursorUtil.getColumnIndexOrThrow(_cursor, "enabled");
          final int _cursorIndexOfSource = CursorUtil.getColumnIndexOrThrow(_cursor, "source");
          final int _cursorIndexOfRequestMaxRetries = CursorUtil.getColumnIndexOrThrow(_cursor, "requestMaxRetries");
          final int _cursorIndexOfStreamMaxRetries = CursorUtil.getColumnIndexOrThrow(_cursor, "streamMaxRetries");
          final int _cursorIndexOfStreamIdleTimeoutMs = CursorUtil.getColumnIndexOrThrow(_cursor, "streamIdleTimeoutMs");
          final int _cursorIndexOfHttpHeadersJson = CursorUtil.getColumnIndexOrThrow(_cursor, "httpHeadersJson");
          final int _cursorIndexOfModelsJson = CursorUtil.getColumnIndexOrThrow(_cursor, "modelsJson");
          final int _cursorIndexOfIsPrimary = CursorUtil.getColumnIndexOrThrow(_cursor, "isPrimary");
          final int _cursorIndexOfCreatedAtMs = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAtMs");
          final int _cursorIndexOfUpdatedAtMs = CursorUtil.getColumnIndexOrThrow(_cursor, "updatedAtMs");
          final List<ProviderEntity> _result = new ArrayList<ProviderEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final ProviderEntity _item;
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpName;
            _tmpName = _cursor.getString(_cursorIndexOfName);
            final String _tmpKind;
            _tmpKind = _cursor.getString(_cursorIndexOfKind);
            final String _tmpBaseUrl;
            _tmpBaseUrl = _cursor.getString(_cursorIndexOfBaseUrl);
            final String _tmpApiKey;
            _tmpApiKey = _cursor.getString(_cursorIndexOfApiKey);
            final boolean _tmpApiKeyRequired;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfApiKeyRequired);
            _tmpApiKeyRequired = _tmp != 0;
            final boolean _tmpEnabled;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfEnabled);
            _tmpEnabled = _tmp_1 != 0;
            final String _tmpSource;
            _tmpSource = _cursor.getString(_cursorIndexOfSource);
            final int _tmpRequestMaxRetries;
            _tmpRequestMaxRetries = _cursor.getInt(_cursorIndexOfRequestMaxRetries);
            final int _tmpStreamMaxRetries;
            _tmpStreamMaxRetries = _cursor.getInt(_cursorIndexOfStreamMaxRetries);
            final long _tmpStreamIdleTimeoutMs;
            _tmpStreamIdleTimeoutMs = _cursor.getLong(_cursorIndexOfStreamIdleTimeoutMs);
            final String _tmpHttpHeadersJson;
            _tmpHttpHeadersJson = _cursor.getString(_cursorIndexOfHttpHeadersJson);
            final String _tmpModelsJson;
            _tmpModelsJson = _cursor.getString(_cursorIndexOfModelsJson);
            final boolean _tmpIsPrimary;
            final int _tmp_2;
            _tmp_2 = _cursor.getInt(_cursorIndexOfIsPrimary);
            _tmpIsPrimary = _tmp_2 != 0;
            final long _tmpCreatedAtMs;
            _tmpCreatedAtMs = _cursor.getLong(_cursorIndexOfCreatedAtMs);
            final long _tmpUpdatedAtMs;
            _tmpUpdatedAtMs = _cursor.getLong(_cursorIndexOfUpdatedAtMs);
            _item = new ProviderEntity(_tmpId,_tmpName,_tmpKind,_tmpBaseUrl,_tmpApiKey,_tmpApiKeyRequired,_tmpEnabled,_tmpSource,_tmpRequestMaxRetries,_tmpStreamMaxRetries,_tmpStreamIdleTimeoutMs,_tmpHttpHeadersJson,_tmpModelsJson,_tmpIsPrimary,_tmpCreatedAtMs,_tmpUpdatedAtMs);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Flow<ProviderEntity> observePrimaryProvider() {
    final String _sql = "SELECT * FROM providers WHERE isPrimary = 1 LIMIT 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"providers"}, new Callable<ProviderEntity>() {
      @Override
      @Nullable
      public ProviderEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfKind = CursorUtil.getColumnIndexOrThrow(_cursor, "kind");
          final int _cursorIndexOfBaseUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "baseUrl");
          final int _cursorIndexOfApiKey = CursorUtil.getColumnIndexOrThrow(_cursor, "apiKey");
          final int _cursorIndexOfApiKeyRequired = CursorUtil.getColumnIndexOrThrow(_cursor, "apiKeyRequired");
          final int _cursorIndexOfEnabled = CursorUtil.getColumnIndexOrThrow(_cursor, "enabled");
          final int _cursorIndexOfSource = CursorUtil.getColumnIndexOrThrow(_cursor, "source");
          final int _cursorIndexOfRequestMaxRetries = CursorUtil.getColumnIndexOrThrow(_cursor, "requestMaxRetries");
          final int _cursorIndexOfStreamMaxRetries = CursorUtil.getColumnIndexOrThrow(_cursor, "streamMaxRetries");
          final int _cursorIndexOfStreamIdleTimeoutMs = CursorUtil.getColumnIndexOrThrow(_cursor, "streamIdleTimeoutMs");
          final int _cursorIndexOfHttpHeadersJson = CursorUtil.getColumnIndexOrThrow(_cursor, "httpHeadersJson");
          final int _cursorIndexOfModelsJson = CursorUtil.getColumnIndexOrThrow(_cursor, "modelsJson");
          final int _cursorIndexOfIsPrimary = CursorUtil.getColumnIndexOrThrow(_cursor, "isPrimary");
          final int _cursorIndexOfCreatedAtMs = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAtMs");
          final int _cursorIndexOfUpdatedAtMs = CursorUtil.getColumnIndexOrThrow(_cursor, "updatedAtMs");
          final ProviderEntity _result;
          if (_cursor.moveToFirst()) {
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpName;
            _tmpName = _cursor.getString(_cursorIndexOfName);
            final String _tmpKind;
            _tmpKind = _cursor.getString(_cursorIndexOfKind);
            final String _tmpBaseUrl;
            _tmpBaseUrl = _cursor.getString(_cursorIndexOfBaseUrl);
            final String _tmpApiKey;
            _tmpApiKey = _cursor.getString(_cursorIndexOfApiKey);
            final boolean _tmpApiKeyRequired;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfApiKeyRequired);
            _tmpApiKeyRequired = _tmp != 0;
            final boolean _tmpEnabled;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfEnabled);
            _tmpEnabled = _tmp_1 != 0;
            final String _tmpSource;
            _tmpSource = _cursor.getString(_cursorIndexOfSource);
            final int _tmpRequestMaxRetries;
            _tmpRequestMaxRetries = _cursor.getInt(_cursorIndexOfRequestMaxRetries);
            final int _tmpStreamMaxRetries;
            _tmpStreamMaxRetries = _cursor.getInt(_cursorIndexOfStreamMaxRetries);
            final long _tmpStreamIdleTimeoutMs;
            _tmpStreamIdleTimeoutMs = _cursor.getLong(_cursorIndexOfStreamIdleTimeoutMs);
            final String _tmpHttpHeadersJson;
            _tmpHttpHeadersJson = _cursor.getString(_cursorIndexOfHttpHeadersJson);
            final String _tmpModelsJson;
            _tmpModelsJson = _cursor.getString(_cursorIndexOfModelsJson);
            final boolean _tmpIsPrimary;
            final int _tmp_2;
            _tmp_2 = _cursor.getInt(_cursorIndexOfIsPrimary);
            _tmpIsPrimary = _tmp_2 != 0;
            final long _tmpCreatedAtMs;
            _tmpCreatedAtMs = _cursor.getLong(_cursorIndexOfCreatedAtMs);
            final long _tmpUpdatedAtMs;
            _tmpUpdatedAtMs = _cursor.getLong(_cursorIndexOfUpdatedAtMs);
            _result = new ProviderEntity(_tmpId,_tmpName,_tmpKind,_tmpBaseUrl,_tmpApiKey,_tmpApiKeyRequired,_tmpEnabled,_tmpSource,_tmpRequestMaxRetries,_tmpStreamMaxRetries,_tmpStreamIdleTimeoutMs,_tmpHttpHeadersJson,_tmpModelsJson,_tmpIsPrimary,_tmpCreatedAtMs,_tmpUpdatedAtMs);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object getProvider(final String id,
      final Continuation<? super ProviderEntity> $completion) {
    final String _sql = "SELECT * FROM providers WHERE id = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, id);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<ProviderEntity>() {
      @Override
      @Nullable
      public ProviderEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfKind = CursorUtil.getColumnIndexOrThrow(_cursor, "kind");
          final int _cursorIndexOfBaseUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "baseUrl");
          final int _cursorIndexOfApiKey = CursorUtil.getColumnIndexOrThrow(_cursor, "apiKey");
          final int _cursorIndexOfApiKeyRequired = CursorUtil.getColumnIndexOrThrow(_cursor, "apiKeyRequired");
          final int _cursorIndexOfEnabled = CursorUtil.getColumnIndexOrThrow(_cursor, "enabled");
          final int _cursorIndexOfSource = CursorUtil.getColumnIndexOrThrow(_cursor, "source");
          final int _cursorIndexOfRequestMaxRetries = CursorUtil.getColumnIndexOrThrow(_cursor, "requestMaxRetries");
          final int _cursorIndexOfStreamMaxRetries = CursorUtil.getColumnIndexOrThrow(_cursor, "streamMaxRetries");
          final int _cursorIndexOfStreamIdleTimeoutMs = CursorUtil.getColumnIndexOrThrow(_cursor, "streamIdleTimeoutMs");
          final int _cursorIndexOfHttpHeadersJson = CursorUtil.getColumnIndexOrThrow(_cursor, "httpHeadersJson");
          final int _cursorIndexOfModelsJson = CursorUtil.getColumnIndexOrThrow(_cursor, "modelsJson");
          final int _cursorIndexOfIsPrimary = CursorUtil.getColumnIndexOrThrow(_cursor, "isPrimary");
          final int _cursorIndexOfCreatedAtMs = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAtMs");
          final int _cursorIndexOfUpdatedAtMs = CursorUtil.getColumnIndexOrThrow(_cursor, "updatedAtMs");
          final ProviderEntity _result;
          if (_cursor.moveToFirst()) {
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpName;
            _tmpName = _cursor.getString(_cursorIndexOfName);
            final String _tmpKind;
            _tmpKind = _cursor.getString(_cursorIndexOfKind);
            final String _tmpBaseUrl;
            _tmpBaseUrl = _cursor.getString(_cursorIndexOfBaseUrl);
            final String _tmpApiKey;
            _tmpApiKey = _cursor.getString(_cursorIndexOfApiKey);
            final boolean _tmpApiKeyRequired;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfApiKeyRequired);
            _tmpApiKeyRequired = _tmp != 0;
            final boolean _tmpEnabled;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfEnabled);
            _tmpEnabled = _tmp_1 != 0;
            final String _tmpSource;
            _tmpSource = _cursor.getString(_cursorIndexOfSource);
            final int _tmpRequestMaxRetries;
            _tmpRequestMaxRetries = _cursor.getInt(_cursorIndexOfRequestMaxRetries);
            final int _tmpStreamMaxRetries;
            _tmpStreamMaxRetries = _cursor.getInt(_cursorIndexOfStreamMaxRetries);
            final long _tmpStreamIdleTimeoutMs;
            _tmpStreamIdleTimeoutMs = _cursor.getLong(_cursorIndexOfStreamIdleTimeoutMs);
            final String _tmpHttpHeadersJson;
            _tmpHttpHeadersJson = _cursor.getString(_cursorIndexOfHttpHeadersJson);
            final String _tmpModelsJson;
            _tmpModelsJson = _cursor.getString(_cursorIndexOfModelsJson);
            final boolean _tmpIsPrimary;
            final int _tmp_2;
            _tmp_2 = _cursor.getInt(_cursorIndexOfIsPrimary);
            _tmpIsPrimary = _tmp_2 != 0;
            final long _tmpCreatedAtMs;
            _tmpCreatedAtMs = _cursor.getLong(_cursorIndexOfCreatedAtMs);
            final long _tmpUpdatedAtMs;
            _tmpUpdatedAtMs = _cursor.getLong(_cursorIndexOfUpdatedAtMs);
            _result = new ProviderEntity(_tmpId,_tmpName,_tmpKind,_tmpBaseUrl,_tmpApiKey,_tmpApiKeyRequired,_tmpEnabled,_tmpSource,_tmpRequestMaxRetries,_tmpStreamMaxRetries,_tmpStreamIdleTimeoutMs,_tmpHttpHeadersJson,_tmpModelsJson,_tmpIsPrimary,_tmpCreatedAtMs,_tmpUpdatedAtMs);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object allProviders(final Continuation<? super List<ProviderEntity>> $completion) {
    final String _sql = "SELECT * FROM providers";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<ProviderEntity>>() {
      @Override
      @NonNull
      public List<ProviderEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfKind = CursorUtil.getColumnIndexOrThrow(_cursor, "kind");
          final int _cursorIndexOfBaseUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "baseUrl");
          final int _cursorIndexOfApiKey = CursorUtil.getColumnIndexOrThrow(_cursor, "apiKey");
          final int _cursorIndexOfApiKeyRequired = CursorUtil.getColumnIndexOrThrow(_cursor, "apiKeyRequired");
          final int _cursorIndexOfEnabled = CursorUtil.getColumnIndexOrThrow(_cursor, "enabled");
          final int _cursorIndexOfSource = CursorUtil.getColumnIndexOrThrow(_cursor, "source");
          final int _cursorIndexOfRequestMaxRetries = CursorUtil.getColumnIndexOrThrow(_cursor, "requestMaxRetries");
          final int _cursorIndexOfStreamMaxRetries = CursorUtil.getColumnIndexOrThrow(_cursor, "streamMaxRetries");
          final int _cursorIndexOfStreamIdleTimeoutMs = CursorUtil.getColumnIndexOrThrow(_cursor, "streamIdleTimeoutMs");
          final int _cursorIndexOfHttpHeadersJson = CursorUtil.getColumnIndexOrThrow(_cursor, "httpHeadersJson");
          final int _cursorIndexOfModelsJson = CursorUtil.getColumnIndexOrThrow(_cursor, "modelsJson");
          final int _cursorIndexOfIsPrimary = CursorUtil.getColumnIndexOrThrow(_cursor, "isPrimary");
          final int _cursorIndexOfCreatedAtMs = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAtMs");
          final int _cursorIndexOfUpdatedAtMs = CursorUtil.getColumnIndexOrThrow(_cursor, "updatedAtMs");
          final List<ProviderEntity> _result = new ArrayList<ProviderEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final ProviderEntity _item;
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpName;
            _tmpName = _cursor.getString(_cursorIndexOfName);
            final String _tmpKind;
            _tmpKind = _cursor.getString(_cursorIndexOfKind);
            final String _tmpBaseUrl;
            _tmpBaseUrl = _cursor.getString(_cursorIndexOfBaseUrl);
            final String _tmpApiKey;
            _tmpApiKey = _cursor.getString(_cursorIndexOfApiKey);
            final boolean _tmpApiKeyRequired;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfApiKeyRequired);
            _tmpApiKeyRequired = _tmp != 0;
            final boolean _tmpEnabled;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfEnabled);
            _tmpEnabled = _tmp_1 != 0;
            final String _tmpSource;
            _tmpSource = _cursor.getString(_cursorIndexOfSource);
            final int _tmpRequestMaxRetries;
            _tmpRequestMaxRetries = _cursor.getInt(_cursorIndexOfRequestMaxRetries);
            final int _tmpStreamMaxRetries;
            _tmpStreamMaxRetries = _cursor.getInt(_cursorIndexOfStreamMaxRetries);
            final long _tmpStreamIdleTimeoutMs;
            _tmpStreamIdleTimeoutMs = _cursor.getLong(_cursorIndexOfStreamIdleTimeoutMs);
            final String _tmpHttpHeadersJson;
            _tmpHttpHeadersJson = _cursor.getString(_cursorIndexOfHttpHeadersJson);
            final String _tmpModelsJson;
            _tmpModelsJson = _cursor.getString(_cursorIndexOfModelsJson);
            final boolean _tmpIsPrimary;
            final int _tmp_2;
            _tmp_2 = _cursor.getInt(_cursorIndexOfIsPrimary);
            _tmpIsPrimary = _tmp_2 != 0;
            final long _tmpCreatedAtMs;
            _tmpCreatedAtMs = _cursor.getLong(_cursorIndexOfCreatedAtMs);
            final long _tmpUpdatedAtMs;
            _tmpUpdatedAtMs = _cursor.getLong(_cursorIndexOfUpdatedAtMs);
            _item = new ProviderEntity(_tmpId,_tmpName,_tmpKind,_tmpBaseUrl,_tmpApiKey,_tmpApiKeyRequired,_tmpEnabled,_tmpSource,_tmpRequestMaxRetries,_tmpStreamMaxRetries,_tmpStreamIdleTimeoutMs,_tmpHttpHeadersJson,_tmpModelsJson,_tmpIsPrimary,_tmpCreatedAtMs,_tmpUpdatedAtMs);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
