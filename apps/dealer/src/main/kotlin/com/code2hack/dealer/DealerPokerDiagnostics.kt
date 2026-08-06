package com.code2hack.dealer

import com.code2hack.pokerdealer.domain.PokerBindingSyncStatus
import com.code2hack.pokerdealer.protocol.PokerConnectionState
import com.code2hack.pokerdealer.protocol.PokerPairingState
import com.code2hack.pokerdealer.protocol.PokerWakeCapability

enum class PokerFontSyncStatus {
    UNSYNCHRONIZED,
    PENDING,
    SYNCHRONIZED,
}

enum class PokerDiagnosticFailure {
    NONE,
    PAIRING,
    PROTOCOL,
    SNAPSHOT,
    DELTA,
    BINDINGS,
    FONT,
    WAKE,
    STORAGE,
}

/** UI-safe Poker health. It has no content, endpoint, credential, or raw-error fields. */
data class DealerPokerDiagnostics(
    val pairing: PokerPairingState = PokerPairingState.UNPAIRED,
    val connection: PokerConnectionState = PokerConnectionState.DISCONNECTED,
    val connectionEpoch: Long? = null,
    val capabilities: Set<String> = emptySet(),
    val snapshotRevision: Long? = null,
    val deltaRevision: Long? = null,
    val bindingSync: PokerBindingSyncStatus = PokerBindingSyncStatus.UNSYNCHRONIZED,
    val unreadCount: Int? = null,
    val wakeCapability: PokerWakeCapability = PokerWakeCapability.UNKNOWN,
    val fontSync: PokerFontSyncStatus = PokerFontSyncStatus.UNSYNCHRONIZED,
    val fontRevision: Long = 0,
    val acknowledgedFontRevision: Long? = null,
    val lastFailure: PokerDiagnosticFailure = PokerDiagnosticFailure.NONE,
)
