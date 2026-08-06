package com.code2hack.poker

import com.code2hack.pokerdealer.protocol.ComposerDraftProjection
import com.code2hack.pokerdealer.protocol.ComposerMutationRequest
import com.code2hack.pokerdealer.protocol.ComposerMutationResult
import com.code2hack.pokerdealer.domain.ServerRequestLocator
import com.code2hack.pokerdealer.protocol.POKER_PHOTO_CANCEL_TYPE
import com.code2hack.pokerdealer.protocol.POKER_PHOTO_CAPTURE_BEGIN_TYPE
import com.code2hack.pokerdealer.protocol.POKER_PHOTO_CAPTURE_CHUNK_TYPE
import com.code2hack.pokerdealer.protocol.POKER_PHOTO_CAPTURE_COMPLETE_TYPE
import com.code2hack.pokerdealer.protocol.POKER_PHOTO_CAPTURE_RESULT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_PHOTO_DELETE_RESULT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_PHOTO_DELETE_TYPE
import com.code2hack.pokerdealer.protocol.POKER_PHOTO_START_RESULT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_PHOTO_START_TYPE
import com.code2hack.pokerdealer.protocol.POKER_USER_INPUT_MUTATION_RESULT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_USER_INPUT_MUTATION_TYPE
import com.code2hack.pokerdealer.protocol.POKER_USER_INPUT_PROJECTION_TYPE
import com.code2hack.pokerdealer.protocol.POKER_PRIMARY_ACTION_RESULT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_PRIMARY_ACTION_TYPE
import com.code2hack.pokerdealer.protocol.PokerPrimaryActionResult
import com.code2hack.pokerdealer.protocol.PokerPrimaryActionTarget
import com.code2hack.pokerdealer.protocol.PhotoAssetTarget
import com.code2hack.pokerdealer.protocol.PhotoCaptureBegin
import com.code2hack.pokerdealer.protocol.PhotoCaptureChunk
import com.code2hack.pokerdealer.protocol.PhotoCaptureComplete
import com.code2hack.pokerdealer.protocol.PhotoCaptureResult
import com.code2hack.pokerdealer.protocol.PhotoDeleteResult
import com.code2hack.pokerdealer.protocol.PhotoStartResult
import com.code2hack.pokerdealer.protocol.PhotoStartTarget
import com.code2hack.pokerdealer.protocol.UserInputAnswerMutationRequest
import com.code2hack.pokerdealer.protocol.UserInputAnswerMutationResult
import com.code2hack.pokerdealer.protocol.UserInputRequestProjection
import com.code2hack.pokerdealer.domain.RequestResolutionState
import com.code2hack.pokerdealer.protocol.POKER_COMPOSER_DRAFT_PROJECTION_TYPE
import com.code2hack.pokerdealer.protocol.POKER_COMPOSER_MUTATION_RESULT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_COMPOSER_MUTATION_TYPE
import com.code2hack.pokerdealer.protocol.PokerProtocolJson
import com.code2hack.pokerdealer.protocol.ProtocolEnvelope
import com.code2hack.pokerdealer.protocol.pokerUnreadRequestKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Process-local handoff between the listener service and Poker's visible composer surface. */
internal object PokerComposerBridge {
    private val projectionState = MutableStateFlow<Map<com.code2hack.pokerdealer.domain.CodexThreadLocator, ComposerDraftProjection>>(emptyMap())
    private val resultState = MutableStateFlow<Map<String, ComposerMutationResult>>(emptyMap())
    private val userInputProjectionState =
        MutableStateFlow<Map<ServerRequestLocator, UserInputRequestProjection>>(emptyMap())
    private val userInputResultState =
        MutableStateFlow<Map<String, UserInputAnswerMutationResult>>(emptyMap())
    private val primaryResultState = MutableStateFlow<Map<String, PokerPrimaryActionResult>>(emptyMap())
    private val photoStartResultState = MutableStateFlow<Map<String, PhotoStartResult>>(emptyMap())
    private val photoCaptureResultState = MutableStateFlow<Map<String, PhotoCaptureResult>>(emptyMap())
    private val photoDeleteResultState = MutableStateFlow<Map<String, PhotoDeleteResult>>(emptyMap())
    private var sender: (suspend (String, JsonObject, Boolean) -> Boolean)? = null

    val projections: StateFlow<Map<com.code2hack.pokerdealer.domain.CodexThreadLocator, ComposerDraftProjection>> =
        projectionState
    val results: StateFlow<Map<String, ComposerMutationResult>> = resultState
    val userInputProjections: StateFlow<Map<ServerRequestLocator, UserInputRequestProjection>> =
        userInputProjectionState
    val userInputResults: StateFlow<Map<String, UserInputAnswerMutationResult>> =
        userInputResultState
    val primaryResults: StateFlow<Map<String, PokerPrimaryActionResult>> = primaryResultState
    val photoStartResults: StateFlow<Map<String, PhotoStartResult>> = photoStartResultState
    val photoCaptureResults: StateFlow<Map<String, PhotoCaptureResult>> = photoCaptureResultState
    val photoDeleteResults: StateFlow<Map<String, PhotoDeleteResult>> = photoDeleteResultState

    fun attach(sender: suspend (String, JsonObject, Boolean) -> Boolean) {
        this.sender = sender
        projectionState.value = emptyMap()
        resultState.value = emptyMap()
        userInputProjectionState.value = emptyMap()
        userInputResultState.value = emptyMap()
        primaryResultState.value = emptyMap()
        photoStartResultState.value = emptyMap()
        photoCaptureResultState.value = emptyMap()
        photoDeleteResultState.value = emptyMap()
    }

    fun detach() {
        sender = null
        projectionState.value = emptyMap()
        resultState.value = emptyMap()
        userInputProjectionState.value = emptyMap()
        userInputResultState.value = emptyMap()
        primaryResultState.value = emptyMap()
        photoStartResultState.value = emptyMap()
        photoCaptureResultState.value = emptyMap()
        photoDeleteResultState.value = emptyMap()
    }

    fun receive(envelope: ProtocolEnvelope): Boolean = try {
        when (envelope.type) {
            POKER_COMPOSER_DRAFT_PROJECTION_TYPE -> {
                val projection = PokerProtocolJson.decodeFromJsonElement(
                    ComposerDraftProjection.serializer(),
                    envelope.payload,
                )
                projectionState.value = projectionState.value + (projection.locator to projection)
            }
            POKER_COMPOSER_MUTATION_RESULT_TYPE -> {
                val result = PokerProtocolJson.decodeFromJsonElement(
                    ComposerMutationResult.serializer(),
                    envelope.payload,
                )
                resultState.value = resultState.value + (result.target.operationId to result)
            }
            POKER_USER_INPUT_PROJECTION_TYPE -> {
                val projection = PokerProtocolJson.decodeFromJsonElement(
                    UserInputRequestProjection.serializer(),
                    envelope.payload,
                )
                PokerSnapshotRuntime.observeRequest(
                    locator = projection.request.thread,
                    requestKey = pokerUnreadRequestKey(
                        "user-input",
                        projection.request.locator.requestId,
                        projection.request.fingerprint,
                    ),
                    finalized = projection.request.resolution != RequestResolutionState.PENDING &&
                        projection.request.resolution != RequestResolutionState.RESPONDING,
                )
                userInputProjectionState.value = userInputProjectionState.value +
                    (projection.request.locator to projection)
            }
            POKER_USER_INPUT_MUTATION_RESULT_TYPE -> {
                val result = PokerProtocolJson.decodeFromJsonElement(
                    UserInputAnswerMutationResult.serializer(),
                    envelope.payload,
                )
                userInputResultState.value = userInputResultState.value +
                    (result.target.operationId to result)
            }
            POKER_PRIMARY_ACTION_RESULT_TYPE -> {
                val result = PokerProtocolJson.decodeFromJsonElement(
                    PokerPrimaryActionResult.serializer(),
                    envelope.payload,
                )
                primaryResultState.value = primaryResultState.value +
                    (result.target.operationId to result)
            }
            POKER_PHOTO_START_RESULT_TYPE -> {
                val result = PokerProtocolJson.decodeFromJsonElement(
                    PhotoStartResult.serializer(),
                    envelope.payload,
                )
                photoStartResultState.value = photoStartResultState.value +
                    (result.target.sessionId to result)
            }
            POKER_PHOTO_CAPTURE_RESULT_TYPE -> {
                val result = PokerProtocolJson.decodeFromJsonElement(
                    PhotoCaptureResult.serializer(),
                    envelope.payload,
                )
                photoCaptureResultState.value = photoCaptureResultState.value +
                    (result.target.operationId to result)
            }
            POKER_PHOTO_DELETE_RESULT_TYPE -> {
                val result = PokerProtocolJson.decodeFromJsonElement(
                    PhotoDeleteResult.serializer(),
                    envelope.payload,
                )
                photoDeleteResultState.value = photoDeleteResultState.value +
                    (result.target.operationId to result)
            }
            else -> return false
        }
        true
    } catch (_: Throwable) {
        false
    }

    suspend fun sendMutation(request: ComposerMutationRequest): Boolean {
        val send = sender ?: return false
        val payload = PokerProtocolJson.encodeToJsonElement(
            ComposerMutationRequest.serializer(),
            request,
        ).jsonObject
        return send(POKER_COMPOSER_MUTATION_TYPE, payload, true)
    }

    suspend fun sendUserInputMutation(request: UserInputAnswerMutationRequest): Boolean {
        val send = sender ?: return false
        val payload = PokerProtocolJson.encodeToJsonElement(
            UserInputAnswerMutationRequest.serializer(),
            request,
        ).jsonObject
        return send(POKER_USER_INPUT_MUTATION_TYPE, payload, true)
    }

    suspend fun sendPrimaryAction(target: PokerPrimaryActionTarget): Boolean {
        val send = sender ?: return false
        val payload = PokerProtocolJson.encodeToJsonElement(
            PokerPrimaryActionTarget.serializer(),
            target,
        ).jsonObject
        return send(POKER_PRIMARY_ACTION_TYPE, payload, true)
    }

    suspend fun sendPhotoStart(target: PhotoStartTarget): Boolean = sendPhoto(
        POKER_PHOTO_START_TYPE,
        PhotoStartTarget.serializer(),
        target,
    )

    suspend fun sendPhotoCaptureBegin(begin: PhotoCaptureBegin): Boolean = sendPhoto(
        POKER_PHOTO_CAPTURE_BEGIN_TYPE,
        PhotoCaptureBegin.serializer(),
        begin,
    )

    suspend fun sendPhotoCaptureChunk(chunk: PhotoCaptureChunk): Boolean = sendPhoto(
        POKER_PHOTO_CAPTURE_CHUNK_TYPE,
        PhotoCaptureChunk.serializer(),
        chunk,
    )

    suspend fun sendPhotoCaptureComplete(complete: PhotoCaptureComplete): Boolean = sendPhoto(
        POKER_PHOTO_CAPTURE_COMPLETE_TYPE,
        PhotoCaptureComplete.serializer(),
        complete,
    )

    suspend fun sendPhotoDelete(target: PhotoAssetTarget): Boolean = sendPhoto(
        POKER_PHOTO_DELETE_TYPE,
        PhotoAssetTarget.serializer(),
        target,
    )

    suspend fun sendPhotoCancel(target: PhotoStartTarget): Boolean = sendPhoto(
        POKER_PHOTO_CANCEL_TYPE,
        PhotoStartTarget.serializer(),
        target,
    )

    private suspend fun <T> sendPhoto(
        type: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        value: T,
    ): Boolean {
        val send = sender ?: return false
        val payload = PokerProtocolJson.encodeToJsonElement(serializer, value).jsonObject
        return send(type, payload, true)
    }
}
