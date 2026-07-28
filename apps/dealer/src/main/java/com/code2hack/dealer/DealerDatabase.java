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

@Database(entities = DealerDatabase.AttachedThread.class, version = 1, exportSchema = false)
public abstract class DealerDatabase extends RoomDatabase {
    public abstract AttachedThreadDao attachedThreads();

    @Entity(tableName = "attached_threads", primaryKeys = {"hostId", "threadId"})
    public static final class AttachedThread {
        @NonNull public final String hostId;
        @NonNull public final String threadId;

        public AttachedThread(@NonNull String hostId, @NonNull String threadId) {
            this.hostId = hostId;
            this.threadId = threadId;
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
}
