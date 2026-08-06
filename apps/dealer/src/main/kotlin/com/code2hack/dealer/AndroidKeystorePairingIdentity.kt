package com.code2hack.dealer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.code2hack.pokerdealer.protocol.PairingKeyUnavailableException
import com.code2hack.pokerdealer.protocol.PinnedMutualTls
import com.code2hack.pokerdealer.protocol.FilePokerPairingStore
import com.code2hack.pokerdealer.protocol.PokerPairingController
import com.code2hack.pokerdealer.protocol.PokerPairingIdentity
import com.code2hack.pokerdealer.protocol.PokerPairingRole
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import javax.security.auth.x500.X500Principal

/** The private key never leaves Android Keystore; no recovery path silently regenerates it. */
class AndroidKeystorePairingIdentity(
    private val alias: String = DEFAULT_ALIAS,
) : PokerPairingIdentity {
    override val publicKey: ByteArray
        get() = load().certificate.publicKey.encoded

    override fun sign(payload: ByteArray): ByteArray = try {
        val privateKey = load().keyStore.getKey(alias, null) as? PrivateKey
            ?: throw PairingKeyUnavailableException()
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(payload)
            sign()
        }
    } catch (failure: PairingKeyUnavailableException) {
        throw failure
    } catch (failure: Exception) {
        throw PairingKeyUnavailableException(failure)
    }

    fun createForExplicitEnrollment() {
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(alias)) {
            load(keyStore)
            return
        }
        try {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEYSTORE,
            )
            val now = Calendar.getInstance()
            val notBefore = now.time
            now.add(Calendar.YEAR, 10)
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setCertificateSubject(X500Principal("CN=Poker-Dealer Dealer"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(notBefore)
                    .setCertificateNotAfter(now.time)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generator.generateKeyPair()
            load()
        } catch (failure: Exception) {
            throw PairingKeyUnavailableException(failure)
        }
    }

    fun keyStoreForTls(): KeyStore = load().keyStore

    fun tlsContext(peerPublicKey: ByteArray) =
        PinnedMutualTls.context(keyStoreForTls(), alias, peerPublicKey)

    fun pairingController(context: Context): PokerPairingController = PokerPairingController(
        role = PokerPairingRole.DEALER,
        identity = this,
        store = FilePokerPairingStore(context.noBackupFilesDir.resolve(PAIRING_STATE_FILE)),
    )

    private fun load(): LoadedIdentity = load(loadKeyStore())

    private fun load(keyStore: KeyStore): LoadedIdentity {
        return try {
            val key = keyStore.getKey(alias, null)
            val certificate = keyStore.getCertificate(alias) as? X509Certificate
            check(key is PrivateKey && certificate != null) {
                "Android Keystore pairing identity is unavailable"
            }
            LoadedIdentity(keyStore, certificate)
        } catch (failure: Exception) {
            if (failure is PairingKeyUnavailableException) throw failure
            throw PairingKeyUnavailableException(failure)
        }
    }

    private fun loadKeyStore(): KeyStore = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
    } catch (failure: Exception) {
        throw PairingKeyUnavailableException(failure)
    }

    private data class LoadedIdentity(
        val keyStore: KeyStore,
        val certificate: X509Certificate,
    )

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "poker-dealer-dealer-identity-v1"
        const val PAIRING_STATE_FILE = "poker-pairing.json"
    }
}
