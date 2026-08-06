package com.code2hack.pokerdealer.protocol

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardState
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** One bounded, UTF-8-safe append batch for one card revision. */
@Serializable
data class PokerCardDelta(
    @SerialName("base_snapshot_revision") val baseSnapshotRevision: Long,
    @SerialName("snapshot_revision") val snapshotRevision: Long,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("card_id") val cardId: String,
    @SerialName("base_card_revision") val baseCardRevision: Long,
    @SerialName("card_revision") val cardRevision: Long,
    @SerialName("offset_utf8_bytes") val offsetUtf8Bytes: Long,
    @SerialName("chunk_index") val chunkIndex: Int,
    @SerialName("chunk_count") val chunkCount: Int,
    @SerialName("append_bytes") val appendBytes: ByteArray,
    @SerialName("updated_at_ms") val updatedAtMs: Long,
    @SerialName("final") val isFinal: Boolean = false,
    @SerialName("authoritative_card") val authoritativeCard: Card? = null,
)

@Serializable
data class PokerCardDeltaAcknowledgement(
    val revision: Long,
)

object PokerLiveDeltaWire {
    fun deltaPayload(delta: PokerCardDelta): JsonObject = PokerProtocolJson.encodeToJsonElement(
        PokerCardDelta.serializer(),
        delta,
    ).jsonObject

    fun acknowledgementPayload(
        acknowledgement: PokerCardDeltaAcknowledgement,
    ): JsonObject = PokerProtocolJson.encodeToJsonElement(
        PokerCardDeltaAcknowledgement.serializer(),
        acknowledgement,
    ).jsonObject

    fun delta(payload: JsonObject): PokerCardDelta = PokerProtocolJson.decodeFromJsonElement(
        PokerCardDelta.serializer(),
        payload,
    )

    fun acknowledgement(payload: JsonObject): PokerCardDeltaAcknowledgement =
        PokerProtocolJson.decodeFromJsonElement(
            PokerCardDeltaAcknowledgement.serializer(),
            payload,
        )

    /** Returns null when a whole snapshot is safer than an append delta. */
    fun build(
        previous: PokerSnapshot,
        next: PokerSnapshot,
        maxChunkBytes: Int = DEFAULT_POKER_SNAPSHOT_CHUNK_BYTES,
    ): List<PokerCardDelta>? {
        PokerSnapshotWire.validate(previous)
        PokerSnapshotWire.validate(next)
        require(maxChunkBytes > 0) { "Delta chunk size must be positive" }
        if (next.revision != previous.revision + 1 ||
            previous.projection != next.projection ||
            previous.piles.map { it.metadata } != next.piles.map { it.metadata }
        ) {
            return null
        }

        val previousPiles = previous.piles.associateBy { it.metadata.locator }
        val nextPiles = next.piles.associateBy { it.metadata.locator }
        if (previousPiles.keys != nextPiles.keys) return null

        var changed: Pair<Card, Card>? = null
        for (locator in previousPiles.keys) {
            val oldCards = previousPiles.getValue(locator).cards
            val newCards = nextPiles.getValue(locator).cards
            if (oldCards.map(Card::id) != newCards.map(Card::id)) return null
            oldCards.zip(newCards).forEach { (old, current) ->
                if (old != current) {
                    if (changed != null) return null
                    changed = old to current
                }
            }
        }
        val (oldCard, newCard) = changed ?: return null
        if (oldCard.conversationId != newCard.conversationId ||
            oldCard.id != newCard.id ||
            newCard.revision != oldCard.revision + 1
        ) {
            return null
        }

        val oldBytes = strictUtf8(oldCard.fullText) ?: return null
        val newBytes = strictUtf8(newCard.fullText) ?: return null
        if (newBytes.size < oldBytes.size ||
            !newBytes.copyOfRange(0, oldBytes.size).contentEquals(oldBytes)
        ) {
            return null
        }

        val nonLiveChange = newCard.copy(
            fullText = oldCard.fullText,
            revision = oldCard.revision,
            updatedAtMs = oldCard.updatedAtMs,
        ) != oldCard
        if (nonLiveChange && !newCard.contentComplete) return null

        val append = newBytes.copyOfRange(oldBytes.size, newBytes.size)
        val chunks = splitUtf8(append, maxChunkBytes)
        val final = newCard.contentComplete && newCard.state.isTerminal()
        return chunks.mapIndexed { index, bytes ->
            PokerCardDelta(
                baseSnapshotRevision = previous.revision,
                snapshotRevision = next.revision,
                conversationId = newCard.conversationId,
                cardId = newCard.id,
                baseCardRevision = oldCard.revision,
                cardRevision = newCard.revision,
                offsetUtf8Bytes = oldBytes.size.toLong() + chunks.take(index).sumOf { it.size },
                chunkIndex = index,
                chunkCount = chunks.size,
                appendBytes = bytes,
                updatedAtMs = newCard.updatedAtMs,
                isFinal = final && index == chunks.lastIndex,
                authoritativeCard = newCard.takeIf { final && index == chunks.lastIndex },
            )
        }
    }

    private fun splitUtf8(bytes: ByteArray, maxChunkBytes: Int): List<ByteArray> {
        if (bytes.isEmpty()) return listOf(ByteArray(0))
        val result = mutableListOf<ByteArray>()
        var start = 0
        while (start < bytes.size) {
            var end = (start + maxChunkBytes).coerceAtMost(bytes.size)
            while (end > start && end < bytes.size && isContinuation(bytes[end])) end--
            require(end > start) { "Delta chunk size is smaller than one UTF-8 code point" }
            result += bytes.copyOfRange(start, end)
            start = end
        }
        return result
    }

    internal fun strictUtf8(text: String): ByteArray? {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        return bytes.takeIf { decodeUtf8(it) == text }
    }

    internal fun decodeUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun isContinuation(value: Byte): Boolean = value.toInt() and 0xc0 == 0x80
}

enum class PokerLiveDeltaInstallStatus {
    APPLIED,
    QUEUED,
    DUPLICATE,
    RESNAPSHOT_REQUIRED,
    MALFORMED,
    QUEUE_OVERFLOW,
}

data class PokerLiveDeltaInstallResult(
    val status: PokerLiveDeltaInstallStatus,
    val snapshot: PokerSnapshot? = null,
    val acknowledgedRevision: Long? = null,
    val reason: String? = null,
)

/** Applies append batches only against the exact complete snapshot revision they name. */
class PokerLiveDeltaReceiver(
    private val maxQueuedBytes: Long = 64L * DEFAULT_POKER_SNAPSHOT_CHUNK_BYTES,
) {
    init {
        require(maxQueuedBytes > 0) { "Delta queue limit must be positive" }
    }

    private data class Batch(
        val first: PokerCardDelta,
        val chunks: MutableMap<Int, PokerCardDelta> = mutableMapOf(),
        var byteCount: Long = 0,
    )

    private var current: PokerSnapshot? = null
    private val batches = mutableMapOf<Long, Batch>()
    private var queuedBytes = 0L

    val installedSnapshot: PokerSnapshot?
        @Synchronized get() = current

    val hasPendingDeltas: Boolean
        @Synchronized get() = batches.isNotEmpty()

    @Synchronized
    fun discardPendingDeltas() {
        clearQueue()
    }

    @Synchronized
    fun installSnapshot(snapshot: PokerSnapshot) {
        PokerSnapshotWire.validate(snapshot)
        current = snapshot
        batches.clear()
        queuedBytes = 0
    }

    @Synchronized
    fun accept(delta: PokerCardDelta): PokerLiveDeltaInstallResult {
        val basicError = validate(delta)
        if (basicError != null) {
            clearQueue()
            return PokerLiveDeltaInstallResult(
                PokerLiveDeltaInstallStatus.MALFORMED,
                reason = basicError,
            )
        }
        val installed = current ?: return resnapshot("snapshot base is missing")
        if (delta.snapshotRevision <= installed.revision) {
            return PokerLiveDeltaInstallResult(
                PokerLiveDeltaInstallStatus.DUPLICATE,
                acknowledgedRevision = installed.revision,
            )
        }
        if (delta.baseSnapshotRevision != installed.revision ||
            delta.snapshotRevision != installed.revision + 1
        ) {
            clearQueue()
            return resnapshot("snapshot revision gap")
        }

        val batch = batches.getOrPut(delta.snapshotRevision) {
            Batch(delta.copy(), mutableMapOf(), 0)
        }
        if (!sameIdentity(batch.first, delta)) {
            clearQueue()
            return resnapshot("delta revision identity changed")
        }
        val previous = batch.chunks[delta.chunkIndex]
        if (previous != null) {
            return if (previous == delta) {
                PokerLiveDeltaInstallResult(PokerLiveDeltaInstallStatus.DUPLICATE)
            } else {
                clearQueue()
                resnapshot("conflicting duplicate delta")
            }
        }
        if (queuedBytes + delta.appendBytes.size > maxQueuedBytes) {
            clearQueue()
            return PokerLiveDeltaInstallResult(
                PokerLiveDeltaInstallStatus.QUEUE_OVERFLOW,
                reason = "delta queue overflow",
            )
        }
        batch.chunks[delta.chunkIndex] = delta
        batch.byteCount += delta.appendBytes.size
        queuedBytes += delta.appendBytes.size
        if (batch.chunks.size != delta.chunkCount) {
            return PokerLiveDeltaInstallResult(PokerLiveDeltaInstallStatus.QUEUED)
        }

        return try {
            val next = applyBatch(installed, batch)
            batches.remove(delta.snapshotRevision)
            queuedBytes -= batch.byteCount
            current = next
            PokerLiveDeltaInstallResult(
                PokerLiveDeltaInstallStatus.APPLIED,
                snapshot = next,
                acknowledgedRevision = next.revision,
            )
        } catch (failure: IllegalArgumentException) {
            clearQueue()
            resnapshot(failure.message ?: "invalid card delta")
        }
    }

    private fun sameIdentity(first: PokerCardDelta, other: PokerCardDelta): Boolean =
        first.baseSnapshotRevision == other.baseSnapshotRevision &&
            first.snapshotRevision == other.snapshotRevision &&
            first.conversationId == other.conversationId &&
            first.cardId == other.cardId &&
            first.baseCardRevision == other.baseCardRevision &&
            first.cardRevision == other.cardRevision &&
            first.chunkCount == other.chunkCount

    private fun applyBatch(snapshot: PokerSnapshot, batch: Batch): PokerSnapshot {
        val chunks = batch.chunks.values.sortedBy(PokerCardDelta::chunkIndex)
        val first = batch.first
        require(chunks.size == first.chunkCount) { "delta chunk set is incomplete" }
        require(chunks.first().offsetUtf8Bytes >= 0) { "delta UTF-8 offset is negative" }
        require(chunks.all { it.baseSnapshotRevision == first.baseSnapshotRevision }) {
            "delta base revision changed"
        }
        require(chunks.all { it.snapshotRevision == first.snapshotRevision }) {
            "delta snapshot revision changed"
        }
        require(chunks.all { it.conversationId == first.conversationId && it.cardId == first.cardId }) {
            "delta card identity changed"
        }
        require(chunks.all { it.baseCardRevision == first.baseCardRevision }) {
            "delta card base revision changed"
        }
        require(chunks.all { it.cardRevision == first.cardRevision }) {
            "delta card revision changed"
        }
        require(chunks.zipWithNext().all { (a, b) -> b.chunkIndex == a.chunkIndex + 1 }) {
            "delta chunk sequence has a gap"
        }
        val finalChunks = chunks.filter(PokerCardDelta::isFinal)
        require(finalChunks.size <= 1 && (finalChunks.isEmpty() || finalChunks.single() == chunks.last())) {
            "delta final revision is missing or misplaced"
        }
        require(chunks.dropLast(1).all { !it.isFinal && it.authoritativeCard == null }) {
            "delta authoritative final card is misplaced"
        }
        require(finalChunks.isNotEmpty() == (chunks.last().authoritativeCard != null)) {
            "delta final card does not match final marker"
        }

        val pile = snapshot.piles.singleOrNull {
            it.metadata.locator.let { locator -> "${locator.hostId}/${locator.threadId}" } ==
                first.conversationId
        } ?: throw IllegalArgumentException("delta card pile is missing")
        val oldCard = pile.cards.singleOrNull { it.id == first.cardId }
            ?: throw IllegalArgumentException("delta card base is missing")
        require(oldCard.conversationId == first.conversationId) { "delta conversation changed" }
        require(oldCard.revision == first.baseCardRevision) { "delta card revision gap" }
        val oldBytes = PokerLiveDeltaWire.strictUtf8(oldCard.fullText)
            ?: throw IllegalArgumentException("delta card base is not valid UTF-8")
        var expectedOffset = oldBytes.size.toLong()
        chunks.forEach { chunk ->
            require(chunk.offsetUtf8Bytes == expectedOffset) {
                "delta UTF-8 offset does not match cumulative append position"
            }
            expectedOffset += chunk.appendBytes.size.toLong()
        }

        val appended = chunks.flatMap { it.appendBytes.asList() }.toByteArray()
        val appendText = PokerLiveDeltaWire.decodeUtf8(appended)
            ?: throw IllegalArgumentException("delta append is not valid UTF-8")
        val combinedText = oldCard.fullText + appendText
        require(PokerLiveDeltaWire.strictUtf8(combinedText) != null) {
            "delta result is not valid UTF-8"
        }
        val updated = chunks.last().authoritativeCard?.also { finalCard ->
            require(finalCard.id == oldCard.id && finalCard.conversationId == oldCard.conversationId) {
                "delta final card identity changed"
            }
            require(finalCard.revision == first.cardRevision) { "delta final revision changed" }
            require(finalCard.fullText == combinedText) { "delta final text is not authoritative" }
            require(finalCard.contentComplete) { "delta final card is incomplete" }
            require(finalCard.state.isTerminal()) { "delta final card is not terminal" }
        } ?: oldCard.copy(
            revision = first.cardRevision,
            fullText = combinedText,
            updatedAtMs = chunks.last().updatedAtMs,
        )
        val updatedPile = pile.copy(cards = pile.cards.map { card ->
            if (card.id == updated.id) updated else card
        })
        val next = snapshot.copy(
            revision = first.snapshotRevision,
            piles = snapshot.piles.map { currentPile ->
                if (currentPile.metadata.locator == pile.metadata.locator) updatedPile else currentPile
            },
        )
        PokerSnapshotWire.validate(next)
        return next
    }

    private fun validate(delta: PokerCardDelta): String? = when {
        delta.baseSnapshotRevision <= 0 || delta.snapshotRevision <= 0 ->
            "delta snapshot revision is invalid"
        delta.snapshotRevision != delta.baseSnapshotRevision + 1 ->
            "delta snapshot revision is not contiguous"
        delta.conversationId.isBlank() || delta.cardId.isBlank() ->
            "delta card identity is blank"
        delta.baseCardRevision < 0 || delta.cardRevision != delta.baseCardRevision + 1 ->
            "delta card revision is invalid"
        delta.offsetUtf8Bytes < 0 -> "delta UTF-8 offset is negative"
        delta.chunkCount <= 0 || delta.chunkIndex !in 0 until delta.chunkCount ->
            "delta chunk index is invalid"
        delta.appendBytes.size > DEFAULT_MAX_FRAME_BYTES -> "delta chunk is too large"
        PokerLiveDeltaWire.decodeUtf8(delta.appendBytes) == null ->
            "delta append is not valid UTF-8"
        delta.chunkIndex == delta.chunkCount - 1 &&
            delta.isFinal != (delta.authoritativeCard != null) ->
            "delta final marker is invalid"
        delta.chunkIndex < delta.chunkCount - 1 &&
            (delta.isFinal || delta.authoritativeCard != null) ->
            "delta final card is misplaced"
        else -> null
    }

    private fun resnapshot(reason: String): PokerLiveDeltaInstallResult =
        PokerLiveDeltaInstallResult(
            PokerLiveDeltaInstallStatus.RESNAPSHOT_REQUIRED,
            reason = reason,
        )

    private fun clearQueue() {
        batches.clear()
        queuedBytes = 0
    }
}

sealed interface PokerLiveDeltaSendAction {
    data object None : PokerLiveDeltaSendAction

    data class Deltas(val deltas: List<PokerCardDelta>) : PokerLiveDeltaSendAction

    data class Snapshot(val snapshot: PokerSnapshot) : PokerLiveDeltaSendAction
}

/** Keeps live revisions behind the last acknowledged complete snapshot. */
class PokerLiveDeltaSender(
    private val maxQueuedSnapshots: Int = 32,
    private val maxChunkBytes: Int = DEFAULT_POKER_SNAPSHOT_CHUNK_BYTES,
) {
    init {
        require(maxQueuedSnapshots > 0) { "Delta snapshot queue limit must be positive" }
        require(maxChunkBytes in 1..DEFAULT_MAX_FRAME_BYTES) {
            "Delta chunk size must fit one transport frame"
        }
    }

    private sealed interface InFlight {
        val snapshot: PokerSnapshot

        data class Snapshot(override val snapshot: PokerSnapshot) : InFlight
        data class Deltas(override val snapshot: PokerSnapshot) : InFlight
    }

    private var acknowledged: PokerSnapshot? = null
    private var inFlight: InFlight? = null
    private var latest: PokerSnapshot? = null
    private val queued = ArrayDeque<PokerSnapshot>()

    @Synchronized
    fun reset() {
        acknowledged = null
        inFlight = null
        latest = null
        queued.clear()
    }

    @Synchronized
    fun snapshotSent(snapshot: PokerSnapshot) {
        PokerSnapshotWire.validate(snapshot)
        latest = snapshot
        queued.removeIf { it.revision <= snapshot.revision }
        inFlight = InFlight.Snapshot(snapshot)
    }

    @Synchronized
    fun publish(snapshot: PokerSnapshot): PokerLiveDeltaSendAction {
        PokerSnapshotWire.validate(snapshot)
        if (snapshot.revision <= (latest?.revision ?: 0L)) return PokerLiveDeltaSendAction.None
        latest = snapshot
        if (inFlight != null || acknowledged == null) {
            if (queued.lastOrNull()?.revision == snapshot.revision) queued.removeLast()
            queued.addLast(snapshot)
            return if (queued.size > maxQueuedSnapshots) {
                queued.clear()
                inFlight = InFlight.Snapshot(snapshot)
                PokerLiveDeltaSendAction.Snapshot(snapshot)
            } else {
                PokerLiveDeltaSendAction.None
            }
        }
        return start(snapshot)
    }

    @Synchronized
    fun acknowledged(revision: Long): PokerLiveDeltaSendAction {
        val flight = inFlight ?: return PokerLiveDeltaSendAction.None
        if (revision < flight.snapshot.revision) return PokerLiveDeltaSendAction.None
        if (revision != flight.snapshot.revision) {
            val snapshot = latest ?: flight.snapshot
            inFlight = InFlight.Snapshot(snapshot)
            queued.clear()
            return PokerLiveDeltaSendAction.Snapshot(snapshot)
        }
        acknowledged = flight.snapshot
        inFlight = null
        return next()
    }

    @Synchronized
    fun isAwaitingAcknowledgement(revision: Long): Boolean =
        inFlight?.let { revision >= it.snapshot.revision } == true

    @Synchronized
    fun forceSnapshot(): PokerLiveDeltaSendAction {
        if (inFlight == null) return PokerLiveDeltaSendAction.None
        val snapshot = latest ?: return PokerLiveDeltaSendAction.None
        inFlight = InFlight.Snapshot(snapshot)
        queued.clear()
        return PokerLiveDeltaSendAction.Snapshot(snapshot)
    }

    private fun next(): PokerLiveDeltaSendAction {
        if (queued.isEmpty()) return PokerLiveDeltaSendAction.None
        val next = queued.removeFirst()
        return start(next)
    }

    private fun start(snapshot: PokerSnapshot): PokerLiveDeltaSendAction {
        val base = acknowledged ?: run {
            queued.addFirst(snapshot)
            return PokerLiveDeltaSendAction.None
        }
        if (snapshot.revision != base.revision + 1) {
            inFlight = InFlight.Snapshot(snapshot)
            queued.clear()
            latest?.takeIf { it.revision > snapshot.revision }?.let(queued::addLast)
            return PokerLiveDeltaSendAction.Snapshot(snapshot)
        }
        val deltas = PokerLiveDeltaWire.build(base, snapshot, maxChunkBytes)
        if (deltas == null) {
            inFlight = InFlight.Snapshot(snapshot)
            queued.clear()
            latest?.takeIf { it.revision > snapshot.revision }?.let(queued::addLast)
            return PokerLiveDeltaSendAction.Snapshot(snapshot)
        }
        inFlight = InFlight.Deltas(snapshot)
        return PokerLiveDeltaSendAction.Deltas(deltas)
    }
}

private fun CardState.isTerminal(): Boolean = when (this) {
    CardState.COMMITTED, CardState.CORRECTED, CardState.FAILED -> true
    CardState.OPEN -> false
}
