package com.code2hack.pokerdealer.protocol

import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PokerConnectionOwnerTest {
    @Test
    fun `listener failure schedules the returned backoff and later opens`() = runTest {
        val scheduler = FakeScheduler()
        val factory = FakeFactory().apply { failuresRemaining = 1 }
        val owner = owner(factory, scheduler, this)

        owner.start()
        scheduler.runNext()
        runCurrent()

        assertEquals(listOf(0L, 1_000L), scheduler.delays)
        assertFalse(owner.isListening)

        scheduler.runNext()
        runCurrent()

        assertTrue(owner.isListening)
        assertEquals(2, factory.openCount)
        owner.stop()
    }

    @Test
    fun `successful negotiation resets the next failure to the initial backoff`() = runTest {
        val scheduler = FakeScheduler()
        val factory = FakeFactory().apply { failuresRemaining = 1 }
        val owner = owner(factory, scheduler, this)
        owner.start()
        scheduler.runNext()
        runCurrent()
        scheduler.runNext()
        runCurrent()

        val socket = FakeFrameSocket().apply { offerPeerOffer() }
        factory.listeners.single().offer(socket)
        runCurrent()
        socket.close()
        runCurrent()

        assertEquals(1_000L, scheduler.delays.last())
        owner.stop()
    }

    @Test
    fun `network and manual retry close resources and schedule immediately`() = runTest {
        val scheduler = FakeScheduler()
        val factory = FakeFactory()
        val owner = owner(factory, scheduler, this)
        owner.start()
        scheduler.runNext()
        runCurrent()
        val listener = factory.listeners.single()
        val socket = FakeFrameSocket()
        listener.offer(socket)
        runCurrent()

        assertEquals(0L, owner.retry(PokerReconnectTrigger.NETWORK_CHANGE))
        assertTrue(listener.closed)
        assertTrue(socket.closed)
        assertEquals(0L, scheduler.delays.last())

        scheduler.runNext()
        runCurrent()
        assertEquals(2, factory.openCount)
        assertEquals(0L, owner.retry(PokerReconnectTrigger.MANUAL_RETRY))
        owner.stop()
    }

    @Test
    fun `outbound connector negotiates through the same owner and closes on stop`() = runTest {
        val scheduler = FakeScheduler()
        val connector = FakeConnector()
        val owner = clientOwner(connector, scheduler, this)

        owner.start()
        scheduler.runNext()
        runCurrent()
        val socket = FakeFrameSocket().apply { offerPeerOffer() }
        connector.offer(socket)
        runCurrent()

        assertEquals(
            listOf(POKER_PROTOCOL_OFFER_TYPE, POKER_PROTOCOL_NEGOTIATED_TYPE),
            socket.sentTypes(),
        )
        owner.stop()
        runCurrent()
        assertTrue(socket.closed)
    }

    @Test
    fun `new authenticated socket replaces the old epoch without closing the new one`() = runTest {
        val scheduler = FakeScheduler()
        val factory = FakeFactory()
        val owner = owner(factory, scheduler, this)
        owner.start()
        scheduler.runNext()
        runCurrent()
        val listener = factory.listeners.single()

        val first = FakeFrameSocket().apply { offerPeerOffer() }
        listener.offer(first)
        runCurrent()
        assertEquals(
            listOf(POKER_PROTOCOL_OFFER_TYPE, POKER_PROTOCOL_NEGOTIATED_TYPE),
            first.sentTypes(),
        )

        val second = FakeFrameSocket().apply { offerPeerOffer() }
        listener.offer(second)
        runCurrent()

        assertTrue(first.closed)
        assertFalse(second.closed)
        assertEquals(
            listOf(POKER_PROTOCOL_OFFER_TYPE, POKER_PROTOCOL_NEGOTIATED_TYPE),
            second.sentTypes(),
        )
        owner.stop()
    }

    @Test
    fun `newest accepted socket wins even when the earlier handler runs last`() = runTest {
        val dispatcher = LifoDispatcher()
        val connectionScope = CoroutineScope(SupervisorJob() + dispatcher)
        val scheduler = FakeScheduler()
        val factory = FakeFactory()
        val owner = owner(factory, scheduler, connectionScope)

        owner.start()
        scheduler.runNext()
        dispatcher.runNext()
        val listener = factory.listeners.single()
        val first = FakeFrameSocket().apply { offerPeerOffer() }
        val second = FakeFrameSocket().apply { offerPeerOffer() }
        listener.offer(first)
        listener.offer(second)
        repeat(3) { dispatcher.runNext() }

        assertTrue(first.closed)
        assertEquals(
            listOf(POKER_PROTOCOL_OFFER_TYPE, POKER_PROTOCOL_NEGOTIATED_TYPE),
            second.sentTypes(),
        )
        assertFalse(second.closed)
        owner.stop()
        connectionScope.cancel()
    }

    @Test
    fun `incompatible major reaches negotiation and fences mutations while retaining snapshot`() =
        runTest {
            val scheduler = FakeScheduler()
            val factory = FakeFactory()
            val session = PokerConnectionSession<String>().also {
                it.retainCompleteSnapshot("complete snapshot")
            }
            val owner = owner(factory, scheduler, this, session)
            owner.start()
            scheduler.runNext()
            runCurrent()
            val socket = FakeFrameSocket().apply {
                offerPeerOffer(
                    major = POKER_PROTOCOL_MAJOR + 1,
                    version = POKER_PROTOCOL_VERSION + 1,
                )
            }
            factory.listeners.single().offer(socket)
            runCurrent()

            assertEquals(PokerConnectionState.READ_ONLY, session.state)
            assertEquals("complete snapshot", session.completeSnapshot())
            assertFalse(session.canMutate())
            assertEquals(
                PokerMutationResult.Rejected(PokerMutationRejection.READ_ONLY),
                session.applyMutation(
                    epoch = PokerConnectionEpoch(1),
                    stream = POKER_CONTROL_STREAM,
                    sequence = 2,
                ) { "must not apply" },
            )
            assertEquals(
                listOf(POKER_PROTOCOL_OFFER_TYPE, POKER_PROTOCOL_NEGOTIATED_TYPE),
                socket.sentTypes(),
            )
            owner.stop()
        }

    @Test
    fun `heartbeat sends three pings and closes an unanswered socket`() = runTest {
        val scheduler = FakeScheduler()
        val clock = FakeClock()
        val factory = FakeFactory()
        val owner = owner(
            factory = factory,
            scheduler = scheduler,
            scope = this,
            clock = clock,
            heartbeatPolicy = PokerHeartbeatPolicy(idlePingIntervalMs = 10, maxUnansweredPongs = 3),
        )
        owner.start()
        scheduler.runNext()
        runCurrent()
        val listener = factory.listeners.single()
        val socket = FakeFrameSocket().apply { offerPeerOffer() }
        listener.offer(socket)
        runCurrent()

        repeat(4) { index ->
            clock.nowMs = (index + 1) * 10L
            scheduler.runNext()
            runCurrent()
        }

        assertEquals(3, socket.sentTypes().count { it == POKER_HEARTBEAT_PING_TYPE })
        assertTrue(socket.closed)
        owner.stop()
    }

    @Test
    fun `incoming ping gets a pong and incoming pong keeps the epoch alive`() = runTest {
        val scheduler = FakeScheduler()
        val clock = FakeClock()
        val factory = FakeFactory()
        val owner = owner(factory, scheduler, this, clock = clock)
        owner.start()
        scheduler.runNext()
        runCurrent()
        val socket = FakeFrameSocket().apply { offerPeerOffer() }
        factory.listeners.single().offer(socket)
        runCurrent()

        socket.offerPeerFrame(POKER_HEARTBEAT_PING_TYPE, sequence = 2, messageId = "peer-ping")
        runCurrent()

        assertEquals(POKER_HEARTBEAT_PONG_TYPE, socket.sentTypes().last())
        assertEquals("peer-ping", PokerFrameCodec.decode(socket.sent.last()).replyTo)
        assertFalse(socket.closed)
        owner.stop()
    }

    @Test
    fun `stop cancels the listener and heartbeat without a later reopen`() = runTest {
        val scheduler = FakeScheduler()
        val factory = FakeFactory()
        val owner = owner(factory, scheduler, this)
        owner.start()
        scheduler.runNext()
        runCurrent()
        val listener = factory.listeners.single()
        val socket = FakeFrameSocket().apply { offerPeerOffer() }
        listener.offer(socket)
        runCurrent()

        owner.stop()
        scheduler.runAll()
        runCurrent()

        assertTrue(listener.closed)
        assertTrue(socket.closed)
        assertFalse(owner.isRunning)
        assertEquals(1, factory.openCount)
    }

    @Test
    fun `a fresh process owner has no snapshot or mutation replay`() = runTest {
        val firstSession = PokerConnectionSession<String>()
        firstSession.retainCompleteSnapshot("card content")
        val firstFactory = FakeFactory()
        val firstScheduler = FakeScheduler()
        val firstOwner = owner(firstFactory, firstScheduler, this, firstSession)
        firstOwner.start()
        firstScheduler.runNext()
        runCurrent()
        firstOwner.stop()

        val secondSession = PokerConnectionSession<String>()
        val secondFactory = FakeFactory()
        val secondScheduler = FakeScheduler()
        val secondOwner = owner(secondFactory, secondScheduler, this, secondSession)
        secondOwner.start()
        secondScheduler.runNext()
        runCurrent()
        val socket = FakeFrameSocket().apply { offerPeerOffer() }
        secondFactory.listeners.single().offer(socket)
        runCurrent()

        assertNull(secondSession.completeSnapshot())
        assertEquals(
            listOf(POKER_PROTOCOL_OFFER_TYPE, POKER_PROTOCOL_NEGOTIATED_TYPE),
            socket.sentTypes(),
        )
        assertFalse(socket.sentTypes().any { it.startsWith("card.") })
        secondOwner.stop()
    }

    private fun owner(
        factory: FakeFactory,
        scheduler: FakeScheduler,
        scope: kotlinx.coroutines.CoroutineScope,
        session: PokerConnectionSession<String> = PokerConnectionSession(),
        clock: FakeClock = FakeClock(),
        heartbeatPolicy: PokerHeartbeatPolicy = PokerHeartbeatPolicy(),
    ) = PokerConnectionOwner(
        factory = factory,
        scope = scope,
        session = session,
        scheduler = scheduler,
        clock = clock,
        heartbeatPolicy = heartbeatPolicy,
        reconnect = PokerReconnectController(
            PokerReconnectPolicy(jitterFraction = 0.0),
        ),
    )

    private fun clientOwner(
        connector: FakeConnector,
        scheduler: FakeScheduler,
        scope: CoroutineScope,
    ) = PokerConnectionOwner<String>(
        factory = null,
        connector = connector,
        scope = scope,
        scheduler = scheduler,
        clock = FakeClock(),
        reconnect = PokerReconnectController(
            PokerReconnectPolicy(jitterFraction = 0.0),
        ),
    )

    private class FakeFactory : PokerListenerFactory {
        var failuresRemaining = 0
        var openCount = 0
        val listeners = mutableListOf<FakeListener>()

        override fun open(): PokerListenerSocket {
            openCount++
            if (failuresRemaining > 0) {
                failuresRemaining--
                error("listener unavailable")
            }
            return FakeListener().also(listeners::add)
        }
    }

    private class FakeConnector : PokerConnectionConnector {
        private val sockets = Channel<PokerFrameSocket>(Channel.UNLIMITED)
        var attempts = 0
            private set

        override suspend fun connect(): PokerFrameSocket {
            attempts++
            return sockets.receive()
        }

        fun offer(socket: PokerFrameSocket) {
            sockets.trySend(socket)
        }
    }

    private class FakeListener : PokerListenerSocket {
        private val sockets = Channel<PokerFrameSocket>(Channel.UNLIMITED)
        var closed = false
            private set

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

    private class FakeFrameSocket : PokerFrameSocket {
        private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val sent = mutableListOf<ByteArray>()
        var closed = false
            private set

        fun offerPeerOffer(
            major: Int = POKER_PROTOCOL_MAJOR,
            version: Int = POKER_PROTOCOL_VERSION,
        ) {
            offerPeerFrame(
                type = POKER_PROTOCOL_OFFER_TYPE,
                sequence = 1,
                messageId = "peer-offer",
                payload = PokerProtocolJson.encodeToJsonElement(
                    PokerProtocolOffer.serializer(),
                    PokerProtocolOffer(major = major),
                ).jsonObject,
                version = version,
            )
        }

        fun offerPeerFrame(
            type: String,
            sequence: Long,
            messageId: String = "peer-message-$sequence",
            payload: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject { },
            version: Int = POKER_PROTOCOL_VERSION,
        ) {
            incoming.trySend(
                PokerFrameCodec.encode(
                    ProtocolEnvelope(
                        type = type,
                        messageId = messageId,
                        sessionId = "dealer",
                        sentAtMs = 0,
                        version = version,
                        sequence = sequence,
                        payload = payload,
                    ),
                ),
            )
        }

        override suspend fun sendFrame(frame: ByteArray) {
            sent += frame
        }

        override suspend fun receiveFrame(): ByteArray? = incoming.receive()

        override fun close() {
            closed = true
            incoming.close()
        }

        fun sentTypes(): List<String> = sent.map { PokerFrameCodec.decode(it).type }
    }

    private class FakeScheduler : PokerScheduler {
        private data class Entry(
            val delayMs: Long,
            val task: () -> Unit,
            var cancelled: Boolean = false,
        )

        private val entries = ArrayDeque<Entry>()
        val delays = mutableListOf<Long>()

        override fun schedule(delayMs: Long, task: () -> Unit): PokerScheduledTask {
            val entry = Entry(delayMs, task)
            entries += entry
            delays += delayMs
            return PokerScheduledTask { entry.cancelled = true }
        }

        fun runNext() {
            while (entries.isNotEmpty()) {
                val entry = entries.removeFirst()
                if (!entry.cancelled) {
                    entry.task()
                    return
                }
            }
            error("No scheduled Poker task")
        }

        fun runAll() {
            while (entries.any { !it.cancelled }) runNext()
        }
    }

    private class FakeClock(var nowMs: Long = 0) : PokerClock {
        override fun nowMs(): Long = nowMs
    }

    private class LifoDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addFirst(block)
        }

        fun runNext() {
            check(queue.isNotEmpty()) { "No queued coroutine" }
            queue.removeFirst().run()
        }
    }
}
