package com.code2hack.pokerdealer.protocol

import com.code2hack.pokerdealer.domain.PokerBindingControl
import com.code2hack.pokerdealer.domain.PokerBindingController
import com.code2hack.pokerdealer.domain.PokerBindingInstallResult
import com.code2hack.pokerdealer.domain.PokerBindingMap
import com.code2hack.pokerdealer.domain.PokerOperation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PokerBindingsProtocolTest {
    @Test
    fun `complete map snapshot round trips through the envelope`() {
        val map = PokerBindingMap.defaultGlasses().bind(
            com.code2hack.pokerdealer.domain.PokerBindingDevice.remote("remote-a"),
            PokerOperation.TAP,
            PokerBindingControl.remote("remote-a", 42),
        )

        val frame = PokerBindingProtocol.encodeSnapshot(
            map = map,
            messageId = "message-1",
            sessionId = "epoch-1",
            sentAtMs = 100,
            sequence = 4,
        )

        assertEquals(map, PokerBindingProtocol.decodeSnapshot(frame))
    }

    @Test
    fun `malformed or stale snapshots retain the last complete map`() {
        val receiver = PokerBindingController()
        val initial = receiver.map
        assertEquals(PokerBindingInstallResult.REJECTED, PokerBindingProtocol.installSnapshot(receiver, byteArrayOf(1, 2, 3)))
        assertEquals(initial, receiver.map)

        val newer = initial.bind(
            com.code2hack.pokerdealer.domain.PokerBindingDevice.remote("remote-a"),
            PokerOperation.FN,
            PokerBindingControl.remote("remote-a", 7),
        )
        assertEquals(
            PokerBindingInstallResult.INSTALLED,
            PokerBindingProtocol.installSnapshot(
                receiver,
                PokerBindingProtocol.encodeSnapshot(newer, "message-2", "epoch-1", 101, 5),
            ),
        )
        assertEquals(
            PokerBindingInstallResult.STALE,
            PokerBindingProtocol.installSnapshot(
                receiver,
                PokerBindingProtocol.encodeSnapshot(initial, "message-3", "epoch-1", 102, 6),
            ),
        )
        assertEquals(newer, receiver.map)
    }
}
