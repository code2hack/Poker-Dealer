package com.code2hack.dealer;

import androidx.annotation.NonNull;
import androidx.room.Dao;
import androidx.room.Database;
import androidx.room.Entity;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.RoomDatabase;

import java.util.List;

@Database(
    entities = {
        DealerDatabase.AttachedThread.class,
        DealerDatabase.ThreadDraft.class,
        DealerDatabase.PendingThreadAction.class
    },
    version = 2,
    exportSchema = false
)
public abstract class DealerDatabase extends RoomDatabase {
    public abstract AttachedThreadDao attachedThreads();
    public abstract ThreadDraftDao threadDrafts();
    public abstract PendingThreadActionDao pendingThreadActions();

    @Entity(tableName = "attached_threads", primaryKeys = {"hostId", "threadId"})
    public static final class AttachedThread {
        @NonNull public final String hostId;
        @NonNull public final String threadId;

        public AttachedThread(@NonNull String hostId, @NonNull String threadId) {
            this.hostId = hostId;
            this.threadId = threadId;
        }
    }

    @Entity(tableName = "thread_drafts", primaryKeys = {"hostId", "threadId"})
    public static final class ThreadDraft {
        @NonNull public final String hostId;
        @NonNull public final String threadId;
        @NonNull public final String text;

        public ThreadDraft(@NonNull String hostId, @NonNull String threadId, @NonNull String text) {
            this.hostId = hostId;
            this.threadId = threadId;
            this.text = text;
        }
    }

    @Entity(tableName = "pending_thread_actions", primaryKeys = {"hostId", "threadId"})
    public static final class PendingThreadAction {
        @NonNull public final String hostId;
        @NonNull public final String threadId;
        @NonNull public final String kind;
        public final String clientId;
        public final String expectedTurnId;
        public final String draftText;

        public PendingThreadAction(
            @NonNull String hostId,
            @NonNull String threadId,
            @NonNull String kind,
            String clientId,
            String expectedTurnId,
            String draftText
        ) {
            this.hostId = hostId;
            this.threadId = threadId;
            this.kind = kind;
            this.clientId = clientId;
            this.expectedTurnId = expectedTurnId;
            this.draftText = draftText;
        }
    }

    @Dao
    public interface AttachedThreadDao {
        @Query("SELECT * FROM attached_threads")
        List<AttachedThread> readAll();

        @Insert(onConflict = OnConflictStrategy.IGNORE)
        void insert(AttachedThread thread);

        @Query("DELETE FROM attached_threads WHERE hostId = :hostId AND threadId = :threadId")
        void delete(String hostId, String threadId);
    }

    @Dao
    public interface ThreadDraftDao {
        @Query("SELECT * FROM thread_drafts")
        List<ThreadDraft> readAll();

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        void upsert(ThreadDraft draft);

        @Query("DELETE FROM thread_drafts WHERE hostId = :hostId AND threadId = :threadId")
        void delete(String hostId, String threadId);
    }

    @Dao
    public interface PendingThreadActionDao {
        @Query("SELECT * FROM pending_thread_actions")
        List<PendingThreadAction> readAll();

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        void upsert(PendingThreadAction action);

        @Query("DELETE FROM pending_thread_actions WHERE hostId = :hostId AND threadId = :threadId")
        void delete(String hostId, String threadId);
    }
}
