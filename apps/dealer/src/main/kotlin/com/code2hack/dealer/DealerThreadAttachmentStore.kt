package com.code2hack.dealer

import android.content.Context
import androidx.room.Room
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DealerThreadAttachmentStore(context: Context) {
    private val database = Room.databaseBuilder(
        context.applicationContext,
        DealerDatabase::class.java,
        "dealer.db",
    ).build()
    private val dao = database.attachedThreads()

    suspend fun read(): Set<CodexThreadLocator> = withContext(Dispatchers.IO) {
        dao.readAll().mapTo(mutableSetOf()) { CodexThreadLocator(it.hostId, it.threadId) }
    }

    suspend fun attach(locator: CodexThreadLocator) = withContext(Dispatchers.IO) {
        dao.insert(DealerDatabase.AttachedThread(locator.hostId, locator.threadId))
    }

    suspend fun detach(locator: CodexThreadLocator) = withContext(Dispatchers.IO) {
        dao.delete(locator.hostId, locator.threadId)
    }

    fun close() = database.close()
}
