package com.code2hack.pokerdealer.protocol

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardRole
import com.code2hack.pokerdealer.domain.CardSource
import com.code2hack.pokerdealer.domain.CardState
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ThreadWorkState
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PokerSnapshotTest {
    @Test
    fun `empty fixture and empty snapshot install atomically`() {
        val fixture = PokerProtocolJson.decodeFromString<PokerSnapshot>(
            checkNotNull(javaClass.getResource("/poker/snapshot-empty.json")).readText(),
        )
        PokerSnapshotWire.validate(fixture)

        val installer = PokerSnapshotInstaller()
        val result = install(installer, PokerSnapshotWire.encode(fixture, "empty"))

        assertEquals(PokerSnapshotInstallStatus.INSTALLED, result.status)
        assertEquals(fixture, installer.installedSnapshot)
        assertEquals(1L, result.acknowledgement?.revision)
    }

    @Test
    fun `multi-pile snapshot preserves host-qualified cards and projection metadata`() {
        val spark = CodexThreadLocator("spark", "thread-1")
        val termux = CodexThreadLocator("fold6-termux", "thread-2")
        val snapshot = PokerSnapshot(
            revision = 2,
            projection = PokerSnapshotProjection(
                orderedPiles = listOf(
                    metadata(spark, ThreadWorkState.BUSY, order = 0),
                    metadata(termux, ThreadWorkState.READY, order = 1),
                ),
                focused = termux,
                hudVisible = true,
            ),
            piles = listOf(
                PokerSnapshotPile(
                    metadata(spark, ThreadWorkState.BUSY, order = 0),
                    cards = listOf(card(spark, "agent", "Spark output")),
                ),
                PokerSnapshotPile(
                    metadata(termux, ThreadWorkState.READY, order = 1),
                    cards = listOf(card(termux, "user", "Termux input")),
                ),
            ),
        )

        val installer = PokerSnapshotInstaller()
        val result = install(installer, PokerSnapshotWire.encode(snapshot, "multi", 32))

        assertEquals(PokerSnapshotInstallStatus.INSTALLED, result.status)
        assertEquals(snapshot, installer.installedSnapshot)
        assertEquals(termux, installer.installedSnapshot?.projection?.focused)
        assertEquals("Spark output", installer.installedSnapshot?.piles?.first()?.cards?.single()?.fullText)
    }

    @Test
    fun `large card content uses framing chunks without a card size ceiling`() {
        val locator = CodexThreadLocator("spark", "large")
        val text = "中🙂line\n".repeat(120_000)
        val snapshot = snapshot(
            revision = 3,
            piles = listOf(
                PokerSnapshotPile(
                    metadata(locator, ThreadWorkState.READY, order = 0),
                    listOf(card(locator, "large-card", text)),
                ),
            ),
        )
        val transfer = PokerSnapshotWire.encode(snapshot, "large", maxChunkBytes = 4_096)

        assertTrue(text.toByteArray().size > 512 * 1_024)
        assertTrue(transfer.chunks.all { it.bytes.size <= DEFAULT_MAX_FRAME_BYTES })
        assertTrue(transfer.chunks.size > 100)
        assertTrue(
            PokerFrameCodec.encode(
                ProtocolEnvelope(
                    type = POKER_SNAPSHOT_CHUNK_TYPE,
                    messageId = "message",
                    sessionId = "session",
                    sentAtMs = 0,
                    epoch = 1,
                    stream = POKER_SNAPSHOT_STREAM,
                    sequence = 1,
                    payload = PokerSnapshotWire.chunkPayload(
                        PokerSnapshotChunk(
                            snapshotId = "frame",
                            index = 0,
                            count = 1,
                            bytes = ByteArray(DEFAULT_POKER_SNAPSHOT_CHUNK_BYTES),
                        ),
                    ),
                ),
            ).size <= DEFAULT_MAX_FRAME_BYTES,
        )
        assertEquals(
            PokerSnapshotInstallStatus.INSTALLED,
            install(PokerSnapshotInstaller(), transfer).status,
        )
    }

    @Test
    fun `incomplete replacement leaves the prior complete snapshot readable`() {
        val installer = PokerSnapshotInstaller()
        install(installer, PokerSnapshotWire.encode(snapshot(1), "old"))
        val replacement = PokerSnapshotWire.encode(snapshot(2), "new", maxChunkBytes = 64)

        assertEquals(PokerSnapshotInstallStatus.STARTED, installer.begin(replacement.manifest).status)
        replacement.chunks.dropLast(1).forEach { chunk ->
            assertEquals(PokerSnapshotInstallStatus.CHUNK_ACCEPTED, installer.acceptChunk(chunk).status)
        }
        assertEquals(
            PokerSnapshotInstallStatus.INCOMPLETE,
            installer.complete(PokerSnapshotComplete("new")).status,
        )
        assertEquals(1L, installer.installedSnapshot?.revision)
        assertNull(installer.stagingRevision)
    }

    @Test
    fun `duplicate chunks are idempotent and conflicting duplicates discard the stage`() {
        val transfer = PokerSnapshotWire.encode(snapshot(1), "duplicate", maxChunkBytes = 32)
        val installer = PokerSnapshotInstaller()
        assertEquals(PokerSnapshotInstallStatus.STARTED, installer.begin(transfer.manifest).status)
        val first = transfer.chunks.first()
        assertEquals(PokerSnapshotInstallStatus.CHUNK_ACCEPTED, installer.acceptChunk(first).status)
        assertEquals(PokerSnapshotInstallStatus.DUPLICATE, installer.acceptChunk(first).status)
        assertEquals(
            PokerSnapshotInstallStatus.MALFORMED,
            installer.acceptChunk(first.copy(bytes = first.bytes + byteArrayOf(0))).status,
        )
        assertNull(installer.stagingRevision)
        assertNull(installer.installedSnapshot)
    }

    @Test
    fun `newer snapshot supersedes late chunks without damaging the newer stage`() {
        val old = PokerSnapshotWire.encode(snapshot(2), "old", maxChunkBytes = 32)
        val newer = PokerSnapshotWire.encode(snapshot(3), "new", maxChunkBytes = 32)
        val installer = PokerSnapshotInstaller()

        installer.begin(old.manifest)
        assertEquals(PokerSnapshotInstallStatus.STARTED, installer.begin(newer.manifest).status)
        assertEquals(PokerSnapshotInstallStatus.SUPERSEDED, installer.acceptChunk(old.chunks.first()).status)
        newer.chunks.forEach { installer.acceptChunk(it) }
        assertEquals(
            PokerSnapshotInstallStatus.INSTALLED,
            installer.complete(PokerSnapshotComplete("new")).status,
        )
        assertEquals(3L, installer.installedSnapshot?.revision)
    }

    @Test
    fun `bad digest and storage pressure do not replace the prior snapshot`() {
        val installer = PokerSnapshotInstaller()
        install(installer, PokerSnapshotWire.encode(snapshot(1), "old"))
        val bad = PokerSnapshotWire.encode(snapshot(2), "bad").let {
            it.copy(manifest = it.manifest.copy(sha256 = "0".repeat(64)))
        }
        installer.begin(bad.manifest)
        bad.chunks.forEach { installer.acceptChunk(it) }
        assertEquals(
            PokerSnapshotInstallStatus.MALFORMED,
            installer.complete(PokerSnapshotComplete("bad")).status,
        )
        assertEquals(1L, installer.installedSnapshot?.revision)

        val failing = PokerSnapshotInstaller(PokerSnapshotStageFactory { error("storage full") })
        assertEquals(
            PokerSnapshotInstallStatus.STORAGE_FAILED,
            failing.begin(PokerSnapshotWire.encode(snapshot(2), "pressure").manifest).status,
        )
        assertNull(failing.installedSnapshot)
    }

    @Test
    fun `restart discards derived card content and requests a fresh snapshot`() {
        val installer = PokerSnapshotInstaller()
        install(installer, PokerSnapshotWire.encode(snapshot(1), "before-restart"))

        assertEquals("restart", installer.requestAfterRestart().reason)
        assertNull(installer.installedSnapshot)
        assertNull(installer.stagingRevision)
    }

    @Test
    fun `connection handler requests and installs a complete snapshot`() = runTest {
        val snapshot = snapshot(4)
        val context = PokerConnectionContext(
            epoch = PokerConnectionEpoch(1),
            negotiation = PokerProtocolNegotiation(
                access = PokerProtocolAccess.READ_WRITE,
                majorCompatible = true,
                capabilities = setOf(POKER_SNAPSHOT_CAPABILITY),
                missingRequiredCapabilities = emptySet(),
            ),
        )
        val pokerInstaller = PokerSnapshotInstaller()
        val poker = PokerSnapshotConnectionHandler(
            role = PokerSnapshotRole.POKER,
            installer = pokerInstaller,
        )
        val dealer = PokerSnapshotConnectionHandler(
            role = PokerSnapshotRole.DEALER,
            snapshotSource = { snapshot },
        )
        val outgoing = mutableListOf<WireMessage>()
        val send: PokerEnvelopeSender = { type, stream, payload, replyTo ->
            outgoing += WireMessage(type, stream, payload, replyTo)
        }

        poker.onConnected(context, send)
        val request = outgoing.single()
        outgoing.clear()
        dealer.onEnvelope(
            context,
            envelope(POKER_SNAPSHOT_REQUEST_TYPE, request.payload, request.messageId),
            send,
        )
        val transferMessages = outgoing.toList()
        transferMessages.forEach { message ->
            poker.onEnvelope(
                context,
                envelope(message.type, message.payload, message.messageId),
                send,
            )
        }

        assertEquals(snapshot, pokerInstaller.installedSnapshot)
        assertTrue(outgoing.any { it.type == POKER_SNAPSHOT_ACK_TYPE })
    }

    private fun install(
        installer: PokerSnapshotInstaller,
        transfer: PokerSnapshotTransfer,
    ): PokerSnapshotInstallResult {
        installer.begin(transfer.manifest)
        transfer.chunks.forEach(installer::acceptChunk)
        return installer.complete(PokerSnapshotComplete(transfer.manifest.snapshotId))
    }

    private fun snapshot(
        revision: Long,
        piles: List<PokerSnapshotPile> = emptyList(),
    ) = PokerSnapshot(
        revision = revision,
        projection = PokerSnapshotProjection(
            orderedPiles = piles.map { it.metadata },
            focused = piles.firstOrNull()?.metadata?.locator,
            hudVisible = piles.isNotEmpty(),
        ),
        piles = piles,
    )

    private fun metadata(
        locator: CodexThreadLocator,
        state: ThreadWorkState,
        order: Long,
    ) = PokerSnapshotPileMetadata(
        locator = locator,
        attachmentOrder = order,
        workState = state.name,
        stateChangedAtMs = order + 1,
        available = true,
        outcome = null,
    )

    private fun card(locator: CodexThreadLocator, id: String, text: String) = Card(
        id = id,
        conversationId = "${locator.hostId}/${locator.threadId}",
        sequence = 1,
        revision = 1,
        role = if (id == "user") CardRole.USER else CardRole.AGENT,
        state = CardState.COMMITTED,
        fullText = text,
        createdAtMs = 1,
        updatedAtMs = 1,
        source = if (id == "user") CardSource.DEALER_INPUT else CardSource.CODEX_AGENT_MESSAGE,
    )

    private fun envelope(type: String, payload: kotlinx.serialization.json.JsonObject, messageId: String) =
        ProtocolEnvelope(
            type = type,
            messageId = messageId,
            sessionId = "session",
            sentAtMs = 0,
            epoch = 1,
            stream = POKER_SNAPSHOT_STREAM,
            sequence = 1,
            payload = payload,
        )

    private data class WireMessage(
        val type: String,
        val stream: String,
        val payload: kotlinx.serialization.json.JsonObject,
        val replyTo: String?,
        val messageId: String = "message-${counter.incrementAndGet()}",
    )

    private companion object {
        val counter = AtomicInteger()
    }
}
