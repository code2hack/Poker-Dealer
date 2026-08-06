package com.code2hack.poker

import android.content.Context
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.protocol.PokerSnapshot
import com.code2hack.pokerdealer.protocol.PokerUnreadUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local Poker projection; card text is never written to durable storage. */
object PokerSnapshotRuntime {
    private val mutableSnapshot = MutableStateFlow<PokerSnapshot?>(null)
    private var foregroundRequester: (() -> Unit)? = null

    val snapshot: StateFlow<PokerSnapshot?> = mutableSnapshot.asStateFlow()
    val unreadCount: StateFlow<Int> = PokerUnreadRuntime.unreadCount

    fun clearForRestart() {
        mutableSnapshot.value = null
        PokerUnreadRuntime.resetForRestart()
    }

    fun initializeUnread(context: Context, pairingFingerprint: String?) {
        PokerUnreadRuntime.initialize(context, pairingFingerprint)
    }

    @Synchronized
    fun attachForegroundRequester(request: () -> Unit) {
        foregroundRequester = request
    }

    @Synchronized
    fun detachForegroundRequester() {
        foregroundRequester = null
    }

    fun install(snapshot: PokerSnapshot) {
        val update = PokerUnreadRuntime.install(snapshot)
        mutableSnapshot.value = snapshot
        if (update.shouldForeground) {
            synchronized(this) { foregroundRequester }?.invoke()
        }
    }

    fun observeRequest(
        locator: CodexThreadLocator,
        requestKey: String,
        finalized: Boolean = false,
    ): PokerUnreadUpdate = PokerUnreadRuntime.observeRequest(locator, requestKey, finalized).also {
        if (it.shouldForeground) {
            synchronized(this) { foregroundRequester }?.invoke()
        }
    }

    fun markCardRead(
        locator: CodexThreadLocator,
        cardId: String,
        finalized: Boolean,
        finalLineVisible: Boolean,
    ) = PokerUnreadRuntime.markCardRead(locator, cardId, finalized, finalLineVisible)

    fun markRequestRead(
        locator: CodexThreadLocator,
        requestKey: String,
        finalized: Boolean,
        finalLineVisible: Boolean,
    ) = PokerUnreadRuntime.markRequestRead(locator, requestKey, finalized, finalLineVisible)

}
