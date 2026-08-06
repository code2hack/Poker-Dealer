package com.code2hack.pokerdealer.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

private const val MAX_PAIRING_FRAME_BYTES = 64 * 1024

@Serializable
data class PokerPairingWireMessage(
    val type: String,
    val payload: JsonObject,
) {
    override fun toString(): String = "PokerPairingWireMessage(type=$type, payload=<redacted>)"
}

/** Unauthenticated transport for the PAKE transcript; the transcript authenticates the peer. */
object PokerPairingWire {
    fun challenge(challenge: PokerPairingChallenge): PokerPairingWireMessage = message(
        POKER_PAIRING_CHALLENGE_TYPE,
        PokerPairingChallenge.serializer(),
        challenge,
    )

    fun response(response: PokerPairingResponse): PokerPairingWireMessage = message(
        POKER_PAIRING_RESPONSE_TYPE,
        PokerPairingResponse.serializer(),
        response,
    )

    fun confirmation(confirmation: PokerPairingConfirmation): PokerPairingWireMessage = message(
        POKER_PAIRING_CONFIRMATION_TYPE,
        PokerPairingConfirmation.serializer(),
        confirmation,
    )

    fun failure(reason: PokerPairingFailure, failedAttempts: Int): PokerPairingWireMessage =
        PokerPairingWireMessage(
            type = "pairing.failure",
            payload = PokerProtocolJson.encodeToJsonElement(
                PokerPairingFailurePayload.serializer(),
                PokerPairingFailurePayload(reason, failedAttempts),
            ).jsonObject,
        )

    fun decodeChallenge(message: PokerPairingWireMessage): PokerPairingChallenge {
        require(message.type == POKER_PAIRING_CHALLENGE_TYPE) { "Expected pairing challenge" }
        return PokerProtocolJson.decodeFromJsonElement(
            PokerPairingChallenge.serializer(),
            message.payload,
        )
    }

    fun decodeResponse(message: PokerPairingWireMessage): PokerPairingResponse {
        require(message.type == POKER_PAIRING_RESPONSE_TYPE) { "Expected pairing response" }
        return PokerProtocolJson.decodeFromJsonElement(
            PokerPairingResponse.serializer(),
            message.payload,
        )
    }

    fun decodeConfirmation(message: PokerPairingWireMessage): PokerPairingConfirmation {
        require(message.type == POKER_PAIRING_CONFIRMATION_TYPE) { "Expected pairing confirmation" }
        return PokerProtocolJson.decodeFromJsonElement(
            PokerPairingConfirmation.serializer(),
            message.payload,
        )
    }

    fun decodeFailure(message: PokerPairingWireMessage): PokerPairingFailurePayload {
        require(message.type == "pairing.failure") { "Expected pairing failure" }
        return PokerProtocolJson.decodeFromJsonElement(
            PokerPairingFailurePayload.serializer(),
            message.payload,
        )
    }

    fun encode(message: PokerPairingWireMessage): ByteArray = PokerProtocolJson
        .encodeToString(PokerPairingWireMessage.serializer(), message)
        .toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): PokerPairingWireMessage = PokerProtocolJson.decodeFromString(
        PokerPairingWireMessage.serializer(),
        bytes.toString(Charsets.UTF_8),
    )

    fun write(output: OutputStream, message: PokerPairingWireMessage) {
        val bytes = encode(message)
        require(bytes.size in 1..MAX_PAIRING_FRAME_BYTES) { "Pairing frame is too large" }
        DataOutputStream(output).apply {
            writeInt(bytes.size)
            write(bytes)
            flush()
        }
    }

    fun read(input: InputStream): PokerPairingWireMessage? {
        val data = DataInputStream(input)
        val size = try {
            data.readInt()
        } catch (_: EOFException) {
            return null
        }
        require(size in 1..MAX_PAIRING_FRAME_BYTES) { "Pairing frame size is invalid" }
        return ByteArray(size).also(data::readFully).let(::decode)
    }

    private fun <T> message(
        type: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        payload: T,
    ) = PokerPairingWireMessage(
        type = type,
        payload = PokerProtocolJson.encodeToJsonElement(serializer, payload).jsonObject,
    )
}

@Serializable
data class PokerPairingFailurePayload(
    val reason: PokerPairingFailure,
    val failedAttempts: Int,
) {
    override fun toString(): String =
        "PokerPairingFailurePayload(reason=$reason, failedAttempts=$failedAttempts)"
}
