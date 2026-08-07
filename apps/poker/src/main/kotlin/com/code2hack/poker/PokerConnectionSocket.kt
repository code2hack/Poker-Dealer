package com.code2hack.poker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.code2hack.pokerdealer.protocol.LengthPrefixedPokerFrameSocket
import com.code2hack.pokerdealer.protocol.PinnedMutualTls
import com.code2hack.pokerdealer.protocol.PokerFrameSocket
import com.code2hack.pokerdealer.protocol.PokerListenerFactory
import com.code2hack.pokerdealer.protocol.PokerListenerSocket
import com.code2hack.pokerdealer.protocol.PokerPairingController
import com.code2hack.pokerdealer.protocol.POKER_LISTENER_PORT
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.cert.X509Certificate
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import com.code2hack.pokerdealer.protocol.host.SocketDuplexByteStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PokerListenerUnavailableException(message: String) : IOException(message)

class AndroidPokerListenerFactory(
    context: Context,
    private val identity: AndroidKeystorePairingIdentity,
    private val pairing: PokerPairingController,
    private val port: Int = POKER_LISTENER_PORT,
) : PokerListenerFactory {
    private val context = context.applicationContext

    override fun open(): PokerListenerSocket {
        val peerPublicKey = pairing.pinnedPeerPublicKey
            ?: throw PokerListenerUnavailableException("Poker is not paired")
        val address = activeWifiAddress(context)
        val server = identity.tlsContext(peerPublicKey).serverSocketFactory
            .createServerSocket() as SSLServerSocket
        try {
            PinnedMutualTls.requireClientAuthentication(server)
            server.reuseAddress = true
            server.bind(InetSocketAddress(address, port))
        } catch (failure: Exception) {
            runCatching { server.close() }
            throw PokerListenerUnavailableException("Unable to bind the active Wi-Fi interface").also {
                it.initCause(failure)
            }
        }
        return AndroidPokerListenerSocket(server, pairing)
    }
}

internal fun activeWifiAddress(context: Context): Inet4Address {
    selectOrdinaryWifiInterfaceAddress(
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty().mapNotNull { networkInterface ->
                runCatching {
                    WifiInterfaceSnapshot(
                        name = networkInterface.name,
                        isUp = networkInterface.isUp,
                        isLoopback = networkInterface.isLoopback,
                        addresses = networkInterface.inetAddresses.asSequence()
                            .filterIsInstance<Inet4Address>()
                            .toList(),
                    )
                }.getOrNull()
            }.toList()
        }.getOrDefault(emptyList()),
    )?.let { return it }

    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val candidates = connectivity.allNetworks.asSequence().mapNotNull { network ->
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
        val addresses = connectivity.getLinkProperties(network)?.linkAddresses
            ?.asSequence()
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.toList()
            .orEmpty()
        WifiAddressSnapshot(
            hasWifiTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            hasVpnTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            hasNotVpnCapability = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
            addresses = addresses,
        )
    }.toList()
    return selectOrdinaryWifiAddress(candidates)
        ?: throw PokerListenerUnavailableException("No ordinary Wi-Fi IPv4 address")
}

internal data class WifiInterfaceSnapshot(
    val name: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val addresses: List<Inet4Address>,
)

internal fun selectOrdinaryWifiInterfaceAddress(
    candidates: Iterable<WifiInterfaceSnapshot>,
): Inet4Address? = candidates.asSequence()
    .filter { it.name.startsWith("wlan") && it.isUp && !it.isLoopback }
    .flatMap { it.addresses.asSequence() }
    .firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }

internal data class WifiAddressSnapshot(
    val hasWifiTransport: Boolean,
    val hasVpnTransport: Boolean,
    val hasNotVpnCapability: Boolean,
    val addresses: List<Inet4Address>,
)

internal fun selectOrdinaryWifiAddress(
    candidates: Iterable<WifiAddressSnapshot>,
): Inet4Address? = candidates.asSequence()
    .filter { it.hasWifiTransport && !it.hasVpnTransport && it.hasNotVpnCapability }
    .flatMap { it.addresses.asSequence() }
    .firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }

private class AndroidPokerListenerSocket(
    private val server: SSLServerSocket,
    private val pairing: PokerPairingController,
) : PokerListenerSocket {
    private val pending = mutableSetOf<SSLSocket>()
    private var closed = false

    override suspend fun accept(): PokerFrameSocket = withContext(Dispatchers.IO) {
        synchronized(this@AndroidPokerListenerSocket) {
            check(!closed) { "Poker listener is closed" }
        }
        val socket = server.accept() as SSLSocket
        synchronized(this@AndroidPokerListenerSocket) {
            if (closed) {
                runCatching { socket.close() }
                throw PokerListenerUnavailableException("Poker listener is closed")
            }
            pending += socket
        }
        try {
            socket.startHandshake()
            val certificate = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw PokerListenerUnavailableException("Dealer certificate is unavailable")
            pairing.authenticatePeer(certificate.publicKey.encoded)
            LengthPrefixedPokerFrameSocket(SocketDuplexByteStream(socket), socket::close)
        } catch (failure: Exception) {
            runCatching { socket.close() }
            throw failure
        } finally {
            synchronized(this@AndroidPokerListenerSocket) { pending -= socket }
        }
    }

    override fun close() {
        val sockets = synchronized(this) {
            if (closed) return
            closed = true
            pending.toList().also { pending.clear() }
        }
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
    }
}
