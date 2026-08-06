package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.CardRole
import com.code2hack.pokerdealer.domain.DeliveryState
import com.code2hack.pokerdealer.domain.HostConnectionRoute
import com.code2hack.pokerdealer.domain.InitialCodexHosts
import com.code2hack.pokerdealer.domain.ThreadWorkState
import com.code2hack.pokerdealer.protocol.host.CommandResult
import com.code2hack.pokerdealer.protocol.host.ConnectionPhaseTimeoutException
import com.code2hack.pokerdealer.protocol.host.DuplexByteStream
import com.code2hack.pokerdealer.protocol.host.HostSshClient
import com.code2hack.pokerdealer.protocol.host.HostSshSession
import com.code2hack.pokerdealer.protocol.host.HostTcpDialer
import com.code2hack.pokerdealer.protocol.host.HostIdentityException
import com.code2hack.pokerdealer.protocol.host.RouteCapability
import com.code2hack.pokerdealer.protocol.host.RouteConnectionException
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
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodexAppServerM1Test {
    @Test
    fun `image turn input keeps the native data URL and original detail`() {
        val input = AppServerTurnInput.image("data:image/jpeg;base64,AA==")

        assertEquals("image", input["type"]?.jsonPrimitive?.content)
        assertEquals("data:image/jpeg;base64,AA==", input["image_url"]?.jsonPrimitive?.content)
        assertEquals("original", input["detail"]?.jsonPrimitive?.content)
    }

    @Test
    fun `steer carries the exact active turn precondition and interrupt stays bound to it`() = runTest {
        val peer = FixtureJsonRpcPeer(
            exchanges = listOf(
                fixture("initialize-request.json", "initialize-response.json"),
                fixture("turn-steer-request.json", "turn-steer-response.json"),
                fixture("turn-interrupt-request.json", "turn-interrupt-response.json"),
            ),
        )
        val session = CodexAppServerSession(peer)
        session.initialize()

        assertEquals(
            "turn_active",
            session.turnSteer(
                threadId = "thr_u4090_m1",
                expectedTurnId = "turn_active",
                text = "Use the smaller fix",
                clientUserMessageId = "dealer-steer-1",
            )["turnId"]?.jsonPrimitive?.content,
        )
        session.turnInterrupt("thr_u4090_m1", "turn_active")

        assertEquals(listOf("initialize", "turn/steer", "turn/interrupt"), peer.requests)
    }

    @Test
    fun `authoritative read recovers the active turn and reconciles one client keyed user card`() {
        val response = loadFixture("thread-read-active-response.json").jsonObject
            .getValue("result").jsonObject

        val state = AppServerThreadProjection.authoritativeState(response)
        val cards = AppServerThreadProjection.cards(response, "u4090/thr_u4090_m1")

        assertEquals(ThreadWorkState.BUSY, state.workState)
        assertEquals("turn_active", state.activeTurnId)
        assertEquals(listOf("dealer-steer-1"), cards.map { it.id })
        assertEquals(listOf(DeliveryState.DELIVERED), cards.map { it.delivery })
    }

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
        val phases = mutableListOf<M1ConnectionPhase>()
        val activeRoutes = mutableListOf<HostConnectionRoute>()
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
            onPhase = phases::add,
            onRoute = { route, _ -> activeRoutes += route },
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
        assertEquals("dealer-client-u4090-m1", result.userCard.id)
        assertEquals(DeliveryState.DELIVERED, result.userCard.delivery)
        assertEquals(1, result.matchingUserMessagesAfterReconnect)
        assertEquals(false, result.recoveredAfterDisconnect)
        assertEquals(
            listOf(M1ConnectionPhase.CONNECTING, M1ConnectionPhase.RUNNING, M1ConnectionPhase.RECONNECTING),
            phases,
        )
        assertEquals(listOf(HostConnectionRoute.SSH_LAN, HostConnectionRoute.SSH_LAN), activeRoutes)
        val userRevisions = renderedCards.filter { it.id == "dealer-client-u4090-m1" }
        assertEquals(
            listOf(DeliveryState.LOCAL_PENDING, DeliveryState.ACCEPTED, DeliveryState.DELIVERED),
            userRevisions.map { it.delivery },
        )
        assertEquals(listOf(1L, 2L, 3L), userRevisions.map { it.revision })
        assertEquals(1, userRevisions.map { it.id }.distinct().size)
        assertEquals(
            listOf(
                "Existing prompt",
                "Existing answer",
                "Dealer M1 proof from u4090",
                "Dealer M1 proof from u4090",
                "streamed ",
                "streamed answer",
                "streamed answer",
                "Dealer M1 proof from u4090",
            ),
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
        assertEquals(DeliveryState.DELIVERED, result.userCard.delivery)
        assertTrue(result.streamedCards.all { it.sequence > result.userCard.sequence })
        assertEquals(1, firstPeer.requests.count { it == "turn/start" })
        assertEquals(0, secondPeer.requests.count { it == "turn/start" })
    }

    @Test
    fun `Termux reconnect backs off after loopback failure then rereads without replay`() = runTest {
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
        val recoveredPeer = completeReconnectPeer()
        val peers = ArrayDeque(listOf(firstPeer, recoveredPeer))
        val dialer = RecordingDialer(
            capabilities = mapOf(HostConnectionRoute.SSH_LOOPBACK to RouteCapability.SUPPORTED_CONFIGURED),
            failuresByCall = mapOf(2 to IllegalStateException("loopback sshd stopped")),
        )
        val recovery = mutableListOf<M1RecoveryUpdate>()
        val slice = M1OneHostDealerSlice(
            host = InitialCodexHosts.fold6Termux,
            dialer = dialer,
            sshClient = RecordingSshClient(
                daemonStatus = loadFixtureText("termux-daemon-running.json"),
            ),
            daemon = TermuxCommunityCodexDaemon(),
            reconnectPolicy = M1ReconnectPolicy(maxAttempts = 3, initialBackoffMs = 10, maxBackoffMs = 20),
            appServerFactory = { CodexAppServerSession(peers.removeFirst()) },
        )

        val result = slice.run(
            M1TurnInput("Dealer M1 proof from u4090", clientUserMessageId = "dealer-client-u4090-m1"),
            onRecovery = recovery::add,
        )

        assertEquals(M1FailurePhase.TCP_CONNECT, recovery.single().failurePhase)
        assertEquals(10, recovery.single().retryInMs)
        assertEquals(3, dialer.routes.size)
        assertEquals(1, firstPeer.requests.count { it == "turn/start" })
        assertEquals(0, recoveredPeer.requests.count { it == "turn/start" })
        assertEquals(DeliveryState.DELIVERED, result.userCard.delivery)
        assertTrue(result.recoveredAfterDisconnect)
    }

    @Test
    fun `cancellation during reconnect backoff stops before another connection attempt`() = runTest {
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
        val dialer = RecordingDialer(failuresByCall = mapOf(2 to IllegalStateException("host stopped")))
        val recovery = mutableListOf<M1RecoveryUpdate>()
        val slice = M1OneHostDealerSlice(
            dialer = dialer,
            sshClient = RecordingSshClient(),
            reconnectPolicy = M1ReconnectPolicy(initialBackoffMs = 60_000, maxBackoffMs = 60_000),
            appServerFactory = { CodexAppServerSession(firstPeer) },
        )
        val job = launch {
            slice.run(
                M1TurnInput("Dealer M1 proof from u4090", clientUserMessageId = "dealer-client-u4090-m1"),
                onRecovery = recovery::add,
            )
        }
        while (recovery.isEmpty()) yield()

        job.cancelAndJoin()

        assertEquals(2, dialer.routes.size)
        assertTrue(firstPeer.closed)
        assertTrue(dialer.streams.single().closed)
    }

    @Test
    fun `uncertain turn acceptance keeps one user card marked unknown without replay`() = runTest {
        val firstPeer = FixtureJsonRpcPeer(
            exchanges = listOf(
                fixture("initialize-request.json", "initialize-response.json"),
                fixture("thread-list-request.json", "thread-list-response.json"),
                fixture("thread-resume-request.json", "thread-resume-response.json"),
                fixture("thread-read-request.json", "thread-read-response-before.json"),
                fixture("turn-start-request.json", "turn-start-response.json"),
            ),
            failOnRequestMethod = "turn/start",
        )
        val secondPeer = FixtureJsonRpcPeer(
            exchanges = listOf(
                fixture("initialize-request.json", "initialize-response.json"),
                fixture("thread-resume-request.json", "thread-resume-response.json"),
                fixture("thread-read-request.json", "thread-read-response-before.json"),
            ),
        )
        val peers = ArrayDeque(listOf(firstPeer, secondPeer))
        val renderedCards = mutableListOf<com.code2hack.pokerdealer.domain.Card>()
        val slice = M1OneHostDealerSlice(
            dialer = RecordingDialer(),
            sshClient = RecordingSshClient(),
            appServerFactory = { CodexAppServerSession(peers.removeFirst()) },
        )

        val failure = runCatching {
            slice.run(
                M1TurnInput("Dealer M1 proof from u4090", clientUserMessageId = "dealer-client-u4090-m1"),
                onCard = renderedCards::add,
            )
        }.exceptionOrNull()

        val userRevisions = renderedCards.filter { it.id == "dealer-client-u4090-m1" }
        assertEquals(
            listOf(DeliveryState.LOCAL_PENDING, DeliveryState.UNKNOWN),
            userRevisions.map { it.delivery },
        )
        assertEquals(listOf(1L, 2L), userRevisions.map { it.revision })
        assertTrue(failure?.message.orEmpty().contains("turn/start was not replayed"))
        assertEquals(M1TurnOutcome.UNKNOWN, (failure as M1TurnRecoveryException).outcome)
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
    fun `cancellation while awaiting turn acceptance marks the same user card unknown`() = runTest {
        val renderedCards = mutableListOf<com.code2hack.pokerdealer.domain.Card>()
        val peer = FixtureJsonRpcPeer(
            exchanges = listOf(
                fixture("initialize-request.json", "initialize-response.json"),
                fixture("thread-list-request.json", "thread-list-response.json"),
                fixture("thread-resume-request.json", "thread-resume-response.json"),
                fixture("thread-read-request.json", "thread-read-response-before.json"),
                fixture("turn-start-request.json", "turn-start-response.json"),
            ),
            waitOnRequestMethod = "turn/start",
        )
        val slice = M1OneHostDealerSlice(
            dialer = RecordingDialer(),
            sshClient = RecordingSshClient(),
            appServerFactory = { CodexAppServerSession(peer) },
        )

        val job = launch {
            slice.run(
                M1TurnInput(
                    text = "Dealer M1 proof from u4090",
                    clientUserMessageId = "dealer-client-u4090-m1",
                ),
                onCard = renderedCards::add,
            )
        }
        while ("turn/start" !in peer.requests) yield()
        job.cancelAndJoin()

        val userRevisions = renderedCards.filter { it.id == "dealer-client-u4090-m1" }
        assertEquals(
            listOf(DeliveryState.LOCAL_PENDING, DeliveryState.UNKNOWN),
            userRevisions.map { it.delivery },
        )
        assertEquals(1, userRevisions.map { it.id }.distinct().size)
        assertTrue(peer.closed)
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

    @Test
    fun `Termux community daemon parses machine lifecycle output and requires a bound socket`() = runTest {
        val daemon = TermuxCommunityCodexDaemon()
        val session = ScriptedSshSession(
            CommandResult(
                1,
                "",
                "Error: failed to connect to the Termux app-server control socket: No such file or directory",
            ),
            CommandResult(0, loadFixtureText("termux-daemon-started.json")),
            CommandResult(0, loadFixtureText("termux-daemon-running.json")),
        )

        val versions = daemon.ensureRunning(session)

        assertEquals("running", versions.status)
        assertEquals("0.145.0", versions.managedCodexVersion)
        assertEquals("0.145.0", versions.appServerVersion)
        assertTrue(versions.socketPath.orEmpty().endsWith("app-server-control.sock"))
        assertEquals("\"pid\"", versions.raw["backend"].toString())
        assertEquals(
            listOf(
                daemon.daemonVersionCommand,
                daemon.daemonStartCommand,
                daemon.daemonVersionCommand,
            ),
            session.execCommands,
        )
    }

    @Test
    fun `Termux community daemon rejects running output without socket evidence`() = runTest {
        val session = ScriptedSshSession(
            CommandResult(
                0,
                """{"status":"running","managedCodexVersion":"0.145.0","appServerVersion":"0.145.0"}""",
            ),
        )

        val failure = runCatching {
            TermuxCommunityCodexDaemon().ensureRunning(session)
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("did not report a bound control socket"))
    }

    @Test
    fun `Termux community daemon rejects an unobserved start status`() = runTest {
        val session = ScriptedSshSession(
            CommandResult(1, "", "control socket unavailable"),
            CommandResult(0, loadFixtureText("termux-daemon-running.json")),
        )

        val failure = runCatching {
            TermuxCommunityCodexDaemon().ensureRunning(session)
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("start returned status running"))
    }

    @Test
    fun `Fold6 Termux reuses the shared turn stack through loopback only`() = runTest {
        val firstPeer = completeFirstPeer()
        val secondPeer = completeReconnectPeer()
        val peers = ArrayDeque(listOf(firstPeer, secondPeer))
        val dialer = RecordingDialer(
            capabilities = mapOf(HostConnectionRoute.SSH_LOOPBACK to RouteCapability.SUPPORTED_CONFIGURED),
        )
        val sshClient = RecordingSshClient(loadFixtureText("termux-daemon-running.json"))
        val renderedCards = mutableListOf<com.code2hack.pokerdealer.domain.Card>()
        val slice = M1OneHostDealerSlice(
            host = InitialCodexHosts.fold6Termux,
            dialer = dialer,
            sshClient = sshClient,
            daemon = TermuxCommunityCodexDaemon(),
            appServerFactory = { CodexAppServerSession(peers.removeFirst()) },
        )

        val result = slice.run(
            M1TurnInput(
                text = "Dealer M1 proof from u4090",
                clientUserMessageId = "dealer-client-u4090-m1",
            ),
            onCard = renderedCards::add,
        )

        assertEquals("${InitialCodexHosts.fold6Termux.id}/${result.threadId}", result.conversationId)
        assertEquals(HostConnectionRoute.SSH_LOOPBACK, result.route)
        assertEquals(HostConnectionRoute.SSH_LOOPBACK, result.reconnectRoute)
        assertEquals(listOf(HostConnectionRoute.SSH_LOOPBACK, HostConnectionRoute.SSH_LOOPBACK), dialer.routes)
        assertTrue(result.routeDiagnostics.all { it.route == HostConnectionRoute.SSH_LOOPBACK })
        assertEquals(
            listOf(DeliveryState.LOCAL_PENDING, DeliveryState.ACCEPTED, DeliveryState.DELIVERED),
            renderedCards.filter { it.id == "dealer-client-u4090-m1" }.map { it.delivery },
        )
        assertEquals(listOf("initialize", "thread/list", "thread/resume", "thread/read", "turn/start"), firstPeer.requests)
        assertEquals(listOf("initialized"), firstPeer.notifications)
        assertEquals(listOf("initialize", "thread/resume", "thread/read"), secondPeer.requests)
        assertEquals(listOf("initialized"), secondPeer.notifications)
    }

    @Test
    fun `LAN-only provider skips unsupported routes and preserves the LAN failure`() = runTest {
        val lanFailure = IllegalStateException("LAN connection refused")
        val dialer = RecordingDialer(failures = mapOf(HostConnectionRoute.SSH_LAN to lanFailure))
        val slice = M1OneHostDealerSlice(dialer = dialer, sshClient = RecordingSshClient())

        val failure = runCatching {
            slice.run(M1TurnInput("test", clientUserMessageId = "client-1"))
        }.exceptionOrNull()

        assertTrue(failure is RouteConnectionException)
        assertEquals(listOf(HostConnectionRoute.SSH_LAN), dialer.routes)
        assertEquals(lanFailure.message, failure?.cause?.message)
        assertTrue(failure?.message.orEmpty().contains("SSH_LAN: LAN connection refused"))
    }

    @Test
    fun `route fallback follows host order among supported configured routes`() = runTest {
        val firstPeer = completeFirstPeer()
        val secondPeer = completeReconnectPeer()
        val peers = ArrayDeque(listOf(firstPeer, secondPeer))
        val dialer = RecordingDialer(
            capabilities = mapOf(
                HostConnectionRoute.SSH_LAN to RouteCapability.SUPPORTED_CONFIGURED,
                HostConnectionRoute.SSH_EMBEDDED_TSNET to RouteCapability.SUPPORTED_CONFIGURED,
            ),
            failures = mapOf(HostConnectionRoute.SSH_LAN to IllegalStateException("LAN unavailable")),
        )
        val slice = M1OneHostDealerSlice(
            dialer = dialer,
            sshClient = RecordingSshClient(),
            appServerFactory = { CodexAppServerSession(peers.removeFirst()) },
        )

        val result = slice.run(M1TurnInput("Dealer M1 proof from u4090", clientUserMessageId = "dealer-client-u4090-m1"))

        assertEquals(HostConnectionRoute.SSH_EMBEDDED_TSNET, result.route)
        assertEquals(HostConnectionRoute.SSH_EMBEDDED_TSNET, result.reconnectRoute)
        assertEquals(
            listOf(
                HostConnectionRoute.SSH_LAN,
                HostConnectionRoute.SSH_EMBEDDED_TSNET,
                HostConnectionRoute.SSH_LAN,
                HostConnectionRoute.SSH_EMBEDDED_TSNET,
            ),
            dialer.routes,
        )
        assertTrue(result.routeDiagnostics.any { it.route == HostConnectionRoute.SSH_LAN && it.failure != null })
    }

    @Test
    fun `SSH host-key failure is terminal across routes`() = runTest {
        val dialer = RecordingDialer(
            capabilities = mapOf(
                HostConnectionRoute.SSH_LAN to RouteCapability.SUPPORTED_CONFIGURED,
                HostConnectionRoute.SSH_EMBEDDED_TSNET to RouteCapability.SUPPORTED_CONFIGURED,
            ),
        )
        val identityFailure = HostIdentityException("host key changed", IllegalStateException("mismatch"))
        val sshClient = object : HostSshClient {
            override suspend fun connect(
                host: com.code2hack.pokerdealer.domain.CodexHost,
                tcpStream: DuplexByteStream,
            ): HostSshSession = throw identityFailure
        }
        val slice = M1OneHostDealerSlice(dialer = dialer, sshClient = sshClient)

        val failure = runCatching {
            slice.run(M1TurnInput("test", clientUserMessageId = "client-1"))
        }.exceptionOrNull()

        assertTrue(failure is HostIdentityException)
        assertEquals(identityFailure.message, failure?.message)
        assertEquals(listOf(HostConnectionRoute.SSH_LAN), dialer.routes)
        val diagnostics = (failure as HostIdentityException).diagnostics
        assertEquals(true, diagnostics.first { it.route == HostConnectionRoute.SSH_LAN }.attempted)
        assertEquals("host key changed", diagnostics.first { it.route == HostConnectionRoute.SSH_LAN }.failure)
        assertEquals(false, diagnostics.first { it.route == HostConnectionRoute.SSH_EMBEDDED_TSNET }.attempted)
    }

    @Test
    fun `TCP dial timeout is bounded and route labelled`() = runTest {
        val dialer = object : HostTcpDialer {
            override fun capability(
                host: com.code2hack.pokerdealer.domain.CodexHost,
                route: HostConnectionRoute,
            ) = if (route == HostConnectionRoute.SSH_LAN) {
                RouteCapability.SUPPORTED_CONFIGURED
            } else {
                RouteCapability.UNSUPPORTED
            }

            override suspend fun connect(
                host: com.code2hack.pokerdealer.domain.CodexHost,
                route: HostConnectionRoute,
                port: Int,
            ): DuplexByteStream = awaitCancellation()
        }
        val slice = M1OneHostDealerSlice(
            dialer = dialer,
            sshClient = RecordingSshClient(),
            timeouts = M1Timeouts(tcpConnectMs = 100),
        )

        val failure = runCatching {
            slice.run(M1TurnInput("test", clientUserMessageId = "client-1"))
        }.exceptionOrNull()

        assertTrue(failure is RouteConnectionException)
        assertTrue(failure?.cause is ConnectionPhaseTimeoutException)
        assertTrue(failure?.message.orEmpty().contains("SSH_LAN"))
    }

    @Test
    fun `SSH connect timeout closes the established TCP stream`() = runTest {
        val dialer = RecordingDialer()
        val sshClient = object : HostSshClient {
            override suspend fun connect(
                host: com.code2hack.pokerdealer.domain.CodexHost,
                tcpStream: DuplexByteStream,
            ): HostSshSession = awaitCancellation()
        }
        val slice = M1OneHostDealerSlice(
            dialer = dialer,
            sshClient = sshClient,
            timeouts = M1Timeouts(sshConnectMs = 100),
        )

        val failure = runCatching {
            slice.run(M1TurnInput("test", clientUserMessageId = "client-1"))
        }.exceptionOrNull()

        assertTrue(failure is RouteConnectionException)
        assertTrue(failure?.cause is ConnectionPhaseTimeoutException)
        assertTrue(dialer.streams.single().closed)
    }

    @Test
    fun `daemon command timeout closes resources and retains fallback route diagnostics`() = runTest {
        val dialer = RecordingDialer(
            capabilities = mapOf(
                HostConnectionRoute.SSH_LAN to RouteCapability.SUPPORTED_CONFIGURED,
                HostConnectionRoute.SSH_EMBEDDED_TSNET to RouteCapability.SUPPORTED_CONFIGURED,
            ),
            failures = mapOf(HostConnectionRoute.SSH_LAN to IllegalStateException("LAN unavailable")),
        )
        val session = BlockingCommandSshSession()
        val activeRoutes = mutableListOf<HostConnectionRoute>()
        val sshClient = object : HostSshClient {
            override suspend fun connect(
                host: com.code2hack.pokerdealer.domain.CodexHost,
                tcpStream: DuplexByteStream,
            ): HostSshSession = session
        }
        val slice = M1OneHostDealerSlice(
            dialer = dialer,
            sshClient = sshClient,
            timeouts = M1Timeouts(daemonCommandMs = 100),
        )

        val failure = runCatching {
            slice.run(
                M1TurnInput("test", clientUserMessageId = "client-1"),
                onRoute = { route, _ -> activeRoutes += route },
            )
        }.exceptionOrNull()

        assertTrue(failure is RouteConnectionException)
        assertTrue(failure?.cause is ConnectionPhaseTimeoutException)
        val diagnostics = (failure as RouteConnectionException).diagnostics
        assertEquals("LAN unavailable", diagnostics.first { it.route == HostConnectionRoute.SSH_LAN }.failure)
        assertTrue(
            diagnostics.first { it.route == HostConnectionRoute.SSH_EMBEDDED_TSNET }
                .failure
                .orEmpty()
                .contains("daemon status/start timed out"),
        )
        assertEquals(listOf(HostConnectionRoute.SSH_EMBEDDED_TSNET), activeRoutes)
        assertTrue(session.closed)
        assertTrue(dialer.streams.single().closed)
    }

    @Test
    fun `reconnect inspection timeout does not regress an accepted user card`() = runTest {
        val firstPeer = completeFirstPeer()
        val secondPeer = HangingJsonRpcPeer()
        val peers = ArrayDeque<JsonRpcPeer>(listOf(firstPeer, secondPeer))
        val renderedCards = mutableListOf<com.code2hack.pokerdealer.domain.Card>()
        val slice = M1OneHostDealerSlice(
            dialer = RecordingDialer(),
            sshClient = RecordingSshClient(),
            timeouts = M1Timeouts(reconnectInspectionMs = 100),
            appServerFactory = { CodexAppServerSession(peers.removeFirst(), requestTimeoutMs = 10_000) },
        )

        val failure = runCatching {
            slice.run(
                M1TurnInput("Dealer M1 proof from u4090", clientUserMessageId = "dealer-client-u4090-m1"),
                onCard = renderedCards::add,
            )
        }.exceptionOrNull()

        assertTrue(failure is ConnectionPhaseTimeoutException)
        assertTrue(failure?.message.orEmpty().contains("reconnect inspection timed out after 100ms"))
        assertEquals(M1FailurePhase.RECONNECT_INSPECTION, failure?.m1FailurePhase())
        assertEquals(
            listOf(DeliveryState.LOCAL_PENDING, DeliveryState.ACCEPTED),
            renderedCards.filter { it.id == "dealer-client-u4090-m1" }.map { it.delivery },
        )
        assertTrue(secondPeer.closed)
    }

    private fun fixture(request: String, response: String) = FixtureExchange(request, response)

    private fun completeFirstPeer() = FixtureJsonRpcPeer(
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

    private fun completeReconnectPeer() = FixtureJsonRpcPeer(
        exchanges = listOf(
            fixture("initialize-request.json", "initialize-response.json"),
            fixture("thread-resume-request.json", "thread-resume-response.json"),
            fixture("thread-read-request.json", "thread-read-response-after.json"),
        ),
    )
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
    private val failOnRequestMethod: String? = null,
    private val waitOnRequestMethod: String? = null,
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
        if (method == failOnRequestMethod) error("$method disconnected before response")
        if (method == waitOnRequestMethod) awaitCancellation()
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

private class HangingJsonRpcPeer : JsonRpcPeer {
    var closed = false

    override suspend fun request(method: String, params: JsonElement): JsonElement = awaitCancellation()
    override suspend fun notify(method: String, params: JsonElement?) = Unit
    override suspend fun receiveNotification(): AppServerNotification? = awaitCancellation()
    override suspend fun close() {
        closed = true
    }
}

private fun loadFixture(name: String): JsonElement = AppServerJson.parseToJsonElement(loadFixtureText(name))

private fun loadFixtureText(name: String): String {
    return object {}.javaClass.getResource("/app-server/v2/$name")?.readText()
        ?: error("Missing fixture $name")
}

private class RecordingDialer(
    private val capabilities: Map<HostConnectionRoute, RouteCapability> =
        mapOf(HostConnectionRoute.SSH_LAN to RouteCapability.SUPPORTED_CONFIGURED),
    private val failures: Map<HostConnectionRoute, Throwable> = emptyMap(),
    private val failuresByCall: Map<Int, Throwable> = emptyMap(),
) : HostTcpDialer {
    val routes = mutableListOf<HostConnectionRoute>()
    val ports = mutableListOf<Int>()
    val streams = mutableListOf<NoopStream>()

    override fun capability(
        host: com.code2hack.pokerdealer.domain.CodexHost,
        route: HostConnectionRoute,
    ): RouteCapability = capabilities[route] ?: RouteCapability.UNSUPPORTED

    override suspend fun connect(
        host: com.code2hack.pokerdealer.domain.CodexHost,
        route: HostConnectionRoute,
        port: Int,
    ): DuplexByteStream {
        routes += route
        ports += port
        failuresByCall[routes.size]?.let { throw it }
        failures[route]?.let { throw it }
        return NoopStream().also { streams += it }
    }
}

private class RecordingSshClient(
    private val daemonStatus: String =
        """{"status":"running","cliVersion":"codex-cli 0.145.0","appServerVersion":"0.145.0"}""",
) : HostSshClient {
    val sessions = mutableListOf<ScriptedSshSession>()

    override suspend fun connect(
        host: com.code2hack.pokerdealer.domain.CodexHost,
        tcpStream: DuplexByteStream,
    ): HostSshSession {
        return ScriptedSshSession(
            CommandResult(0, daemonStatus),
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

private class BlockingCommandSshSession : HostSshSession {
    var closed = false

    override suspend fun exec(command: String): CommandResult = awaitCancellation()
    override suspend fun execStream(command: String): DuplexByteStream = NoopStream()
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
