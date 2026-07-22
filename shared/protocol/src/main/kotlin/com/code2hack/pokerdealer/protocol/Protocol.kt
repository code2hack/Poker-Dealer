package com.code2hack.pokerdealer.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

const val POKER_PROTOCOL_NAME = "poker-dealer"
const val POKER_PROTOCOL_VERSION = 1
const val DEFAULT_MAX_FRAME_BYTES = 4_096
const val DEFAULT_TEXT_CHUNK_BYTES = 2_048

val PokerProtocolJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    explicitNulls = true
}
@Serializable
data class ProtocolEnvelope(
    val protocol: String = POKER_PROTOCOL_NAME,
    val version: Int = POKER_PROTOCOL_VERSION,
    val type: String,
    @SerialName("message_id") val messageId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("sent_at_ms") val sentAtMs: Long,
    val sequence: Long,
    @SerialName("reply_to") val replyTo: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    val payload: JsonObject,
)

enum class PokerTransportState {
    DISABLED,
    CONNECTING,
    AUTHENTICATING,
    SYNCING,
    CONNECTED,
    BACKING_OFF,
    ERROR,
}

interface PokerTransport {
    val state: StateFlow<PokerTransportState>
    val incomingFrames: Flow<ByteArray>

    suspend fun connect()
    suspend fun disconnect()
    suspend fun send(frame: ByteArray)
}
