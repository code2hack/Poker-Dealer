package com.code2hack.pokerdealer.protocol.host

import com.code2hack.pokerdealer.domain.CodexHost
import com.code2hack.pokerdealer.domain.HostConnectionRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface DuplexByteStream {
    suspend fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int
    suspend fun write(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset)
    suspend fun close()
}

class ConnectionPhaseTimeoutException(
    phase: String,
    timeoutMs: Long,
    cause: Throwable,
) : IllegalStateException("$phase timed out after ${timeoutMs}ms", cause)

internal suspend fun <T> withConnectionPhaseTimeout(
    phase: String,
    timeoutMs: Long,
    block: suspend () -> T,
): T {
    require(timeoutMs > 0) { "$phase timeout must be positive" }
    return try {
        withTimeout(timeoutMs) { block() }
    } catch (failure: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw ConnectionPhaseTimeoutException(phase, timeoutMs, failure)
    }
}

interface HostTcpDialer {
    fun capability(host: CodexHost, route: HostConnectionRoute): RouteCapability

    suspend fun connect(
        host: CodexHost,
        route: HostConnectionRoute,
        port: Int,
    ): DuplexByteStream
}

enum class RouteCapability {
    SUPPORTED_CONFIGURED,
    SUPPORTED_UNAVAILABLE,
    UNSUPPORTED,
    DISABLED,
}

data class RouteDiagnostic(
    val route: HostConnectionRoute,
    val capability: RouteCapability,
    val attempted: Boolean,
    val failure: String? = null,
)

class RouteConnectionException(
    hostId: String,
    val diagnostics: List<RouteDiagnostic>,
    cause: Throwable?,
) : IllegalStateException(
    buildString {
        append("Unable to connect to ").append(hostId).append(": ")
        append(
            diagnostics.joinToString { diagnostic ->
                when {
                    diagnostic.failure != null -> "${diagnostic.route}: ${diagnostic.failure}"
                    diagnostic.attempted -> "${diagnostic.route}: connected"
                    else -> "${diagnostic.route}: ${diagnostic.capability}"
                }
            },
        )
    },
    cause,
)

data class RouteEndpoint(
    val hostName: String,
    val port: Int? = null,
)

class SocketHostTcpDialer(
    private val endpoints: Map<Pair<String, HostConnectionRoute>, RouteEndpoint>,
    private val capabilities: Map<Pair<String, HostConnectionRoute>, RouteCapability> =
        endpoints.keys.associateWith { RouteCapability.SUPPORTED_CONFIGURED },
    private val connectTimeoutMs: Int = 10_000,
) : HostTcpDialer {
    init {
        require(connectTimeoutMs > 0) { "TCP connect timeout must be positive" }
        capabilities.filterValues { it == RouteCapability.SUPPORTED_CONFIGURED }.keys.forEach { key ->
            require(key in endpoints) { "Supported route $key requires an endpoint" }
        }
    }

    override fun capability(host: CodexHost, route: HostConnectionRoute): RouteCapability =
        capabilities[host.id to route] ?: RouteCapability.UNSUPPORTED

    override suspend fun connect(
        host: CodexHost,
        route: HostConnectionRoute,
        port: Int,
    ): DuplexByteStream {
        check(capability(host, route) == RouteCapability.SUPPORTED_CONFIGURED) {
            "Route ${host.id}/$route is not available to this provider"
        }
        val endpoint = endpoints[host.id to route] ?: error("No endpoint configured for ${host.id} via $route")
        require(endpoint.hostName.isNotBlank()) { "TCP endpoint host name is required" }
        val endpointPort = endpoint.port ?: port
        require(endpointPort in 1..65_535) { "Invalid TCP port $endpointPort" }
        val socket = Socket()
        try {
            socket.cancellableIo {
                connect(InetSocketAddress(endpoint.hostName, endpointPort), connectTimeoutMs)
            }
            return SocketDuplexByteStream(socket)
        } catch (failure: Throwable) {
            socket.close()
            throw failure
        }
    }
}

/** A cancellable socket stream shared by route-neutral TCP clients. */
class SocketDuplexByteStream(
    private val socket: Socket,
) : DuplexByteStream {
    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = socket.cancellableIo {
        getInputStream().read(buffer, offset, length)
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) = socket.cancellableIo {
        getOutputStream().write(buffer, offset, length)
        getOutputStream().flush()
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        socket.close()
    }
}

private suspend fun <T> Socket.cancellableIo(operation: Socket.() -> T): T = withContext(Dispatchers.IO) {
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            runCatching { close() }
        }
        try {
            val result = operation()
            if (continuation.isActive) continuation.resume(result)
        } catch (failure: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
    }
}

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String = "",
)

class HostIdentityException(
    message: String,
    cause: Throwable,
    val diagnostics: List<RouteDiagnostic> = emptyList(),
) : SecurityException(message, cause)

interface HostSshClient {
    suspend fun connect(
        host: CodexHost,
        tcpStream: DuplexByteStream,
    ): HostSshSession
}

interface HostSshSession {
    suspend fun exec(command: String): CommandResult
    suspend fun execStream(command: String): DuplexByteStream
    suspend fun close()
}
