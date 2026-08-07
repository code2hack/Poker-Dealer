package com.code2hack.pokerdealer.protocol

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val POKER_BOND_BOOTSTRAP_PROTOCOL_VERSION = 1
const val POKER_BOND_BOOTSTRAP_SERVICE_NAME = "Poker-Dealer Bootstrap"
const val POKER_BOND_BOOTSTRAP_SERVICE_UUID = "42db7e5f-7b7b-4f7d-a0b4-39817d33a001"
const val POKER_BOND_BOOTSTRAP_MTLS_CAPABILITY = "wifi-mtls-v1"

private const val POKER_BOND_BOOTSTRAP_NONCE_BYTES = 32
private const val POKER_BOND_BOOTSTRAP_MAX_FRAME_BYTES = 64 * 1024
private const val POKER_BOND_BOOTSTRAP_MAX_PUBLIC_KEY_BYTES = 4 * 1024
private const val POKER_BOND_BOOTSTRAP_MAX_SIGNATURE_BYTES = 4 * 1024
private const val POKER_BOND_BOOTSTRAP_MAX_CAPABILITIES = 32

@Serializable
data class PokerBondBootstrapHello(
    val version: Int = POKER_BOND_BOOTSTRAP_PROTOCOL_VERSION,
    val dealerPublicKey: ByteArray,
    val dealerNonce: ByteArray,
)

@Serializable
data class PokerBondBootstrapOffer(
    val version: Int = POKER_BOND_BOOTSTRAP_PROTOCOL_VERSION,
    val pokerPublicKey: ByteArray,
    val pokerNonce: ByteArray,
    val endpoint: PokerHotspotEndpoint,
    val capabilities: Set<String> = setOf(POKER_BOND_BOOTSTRAP_MTLS_CAPABILITY),
    val pokerSignature: ByteArray,
)

@Serializable
data class PokerBondBootstrapConfirmation(
    val dealerSignature: ByteArray,
)

@Serializable
data class PokerBondBootstrapAck(
    val accepted: Boolean,
)

object PokerBondBootstrapProtocol {
    fun createHello(
        identity: PokerPairingIdentity,
        random: SecureRandom = SecureRandom(),
    ): PokerBondBootstrapHello = PokerBondBootstrapHello(
        dealerPublicKey = identity.publicKey.copyOf(),
        dealerNonce = ByteArray(POKER_BOND_BOOTSTRAP_NONCE_BYTES).also(random::nextBytes),
    ).also(::validateHello)

    fun createOffer(
        identity: PokerPairingIdentity,
        hello: PokerBondBootstrapHello,
        endpoint: PokerHotspotEndpoint,
        capabilities: Set<String> = setOf(POKER_BOND_BOOTSTRAP_MTLS_CAPABILITY),
        random: SecureRandom = SecureRandom(),
    ): PokerBondBootstrapOffer {
        validateHello(hello)
        val pokerPublicKey = identity.publicKey.copyOf()
        val pokerNonce = ByteArray(POKER_BOND_BOOTSTRAP_NONCE_BYTES).also(random::nextBytes)
        val normalizedCapabilities = capabilities.toSortedSet()
        validateCapabilities(normalizedCapabilities)
        val signature = identity.sign(
            proofPayload(
                signer = PokerPairingRole.POKER,
                hello = hello,
                pokerPublicKey = pokerPublicKey,
                pokerNonce = pokerNonce,
                endpoint = endpoint,
                capabilities = normalizedCapabilities,
            ),
        )
        return PokerBondBootstrapOffer(
            pokerPublicKey = pokerPublicKey,
            pokerNonce = pokerNonce,
            endpoint = endpoint,
            capabilities = normalizedCapabilities,
            pokerSignature = signature,
        ).also { validateOffer(hello, it) }
    }

    fun verifyOffer(
        hello: PokerBondBootstrapHello,
        offer: PokerBondBootstrapOffer,
    ): Boolean = runCatching {
        validateHello(hello)
        validateOfferShape(offer)
        offer.version == hello.version &&
            POKER_BOND_BOOTSTRAP_MTLS_CAPABILITY in offer.capabilities &&
            verifySignature(
                publicKey = offer.pokerPublicKey,
                payload = proofPayload(
                    signer = PokerPairingRole.POKER,
                    hello = hello,
                    pokerPublicKey = offer.pokerPublicKey,
                    pokerNonce = offer.pokerNonce,
                    endpoint = offer.endpoint,
                    capabilities = offer.capabilities,
                ),
                signature = offer.pokerSignature,
            )
    }.getOrDefault(false)

    fun createConfirmation(
        identity: PokerPairingIdentity,
        hello: PokerBondBootstrapHello,
        offer: PokerBondBootstrapOffer,
    ): PokerBondBootstrapConfirmation {
        require(verifyOffer(hello, offer)) { "Poker bootstrap offer is invalid" }
        return PokerBondBootstrapConfirmation(
            dealerSignature = identity.sign(
                proofPayload(
                    signer = PokerPairingRole.DEALER,
                    hello = hello,
                    pokerPublicKey = offer.pokerPublicKey,
                    pokerNonce = offer.pokerNonce,
                    endpoint = offer.endpoint,
                    capabilities = offer.capabilities,
                ),
            ),
        ).also(::validateConfirmation)
    }

    fun verifyConfirmation(
        hello: PokerBondBootstrapHello,
        offer: PokerBondBootstrapOffer,
        confirmation: PokerBondBootstrapConfirmation,
    ): Boolean = runCatching {
        validateHello(hello)
        validateOffer(hello, offer)
        validateConfirmation(confirmation)
        verifySignature(
            publicKey = hello.dealerPublicKey,
            payload = proofPayload(
                signer = PokerPairingRole.DEALER,
                hello = hello,
                pokerPublicKey = offer.pokerPublicKey,
                pokerNonce = offer.pokerNonce,
                endpoint = offer.endpoint,
                capabilities = offer.capabilities,
            ),
            signature = confirmation.dealerSignature,
        )
    }.getOrDefault(false)

    fun validateHello(hello: PokerBondBootstrapHello) {
        require(hello.version == POKER_BOND_BOOTSTRAP_PROTOCOL_VERSION) {
            "Unsupported Poker bootstrap protocol"
        }
        require(hello.dealerPublicKey.isNotEmpty() && hello.dealerPublicKey.size <= POKER_BOND_BOOTSTRAP_MAX_PUBLIC_KEY_BYTES) {
            "Invalid Dealer bootstrap public key"
        }
        require(hello.dealerNonce.size == POKER_BOND_BOOTSTRAP_NONCE_BYTES) {
            "Invalid Dealer bootstrap nonce"
        }
    }

    fun validateOffer(hello: PokerBondBootstrapHello, offer: PokerBondBootstrapOffer) {
        validateOfferShape(offer)
        require(offer.version == hello.version) { "Poker bootstrap version mismatch" }
        require(POKER_BOND_BOOTSTRAP_MTLS_CAPABILITY in offer.capabilities) {
            "Poker bootstrap does not support Wi-Fi mTLS"
        }
        require(verifyOffer(hello, offer)) { "Poker bootstrap signature is invalid" }
    }

    private fun validateOfferShape(offer: PokerBondBootstrapOffer) {
        require(offer.version == POKER_BOND_BOOTSTRAP_PROTOCOL_VERSION) {
            "Unsupported Poker bootstrap protocol"
        }
        require(offer.pokerPublicKey.isNotEmpty() && offer.pokerPublicKey.size <= POKER_BOND_BOOTSTRAP_MAX_PUBLIC_KEY_BYTES) {
            "Invalid Poker bootstrap public key"
        }
        require(offer.pokerNonce.size == POKER_BOND_BOOTSTRAP_NONCE_BYTES) {
            "Invalid Poker bootstrap nonce"
        }
        require(offer.pokerSignature.isNotEmpty() && offer.pokerSignature.size <= POKER_BOND_BOOTSTRAP_MAX_SIGNATURE_BYTES) {
            "Invalid Poker bootstrap signature"
        }
        validateCapabilities(offer.capabilities)
    }

    private fun validateConfirmation(confirmation: PokerBondBootstrapConfirmation) {
        require(
            confirmation.dealerSignature.isNotEmpty() &&
                confirmation.dealerSignature.size <= POKER_BOND_BOOTSTRAP_MAX_SIGNATURE_BYTES,
        ) { "Invalid Dealer bootstrap signature" }
    }

    private fun validateCapabilities(capabilities: Set<String>) {
        require(capabilities.isNotEmpty() && capabilities.size <= POKER_BOND_BOOTSTRAP_MAX_CAPABILITIES) {
            "Invalid Poker bootstrap capabilities"
        }
        require(capabilities.all { it.isNotBlank() && it.length <= 128 }) {
            "Invalid Poker bootstrap capability"
        }
    }

    private fun proofPayload(
        signer: PokerPairingRole,
        hello: PokerBondBootstrapHello,
        pokerPublicKey: ByteArray,
        pokerNonce: ByteArray,
        endpoint: PokerHotspotEndpoint,
        capabilities: Set<String>,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeUtf8("poker-dealer/bluetooth-bond-bootstrap/v1")
            output.writeUtf8(signer.name)
            output.writeInt(hello.version)
            output.writeBounded(hello.dealerPublicKey)
            output.writeBounded(hello.dealerNonce)
            output.writeBounded(pokerPublicKey)
            output.writeBounded(pokerNonce)
            output.writeUtf8(endpoint.host)
            output.writeInt(endpoint.port)
            val normalized = capabilities.toSortedSet()
            output.writeInt(normalized.size)
            normalized.forEach { capability -> output.writeUtf8(capability) }
        }
        bytes.toByteArray()
    }

    private fun verifySignature(
        publicKey: ByteArray,
        payload: ByteArray,
        signature: ByteArray,
    ): Boolean = runCatching {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(payload)
            verify(signature)
        }
    }.getOrDefault(false)

    private fun DataOutputStream.writeBounded(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun DataOutputStream.writeUtf8(value: String) =
        writeBounded(value.toByteArray(StandardCharsets.UTF_8))
}

object PokerBondBootstrapWire {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = true
    }

    fun writeHello(output: OutputStream, value: PokerBondBootstrapHello) =
        write(output, PokerBondBootstrapHello.serializer(), value)

    fun readHello(input: InputStream): PokerBondBootstrapHello =
        read(input, PokerBondBootstrapHello.serializer()).also(PokerBondBootstrapProtocol::validateHello)

    fun writeOffer(output: OutputStream, value: PokerBondBootstrapOffer) =
        write(output, PokerBondBootstrapOffer.serializer(), value)

    fun readOffer(input: InputStream): PokerBondBootstrapOffer =
        read(input, PokerBondBootstrapOffer.serializer())

    fun writeConfirmation(output: OutputStream, value: PokerBondBootstrapConfirmation) =
        write(output, PokerBondBootstrapConfirmation.serializer(), value)

    fun readConfirmation(input: InputStream): PokerBondBootstrapConfirmation =
        read(input, PokerBondBootstrapConfirmation.serializer())

    fun writeAck(output: OutputStream, value: PokerBondBootstrapAck) =
        write(output, PokerBondBootstrapAck.serializer(), value)

    fun readAck(input: InputStream): PokerBondBootstrapAck =
        read(input, PokerBondBootstrapAck.serializer())

    private fun <T> write(output: OutputStream, serializer: KSerializer<T>, value: T) {
        val payload = json.encodeToString(serializer, value).toByteArray(StandardCharsets.UTF_8)
        require(payload.isNotEmpty() && payload.size <= POKER_BOND_BOOTSTRAP_MAX_FRAME_BYTES) {
            "Poker bootstrap frame is too large"
        }
        DataOutputStream(output).apply {
            writeInt(payload.size)
            write(payload)
            flush()
        }
    }

    private fun <T> read(input: InputStream, serializer: KSerializer<T>): T {
        val stream = DataInputStream(input)
        val size = stream.readInt()
        require(size in 1..POKER_BOND_BOOTSTRAP_MAX_FRAME_BYTES) {
            "Invalid Poker bootstrap frame length"
        }
        val payload = ByteArray(size)
        stream.readFully(payload)
        return json.decodeFromString(serializer, payload.toString(StandardCharsets.UTF_8))
    }
}
