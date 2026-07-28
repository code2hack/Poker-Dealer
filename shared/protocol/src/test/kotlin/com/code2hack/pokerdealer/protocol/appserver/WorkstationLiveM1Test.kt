package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardState
import com.code2hack.pokerdealer.domain.DeliveryState
import com.code2hack.pokerdealer.domain.HostConnectionRoute
import com.code2hack.pokerdealer.domain.InitialCodexHosts
import com.code2hack.pokerdealer.protocol.host.JschHostSshClient
import com.code2hack.pokerdealer.protocol.host.RouteEndpoint
import com.code2hack.pokerdealer.protocol.host.SocketHostTcpDialer
import com.code2hack.pokerdealer.protocol.host.SshHostAuthentication
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class WorkstationLiveM1Test {
    @Test
    @EnabledIfEnvironmentVariable(named = "POKER_DEALER_LIVE_WORKSTATION", matches = "true")
    fun `workstation completes the live LAN app-server slice`() = runBlocking {
        val host = liveHost()
        val threadId = requireEnv("POKER_DEALER_LIVE_THREAD_ID")
        val privateKey = Files.readAllBytes(Path.of(requireEnv("POKER_DEALER_LIVE_SSH_PRIVATE_KEY")))
        val knownHosts = Files.readAllBytes(Path.of(requireEnv("POKER_DEALER_LIVE_KNOWN_HOSTS")))
        val rendered = linkedMapOf<String, Card>()
        val clientId = "dealer-${host.id}-m2-${System.currentTimeMillis()}"
        val slice = M1OneHostDealerSlice(
            host = host,
            dialer = SocketHostTcpDialer(
                mapOf(
                    (host.id to HostConnectionRoute.SSH_LAN) to
                        RouteEndpoint(requireEnv("POKER_DEALER_LIVE_LAN_HOST")),
                ),
            ),
            sshClient = JschHostSshClient(
                mapOf(
                    host.id to SshHostAuthentication(
                        username = requireEnv("POKER_DEALER_LIVE_SSH_USER"),
                        privateKey = privateKey,
                        knownHosts = knownHosts,
                    ),
                ),
            ),
        )

        val result = withTimeout(300_000) {
            slice.run(
                M1TurnInput(
                    text = "Reply with exactly DEALER_M1_OK.",
                    threadId = threadId,
                    clientUserMessageId = clientId,
                ),
                onCard = { rendered[it.id] = it },
            )
        }

        assertEquals(HostConnectionRoute.SSH_LAN, result.route)
        assertEquals(HostConnectionRoute.SSH_LAN, result.reconnectRoute)
        assertEquals("${host.id}/$threadId", result.conversationId)
        assertTrue(result.historyCards.isNotEmpty())
        assertTrue(result.streamedCards.all { it.state == CardState.COMMITTED })
        assertTrue(result.streamedCards.isNotEmpty())
        assertEquals(clientId, result.userCard.id)
        assertEquals(DeliveryState.DELIVERED, result.userCard.delivery)
        assertEquals(result.userCard, rendered[clientId])
        assertEquals(1, result.matchingUserMessagesAfterReconnect)
        assertTrue(rendered.values.containsAll(result.streamedCards))
        assertTrue(
            result.routeDiagnostics
                .filter { it.attempted }
                .all { it.route == HostConnectionRoute.SSH_LAN },
        )
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "POKER_DEALER_LIVE_WORKSTATION_INTERRUPT", matches = "true")
    fun `workstation reconciles one user message after a controlled post-accept disconnect`() = runBlocking {
        val host = liveHost()
        val threadId = requireEnv("POKER_DEALER_LIVE_THREAD_ID")
        val clientId = "dealer-${host.id}-interrupt-${System.currentTimeMillis()}"
        val rendered = linkedMapOf<String, Card>()
        var connectionCount = 0
        val turnStartRequests = AtomicInteger()
        val slice = M1OneHostDealerSlice(
            host = host,
            dialer = liveDialer(),
            sshClient = liveSshClient(),
            appServerFactory = { proxy ->
                val socket = AppServerWebSocket(proxy)
                socket.open()
                val peer = WebSocketJsonRpcPeer(socket)
                CodexAppServerSession(
                    TrackedTurnStartPeer(
                        delegate = peer,
                        requests = turnStartRequests,
                        disconnectAfterResponse = connectionCount++ == 0,
                    ),
                )
            },
        )

        val outcome = runCatching {
            withTimeout(300_000) {
                slice.run(
                    M1TurnInput(
                        text = "Reply with exactly DEALER_INTERRUPT_OK.",
                        threadId = threadId,
                        clientUserMessageId = clientId,
                    ),
                    onCard = { rendered[it.id] = it },
                )
            }
        }

        outcome.getOrNull()?.let {
            assertTrue(it.recoveredAfterDisconnect)
            assertEquals(1, it.matchingUserMessagesAfterReconnect)
        } ?: assertTrue(outcome.exceptionOrNull()?.message.orEmpty().contains("reconnect found 1 matching user message"))
        assertEquals(1, turnStartRequests.get())
        assertEquals(DeliveryState.DELIVERED, rendered[clientId]?.delivery)
    }

    private fun liveDialer() = SocketHostTcpDialer(
        mapOf(
            (liveHost().id to HostConnectionRoute.SSH_LAN) to
                RouteEndpoint(requireEnv("POKER_DEALER_LIVE_LAN_HOST")),
        ),
    )

    private fun liveSshClient() = JschHostSshClient(
        mapOf(
            liveHost().id to SshHostAuthentication(
                username = requireEnv("POKER_DEALER_LIVE_SSH_USER"),
                privateKey = Files.readAllBytes(Path.of(requireEnv("POKER_DEALER_LIVE_SSH_PRIVATE_KEY"))),
                knownHosts = Files.readAllBytes(Path.of(requireEnv("POKER_DEALER_LIVE_KNOWN_HOSTS"))),
            ),
        ),
    )

    private fun liveHost() =
        InitialCodexHosts.workstations.firstOrNull { it.id == requireEnv("POKER_DEALER_LIVE_HOST_ID") }
            ?: error("POKER_DEALER_LIVE_HOST_ID must be spark or u4090")

    private fun requireEnv(name: String): String =
        System.getenv(name)?.takeIf(String::isNotBlank) ?: error("$name is required")
}

private class TrackedTurnStartPeer(
    private val delegate: JsonRpcPeer,
    private val requests: AtomicInteger,
    private val disconnectAfterResponse: Boolean,
) : JsonRpcPeer by delegate {
    override suspend fun request(
        method: String,
        params: kotlinx.serialization.json.JsonElement,
    ): kotlinx.serialization.json.JsonElement {
        if (method == "turn/start") requests.incrementAndGet()
        val result = delegate.request(method, params)
        if (method == "turn/start" && disconnectAfterResponse) {
            delegate.close()
        }
        return result
    }
}
