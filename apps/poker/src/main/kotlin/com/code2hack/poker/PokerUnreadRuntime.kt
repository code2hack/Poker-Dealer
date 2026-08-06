package com.code2hack.poker

import android.content.Context
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.protocol.FilePokerUnreadStore
import com.code2hack.pokerdealer.protocol.PokerSnapshot
import com.code2hack.pokerdealer.protocol.PokerUnreadState
import com.code2hack.pokerdealer.protocol.PokerUnreadTracker
import com.code2hack.pokerdealer.protocol.PokerUnreadUpdate
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local unread coordinator; its durable value contains no card text. */
internal object PokerUnreadRuntime {
    private const val FILE_NAME = "poker-unread-v1.json"
    private val lock = Any()
    private val mutableUnreadCount = MutableStateFlow(0)
    private var tracker = PokerUnreadTracker()
    private var store: FilePokerUnreadStore? = null
    private var pairingFingerprint: String? = null

    val unreadCount: StateFlow<Int> = mutableUnreadCount.asStateFlow()

    fun initialize(context: Context, fingerprint: String?) {
        initialize(context.noBackupFilesDir.resolve(FILE_NAME), fingerprint)
    }

    fun initialize(file: File, fingerprint: String?) {
        synchronized(lock) {
            val normalizedFingerprint = fingerprint?.takeIf(String::isNotBlank)
            if (pairingFingerprint == normalizedFingerprint &&
                (normalizedFingerprint == null || store != null)
            ) return
            pairingFingerprint = normalizedFingerprint
            store = normalizedFingerprint?.let { FilePokerUnreadStore(file) }
            val loaded = if (store != null && normalizedFingerprint != null) {
                store!!.load(normalizedFingerprint)
            } else {
                PokerUnreadState()
            }
            tracker = PokerUnreadTracker(loaded)
            publish(tracker.state.unreadCount)
        }
    }

    fun resetForRestart() {
        synchronized(lock) {
            tracker = PokerUnreadTracker()
            store = null
            pairingFingerprint = null
            publish(0)
        }
    }

    fun install(snapshot: PokerSnapshot): PokerUnreadUpdate = synchronized(lock) {
        tracker.installSnapshot(snapshot).also { updateCountAndPersist() }
    }

    fun observeRequest(
        locator: CodexThreadLocator,
        requestKey: String,
        finalized: Boolean = false,
    ): PokerUnreadUpdate = synchronized(lock) {
        tracker.observeRequest(locator, requestKey, finalized).also { updateCountAndPersist() }
    }

    fun markCardRead(
        locator: CodexThreadLocator,
        cardId: String,
        finalized: Boolean,
        finalLineVisible: Boolean,
    ) {
        synchronized(lock) {
            tracker.markCardRead(locator, cardId, finalized, finalLineVisible)
            updateCountAndPersist()
        }
    }

    fun markRequestRead(
        locator: CodexThreadLocator,
        requestKey: String,
        finalized: Boolean,
        finalLineVisible: Boolean,
    ) {
        synchronized(lock) {
            tracker.markRequestRead(locator, requestKey, finalized, finalLineVisible)
            updateCountAndPersist()
        }
    }

    private fun updateCountAndPersist() {
        publish(tracker.state.unreadCount)
        val currentStore = store
        val currentFingerprint = pairingFingerprint
        if (currentStore != null && currentFingerprint != null) {
            runCatching { currentStore.save(currentFingerprint, tracker.state) }
        }
    }

    private fun publish(count: Int) {
        mutableUnreadCount.value = count
    }
}
