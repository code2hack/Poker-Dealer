package com.code2hack.pokerdealer.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PokerConnectionTest {
    @Test
    fun `same major negotiates common capabilities and blocks only missing optional capability`() {
        val result = negotiatePokerProtocol(
            PokerProtocolOffer(
                capabilities = setOf("snapshot", "send"),
                requiredCapabilities = setOf("snapshot"),
            ),
            PokerProtocolOffer(capabilities = setOf("snapshot", "future")),
        )

        assertEquals(PokerProtocolAccess.READ_WRITE, result.access)
        assertTrue(result.majorCompatible)
        assertEquals(setOf("snapshot"), result.capabilities)
        assertTrue(result.supports("snapshot"))
        assertFalse(result.supports("send"))
    }

    @Test
    fun `major mismatch retains the snapshot but makes the connection read only`() {
        val session = PokerConnectionSession<String>()
        val old = RecordingConnection()
        val epoch = session.replaceAuthenticatedConnection(old)
        session.retainCompleteSnapshot("last complete cards")

        val result = session.negotiate(epoch, PokerProtocolOffer(major = 2))

        assertEquals(PokerProtocolAccess.READ_ONLY, result?.access)
        assertEquals(PokerConnectionState.READ_ONLY, session.state)
        assertEquals("last complete cards", session.completeSnapshot())
        assertEquals(
            PokerMutationRejection.READ_ONLY,
            (session.applyMutation(epoch, "cards", 1) { "new cards" } as PokerMutationResult.Rejected).reason,
        )
    }

    @Test
    fun `replacement closes the old epoch and fences duplicate and out of order mutations`() {
        val fence = PokerConnectionEpochFence()
        val first = RecordingConnection()
        val firstEpoch = fence.replace(first)
        var applied = 0

        assertEquals(PokerSequenceDecision.ACCEPTED, fence.acceptInbound(firstEpoch, "cards", 1))
        assertEquals(PokerSequenceDecision.INVALID_STREAM, fence.acceptInbound(firstEpoch, "", 1))
        assertEquals(PokerSequenceDecision.DUPLICATE, fence.acceptInbound(firstEpoch, "cards", 1))
        assertEquals(PokerSequenceDecision.OUT_OF_ORDER, fence.acceptInbound(firstEpoch, "cards", 3))
        assertEquals(PokerSequenceDecision.ACCEPTED, fence.acceptInbound(firstEpoch, "cards", 2))
        assertEquals(
            PokerSequenceDecision.DUPLICATE,
            fence.applyInbound(firstEpoch, "cards", 2) { applied++ }
                .let { if (it is PokerMutationResult.Rejected) PokerSequenceDecision.DUPLICATE else PokerSequenceDecision.ACCEPTED },
        )
        assertEquals(0, applied)

        val second = RecordingConnection()
        val secondEpoch = fence.replace(second)
        assertTrue(first.closed)
        assertFalse(fence.isCurrent(firstEpoch))
        assertEquals(PokerSequenceDecision.STALE_EPOCH, fence.acceptInbound(firstEpoch, "cards", 3))
        assertEquals(1, fence.nextOutboundSequence(secondEpoch, "cards"))
        assertEquals(null, fence.nextOutboundSequence(firstEpoch, "cards"))
    }

    @Test
    fun `reconnect starts a fresh sequence and never replays the old mutation`() {
        val session = PokerConnectionSession<String>(
            localOffer = PokerProtocolOffer(capabilities = setOf("send")),
        )
        val first = RecordingConnection()
        val firstEpoch = session.replaceAuthenticatedConnection(first)
        session.negotiate(firstEpoch, PokerProtocolOffer(capabilities = setOf("send")))
        var applied = 0

        assertTrue(session.applyMutation(firstEpoch, "cards", 1, "send") {
            applied++
            "applied"
        } is PokerMutationResult.Applied)
        assertEquals(1, session.nextOutboundSequence(firstEpoch, "cards"))

        val secondEpoch = session.replaceAuthenticatedConnection(RecordingConnection())
        session.negotiate(secondEpoch, PokerProtocolOffer(capabilities = setOf("send")))

        val oldMutation = session.applyMutation(firstEpoch, "cards", 2, "send") { applied++ }
        assertEquals(PokerMutationRejection.STALE_EPOCH, (oldMutation as PokerMutationResult.Rejected).reason)
        assertEquals(1, session.nextOutboundSequence(secondEpoch, "cards"))
        assertEquals(1, applied)
    }

    @Test
    fun `heartbeat pings idle connection and closes after three missed pongs`() {
        val monitor = PokerHeartbeatMonitor(
            policy = PokerHeartbeatPolicy(idlePingIntervalMs = 30, maxUnansweredPongs = 3),
        )

        assertEquals(PokerHeartbeatAction.NONE, monitor.poll(29))
        assertEquals(PokerHeartbeatAction.SEND_PING, monitor.poll(30))
        assertEquals(PokerHeartbeatAction.SEND_PING, monitor.poll(60))
        assertEquals(PokerHeartbeatAction.SEND_PING, monitor.poll(90))
        assertEquals(PokerHeartbeatAction.CLOSE, monitor.poll(120))
        assertEquals(PokerHeartbeatAction.CLOSE, monitor.poll(150))
    }

    @Test
    fun `pong resets heartbeat and traffic postpones the next idle ping`() {
        val monitor = PokerHeartbeatMonitor(
            policy = PokerHeartbeatPolicy(idlePingIntervalMs = 30, maxUnansweredPongs = 3),
        )

        assertEquals(PokerHeartbeatAction.SEND_PING, monitor.poll(30))
        monitor.onPong(31)
        monitor.onTraffic(50)
        assertEquals(PokerHeartbeatAction.NONE, monitor.poll(79))
        assertEquals(PokerHeartbeatAction.SEND_PING, monitor.poll(80))
        assertEquals(1, monitor.pendingPongs)
    }

    @Test
    fun `network and manual retry are immediate while failures back off and stable connection resets`() {
        val reconnect = PokerReconnectController(
            PokerReconnectPolicy(initialDelayMs = 1_000, maxDelayMs = 30_000, jitterFraction = 0.0),
        )

        assertEquals(0, reconnect.request(PokerReconnectTrigger.NETWORK_CHANGE))
        assertEquals(0, reconnect.request(PokerReconnectTrigger.MANUAL_RETRY))
        assertEquals(1_000, reconnect.request(PokerReconnectTrigger.FAILURE, jitterUnit = 0.5))
        assertEquals(2_000, reconnect.request(PokerReconnectTrigger.FAILURE, jitterUnit = 0.5))
        reconnect.markStable()
        assertEquals(1_000, reconnect.request(PokerReconnectTrigger.FAILURE, jitterUnit = 0.5))

        reconnect.cancel()
        assertNull(reconnect.request(PokerReconnectTrigger.MANUAL_RETRY))
        reconnect.enable()
        assertNotNull(reconnect.request(PokerReconnectTrigger.NETWORK_CHANGE))
    }

    private class RecordingConnection : PokerEpochConnection {
        var closed = false

        override fun close() {
            closed = true
        }
    }
}
