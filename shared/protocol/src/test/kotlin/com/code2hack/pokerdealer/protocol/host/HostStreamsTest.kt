package com.code2hack.pokerdealer.protocol.host

import com.code2hack.pokerdealer.domain.HostConnectionRoute
import com.code2hack.pokerdealer.domain.InitialCodexHosts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.ServerSocket

class HostStreamsTest {
    @Test
    fun `cancelling a blocked socket read closes the socket`() = runBlocking {
        ServerSocket(0).use { server ->
            val accepted = async(Dispatchers.IO) { server.accept() }
            val stream = SocketHostTcpDialer(
                endpoints = mapOf(
                    (InitialCodexHosts.u4090.id to HostConnectionRoute.SSH_LAN) to
                        RouteEndpoint("127.0.0.1", server.localPort),
                ),
            ).connect(InitialCodexHosts.u4090, HostConnectionRoute.SSH_LAN, 22)
            val serverPeer = accepted.await()
            try {
                val readJob = async { stream.read(ByteArray(1)) }
                delay(25)

                withTimeout(1_000) {
                    readJob.cancelAndJoin()
                }

                val peerRead = async(Dispatchers.IO) { serverPeer.getInputStream().read() }
                assertEquals(-1, withTimeout(1_000) { peerRead.await() })
            } finally {
                serverPeer.close()
                stream.close()
            }
        }
    }
}
