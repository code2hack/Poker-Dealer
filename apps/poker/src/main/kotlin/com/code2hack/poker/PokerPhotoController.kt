package com.code2hack.poker

import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.PokerInteraction
import com.code2hack.pokerdealer.domain.PokerInteractionPhase
import com.code2hack.pokerdealer.domain.PokerNavigationReducer
import com.code2hack.pokerdealer.domain.PokerOperation
import com.code2hack.pokerdealer.domain.PokerWheelSelection
import com.code2hack.pokerdealer.domain.PokerWheelAction
import com.code2hack.pokerdealer.domain.PokerActionWheel
import com.code2hack.pokerdealer.protocol.PhotoAssetCodec
import com.code2hack.pokerdealer.protocol.PhotoAssetTarget
import com.code2hack.pokerdealer.protocol.PhotoCaptureBegin
import com.code2hack.pokerdealer.protocol.PhotoCaptureComplete
import com.code2hack.pokerdealer.protocol.PhotoCaptureOutcome
import com.code2hack.pokerdealer.protocol.PhotoCaptureResult
import com.code2hack.pokerdealer.protocol.PhotoDeleteResult
import com.code2hack.pokerdealer.protocol.PhotoStartOutcome
import com.code2hack.pokerdealer.protocol.PhotoStartResult
import com.code2hack.pokerdealer.protocol.PhotoStartTarget
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal enum class PokerPhotoPhase {
    IDLE,
    STARTING,
    PREVIEW,
    CAPTURING,
    TRANSFERRING,
    DELETING,
}

internal data class PokerPhotoState(
    val phase: PokerPhotoPhase = PokerPhotoPhase.IDLE,
    val zoom: Float = 1f,
    val frozenBytes: ByteArray? = null,
    val notice: String? = null,
    val locator: CodexThreadLocator? = null,
)

internal const val POKER_PHOTO_CAPTURE_TIMEOUT_MS = 5_000L
internal const val POKER_PHOTO_TRANSFER_TIMEOUT_MS = 15_000L
internal const val POKER_PHOTO_DELETE_TIMEOUT_MS = 5_000L

internal fun photoZoomStep(value: Float, increase: Boolean): Float =
    (value * if (increase) 1.25f else 1f / 1.25f).coerceIn(1f, 8f)

/** Owns the short-lived Photo mode transaction; image bytes never enter the draft projection. */
internal class PokerPhotoController(
    private val navigation: PokerNavigationReducer,
    private val composer: PokerComposerController,
    private val scope: CoroutineScope,
    private val sendStart: suspend (PhotoStartTarget) -> Boolean,
    private val sendBegin: suspend (PhotoCaptureBegin) -> Boolean,
    private val sendChunk: suspend (com.code2hack.pokerdealer.protocol.PhotoCaptureChunk) -> Boolean,
    private val sendComplete: suspend (PhotoCaptureComplete) -> Boolean,
    private val sendDelete: suspend (PhotoAssetTarget) -> Boolean,
    private val sendCancel: suspend (PhotoStartTarget) -> Boolean,
    private val openCamera: () -> Unit = {},
    private val closeCamera: () -> Unit = {},
    private val setCameraZoom: (Float) -> Unit = {},
    private val storageAvailable: () -> Boolean = { true },
) {
    private val mutableState = MutableStateFlow(PokerPhotoState())
    private val waiters = mutableMapOf<String, CompletableDeferred<PhotoCaptureResult>>()
    private val deleteWaiters = mutableMapOf<String, CompletableDeferred<PhotoDeleteResult>>()
    private val committedAssetIds = linkedSetOf<String>()
    private var session: PhotoStartTarget? = null
    private var cursorPosition = 0
    private var draftRevision = 0L
    private var captureJob: Job? = null
    private var transferJob: Job? = null
    private var noticeJob: Job? = null
    private var lastGesture: PokerOperation? = null
    private var functionStartedAtMs: Long? = null

    val state: StateFlow<PokerPhotoState> = mutableState

    fun start(selection: PokerWheelSelection) {
        if (selection.action != PokerWheelAction.PHOTO || mutableState.value.phase != PokerPhotoPhase.IDLE) {
            return
        }
        val locator = navigation.metadata().focused ?: return
        val layout = navigation.layout(locator)?.composer ?: return
        val draft = layout.draft ?: return
        val cursor = navigation.anchor(locator)?.cursorPosition ?: return
        if (!selection.context.photoAvailable ||
            selection.context.modeSession.isBlank() ||
            layout.controlGeneration != selection.context.controlGeneration ||
            layout.connectionEpoch != selection.context.connectionEpoch ||
            layout.modeSession != selection.context.modeSession ||
            cursor !in 0 until draft.cursorCount
        ) return
        val target = PhotoStartTarget(
            locator = locator,
            draftRevision = draft.revision,
            cursorPosition = cursor,
            controlGeneration = selection.context.controlGeneration,
            connectionEpoch = selection.context.connectionEpoch,
            modeSession = selection.context.modeSession,
            sessionId = UUID.randomUUID().toString(),
        )
        session = target
        cursorPosition = target.cursorPosition
        draftRevision = target.draftRevision
        committedAssetIds.clear()
        mutableState.value = PokerPhotoState(PokerPhotoPhase.STARTING, locator = locator)
        scope.launch {
            if (!sendStart(target)) fail("Photo not added")
        }
    }

    fun onStartResult(result: PhotoStartResult) {
        val target = session ?: return
        if (mutableState.value.phase != PokerPhotoPhase.STARTING || result.target != target) return
        if (result.outcome == PhotoStartOutcome.ACCEPTED) {
            mutableState.value = PokerPhotoState(
                phase = PokerPhotoPhase.PREVIEW,
                locator = target.locator,
            )
            openCamera()
        } else {
            fail(result.reason ?: "Photo not added")
        }
    }

    /** Returns true while Photo mode owns the interaction stream. */
    fun handleInteraction(interaction: PokerInteraction): Boolean {
        val phase = mutableState.value.phase
        if (phase == PokerPhotoPhase.IDLE) return false
        if (phase != PokerPhotoPhase.PREVIEW) return true
        when (interaction.phase) {
            PokerInteractionPhase.BEGIN -> {
                lastGesture = null
                if (interaction.operation == PokerOperation.FN) {
                    functionStartedAtMs = interaction.eventTimeMs
                }
            }
            PokerInteractionPhase.UPDATE,
            PokerInteractionPhase.RELEASE,
            -> Unit
            PokerInteractionPhase.CANCEL -> functionStartedAtMs = null
        }
        if (interaction.operation == PokerOperation.DOWN || interaction.operation == PokerOperation.UP) {
            if (interaction.phase != PokerInteractionPhase.RELEASE && lastGesture != interaction.operation) {
                lastGesture = interaction.operation
                changeZoom(if (interaction.operation == PokerOperation.DOWN) 1.25f else 1f / 1.25f)
            }
            return true
        }
        if (interaction.phase == PokerInteractionPhase.RELEASE) {
            when (interaction.operation) {
                PokerOperation.TAP -> requestCapture()
                PokerOperation.FN -> {
                    val duration = functionStartedAtMs
                        ?.let { interaction.eventTimeMs - it }
                        ?: interaction.durationMs
                    functionStartedAtMs = null
                    if (duration >= PokerActionWheel.DEFAULT_LONG_PRESS_TIMEOUT_MS) exit() else requestDelete()
                }
                else -> Unit
            }
        }
        return true
    }

    fun onCaptured(bytes: ByteArray, mimeType: String = "image/jpeg") {
        val target = session ?: return
        if (mutableState.value.phase != PokerPhotoPhase.CAPTURING) return
        if (!storageAvailable()) {
            fail("Photo not added")
            return
        }
        val assetTarget = PhotoAssetTarget(
            locator = target.locator,
            sessionId = target.sessionId,
            assetId = UUID.randomUUID().toString(),
            draftRevision = draftRevision,
            cursorPosition = cursorPosition,
            controlGeneration = target.controlGeneration,
            connectionEpoch = target.connectionEpoch,
            modeSession = target.modeSession,
            operationId = UUID.randomUUID().toString(),
        )
        val waiter = CompletableDeferred<PhotoCaptureResult>()
        waiters[assetTarget.operationId] = waiter
        mutableState.value = mutableState.value.copy(
            phase = PokerPhotoPhase.TRANSFERRING,
            frozenBytes = bytes,
        )
        transferJob?.cancel()
        transferJob = scope.launch {
            val result = withTimeoutOrNull(POKER_PHOTO_TRANSFER_TIMEOUT_MS) {
                if (!sendBegin(PhotoCaptureBegin(assetTarget, mimeType, bytes.size.toLong()))) return@withTimeoutOrNull null
                PhotoAssetCodec.chunks(bytes).forEachIndexed { index, chunk ->
                    val sent = sendChunk(
                        com.code2hack.pokerdealer.protocol.PhotoCaptureChunk(
                            target = assetTarget,
                            offset = index.toLong() * com.code2hack.pokerdealer.protocol.POKER_PHOTO_CHUNK_BYTES,
                            data = PhotoAssetCodec.encode(chunk),
                        ),
                    )
                    if (!sent) return@withTimeoutOrNull null
                }
                if (!sendComplete(PhotoCaptureComplete(assetTarget, bytes.size.toLong(), PhotoAssetCodec.sha256(bytes)))) {
                    return@withTimeoutOrNull null
                }
                waiter.await()
            }
            waiters.remove(assetTarget.operationId)
            if (result == null) fail("Photo not added") else applyCaptureResult(result)
        }
    }

    fun onCaptureFailed() {
        if (mutableState.value.phase == PokerPhotoPhase.CAPTURING) fail("Photo not added")
    }

    fun onPermissionDenied() {
        if (session != null) forceExit("Camera permission denied")
    }

    fun onCameraFailure() {
        if (mutableState.value.phase == PokerPhotoPhase.IDLE) return
        forceExit(if (mutableState.value.phase == PokerPhotoPhase.PREVIEW) "Photo unavailable" else "Photo not added")
    }

    fun onConnectionLost() {
        if (session != null) forceExit(null)
    }

    fun onCaptureRequested() {
        if (mutableState.value.phase != PokerPhotoPhase.PREVIEW) return
        mutableState.value = mutableState.value.copy(phase = PokerPhotoPhase.CAPTURING, frozenBytes = null)
    }

    fun onCaptureResult(result: PhotoCaptureResult) {
        waiters[result.target.operationId]?.complete(result)
    }

    fun onDeleteResult(result: PhotoDeleteResult) {
        deleteWaiters[result.target.operationId]?.complete(result)
    }

    fun requestDelete() {
        val target = session ?: return
        if (mutableState.value.phase != PokerPhotoPhase.PREVIEW || committedAssetIds.isEmpty()) return
        val locator = target.locator
        val draft = navigation.layout(locator)?.composer?.draft ?: return
        val assetId = committedAssetIds.lastOrNull() ?: return
        val cursor = draft.visibleUnits().indexOfFirst { it.photoAssetId == assetId }
        if (cursor < 0) return
        val operation = PhotoAssetTarget(
            locator = locator,
            sessionId = target.sessionId,
            assetId = assetId,
            draftRevision = draft.revision,
            cursorPosition = cursor,
            controlGeneration = target.controlGeneration,
            connectionEpoch = target.connectionEpoch,
            modeSession = target.modeSession,
            operationId = UUID.randomUUID().toString(),
        )
        val waiter = CompletableDeferred<PhotoDeleteResult>()
        deleteWaiters[operation.operationId] = waiter
        mutableState.value = mutableState.value.copy(phase = PokerPhotoPhase.DELETING, frozenBytes = null)
        scope.launch {
            val result = withTimeoutOrNull(POKER_PHOTO_DELETE_TIMEOUT_MS) {
                if (!sendDelete(operation)) return@withTimeoutOrNull null
                waiter.await()
            }
            deleteWaiters.remove(operation.operationId)
            if (result == null) fail("Photo not deleted") else applyDeleteResult(result, assetId, cursor)
        }
    }

    fun exit() {
        if (session == null) return
        forceExit(null)
    }

    fun close() {
        if (session != null) forceExit(null) else closeCamera()
        noticeJob?.cancel()
    }

    private fun requestCapture() {
        if (!storageAvailable()) {
            fail("Photo not added")
            return
        }
        onCaptureRequested()
        if (mutableState.value.phase != PokerPhotoPhase.CAPTURING) return
        val callback = captureRequestedCallback ?: run {
            onCaptureFailed()
            return
        }
        captureJob?.cancel()
        captureJob = scope.launch {
            val bytes = withTimeoutOrNull(POKER_PHOTO_CAPTURE_TIMEOUT_MS) { callback() }
            if (bytes == null) onCaptureFailed() else onCaptured(bytes)
        }
    }

    private var captureRequestedCallback: (suspend () -> ByteArray?)? = null

    fun setCaptureRequestedCallback(callback: suspend () -> ByteArray?) {
        captureRequestedCallback = callback
    }

    private fun applyCaptureResult(result: PhotoCaptureResult) {
        if (session?.sessionId != result.target.sessionId ||
            mutableState.value.phase != PokerPhotoPhase.TRANSFERRING
        ) return
        if (result.outcome == PhotoCaptureOutcome.ACKNOWLEDGED) {
            composer.applyPhotoDraft(
                result.target.locator,
                result.draft,
                result.target.cursorPosition + 1,
            )
            draftRevision = result.draft.revision
            cursorPosition = result.target.cursorPosition + 1
            committedAssetIds += result.target.assetId
            mutableState.value = PokerPhotoState(
                phase = PokerPhotoPhase.PREVIEW,
                zoom = mutableState.value.zoom,
                locator = result.target.locator,
            )
            openCamera()
        } else {
            fail("Photo not added")
        }
    }

    private fun applyDeleteResult(result: PhotoDeleteResult, assetId: String, cursor: Int) {
        if (session?.sessionId != result.target.sessionId ||
            mutableState.value.phase != PokerPhotoPhase.DELETING
        ) return
        if (result.outcome == PhotoCaptureOutcome.ACKNOWLEDGED) {
            composer.applyPhotoDraft(result.target.locator, result.draft, cursor)
            committedAssetIds.remove(assetId)
            draftRevision = result.draft.revision
            cursorPosition = cursor
            mutableState.value = PokerPhotoState(
                phase = PokerPhotoPhase.PREVIEW,
                zoom = mutableState.value.zoom,
                locator = result.target.locator,
            )
            openCamera()
        } else {
            fail("Photo not deleted")
        }
    }

    private fun changeZoom(factor: Float) {
        val zoom = photoZoomStep(mutableState.value.zoom, factor > 1f)
        mutableState.value = mutableState.value.copy(zoom = zoom)
        setCameraZoom(zoom)
    }

    private fun forceExit(reason: String?) {
        val target = session
        captureJob?.cancel()
        transferJob?.cancel()
        waiters.clear()
        deleteWaiters.clear()
        functionStartedAtMs = null
        session = null
        committedAssetIds.clear()
        mutableState.value = PokerPhotoState(notice = reason)
        closeCamera()
        noticeJob?.cancel()
        if (target != null) {
            scope.launch { sendCancel(target) }
        }
        if (reason != null) {
            noticeJob = scope.launch {
                delay(1_000L)
                mutableState.value = mutableState.value.copy(notice = null)
            }
        }
    }

    private fun fail(reason: String) {
        val starting = mutableState.value.phase == PokerPhotoPhase.STARTING
        transferJob?.cancel()
        waiters.clear()
        deleteWaiters.clear()
        closeCamera()
        if (starting) {
            session = null
            committedAssetIds.clear()
        }
        mutableState.value = if (session == null) {
            PokerPhotoState(notice = reason)
        } else {
            PokerPhotoState(
                phase = PokerPhotoPhase.PREVIEW,
                zoom = mutableState.value.zoom,
                notice = reason,
                locator = session?.locator,
            )
        }
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(1_000L)
            mutableState.value = mutableState.value.copy(notice = null)
            if (session != null && mutableState.value.phase == PokerPhotoPhase.PREVIEW) openCamera()
        }
    }
}
