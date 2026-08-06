package com.code2hack.pokerdealer.protocol

import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ComposerDraft
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val POKER_PHOTO_CAPABILITY = "photo.v1"
const val POKER_PHOTO_STREAM = "photo"
const val POKER_PHOTO_START_TYPE = "photo.start"
const val POKER_PHOTO_START_RESULT_TYPE = "photo.start.result"
const val POKER_PHOTO_CAPTURE_BEGIN_TYPE = "photo.capture.begin"
const val POKER_PHOTO_CAPTURE_CHUNK_TYPE = "photo.capture.chunk"
const val POKER_PHOTO_CAPTURE_COMPLETE_TYPE = "photo.capture.complete"
const val POKER_PHOTO_CAPTURE_RESULT_TYPE = "photo.capture.result"
const val POKER_PHOTO_DELETE_TYPE = "photo.delete"
const val POKER_PHOTO_DELETE_RESULT_TYPE = "photo.delete.result"
const val POKER_PHOTO_CANCEL_TYPE = "photo.cancel"
const val POKER_PHOTO_CHUNK_BYTES = 1_800

@Serializable
data class PhotoStartTarget(
    val locator: CodexThreadLocator,
    @SerialName("draft_revision") val draftRevision: Long,
    @SerialName("cursor_position") val cursorPosition: Int,
    @SerialName("control_generation") val controlGeneration: Long,
    @SerialName("connection_epoch") val connectionEpoch: Long,
    @SerialName("mode_session") val modeSession: String,
    @SerialName("session_id") val sessionId: String,
) {
    init {
        require(draftRevision >= 0) { "Photo draft revision must not be negative" }
        require(cursorPosition >= 0) { "Photo cursor position must not be negative" }
        require(controlGeneration >= 0) { "Photo control generation must not be negative" }
        require(connectionEpoch >= 0) { "Photo connection epoch must not be negative" }
        require(modeSession.isNotBlank()) { "Photo mode session must not be blank" }
        require(sessionId.isNotBlank()) { "Photo session id must not be blank" }
    }
}

@Serializable
enum class PhotoStartOutcome {
    ACCEPTED,
    REJECTED,
}

@Serializable
data class PhotoStartResult(
    val target: PhotoStartTarget,
    val outcome: PhotoStartOutcome,
    val reason: String? = null,
)

@Serializable
data class PhotoAssetTarget(
    val locator: CodexThreadLocator,
    @SerialName("session_id") val sessionId: String,
    @SerialName("asset_id") val assetId: String,
    @SerialName("draft_revision") val draftRevision: Long,
    @SerialName("cursor_position") val cursorPosition: Int,
    @SerialName("control_generation") val controlGeneration: Long,
    @SerialName("connection_epoch") val connectionEpoch: Long,
    @SerialName("mode_session") val modeSession: String,
    @SerialName("operation_id") val operationId: String,
) {
    init {
        require(sessionId.isNotBlank()) { "Photo session id must not be blank" }
        require(assetId.isNotBlank()) { "Photo asset id must not be blank" }
        require(draftRevision >= 0) { "Photo draft revision must not be negative" }
        require(cursorPosition >= 0) { "Photo cursor position must not be negative" }
        require(controlGeneration >= 0) { "Photo control generation must not be negative" }
        require(connectionEpoch >= 0) { "Photo connection epoch must not be negative" }
        require(modeSession.isNotBlank()) { "Photo mode session must not be blank" }
        require(operationId.isNotBlank()) { "Photo operation id must not be blank" }
    }
}

@Serializable
data class PhotoCaptureBegin(
    val target: PhotoAssetTarget,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("expected_length") val expectedLength: Long,
) {
    init {
        require(mimeType.isNotBlank()) { "Photo MIME type must not be blank" }
        require(expectedLength >= 0) { "Photo length must not be negative" }
    }
}

@Serializable
data class PhotoCaptureChunk(
    val target: PhotoAssetTarget,
    val offset: Long,
    val data: String,
) {
    init {
        require(offset >= 0) { "Photo chunk offset must not be negative" }
        require(data.isNotEmpty()) { "Photo chunk data must not be empty" }
    }
}

@Serializable
data class PhotoCaptureComplete(
    val target: PhotoAssetTarget,
    val length: Long,
    @SerialName("sha256") val sha256: String,
) {
    init {
        require(length >= 0) { "Photo length must not be negative" }
        require(sha256.isNotBlank()) { "Photo digest must not be blank" }
    }
}

@Serializable
enum class PhotoCaptureOutcome {
    ACKNOWLEDGED,
    REJECTED,
    UNCERTAIN,
}

@Serializable
data class PhotoCaptureResult(
    val target: PhotoAssetTarget,
    val outcome: PhotoCaptureOutcome,
    val draft: ComposerDraft,
    val reason: String? = null,
)

@Serializable
data class PhotoDeleteResult(
    val target: PhotoAssetTarget,
    val outcome: PhotoCaptureOutcome,
    val draft: ComposerDraft,
    val reason: String? = null,
)

object PhotoAssetCodec {
    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun decode(encoded: String): ByteArray = Base64.getDecoder().decode(encoded)

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun dataUrl(mimeType: String, bytes: ByteArray): String =
        "data:$mimeType;base64,${encode(bytes)}"

    fun chunks(bytes: ByteArray, chunkBytes: Int = POKER_PHOTO_CHUNK_BYTES): List<ByteArray> {
        require(chunkBytes > 0) { "Photo chunk size must be positive" }
        return bytes.asList().chunked(chunkBytes).map { it.toByteArray() }
    }
}
