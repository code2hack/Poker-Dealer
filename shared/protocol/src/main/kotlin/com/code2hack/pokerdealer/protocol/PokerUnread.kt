package com.code2hack.pokerdealer.protocol

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardSource
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PokerUnreadCardKey(
    val conversationId: String,
    val cardId: String,
)

@Serializable
data class PokerUnreadRequestKey(
    val conversationId: String,
    val requestKey: String,
)

/** Content-free Poker-local unread state. Card text never enters this value. */
@Serializable
data class PokerUnreadState(
    val baselineEstablished: Boolean = false,
    val knownCards: Set<PokerUnreadCardKey> = emptySet(),
    val displayableCards: Set<PokerUnreadCardKey> = emptySet(),
    val unreadCards: Set<PokerUnreadCardKey> = emptySet(),
    val knownRequests: Set<PokerUnreadRequestKey> = emptySet(),
    val unreadRequests: Set<PokerUnreadRequestKey> = emptySet(),
    val cardWatermarks: Map<String, Long> = emptyMap(),
) {
    init {
        require(displayableCards.containsAll(unreadCards)) {
            "Unread cards must be displayable"
        }
        require(cardWatermarks.values.all { it >= 0 }) {
            "Card watermarks must not be negative"
        }
    }

    val unreadCount: Int
        get() = unreadCards.size + unreadRequests.size
}

data class PokerUnreadUpdate(
    val state: PokerUnreadState,
    val shouldForeground: Boolean,
)

/** Applies complete snapshots and stable request projections without owning card content. */
class PokerUnreadTracker(initialState: PokerUnreadState = PokerUnreadState()) {
    var state: PokerUnreadState = initialState
        private set

    fun installSnapshot(snapshot: PokerSnapshot): PokerUnreadUpdate {
        PokerSnapshotWire.validate(snapshot)
        val baseline = !state.baselineEstablished
        val knownCards = state.knownCards.toMutableSet()
        val displayableCards = state.displayableCards.toMutableSet()
        val unreadCards = state.unreadCards.toMutableSet()
        val knownRequests = state.knownRequests.toMutableSet()
        val unreadRequests = state.unreadRequests.toMutableSet()
        val watermarks = state.cardWatermarks.toMutableMap()
        var shouldForeground = false

        snapshot.piles.forEach { pile ->
            val conversationId = pile.conversationId()
            pile.cards.forEach { card ->
                if (!card.source.qualifiesForUnread()) return@forEach
                val key = PokerUnreadCardKey(conversationId, card.id)
                val knownByWatermark = key !in knownCards &&
                    card.sequence <= (watermarks[conversationId] ?: -1L)
                knownCards += key
                if (card.isDisplayable() && key !in displayableCards) {
                    displayableCards += key
                    if (!baseline && !knownByWatermark) {
                        unreadCards += key
                        shouldForeground = true
                    }
                }
                if (card.sequence > (watermarks[conversationId] ?: -1L)) {
                    watermarks[conversationId] = card.sequence
                }
            }
            pile.requestCards.forEach { request ->
                val key = PokerUnreadRequestKey(conversationId, request.key)
                if (knownRequests.add(key) && !baseline) {
                    unreadRequests += key
                    shouldForeground = true
                }
            }
        }

        val attachedConversations = snapshot.piles.mapTo(mutableSetOf()) { it.conversationId() }
        unreadCards.retainAll { it.conversationId in attachedConversations }
        unreadRequests.retainAll { it.conversationId in attachedConversations }
        state = state.copy(
            baselineEstablished = true,
            knownCards = knownCards,
            displayableCards = displayableCards,
            unreadCards = unreadCards,
            knownRequests = knownRequests,
            unreadRequests = unreadRequests,
            cardWatermarks = watermarks,
        )
        return PokerUnreadUpdate(state, shouldForeground)
    }

    fun observeRequest(
        locator: CodexThreadLocator,
        requestKey: String,
        finalized: Boolean = false,
    ): PokerUnreadUpdate {
        require(requestKey.isNotBlank()) { "Request key must not be blank" }
        val baseline = !state.baselineEstablished
        val shouldForeground = observeRequestInternal(
            conversationId = locator.conversationId(),
            requestKey = requestKey,
            baseline = baseline,
        )
        if (!baseline) return PokerUnreadUpdate(state, shouldForeground && !finalized)
        return PokerUnreadUpdate(state, false)
    }

    fun markCardRead(
        locator: CodexThreadLocator,
        cardId: String,
        finalized: Boolean,
        finalLineVisible: Boolean,
    ): PokerUnreadState {
        if (finalized && finalLineVisible) {
            state = state.copy(
                unreadCards = state.unreadCards - PokerUnreadCardKey(locator.conversationId(), cardId),
            )
        }
        return state
    }

    fun markRequestRead(
        locator: CodexThreadLocator,
        requestKey: String,
        finalized: Boolean,
        finalLineVisible: Boolean,
    ): PokerUnreadState {
        if (finalized && finalLineVisible) {
            state = state.copy(
                unreadRequests = state.unreadRequests -
                    PokerUnreadRequestKey(locator.conversationId(), requestKey),
            )
        }
        return state
    }

    private fun observeRequestInternal(
        conversationId: String,
        requestKey: String,
        baseline: Boolean,
    ): Boolean {
        val key = PokerUnreadRequestKey(conversationId, requestKey)
        if (key in state.knownRequests) return false
        state = state.copy(
            knownRequests = state.knownRequests + key,
            unreadRequests = if (baseline) state.unreadRequests else state.unreadRequests + key,
        )
        return !baseline
    }

    private fun PokerSnapshotPile.conversationId(): String =
        "${metadata.locator.hostId}/${metadata.locator.threadId}"

    private fun CodexThreadLocator.conversationId(): String = "$hostId/$threadId"

    private fun Card.isDisplayable(): Boolean = fullText.isNotEmpty() || contentComplete

    private fun CardSource.qualifiesForUnread(): Boolean = when (this) {
        CardSource.CODEX_AGENT_MESSAGE,
        CardSource.CODEX_COMMAND,
        CardSource.CODEX_FILE_CHANGE,
        CardSource.CODEX_APPROVAL,
        CardSource.SYSTEM,
        -> true
        CardSource.POKER_INPUT,
        CardSource.DEALER_INPUT,
        CardSource.CODEX_USER_MESSAGE,
        CardSource.CODEX_PLAN,
        CardSource.CODEX_REASONING,
        -> false
    }
}

@Serializable
private data class PokerUnreadRecord(
    val pairingFingerprint: String,
    val state: PokerUnreadState,
)

/** Stores only pairing-scoped identifiers and watermarks in the app's no-backup directory. */
class FilePokerUnreadStore(
    private val file: File,
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = true
    }

    fun load(pairingFingerprint: String): PokerUnreadState {
        require(pairingFingerprint.isNotBlank()) { "Pairing fingerprint must not be blank" }
        if (!file.exists()) return PokerUnreadState()
        return try {
            val record = json.decodeFromString<PokerUnreadRecord>(file.readText())
            if (record.pairingFingerprint == pairingFingerprint) record.state
            else PokerUnreadState()
        } catch (_: Exception) {
            runCatching { Files.deleteIfExists(file.toPath()) }
            PokerUnreadState()
        }
    }

    fun save(pairingFingerprint: String, state: PokerUnreadState) {
        require(pairingFingerprint.isNotBlank()) { "Pairing fingerprint must not be blank" }
        val parent = file.parentFile
        require(parent == null || parent.isDirectory || parent.mkdirs()) {
            "Unable to create unread storage"
        }
        val temporary = File.createTempFile(
            "${file.name}.".padEnd(3, '_'),
            ".tmp",
            parent ?: file.absoluteFile.parentFile,
        )
        try {
            temporary.writeText(
                json.encodeToString(
                    PokerUnreadRecord(pairingFingerprint, state),
                ),
            )
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }
}

fun pokerPairingFingerprint(localPublicKey: ByteArray, peerPublicKey: ByteArray): String {
    require(localPublicKey.isNotEmpty() && peerPublicKey.isNotEmpty()) {
        "Pairing public keys must not be empty"
    }
    val digest = MessageDigest.getInstance("SHA-256")
    listOf(localPublicKey, peerPublicKey).forEach { key ->
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(key.size).array())
        digest.update(key)
    }
    return digest.digest().joinToString(separator = "") { "%02x".format(it) }
}

fun pokerUnreadRequestKey(kind: String, requestId: String, fingerprint: String): String {
    require(kind.isNotBlank() && requestId.isNotBlank() && fingerprint.isNotBlank()) {
        "Unread request identity must not be blank"
    }
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(requestId.encodeToByteArray())
    digest.update(0.toByte())
    digest.update(fingerprint.encodeToByteArray())
    return "$kind:${digest.digest().joinToString(separator = "") { "%02x".format(it) }}"
}
