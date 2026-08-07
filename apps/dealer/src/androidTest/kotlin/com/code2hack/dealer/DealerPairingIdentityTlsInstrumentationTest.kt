package com.code2hack.dealer

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DealerPairingIdentityTlsInstrumentationTest {
    @Test
    fun explicitEnrollmentKeySupportsPakeAndTlsEcdsaDigests() {
        val alias = "poker-dealer-dealer-tls-instrumentation"
        val identity = AndroidKeystorePairingIdentity(alias)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        runCatching { keyStore.deleteEntry(alias) }
        try {
            identity.createForExplicitEnrollment()
            val privateKey = identity.keyStoreForTls().getKey(alias, null) as PrivateKey

            val tlsSignature = Signature.getInstance("NONEwithECDSA").run {
                initSign(privateKey)
                update(ByteArray(32) { it.toByte() })
                sign()
            }
            assertTrue(tlsSignature.isNotEmpty())

            val pakeSignature = identity.sign("pake-proof".toByteArray())
            assertTrue(pakeSignature.isNotEmpty())
        } finally {
            runCatching { keyStore.deleteEntry(alias) }
        }
    }
}
