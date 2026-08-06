package com.code2hack.pokerdealer.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.code2hack.pokerdealer.domain.ComposerDraft
import com.code2hack.pokerdealer.domain.ComposerEditTarget
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ServerRequestLocator
import com.code2hack.pokerdealer.domain.UserInputAnswerBuffer
import com.code2hack.pokerdealer.domain.UserInputRequest

const val POKER_PROTOCOL_NAME = "poker-dealer"
const val POKER_PROTOCOL_MAJOR = 1
/** Kept as a source-compatible alias for the original envelope field. */
const val POKER_PROTOCOL_VERSION = POKER_PROTOCOL_MAJOR
const val DEFAULT_MAX_FRAME_BYTES = 4_096
const val DEFAULT_TEXT_CHUNK_BYTES = 2_048
const val DEFAULT_POKER_SNAPSHOT_CHUNK_BYTES = 1_024
const val POKER_CONTROL_STREAM = "control"
const val POKER_PROTOCOL_OFFER_TYPE = "protocol.offer"
const val POKER_PROTOCOL_NEGOTIATED_TYPE = "protocol.negotiated"
const val POKER_HEARTBEAT_PING_TYPE = "heartbeat.ping"
const val POKER_HEARTBEAT_PONG_TYPE = "heartbeat.pong"
const val POKER_SNAPSHOT_CAPABILITY = "snapshot"
const val POKER_SNAPSHOT_STREAM = "snapshot"
const val POKER_SNAPSHOT_REQUEST_TYPE = "snapshot.request"
const val POKER_SNAPSHOT_BEGIN_TYPE = "snapshot.begin"
const val POKER_SNAPSHOT_CHUNK_TYPE = "snapshot.chunk"
const val POKER_SNAPSHOT_COMPLETE_TYPE = "snapshot.complete"
const val POKER_SNAPSHOT_ACK_TYPE = "snapshot.ack"
const val POKER_COMPOSER_DRAFT_PROJECTION_TYPE = "composer.projection"
const val POKER_COMPOSER_MUTATION_TYPE = "composer.mutation"
const val POKER_COMPOSER_MUTATION_RESULT_TYPE = "composer.mutation.result"
const val POKER_USER_INPUT_PROJECTION_TYPE = "user-input.projection"
const val POKER_USER_INPUT_MUTATION_TYPE = "user-input.mutation"
const val POKER_USER_INPUT_MUTATION_RESULT_TYPE = "user-input.mutation.result"
const val POKER_LISTENER_PORT = 39_817

val PokerProtocolJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
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
    @SerialName("epoch") val epoch: Long = 0,
    val stream: String = POKER_CONTROL_STREAM,
    val sequence: Long,
    @SerialName("reply_to") val replyTo: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    val payload: JsonObject,
)

@Serializable
data class ComposerDraftProjection(
    val locator: CodexThreadLocator,
    val draft: ComposerDraft,
    @SerialName("control_generation") val controlGeneration: Long,
    @SerialName("connection_epoch") val connectionEpoch: Long,
    @SerialName("mode_session") val modeSession: String = "",
)

@Serializable
enum class ComposerMutationKind {
    DELETE_THROUGH_NEXT_WORD,
}

@Serializable
data class ComposerMutationRequest(
    val target: ComposerEditTarget,
    val kind: ComposerMutationKind,
)

@Serializable
enum class ComposerMutationOutcome {
    ACKNOWLEDGED,
    REJECTED,
    UNCERTAIN,
}

@Serializable
data class ComposerMutationResult(
    val target: ComposerEditTarget,
    val outcome: ComposerMutationOutcome,
    val draft: ComposerDraft,
    val reason: String? = null,
)

@Serializable
data class UserInputRequestProjection(
    val request: UserInputRequest,
    val buffer: UserInputAnswerBuffer = UserInputAnswerBuffer(),
    @SerialName("card_id") val cardId: String = "",
    @SerialName("control_generation") val controlGeneration: Long,
    @SerialName("connection_epoch") val connectionEpoch: Long,
    @SerialName("mode_session") val modeSession: String,
)

@Serializable
data class UserInputAnswerMutationTarget(
    val locator: ServerRequestLocator,
    @SerialName("question_id") val questionId: String,
    @SerialName("answer_revision") val answerRevision: Long,
    @SerialName("control_generation") val controlGeneration: Long,
    @SerialName("connection_epoch") val connectionEpoch: Long,
    @SerialName("mode_session") val modeSession: String,
    @SerialName("operation_id") val operationId: String,
) {
    init {
        require(questionId.isNotBlank()) { "Question id must not be blank" }
        require(answerRevision >= 0) { "Answer revision must not be negative" }
        require(controlGeneration >= 0) { "Control generation must not be negative" }
        require(connectionEpoch >= 0) { "Connection epoch must not be negative" }
        require(modeSession.isNotBlank()) { "Mode session must not be blank" }
        require(operationId.isNotBlank()) { "Operation id must not be blank" }
    }
}

@Serializable
enum class UserInputAnswerMutationKind {
    SELECT_OPTION,
    SELECT_OTHER,
    SET_TEXT,
}

@Serializable
data class UserInputAnswerMutationRequest(
    val target: UserInputAnswerMutationTarget,
    val kind: UserInputAnswerMutationKind,
    val value: String? = null,
)

@Serializable
enum class UserInputAnswerMutationOutcome {
    ACKNOWLEDGED,
    REJECTED,
    UNCERTAIN,
}

@Serializable
data class UserInputAnswerMutationResult(
    val target: UserInputAnswerMutationTarget,
    val outcome: UserInputAnswerMutationOutcome,
    val buffer: UserInputAnswerBuffer,
    val reason: String? = null,
)

@Serializable
data class PokerProtocolOffer(
    @SerialName("major") val major: Int = POKER_PROTOCOL_MAJOR,
    val capabilities: Set<String> = emptySet(),
    @SerialName("required_capabilities") val requiredCapabilities: Set<String> = emptySet(),
) {
    init {
        require(major > 0) { "Protocol major must be positive" }
        require(capabilities.all(String::isNotBlank)) { "Protocol capabilities must not be blank" }
        require(requiredCapabilities.all(String::isNotBlank)) {
            "Required protocol capabilities must not be blank"
        }
    }
}

@Serializable
data class PokerProtocolNegotiationMessage(
    @SerialName("major") val major: Int,
    val capabilities: Set<String> = emptySet(),
    @SerialName("read_only") val readOnly: Boolean,
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
