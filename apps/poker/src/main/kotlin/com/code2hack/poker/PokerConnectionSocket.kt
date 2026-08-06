package com.code2hack.poker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.code2hack.pokerdealer.protocol.DEFAULT_MAX_FRAME_BYTES
import com.code2hack.pokerdealer.protocol.PinnedMutualTls
import com.code2hack.pokerdealer.protocol.PokerFrameSocket
import com.code2hack.pokerdealer.protocol.PokerListenerFactory
import com.code2hack.pokerdealer.protocol.PokerListenerSocket
import com.code2hack.pokerdealer.protocol.PokerPairingController
import com.code2hack.pokerdealer.protocol.PokerScheduledTask
import com.code2hack.pokerdealer.protocol.PokerScheduler
import com.code2hack.pokerdealer.protocol.POKER_LISTENER_PORT
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    private fun activeWifiAddress(context: Context): Inet4Address {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork
            ?: throw PokerListenerUnavailableException("No active Wi-Fi network")
        val capabilities = connectivity.getNetworkCapabilities(network)
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
            throw PokerListenerUnavailableException("The active network is not Wi-Fi")
        }
        return connectivity.getLinkProperties(network)?.linkAddresses
            ?.asSequence()
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }
            ?: throw PokerListenerUnavailableException("The active Wi-Fi interface has no IPv4 address")
    }
}

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
            AndroidPokerFrameSocket(socket)
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

private class AndroidPokerFrameSocket(
    private val socket: SSLSocket,
) : PokerFrameSocket {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    private val sendLock = Any()

    override suspend fun sendFrame(frame: ByteArray) = withContext(Dispatchers.IO) {
        require(frame.isNotEmpty() && frame.size <= DEFAULT_MAX_FRAME_BYTES) {
            "Poker frame size is invalid"
        }
        synchronized(sendLock) {
            output.writeInt(frame.size)
            output.write(frame)
            output.flush()
        }
    }

    override suspend fun receiveFrame(): ByteArray? = withContext(Dispatchers.IO) {
        val size = try {
            input.readInt()
        } catch (_: EOFException) {
            return@withContext null
        }
        require(size in 1..DEFAULT_MAX_FRAME_BYTES) { "Poker frame size is invalid" }
        ByteArray(size).also(input::readFully)
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

class CoroutinePokerScheduler(
    private val scope: CoroutineScope,
) : PokerScheduler {
    override fun schedule(delayMs: Long, task: () -> Unit): PokerScheduledTask {
        require(delayMs >= 0) { "Poker schedule delay must not be negative" }
        val job = scope.launch {
            delay(delayMs)
            task()
        }
        return PokerScheduledTask { job.cancel() }
    }
}
