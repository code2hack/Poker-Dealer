package com.code2hack.pokerdealer.testing

import com.code2hack.pokerdealer.protocol.PokerTransportState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LoopbackPokerTransportTest {
    @Test
    fun `connected transport returns an independent frame copy`() = runTest {
        val transport = LoopbackPokerTransport()
        transport.connect()
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            transport.incomingFrames.first()
        }
        val source = byteArrayOf(1, 2, 3)

        transport.send(source)
        source[0] = 9

        assertArrayEquals(byteArrayOf(1, 2, 3), received.await())
        assertEquals(PokerTransportState.CONNECTED, transport.state.value)
    }

    @Test
    fun `fixture contains a genuinely long untruncated card`() {
        assertTrue(MockDeck.longCard.fullText.length >= 20_000)
        assertTrue(MockDeck.longCard.fullText.contains("中文"))
    }
}
