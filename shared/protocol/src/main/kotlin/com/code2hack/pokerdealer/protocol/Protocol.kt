package com.code2hack.pokerdealer.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.code2hack.pokerdealer.domain.ComposerDraft
import com.code2hack.pokerdealer.domain.ComposerEditTarget
import com.code2hack.pokerdealer.domain.CommandApprovalDecision
import com.code2hack.pokerdealer.domain.CommandApprovalRequest
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.MorseMutationKind
import com.code2hack.pokerdealer.domain.MorseMutationOutcome
import com.code2hack.pokerdealer.domain.MorseMutationTarget
import com.code2hack.pokerdealer.domain.MorseModeTarget
import com.code2hack.pokerdealer.domain.FileApprovalDecision
import com.code2hack.pokerdealer.domain.FileApprovalRequest
import com.code2hack.pokerdealer.domain.FileChangeContent
import com.code2hack.pokerdealer.domain.PokerPrimaryAction
import com.code2hack.pokerdealer.domain.RequestResolutionState
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
const val POKER_LIVE_DELTA_CAPABILITY = "live-delta"
const val POKER_LIVE_DELTA_STREAM = "live"
const val POKER_LIVE_DELTA_TYPE = "card.delta"
const val POKER_LIVE_DELTA_ACK_TYPE = "card.delta.ack"
const val POKER_LIVE_DELTA_RECOVERY_TIMEOUT_MS = 5_000L
const val POKER_COMPOSER_DRAFT_PROJECTION_TYPE = "composer.projection"
const val POKER_COMPOSER_MUTATION_TYPE = "composer.mutation"
const val POKER_COMPOSER_MUTATION_RESULT_TYPE = "composer.mutation.result"
const val POKER_USER_INPUT_PROJECTION_TYPE = "user-input.projection"
const val POKER_USER_INPUT_MUTATION_TYPE = "user-input.mutation"
const val POKER_USER_INPUT_MUTATION_RESULT_TYPE = "user-input.mutation.result"
const val POKER_MORSE_CAPABILITY = "morse.v1"
const val POKER_MORSE_MUTATION_TYPE = "morse.mutation"
const val POKER_MORSE_MUTATION_RESULT_TYPE = "morse.mutation.result"
const val POKER_MORSE_COMPLETION_REQUEST_TYPE = "morse.completion.request"
const val POKER_MORSE_COMPLETION_PROJECTION_TYPE = "morse.completion.projection"
const val POKER_APPROVAL_PROJECTION_TYPE = "approval.projection"
const val POKER_PRIMARY_ACTION_CAPABILITY = "primary-action.v1"
const val POKER_PRIMARY_ACTION_TYPE = "primary.action"
const val POKER_PRIMARY_ACTION_RESULT_TYPE = "primary.action.result"
const val POKER_LISTENER_PORT = 8_341
const val POKER_BINDINGS_CAPABILITY = "bindings.v1"

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
    @SerialName("active_turn_id") val activeTurnId: String? = null,
    @SerialName("has_dealer_claim") val hasDealerClaim: Boolean = true,
)

@Serializable
enum class PokerApprovalKind {
    COMMAND,
    FILE_CHANGE,
}

@Serializable
enum class PokerApprovalDecision(val wireName: String) {
    ACCEPT("accept"),
    ACCEPT_FOR_SESSION("acceptForSession"),
    ACCEPT_WITH_EXECPOLICY_AMENDMENT("acceptWithExecpolicyAmendment"),
    DECLINE("decline"),
    CANCEL("cancel"),
}

@Serializable
data class PokerApprovalScopeProjection(
    val command: String? = null,
    val workingDirectory: String? = null,
    val networkHost: String? = null,
    val networkProtocol: String? = null,
    val reason: String? = null,
    val grantRoot: String? = null,
    val fileChanges: List<FileChangeContent> = emptyList(),
)

@Serializable
data class PokerApprovalRequestProjection(
    val locator: ServerRequestLocator,
    val thread: CodexThreadLocator,
    val turnId: String,
    val itemId: String,
    @SerialName("card_id") val cardId: String = "",
    val kind: PokerApprovalKind,
    val scope: PokerApprovalScopeProjection,
    @SerialName("proposed_execpolicy_amendment")
    val proposedExecpolicyAmendment: List<String>? = null,
    val choices: List<PokerApprovalDecision> = emptyList(),
    val fingerprint: String,
    val complete: Boolean,
    val actionable: Boolean,
    val resolution: RequestResolutionState,
    val decision: PokerApprovalDecision? = null,
    val resolvedElsewhere: Boolean = false,
    @SerialName("control_generation") val controlGeneration: Long,
    @SerialName("connection_epoch") val connectionEpoch: Long,
    @SerialName("mode_session") val modeSession: String,
    @SerialName("has_dealer_claim") val hasDealerClaim: Boolean = true,
) {
    val panelId: String
        get() = "approval:${kind.name.lowercase()}:${locator.requestId}"
}

fun CommandApprovalRequest.toPokerApprovalProjection(
    controlGeneration: Long,
    connectionEpoch: Long,
    modeSession: String,
    hasDealerClaim: Boolean,
): PokerApprovalRequestProjection {
    val networkComplete = !scope.networkHost.isNullOrBlank() && !scope.networkProtocol.isNullOrBlank()
    val commandComplete = !scope.command.isNullOrBlank()
    val complete = (commandComplete || networkComplete) &&
        (scope.networkHost == null && scope.networkProtocol == null || networkComplete)
    val choices = offeredDecisionOrder.ifEmpty {
        CommandApprovalDecision.entries.filter { it in offeredDecisions }
    }.map(CommandApprovalDecision::toPokerApprovalDecision)
    return PokerApprovalRequestProjection(
        locator = locator,
        thread = thread,
        turnId = turnId,
        itemId = itemId,
        cardId = itemId,
        kind = PokerApprovalKind.COMMAND,
        scope = PokerApprovalScopeProjection(
            command = scope.command,
            workingDirectory = scope.workingDirectory,
            networkHost = scope.networkHost,
            networkProtocol = scope.networkProtocol,
        ),
        proposedExecpolicyAmendment = proposedExecpolicyAmendment,
        choices = choices,
        fingerprint = fingerprint,
        complete = complete,
        actionable = complete && commandApprovalIsSafe(scope) && choices.isNotEmpty(),
        resolution = resolution,
        decision = decision?.toPokerApprovalDecision(),
        resolvedElsewhere = resolvedElsewhere,
        controlGeneration = controlGeneration,
        connectionEpoch = connectionEpoch,
        modeSession = modeSession,
        hasDealerClaim = hasDealerClaim,
    )
}

fun FileApprovalRequest.toPokerApprovalProjection(
    controlGeneration: Long,
    connectionEpoch: Long,
    modeSession: String,
    hasDealerClaim: Boolean,
): PokerApprovalRequestProjection {
    val complete = reviewComplete && fileChanges.isNotEmpty() &&
        fileChanges.all { it.path.isNotBlank() && it.kind.isNotBlank() }
    return PokerApprovalRequestProjection(
        locator = locator,
        thread = thread,
        turnId = turnId,
        itemId = itemId,
        cardId = itemId,
        kind = PokerApprovalKind.FILE_CHANGE,
        scope = PokerApprovalScopeProjection(
            reason = reason,
            grantRoot = grantRoot,
            fileChanges = fileChanges,
        ),
        choices = FileApprovalDecision.entries.map(FileApprovalDecision::toPokerApprovalDecision),
        fingerprint = fingerprint,
        complete = complete,
        actionable = complete && hasSafeFileScope(fileChanges),
        resolution = resolution,
        decision = decision?.toPokerApprovalDecision(),
        resolvedElsewhere = resolvedElsewhere,
        controlGeneration = controlGeneration,
        connectionEpoch = connectionEpoch,
        modeSession = modeSession,
        hasDealerClaim = hasDealerClaim,
    )
}

fun PokerApprovalDecision.toCommandApprovalDecision(): CommandApprovalDecision =
    CommandApprovalDecision.entries.first { it.wireName == wireName }

fun PokerApprovalDecision.toFileApprovalDecision(): FileApprovalDecision =
    FileApprovalDecision.entries.first { it.wireName == wireName }

private fun CommandApprovalDecision.toPokerApprovalDecision(): PokerApprovalDecision =
    PokerApprovalDecision.entries.first { it.wireName == wireName }

private fun FileApprovalDecision.toPokerApprovalDecision(): PokerApprovalDecision =
    PokerApprovalDecision.entries.first { it.wireName == wireName }

private fun commandApprovalIsSafe(scope: com.code2hack.pokerdealer.domain.CommandApprovalScope): Boolean {
    val command = scope.command
    if (command != null) {
        // ponytail: conservative string gate until app-server exposes structured risk metadata.
        val dangerous = Regex(
            "(^|[;&|<>]|\\$\\(|`)\\s*(sudo|rm|rmdir|dd|mkfs|shutdown|reboot|poweroff|kill|pkill|chmod|chown|git\\s+(reset|clean)|curl|wget|ssh)\\b",
            RegexOption.IGNORE_CASE,
        )
        if (dangerous.containsMatchIn(command) || command.any { it in ";&|<>*" } ||
            command.contains("$(") || command.contains("`")) return false
        return command.isNotBlank()
    }
    val host = scope.networkHost ?: return false
    return host.isNotBlank() && !host.contains('*') && !host.contains('/') &&
        !host.any(Char::isWhitespace) && !scope.networkProtocol.isNullOrBlank()
}

private fun hasSafeFileScope(changes: List<FileChangeContent>): Boolean =
    changes.none { change ->
        change.kind.contains("delete", ignoreCase = true) ||
            change.path == "/" || change.path == "*" || change.path.contains("../")
    }

@Serializable
data class PokerPrimaryActionTarget(
    val locator: CodexThreadLocator,
    val action: PokerPrimaryAction,
    @SerialName("wheel_session") val wheelSession: String,
    @SerialName("control_generation") val controlGeneration: Long,
    @SerialName("connection_epoch") val connectionEpoch: Long,
    @SerialName("mode_session") val modeSession: String,
    @SerialName("draft_revision") val draftRevision: Long? = null,
    @SerialName("cursor_position") val cursorPosition: Int? = null,
    @SerialName("expected_turn_id") val expectedTurnId: String? = null,
    @SerialName("request_locator") val requestLocator: ServerRequestLocator? = null,
    @SerialName("approval_decision") val approvalDecision: PokerApprovalDecision? = null,
    @SerialName("answer_revision") val answerRevision: Long? = null,
    @SerialName("request_fingerprint") val requestFingerprint: String? = null,
    @SerialName("operation_id") val operationId: String,
) {
    init {
        require(wheelSession.isNotBlank()) { "Wheel session must not be blank" }
        require(controlGeneration >= 0) { "Control generation must not be negative" }
        require(connectionEpoch >= 0) { "Connection epoch must not be negative" }
        require(modeSession.isNotBlank()) { "Mode session must not be blank" }
        require(operationId.isNotBlank()) { "Operation id must not be blank" }
        when (action) {
            PokerPrimaryAction.REQUEST -> {
                require(draftRevision == null && cursorPosition == null && expectedTurnId == null) {
                    "Request action cannot target a composer or turn"
                }
                require(requestLocator != null) { "Request action requires a request locator" }
                if (approvalDecision == null) {
                    require(answerRevision != null && answerRevision >= 0) {
                        "Request action requires an answer revision"
                    }
                } else {
                    require(answerRevision == null) {
                        "Approval action cannot target an answer revision"
                    }
                }
                require(!requestFingerprint.isNullOrBlank()) {
                    "Request action requires a request fingerprint"
                }
            }
            PokerPrimaryAction.SEND,
            PokerPrimaryAction.STEER,
            -> {
                require(requestLocator == null && approvalDecision == null && answerRevision == null && requestFingerprint == null) {
                    "Composer action cannot target a request"
                }
                require(draftRevision != null && draftRevision >= 0) {
                    "Composer action requires a draft revision"
                }
                require(cursorPosition != null && cursorPosition >= 0) {
                    "Composer action requires a cursor position"
                }
                if (action == PokerPrimaryAction.STEER) {
                    require(!expectedTurnId.isNullOrBlank()) {
                        "Steer requires an expected turn ID"
                    }
                } else {
                    require(expectedTurnId == null) { "Send cannot target an active turn" }
                }
            }
            PokerPrimaryAction.INTERRUPT -> {
                require(requestLocator == null && approvalDecision == null && draftRevision == null && cursorPosition == null) {
                    "Interrupt cannot target a request or draft"
                }
                require(answerRevision == null && requestFingerprint == null) {
                    "Interrupt cannot target a request answer"
                }
                require(!expectedTurnId.isNullOrBlank()) { "Interrupt requires an expected turn ID" }
            }
        }
    }
}

@Serializable
enum class PokerPrimaryActionOutcome {
    ACCEPTED,
    REJECTED,
    UNKNOWN,
}

@Serializable
data class PokerPrimaryActionResult(
    val target: PokerPrimaryActionTarget,
    val outcome: PokerPrimaryActionOutcome,
    val reason: String? = null,
)

@Serializable
enum class ComposerMutationKind {
    DELETE_THROUGH_NEXT_WORD,
    DELETE_PHOTO,
}

@Serializable
data class ComposerMutationRequest(
    val target: ComposerEditTarget,
    val kind: ComposerMutationKind,
    @SerialName("asset_id") val assetId: String? = null,
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
data class MorseMutationRequest(
    val target: MorseMutationTarget,
    val kind: MorseMutationKind,
    val text: String? = null,
    @SerialName("delete_start") val deleteStart: Int? = null,
    @SerialName("delete_end_exclusive") val deleteEndExclusive: Int? = null,
    @SerialName("expected_text") val expectedText: String? = null,
) {
    init {
        when (kind) {
            MorseMutationKind.COMMIT_WORD -> require(!text.isNullOrBlank()) {
                "Morse commit text must not be blank"
            }
            MorseMutationKind.DELETE_COMMITTED_WORD -> {
                require(text == null) { "Morse deletion cannot include text" }
                require(deleteStart != null && deleteEndExclusive != null) {
                    "Morse deletion requires a range"
                }
                require(deleteStart >= 0 && deleteStart < deleteEndExclusive) {
                    "Morse deletion range is invalid"
                }
                require(expectedText != null) { "Morse deletion requires expected text" }
            }
        }
    }
}

@Serializable
data class MorseMutationResult(
    val target: MorseMutationTarget,
    val outcome: MorseMutationOutcome,
    @SerialName("composer_draft") val composerDraft: ComposerDraft? = null,
    @SerialName("answer_buffer") val answerBuffer: UserInputAnswerBuffer? = null,
    @SerialName("field_revision") val fieldRevision: Long? = null,
    @SerialName("cursor_position") val cursorPosition: Int? = null,
    val reason: String? = null,
)

@Serializable
data class MorseCompletionRequest(
    val target: MorseModeTarget,
    val prefix: String,
)

@Serializable
data class MorseCompletionProjection(
    val target: MorseModeTarget,
    val prefix: String,
    val suffix: String? = null,
) {
    init {
        require(prefix.length >= 2) { "Morse completion prefix is too short" }
        require(prefix.all { it in 'a'..'z' || it in 'A'..'Z' }) {
            "Morse completion prefix must be Latin letters"
        }
        require(suffix == null || suffix.isNotEmpty() && suffix.all { it in 'a'..'z' || it in 'A'..'Z' }) {
            "Morse completion suffix must be Latin letters"
        }
    }
}

@Serializable
data class UserInputRequestProjection(
    val request: UserInputRequest,
    val buffer: UserInputAnswerBuffer = UserInputAnswerBuffer(),
    @SerialName("card_id") val cardId: String = "",
    @SerialName("control_generation") val controlGeneration: Long,
    @SerialName("connection_epoch") val connectionEpoch: Long,
    @SerialName("mode_session") val modeSession: String,
    @SerialName("has_dealer_claim") val hasDealerClaim: Boolean = true,
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
