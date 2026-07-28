package com.code2hack.dealer

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ComposerAction
import com.code2hack.pokerdealer.domain.PendingThreadInput
import com.code2hack.pokerdealer.domain.ThreadActionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DealerThreadAttachmentStore(context: Context) {
    private val database = Room.databaseBuilder(
        context.applicationContext,
        DealerDatabase::class.java,
        "dealer.db",
    ).addMigrations(MIGRATION_1_2).build()
    private val dao = database.attachedThreads()
    private val drafts = database.threadDrafts()
    private val actions = database.pendingThreadActions()

    suspend fun read(): Set<CodexThreadLocator> = withContext(Dispatchers.IO) {
        dao.readAll().mapTo(mutableSetOf()) { CodexThreadLocator(it.hostId, it.threadId) }
    }

    suspend fun attach(locator: CodexThreadLocator) = withContext(Dispatchers.IO) {
        dao.insert(DealerDatabase.AttachedThread(locator.hostId, locator.threadId))
    }

    suspend fun detach(locator: CodexThreadLocator) = withContext(Dispatchers.IO) {
        dao.delete(locator.hostId, locator.threadId)
    }

    suspend fun readDrafts(): Map<CodexThreadLocator, String> = withContext(Dispatchers.IO) {
        drafts.readAll().associate { CodexThreadLocator(it.hostId, it.threadId) to it.text }
    }

    suspend fun writeDraft(locator: CodexThreadLocator, text: String) = withContext(Dispatchers.IO) {
        if (text.isEmpty()) {
            drafts.delete(locator.hostId, locator.threadId)
        } else {
            drafts.upsert(DealerDatabase.ThreadDraft(locator.hostId, locator.threadId, text))
        }
    }

    suspend fun readActions(): ThreadActionState = withContext(Dispatchers.IO) {
        val pendingInputs = mutableMapOf<CodexThreadLocator, PendingThreadInput>()
        val pendingInterrupts = mutableMapOf<CodexThreadLocator, String>()
        actions.readAll().forEach { row ->
            val locator = CodexThreadLocator(row.hostId, row.threadId)
            if (row.kind == INTERRUPT) {
                row.expectedTurnId?.let { pendingInterrupts[locator] = it }
            } else {
                val action = runCatching { ComposerAction.valueOf(row.kind) }.getOrNull()
                val clientId = row.clientId
                val draftText = row.draftText
                if (action != null && clientId != null && draftText != null) {
                    pendingInputs[locator] = PendingThreadInput(
                        clientId = clientId,
                        action = action,
                        expectedTurnId = row.expectedTurnId,
                        draftText = draftText,
                        uncertain = true,
                    )
                }
            }
        }
        ThreadActionState(
            drafts = readDrafts(),
            pendingInputs = pendingInputs,
            pendingInterrupts = pendingInterrupts,
        )
    }

    suspend fun writePendingInput(locator: CodexThreadLocator, pending: PendingThreadInput?) =
        withContext(Dispatchers.IO) {
            if (pending == null) {
                actions.delete(locator.hostId, locator.threadId)
            } else {
                actions.upsert(
                    DealerDatabase.PendingThreadAction(
                        locator.hostId,
                        locator.threadId,
                        pending.action.name,
                        pending.clientId,
                        pending.expectedTurnId,
                        pending.draftText,
                    ),
                )
            }
        }

    suspend fun writePendingInterrupt(locator: CodexThreadLocator, turnId: String?) =
        withContext(Dispatchers.IO) {
            if (turnId == null) {
                actions.delete(locator.hostId, locator.threadId)
            } else {
                actions.upsert(
                    DealerDatabase.PendingThreadAction(
                        locator.hostId,
                        locator.threadId,
                        INTERRUPT,
                        null,
                        turnId,
                        null,
                    ),
                )
            }
        }

    fun close() = database.close()

    private companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `thread_drafts` (" +
                        "`hostId` TEXT NOT NULL, `threadId` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                        "PRIMARY KEY(`hostId`, `threadId`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_thread_actions` (" +
                        "`hostId` TEXT NOT NULL, `threadId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`clientId` TEXT, `expectedTurnId` TEXT, `draftText` TEXT, " +
                        "PRIMARY KEY(`hostId`, `threadId`))",
                )
            }
        }

        const val INTERRUPT = "INTERRUPT"
    }
}
