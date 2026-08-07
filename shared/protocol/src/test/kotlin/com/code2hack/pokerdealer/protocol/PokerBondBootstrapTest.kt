package com.code2hack.pokerdealer.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.Signature
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PokerBondBootstrapTest {
    @Test
    fun `bond bootstrap proves both app keys and preserves the Wi-Fi endpoint`() {
        val dealer = FakeIdentity()
        val poker = FakeIdentity()
        val endpoint = PokerHotspotEndpoint("192.168.1.8", POKER_LISTENER_PORT)
        val hello = PokerBondBootstrapProtocol.createHello(dealer)
        val offer = PokerBondBootstrapProtocol.createOffer(poker, hello, endpoint)

        assertTrue(PokerBondBootstrapProtocol.verifyOffer(hello, offer))
        val confirmation = PokerBondBootstrapProtocol.createConfirmation(dealer, hello, offer)
        assertTrue(PokerBondBootstrapProtocol.verifyConfirmation(hello, offer, confirmation))
        assertEquals(endpoint, offer.endpoint)
        assertArrayEquals(dealer.publicKey, hello.dealerPublicKey)
        assertArrayEquals(poker.publicKey, offer.pokerPublicKey)
    }

    @Test
    fun `changing the authenticated endpoint invalidates the Poker proof`() {
        val dealer = FakeIdentity()
        val poker = FakeIdentity()
        val hello = PokerBondBootstrapProtocol.createHello(dealer)
        val offer = PokerBondBootstrapProtocol.createOffer(
            poker,
            hello,
            PokerHotspotEndpoint("192.168.1.8", POKER_LISTENER_PORT),
        )

        assertFalse(
            PokerBondBootstrapProtocol.verifyOffer(
                hello,
                offer.copy(endpoint = PokerHotspotEndpoint("192.168.1.9", POKER_LISTENER_PORT)),
            ),
        )
    }

    @Test
    fun `changing the Poker key invalidates the Dealer confirmation`() {
        val dealer = FakeIdentity()
        val poker = FakeIdentity()
        val replacement = FakeIdentity()
        val hello = PokerBondBootstrapProtocol.createHello(dealer)
        val offer = PokerBondBootstrapProtocol.createOffer(
            poker,
            hello,
            PokerHotspotEndpoint("192.168.1.8", POKER_LISTENER_PORT),
        )
        val confirmation = PokerBondBootstrapProtocol.createConfirmation(dealer, hello, offer)

        assertFalse(
            PokerBondBootstrapProtocol.verifyConfirmation(
                hello,
                offer.copy(pokerPublicKey = replacement.publicKey),
                confirmation,
            ),
        )
    }

    @Test
    fun `bootstrap wire frames round trip without a pairing code`() {
        val dealer = FakeIdentity()
        val poker = FakeIdentity()
        val hello = PokerBondBootstrapProtocol.createHello(dealer)
        val offer = PokerBondBootstrapProtocol.createOffer(
            poker,
            hello,
            PokerHotspotEndpoint("192.168.1.8", POKER_LISTENER_PORT),
        )
        val confirmation = PokerBondBootstrapProtocol.createConfirmation(dealer, hello, offer)

        val decodedHello = roundTrip(hello, PokerBondBootstrapWire::writeHello, PokerBondBootstrapWire::readHello)
        assertEquals(hello.version, decodedHello.version)
        assertArrayEquals(hello.dealerPublicKey, decodedHello.dealerPublicKey)
        assertArrayEquals(hello.dealerNonce, decodedHello.dealerNonce)

        val decodedOffer = roundTrip(offer, PokerBondBootstrapWire::writeOffer, PokerBondBootstrapWire::readOffer)
        assertEquals(offer.version, decodedOffer.version)
        assertArrayEquals(offer.pokerPublicKey, decodedOffer.pokerPublicKey)
        assertArrayEquals(offer.pokerNonce, decodedOffer.pokerNonce)
        assertEquals(offer.endpoint, decodedOffer.endpoint)
        assertEquals(offer.capabilities, decodedOffer.capabilities)
        assertArrayEquals(offer.pokerSignature, decodedOffer.pokerSignature)

        val decodedConfirmation = roundTrip(
            confirmation,
            PokerBondBootstrapWire::writeConfirmation,
            PokerBondBootstrapWire::readConfirmation,
        )
        assertArrayEquals(confirmation.dealerSignature, decodedConfirmation.dealerSignature)
        assertEquals(
            PokerBondBootstrapAck(true),
            roundTrip(PokerBondBootstrapAck(true), PokerBondBootstrapWire::writeAck, PokerBondBootstrapWire::readAck),
        )
    }

    @Test
    fun `trusted Bluetooth bond provisions transport pins and bond removal revokes them`() {
        val dealerIdentity = FakeIdentity()
        val pokerIdentity = FakeIdentity()
        val dealerStore = MemoryStore()
        val pokerStore = MemoryStore()
        val dealer = PokerPairingController(PokerPairingRole.DEALER, dealerIdentity, dealerStore)
        val poker = PokerPairingController(PokerPairingRole.POKER, pokerIdentity, pokerStore)
        val endpoint = PokerHotspotEndpoint("192.168.1.8", POKER_LISTENER_PORT)

        dealer.provisionFromTrustedBond("rg-bond-id", pokerIdentity.publicKey, endpoint)
        poker.provisionFromTrustedBond("fold-bond-id", dealerIdentity.publicKey, endpoint)

        assertTrue(dealer.isPaired)
        assertTrue(poker.isPaired)
        assertEquals("rg-bond-id", dealer.bondedPeerId)
        assertEquals("fold-bond-id", poker.bondedPeerId)
        assertEquals(endpoint, dealer.endpoint)
        dealer.authenticatePeer(pokerIdentity.publicKey)
        poker.authenticatePeer(dealerIdentity.publicKey)

        assertTrue(dealer.revokeBondTrust("rg-bond-id"))
        assertFalse(dealer.isPaired)
        assertNull(dealer.endpoint)
        assertNull(dealer.bondedPeerId)
        assertFalse(dealer.revokeBondTrust("rg-bond-id"))
    }

    @Test
    fun `local app-key loss retains bonded peer identity for automatic reprovision`() {
        val dealerIdentity = FakeIdentity()
        val pokerIdentity = FakeIdentity()
        val store = MemoryStore()
        val endpoint = PokerHotspotEndpoint("192.168.1.8", POKER_LISTENER_PORT)
        PokerPairingController(PokerPairingRole.DEALER, dealerIdentity, store)
            .provisionFromTrustedBond("rg-bond-id", pokerIdentity.publicKey, endpoint)

        val replacementIdentity = FakeIdentity()
        val reloaded = PokerPairingController(PokerPairingRole.DEALER, replacementIdentity, store)

        assertFalse(reloaded.isPaired)
        assertEquals(PokerPairingFailure.KEYSTORE_INVALID, reloaded.status.failure)
        assertEquals("rg-bond-id", reloaded.bondedPeerId)
        reloaded.provisionFromTrustedBond("rg-bond-id", pokerIdentity.publicKey, endpoint)
        assertTrue(reloaded.isPaired)
        assertArrayEquals(replacementIdentity.publicKey, store.record?.dealerPublicKey)
    }

    private fun <T> roundTrip(
        value: T,
        writer: (java.io.OutputStream, T) -> Unit,
        reader: (java.io.InputStream) -> T,
    ): T {
        val output = ByteArrayOutputStream()
        writer(output, value)
        return reader(ByteArrayInputStream(output.toByteArray()))
    }

    private class FakeIdentity : PokerPairingIdentity {
        private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        override val publicKey: ByteArray get() = keyPair.public.encoded

        override fun sign(payload: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }
    }

    private class MemoryStore : PokerPairingStore {
        var record: PokerPairingRecord? = null
        override fun load(): PokerPairingRecord? = record
        override fun save(record: PokerPairingRecord) {
            this.record = record
        }
        override fun clear() {
            record = null
        }
    }
}
