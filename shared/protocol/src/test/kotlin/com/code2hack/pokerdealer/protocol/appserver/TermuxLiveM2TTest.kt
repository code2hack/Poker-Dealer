package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardState
import com.code2hack.pokerdealer.domain.CodexHost
import com.code2hack.pokerdealer.domain.DeliveryState
import com.code2hack.pokerdealer.domain.HostConnectionRoute
import com.code2hack.pokerdealer.domain.InitialCodexHosts
import com.code2hack.pokerdealer.protocol.host.HostSshSession
import com.code2hack.pokerdealer.protocol.host.HostTcpDialer
import com.code2hack.pokerdealer.protocol.host.JschHostSshClient
import com.code2hack.pokerdealer.protocol.host.RouteEndpoint
import com.code2hack.pokerdealer.protocol.host.SocketHostTcpDialer
import com.code2hack.pokerdealer.protocol.host.SshHostAuthentication
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path

class TermuxLiveM2TTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "POKER_DEALER_LIVE_TERMUX", matches = "true")
    fun `Termux completes the live loopback app-server slice`() = runBlocking {
        val host = InitialCodexHosts.fold6Termux
        val rendered = linkedMapOf<String, Card>()
        val clientId = "dealer-fold6-termux-m2t-${System.currentTimeMillis()}"
        val dialer = liveDialer(host)
        val sshClient = liveSshClient(host)
        val slice = M1OneHostDealerSlice(
            host = host,
            dialer = dialer,
            sshClient = sshClient,
            daemon = TermuxCommunityCodexDaemon(),
        )

        val result = withTimeout(300_000) {
            slice.run(
                M1TurnInput(
                    text = "Reply with exactly DEALER_TERMUX_LIVE_OK.",
                    threadId = System.getenv("POKER_DEALER_LIVE_TERMUX_THREAD_ID")?.takeIf(String::isNotBlank),
                    clientUserMessageId = clientId,
                ),
                onCard = { rendered[it.id] = it },
            )
        }

        assertEquals(host.id, result.host.id)
        assertEquals(HostConnectionRoute.SSH_LOOPBACK, result.route)
        assertEquals(HostConnectionRoute.SSH_LOOPBACK, result.reconnectRoute)
        assertEquals("${host.id}/${result.threadId}", result.conversationId)
        assertTrue(result.historyCards.isNotEmpty())
        assertTrue(result.streamedCards.isNotEmpty())
        assertTrue(result.streamedCards.all { it.state == CardState.COMMITTED })
        assertEquals(DeliveryState.DELIVERED, result.userCard.delivery)
        assertEquals(result.userCard, rendered[clientId])
        assertEquals(1, result.matchingUserMessagesAfterReconnect)
        assertTrue(
            result.routeDiagnostics
                .filter { it.attempted }
                .all { it.route == HostConnectionRoute.SSH_LOOPBACK },
        )

        withTimeout(300_000) {
            probeUnsupportedServerRequest(host, dialer, sshClient, result.threadId)
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "POKER_DEALER_LIVE_TERMUX", matches = "true")
    fun `Termux recovers a real proxy EOF after bounded loopback backoff without replay`() = runBlocking {
        val host = InitialCodexHosts.fold6Termux
        val rendered = linkedMapOf<String, Card>()
        val recovery = mutableListOf<M1RecoveryUpdate>()
        val clientId = "dealer-fold6-termux-recovery-${System.currentTimeMillis()}"
        val dialer = FaultingReconnectDialer(liveDialer(host))
        val sshClient = liveSshClient(host)
        var appServerConnections = 0
        val slice = M1OneHostDealerSlice(
            host = host,
            dialer = dialer,
            sshClient = sshClient,
            daemon = TermuxCommunityCodexDaemon(),
            appServerFactory = { proxy ->
                val socket = AppServerWebSocket(proxy)
                socket.open()
                val peer = WebSocketJsonRpcPeer(socket)
                appServerConnections += 1
                CodexAppServerSession(
                    if (appServerConnections == 1) DisconnectOnAgentDeltaPeer(peer) else peer,
                )
            },
        )

        val result = withTimeout(300_000) {
            slice.run(
                M1TurnInput(
                    text = "Reply with exactly DEALER_TERMUX_RECOVERY_OK.",
                    threadId = System.getenv("POKER_DEALER_LIVE_TERMUX_THREAD_ID")?.takeIf(String::isNotBlank),
                    clientUserMessageId = clientId,
                ),
                onCard = { rendered[it.id] = it },
                onRecovery = recovery::add,
            )
        }

        assertTrue(result.recoveredAfterDisconnect)
        assertEquals(1, result.matchingUserMessagesAfterReconnect)
        assertEquals(DeliveryState.DELIVERED, result.userCard.delivery)
        assertEquals(result.userCard, rendered[clientId])
        assertEquals(M1FailurePhase.TCP_CONNECT, recovery.single().failurePhase)
        assertEquals(3, dialer.connectCalls)
        assertEquals(2, appServerConnections)
        println("TERMUX_PROXY_RECOVERY=RECOVERED delivery=${result.userCard.delivery} matches=1")
    }

    private fun liveDialer(host: CodexHost) = SocketHostTcpDialer(
        mapOf(
            (host.id to HostConnectionRoute.SSH_LOOPBACK) to RouteEndpoint(
                requireEnv("POKER_DEALER_LIVE_TERMUX_HOST"),
                requireEnv("POKER_DEALER_LIVE_TERMUX_PORT").toInt(),
            ),
        ),
    )

    private fun liveSshClient(host: CodexHost) = JschHostSshClient(
        mapOf(
            host.id to SshHostAuthentication(
                username = requireEnv("POKER_DEALER_LIVE_TERMUX_SSH_USER"),
                privateKey = Files.readAllBytes(
                    Path.of(requireEnv("POKER_DEALER_LIVE_TERMUX_SSH_PRIVATE_KEY")),
                ),
                knownHosts = Files.readAllBytes(
                    Path.of(requireEnv("POKER_DEALER_LIVE_TERMUX_KNOWN_HOSTS")),
                ),
            ),
        ),
    )

    private suspend fun probeUnsupportedServerRequest(
        host: CodexHost,
        dialer: SocketHostTcpDialer,
        sshClient: JschHostSshClient,
        threadId: String,
    ) {
        val rejectedMethods = mutableListOf<String>()
        val tcp = dialer.connect(host, HostConnectionRoute.SSH_LOOPBACK, port = 22)
        var ssh: HostSshSession? = null
        var session: CodexAppServerSession? = null
        try {
            val connectedSsh = sshClient.connect(host, tcp)
            ssh = connectedSsh
            val daemon = TermuxCommunityCodexDaemon()
            daemon.ensureRunning(connectedSsh)
            val socket = AppServerWebSocket(connectedSsh.execStream(daemon.appServerProxyCommand))
            socket.open()
            val connectedPeer = WebSocketJsonRpcPeer(
                socket,
                onRejectedServerRequest = rejectedMethods::add,
            )
            val connectedSession = CodexAppServerSession(connectedPeer)
            session = connectedSession
            connectedSession.initialize()
            connectedSession.threadResume(threadId)
            val turnStart = connectedPeer.request(
                "turn/start",
                liveTurnStartParams(
                    threadId = threadId,
                    clientId = "dealer-fold6-termux-rejection-${System.currentTimeMillis()}",
                    approvalPolicy = "untrusted",
                    text = "Attempt exactly one shell command: " +
                        "`sh -c 'printf APPROVAL_REQUEST_PROBE'`. " +
                        "If it is denied, do not run another command. " +
                        "Then reply exactly DEALER_TERMUX_REJECTION_OK.",
                ),
            ).jsonObject
            val cards = connectedSession.streamAgentCards(
                threadId = threadId,
                turnId = turnStart.liveTurnId(),
                conversationId = "${host.id}/$threadId",
                firstSequence = 1,
            )

            assertEquals(
                1,
                rejectedMethods.count { it == "item/commandExecution/requestApproval" },
            )
            assertTrue(cards.all { it.state == CardState.COMMITTED })
            assertTrue(cards.any { "DEALER_TERMUX_REJECTION_OK" in it.fullText })
            val restoreStart = connectedPeer.request(
                "turn/start",
                liveTurnStartParams(
                    threadId = threadId,
                    clientId = "dealer-fold6-termux-restore-${System.currentTimeMillis()}",
                    approvalPolicy = "never",
                    text = "Reply exactly DEALER_TERMUX_POLICY_RESTORED.",
                ),
            ).jsonObject
            val restoreCards = connectedSession.streamAgentCards(
                threadId = threadId,
                turnId = restoreStart.liveTurnId(),
                conversationId = "${host.id}/$threadId",
                firstSequence = cards.size.toLong() + 1,
            )
            assertTrue(restoreCards.any { "DEALER_TERMUX_POLICY_RESTORED" in it.fullText })
            val restored = connectedSession.threadResume(threadId)
            assertEquals("never", restored["approvalPolicy"]?.jsonPrimitive?.content)
        } finally {
            withContext(NonCancellable) {
                runCatching { session?.close() }
                runCatching { ssh?.close() }
                runCatching { tcp.close() }
            }
        }
    }

    private fun liveTurnStartParams(
        threadId: String,
        clientId: String,
        approvalPolicy: String,
        text: String,
    ): JsonObject = buildJsonObject {
        put("threadId", JsonPrimitive(threadId))
        put("clientUserMessageId", JsonPrimitive(clientId))
        put("approvalPolicy", JsonPrimitive(approvalPolicy))
        put("approvalsReviewer", JsonPrimitive("user"))
        put(
            "input",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(text))
                    },
                )
            },
        )
    }

    private fun JsonObject.liveTurnId(): String =
        this["turn"]
            ?.jsonObject
            ?.get("id")
            ?.jsonPrimitive
            ?.content
            ?: error("live turn/start response did not include a turn ID")

    private fun requireEnv(name: String): String =
        System.getenv(name)?.takeIf(String::isNotBlank) ?: error("$name is required")
}

private class DisconnectOnAgentDeltaPeer(
    private val delegate: JsonRpcPeer,
) : JsonRpcPeer {
    override suspend fun request(method: String, params: JsonElement): JsonElement =
        delegate.request(method, params)

    override suspend fun notify(method: String, params: JsonElement?) =
        delegate.notify(method, params)

    override suspend fun receiveNotification(): AppServerNotification? {
        val notification = delegate.receiveNotification()
        if (notification?.method == "item/agentMessage/delta") {
            delegate.close()
            error("Injected proxy EOF after the first agent delta")
        }
        return notification
    }

    override suspend fun close() = delegate.close()
}

private class FaultingReconnectDialer(
    private val delegate: HostTcpDialer,
) : HostTcpDialer {
    var connectCalls = 0

    override fun capability(host: CodexHost, route: HostConnectionRoute) =
        delegate.capability(host, route)

    override suspend fun connect(
        host: CodexHost,
        route: HostConnectionRoute,
        port: Int,
    ) = when (++connectCalls) {
        2 -> error("Injected loopback sshd interruption")
        3 -> {
            delay(8_000)
            delegate.connect(host, route, port)
        }
        else -> delegate.connect(host, route, port)
    }
}
