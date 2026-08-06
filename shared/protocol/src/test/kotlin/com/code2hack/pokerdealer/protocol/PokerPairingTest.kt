package com.code2hack.pokerdealer.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PokerPairingTest {
    @Test
    fun `initial pairing proves code without putting it in the challenge or response`() {
        val pokerKey = FakeIdentity(byteArrayOf(1, 2, 3))
        val dealerKey = FakeIdentity(byteArrayOf(4, 5, 6))
        val poker = controller(PokerPairingRole.POKER, pokerKey, "123456")
        val dealer = controller(PokerPairingRole.DEALER, dealerKey)

        val enrollment = poker.openEnrollment(
            nowMs = 1_000,
            physicalEnrollmentConfirmed = true,
        )
        val challengeWire = PokerProtocolJson.encodeToString(
            PokerPairingChallenge.serializer(),
            enrollment.challenge,
        )
        assertFalse(challengeWire.contains(enrollment.displayCode))

        val response = dealer.respondToEnrollment(enrollment.challenge, " ${enrollment.displayCode} ", 1_001)
        val responseWire = PokerProtocolJson.encodeToString(PokerPairingResponse.serializer(), response)
        assertFalse(responseWire.contains(enrollment.displayCode))

        val confirmation = poker.acceptEnrollment(response, 1_002)
        dealer.confirmDealerPairing(confirmation)

        assertEquals(PokerPairingState.PAIRED, poker.status.state)
        assertEquals(PokerPairingState.PAIRED, dealer.status.state)
        assertArrayEquals(pokerKey.key, confirmation.pokerPublicKey)
        assertArrayEquals(dealerKey.key, confirmation.dealerPublicKey)
        assertThrows(PokerPairingRejected::class.java) {
            poker.acceptEnrollment(response, 1_003)
        }
    }

    @Test
    fun `captured PAKE wire material needs the hidden ephemeral and has no code oracle`() {
        val poker = controller(
            PokerPairingRole.POKER,
            FakeIdentity(byteArrayOf(1, 2, 3)),
            "123456",
        )
        val dealer = controller(PokerPairingRole.DEALER, FakeIdentity(byteArrayOf(4, 5, 6)))
        val enrollment = poker.openEnrollment(0, physicalEnrollmentConfirmed = true)
        val response = dealer.respondToEnrollment(enrollment.challenge, enrollment.displayCode, 1)
        val repeatedResponse = dealer.respondToEnrollment(enrollment.challenge, enrollment.displayCode, 2)
        val challengeWire = PokerProtocolJson.encodeToString(
            PokerPairingChallenge.serializer(),
            enrollment.challenge,
        )
        val responseWire = PokerProtocolJson.encodeToString(PokerPairingResponse.serializer(), response)
        val capturedResponse = PokerProtocolJson.decodeFromString(
            PokerPairingResponse.serializer(),
            responseWire,
        )

        // A passive dictionary checker sees both public ephemerals, but not the
        // client exponent needed to reproduce a fixed transcript's proof.
        assertFalse(challengeWire.contains(enrollment.displayCode))
        assertFalse(responseWire.contains(enrollment.displayCode))
        assertFalse(capturedResponse.clientEphemeralPublicKey.contentEquals(repeatedResponse.clientEphemeralPublicKey))
        assertFalse(capturedResponse.clientProof.contentEquals(repeatedResponse.clientProof))
        assertEquals(32, capturedResponse.clientProof.size)
    }

    @Test
    fun `enrollment diagnostics redact code and challenge state`() {
        val poker = controller(
            PokerPairingRole.POKER,
            FakeIdentity(byteArrayOf(1, 2, 3)),
            "123456",
        )
        val enrollment = poker.openEnrollment(0, physicalEnrollmentConfirmed = true)

        assertEquals(
            "PokerPairingEnrollment(challenge=<redacted>, displayCode=<redacted>)",
            enrollment.toString(),
        )
        assertFalse(enrollment.toString().contains(enrollment.displayCode))
        assertFalse(enrollment.toString().contains(enrollment.challenge.challengeId))
    }

    @Test
    fun `pairing wire round trip carries only the PAKE transcript`() {
        val poker = controller(
            PokerPairingRole.POKER,
            FakeIdentity(byteArrayOf(1, 2, 3)),
            "123456",
        )
        val enrollment = poker.openEnrollment(0, physicalEnrollmentConfirmed = true)
        val output = ByteArrayOutputStream()

        PokerPairingWire.write(output, PokerPairingWire.challenge(enrollment.challenge))
        val captured = output.toByteArray()
        val decoded = PokerPairingWire.read(ByteArrayInputStream(captured))

        assertEquals(POKER_PAIRING_CHALLENGE_TYPE, decoded?.type)
        assertFalse(captured.toString(Charsets.UTF_8).contains(enrollment.displayCode))
        assertTrue(decoded?.toString()?.contains("redacted") == true)
    }

    @Test
    fun `enrollment code is always six digits`() {
        val poker = controller(
            PokerPairingRole.POKER,
            FakeIdentity(byteArrayOf(1)),
            "12-3456",
        )

        assertThrows(IllegalArgumentException::class.java) {
            poker.openEnrollment(0, physicalEnrollmentConfirmed = true)
        }
    }

    @Test
    fun `enrollment expires at five minutes`() {
        val poker = controller(
            PokerPairingRole.POKER,
            FakeIdentity(byteArrayOf(1)),
            "123456",
        )
        val enrollment = poker.openEnrollment(0, physicalEnrollmentConfirmed = true)

        assertTrue(poker.closeExpiredEnrollment(enrollment.challenge.expiresAtMs))
        assertEquals(PokerPairingFailure.ENROLLMENT_EXPIRED, poker.status.failure)
        assertEquals(PokerPairingState.UNPAIRED, poker.status.state)
    }

    @Test
    fun `five wrong proofs close the window and do not restore a previous pairing`() {
        val pokerKey = FakeIdentity(byteArrayOf(1))
        val dealerKey = FakeIdentity(byteArrayOf(2))
        val poker = controller(PokerPairingRole.POKER, pokerKey, "654321")
        val dealer = controller(PokerPairingRole.DEALER, dealerKey)
        val enrollment = poker.openEnrollment(0, physicalEnrollmentConfirmed = true)

        repeat(POKER_PAIRING_MAX_ATTEMPTS) {
            val response = dealer.respondToEnrollment(enrollment.challenge, "000000", it.toLong())
            assertThrows(PokerPairingRejected::class.java) {
                poker.acceptEnrollment(response, it.toLong())
            }
        }

        assertEquals(PokerPairingFailure.ATTEMPTS_EXHAUSTED, poker.status.failure)
        assertEquals(POKER_PAIRING_MAX_ATTEMPTS, poker.status.failedAttempts)
        assertFalse(poker.isPaired)
    }

    @Test
    fun `pinned identity authenticates reconnect and rejects a different peer`() {
        val pokerKey = FakeIdentity(byteArrayOf(1, 2))
        val dealerKey = FakeIdentity(byteArrayOf(3, 4))
        val (poker, dealer) = pair(pokerKey, dealerKey)

        assertNotNull(dealer.authenticatePeer(pokerKey.key))
        assertNotNull(poker.authenticatePeer(dealerKey.key))
        val failure = assertThrows(PokerPairingRejected::class.java) {
            dealer.authenticatePeer(byteArrayOf(9, 9))
        }
        assertEquals(PokerPairingFailure.PAIRING_MISMATCH, failure.reason)
        assertEquals(PokerPairingState.PAIRED, dealer.status.state)
    }

    @Test
    fun `pairing survives controller reload`() {
        val pokerKey = FakeIdentity(byteArrayOf(1, 2))
        val dealerKey = FakeIdentity(byteArrayOf(3, 4))
        val pokerStore = MemoryPairingStore()
        val dealerStore = MemoryPairingStore()
        val poker = PokerPairingController(
            PokerPairingRole.POKER,
            pokerKey,
            pokerStore,
            codeFactory = { "123456" },
        )
        val dealer = PokerPairingController(PokerPairingRole.DEALER, dealerKey, dealerStore)
        val enrollment = poker.openEnrollment(0, physicalEnrollmentConfirmed = true)
        dealer.confirmDealerPairing(poker.acceptEnrollment(dealer.respondToEnrollment(enrollment.challenge, enrollment.displayCode, 1), 2))

        val reloadedPoker = PokerPairingController(PokerPairingRole.POKER, pokerKey, pokerStore)
        val reloadedDealer = PokerPairingController(PokerPairingRole.DEALER, dealerKey, dealerStore)
        assertNotNull(reloadedPoker.authenticatePeer(dealerKey.key))
        assertNotNull(reloadedDealer.authenticatePeer(pokerKey.key))
    }

    @Test
    fun `endpoint changes require authenticated pinned Poker`() {
        val pokerKey = FakeIdentity(byteArrayOf(1))
        val dealerKey = FakeIdentity(byteArrayOf(2))
        val (poker, dealer) = pair(pokerKey, dealerKey)
        val endpoint = PokerHotspotEndpoint("192.168.43.2", 42_000)

        assertThrows(PokerPairingRejected::class.java) {
            dealer.authenticatePeer(byteArrayOf(8))
        }
        assertEquals(null, dealer.endpoint)

        dealer.updateEndpoint(dealer.authenticatePeer(pokerKey.key), endpoint)
        assertEquals(endpoint, dealer.endpoint)
        assertEquals(null, poker.endpoint)
    }

    @Test
    fun `replacement revokes old trust before a failed replacement`() {
        val oldPokerKey = FakeIdentity(byteArrayOf(1))
        val oldDealerKey = FakeIdentity(byteArrayOf(2))
        val (poker, dealer) = pair(oldPokerKey, oldDealerKey)

        assertThrows(PokerPairingRejected::class.java) {
            poker.openEnrollment(10, physicalEnrollmentConfirmed = true)
        }
        val replacement = poker.openEnrollment(
            10,
            physicalEnrollmentConfirmed = true,
            physicalReplacementConfirmed = true,
        )
        assertFalse(poker.isPaired)

        val response = dealer.respondToEnrollment(replacement.challenge, "wrong", 11)
        assertFalse(dealer.isPaired)
        repeat(POKER_PAIRING_MAX_ATTEMPTS) {
            assertThrows(PokerPairingRejected::class.java) {
                poker.acceptEnrollment(response, 12L + it)
            }
        }

        assertFalse(poker.isPaired)
        assertFalse(dealer.isPaired)
        assertEquals(PokerPairingFailure.ATTEMPTS_EXHAUSTED, poker.status.failure)
        assertThrows(PokerPairingRejected::class.java) {
            dealer.authenticatePeer(oldPokerKey.key)
        }
    }

    @Test
    fun `keystore key loss and corrupt state fail closed`() {
        val directory = createTempDirectory("pairing")
        val file = directory.resolve("pairing.json").toFile()
        val store = FilePokerPairingStore(file)
        val originalKey = FakeIdentity(byteArrayOf(1, 2))
        val peerKey = FakeIdentity(byteArrayOf(3, 4))
        store.save(PokerPairingRecord(originalKey.key, peerKey.key))

        val lostKey = FakeIdentity(byteArrayOf(9, 9))
        val lost = PokerPairingController(PokerPairingRole.DEALER, lostKey, store)
        assertEquals(PokerPairingFailure.KEYSTORE_INVALID, lost.status.failure)
        assertFalse(lost.isPaired)

        Files.writeString(file.toPath(), "not-json")
        val corrupt = PokerPairingController(
            PokerPairingRole.DEALER,
            originalKey,
            FilePokerPairingStore(file),
        )
        assertEquals(PokerPairingFailure.CORRUPT_STATE, corrupt.status.failure)
        assertFalse(corrupt.isPaired)
    }

    @Test
    fun `pairing failures expose no key or code material`() {
        val poker = controller(
            PokerPairingRole.POKER,
            FakeIdentity(byteArrayOf(1, 2, 3)),
            "123456",
        )
        val enrollment = poker.openEnrollment(0, physicalEnrollmentConfirmed = true)
        val failure = assertThrows(PokerPairingRejected::class.java) {
            poker.acceptEnrollment(
                PokerPairingResponse(
                    challengeId = enrollment.challenge.challengeId,
                    dealerPublicKey = byteArrayOf(9),
                    clientEphemeralPublicKey = ByteArray(256),
                    clientProof = byteArrayOf(8),
                ),
                1,
            )
        }

        assertTrue(failure.message?.contains("123456") != true)
        assertTrue(failure.message?.contains("010203") != true)
    }

    private fun pair(
        pokerKey: FakeIdentity,
        dealerKey: FakeIdentity,
    ): Pair<PokerPairingController, PokerPairingController> {
        val poker = controller(PokerPairingRole.POKER, pokerKey, "123456")
        val dealer = controller(PokerPairingRole.DEALER, dealerKey)
        val enrollment = poker.openEnrollment(0, physicalEnrollmentConfirmed = true)
        val response = dealer.respondToEnrollment(enrollment.challenge, enrollment.displayCode, 1)
        val confirmation = poker.acceptEnrollment(response, 2)
        dealer.confirmDealerPairing(confirmation)
        return poker to dealer
    }

    private fun controller(
        role: PokerPairingRole,
        identity: FakeIdentity,
        code: String = "123456",
    ) = PokerPairingController(
        role = role,
        identity = identity,
        store = MemoryPairingStore(),
        codeFactory = { code },
    )

    private class FakeIdentity(
        @Suppress("UNUSED_PARAMETER") seed: ByteArray,
    ) : PokerPairingIdentity {
        private val keyPair = KeyPairGenerator.getInstance("EC").generateKeyPair()
        val key: ByteArray get() = keyPair.public.encoded
        override val publicKey: ByteArray get() = key

        override fun sign(payload: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }
    }

    private class MemoryPairingStore : PokerPairingStore {
        private var record: PokerPairingRecord? = null
        override fun load(): PokerPairingRecord? = record
        override fun save(record: PokerPairingRecord) {
            this.record = record
        }
        override fun clear() {
            record = null
        }
    }
}
