package com.code2hack.pokerdealer.testing

import com.code2hack.pokerdealer.protocol.PokerTransport
import com.code2hack.pokerdealer.protocol.PokerTransportState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class LoopbackPokerTransport : PokerTransport {
    private val mutableState = MutableStateFlow(PokerTransportState.DISABLED)
    private val frames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    override val state: StateFlow<PokerTransportState> = mutableState
    override val incomingFrames: Flow<ByteArray> = frames

    override suspend fun connect() {
        mutableState.value = PokerTransportState.CONNECTING
        mutableState.value = PokerTransportState.CONNECTED
    }

    override suspend fun disconnect() {
        mutableState.value = PokerTransportState.DISABLED
    }

    override suspend fun send(frame: ByteArray) {
        check(mutableState.value == PokerTransportState.CONNECTED) {
            "Loopback transport must be connected before send"
        }
        frames.emit(frame.copyOf())
    }
}
