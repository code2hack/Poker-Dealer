package com.code2hack.dealer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.code2hack.pokerdealer.protocol.appserver.HostConnectionIntentStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.hostConnectionIntentDataStore by preferencesDataStore("host_connection_intent")

class HostConnectionIntentDataStore(
    private val context: Context,
) : HostConnectionIntentStore {
    override suspend fun readEnabledHostIds(): Set<String> =
        context.hostConnectionIntentDataStore.data
            .map { it[EnabledHosts].orEmpty() }
            .first()

    override suspend fun writeEnabledHostIds(hostIds: Set<String>) {
        context.hostConnectionIntentDataStore.edit { it[EnabledHosts] = hostIds }
    }

    private companion object {
        val EnabledHosts = stringSetPreferencesKey("enabled_host_ids")
    }
}

data class StoredHostConnection(
    val config: DealerHostConnectionConfig,
    val privateKey: ByteArray,
    val knownHosts: ByteArray,
)

class DealerHostConnectionProfileStore(
    private val context: Context,
) {
    suspend fun save(
        config: DealerHostConnectionConfig,
        privateKey: ByteArray,
        knownHosts: ByteArray,
    ) {
        require(privateKey.isNotEmpty())
        require(knownHosts.isNotEmpty())
        writeCredentials(config.hostId, privateKey, knownHosts)
        val profile = buildJsonObject {
            put("hostId", config.hostId)
            put("lanHost", config.lanHost)
            put("tailnetHost", config.tailnetHost)
            put("sshUser", config.sshUser)
            put("loopbackSshPort", config.loopbackSshPort)
        }.toString()
        context.hostConnectionIntentDataStore.edit {
            it[profileKey(config.hostId)] = profile
        }
    }

    suspend fun hasConfiguredTailnetRoute(hostId: String): Boolean =
        context.hostConnectionIntentDataStore.data
            .map { it[profileKey(hostId)] }
            .first()
            ?.let { raw ->
                Json.parseToJsonElement(raw).jsonObject["tailnetHost"]
                    ?.jsonPrimitive
                    ?.content
                    ?.isNotBlank() == true
            } == true

    suspend fun load(hostId: String): StoredHostConnection {
        val raw = context.hostConnectionIntentDataStore.data
            .map { it[profileKey(hostId)] }
            .first()
            ?: error("$hostId: connection settings unavailable")
        val profile = Json.parseToJsonElement(raw).jsonObject
        require(profile.getValue("hostId").jsonPrimitive.content == hostId)
        val (privateKey, knownHosts) = readCredentials(hostId)
        return StoredHostConnection(
            config = DealerHostConnectionConfig(
                hostId = hostId,
                lanHost = profile.getValue("lanHost").jsonPrimitive.content,
                tailnetHost = profile.getValue("tailnetHost").jsonPrimitive.content,
                sshUser = profile.getValue("sshUser").jsonPrimitive.content,
                loopbackSshPort = profile.getValue("loopbackSshPort").jsonPrimitive.content.toInt(),
            ),
            privateKey = privateKey,
            knownHosts = knownHosts,
        )
    }

    private fun writeCredentials(hostId: String, privateKey: ByteArray, knownHosts: ByteArray) {
        val clear = ByteBuffer.allocate(Int.SIZE_BYTES + privateKey.size + knownHosts.size)
            .putInt(privateKey.size)
            .put(privateKey)
            .put(knownHosts)
            .array()
        try {
            val cipher = Cipher.getInstance(CipherTransformation).apply {
                init(Cipher.ENCRYPT_MODE, encryptionKey())
            }
            val encrypted = cipher.doFinal(clear)
            val payload = ByteBuffer.allocate(1 + cipher.iv.size + encrypted.size)
                .put(cipher.iv.size.toByte())
                .put(cipher.iv)
                .put(encrypted)
                .array()
            val file = AtomicFile(credentialsFile(hostId))
            val output = file.startWrite()
            try {
                output.write(payload)
                file.finishWrite(output)
            } catch (failure: Throwable) {
                file.failWrite(output)
                throw failure
            }
        } finally {
            clear.fill(0)
        }
    }

    private fun readCredentials(hostId: String): Pair<ByteArray, ByteArray> {
        val payload = AtomicFile(credentialsFile(hostId)).readFully()
        require(payload.isNotEmpty())
        val ivSize = payload[0].toInt() and 0xFF
        require(ivSize in 12..16 && payload.size > 1 + ivSize)
        val clear = Cipher.getInstance(CipherTransformation).run {
            init(
                Cipher.DECRYPT_MODE,
                encryptionKey(),
                GCMParameterSpec(128, payload.copyOfRange(1, 1 + ivSize)),
            )
            doFinal(payload, 1 + ivSize, payload.size - 1 - ivSize)
        }
        try {
            val buffer = ByteBuffer.wrap(clear)
            val keySize = buffer.int
            require(keySize in 1 until clear.size - Int.SIZE_BYTES)
            val privateKey = ByteArray(keySize).also(buffer::get)
            val knownHosts = ByteArray(buffer.remaining()).also(buffer::get)
            return privateKey to knownHosts
        } finally {
            clear.fill(0)
        }
    }

    private fun credentialsFile(hostId: String) =
        context.filesDir.resolve("host-connection-${hostId.replace(Regex("[^a-zA-Z0-9._-]"), "_")}.bin")

    private fun profileKey(hostId: String) = stringPreferencesKey("profile.$hostId")

    private fun encryptionKey(): SecretKey = synchronized(KeyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KeyAlias, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KeyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }
            .generateKey()
    }

    private companion object {
        const val CipherTransformation = "AES/GCM/NoPadding"
        const val KeyAlias = "poker-dealer-host-connections"
        val KeyLock = Any()
    }
}
