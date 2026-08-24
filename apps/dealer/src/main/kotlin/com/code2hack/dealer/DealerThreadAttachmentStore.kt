package com.code2hack.dealer

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ComposerAction
import com.code2hack.pokerdealer.domain.ComposerDraft
import com.code2hack.pokerdealer.domain.ComposerDraftCodec
import com.code2hack.pokerdealer.domain.PendingThreadInput
import com.code2hack.pokerdealer.domain.ThreadActionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DealerThreadAttachmentStore(context: Context) {
    private val database = Room.databaseBuilder(
        context.applicationContext,
        DealerDatabase::class.java,
        "dealer.db",
    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
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

    suspend fun purge(locator: CodexThreadLocator) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            dao.delete(locator.hostId, locator.threadId)
            drafts.delete(locator.hostId, locator.threadId)
            actions.delete(locator.hostId, locator.threadId)
        }
    }

    suspend fun readDrafts(): Map<CodexThreadLocator, String> = withContext(Dispatchers.IO) {
        migrateLegacyDraftStorage()
        drafts.readAll().associate {
            CodexThreadLocator(it.hostId, it.threadId) to storedDraft(it).displayText
        }
    }

    suspend fun readComposerDrafts(): Map<CodexThreadLocator, ComposerDraft> = withContext(Dispatchers.IO) {
        migrateLegacyDraftStorage()
        drafts.readAll().associate {
            CodexThreadLocator(it.hostId, it.threadId) to storedDraft(it)
        }
    }

    suspend fun writeDraft(locator: CodexThreadLocator, text: String) =
        writeDraft(locator, ComposerDraft.fromText(text))

    suspend fun writeDraft(locator: CodexThreadLocator, draft: ComposerDraft) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            val current = drafts.read(locator.hostId, locator.threadId)
            upsertDraft(locator, draft, current?.reasoningEffort)
        }
    }

    suspend fun writeReasoningEffort(locator: CodexThreadLocator, effort: String?) =
        withContext(Dispatchers.IO) {
            migrateLegacyDraftStorage()
            database.runInTransaction {
                val current = drafts.read(locator.hostId, locator.threadId)
                upsertDraft(
                    locator = locator,
                    draft = current?.let(::storedDraft) ?: ComposerDraft(),
                    reasoningEffort = effort,
                )
            }
        }

    suspend fun readActions(): ThreadActionState = withContext(Dispatchers.IO) {
        migrateLegacyDraftStorage()
        val pendingInputs = mutableMapOf<CodexThreadLocator, PendingThreadInput>()
        val pendingInterrupts = mutableMapOf<CodexThreadLocator, String>()
        val draftRows = drafts.readAll()
        val composerDrafts = draftRows.associate {
            CodexThreadLocator(it.hostId, it.threadId) to storedDraft(it)
        }.toMutableMap()
        actions.readAll().forEach { row ->
            val locator = CodexThreadLocator(row.hostId, row.threadId)
            if (row.kind == INTERRUPT) {
                row.expectedTurnId?.let { pendingInterrupts[locator] = it }
            } else {
                val action = runCatching { ComposerAction.valueOf(row.kind) }.getOrNull()
                val clientId = row.clientId
                val pendingDraft = row.draftJson
                    ?.takeUnless(String::isBlank)
                    ?.let(ComposerDraftCodec::decodeOrLegacy)
                    ?: row.draftText?.let(ComposerDraft::fromLegacy)
                if (action != null && clientId != null && pendingDraft != null) {
                    pendingInputs[locator] = PendingThreadInput(
                        clientId = clientId,
                        action = action,
                        expectedTurnId = row.expectedTurnId,
                        draftText = pendingDraft.displayText,
                        draft = pendingDraft,
                        uncertain = true,
                    )
                    composerDrafts.putIfAbsent(locator, pendingDraft)
                }
            }
        }
        ThreadActionState(
            drafts = composerDrafts.mapValues { it.value.displayText },
            composerDrafts = composerDrafts,
            pendingInputs = pendingInputs,
            pendingInterrupts = pendingInterrupts,
            pendingReasoningEfforts = drafts.readAll().mapNotNull { row ->
                row.reasoningEffort?.let {
                    CodexThreadLocator(row.hostId, row.threadId) to it
                }
            }.toMap(),
        )
    }

    suspend fun writePendingInput(locator: CodexThreadLocator, pending: PendingThreadInput?) =
        withContext(Dispatchers.IO) {
            if (pending == null) {
                actions.delete(locator.hostId, locator.threadId)
            } else {
                database.runInTransaction {
                    val current = drafts.read(locator.hostId, locator.threadId)
                    upsertDraft(locator, pending.draft, current?.reasoningEffort)
                    actions.upsert(
                        DealerDatabase.PendingThreadAction(
                            locator.hostId,
                            locator.threadId,
                            pending.action.name,
                            pending.clientId,
                            pending.expectedTurnId,
                            pending.draft.displayText,
                            ComposerDraftCodec.encode(pending.draft),
                        ),
                    )
                }
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
                        null,
                    ),
                )
            }
        }

    fun close() = database.close()

    private fun storedDraft(row: DealerDatabase.ThreadDraft): ComposerDraft =
        row.draftJson.takeUnless(String::isBlank)
            ?.let(ComposerDraftCodec::decodeOrLegacy)
            ?: ComposerDraft.fromLegacy(row.text)

    private fun upsertDraft(
        locator: CodexThreadLocator,
        draft: ComposerDraft,
        reasoningEffort: String?,
    ) {
        val normalized = draft.normalized()
        if (normalized.isEmpty && reasoningEffort == null) {
            drafts.delete(locator.hostId, locator.threadId)
        } else {
            drafts.upsert(
                DealerDatabase.ThreadDraft(
                    locator.hostId,
                    locator.threadId,
                    normalized.displayText,
                    ComposerDraftCodec.encode(normalized),
                    reasoningEffort,
                ),
            )
        }
    }

    /** Converts legacy rows in one Room transaction before any caller reads them. */
    private fun migrateLegacyDraftStorage() {
        database.runInTransaction {
            drafts.readAll().forEach { row ->
                val draft = storedDraft(row).normalized()
                val encoded = ComposerDraftCodec.encode(draft)
                if (row.text != draft.displayText || row.draftJson != encoded) {
                    drafts.upsert(
                        DealerDatabase.ThreadDraft(
                            row.hostId,
                            row.threadId,
                            draft.displayText,
                            encoded,
                            row.reasoningEffort,
                        ),
                    )
                }
            }
            actions.readAll().forEach { row ->
                if (row.kind == INTERRUPT || row.draftText == null) return@forEach
                val draft = row.draftJson
                    ?.takeUnless(String::isBlank)
                    ?.let(ComposerDraftCodec::decodeOrLegacy)
                    ?: ComposerDraft.fromLegacy(row.draftText)
                val encoded = ComposerDraftCodec.encode(draft)
                if (row.draftText != draft.displayText || row.draftJson != encoded) {
                    actions.upsert(
                        DealerDatabase.PendingThreadAction(
                            row.hostId,
                            row.threadId,
                            row.kind,
                            row.clientId,
                            row.expectedTurnId,
                            draft.displayText,
                            encoded,
                        ),
                    )
                }
            }
        }
    }

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
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `thread_drafts` ADD COLUMN `reasoningEffort` TEXT")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `thread_drafts` ADD COLUMN `draftJson` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `pending_thread_actions` ADD COLUMN `draftJson` TEXT")
            }
        }

        const val INTERRUPT = "INTERRUPT"
    }
}
