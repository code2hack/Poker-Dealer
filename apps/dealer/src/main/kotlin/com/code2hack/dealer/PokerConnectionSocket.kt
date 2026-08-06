package com.code2hack.dealer

import com.code2hack.pokerdealer.protocol.LengthPrefixedPokerFrameSocket
import com.code2hack.pokerdealer.protocol.PokerConnectionConnector
import com.code2hack.pokerdealer.protocol.PokerFrameSocket
import com.code2hack.pokerdealer.protocol.PokerPairingController
import com.code2hack.pokerdealer.protocol.host.SocketDuplexByteStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PokerClientUnavailableException(message: String) : IOException(message)

/** Opens the paired Poker endpoint with the Dealer's pinned mutual-TLS identity. */
class AndroidPokerClientConnector(
    private val identity: AndroidKeystorePairingIdentity,
    private val pairing: PokerPairingController,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
) : PokerConnectionConnector {
    init {
        require(connectTimeoutMs > 0) { "Poker connect timeout must be positive" }
    }

    override suspend fun connect(): PokerFrameSocket {
        val endpoint = pairing.endpoint
            ?: throw PokerClientUnavailableException("Poker endpoint is unavailable")
        val peerPublicKey = pairing.pinnedPeerPublicKey
            ?: throw PokerClientUnavailableException("Poker is not paired")
        val socket = try {
            identity.tlsContext(peerPublicKey).socketFactory.createSocket() as SSLSocket
        } catch (failure: Exception) {
            throw PokerClientUnavailableException("Unable to create the Poker TLS socket").also {
                it.initCause(failure)
            }
        }
        try {
            socket.cancellableIo {
                useClientMode = true
                connect(InetSocketAddress(endpoint.host, endpoint.port), connectTimeoutMs)
                startHandshake()
                val certificate = session.peerCertificates.firstOrNull() as? X509Certificate
                    ?: throw PokerClientUnavailableException("Poker certificate is unavailable")
                pairing.authenticatePeer(certificate.publicKey.encoded)
            }
            return LengthPrefixedPokerFrameSocket(SocketDuplexByteStream(socket), socket::close)
        } catch (cancellation: CancellationException) {
            runCatching { socket.close() }
            throw cancellation
        } catch (failure: Exception) {
            runCatching { socket.close() }
            if (failure is PokerClientUnavailableException) throw failure
            throw PokerClientUnavailableException("Unable to connect to Poker").also {
                it.initCause(failure)
            }
        }
    }

    private suspend fun <T> SSLSocket.cancellableIo(operation: SSLSocket.() -> T): T =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { runCatching { close() } }
                try {
                    val result = operation()
                    if (continuation.isActive) continuation.resume(result)
                } catch (failure: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
    }
}
