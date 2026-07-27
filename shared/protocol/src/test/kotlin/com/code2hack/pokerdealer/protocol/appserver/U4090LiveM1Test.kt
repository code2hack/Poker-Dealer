package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardState
import com.code2hack.pokerdealer.domain.HostConnectionRoute
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

class U4090LiveM1Test {
    @Test
    @EnabledIfEnvironmentVariable(named = "POKER_DEALER_LIVE_U4090", matches = "true")
    fun `u4090 completes the live LAN app-server slice`() = runBlocking {
        val threadId = requireEnv("POKER_DEALER_LIVE_THREAD_ID")
        val privateKey = Files.readAllBytes(Path.of(requireEnv("POKER_DEALER_LIVE_SSH_PRIVATE_KEY")))
        val knownHosts = Files.readAllBytes(Path.of(requireEnv("POKER_DEALER_LIVE_KNOWN_HOSTS")))
        val rendered = linkedMapOf<String, Card>()
        val clientId = "dealer-u4090-m1-${System.currentTimeMillis()}"
        val slice = M1OneHostDealerSlice(
            dialer = SocketHostTcpDialer(
                mapOf(
                    ("u4090" to HostConnectionRoute.SSH_LAN) to
                        RouteEndpoint(requireEnv("POKER_DEALER_LIVE_LAN_HOST")),
                ),
            ),
            sshClient = JschHostSshClient(
                mapOf(
                    "u4090" to SshHostAuthentication(
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
        assertEquals("u4090/$threadId", result.conversationId)
        assertTrue(result.historyCards.isNotEmpty())
        assertTrue(result.streamedCards.all { it.state == CardState.COMMITTED })
        assertTrue(result.streamedCards.isNotEmpty())
        assertEquals(1, result.matchingUserMessagesAfterReconnect)
        assertTrue(rendered.values.containsAll(result.streamedCards))
    }

    private fun requireEnv(name: String): String =
        System.getenv(name)?.takeIf(String::isNotBlank) ?: error("$name is required")
}
