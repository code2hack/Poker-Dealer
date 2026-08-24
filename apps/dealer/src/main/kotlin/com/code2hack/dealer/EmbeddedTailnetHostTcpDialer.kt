package com.code2hack.dealer

import com.code2hack.pokerdealer.domain.CodexHost
import com.code2hack.pokerdealer.domain.HostConnectionRoute
import com.code2hack.pokerdealer.protocol.host.DuplexByteStream
import com.code2hack.pokerdealer.protocol.host.HostTcpDialer
import com.code2hack.pokerdealer.protocol.host.RouteCapability
import com.code2hack.pokerdealer.protocol.host.RouteEndpoint
import com.code2hack.pokerdealer.protocol.host.SocketHostTcpDialer
import com.code2hack.tailnet.embeddedtailnet.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean

internal class EmbeddedTailnetHostTcpDialer(
    private val engine: Engine,
    private val destinations: Map<String, String>,
    private val state: () -> EmbeddedTailnetConnectionState,
) : HostTcpDialer {
    override fun capability(host: CodexHost, route: HostConnectionRoute): RouteCapability =
        if (route != HostConnectionRoute.SSH_EMBEDDED_TSNET) {
            RouteCapability.UNSUPPORTED
        } else if (destinations[host.id].isNullOrBlank()) {
            RouteCapability.DISABLED
        } else {
            when (state()) {
                EmbeddedTailnetConnectionState.CONNECTED,
                EmbeddedTailnetConnectionState.DEGRADED,
                -> RouteCapability.SUPPORTED_CONFIGURED

                EmbeddedTailnetConnectionState.STARTING,
                EmbeddedTailnetConnectionState.LOGIN_REQUIRED,
                EmbeddedTailnetConnectionState.UNAVAILABLE,
                EmbeddedTailnetConnectionState.ERROR,
                -> RouteCapability.SUPPORTED_UNAVAILABLE

                EmbeddedTailnetConnectionState.STOPPED,
                EmbeddedTailnetConnectionState.STOPPING,
                -> RouteCapability.DISABLED
            }
        }

    override suspend fun connect(
        host: CodexHost,
        route: HostConnectionRoute,
        port: Int,
    ): DuplexByteStream {
        check(capability(host, route) == RouteCapability.SUPPORTED_CONFIGURED) {
            "Embedded tailnet is not connected"
        }
        val destination = destinations.getValue(host.id)
        val descriptor = withContext(Dispatchers.IO) {
            engine.openTunnel(destination, port.toLong()).toTunnelDescriptor()
        }
        try {
            val stream = SocketHostTcpDialer(
                mapOf(
                    (host.id to route) to RouteEndpoint("127.0.0.1", descriptor.port),
                ),
            ).connect(host, route, port)
            stream.write("${descriptor.token}\n".encodeToByteArray())
            val response = stream.readLine()
            require(response == "OK") {
                response.removePrefix("ERROR ").ifBlank { "Embedded tailnet tunnel failed" }
            }
            return TunnelByteStream(stream, engine, descriptor.id)
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    engine.closeTunnel(descriptor.id)
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
            }
            throw failure
        }
    }
}

internal class DealerHostRouteDialer(
    private val direct: HostTcpDialer,
    private val embeddedTailnet: HostTcpDialer,
) : HostTcpDialer {
    override fun capability(host: CodexHost, route: HostConnectionRoute): RouteCapability =
        if (route == HostConnectionRoute.SSH_EMBEDDED_TSNET) {
            embeddedTailnet.capability(host, route)
        } else {
            direct.capability(host, route)
        }

    override suspend fun connect(
        host: CodexHost,
        route: HostConnectionRoute,
        port: Int,
    ): DuplexByteStream = if (route == HostConnectionRoute.SSH_EMBEDDED_TSNET) {
        embeddedTailnet.connect(host, route, port)
    } else {
        direct.connect(host, route, port)
    }
}

private data class TunnelDescriptor(
    val id: String,
    val port: Int,
    val token: String,
)

private fun String.toTunnelDescriptor(): TunnelDescriptor {
    val value = Json.parseToJsonElement(this).jsonObject
    val descriptor = TunnelDescriptor(
        id = value.getValue("id").jsonPrimitive.content,
        port = value.getValue("port").jsonPrimitive.int,
        token = value.getValue("token").jsonPrimitive.content,
    )
    require(descriptor.id.matches(Regex("[0-9a-f]{32}"))) { "Invalid embedded tunnel ID" }
    require(descriptor.port in 1..65_535) { "Invalid embedded tunnel port" }
    require(descriptor.token.matches(Regex("[0-9a-f]{64}"))) { "Invalid embedded tunnel token" }
    return descriptor
}

private suspend fun DuplexByteStream.readLine(): String {
    val bytes = ArrayList<Byte>()
    while (bytes.size < 1_024) {
        val next = ByteArray(1)
        require(read(next) == 1) { "Embedded tunnel closed during authentication" }
        if (next[0] == '\n'.code.toByte()) return bytes.toByteArray().decodeToString()
        bytes += next[0]
    }
    error("Embedded tunnel authentication response is too large")
}

private class TunnelByteStream(
    private val delegate: DuplexByteStream,
    private val engine: Engine,
    private val tunnelId: String,
) : DuplexByteStream by delegate {
    private val closed = AtomicBoolean()

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        try {
            delegate.close()
        } catch (caught: Throwable) {
            failure = caught
        }
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                engine.closeTunnel(tunnelId)
            } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure.addSuppressed(caught)
            }
        }
        failure?.let { throw it }
    }
}
