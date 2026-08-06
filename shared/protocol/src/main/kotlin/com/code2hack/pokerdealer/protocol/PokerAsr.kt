package com.code2hack.pokerdealer.protocol

import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ServerRequestLocator
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

const val POKER_ASR_AVAILABILITY_TYPE = "asr.availability"
const val POKER_ASR_START_TYPE = "asr.start"
const val POKER_ASR_START_RESULT_TYPE = "asr.start.result"
const val POKER_ASR_AUDIO_TYPE = "asr.audio"
const val POKER_ASR_PROJECTION_TYPE = "asr.projection"
const val POKER_ASR_COMMIT_TYPE = "asr.commit"
const val POKER_ASR_COMMIT_RESULT_TYPE = "asr.commit.result"
const val POKER_ASR_DISCARD_TYPE = "asr.discard"
const val POKER_ASR_DISCARD_RESULT_TYPE = "asr.discard.result"
const val POKER_ASR_EXIT_TYPE = "asr.exit"
const val POKER_ASR_EXIT_RESULT_TYPE = "asr.exit.result"
const val POKER_ASR_MAX_AUDIO_BYTES = 2_048
const val POKER_ASR_MAX_AUDIO_QUEUE_BYTES = 64 * 1024

@Serializable
enum class PokerAsrTargetField {
    COMPOSER,
    REQUEST_TEXT,
}

@Serializable
enum class PokerAsrSource {
    GLASSES,
    DEALER_PHONE,
}

@Serializable
data class PokerAsrTarget(
    val locator: CodexThreadLocator,
    val field: PokerAsrTargetField,
    @SerialName("request_locator") val requestLocator: ServerRequestLocator? = null,
    @SerialName("question_id") val questionId: String? = null,
    @SerialName("target_revision") val targetRevision: Long,
    @SerialName("cursor_position") val cursorPosition: Int,
    @SerialName("control_generation") val controlGeneration: Long,
    @SerialName("connection_epoch") val connectionEpoch: Long,
    @SerialName("mode_session") val modeSession: String,
) {
    init {
        require(targetRevision >= 0) { "ASR target revision must not be negative" }
        require(cursorPosition >= 0) { "ASR cursor position must not be negative" }
        require(controlGeneration >= 0) { "ASR control generation must not be negative" }
        require(connectionEpoch >= 0) { "ASR connection epoch must not be negative" }
        require(modeSession.isNotBlank()) { "ASR mode session must not be blank" }
        when (field) {
            PokerAsrTargetField.COMPOSER -> require(requestLocator == null && questionId == null) {
                "Composer ASR target cannot include a request"
            }
            PokerAsrTargetField.REQUEST_TEXT -> {
                require(requestLocator != null) { "Request ASR target requires a request locator" }
                require(!questionId.isNullOrBlank()) { "Request ASR target requires a question id" }
            }
        }
    }
}

@Serializable
data class PokerAsrPackSelection(
    @SerialName("pack_id") val packId: String,
    val revision: String,
    val profile: JsonObject,
) {
    init {
        require(packId.isNotBlank()) { "ASR pack id must not be blank" }
        require(revision.isNotBlank()) { "ASR pack revision must not be blank" }
    }
}

@Serializable
data class PokerAsrAvailability(
    val available: Boolean,
    val pack: PokerAsrPackSelection? = null,
    val reason: String? = null,
) {
    init {
        require(available == (pack != null)) { "ASR availability and pack must agree" }
        require(reason == null || reason.isNotBlank()) { "ASR availability reason must not be blank" }
    }
}

@Serializable
data class PokerAsrStartRequest(
    val target: PokerAsrTarget,
    @SerialName("session_id") val sessionId: String,
    val source: PokerAsrSource = PokerAsrSource.GLASSES,
) {
    init {
        require(sessionId.isNotBlank()) { "ASR session id must not be blank" }
    }
}

@Serializable
enum class PokerAsrStartOutcome {
    READY,
    REJECTED,
    CANCELLED,
}

@Serializable
data class PokerAsrStartResult(
    val target: PokerAsrTarget,
    @SerialName("session_id") val sessionId: String,
    val outcome: PokerAsrStartOutcome,
    val pack: PokerAsrPackSelection? = null,
    val reason: String? = null,
    val source: PokerAsrSource = PokerAsrSource.GLASSES,
)

@Serializable
data class PokerAsrAudioFrame(
    @SerialName("session_id") val sessionId: String,
    @SerialName("first_sample_offset") val firstSampleOffset: Long,
    @SerialName("pcm16_base64") val pcm16Base64: String,
) {
    init {
        require(sessionId.isNotBlank()) { "ASR audio session id must not be blank" }
        require(firstSampleOffset >= 0) { "ASR audio offset must not be negative" }
        require(pcm16Base64.isNotBlank()) { "ASR audio payload must not be blank" }
    }

    fun decodePcm16(): ByteArray {
        val bytes = runCatching { Base64.getDecoder().decode(pcm16Base64) }
            .getOrElse { throw IllegalArgumentException("ASR audio is not valid base64") }
        require(bytes.isNotEmpty() && bytes.size <= POKER_ASR_MAX_AUDIO_BYTES) {
            "ASR audio payload is outside the bounded frame size"
        }
        require(bytes.size % 2 == 0) { "ASR audio must contain complete PCM16 samples" }
        return bytes
    }
}

@Serializable
data class PokerAsrProjection(
    val target: PokerAsrTarget,
    @SerialName("session_id") val sessionId: String,
    @SerialName("slice_revision") val sliceRevision: Long,
    @SerialName("slice_text") val sliceText: String,
    @SerialName("sample_offset") val sampleOffset: Long,
) {
    init {
        require(sessionId.isNotBlank()) { "ASR projection session id must not be blank" }
        require(sliceRevision >= 0) { "ASR slice revision must not be negative" }
        require(sampleOffset >= 0) { "ASR projection offset must not be negative" }
    }
}

@Serializable
data class PokerAsrCommitRequest(
    val target: PokerAsrTarget,
    @SerialName("session_id") val sessionId: String,
    @SerialName("fence_sample_offset") val fenceSampleOffset: Long?,
    @SerialName("operation_id") val operationId: String,
) {
    init {
        require(sessionId.isNotBlank()) { "ASR commit session id must not be blank" }
        require(fenceSampleOffset == null || fenceSampleOffset >= 0) {
            "ASR commit fence must not be negative"
        }
        require(operationId.isNotBlank()) { "ASR operation id must not be blank" }
    }
}

@Serializable
enum class PokerAsrMutationOutcome {
    ACKNOWLEDGED,
    REJECTED,
    UNCERTAIN,
}

@Serializable
data class PokerAsrCommitResult(
    val target: PokerAsrTarget,
    @SerialName("session_id") val sessionId: String,
    @SerialName("operation_id") val operationId: String,
    val outcome: PokerAsrMutationOutcome,
    @SerialName("committed_text") val committedText: String = "",
    @SerialName("next_target") val nextTarget: PokerAsrTarget? = null,
    val reason: String? = null,
)

@Serializable
enum class PokerAsrDiscardKind {
    CURRENT_SLICE,
    LAST_COMMITTED_SLICE,
}

@Serializable
data class PokerAsrDiscardRequest(
    val target: PokerAsrTarget,
    @SerialName("session_id") val sessionId: String,
    @SerialName("operation_id") val operationId: String,
    @SerialName("fence_sample_offset") val fenceSampleOffset: Long = 0L,
    val kind: PokerAsrDiscardKind = PokerAsrDiscardKind.CURRENT_SLICE,
    @SerialName("delete_start") val deleteStart: Int? = null,
    @SerialName("delete_end_exclusive") val deleteEndExclusive: Int? = null,
    @SerialName("expected_text") val expectedText: String? = null,
) {
    init {
        require(sessionId.isNotBlank()) { "ASR discard session id must not be blank" }
        require(operationId.isNotBlank()) { "ASR operation id must not be blank" }
        require(fenceSampleOffset >= 0) { "ASR discard fence must not be negative" }
        when (kind) {
            PokerAsrDiscardKind.CURRENT_SLICE -> require(
                deleteStart == null && deleteEndExclusive == null && expectedText == null,
            ) { "Current-slice discard cannot include a committed range" }
            PokerAsrDiscardKind.LAST_COMMITTED_SLICE -> {
                require(deleteStart != null && deleteStart >= 0) {
                    "ASR committed-slice delete start must be nonnegative"
                }
                require(deleteEndExclusive != null && deleteEndExclusive > deleteStart) {
                    "ASR committed-slice delete range must be nonempty"
                }
                require(!expectedText.isNullOrEmpty()) {
                    "ASR committed-slice delete text must not be empty"
                }
            }
        }
    }
}

@Serializable
data class PokerAsrDiscardResult(
    val target: PokerAsrTarget,
    @SerialName("session_id") val sessionId: String,
    @SerialName("operation_id") val operationId: String,
    val outcome: PokerAsrMutationOutcome,
    @SerialName("next_target") val nextTarget: PokerAsrTarget? = null,
    val reason: String? = null,
)

@Serializable
data class PokerAsrExitRequest(
    val target: PokerAsrTarget,
    @SerialName("session_id") val sessionId: String,
    @SerialName("operation_id") val operationId: String,
)

@Serializable
data class PokerAsrExitResult(
    val target: PokerAsrTarget,
    @SerialName("session_id") val sessionId: String,
    @SerialName("operation_id") val operationId: String,
    val outcome: PokerAsrMutationOutcome,
    val reason: String? = null,
)
