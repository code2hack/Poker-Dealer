package com.code2hack.pokerdealer.protocol

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val POKER_PAIRING_WINDOW_MS = 5 * 60 * 1_000L
const val POKER_PAIRING_MAX_ATTEMPTS = 5
const val POKER_PAIRING_CHALLENGE_TYPE = "pairing.challenge"
const val POKER_PAIRING_RESPONSE_TYPE = "pairing.response"
const val POKER_PAIRING_CONFIRMATION_TYPE = "pairing.confirmation"

@Serializable
enum class PokerPairingRole {
    DEALER,
    POKER,
}

@Serializable
data class PokerHotspotEndpoint(
    val host: String,
    val port: Int,
) {
    init {
        require(host.isNotBlank()) { "Hotspot host must not be blank" }
        require(port in 1..65_535) { "Hotspot port must be valid" }
    }
}

@Serializable
data class PokerPairingRecord(
    val dealerPublicKey: ByteArray,
    val pokerPublicKey: ByteArray,
    val endpoint: PokerHotspotEndpoint? = null,
) {
    fun isValid(): Boolean = dealerPublicKey.isNotEmpty() && pokerPublicKey.isNotEmpty()
}

@Serializable
data class PokerPairingChallenge(
    val challengeId: String,
    val nonce: ByteArray,
    val pokerPublicKey: ByteArray,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val replacement: Boolean,
)

@Serializable
data class PokerPairingResponse(
    val challengeId: String,
    val dealerPublicKey: ByteArray,
    val proof: ByteArray,
)

@Serializable
data class PokerPairingConfirmation(
    val challengeId: String,
    val dealerPublicKey: ByteArray,
    val pokerPublicKey: ByteArray,
    val pokerSignature: ByteArray,
)

data class PokerPairingEnrollment(
    val challenge: PokerPairingChallenge,
    /** Displayed locally; it is deliberately absent from the wire challenge. */
    val displayCode: String,
)

enum class PokerPairingState {
    UNPAIRED,
    ENROLLMENT_OPEN,
    PAIRED,
}

enum class PokerPairingFailure {
    NONE,
    PHYSICAL_CONFIRMATION_REQUIRED,
    REPLACEMENT_CONFIRMATION_REQUIRED,
    ENROLLMENT_EXPIRED,
    INVALID_CODE,
    ATTEMPTS_EXHAUSTED,
    PAIRING_MISMATCH,
    KEYSTORE_INVALID,
    CORRUPT_STATE,
    INVALID_CHALLENGE,
    NOT_ENROLLMENT_DEVICE,
    INVALID_ENDPOINT,
}

data class PokerPairingStatus(
    val state: PokerPairingState,
    val failure: PokerPairingFailure = PokerPairingFailure.NONE,
    val failedAttempts: Int = 0,
)

class PokerPairingRejected(
    val reason: PokerPairingFailure,
    val failedAttempts: Int = 0,
) : IllegalStateException(reason.name)

class PairingKeyUnavailableException(cause: Throwable? = null) :
    IllegalStateException("Pairing identity is unavailable", cause)

class CorruptPokerPairingStateException(cause: Throwable) :
    IllegalStateException("Corrupt Poker pairing state", cause)

interface PokerPairingIdentity {
    val publicKey: ByteArray

    fun sign(payload: ByteArray): ByteArray =
        throw PairingKeyUnavailableException()
}

interface PokerPairingStore {
    fun load(): PokerPairingRecord?
    fun save(record: PokerPairingRecord)
    fun clear()
}

class FilePokerPairingStore(
    private val file: File,
) : PokerPairingStore {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = true
    }

    override fun load(): PokerPairingRecord? {
        if (!file.exists()) return null
        return try {
            json.decodeFromString(PokerPairingRecord.serializer(), file.readText())
                .also { check(it.isValid()) { "Pairing keys are empty" } }
        } catch (failure: Exception) {
            throw CorruptPokerPairingStateException(failure)
        }
    }

    override fun save(record: PokerPairingRecord) {
        require(record.isValid()) { "Pairing keys are empty" }
        val parent = file.parentFile
        require(parent == null || parent.isDirectory || parent.mkdirs()) {
            "Unable to create pairing storage"
        }
        val temporary = File.createTempFile(
            "${file.name}.".padEnd(3, '_'),
            ".tmp",
            parent ?: file.absoluteFile.parentFile,
        )
        try {
            temporary.writeText(json.encodeToString(PokerPairingRecord.serializer(), record))
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

    override fun clear() {
        Files.deleteIfExists(file.toPath())
    }
}

private class PairingWindow(
    val challenge: PokerPairingChallenge,
    val code: String,
) {
    var failedAttempts: Int = 0
}

class AuthenticatedPokerPeer internal constructor(
    private val owner: PokerPairingController,
    internal val fingerprint: String,
) {
    internal fun belongsTo(controller: PokerPairingController): Boolean = owner === controller
}

class PokerPairingController(
    private val role: PokerPairingRole,
    private val identity: PokerPairingIdentity,
    private val store: PokerPairingStore,
    private val random: SecureRandom = SecureRandom(),
    private val codeFactory: () -> String = { generatePairingCode(random) },
) {
    private var record: PokerPairingRecord? = null
    private var window: PairingWindow? = null
    private var pendingDealerRecord: PokerPairingRecord? = null
    private var pendingChallenge: PokerPairingChallenge? = null
    private var pendingChallengeId: String? = null
    private var failure = PokerPairingFailure.NONE
    private var failedAttempts = 0

    init {
        reload()
    }

    val status: PokerPairingStatus
        get() = PokerPairingStatus(
            state = when {
                window != null -> PokerPairingState.ENROLLMENT_OPEN
                record != null -> PokerPairingState.PAIRED
                else -> PokerPairingState.UNPAIRED
            },
            failure = failure,
            failedAttempts = failedAttempts,
        )

    val isPaired: Boolean get() = record != null

    val endpoint: PokerHotspotEndpoint? get() = record?.endpoint

    fun reload() {
        window = null
        pendingDealerRecord = null
        pendingChallenge = null
        pendingChallengeId = null
        failedAttempts = 0
        try {
            val stored = store.load()
            if (stored == null) {
                record = null
                failure = PokerPairingFailure.NONE
                return
            }
            val localKey = currentPublicKey()
            val expectedLocal = if (role == PokerPairingRole.DEALER) {
                stored.dealerPublicKey
            } else {
                stored.pokerPublicKey
            }
            if (!sameBytes(localKey, expectedLocal)) {
                record = null
                failure = PokerPairingFailure.KEYSTORE_INVALID
                return
            }
            record = stored
            failure = PokerPairingFailure.NONE
        } catch (_: CorruptPokerPairingStateException) {
            record = null
            failure = PokerPairingFailure.CORRUPT_STATE
        } catch (_: PairingKeyUnavailableException) {
            record = null
            failure = PokerPairingFailure.KEYSTORE_INVALID
        }
    }

    /** Poker-only entry point, called by an explicit physical enrollment action. */
    fun openEnrollment(
        nowMs: Long,
        physicalEnrollmentConfirmed: Boolean,
        physicalReplacementConfirmed: Boolean = false,
    ): PokerPairingEnrollment {
        requireRole(PokerPairingRole.POKER)
        if (!physicalEnrollmentConfirmed) {
            failure = PokerPairingFailure.PHYSICAL_CONFIRMATION_REQUIRED
            throw PokerPairingRejected(failure)
        }
        ensureKeyUsable()
        val replacing = record != null
        if (replacing && !physicalReplacementConfirmed) {
            failure = PokerPairingFailure.REPLACEMENT_CONFIRMATION_REQUIRED
            throw PokerPairingRejected(failure)
        }
        val code = normalizeCode(codeFactory())
        require(code.length == 6 && code.all { it in '0'..'9' }) {
            "Pairing code must be six digits"
        }

        // Replacement is deliberately destructive before the new code is tried.
        if (replacing) store.clear()
        record = null
        pendingDealerRecord = null
        pendingChallenge = null
        pendingChallengeId = null
        failedAttempts = 0
        failure = PokerPairingFailure.NONE

        val challenge = PokerPairingChallenge(
            challengeId = randomToken(random, 16),
            nonce = ByteArray(32).also(random::nextBytes),
            pokerPublicKey = currentPublicKey(),
            createdAtMs = nowMs,
            expiresAtMs = nowMs + POKER_PAIRING_WINDOW_MS,
            replacement = replacing,
        )
        window = PairingWindow(challenge, code)
        return PokerPairingEnrollment(challenge, window!!.code)
    }

    /** Dealer-only response. The code never enters the serialized response. */
    fun respondToEnrollment(
        challenge: PokerPairingChallenge,
        code: String,
        nowMs: Long,
    ): PokerPairingResponse {
        requireRole(PokerPairingRole.DEALER)
        ensureKeyUsable()
        if (challenge.challengeId.isBlank() || challenge.nonce.isEmpty() ||
            challenge.pokerPublicKey.isEmpty() || challenge.expiresAtMs <= challenge.createdAtMs
        ) {
            failure = PokerPairingFailure.INVALID_CHALLENGE
            throw PokerPairingRejected(failure)
        }
        if (nowMs >= challenge.expiresAtMs) {
            failure = PokerPairingFailure.ENROLLMENT_EXPIRED
            throw PokerPairingRejected(failure)
        }
        val existing = record
        if (!challenge.replacement && existing != null &&
            !sameBytes(existing.pokerPublicKey, challenge.pokerPublicKey)
        ) {
            failure = PokerPairingFailure.PAIRING_MISMATCH
            throw PokerPairingRejected(failure)
        }
        if (challenge.replacement) {
            // The old trust is gone as soon as Poker has physically confirmed replacement.
            store.clear()
            record = null
            failure = PokerPairingFailure.NONE
        }

        val dealerKey = currentPublicKey()
        val response = PokerPairingResponse(
            challengeId = challenge.challengeId,
            dealerPublicKey = dealerKey,
            proof = PairingCrypto.proof(normalizeCode(code), challenge, dealerKey),
        )
        pendingChallengeId = challenge.challengeId
        pendingChallenge = challenge
        pendingDealerRecord = PokerPairingRecord(
            dealerPublicKey = dealerKey,
            pokerPublicKey = challenge.pokerPublicKey,
        )
        return response
    }

    /** Called by the Poker enrollment timer; it closes an idle window without network input. */
    fun closeExpiredEnrollment(nowMs: Long): Boolean {
        val active = window ?: return false
        if (nowMs < active.challenge.expiresAtMs) return false
        closeEnrollment(PokerPairingFailure.ENROLLMENT_EXPIRED)
        return true
    }

    /** Poker-only verification and commit. A successful response consumes the window. */
    fun acceptEnrollment(
        response: PokerPairingResponse,
        nowMs: Long,
    ): PokerPairingConfirmation {
        requireRole(PokerPairingRole.POKER)
        val active = window
        if (active == null || active.challenge.challengeId != response.challengeId) {
            failure = PokerPairingFailure.INVALID_CHALLENGE
            throw PokerPairingRejected(failure, failedAttempts)
        }
        if (nowMs >= active.challenge.expiresAtMs) {
            closeEnrollment(PokerPairingFailure.ENROLLMENT_EXPIRED)
            throw PokerPairingRejected(failure, failedAttempts)
        }
        val proofMatches = PairingCrypto.proofMatches(
            expected = PairingCrypto.proof(active.code, active.challenge, response.dealerPublicKey),
            actual = response.proof,
        ) && response.dealerPublicKey.isNotEmpty()
        if (!proofMatches) {
            active.failedAttempts++
            failedAttempts = active.failedAttempts
            if (active.failedAttempts >= POKER_PAIRING_MAX_ATTEMPTS) {
                closeEnrollment(PokerPairingFailure.ATTEMPTS_EXHAUSTED)
                throw PokerPairingRejected(failure, failedAttempts)
            }
            failure = PokerPairingFailure.INVALID_CODE
            throw PokerPairingRejected(failure, failedAttempts)
        }

        val localKey = currentPublicKey()
        val paired = PokerPairingRecord(
            dealerPublicKey = response.dealerPublicKey,
            pokerPublicKey = localKey,
        )
        store.save(paired)
        record = paired
        window = null
        failedAttempts = 0
        failure = PokerPairingFailure.NONE
        val pokerSignature = try {
            identity.sign(
                PairingCrypto.confirmationPayload(
                    active.challenge,
                    response.dealerPublicKey,
                    localKey,
                ),
            )
        } catch (_: PairingKeyUnavailableException) {
            record = null
            store.clear()
            failure = PokerPairingFailure.KEYSTORE_INVALID
            throw PokerPairingRejected(failure, failedAttempts)
        }
        return PokerPairingConfirmation(
            challengeId = response.challengeId,
            dealerPublicKey = response.dealerPublicKey,
            pokerPublicKey = localKey,
            pokerSignature = pokerSignature,
        )
    }

    /** Dealer-only commit after Poker acknowledges the verified proof. */
    fun confirmDealerPairing(confirmation: PokerPairingConfirmation) {
        requireRole(PokerPairingRole.DEALER)
        val pending = pendingDealerRecord
        val challenge = pendingChallenge
        if (pending == null || challenge == null || pendingChallengeId != confirmation.challengeId ||
            !sameBytes(pending.dealerPublicKey, confirmation.dealerPublicKey) ||
            !sameBytes(pending.pokerPublicKey, confirmation.pokerPublicKey) ||
            !PairingCrypto.verifySignature(
                confirmation.pokerPublicKey,
                PairingCrypto.confirmationPayload(
                    challenge,
                    confirmation.dealerPublicKey,
                    confirmation.pokerPublicKey,
                ),
                confirmation.pokerSignature,
            )
        ) {
            pendingDealerRecord = null
            pendingChallenge = null
            pendingChallengeId = null
            record = null
            failure = PokerPairingFailure.PAIRING_MISMATCH
            throw PokerPairingRejected(failure)
        }
        store.save(pending)
        record = pending
        pendingDealerRecord = null
        pendingChallenge = null
        pendingChallengeId = null
        failure = PokerPairingFailure.NONE
    }

    fun authenticatePeer(peerPublicKey: ByteArray): AuthenticatedPokerPeer {
        val paired = record ?: run {
            failure = if (identityUsable()) {
                PokerPairingFailure.PAIRING_MISMATCH
            } else {
                PokerPairingFailure.KEYSTORE_INVALID
            }
            throw PokerPairingRejected(failure)
        }
        val localKey = try {
            currentPublicKey()
        } catch (_: PairingKeyUnavailableException) {
            record = null
            failure = PokerPairingFailure.KEYSTORE_INVALID
            throw PokerPairingRejected(failure)
        }
        val expectedLocal = if (role == PokerPairingRole.DEALER) paired.dealerPublicKey else paired.pokerPublicKey
        if (!sameBytes(localKey, expectedLocal)) {
            record = null
            failure = PokerPairingFailure.KEYSTORE_INVALID
            throw PokerPairingRejected(failure)
        }
        val expectedPeer = if (role == PokerPairingRole.DEALER) {
            paired.pokerPublicKey
        } else {
            paired.dealerPublicKey
        }
        if (!sameBytes(peerPublicKey, expectedPeer)) {
            failure = PokerPairingFailure.PAIRING_MISMATCH
            throw PokerPairingRejected(failure)
        }
        failure = PokerPairingFailure.NONE
        return AuthenticatedPokerPeer(this, fingerprint(peerPublicKey))
    }

    /** Only a Dealer may update the endpoint, and only with a peer-authentication token. */
    fun updateEndpoint(
        authenticatedPeer: AuthenticatedPokerPeer,
        endpoint: PokerHotspotEndpoint,
    ) {
        requireRole(PokerPairingRole.DEALER)
        val current = record ?: throw PokerPairingRejected(PokerPairingFailure.PAIRING_MISMATCH)
        val localKey = try {
            currentPublicKey()
        } catch (_: PairingKeyUnavailableException) {
            record = null
            failure = PokerPairingFailure.KEYSTORE_INVALID
            throw PokerPairingRejected(failure)
        }
        if (!sameBytes(localKey, current.dealerPublicKey)) {
            record = null
            failure = PokerPairingFailure.KEYSTORE_INVALID
            throw PokerPairingRejected(failure)
        }
        if (!authenticatedPeer.belongsTo(this) ||
            authenticatedPeer.fingerprint != fingerprint(current.pokerPublicKey)
        ) {
            failure = PokerPairingFailure.PAIRING_MISMATCH
            throw PokerPairingRejected(failure)
        }
        val updated = current.copy(endpoint = endpoint)
        store.save(updated)
        record = updated
        failure = PokerPairingFailure.NONE
    }

    private fun closeEnrollment(reason: PokerPairingFailure) {
        window = null
        failure = reason
        failedAttempts = if (reason == PokerPairingFailure.ATTEMPTS_EXHAUSTED) {
            POKER_PAIRING_MAX_ATTEMPTS
        } else {
            failedAttempts
        }
    }

    private fun ensureKeyUsable() {
        try {
            currentPublicKey()
        } catch (_: PairingKeyUnavailableException) {
            failure = PokerPairingFailure.KEYSTORE_INVALID
            throw PokerPairingRejected(failure)
        }
    }

    private fun identityUsable(): Boolean = runCatching { currentPublicKey() }.isSuccess

    private fun currentPublicKey(): ByteArray = identity.publicKey
        .takeIf { it.isNotEmpty() }
        ?.copyOf()
        ?: throw PairingKeyUnavailableException()

    private fun requireRole(expected: PokerPairingRole) {
        if (role != expected) {
            failure = PokerPairingFailure.NOT_ENROLLMENT_DEVICE
            throw PokerPairingRejected(failure)
        }
    }

    private companion object {
        fun normalizeCode(code: String): String = code.trim()

        fun generatePairingCode(random: SecureRandom): String =
            random.nextInt(1_000_000).toString().padStart(6, '0')

        fun randomToken(random: SecureRandom, bytes: Int): String = ByteArray(bytes).also(random::nextBytes)
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

        fun sameBytes(left: ByteArray, right: ByteArray): Boolean =
            MessageDigest.isEqual(left, right)

        fun fingerprint(publicKey: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(publicKey)
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
    }
}

private object PairingCrypto {
    fun proof(
        code: String,
        challenge: PokerPairingChallenge,
        dealerPublicKey: ByteArray,
    ): ByteArray {
        val transcript = buildList<Byte> {
            addAll("poker-dealer/pairing/v1\u0000".toByteArray(Charsets.UTF_8).toList())
            addAll(challenge.challengeId.toByteArray(Charsets.UTF_8).toList())
            add(0)
            addAll(challenge.nonce.toList())
            addAll(challenge.pokerPublicKey.toList())
            addAll(dealerPublicKey.toList())
            add(if (challenge.replacement) 1 else 0)
        }.toByteArray()
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(code.toByteArray(Charsets.UTF_8), algorithm))
            doFinal(transcript)
        }
    }

    fun proofMatches(expected: ByteArray, actual: ByteArray): Boolean =
        MessageDigest.isEqual(expected, actual)

    fun confirmationPayload(
        challenge: PokerPairingChallenge,
        dealerPublicKey: ByteArray,
        pokerPublicKey: ByteArray,
    ): ByteArray = buildList<Byte> {
        addAll("poker-dealer/pairing/confirmation/v1\u0000".toByteArray(Charsets.UTF_8).toList())
        addAll(challenge.challengeId.toByteArray(Charsets.UTF_8).toList())
        add(0)
        addAll(challenge.nonce.toList())
        addAll(dealerPublicKey.toList())
        addAll(pokerPublicKey.toList())
        add(if (challenge.replacement) 1 else 0)
    }.toByteArray()

    fun verifySignature(
        publicKey: ByteArray,
        payload: ByteArray,
        signatureBytes: ByteArray,
    ): Boolean = runCatching {
        val key = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(publicKey))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(payload)
            verify(signatureBytes)
        }
    }.getOrDefault(false)
}
