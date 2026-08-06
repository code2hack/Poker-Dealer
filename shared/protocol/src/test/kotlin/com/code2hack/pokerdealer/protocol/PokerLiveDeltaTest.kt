package com.code2hack.pokerdealer.protocol

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardRole
import com.code2hack.pokerdealer.domain.CardSource
import com.code2hack.pokerdealer.domain.CardState
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ThreadWorkState
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
        source = CardSource.CODEX_AGENT_MESSAGE,
        contentComplete = complete,
    )

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
