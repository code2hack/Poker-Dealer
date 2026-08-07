package com.code2hack.pokerdealer.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val POKER_PAIRING_WINDOW_MS = 5 * 60 * 1_000L
const val POKER_PAIRING_MAX_ATTEMPTS = 5
const val POKER_PAIRING_CHALLENGE_TYPE = "pairing.challenge"
const val POKER_PAIRING_RESPONSE_TYPE = "pairing.response"
const val POKER_PAIRING_CONFIRMATION_TYPE = "pairing.confirmation"

private const val POKER_PAIRING_SALT_BYTES = 16

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
    val salt: ByteArray,
    val serverEphemeralPublicKey: ByteArray,
)

@Serializable
data class PokerPairingResponse(
    val challengeId: String,
    val dealerPublicKey: ByteArray,
    val clientEphemeralPublicKey: ByteArray,
    val clientProof: ByteArray,
)

@Serializable
data class PokerPairingConfirmation(
    val challengeId: String,
    val dealerPublicKey: ByteArray,
    val pokerPublicKey: ByteArray,
    val clientEphemeralPublicKey: ByteArray,
    val clientProof: ByteArray,
    val serverProof: ByteArray,
    val pokerSignature: ByteArray,
)

data class PokerPairingEnrollment(
    val challenge: PokerPairingChallenge,
    /** Displayed locally; it is deliberately absent from the wire challenge. */
    val displayCode: String,
) {
    override fun toString(): String =
        "PokerPairingEnrollment(challenge=<redacted>, displayCode=<redacted>)"
}

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
    ENROLLMENT_DISCOVERY_FAILED,
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
    val server: SrpServerState,
) {
    var failedAttempts: Int = 0
}

private data class SrpServerState(
    val verifier: BigInteger,
    val privateExponent: BigInteger,
)

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
    private var pendingClientEphemeralPublicKey: ByteArray? = null
    private var pendingClientProof: ByteArray? = null
    private var pendingSessionKey: ByteArray? = null
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

    /** The non-secret public key pinned for the next mutually-authenticated connection. */
    val pinnedPeerPublicKey: ByteArray?
        get() = record?.let { paired ->
            (if (role == PokerPairingRole.DEALER) paired.pokerPublicKey else paired.dealerPublicKey)
                .copyOf()
        }

    fun reload() {
        window = null
        pendingDealerRecord = null
        pendingChallenge = null
        pendingChallengeId = null
        pendingClientEphemeralPublicKey = null
        pendingClientProof = null
        pendingSessionKey = null
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
        pendingClientEphemeralPublicKey = null
        pendingClientProof = null
        pendingSessionKey = null
        failedAttempts = 0
        failure = PokerPairingFailure.NONE

        val server = PairingCrypto.serverState(random, code)
        val challenge = PokerPairingChallenge(
            challengeId = randomToken(random, 16),
            nonce = ByteArray(32).also(random::nextBytes),
            pokerPublicKey = currentPublicKey(),
            createdAtMs = nowMs,
            expiresAtMs = nowMs + POKER_PAIRING_WINDOW_MS,
            replacement = replacing,
            salt = server.salt,
            serverEphemeralPublicKey = server.publicKey,
        )
        window = PairingWindow(challenge, server.state)
        return PokerPairingEnrollment(challenge, code)
    }

    /** Dealer-only response. The code never enters the serialized response. */
    fun respondToEnrollment(
        challenge: PokerPairingChallenge,
        code: String,
        nowMs: Long,
    ): PokerPairingResponse {
        requireRole(PokerPairingRole.DEALER)
        ensureKeyUsable()
        if (!PairingCrypto.isValidChallenge(challenge)) {
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
        val clientSession = try {
            PairingCrypto.clientSession(challenge, normalizeCode(code), dealerKey, random)
        } catch (_: IllegalArgumentException) {
            failure = PokerPairingFailure.INVALID_CHALLENGE
            throw PokerPairingRejected(failure)
        }
        val response = PokerPairingResponse(
            challengeId = challenge.challengeId,
            dealerPublicKey = dealerKey,
            clientEphemeralPublicKey = clientSession.publicKey,
            clientProof = clientSession.proof,
        )
        pendingChallengeId = challenge.challengeId
        pendingChallenge = challenge
        pendingClientEphemeralPublicKey = clientSession.publicKey
        pendingClientProof = clientSession.proof
        pendingSessionKey = clientSession.sessionKey
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

    fun cancelEnrollment(reason: PokerPairingFailure = PokerPairingFailure.INVALID_ENDPOINT) {
        requireRole(PokerPairingRole.POKER)
        if (window != null) closeEnrollment(reason)
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
        val sessionKey = runCatching {
            PairingCrypto.serverSessionKey(
                active.challenge,
                response.dealerPublicKey,
                response.clientEphemeralPublicKey,
                active.server,
            )
        }.getOrNull()
        val proofMatches = sessionKey != null && response.dealerPublicKey.isNotEmpty() &&
            PairingCrypto.proofMatches(
                expected = PairingCrypto.clientProof(
                    active.challenge,
                    response.dealerPublicKey,
                    response.clientEphemeralPublicKey,
                    sessionKey,
                ),
                actual = response.clientProof,
            )
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

        val localKey = try {
            currentPublicKey()
        } catch (_: PairingKeyUnavailableException) {
            closeEnrollment(PokerPairingFailure.KEYSTORE_INVALID)
            record = null
            store.clear()
            throw PokerPairingRejected(failure, failedAttempts)
        }
        val serverProof = PairingCrypto.serverProof(
            active.challenge,
            response.dealerPublicKey,
            response.clientEphemeralPublicKey,
            response.clientProof,
            sessionKey,
        )
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
                    response.clientEphemeralPublicKey,
                    response.clientProof,
                    serverProof,
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
            clientEphemeralPublicKey = response.clientEphemeralPublicKey,
            clientProof = response.clientProof,
            serverProof = serverProof,
            pokerSignature = pokerSignature,
        )
    }

    /** Dealer-only commit after Poker acknowledges the verified proof. */
    fun confirmDealerPairing(confirmation: PokerPairingConfirmation): AuthenticatedPokerPeer {
        requireRole(PokerPairingRole.DEALER)
        val pending = pendingDealerRecord
        val challenge = pendingChallenge
        val clientEphemeralPublicKey = pendingClientEphemeralPublicKey
        val clientProof = pendingClientProof
        val sessionKey = pendingSessionKey
        if (pending == null || challenge == null || clientEphemeralPublicKey == null ||
            clientProof == null || sessionKey == null || pendingChallengeId != confirmation.challengeId ||
            !sameBytes(pending.dealerPublicKey, confirmation.dealerPublicKey) ||
            !sameBytes(pending.pokerPublicKey, confirmation.pokerPublicKey) ||
            !sameBytes(clientEphemeralPublicKey, confirmation.clientEphemeralPublicKey) ||
            !sameBytes(clientProof, confirmation.clientProof) ||
            !sameBytes(challenge.pokerPublicKey, confirmation.pokerPublicKey) ||
            !PairingCrypto.proofMatches(
                PairingCrypto.serverProof(
                    challenge,
                    confirmation.dealerPublicKey,
                    confirmation.clientEphemeralPublicKey,
                    confirmation.clientProof,
                    sessionKey,
                ),
                confirmation.serverProof,
            ) ||
            !PairingCrypto.verifySignature(
                confirmation.pokerPublicKey,
                PairingCrypto.confirmationPayload(
                    challenge,
                    confirmation.dealerPublicKey,
                    confirmation.pokerPublicKey,
                    confirmation.clientEphemeralPublicKey,
                    confirmation.clientProof,
                    confirmation.serverProof,
                ),
                confirmation.pokerSignature,
            )
        ) {
            pendingDealerRecord = null
            pendingChallenge = null
            pendingChallengeId = null
            pendingClientEphemeralPublicKey = null
            pendingClientProof = null
            pendingSessionKey = null
            record = null
            failure = PokerPairingFailure.PAIRING_MISMATCH
            throw PokerPairingRejected(failure)
        }
        store.save(pending)
        record = pending
        pendingDealerRecord = null
        pendingChallenge = null
        pendingChallengeId = null
        pendingClientEphemeralPublicKey = null
        pendingClientProof = null
        pendingSessionKey = null
        failure = PokerPairingFailure.NONE
        return AuthenticatedPokerPeer(this, fingerprint(pending.pokerPublicKey))
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

private data class SrpServerEnrollment(
    val salt: ByteArray,
    val publicKey: ByteArray,
    val state: SrpServerState,
)

private data class SrpClientSession(
    val publicKey: ByteArray,
    val proof: ByteArray,
    val sessionKey: ByteArray,
)

/** SRP-6a-style PAKE: the six-digit code is never a wire-verifiable MAC key. */
private object PairingCrypto {
    private val modulus = BigInteger(
        """
        FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1
        29024E088A67CC74020BBEA63B139B22514A08798E3404DD
        EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E
        485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED
        EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC
        2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F836
        55D23DCA3AD961C62F356208552BB9ED529077096966D670C
        354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E77
        2C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6
        955817183995497CEA956AE515D2261898FA051015728E5A8
        AACAA68FFFFFFFFFFFFFFFF
        """.replace("\n", "").replace(" ", ""),
        16,
    )
    private val generator = BigInteger.valueOf(2)
    private val subgroupOrder = modulus.subtract(BigInteger.ONE).divide(BigInteger.valueOf(2))
    private val modulusBytes = (modulus.bitLength() + 7) / 8
    private val multiplier = hashInteger(padded(modulus), padded(generator))

    fun serverState(random: SecureRandom, code: String): SrpServerEnrollment {
        val salt = ByteArray(POKER_PAIRING_SALT_BYTES).also(random::nextBytes)
        val privateExponent = randomExponent(random)
        val verifier = verifier(salt, code)
        val publicKey = generator.modPow(privateExponent, modulus)
            .add(multiplier.multiply(verifier))
            .mod(modulus)
        require(publicKey.signum() != 0) { "Invalid pairing server public key" }
        return SrpServerEnrollment(
            salt = salt,
            publicKey = padded(publicKey),
            state = SrpServerState(verifier, privateExponent),
        )
    }

    fun isValidChallenge(challenge: PokerPairingChallenge): Boolean =
        challenge.challengeId.isNotBlank() &&
            challenge.nonce.size == 32 &&
            challenge.pokerPublicKey.isNotEmpty() &&
            challenge.expiresAtMs > challenge.createdAtMs &&
            challenge.salt.size == POKER_PAIRING_SALT_BYTES &&
            challenge.serverEphemeralPublicKey.size == modulusBytes &&
            runCatching { parsePublic(challenge.serverEphemeralPublicKey) }.isSuccess

    fun clientSession(
        challenge: PokerPairingChallenge,
        code: String,
        dealerPublicKey: ByteArray,
        random: SecureRandom,
    ): SrpClientSession {
        require(isValidChallenge(challenge)) { "Invalid pairing challenge" }
        require(dealerPublicKey.isNotEmpty()) { "Dealer identity is unavailable" }
        val serverPublicKey = parsePublic(challenge.serverEphemeralPublicKey)
        val privateExponent = randomExponent(random)
        val clientPublicKey = generator.modPow(privateExponent, modulus)
        val x = verifierExponent(challenge.salt, code)
        val u = hashInteger(padded(clientPublicKey), padded(serverPublicKey))
        val base = serverPublicKey.subtract(
            multiplier.multiply(generator.modPow(x, modulus)),
        ).mod(modulus)
        require(base.signum() != 0) { "Invalid pairing server public key" }
        val shared = base.modPow(privateExponent.add(u.multiply(x)), modulus)
        val sessionKey = sessionKey(shared)
        return SrpClientSession(
            publicKey = padded(clientPublicKey),
            proof = clientProof(challenge, dealerPublicKey, padded(clientPublicKey), sessionKey),
            sessionKey = sessionKey,
        )
    }

    fun serverSessionKey(
        challenge: PokerPairingChallenge,
        dealerPublicKey: ByteArray,
        clientPublicKey: ByteArray,
        server: SrpServerState,
    ): ByteArray {
        require(isValidChallenge(challenge)) { "Invalid pairing challenge" }
        require(dealerPublicKey.isNotEmpty()) { "Dealer identity is unavailable" }
        val client = parseClientPublic(clientPublicKey)
        val serverPublicKey = parsePublic(challenge.serverEphemeralPublicKey)
        val u = hashInteger(padded(client), padded(serverPublicKey))
        val shared = client.multiply(server.verifier.modPow(u, modulus))
            .mod(modulus)
            .modPow(server.privateExponent, modulus)
        return sessionKey(shared)
    }

    fun clientProof(
        challenge: PokerPairingChallenge,
        dealerPublicKey: ByteArray,
        clientPublicKey: ByteArray,
        sessionKey: ByteArray,
    ): ByteArray = sha256(
        transcript(
            label = "client",
            challenge = challenge,
            dealerPublicKey = dealerPublicKey,
            clientPublicKey = clientPublicKey,
        ),
        sessionKey,
    )

    fun serverProof(
        challenge: PokerPairingChallenge,
        dealerPublicKey: ByteArray,
        clientPublicKey: ByteArray,
        clientProof: ByteArray,
        sessionKey: ByteArray,
    ): ByteArray = sha256(
        transcript(
            label = "server",
            challenge = challenge,
            dealerPublicKey = dealerPublicKey,
            clientPublicKey = clientPublicKey,
            clientProof = clientProof,
        ),
        sessionKey,
    )

    fun proofMatches(expected: ByteArray, actual: ByteArray): Boolean =
        MessageDigest.isEqual(expected, actual)

    fun confirmationPayload(
        challenge: PokerPairingChallenge,
        dealerPublicKey: ByteArray,
        pokerPublicKey: ByteArray,
        clientPublicKey: ByteArray,
        clientProof: ByteArray,
        serverProof: ByteArray,
    ): ByteArray = transcript(
        label = "confirmation",
        challenge = challenge,
        dealerPublicKey = dealerPublicKey,
        clientPublicKey = clientPublicKey,
        pokerPublicKey = pokerPublicKey,
        clientProof = clientProof,
        serverProof = serverProof,
    )

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

    private fun verifier(salt: ByteArray, code: String): BigInteger =
        generator.modPow(verifierExponent(salt, code), modulus)

    private fun verifierExponent(salt: ByteArray, code: String): BigInteger =
        BigInteger(1, sha256(salt, code.toByteArray(StandardCharsets.UTF_8)))

    private fun sessionKey(shared: BigInteger): ByteArray = sha256(padded(shared))

    private fun randomExponent(random: SecureRandom): BigInteger =
        BigInteger(256, random).add(BigInteger.ONE)

    private fun parsePublic(encoded: ByteArray): BigInteger {
        require(encoded.size == modulusBytes) { "Invalid pairing public key" }
        return BigInteger(1, encoded).also {
            require(it > BigInteger.ZERO && it < modulus) { "Invalid pairing public key" }
        }
    }

    private fun parseClientPublic(encoded: ByteArray): BigInteger = parsePublic(encoded).also {
        require(it.modPow(subgroupOrder, modulus) == BigInteger.ONE) {
            "Invalid pairing client public key"
        }
    }

    private fun padded(value: BigInteger): ByteArray {
        require(value.signum() >= 0 && value <= modulus) { "Invalid pairing value" }
        val source = value.toByteArray()
        val result = ByteArray(modulusBytes)
        val length = minOf(source.size, result.size)
        source.copyInto(result, destinationOffset = result.size - length, startIndex = source.size - length)
        return result
    }

    private fun hashInteger(vararg values: ByteArray): BigInteger =
        BigInteger(1, sha256(*values))

    private fun sha256(vararg values: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            values.forEach(::update)
            digest()
        }

    private fun transcript(
        label: String,
        challenge: PokerPairingChallenge,
        dealerPublicKey: ByteArray,
        clientPublicKey: ByteArray,
        pokerPublicKey: ByteArray = challenge.pokerPublicKey,
        clientProof: ByteArray = ByteArray(0),
        serverProof: ByteArray = ByteArray(0),
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            fun field(value: ByteArray) {
                output.writeInt(value.size)
                output.write(value)
            }

            field("poker-dealer/pairing/srp/v1:$label".toByteArray(StandardCharsets.UTF_8))
            field(challenge.challengeId.toByteArray(StandardCharsets.UTF_8))
            field(challenge.nonce)
            field(challenge.pokerPublicKey)
            field(pokerPublicKey)
            output.writeLong(challenge.createdAtMs)
            output.writeLong(challenge.expiresAtMs)
            output.writeBoolean(challenge.replacement)
            field(challenge.salt)
            field(challenge.serverEphemeralPublicKey)
            field(dealerPublicKey)
            field(clientPublicKey)
            field(clientProof)
            field(serverProof)
        }
        bytes.toByteArray()
    }
}
