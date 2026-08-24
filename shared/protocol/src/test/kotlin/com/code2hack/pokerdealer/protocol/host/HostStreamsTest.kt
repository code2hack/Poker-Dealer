package com.code2hack.pokerdealer.protocol.host

import com.code2hack.pokerdealer.domain.CodexDistribution
import com.code2hack.pokerdealer.domain.CodexHost
import com.code2hack.pokerdealer.domain.CodexHostKind
import com.code2hack.pokerdealer.domain.HostArchitecture
import com.code2hack.pokerdealer.domain.HostAvailabilityClass
import com.code2hack.pokerdealer.domain.HostConnectionRoute
import com.code2hack.pokerdealer.domain.HostConnectionState
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
        val host = CodexHost(
            id = "host",
            displayName = "Host",
            kind = CodexHostKind.LINUX_WORKSTATION,
            architecture = HostArchitecture.LINUX_X86_64,
            distribution = CodexDistribution.OPENAI_UPSTREAM,
            connectionRoutes = listOf(HostConnectionRoute.SSH_LAN),
            availabilityClass = HostAvailabilityClass.PERSISTENT,
            connectionState = HostConnectionState.DISCONNECTED,
        )
        ServerSocket(0).use { server ->
            val accepted = async(Dispatchers.IO) { server.accept() }
            val stream = SocketHostTcpDialer(
                endpoints = mapOf(
                    (host.id to HostConnectionRoute.SSH_LAN) to
                        RouteEndpoint("127.0.0.1", server.localPort),
                ),
            ).connect(host, HostConnectionRoute.SSH_LAN, 22)
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
