package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.CardRole
import com.code2hack.pokerdealer.domain.HostConnectionRoute
import com.code2hack.pokerdealer.domain.InitialCodexHosts
import com.code2hack.pokerdealer.protocol.host.CommandResult
import com.code2hack.pokerdealer.protocol.host.DuplexByteStream
import com.code2hack.pokerdealer.protocol.host.HostSshClient
import com.code2hack.pokerdealer.protocol.host.HostSshSession
import com.code2hack.pokerdealer.protocol.host.HostTcpDialer
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodexAppServerM1Test {
    @Test
    fun `u4090 M1 slice uses LAN route, app-server methods, streaming, and reconnect dedupe`() = runTest {
        val firstPeer = FixtureJsonRpcPeer(
            exchanges = listOf(
                fixture("initialize-request.json", "initialize-response.json"),
                fixture("thread-list-request.json", "thread-list-response.json"),
                fixture("thread-resume-request.json", "thread-resume-response.json"),
                fixture("thread-read-request.json", "thread-read-response-before.json"),
                fixture("turn-start-request.json", "turn-start-response.json"),
            ),
            notifications = listOf(
                "agent-delta-notification-1.json",
                "agent-delta-notification-2.json",
                "turn-completed-notification.json",
            ),
        )
        val secondPeer = FixtureJsonRpcPeer(
            exchanges = listOf(
                fixture("initialize-request.json", "initialize-response.json"),
                fixture("thread-resume-request.json", "thread-resume-response.json"),
                fixture("thread-read-request.json", "thread-read-response-after.json"),
            ),
        )
        val peers = ArrayDeque(listOf(firstPeer, secondPeer))
        val dialer = RecordingDialer()
        val sshClient = RecordingSshClient()
        val daemon = UpstreamCodexDaemon()
        val renderedCards = mutableListOf<com.code2hack.pokerdealer.domain.Card>()
        val slice = M1OneHostDealerSlice(
            dialer = dialer,
            sshClient = sshClient,
            daemon = daemon,
            appServerFactory = { CodexAppServerSession(peers.removeFirst(), nowMs = { 1_784_600_000_000 }) },
        )

        val result = slice.run(
            M1TurnInput(
                text = "Dealer M1 proof from u4090",
                clientUserMessageId = "dealer-client-u4090-m1",
            ),
            onCard = renderedCards::add,
        )

        assertEquals(InitialCodexHosts.u4090.id, result.host.id)
        assertEquals(HostConnectionRoute.SSH_LAN, result.route)
        assertEquals(HostConnectionRoute.SSH_LAN, result.reconnectRoute)
        assertEquals(listOf(22, 22), dialer.ports)
        assertEquals(listOf(HostConnectionRoute.SSH_LAN, HostConnectionRoute.SSH_LAN), dialer.routes)
        assertEquals("running", result.daemonVersions.status)
        assertEquals("0.145.0", result.daemonVersions.appServerVersion)
        assertEquals("thr_u4090_m1", result.threadId)
        assertEquals("u4090/thr_u4090_m1", result.conversationId)
        assertTrue((result.historyCards + result.streamedCards).all { it.conversationId == result.conversationId })
        assertEquals(listOf("Existing prompt", "Existing answer"), result.historyCards.map { it.fullText })
        assertEquals(CardRole.AGENT, result.streamedCards.single().role)
        assertEquals("streamed answer", result.streamedCards.single().fullText)
        assertEquals(3, result.streamedCards.single().revision)
        assertEquals(1, result.matchingUserMessagesAfterReconnect)
        assertEquals(false, result.recoveredAfterDisconnect)
        assertEquals(
            listOf("Existing prompt", "Existing answer", "streamed ", "streamed answer", "streamed answer"),
            renderedCards.map { it.fullText },
        )
        assertEquals(
            listOf("initialize", "thread/list", "thread/resume", "thread/read", "turn/start"),
            firstPeer.requests,
        )
        assertEquals(listOf("initialized"), firstPeer.notifications)
        assertEquals(listOf("initialize", "thread/resume", "thread/read"), secondPeer.requests)
        assertTrue(sshClient.sessions.all { daemon.appServerProxyCommand in it.streamCommands })
        assertTrue(sshClient.sessions.all { daemon.daemonVersionCommand in it.execCommands })
    }

    @Test
    fun `disconnect after turn start recovers completed state and never replays turn start`() = runTest {
        val firstPeer = FixtureJsonRpcPeer(
            exchanges = listOf(
                fixture("initialize-request.json", "initialize-response.json"),
                fixture("thread-list-request.json", "thread-list-response.json"),
                fixture("thread-resume-request.json", "thread-resume-response.json"),
                fixture("thread-read-request.json", "thread-read-response-before.json"),
                fixture("turn-start-request.json", "turn-start-response.json"),
            ),
            notifications = listOf("agent-delta-notification-1.json"),
        )
        val secondPeer = FixtureJsonRpcPeer(
            exchanges = listOf(
                fixture("initialize-request.json", "initialize-response.json"),
                fixture("thread-resume-request.json", "thread-resume-response.json"),
                fixture("thread-read-request.json", "thread-read-response-after.json"),
            ),
        )
        val peers = ArrayDeque(listOf(firstPeer, secondPeer))
        val slice = M1OneHostDealerSlice(
            dialer = RecordingDialer(),
            sshClient = RecordingSshClient(),
            appServerFactory = { CodexAppServerSession(peers.removeFirst()) },
        )

        val result = slice.run(
            M1TurnInput(
                text = "Dealer M1 proof from u4090",
                clientUserMessageId = "dealer-client-u4090-m1",
            ),
        )

        assertEquals("streamed answer", result.streamedCards.single().fullText)
        assertEquals(2, result.streamedCards.single().revision)
        assertEquals(1, result.matchingUserMessagesAfterReconnect)
        assertEquals(true, result.recoveredAfterDisconnect)
        assertEquals(1, firstPeer.requests.count { it == "turn/start" })
        assertEquals(0, secondPeer.requests.count { it == "turn/start" })
    }

    @Test
    fun `cancellation closes app-server, SSH, and TCP resources`() = runTest {
        val peer = FixtureJsonRpcPeer(
            exchanges = listOf(
                fixture("initialize-request.json", "initialize-response.json"),
                fixture("thread-list-request.json", "thread-list-response.json"),
                fixture("thread-resume-request.json", "thread-resume-response.json"),
                fixture("thread-read-request.json", "thread-read-response-before.json"),
                fixture("turn-start-request.json", "turn-start-response.json"),
            ),
            waitForNotifications = true,
        )
        val dialer = RecordingDialer()
        val sshClient = RecordingSshClient()
        val slice = M1OneHostDealerSlice(
            dialer = dialer,
            sshClient = sshClient,
            appServerFactory = { CodexAppServerSession(peer) },
        )

        val job = launch {
            slice.run(
                M1TurnInput(
                    text = "Dealer M1 proof from u4090",
                    clientUserMessageId = "dealer-client-u4090-m1",
                ),
            )
        }
        yield()
        job.cancelAndJoin()

        assertTrue(peer.closed)
        assertTrue(sshClient.sessions.single().closed)
        assertTrue(dialer.streams.single().closed)
    }

    @Test
    fun `daemon ensure starts upstream daemon when status is stopped`() = runTest {
        val session = ScriptedSshSession(
            CommandResult(0, """{"status":"stopped"}"""),
            CommandResult(0, ""),
            CommandResult(0, """{"status":"running","cliVersion":"codex-cli 0.145.0","appServerVersion":"0.145.0"}"""),
        )
        val daemon = UpstreamCodexDaemon()

        val versions = daemon.ensureRunning(session)

        assertEquals("0.145.0", versions.appServerVersion)
        assertEquals(
            listOf(
                daemon.daemonVersionCommand,
                daemon.daemonStartCommand,
                daemon.daemonVersionCommand,
            ),
            session.execCommands,
        )
    }

    private fun fixture(request: String, response: String) = FixtureExchange(request, response)
}

private data class FixtureExchange(
    val requestFixture: String,
    val responseFixture: String,
)

private class FixtureJsonRpcPeer(
    exchanges: List<FixtureExchange>,
    notifications: List<String> = emptyList(),
    clientNotifications: List<String> = listOf("initialized-notification.json"),
    private val waitForNotifications: Boolean = false,
) : JsonRpcPeer {
    private val exchanges = ArrayDeque(exchanges)
    private val notificationFixtures = ArrayDeque(notifications)
    private val clientNotificationFixtures = ArrayDeque(clientNotifications)
    val requests = mutableListOf<String>()
    val notifications = mutableListOf<String>()
    var closed = false

    override suspend fun request(method: String, params: JsonElement): JsonElement {
        val exchange = exchanges.removeFirst()
        val expected = loadFixture(exchange.requestFixture).jsonObject
        assertEquals(expected["method"]?.toString()?.trim('"'), method)
        assertEquals(expected["params"], params)
        requests += method
        return loadFixture(exchange.responseFixture).jsonObject["result"] ?: JsonObject(emptyMap())
    }

    override suspend fun notify(method: String, params: JsonElement?) {
        val expected = loadFixture(clientNotificationFixtures.removeFirst()).jsonObject
        assertEquals((expected["method"] as? JsonPrimitive)?.contentOrNull, method)
        assertEquals(expected["params"], params)
        notifications += method
    }

    override suspend fun receiveNotification(): AppServerNotification? {
        if (notificationFixtures.isEmpty()) {
            if (waitForNotifications) awaitCancellation()
            return null
        }
        val message = loadFixture(notificationFixtures.removeFirst()).jsonObject
        return AppServerNotification(
            method = (message["method"] as? JsonPrimitive)?.contentOrNull
                ?: error("fixture notification missing method"),
            params = message["params"] ?: JsonObject(emptyMap()),
            raw = message,
        )
    }

    override suspend fun close() {
        closed = true
    }
}

private fun loadFixture(name: String): JsonElement {
    val text = object {}.javaClass.getResource("/app-server/v2/$name")?.readText()
        ?: error("Missing fixture $name")
    return AppServerJson.parseToJsonElement(text)
}

private class RecordingDialer : HostTcpDialer {
    val routes = mutableListOf<HostConnectionRoute>()
    val ports = mutableListOf<Int>()
    val streams = mutableListOf<NoopStream>()

    override suspend fun connect(
        host: com.code2hack.pokerdealer.domain.CodexHost,
        route: HostConnectionRoute,
        port: Int,
    ): DuplexByteStream {
        routes += route
        ports += port
        return NoopStream().also { streams += it }
    }
}

private class RecordingSshClient : HostSshClient {
    val sessions = mutableListOf<ScriptedSshSession>()

    override suspend fun connect(
        host: com.code2hack.pokerdealer.domain.CodexHost,
        tcpStream: DuplexByteStream,
    ): HostSshSession {
        return ScriptedSshSession(
            CommandResult(0, """{"status":"running","cliVersion":"codex-cli 0.145.0","appServerVersion":"0.145.0"}"""),
        ).also { sessions += it }
    }
}

private class ScriptedSshSession(
    vararg results: CommandResult,
) : HostSshSession {
    private val results = ArrayDeque(results.toList())
    val execCommands = mutableListOf<String>()
    val streamCommands = mutableListOf<String>()
    var closed = false

    override suspend fun exec(command: String): CommandResult {
        execCommands += command
        return results.removeFirst()
    }

    override suspend fun execStream(command: String): DuplexByteStream {
        streamCommands += command
        return NoopStream()
    }

    override suspend fun close() {
        closed = true
    }
}

private class NoopStream : DuplexByteStream {
    var closed = false
    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
    override suspend fun close() {
        closed = true
    }
}
