package com.code2hack.pokerdealer.protocol.host

import com.code2hack.pokerdealer.domain.CodexHost
import com.code2hack.pokerdealer.domain.HostConnectionRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

interface DuplexByteStream {
    suspend fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int
    suspend fun write(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset)
    suspend fun close()
}

interface HostTcpDialer {
    suspend fun connect(
        host: CodexHost,
        route: HostConnectionRoute,
        port: Int,
    ): DuplexByteStream
}

data class RouteEndpoint(
    val hostName: String,
    val port: Int? = null,
)

class SocketHostTcpDialer(
    private val endpoints: Map<Pair<String, HostConnectionRoute>, RouteEndpoint>,
    private val connectTimeoutMs: Int = 10_000,
) : HostTcpDialer {
    init {
        require(connectTimeoutMs > 0) { "TCP connect timeout must be positive" }
    }

    override suspend fun connect(
        host: CodexHost,
        route: HostConnectionRoute,
        port: Int,
    ): DuplexByteStream = withContext(Dispatchers.IO) {
        val endpoint = endpoints[host.id to route] ?: error("No endpoint configured for ${host.id} via $route")
        require(endpoint.hostName.isNotBlank()) { "TCP endpoint host name is required" }
        val endpointPort = endpoint.port ?: port
        require(endpointPort in 1..65_535) { "Invalid TCP port $endpointPort" }
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(endpoint.hostName, endpointPort), connectTimeoutMs)
            SocketByteStream(socket)
        } catch (failure: Throwable) {
            socket.close()
            throw failure
        }
    }
}

private class SocketByteStream(
    private val socket: Socket,
) : DuplexByteStream {
    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = withContext(Dispatchers.IO) {
        socket.getInputStream().read(buffer, offset, length)
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) = withContext(Dispatchers.IO) {
        socket.getOutputStream().write(buffer, offset, length)
        socket.getOutputStream().flush()
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        socket.close()
    }
}

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String = "",
)

class HostIdentityException(message: String, cause: Throwable) : SecurityException(message, cause)

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
