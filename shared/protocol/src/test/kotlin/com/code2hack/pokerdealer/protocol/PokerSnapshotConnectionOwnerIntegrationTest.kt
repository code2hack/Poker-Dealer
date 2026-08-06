package com.code2hack.pokerdealer.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PokerSnapshotConnectionOwnerIntegrationTest {
    @Test
    fun `production owners request install and acknowledge a complete snapshot`() = runTest {
        val offer = PokerProtocolOffer(capabilities = setOf(POKER_SNAPSHOT_CAPABILITY))
        val snapshot = PokerSnapshot(revision = 1)
        val installer = PokerSnapshotInstaller()
        val dealerHandler = PokerSnapshotConnectionHandler(
            role = PokerSnapshotRole.DEALER,
            snapshotSource = { snapshot },
            snapshotId = { "dealer-snapshot" },
        )
        val pokerHandler = PokerSnapshotConnectionHandler(
            role = PokerSnapshotRole.POKER,
            installer = installer,
        )
        val listener = TestListener()
        val connector = TestConnector()
        val dealer = PokerConnectionOwner<Unit>(
            factory = TestFactory(listener),
            scope = this,
            localOffer = offer,
            scheduler = TestScheduler(this),
            clock = PokerClock { 0L },
            heartbeatPolicy = PokerHeartbeatPolicy(idlePingIntervalMs = 1_000_000L),
            reconnect = PokerReconnectController(PokerReconnectPolicy(jitterFraction = 0.0)),
            callbacks = dealerHandler,
        )
        val poker = PokerConnectionOwner<Unit>(
            factory = null,
            connector = connector,
            scope = this,
            localOffer = offer,
            scheduler = TestScheduler(this),
            clock = PokerClock { 0L },
            heartbeatPolicy = PokerHeartbeatPolicy(idlePingIntervalMs = 1_000_000L),
            reconnect = PokerReconnectController(PokerReconnectPolicy(jitterFraction = 0.0)),
            callbacks = pokerHandler,
        )

        dealer.start()
        poker.start()
        runCurrent()
        val (dealerSocket, pokerSocket) = linkedPair()
        listener.offer(dealerSocket)
        connector.offer(pokerSocket)
        runCurrent()

        assertEquals(snapshot, installer.installedSnapshot)
        assertTrue(pokerSocket.sentTypes().contains(POKER_SNAPSHOT_REQUEST_TYPE))
        assertTrue(dealerSocket.sentTypes().contains(POKER_SNAPSHOT_BEGIN_TYPE))
        assertTrue(dealerSocket.sentTypes().contains(POKER_SNAPSHOT_COMPLETE_TYPE))
        assertTrue(pokerSocket.sentTypes().contains(POKER_SNAPSHOT_ACK_TYPE))

        dealer.stop()
        poker.stop()
        runCurrent()
    }

    private class TestScheduler(private val scope: CoroutineScope) : PokerScheduler {
        override fun schedule(delayMs: Long, task: () -> Unit): PokerScheduledTask {
            val job = scope.launch {
                delay(delayMs)
                task()
            }
            return PokerScheduledTask { job.cancel() }
        }
    }

    private class TestListener : PokerListenerSocket {
        private val sockets = Channel<PokerFrameSocket>(Channel.UNLIMITED)
        private var closed = false

        fun offer(socket: PokerFrameSocket) {
            check(!closed)
            sockets.trySend(socket)
        }

        override suspend fun accept(): PokerFrameSocket = sockets.receive()

        override fun close() {
            closed = true
            sockets.close()
        }
    }

    private class TestFactory(private val listener: TestListener) : PokerListenerFactory {
        override fun open(): PokerListenerSocket = listener
    }

    private class TestConnector : PokerConnectionConnector {
        private val sockets = Channel<PokerFrameSocket>(Channel.UNLIMITED)

        fun offer(socket: PokerFrameSocket) {
            sockets.trySend(socket)
        }

        override suspend fun connect(): PokerFrameSocket = sockets.receive()
    }

    private class LinkedSocket : PokerFrameSocket {
        private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        private lateinit var peer: LinkedSocket
        private val sent = mutableListOf<ByteArray>()
        private var closed = false

        fun connect(peer: LinkedSocket) {
            this.peer = peer
        }

        override suspend fun sendFrame(frame: ByteArray) {
            check(!closed)
            sent += frame.copyOf()
            peer.incoming.send(frame.copyOf())
        }

        override suspend fun receiveFrame(): ByteArray? = incoming.receiveCatching().getOrNull()

        override fun close() {
            closed = true
            incoming.close()
        }

        fun sentTypes(): List<String> = sent.map { PokerFrameCodec.decode(it).type }
    }

    private fun linkedPair(): Pair<LinkedSocket, LinkedSocket> {
        val first = LinkedSocket()
        val second = LinkedSocket()
        first.connect(second)
        second.connect(first)
        return first to second
    }
}
