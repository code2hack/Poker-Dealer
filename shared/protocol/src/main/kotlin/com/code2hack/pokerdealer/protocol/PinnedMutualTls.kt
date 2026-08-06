package com.code2hack.pokerdealer.protocol

import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.X509TrustManager

/** Trusts exactly one authenticated peer public key; the pairing record supplies the pin. */
class PinnedPublicKeyTrustManager(
    expectedPublicKey: ByteArray,
) : X509TrustManager {
    private val pinnedPublicKey = expectedPublicKey.copyOf()

    init {
        require(pinnedPublicKey.isNotEmpty()) { "Peer public-key pin must not be empty" }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = verify(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = verify(chain)

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun verify(chain: Array<out X509Certificate>) {
        val certificate = chain.firstOrNull()
            ?: throw CertificateException("Pairing mismatch")
        try {
            certificate.checkValidity()
        } catch (failure: Exception) {
            throw CertificateException("Pairing certificate is invalid", failure)
        }
        val actual = certificate.publicKey.encoded
        if (!MessageDigest.isEqual(pinnedPublicKey, actual)) {
            throw CertificateException("Pairing mismatch")
        }
    }
}

object PinnedMutualTls {
    fun context(
        localKeyStore: KeyStore,
        localKeyAlias: String,
        peerPublicKey: ByteArray,
    ): SSLContext {
        val localEntry = localKeyStore.getEntry(localKeyAlias, null)
        check(localEntry is KeyStore.PrivateKeyEntry && localEntry.privateKey is PrivateKey) {
            "Pairing identity is unavailable"
        }
        check(localKeyStore.getCertificate(localKeyAlias) is X509Certificate) {
            "Pairing certificate is unavailable"
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).run {
            init(localKeyStore, null)
            keyManagers
        }
        return SSLContext.getInstance("TLS").apply {
            init(
                keyManagers,
                arrayOf(PinnedPublicKeyTrustManager(peerPublicKey)),
                null,
            )
        }
    }

    fun requireClientAuthentication(serverSocket: SSLServerSocket) {
        serverSocket.needClientAuth = true
        serverSocket.wantClientAuth = false
    }
}
