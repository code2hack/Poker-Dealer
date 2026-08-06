package com.code2hack.pokerdealer.protocol

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.PokerPileMetadata
import com.code2hack.pokerdealer.domain.ThreadPile
import com.code2hack.pokerdealer.domain.TurnOutcome
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Wire metadata is separate from the mutable Dealer/Poker navigation model. */
@Serializable
data class PokerSnapshotPileMetadata(
    val locator: CodexThreadLocator,
    val attachmentOrder: Long,
    val workState: String?,
    val stateChangedAtMs: Long,
    val available: Boolean,
    val outcome: TurnOutcome? = null,
)

@Serializable
data class PokerSnapshotProjection(
    val orderedPiles: List<PokerSnapshotPileMetadata> = emptyList(),
    val unknownWorkState: List<PokerSnapshotPileMetadata> = emptyList(),
    val hudVisible: Boolean = false,
    val focused: CodexThreadLocator? = null,
)

@Serializable
data class PokerSnapshotPile(
    val metadata: PokerSnapshotPileMetadata,
    val cards: List<Card> = emptyList(),
)

@Serializable
data class PokerSnapshot(
    val revision: Long,
    val projection: PokerSnapshotProjection = PokerSnapshotProjection(),
    val piles: List<PokerSnapshotPile> = emptyList(),
)

@Serializable
data class PokerSnapshotManifest(
    val snapshotId: String,
    val revision: Long,
    val chunkCount: Int,
    val byteCount: Long,
    val sha256: String,
)

@Serializable
data class PokerSnapshotChunk(
    val snapshotId: String,
    val index: Int,
    val count: Int,
    val bytes: ByteArray,
)

@Serializable
data class PokerSnapshotComplete(val snapshotId: String)

@Serializable
data class PokerSnapshotAcknowledgement(
    val snapshotId: String,
    val revision: Long,
)

@Serializable
data class PokerSnapshotRequest(val reason: String = "connection")

data class PokerSnapshotTransfer(
    val manifest: PokerSnapshotManifest,
    val chunks: List<PokerSnapshotChunk>,
) {
    init {
        require(chunks.size == manifest.chunkCount) { "Snapshot chunks do not match the manifest" }
    }
}

object PokerSnapshotWire {
    fun manifestPayload(manifest: PokerSnapshotManifest): JsonObject =
        PokerProtocolJson.encodeToJsonElement(
            PokerSnapshotManifest.serializer(),
            manifest,
        ).jsonObject

    fun chunkPayload(chunk: PokerSnapshotChunk): JsonObject =
        PokerProtocolJson.encodeToJsonElement(
            PokerSnapshotChunk.serializer(),
            chunk,
        ).jsonObject

    fun completePayload(complete: PokerSnapshotComplete): JsonObject =
        PokerProtocolJson.encodeToJsonElement(
            PokerSnapshotComplete.serializer(),
            complete,
        ).jsonObject

    fun acknowledgementPayload(acknowledgement: PokerSnapshotAcknowledgement): JsonObject =
        PokerProtocolJson.encodeToJsonElement(
            PokerSnapshotAcknowledgement.serializer(),
            acknowledgement,
        ).jsonObject

    fun requestPayload(request: PokerSnapshotRequest): JsonObject =
        PokerProtocolJson.encodeToJsonElement(
            PokerSnapshotRequest.serializer(),
            request,
        ).jsonObject

    fun manifest(payload: JsonObject): PokerSnapshotManifest =
        PokerProtocolJson.decodeFromJsonElement(PokerSnapshotManifest.serializer(), payload)

    fun chunk(payload: JsonObject): PokerSnapshotChunk =
        PokerProtocolJson.decodeFromJsonElement(PokerSnapshotChunk.serializer(), payload)

    fun complete(payload: JsonObject): PokerSnapshotComplete =
        PokerProtocolJson.decodeFromJsonElement(PokerSnapshotComplete.serializer(), payload)

    fun acknowledgement(payload: JsonObject): PokerSnapshotAcknowledgement =
        PokerProtocolJson.decodeFromJsonElement(
            PokerSnapshotAcknowledgement.serializer(),
            payload,
        )

    fun request(payload: JsonObject): PokerSnapshotRequest =
        PokerProtocolJson.decodeFromJsonElement(PokerSnapshotRequest.serializer(), payload)

    fun encode(
        snapshot: PokerSnapshot,
        snapshotId: String = UUID.randomUUID().toString(),
        maxChunkBytes: Int = DEFAULT_POKER_SNAPSHOT_CHUNK_BYTES,
    ): PokerSnapshotTransfer {
        validate(snapshot)
        require(snapshotId.isNotBlank()) { "Snapshot ID must not be blank" }
        require(maxChunkBytes in 1..DEFAULT_MAX_FRAME_BYTES) {
            "Snapshot chunks must fit one transport frame"
        }
        val bytes = PokerProtocolJson.encodeToString(PokerSnapshot.serializer(), snapshot)
            .encodeToByteArray()
        val rawChunks = bytes.chunked(maxChunkBytes)
        val chunks = rawChunks.mapIndexed { index, chunk ->
            PokerSnapshotChunk(
                snapshotId = snapshotId,
                index = index,
                count = rawChunks.size,
                bytes = chunk,
            )
        }
        return PokerSnapshotTransfer(
            manifest = PokerSnapshotManifest(
                snapshotId = snapshotId,
                revision = snapshot.revision,
                chunkCount = chunks.size,
                byteCount = bytes.size.toLong(),
                sha256 = sha256(bytes),
            ),
            chunks = chunks,
        )
    }

    fun decode(bytes: ByteArray): PokerSnapshot =
        PokerProtocolJson.decodeFromString(PokerSnapshot.serializer(), bytes.decodeToString())

    fun validate(snapshot: PokerSnapshot) {
        require(snapshot.revision > 0) { "Snapshot revision must be positive" }
        val projection = snapshot.projection
        val metadata = projection.orderedPiles + projection.unknownWorkState
        val metadataLocators = metadata.map(PokerSnapshotPileMetadata::locator)
        require(metadataLocators.size == metadataLocators.toSet().size) {
            "Snapshot projection contains duplicate piles"
        }
        require(projection.orderedPiles.all { it.workState != null }) {
            "Ordered snapshot piles require work state"
        }
        require(projection.unknownWorkState.all { it.workState == null }) {
            "Unknown snapshot piles must omit work state"
        }
        require(projection.focused == null || projection.focused in metadataLocators) {
            "Snapshot focus must name an attached pile"
        }

        val pileLocators = snapshot.piles.map { it.metadata.locator }
        require(pileLocators.size == pileLocators.toSet().size) {
            "Snapshot contains duplicate piles"
        }
        require(pileLocators.toSet() == metadataLocators.toSet()) {
            "Snapshot projection and pile content disagree"
        }
        val metadataByLocator = metadata.associateBy(PokerSnapshotPileMetadata::locator)
        require(snapshot.piles.all { metadataByLocator[it.metadata.locator] == it.metadata }) {
            "Snapshot pile metadata disagrees with its projection"
        }

        val cardIds = mutableSetOf<String>()
        snapshot.piles.forEach { pile ->
            require(pile.metadata.locator.hostId.isNotBlank()) { "Snapshot host ID is blank" }
            require(pile.metadata.locator.threadId.isNotBlank()) { "Snapshot thread ID is blank" }
            val conversationId = "${pile.metadata.locator.hostId}/${pile.metadata.locator.threadId}"
            pile.cards.forEach { card ->
                require(card.id.isNotBlank()) { "Snapshot card ID is blank" }
                require(card.conversationId == conversationId) {
                    "Snapshot card is not qualified by its pile"
                }
                require(cardIds.add("$conversationId\u0000${card.id}")) {
                    "Snapshot contains duplicate cards"
                }
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { "%02x".format(it) }

    private fun ByteArray.chunked(maxChunkBytes: Int): List<ByteArray> {
        if (isEmpty()) return listOf(ByteArray(0))
        return (indices step maxChunkBytes).map { start ->
            copyOfRange(start, (start + maxChunkBytes).coerceAtMost(size))
        }
    }
}

fun ThreadPile.toPokerSnapshotMetadata(): PokerSnapshotPileMetadata =
    PokerSnapshotPileMetadata(
        locator = locator,
        attachmentOrder = attachmentOrder,
        workState = workState?.name,
        stateChangedAtMs = stateChangedAtMs,
        available = available,
        outcome = outcome,
    )

fun PokerPileMetadata.toPokerSnapshotProjection(): PokerSnapshotProjection =
    PokerSnapshotProjection(
        orderedPiles = orderedPiles.map(ThreadPile::toPokerSnapshotMetadata),
        unknownWorkState = unknownWorkState.map(ThreadPile::toPokerSnapshotMetadata),
        hudVisible = hudVisible,
        focused = focused,
    )

enum class PokerSnapshotInstallStatus {
    STARTED,
    CHUNK_ACCEPTED,
    DUPLICATE,
    INSTALLED,
    SUPERSEDED,
    INCOMPLETE,
    MALFORMED,
    STORAGE_FAILED,
    NO_ACTIVE_SNAPSHOT,
}

data class PokerSnapshotInstallResult(
    val status: PokerSnapshotInstallStatus,
    val snapshot: PokerSnapshot? = null,
    val acknowledgement: PokerSnapshotAcknowledgement? = null,
)

interface PokerSnapshotStage {
    fun write(index: Int, bytes: ByteArray)
    fun read(index: Int): ByteArray
    fun discard()
}

fun interface PokerSnapshotStageFactory {
    fun create(manifest: PokerSnapshotManifest): PokerSnapshotStage
}

private class InMemoryPokerSnapshotStage : PokerSnapshotStage {
    private val chunks = mutableMapOf<Int, ByteArray>()

    override fun write(index: Int, bytes: ByteArray) {
        chunks[index] = bytes.copyOf()
    }

    override fun read(index: Int): ByteArray = chunks[index]?.copyOf()
        ?: error("Missing snapshot chunk $index")

    override fun discard() {
        chunks.clear()
    }
}

val InMemoryPokerSnapshotStageFactory = PokerSnapshotStageFactory { InMemoryPokerSnapshotStage() }

/** Installs only a fully decoded, digest-checked snapshot; card text is never persisted here. */
class PokerSnapshotInstaller(
    private val stageFactory: PokerSnapshotStageFactory = InMemoryPokerSnapshotStageFactory,
) {
    private data class Staging(
        val manifest: PokerSnapshotManifest,
        val stage: PokerSnapshotStage,
        val chunks: MutableSet<Int> = mutableSetOf(),
        var byteCount: Long = 0,
    )

    private var staging: Staging? = null
    private var complete: PokerSnapshot? = null
    private var completeManifest: PokerSnapshotManifest? = null

    val installedSnapshot: PokerSnapshot?
        get() = complete

    val stagingRevision: Long?
        get() = staging?.manifest?.revision

    fun begin(manifest: PokerSnapshotManifest): PokerSnapshotInstallResult {
        val validation = validateManifest(manifest)
        if (validation != null) return PokerSnapshotInstallResult(validation)
        if (manifest.revision <= (complete?.revision ?: 0L)) {
            return PokerSnapshotInstallResult(
                status = PokerSnapshotInstallStatus.SUPERSEDED,
                acknowledgement = completeManifest
                    ?.takeIf { it.revision == manifest.revision }
                    ?.let { PokerSnapshotAcknowledgement(it.snapshotId, it.revision) },
            )
        }

        staging?.let { current ->
            when {
                manifest.revision < current.manifest.revision ->
                    return PokerSnapshotInstallResult(PokerSnapshotInstallStatus.SUPERSEDED)
                manifest == current.manifest ->
                    return PokerSnapshotInstallResult(PokerSnapshotInstallStatus.DUPLICATE)
                manifest.revision == current.manifest.revision -> {
                    discardStaging()
                    return PokerSnapshotInstallResult(PokerSnapshotInstallStatus.MALFORMED)
                }
                else -> discardStaging()
            }
        }

        val stage = try {
            stageFactory.create(manifest)
        } catch (_: Throwable) {
            return PokerSnapshotInstallResult(PokerSnapshotInstallStatus.STORAGE_FAILED)
        }
        staging = Staging(manifest, stage)
        return PokerSnapshotInstallResult(PokerSnapshotInstallStatus.STARTED)
    }

    fun acceptChunk(chunk: PokerSnapshotChunk): PokerSnapshotInstallResult {
        val current = staging
            ?: return PokerSnapshotInstallResult(
                if (completeManifest?.snapshotId == chunk.snapshotId) {
                    PokerSnapshotInstallStatus.DUPLICATE
                } else {
                    PokerSnapshotInstallStatus.NO_ACTIVE_SNAPSHOT
                },
            )
        if (chunk.snapshotId != current.manifest.snapshotId) {
            return PokerSnapshotInstallResult(PokerSnapshotInstallStatus.SUPERSEDED)
        }
        if (chunk.count != current.manifest.chunkCount) {
            discardStaging()
            return PokerSnapshotInstallResult(PokerSnapshotInstallStatus.MALFORMED)
        }
        if (chunk.index !in 0 until current.manifest.chunkCount ||
            chunk.bytes.size > DEFAULT_MAX_FRAME_BYTES
        ) {
            discardStaging()
            return PokerSnapshotInstallResult(PokerSnapshotInstallStatus.MALFORMED)
        }
        if (chunk.index in current.chunks) {
            val previous = try {
                current.stage.read(chunk.index)
            } catch (_: Throwable) {
                discardStaging()
                return PokerSnapshotInstallResult(PokerSnapshotInstallStatus.STORAGE_FAILED)
            }
            return PokerSnapshotInstallResult(
                status = if (previous.contentEquals(chunk.bytes)) {
                    PokerSnapshotInstallStatus.DUPLICATE
                } else {
                    discardStaging()
                    PokerSnapshotInstallStatus.MALFORMED
                },
            )
        }
        val nextByteCount = current.byteCount + chunk.bytes.size
        if (nextByteCount > current.manifest.byteCount) {
            discardStaging()
            return PokerSnapshotInstallResult(PokerSnapshotInstallStatus.MALFORMED)
        }
        try {
            current.stage.write(chunk.index, chunk.bytes)
        } catch (_: Throwable) {
            discardStaging()
            return PokerSnapshotInstallResult(PokerSnapshotInstallStatus.STORAGE_FAILED)
        }
        current.chunks += chunk.index
        current.byteCount = nextByteCount
        return PokerSnapshotInstallResult(PokerSnapshotInstallStatus.CHUNK_ACCEPTED)
    }

    fun complete(snapshot: PokerSnapshotComplete): PokerSnapshotInstallResult {
        val current = staging
        if (current == null) {
            val previous = completeManifest
            return if (previous?.snapshotId == snapshot.snapshotId) {
                PokerSnapshotInstallResult(
                    status = PokerSnapshotInstallStatus.DUPLICATE,
                    snapshot = complete,
                    acknowledgement = PokerSnapshotAcknowledgement(
                        previous.snapshotId,
                        previous.revision,
                    ),
                )
            } else {
                PokerSnapshotInstallResult(PokerSnapshotInstallStatus.NO_ACTIVE_SNAPSHOT)
            }
        }
        if (snapshot.snapshotId != current.manifest.snapshotId) {
            return PokerSnapshotInstallResult(PokerSnapshotInstallStatus.SUPERSEDED)
        }
        if (current.chunks.size != current.manifest.chunkCount) {
            discardStaging()
            return PokerSnapshotInstallResult(PokerSnapshotInstallStatus.INCOMPLETE)
        }
        if (current.byteCount != current.manifest.byteCount) {
            discardStaging()
            return PokerSnapshotInstallResult(PokerSnapshotInstallStatus.MALFORMED)
        }

        val bytes = try {
            ByteArrayOutputStream().use { output ->
                repeat(current.manifest.chunkCount) { index ->
                    output.write(current.stage.read(index))
                }
                output.toByteArray()
            }
        } catch (_: Throwable) {
            discardStaging()
            return PokerSnapshotInstallResult(PokerSnapshotInstallStatus.STORAGE_FAILED)
        }
        if (bytes.size.toLong() != current.manifest.byteCount ||
            !sha256(bytes).equals(current.manifest.sha256, ignoreCase = true)
        ) {
            discardStaging()
            return PokerSnapshotInstallResult(PokerSnapshotInstallStatus.MALFORMED)
        }

        val decoded = try {
            PokerSnapshotWire.decode(bytes).also(PokerSnapshotWire::validate)
        } catch (_: SerializationException) {
            discardStaging()
            return PokerSnapshotInstallResult(PokerSnapshotInstallStatus.MALFORMED)
        } catch (_: IllegalArgumentException) {
            discardStaging()
            return PokerSnapshotInstallResult(PokerSnapshotInstallStatus.MALFORMED)
        }
        if (decoded.revision != current.manifest.revision) {
            discardStaging()
            return PokerSnapshotInstallResult(PokerSnapshotInstallStatus.MALFORMED)
        }
        if (decoded.revision <= (complete?.revision ?: 0L)) {
            discardStaging()
            return PokerSnapshotInstallResult(PokerSnapshotInstallStatus.SUPERSEDED)
        }

        val acknowledgement = PokerSnapshotAcknowledgement(
            snapshotId = current.manifest.snapshotId,
            revision = current.manifest.revision,
        )
        val stage = current.stage
        staging = null
        complete = decoded
        completeManifest = current.manifest
        runCatching { stage.discard() }
        return PokerSnapshotInstallResult(
            status = PokerSnapshotInstallStatus.INSTALLED,
            snapshot = decoded,
            acknowledgement = acknowledgement,
        )
    }

    fun requestAfterConnection(): PokerSnapshotRequest {
        discardStaging()
        return PokerSnapshotRequest("connection")
    }

    fun requestAfterRestart(): PokerSnapshotRequest {
        discardStaging()
        complete = null
        completeManifest = null
        return PokerSnapshotRequest("restart")
    }

    private fun discardStaging() {
        staging?.let { runCatching { it.stage.discard() } }
        staging = null
    }

    private fun validateManifest(manifest: PokerSnapshotManifest): PokerSnapshotInstallStatus? {
        if (manifest.snapshotId.isBlank() || manifest.revision <= 0 ||
            manifest.chunkCount <= 0 || manifest.byteCount < 0 ||
            !manifest.sha256.matches(SHA256_PATTERN)
        ) {
            return PokerSnapshotInstallStatus.MALFORMED
        }
        return null
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { "%02x".format(it) }

    private companion object {
        val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
    }
}

enum class PokerSnapshotRole {
    DEALER,
    POKER,
}

/** Bridges the snapshot state machine to the already-authenticated connection owner. */
class PokerSnapshotConnectionHandler(
    private val role: PokerSnapshotRole,
    snapshotSource: (suspend () -> PokerSnapshot)? = null,
    private val installer: PokerSnapshotInstaller? = null,
    private val snapshotId: () -> String = { UUID.randomUUID().toString() },
) : PokerConnectionCallbacks {
    private val snapshotSource = snapshotSource

    init {
        require(
            (role == PokerSnapshotRole.DEALER && snapshotSource != null && installer == null) ||
                (role == PokerSnapshotRole.POKER && snapshotSource == null && installer != null),
        ) { "Dealer needs a source; Poker needs an installer" }
    }

    override suspend fun onConnected(
        context: PokerConnectionContext,
        send: PokerEnvelopeSender,
    ) {
        if (role == PokerSnapshotRole.POKER && context.canUseSnapshots()) {
            send(
                POKER_SNAPSHOT_REQUEST_TYPE,
                POKER_SNAPSHOT_STREAM,
                PokerSnapshotWire.requestPayload(installer!!.requestAfterConnection()),
                null,
            )
        }
    }

    override suspend fun onEnvelope(
        context: PokerConnectionContext,
        envelope: ProtocolEnvelope,
        send: PokerEnvelopeSender,
    ) {
        if (!context.canUseSnapshots() || envelope.stream != POKER_SNAPSHOT_STREAM) return
        when (role) {
            PokerSnapshotRole.DEALER -> if (envelope.type == POKER_SNAPSHOT_REQUEST_TYPE) {
                runCatching { PokerSnapshotWire.request(envelope.payload) }
                    .getOrNull() ?: return
                sendSnapshot(envelope.messageId, send)
            }

            PokerSnapshotRole.POKER -> receiveSnapshot(envelope, send)
        }
    }

    private suspend fun sendSnapshot(
        replyTo: String,
        send: PokerEnvelopeSender,
    ) {
        val transfer = PokerSnapshotWire.encode(snapshotSource!!(), snapshotId())
        send(
            POKER_SNAPSHOT_BEGIN_TYPE,
            POKER_SNAPSHOT_STREAM,
            PokerSnapshotWire.manifestPayload(transfer.manifest),
            replyTo,
        )
        transfer.chunks.forEach { chunk ->
            send(
                POKER_SNAPSHOT_CHUNK_TYPE,
                POKER_SNAPSHOT_STREAM,
                PokerSnapshotWire.chunkPayload(chunk),
                replyTo,
            )
        }
        send(
            POKER_SNAPSHOT_COMPLETE_TYPE,
            POKER_SNAPSHOT_STREAM,
            PokerSnapshotWire.completePayload(PokerSnapshotComplete(transfer.manifest.snapshotId)),
            replyTo,
        )
    }

    private suspend fun receiveSnapshot(
        envelope: ProtocolEnvelope,
        send: PokerEnvelopeSender,
    ) {
        val result = runCatching {
            when (envelope.type) {
                POKER_SNAPSHOT_BEGIN_TYPE -> installer!!.begin(PokerSnapshotWire.manifest(envelope.payload))
                POKER_SNAPSHOT_CHUNK_TYPE -> installer!!.acceptChunk(PokerSnapshotWire.chunk(envelope.payload))
                POKER_SNAPSHOT_COMPLETE_TYPE -> installer!!.complete(PokerSnapshotWire.complete(envelope.payload))
                else -> return
            }
        }.getOrElse {
            PokerSnapshotInstallResult(PokerSnapshotInstallStatus.MALFORMED)
        }
        when (result.status) {
            PokerSnapshotInstallStatus.INSTALLED,
            PokerSnapshotInstallStatus.DUPLICATE,
            PokerSnapshotInstallStatus.SUPERSEDED,
            -> result.acknowledgement?.let { acknowledgement ->
                send(
                    POKER_SNAPSHOT_ACK_TYPE,
                    POKER_SNAPSHOT_STREAM,
                    PokerSnapshotWire.acknowledgementPayload(acknowledgement),
                    envelope.messageId,
                )
            }

            PokerSnapshotInstallStatus.MALFORMED,
            PokerSnapshotInstallStatus.INCOMPLETE,
            PokerSnapshotInstallStatus.STORAGE_FAILED,
            PokerSnapshotInstallStatus.NO_ACTIVE_SNAPSHOT,
            -> sendFreshRequest(send, result.status.name.lowercase())

            else -> Unit
        }
    }

    private suspend fun sendFreshRequest(send: PokerEnvelopeSender, reason: String) {
        send(
            POKER_SNAPSHOT_REQUEST_TYPE,
            POKER_SNAPSHOT_STREAM,
            PokerSnapshotWire.requestPayload(PokerSnapshotRequest(reason)),
            null,
        )
    }
}

private fun PokerConnectionContext.canUseSnapshots(): Boolean =
    negotiation.access == PokerProtocolAccess.READ_WRITE &&
        negotiation.supports(POKER_SNAPSHOT_CAPABILITY)
