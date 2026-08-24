package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.CodexHost
import com.code2hack.pokerdealer.domain.HostConnectionRoute
import com.code2hack.pokerdealer.domain.InitialCodexHosts
import com.code2hack.pokerdealer.protocol.host.CommandResult
import com.code2hack.pokerdealer.protocol.host.DuplexByteStream
import com.code2hack.pokerdealer.protocol.host.HostSshClient
import com.code2hack.pokerdealer.protocol.host.HostSshSession
import com.code2hack.pokerdealer.protocol.host.HostTcpDialer
import com.code2hack.pokerdealer.protocol.host.RouteCapability
import com.code2hack.pokerdealer.protocol.host.RouteDiagnostic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HostSessionManagerTest {
    @Test
    fun `enabled hosts own independent sessions and disable closes only its host`() = runTest {
        val store = MemoryHostConnectionIntentStore()
        val sessions = mutableMapOf<String, RecordingHostSession>()
        val manager = HostSessionManager(
            hostIds = setOf("spark", "u4090", "fold6-termux"),
            intentStore = store,
            connector = HostSessionConnector { hostId ->
                RecordingHostSession().also { sessions[hostId] = it }
            },
            scope = backgroundScope,
        )

        manager.start()
        manager.setEnabled("spark", true)
        manager.setEnabled("u4090", true)
        runCurrent()

        assertEquals(HostSessionStatus.CONNECTED, manager.state.value.getValue("spark").status)
        assertEquals(HostSessionStatus.CONNECTED, manager.state.value.getValue("u4090").status)
        assertEquals(sessions.getValue("spark"), manager.connectedSession("spark"))
        assertEquals(sessions.getValue("u4090"), manager.connectedSession("u4090"))

        manager.setEnabled("spark", false)
        runCurrent()

        assertTrue(sessions.getValue("spark").closed.isCompleted)
        assertEquals(null, manager.connectedSession("spark"))
        assertTrue(!sessions.getValue("u4090").closed.isCompleted)
        assertEquals(HostSessionStatus.DISABLED, manager.state.value.getValue("spark").status)
        assertEquals(HostSessionStatus.CONNECTED, manager.state.value.getValue("u4090").status)
    }

    @Test
    fun `durable intent is restored after manager recreation`() = runTest {
        val store = MemoryHostConnectionIntentStore()
        val first = HostSessionManager(
            setOf("spark"),
            store,
            HostSessionConnector { RecordingHostSession() },
            backgroundScope,
        )
        first.start()
        first.setEnabled("spark", true)
        first.close()

        val connected = CompletableDeferred<Unit>()
        val restored = HostSessionManager(
            setOf("spark"),
            store,
            HostSessionConnector {
                connected.complete(Unit)
                RecordingHostSession()
            },
            backgroundScope,
        )
        restored.start()

        connected.await()
        assertEquals(true, restored.state.value.getValue("spark").enabled)
    }

    @Test
    fun `one host failure backs off without closing another host`() = runTest {
        var sparkAttempts = 0
        val u4090 = RecordingHostSession()
        val manager = HostSessionManager(
            setOf("spark", "u4090"),
            MemoryHostConnectionIntentStore(setOf("spark", "u4090")),
            HostSessionConnector { hostId ->
                if (hostId == "spark") {
                    sparkAttempts++
                    throw HostSessionConnectionException(
                        HostSessionPhase.SSH,
                        listOf(
                            RouteDiagnostic(
                                HostConnectionRoute.SSH_LAN,
                                RouteCapability.SUPPORTED_CONFIGURED,
                                attempted = true,
                                failure = "connection refused",
                            ),
                        ),
                    )
                }
                u4090
            },
            backgroundScope,
            backoff = HostSessionBackoff(initialMs = 1, maxMs = 2),
        )

        manager.start()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()

        assertTrue(sparkAttempts > 1)
        assertEquals(HostSessionStatus.BACKING_OFF, manager.state.value.getValue("spark").status)
        assertEquals("connection refused", manager.state.value.getValue("spark").diagnostics.single().failure)
        assertEquals(HostSessionStatus.CONNECTED, manager.state.value.getValue("u4090").status)
        assertTrue(!u4090.closed.isCompleted)
    }

    @Test
    fun `disabling a host cancels a blocked connection attempt`() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val manager = HostSessionManager(
            setOf("spark"),
            MemoryHostConnectionIntentStore(),
            HostSessionConnector {
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            },
            backgroundScope,
        )

        manager.start()
        manager.setEnabled("spark", true)
        val disabled = async { manager.setEnabled("spark", false) }
        disabled.await()

        cancelled.await()
        assertEquals(HostSessionStatus.DISABLED, manager.state.value.getValue("spark").status)
    }

    @Test
    fun `cancelling initialization closes SSH and TCP resources`() = runTest {
        val tcp = RecordingByteStream()
        val ssh = BlockingProxySshSession()
        val connector = InitializedHostSessionConnector {
            HostSessionConnectionConfig(
                host = InitialCodexHosts.u4090,
                dialer = SingleStreamDialer(tcp),
                sshClient = SingleSshClient(ssh),
                daemon = ImmediateDaemon,
            )
        }
        val connection = launch { connector.connect("u4090") }
        runCurrent()

        connection.cancelAndJoin()

        assertTrue(ssh.closed.isCompleted)
        assertTrue(tcp.closed.isCompleted)
    }

    @Test
    fun `SSH failure retains its phase and route diagnostic`() = runTest {
        val tcp = RecordingByteStream()
        val connector = InitializedHostSessionConnector {
            HostSessionConnectionConfig(
                host = InitialCodexHosts.u4090,
                dialer = SingleStreamDialer(tcp),
                sshClient = FailingSshClient,
                daemon = ImmediateDaemon,
            )
        }

        val failure = runCatching { connector.connect("u4090") }
            .exceptionOrNull() as HostSessionConnectionException
        val attempted = failure.diagnostics.single { it.attempted }

        assertEquals(HostSessionPhase.SSH, failure.phase)
        assertEquals(HostConnectionRoute.SSH_LAN, attempted.route)
        assertEquals("SSH refused", attempted.failure)
        assertTrue(tcp.closed.isCompleted)
    }

    @Test
    fun `cleanup failure does not stop reconnecting`() = runTest {
        var attempts = 0
        val manager = HostSessionManager(
            setOf("spark"),
            MemoryHostConnectionIntentStore(setOf("spark")),
            HostSessionConnector {
                attempts++
                FailingHostSession()
            },
            backgroundScope,
            HostSessionBackoff(initialMs = 1, maxMs = 1),
        )

        manager.start()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()

        assertTrue(attempts > 1)
        assertEquals(HostSessionStatus.BACKING_OFF, manager.state.value.getValue("spark").status)
    }

    @Test
    fun `terminal host identity failure does not retry`() = runTest {
        var attempts = 0
        val manager = HostSessionManager(
            setOf("spark"),
            MemoryHostConnectionIntentStore(setOf("spark")),
            HostSessionConnector {
                attempts++
                throw HostSessionConnectionException(
                    HostSessionPhase.SSH,
                    emptyList(),
                    retryable = false,
                )
            },
            backgroundScope,
            HostSessionBackoff(initialMs = 1, maxMs = 1),
        )

        manager.start()
        runCurrent()
        advanceTimeBy(10)
        runCurrent()

        assertEquals(1, attempts)
        assertEquals(HostSessionStatus.ERROR, manager.state.value.getValue("spark").status)
    }
}

private object ImmediateDaemon : CodexDaemonLifecycle {
    override val appServerProxyCommand = "codex app-server proxy"

    override suspend fun ensureRunning(ssh: HostSshSession) = DaemonVersions(
        status = "running",
        cliVersion = null,
        appServerVersion = null,
        managedCodexVersion = null,
        socketPath = "/tmp/codex.sock",
        raw = AppServerJson.parseToJsonElement("{}").jsonObject,
    )
}

private class SingleStreamDialer(
    private val stream: DuplexByteStream,
) : HostTcpDialer {
    override fun capability(host: CodexHost, route: HostConnectionRoute) =
        if (route == HostConnectionRoute.SSH_LAN) {
            RouteCapability.SUPPORTED_CONFIGURED
        } else {
            RouteCapability.DISABLED
        }

    override suspend fun connect(host: CodexHost, route: HostConnectionRoute, port: Int) = stream
}

private class SingleSshClient(
    private val session: HostSshSession,
) : HostSshClient {
    override suspend fun connect(host: CodexHost, tcpStream: DuplexByteStream) = session
}

private object FailingSshClient : HostSshClient {
    override suspend fun connect(host: CodexHost, tcpStream: DuplexByteStream): HostSshSession =
        error("SSH refused")
}

private class BlockingProxySshSession : HostSshSession {
    val closed = CompletableDeferred<Unit>()

    override suspend fun exec(command: String) = CommandResult(0, "", "")

    override suspend fun execStream(command: String): DuplexByteStream = awaitCancellation()

    override suspend fun close() {
        closed.complete(Unit)
    }
}

private class RecordingByteStream : DuplexByteStream {
    val closed = CompletableDeferred<Unit>()

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = awaitCancellation()

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) = Unit

    override suspend fun close() {
        closed.complete(Unit)
    }
}

private class RecordingHostSession : HostSession {
    override val appServer: CodexAppServerSession? = null
    override val route: HostConnectionRoute? = null
    override val diagnostics: List<RouteDiagnostic> = emptyList()
    val closed = CompletableDeferred<Unit>()

    override suspend fun awaitDisconnect(): Nothing = awaitCancellation()

    override suspend fun close() {
        closed.complete(Unit)
    }
}

private class FailingHostSession : HostSession {
    override val appServer: CodexAppServerSession? = null
    override val route: HostConnectionRoute? = null
    override val diagnostics: List<RouteDiagnostic> = emptyList()

    override suspend fun awaitDisconnect(): Nothing = error("proxy EOF")

    override suspend fun close() {
        error("cleanup failed")
    }
}

private class MemoryHostConnectionIntentStore(
    initial: Set<String> = emptySet(),
) : HostConnectionIntentStore {
    private var enabled = initial

    override suspend fun readEnabledHostIds(): Set<String> = enabled

    override suspend fun writeEnabledHostIds(hostIds: Set<String>) {
        enabled = hostIds
    }
}
