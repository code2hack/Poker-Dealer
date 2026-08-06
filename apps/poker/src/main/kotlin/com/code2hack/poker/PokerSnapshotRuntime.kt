package com.code2hack.poker

import com.code2hack.pokerdealer.protocol.PokerSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local Poker projection; card text is never written to durable storage. */
object PokerSnapshotRuntime {
    private val mutableSnapshot = MutableStateFlow<PokerSnapshot?>(null)

    val snapshot: StateFlow<PokerSnapshot?> = mutableSnapshot.asStateFlow()

    fun clearForRestart() {
        mutableSnapshot.value = null
    }

    fun install(snapshot: PokerSnapshot) {
        mutableSnapshot.value = snapshot
    }
}
