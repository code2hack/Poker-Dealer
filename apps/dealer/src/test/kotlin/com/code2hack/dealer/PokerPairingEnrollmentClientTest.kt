package com.code2hack.dealer

import com.code2hack.pokerdealer.protocol.PokerHotspotEndpoint
import com.code2hack.pokerdealer.protocol.PokerPairingController
import com.code2hack.pokerdealer.protocol.PokerPairingIdentity
import com.code2hack.pokerdealer.protocol.PokerPairingRole
import com.code2hack.pokerdealer.protocol.PokerPairingStore
import com.code2hack.pokerdealer.protocol.PokerPairingWire
import com.code2hack.pokerdealer.protocol.PokerPairingRecord
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PokerPairingEnrollmentClientTest {
    @Test
    fun `dealer enrollment client completes PAKE and persists authenticated endpoint`() {
        val pokerIdentity = FakeIdentity()
        val dealerIdentity = FakeIdentity()
        val poker = controller(PokerPairingRole.POKER, pokerIdentity)
        val dealer = controller(PokerPairingRole.DEALER, dealerIdentity)
        val enrollment = poker.openEnrollment(
            nowMs = System.currentTimeMillis(),
            physicalEnrollmentConfirmed = true,
        )
        var endpointPort = 0
        ServerSocket(0).use { server ->
            endpointPort = server.localPort
            val endpoint = PokerHotspotEndpoint("127.0.0.1", endpointPort)
            val serverJob = thread {
                server.accept().use { socket ->
                    PokerPairingWire.write(
                        socket.getOutputStream(),
                        PokerPairingWire.challenge(enrollment.challenge),
                    )
                    val response = PokerPairingWire.decodeResponse(
                        checkNotNull(PokerPairingWire.read(socket.getInputStream())),
                    )
                    val confirmation = poker.acceptEnrollment(response, System.currentTimeMillis())
                    PokerPairingWire.write(
                        socket.getOutputStream(),
                        PokerPairingWire.confirmation(confirmation),
                    )
                }
            }

            val peer = kotlinx.coroutines.runBlocking {
                PokerPairingEnrollmentClient(dealer).enroll(endpoint, enrollment.displayCode)
            }
            dealer.updateEndpoint(peer, endpoint)
            serverJob.join()
        }

        assertEquals(endpointPort, dealer.endpoint!!.port)
        assertTrue(dealer.isPaired)
        assertTrue(poker.isPaired)
    }

    private fun controller(role: PokerPairingRole, identity: FakeIdentity) = PokerPairingController(
        role = role,
        identity = identity,
        store = MemoryStore(),
    )

    private class FakeIdentity : PokerPairingIdentity {
        private val keyPair = KeyPairGenerator.getInstance("EC").generateKeyPair()
        override val publicKey: ByteArray get() = keyPair.public.encoded
        override fun sign(payload: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }
    }

    private class MemoryStore : PokerPairingStore {
        private var record: PokerPairingRecord? = null
        override fun load(): PokerPairingRecord? = record
        override fun save(record: PokerPairingRecord) { this.record = record }
        override fun clear() { record = null }
    }
}
