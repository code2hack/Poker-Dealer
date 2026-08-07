package com.code2hack.poker

import com.code2hack.pokerdealer.protocol.PokerHotspotEndpoint
import com.code2hack.pokerdealer.protocol.PokerPairingController
import com.code2hack.pokerdealer.protocol.PokerPairingIdentity
import com.code2hack.pokerdealer.protocol.PokerPairingRecord
import com.code2hack.pokerdealer.protocol.PokerPairingRole
import com.code2hack.pokerdealer.protocol.PokerPairingStore
import com.code2hack.pokerdealer.protocol.PokerPairingWire
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PokerEnrollmentServerTest {
    @Test
    fun `successful enrollment closes port before authenticated listener handoff`() {
        val poker = controller(PokerPairingRole.POKER)
        val dealer = controller(PokerPairingRole.DEALER)
        val enrollment = poker.openEnrollment(
            nowMs = System.currentTimeMillis(),
            physicalEnrollmentConfirmed = true,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val completed = CountDownLatch(1)
        val rebound = AtomicBoolean(false)
        val port = ServerSocket(0).use { it.localPort }
        val server = PokerEnrollmentServer(
            addressProvider = { InetAddress.getLoopbackAddress() as Inet4Address },
            enrollment = enrollment,
            pairing = poker,
            scope = scope,
            nowMs = System::currentTimeMillis,
            onFailure = { _, _ -> },
            onComplete = {
                try {
                    ServerSocket().use { probe ->
                        probe.reuseAddress = true
                        probe.bind(InetSocketAddress("127.0.0.1", port))
                    }
                    rebound.set(true)
                } finally {
                    completed.countDown()
                }
            },
            port = port,
        )

        try {
            server.start()
            val endpoint = PokerHotspotEndpoint("127.0.0.1", port)
            val client = thread {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(endpoint.host, endpoint.port), 5_000)
                    val challenge = PokerPairingWire.decodeChallenge(
                        checkNotNull(PokerPairingWire.read(socket.getInputStream())),
                    )
                    val response = dealer.respondToEnrollment(
                        challenge,
                        enrollment.displayCode,
                        System.currentTimeMillis(),
                    )
                    PokerPairingWire.write(socket.getOutputStream(), PokerPairingWire.response(response))
                    val confirmation = PokerPairingWire.decodeConfirmation(
                        checkNotNull(PokerPairingWire.read(socket.getInputStream())),
                    )
                    dealer.confirmDealerPairing(confirmation)
                }
            }
            client.join()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertTrue(rebound.get())
        } finally {
            server.stop()
            scope.cancel()
        }

        assertTrue(poker.isPaired)
        assertTrue(dealer.isPaired)
        assertEquals(null, poker.endpoint)
    }

    private fun controller(role: PokerPairingRole) = PokerPairingController(
        role = role,
        identity = FakeIdentity(),
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
