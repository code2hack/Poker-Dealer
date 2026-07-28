package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.CodexHost
import com.code2hack.pokerdealer.domain.HostConnectionRoute
import com.code2hack.pokerdealer.protocol.host.DuplexByteStream
import com.code2hack.pokerdealer.protocol.host.HostIdentityException
import com.code2hack.pokerdealer.protocol.host.HostSshClient
import com.code2hack.pokerdealer.protocol.host.HostSshSession
import com.code2hack.pokerdealer.protocol.host.HostTcpDialer
import com.code2hack.pokerdealer.protocol.host.RouteCapability
import com.code2hack.pokerdealer.protocol.host.RouteDiagnostic
import com.code2hack.pokerdealer.protocol.host.withConnectionPhaseTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class HostSessionConnectionConfig(
    val host: CodexHost,
    val dialer: HostTcpDialer,
    val sshClient: HostSshClient,
    val daemon: CodexDaemonLifecycle,
    val qualifiedServerRequestVersions: Map<String, Set<String>> = emptyMap(),
    val timeouts: M1Timeouts = M1Timeouts(),
)

class InitializedHostSessionConnector(
    private val config: suspend (String) -> HostSessionConnectionConfig,
) : HostSessionConnector {
    override suspend fun connect(hostId: String): HostSession {
        val configured = config(hostId)
        require(configured.host.id == hostId)
        val routed = connectSsh(configured)
        var proxy: DuplexByteStream? = null
        var appServer: CodexAppServerSession? = null
        try {
            val daemonVersions = phase(HostSessionPhase.DAEMON, routed.diagnostics) {
                withConnectionPhaseTimeout("daemon status/start", configured.timeouts.daemonCommandMs) {
                    configured.daemon.ensureRunning(routed.ssh)
                }
            }
            proxy = phase(HostSessionPhase.PROXY, routed.diagnostics) {
                withConnectionPhaseTimeout("app-server proxy start", configured.timeouts.proxyStartMs) {
                    routed.ssh.execStream(configured.daemon.appServerProxyCommand)
                }
            }
            appServer = phase(HostSessionPhase.WEBSOCKET, routed.diagnostics) {
                val socket = AppServerWebSocket(
                    proxy,
                    handshakeTimeoutMs = configured.timeouts.webSocketUpgradeMs,
                )
                socket.open()
                CodexAppServerSession(
                    WebSocketJsonRpcPeer(
                        socket,
                        supportedServerRequests = supportedServerRequests(
                            daemonVersions.appServerVersion,
                            configured.qualifiedServerRequestVersions,
                        ),
                    ),
                    requestTimeoutMs = configured.timeouts.appServerRequestMs,
                    turnInactivityTimeoutMs = configured.timeouts.turnInactivityMs,
                )
            }
            phase(HostSessionPhase.INITIALIZE, routed.diagnostics) { appServer.initialize() }
            return ConnectedHostSession(
                routed.tcp,
                routed.ssh,
                appServer,
                routed.route,
                routed.diagnostics,
            )
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                runCatching { appServer?.close() }
                runCatching { proxy?.close() }
                runCatching { routed.ssh.close() }
                runCatching { routed.tcp.close() }
            }
            throw failure
        }
    }

    private suspend fun connectSsh(config: HostSessionConnectionConfig): RoutedSsh {
        // ponytail: keep the proven M1 error contract isolated; delete its parallel route loop with the one-shot path.
        val diagnostics = config.host.connectionRoutes.map { route ->
            RouteDiagnostic(route, config.dialer.capability(config.host, route), attempted = false)
        }.toMutableList()
        var lastFailure: Throwable? = null
        var lastPhase = HostSessionPhase.TCP
        config.host.connectionRoutes.forEachIndexed { index, route ->
            if (diagnostics[index].capability != RouteCapability.SUPPORTED_CONFIGURED) return@forEachIndexed
            var tcp: DuplexByteStream? = null
            try {
                lastPhase = HostSessionPhase.TCP
                tcp = withConnectionPhaseTimeout(
                    "TCP connect ${config.host.id} via $route",
                    config.timeouts.tcpConnectMs,
                ) {
                    config.dialer.connect(config.host, route, 22)
                }
                lastPhase = HostSessionPhase.SSH
                val ssh = withConnectionPhaseTimeout(
                    "SSH connect ${config.host.id} via $route",
                    config.timeouts.sshConnectMs,
                ) {
                    config.sshClient.connect(config.host, tcp)
                }
                diagnostics[index] = diagnostics[index].copy(attempted = true)
                return RoutedSsh(route, tcp, ssh, diagnostics)
            } catch (failure: CancellationException) {
                withContext(NonCancellable) { runCatching { tcp?.close() } }
                throw failure
            } catch (failure: HostIdentityException) {
                withContext(NonCancellable) { runCatching { tcp?.close() } }
                diagnostics[index] = diagnostics[index].copy(attempted = true, failure = failure.message)
                throw HostSessionConnectionException(
                    HostSessionPhase.SSH,
                    diagnostics,
                    retryable = false,
                    cause = failure,
                )
            } catch (failure: Throwable) {
                withContext(NonCancellable) { runCatching { tcp?.close() } }
                lastFailure = failure
                diagnostics[index] = diagnostics[index].copy(attempted = true, failure = failure.message)
            }
        }
        throw HostSessionConnectionException(
            lastPhase,
            diagnostics,
            cause = lastFailure,
        )
    }

    private suspend fun <T> phase(
        phase: HostSessionPhase,
        diagnostics: List<RouteDiagnostic>,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        throw HostSessionConnectionException(phase, diagnostics, cause = failure)
    }
}

internal fun supportedServerRequests(
    appServerVersion: String?,
    qualifiedVersions: Map<String, Set<String>>,
): Set<String> = buildSet {
    add(COMMAND_APPROVAL_METHOD)
    add(FILE_APPROVAL_METHOD)
    qualifiedVersions.forEach { (method, versions) ->
        if (appServerVersion in versions) add(method)
    }
}

private data class RoutedSsh(
    val route: HostConnectionRoute,
    val tcp: DuplexByteStream,
    val ssh: HostSshSession,
    val diagnostics: List<RouteDiagnostic>,
)

private class ConnectedHostSession(
    private val tcp: DuplexByteStream,
    private val ssh: HostSshSession,
    override val appServer: CodexAppServerSession,
    override val route: HostConnectionRoute,
    override val diagnostics: List<RouteDiagnostic>,
) : HostSession {
    override suspend fun awaitDisconnect(): Nothing = try {
        appServer.awaitClose()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        throw HostSessionConnectionException(
            HostSessionPhase.CONNECTED,
            diagnostics.map {
                if (it.route == route) {
                    it.copy(failure = failure.message ?: failure::class.java.simpleName)
                } else {
                    it
                }
            },
            cause = failure,
        )
    }

    override suspend fun close() {
        var failure: Throwable? = null
        suspend fun close(resource: suspend () -> Unit) {
            try {
                resource()
            } catch (caught: Throwable) {
                val firstFailure = failure
                if (firstFailure == null) failure = caught else firstFailure.addSuppressed(caught)
            }
        }
        close { appServer.close() }
        close { ssh.close() }
        close { tcp.close() }
        failure?.let { throw it }
    }
}
