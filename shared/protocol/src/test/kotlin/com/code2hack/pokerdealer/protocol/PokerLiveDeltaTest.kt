package com.code2hack.pokerdealer.protocol

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardRole
import com.code2hack.pokerdealer.domain.CardSource
import com.code2hack.pokerdealer.domain.CardState
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ThreadWorkState
import java.util.ArrayDeque
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PokerLiveDeltaTest {
    @Test
    fun `connected handlers request a snapshot then stream and acknowledge a live append`() = runTest {
        val base = snapshot(1, card(text = "a", revision = 1, complete = false))
        val growing = snapshot(2, card(text = "ab", revision = 2, complete = false))
        var current = base
        val installed = mutableListOf<PokerSnapshot>()
        val outgoing = mutableListOf<WireMessage>()
        val send: PokerEnvelopeSender = { type, stream, payload, replyTo ->
            outgoing += WireMessage(type, stream, payload, replyTo, "m${outgoing.size}")
        }
        val context = PokerConnectionContext(
            epoch = PokerConnectionEpoch(1),
            negotiation = PokerProtocolNegotiation(
                access = PokerProtocolAccess.READ_WRITE,
                majorCompatible = true,
                capabilities = setOf(POKER_SNAPSHOT_CAPABILITY, POKER_LIVE_DELTA_CAPABILITY),
                missingRequiredCapabilities = emptySet(),
            ),
        )
        val dealer = PokerSnapshotConnectionHandler(
            role = PokerSnapshotRole.DEALER,
            snapshotSource = { current },
            snapshotId = { "snapshot" },
        )
        val poker = PokerSnapshotConnectionHandler(
            role = PokerSnapshotRole.POKER,
            installer = PokerSnapshotInstaller(),
            onInstalled = { installed += it },
        )

        dealer.onConnected(context, send)
        poker.onConnected(context, send)
        val request = outgoing.single()
        outgoing.clear()
        dealer.onEnvelope(context, request.envelope(), send)
        val transfer = outgoing.toList()
        outgoing.clear()
        transfer.forEach { poker.onEnvelope(context, it.envelope(), send) }
        val snapshotAck = outgoing.single()
        outgoing.clear()
        dealer.onEnvelope(context, snapshotAck.envelope(), send)

        current = growing
        dealer.publish(growing)
        val delta = outgoing.single { it.type == POKER_LIVE_DELTA_TYPE }
        outgoing.clear()
        poker.onEnvelope(context, delta.envelope(), send)
        assertEquals(growing, installed.last())
        val deltaAck = outgoing.single { it.type == POKER_LIVE_DELTA_ACK_TYPE }
        dealer.onEnvelope(context, deltaAck.envelope(), send)
        assertEquals(listOf(POKER_LIVE_DELTA_ACK_TYPE), outgoing.map(WireMessage::type))
    }

    @Test
    fun `growing UTF-8 text applies only after every ordered chunk`() {
        val base = snapshot(1, card(text = "a", revision = 1, complete = false))
        val growing = snapshot(2, card(text = "a🙂b", revision = 2, complete = false, updatedAtMs = 2))
        val deltas = checkNotNull(PokerLiveDeltaWire.build(base, growing, maxChunkBytes = 4))
        assertEquals(listOf(1L, 5L), deltas.map(PokerCardDelta::offsetUtf8Bytes))
        assertTrue(deltas.dropLast(1).all { !it.isFinal })

        val receiver = PokerLiveDeltaReceiver()
        receiver.installSnapshot(base)
        assertEquals(PokerLiveDeltaInstallStatus.QUEUED, receiver.accept(deltas[1]).status)
        assertEquals(PokerLiveDeltaInstallStatus.APPLIED, receiver.accept(deltas[0]).status)
        assertEquals("a🙂b", receiver.installedSnapshot!!.piles.single().cards.single().fullText)
        assertEquals(2L, receiver.installedSnapshot!!.revision)
    }

    @Test
    fun `sender and receiver reject skipped card revisions`() {
        val base = snapshot(1, card(text = "a", revision = 1, complete = false))
        val jumped = snapshot(2, card(text = "ab", revision = 3, complete = false))
        assertNull(PokerLiveDeltaWire.build(base, jumped))

        val valid = checkNotNull(
            PokerLiveDeltaWire.build(
                base,
                snapshot(2, card(text = "ab", revision = 2, complete = false)),
            ),
        ).single()
        val receiver = PokerLiveDeltaReceiver().also { it.installSnapshot(base) }
        assertEquals(
            PokerLiveDeltaInstallStatus.MALFORMED,
            receiver.accept(valid.copy(cardRevision = 3)).status,
        )
        assertEquals(1L, receiver.installedSnapshot!!.revision)
    }

    @Test
    fun `receiver validates every split UTF-8 chunk offset`() {
        val base = snapshot(1, card(text = "a", revision = 1, complete = false))
        val growing = snapshot(2, card(text = "a🙂b", revision = 2, complete = false))
        val deltas = checkNotNull(PokerLiveDeltaWire.build(base, growing, maxChunkBytes = 4))
        val receiver = PokerLiveDeltaReceiver().also { it.installSnapshot(base) }

        assertEquals(PokerLiveDeltaInstallStatus.QUEUED, receiver.accept(deltas[0]).status)
        assertEquals(
            PokerLiveDeltaInstallStatus.RESNAPSHOT_REQUIRED,
            receiver.accept(deltas[1].copy(offsetUtf8Bytes = 4)).status,
        )
        assertEquals(base, receiver.installedSnapshot)
    }

    @Test
    fun `final append carries one authoritative complete card revision`() {
        val base = snapshot(1, card(text = "partial", revision = 1, complete = false))
        val final = snapshot(
            2,
            card(
                text = "partial answer",
                revision = 2,
                complete = true,
                state = CardState.COMMITTED,
                updatedAtMs = 4,
            ),
        )
        val receiver = PokerLiveDeltaReceiver()
        receiver.installSnapshot(base)
        val delta = checkNotNull(PokerLiveDeltaWire.build(base, final, maxChunkBytes = 64)).single()

        assertTrue(delta.isFinal)
        assertEquals(final.piles.single().cards.single(), delta.authoritativeCard)
        assertEquals(PokerLiveDeltaInstallStatus.APPLIED, receiver.accept(delta).status)
        assertEquals(final, receiver.installedSnapshot)
    }

    @Test
    fun `growing command and file cards become authoritative only when terminal`() {
        listOf(CardSource.CODEX_COMMAND, CardSource.CODEX_FILE_CHANGE).forEach { source ->
            val base = snapshot(
                1,
                card(
                    text = "command output",
                    revision = 1,
                    complete = true,
                    source = source,
                ),
            )
            val growing = snapshot(
                2,
                card(
                    text = "command output more",
                    revision = 2,
                    complete = true,
                    source = source,
                    updatedAtMs = 2,
                ),
            )
            val final = snapshot(
                3,
                card(
                    text = "command output more done",
                    revision = 3,
                    complete = true,
                    state = CardState.COMMITTED,
                    source = source,
                    updatedAtMs = 3,
                ),
            )
            val growingDeltas = checkNotNull(PokerLiveDeltaWire.build(base, growing))
            assertTrue(growingDeltas.all { !it.isFinal && it.authoritativeCard == null })

            val receiver = PokerLiveDeltaReceiver().also { it.installSnapshot(base) }
            growingDeltas.forEach { assertEquals(PokerLiveDeltaInstallStatus.APPLIED, receiver.accept(it).status) }
            val finalDeltas = checkNotNull(PokerLiveDeltaWire.build(growing, final))
            assertEquals(1, finalDeltas.count(PokerCardDelta::isFinal))
            assertEquals(final.piles.single().cards.single(), finalDeltas.last().authoritativeCard)
            finalDeltas.forEach { assertEquals(PokerLiveDeltaInstallStatus.APPLIED, receiver.accept(it).status) }
            assertEquals(final, receiver.installedSnapshot)
        }
    }

    @Test
    fun `duplicate and late deltas acknowledge the installed revision`() {
        val base = snapshot(1, card(text = "a", revision = 1, complete = false))
        val next = snapshot(2, card(text = "ab", revision = 2, complete = false))
        val delta = checkNotNull(PokerLiveDeltaWire.build(base, next)).single()
        val receiver = PokerLiveDeltaReceiver().also { it.installSnapshot(base) }

        assertEquals(PokerLiveDeltaInstallStatus.APPLIED, receiver.accept(delta).status)
        val duplicate = receiver.accept(delta)
        assertEquals(PokerLiveDeltaInstallStatus.DUPLICATE, duplicate.status)
        assertEquals(2L, duplicate.acknowledgedRevision)
    }

    @Test
    fun `unknown same-major delta fields are ignored`() {
        val base = snapshot(1, card(text = "a", revision = 1, complete = false))
        val next = snapshot(2, card(text = "ab", revision = 2, complete = false))
        val delta = checkNotNull(PokerLiveDeltaWire.build(base, next)).single()
        val payload = JsonObject(
            PokerLiveDeltaWire.deltaPayload(delta) + ("futureField" to JsonPrimitive(true)),
        )

        val decoded = PokerLiveDeltaWire.delta(payload)
        assertEquals(delta.copy(appendBytes = decoded.appendBytes), decoded)
        assertTrue(delta.appendBytes.contentEquals(decoded.appendBytes))
    }

    @Test
    fun `wrong offset and missing revision request a fresh snapshot`() {
        val base = snapshot(1, card(text = "hello", revision = 1, complete = false))
        val next = snapshot(2, card(text = "hello!", revision = 2, complete = false))
        val delta = checkNotNull(PokerLiveDeltaWire.build(base, next)).single()
        val receiver = PokerLiveDeltaReceiver().also { it.installSnapshot(base) }

        assertEquals(
            PokerLiveDeltaInstallStatus.RESNAPSHOT_REQUIRED,
            receiver.accept(delta.copy(offsetUtf8Bytes = 4)).status,
        )
        assertNull(receiver.accept(delta.copy(baseSnapshotRevision = 4, snapshotRevision = 5)).snapshot)
        assertEquals(1L, receiver.installedSnapshot!!.revision)
    }

    @Test
    fun `sender releases queued revisions only after acknowledgements`() {
        val base = snapshot(1, card(text = "a", revision = 1, complete = false))
        val growing = snapshot(2, card(text = "ab", revision = 2, complete = false))
        val final = snapshot(
            3,
            card(text = "abc", revision = 3, complete = true, state = CardState.COMMITTED),
        )
        val sender = PokerLiveDeltaSender()
        sender.snapshotSent(base)
        assertEquals(PokerLiveDeltaSendAction.None, sender.acknowledged(1))
        assertTrue(sender.publish(growing) is PokerLiveDeltaSendAction.Deltas)
        assertEquals(PokerLiveDeltaSendAction.None, sender.publish(final))
        val released = sender.acknowledged(2)
        assertTrue(released is PokerLiveDeltaSendAction.Deltas)
        assertEquals(3L, (released as PokerLiveDeltaSendAction.Deltas).deltas.single().snapshotRevision)
    }

    @Test
    fun `queue overflow falls back to a complete snapshot`() {
        val base = snapshot(1, card(text = "a", revision = 1, complete = false))
        val first = snapshot(2, card(text = "ab", revision = 2, complete = false))
        val second = snapshot(3, card(text = "abc", revision = 3, complete = false))
        val third = snapshot(4, card(text = "abcd", revision = 4, complete = false))
        val sender = PokerLiveDeltaSender(maxQueuedSnapshots = 1)
        sender.snapshotSent(base)
        sender.acknowledged(1)
        sender.publish(first)
        sender.publish(second)

        val fallback = sender.publish(third)
        assertEquals(third, (fallback as PokerLiveDeltaSendAction.Snapshot).snapshot)
    }

    @Test
    fun `snapshot fallback resumes with the newest queued revision`() {
        val base = snapshot(1, card(text = "a", revision = 1, complete = false))
        val growing = snapshot(2, card(text = "ab", revision = 2, complete = false))
        val reordered = snapshot(
            3,
            card(text = "ab", revision = 2, complete = false),
            workState = ThreadWorkState.READY,
        )
        val latest = snapshot(
            4,
            card(text = "abc", revision = 3, complete = true, state = CardState.COMMITTED),
            workState = ThreadWorkState.READY,
        )
        val sender = PokerLiveDeltaSender()
        sender.snapshotSent(base)
        sender.acknowledged(1)
        assertTrue(sender.publish(growing) is PokerLiveDeltaSendAction.Deltas)
        assertEquals(PokerLiveDeltaSendAction.None, sender.publish(reordered))
        assertEquals(PokerLiveDeltaSendAction.None, sender.publish(latest))

        val fallback = sender.acknowledged(2)
        assertEquals(reordered, (fallback as PokerLiveDeltaSendAction.Snapshot).snapshot)
        val resumed = sender.acknowledged(3)
        assertTrue(resumed is PokerLiveDeltaSendAction.Deltas)
        assertEquals(
            4L,
            (resumed as PokerLiveDeltaSendAction.Deltas).deltas.single().snapshotRevision,
        )
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `incomplete delta requests a fresh snapshot after bounded recovery`() = runTest {
        val base = snapshot(1, card(text = "a", revision = 1, complete = false))
        val growing = snapshot(2, card(text = "a🙂b", revision = 2, complete = false))
        val scheduler = ManualPokerScheduler()
        val outgoing = mutableListOf<WireMessage>()
        val send: PokerEnvelopeSender = { type, stream, payload, replyTo ->
            outgoing += WireMessage(type, stream, payload, replyTo, "m${outgoing.size}")
        }
        val context = liveContext()
        val poker = PokerSnapshotConnectionHandler(
            role = PokerSnapshotRole.POKER,
            installer = PokerSnapshotInstaller(),
            scheduler = scheduler,
            scope = this,
        )
        val dealer = PokerSnapshotConnectionHandler(
            role = PokerSnapshotRole.DEALER,
            snapshotSource = { base },
            snapshotId = { "snapshot" },
        )

        poker.onConnected(context, send)
        val request = outgoing.single()
        outgoing.clear()
        dealer.onEnvelope(context, request.envelope(), send)
        val transfer = outgoing.toList()
        outgoing.clear()
        transfer.forEach { poker.onEnvelope(context, it.envelope(), send) }
        outgoing.clear()

        val deltas = checkNotNull(PokerLiveDeltaWire.build(base, growing, maxChunkBytes = 4))
        poker.onEnvelope(
            context,
            WireMessage(
                POKER_LIVE_DELTA_TYPE,
                POKER_LIVE_DELTA_STREAM,
                PokerLiveDeltaWire.deltaPayload(deltas.first()),
                null,
                "delta",
            ).envelope(),
            send,
        )
        scheduler.runNext()
        runCurrent()

        assertTrue(outgoing.any { it.type == POKER_SNAPSHOT_REQUEST_TYPE })
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `slow Poker forces a newer full snapshot after bounded ACK recovery`() = runTest {
        val base = snapshot(1, card(text = "a", revision = 1, complete = false))
        val growing = snapshot(2, card(text = "ab", revision = 2, complete = false))
        var current = base
        val scheduler = ManualPokerScheduler()
        val outgoing = mutableListOf<WireMessage>()
        val send: PokerEnvelopeSender = { type, stream, payload, replyTo ->
            outgoing += WireMessage(type, stream, payload, replyTo, "m${outgoing.size}")
        }
        val context = liveContext()
        val dealer = PokerSnapshotConnectionHandler(
            role = PokerSnapshotRole.DEALER,
            snapshotSource = { current },
            snapshotId = { "snapshot" },
            scheduler = scheduler,
            scope = this,
        )

        dealer.onConnected(context, send)
        dealer.onEnvelope(
            context,
            WireMessage(
                POKER_SNAPSHOT_REQUEST_TYPE,
                POKER_SNAPSHOT_STREAM,
                PokerSnapshotWire.requestPayload(PokerSnapshotRequest()),
                null,
                "request",
            ).envelope(),
            send,
        )
        val manifest = PokerSnapshotWire.manifest(
            outgoing.first { it.type == POKER_SNAPSHOT_BEGIN_TYPE }.payload,
        )
        dealer.onEnvelope(
            context,
            WireMessage(
                POKER_SNAPSHOT_ACK_TYPE,
                POKER_SNAPSHOT_STREAM,
                PokerSnapshotWire.acknowledgementPayload(
                    PokerSnapshotAcknowledgement(manifest.snapshotId, base.revision),
                ),
                null,
                "ack",
            ).envelope(),
            send,
        )
        outgoing.clear()

        current = growing
        dealer.publish(growing)
        assertTrue(outgoing.any { it.type == POKER_LIVE_DELTA_TYPE })
        scheduler.runNext()
        runCurrent()

        val replacement = outgoing.filter { it.type == POKER_SNAPSHOT_BEGIN_TYPE }.single()
        assertEquals(2L, PokerSnapshotWire.manifest(replacement.payload).revision)
    }

    private fun snapshot(
        revision: Long,
        card: Card,
        workState: ThreadWorkState = ThreadWorkState.BUSY,
    ) = PokerSnapshot(
        revision = revision,
        projection = PokerSnapshotProjection(
            orderedPiles = listOf(
                PokerSnapshotPileMetadata(
                    locator = CodexThreadLocator("spark", "thread"),
                    attachmentOrder = 0,
                    workState = workState.name,
                    stateChangedAtMs = 1,
                    available = true,
                ),
            ),
        ),
        piles = listOf(
            PokerSnapshotPile(
                metadata = PokerSnapshotProjection(
                    orderedPiles = listOf(
                        PokerSnapshotPileMetadata(
                            locator = CodexThreadLocator("spark", "thread"),
                            attachmentOrder = 0,
                            workState = workState.name,
                            stateChangedAtMs = 1,
                            available = true,
                        ),
                    ),
                ).orderedPiles.single(),
                cards = listOf(card),
            ),
        ),
    )

    private fun card(
        text: String,
        revision: Long,
        complete: Boolean,
        state: CardState = CardState.OPEN,
        updatedAtMs: Long = revision,
        source: CardSource = CardSource.CODEX_AGENT_MESSAGE,
    ) = Card(
        id = "card",
        conversationId = "spark/thread",
        sequence = 1,
        revision = revision,
        role = CardRole.AGENT,
        state = state,
        fullText = text,
        createdAtMs = 1,
        updatedAtMs = updatedAtMs,
        source = source,
        contentComplete = complete,
    )

    private fun liveContext() = PokerConnectionContext(
        epoch = PokerConnectionEpoch(1),
        negotiation = PokerProtocolNegotiation(
            access = PokerProtocolAccess.READ_WRITE,
            majorCompatible = true,
            capabilities = setOf(POKER_SNAPSHOT_CAPABILITY, POKER_LIVE_DELTA_CAPABILITY),
            missingRequiredCapabilities = emptySet(),
        ),
    )

    private class ManualPokerScheduler : PokerScheduler {
        private data class Entry(
            val task: () -> Unit,
            var cancelled: Boolean = false,
        )

        private val entries = ArrayDeque<Entry>()

        override fun schedule(delayMs: Long, task: () -> Unit): PokerScheduledTask {
            require(delayMs == POKER_LIVE_DELTA_RECOVERY_TIMEOUT_MS)
            val entry = Entry(task)
            entries += entry
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
    }

    private data class WireMessage(
        val type: String,
        val stream: String,
        val payload: kotlinx.serialization.json.JsonObject,
        val replyTo: String?,
        val messageId: String,
    ) {
        fun envelope() = ProtocolEnvelope(
            type = type,
            messageId = messageId,
            sessionId = "session",
            sentAtMs = 0,
            epoch = 1,
            stream = stream,
            sequence = 1,
            replyTo = replyTo,
            payload = payload,
        )
    }
}
