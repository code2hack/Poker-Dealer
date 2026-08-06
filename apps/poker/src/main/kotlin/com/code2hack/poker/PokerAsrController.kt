package com.code2hack.poker

import com.code2hack.pokerdealer.domain.ComposerDraft
import com.code2hack.pokerdealer.domain.PokerInteraction
import com.code2hack.pokerdealer.domain.PokerInteractionPhase
import com.code2hack.pokerdealer.domain.PokerNavigationMode
import com.code2hack.pokerdealer.domain.PokerNavigationReducer
import com.code2hack.pokerdealer.domain.PokerOperation
import com.code2hack.pokerdealer.protocol.PokerAsrCommitRequest
import com.code2hack.pokerdealer.protocol.PokerAsrCommitResult
import com.code2hack.pokerdealer.protocol.PokerAsrDiscardRequest
import com.code2hack.pokerdealer.protocol.PokerAsrDiscardResult
import com.code2hack.pokerdealer.protocol.PokerAsrDiscardKind
import com.code2hack.pokerdealer.protocol.PokerAsrExitRequest
import com.code2hack.pokerdealer.protocol.PokerAsrExitResult
import com.code2hack.pokerdealer.protocol.PokerAsrMutationOutcome
import com.code2hack.pokerdealer.protocol.PokerAsrProjection
import com.code2hack.pokerdealer.protocol.PokerAsrStartOutcome
import com.code2hack.pokerdealer.protocol.PokerAsrStartRequest
import com.code2hack.pokerdealer.protocol.PokerAsrStartResult
import com.code2hack.pokerdealer.protocol.PokerAsrTarget
import com.code2hack.pokerdealer.protocol.PokerAsrTargetField
import com.code2hack.pokerdealer.protocol.POKER_ASR_MAX_AUDIO_BYTES
import com.code2hack.pokerdealer.protocol.POKER_ASR_MAX_AUDIO_QUEUE_BYTES
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class PokerAsrState {
    IDLE,
    PREPARING,
    ACTIVE,
    EXITING,
}

internal class PokerAsrController(
    private val navigation: PokerNavigationReducer,
    private val userInput: PokerUserInputController,
    private val onCaptureRequired: () -> Unit,
    private val onCaptureStop: () -> Unit,
    private val onExitNotice: () -> Unit = {},
    private val onFailureNotice: (String) -> Unit = {},
) {
    private data class ReservedAudio(
        val offset: Long,
        val pcm16: ByteArray,
    )

    private data class CommittedSlice(
        val start: Int,
        val endExclusive: Int,
        val text: String,
    )

    var state: PokerAsrState = PokerAsrState.IDLE
        private set
    private var sessionId: String? = null
    private var target: PokerAsrTarget? = null
    private var nextSampleOffset = 0L
    private var pendingOperationId: String? = null
    private var pendingCommit = false
    private var pendingDeletesLast = false
    private var pendingExitOperationId: String? = null
    private var exitWasActive = false
    private var currentSliceHasAudio = false
    private var lastCommittedSlice: CommittedSlice? = null
    private val audioMutex = Mutex()
    private val reservedAudio = ArrayDeque<ReservedAudio>()
    private var reservedAudioBytes = 0
    @Volatile
    private var queueOverflowed = false

    fun isInputCaptured(): Boolean = state != PokerAsrState.IDLE

    fun isActive(): Boolean = state == PokerAsrState.ACTIVE

    suspend fun start(): Boolean {
        if (state != PokerAsrState.IDLE || !PokerAsrBridge.availability.value.available) return false
        val nextTarget = focusedTarget() ?: return false
        val nextSession = UUID.randomUUID().toString()
        sessionId = nextSession
        target = nextTarget
        nextSampleOffset = 0
        pendingOperationId = null
        pendingCommit = false
        pendingDeletesLast = false
        pendingExitOperationId = null
        currentSliceHasAudio = false
        lastCommittedSlice = null
        queueOverflowed = false
        state = PokerAsrState.PREPARING
        val sent = PokerAsrBridge.sendStart(PokerAsrStartRequest(nextTarget, nextSession))
        if (!sent) reset()
        return sent
    }

    suspend fun handleInteraction(interaction: PokerInteraction) {
        if (interaction.phase != PokerInteractionPhase.RELEASE) return
        if (state == PokerAsrState.PREPARING) {
            if (interaction.operation == PokerOperation.FN && interaction.durationMs >= 500L) exit()
            return
        }
        if (state == PokerAsrState.ACTIVE &&
            interaction.operation == PokerOperation.FN &&
            interaction.durationMs >= 500L
        ) {
            exit()
            return
        }
        if (state != PokerAsrState.ACTIVE || pendingOperationId != null) return
        when (interaction.operation) {
            PokerOperation.DOWN -> commit()
            PokerOperation.FN -> discard()
            else -> Unit
        }
    }

    suspend fun sendAudio(pcm16: ByteArray): Boolean {
        if (state != PokerAsrState.ACTIVE || pcm16.isEmpty() || pcm16.size % 2 != 0) return false
        if (pcm16.size > POKER_ASR_MAX_AUDIO_BYTES) return false
        return audioMutex.withLock {
            val id = sessionId ?: return@withLock false
            val offset = nextSampleOffset
            if (pendingOperationId != null) {
                if (pcm16.size > POKER_ASR_MAX_AUDIO_QUEUE_BYTES - reservedAudioBytes) {
                    queueOverflowed = true
                    return@withLock false
                }
                reservedAudio.addLast(ReservedAudio(offset, pcm16.copyOf()))
                reservedAudioBytes += pcm16.size
                nextSampleOffset += pcm16.size / 2
                currentSliceHasAudio = true
                return@withLock true
            }
            val sent = PokerAsrBridge.sendAudio(id, offset, pcm16)
            if (sent) {
                nextSampleOffset += pcm16.size / 2
                currentSliceHasAudio = true
            }
            sent
        }
    }

    fun onStartResult(result: PokerAsrStartResult) {
        if (result.sessionId != sessionId || result.target != target || state != PokerAsrState.PREPARING) return
        when (result.outcome) {
            PokerAsrStartOutcome.READY -> {
                target = result.target
                state = PokerAsrState.ACTIVE
                onCaptureRequired()
            }
            PokerAsrStartOutcome.REJECTED,
            PokerAsrStartOutcome.CANCELLED,
            -> reset()
        }
    }

    fun onProjection(projection: PokerAsrProjection?) {
        if (pendingOperationId != null || pendingExitOperationId != null) return
        projection?.takeIf { it.sessionId == sessionId }?.let { target = it.target }
    }

    suspend fun onCommitResult(result: PokerAsrCommitResult) {
        if (result.sessionId != sessionId || result.target != target || result.operationId != pendingOperationId) return
        var stopCapture = false
        var flushFailed = false
        var keepCapturing = false
        var hadReservedAudio = false
        var wasCommit = false
        var baseTarget: PokerAsrTarget? = null
        audioMutex.withLock {
            if (result.sessionId != sessionId || result.target != target || result.operationId != pendingOperationId) {
                return@withLock
            }
            baseTarget = target
            wasCommit = pendingCommit
            hadReservedAudio = reservedAudio.isNotEmpty()
            pendingOperationId = null
            pendingCommit = false
            pendingDeletesLast = false
            if (result.outcome == PokerAsrMutationOutcome.ACKNOWLEDGED) {
                target = result.nextTarget ?: result.target
                currentSliceHasAudio = false
                if (wasCommit && result.committedText.isNotEmpty()) {
                    lastCommittedSlice = committedSlice(baseTarget, result.committedText)
                }
                if (pendingExitOperationId == null) {
                    flushFailed = !flushReservedAudioLocked(result.sessionId)
                    if (flushFailed) {
                        stopCapture = state != PokerAsrState.IDLE
                        clearStateLocked()
                    } else {
                        currentSliceHasAudio = hadReservedAudio
                        keepCapturing = true
                    }
                }
            } else {
                stopCapture = state != PokerAsrState.IDLE
                clearStateLocked()
            }
        }
        if (stopCapture) onCaptureStop()
        if (flushFailed) {
            onFailureNotice("ASR failed")
        } else if (keepCapturing) {
            onCaptureRequired()
        } else if (result.outcome != PokerAsrMutationOutcome.ACKNOWLEDGED) {
            onFailureNotice(asrFailureNotice(result.reason))
        }
    }

    suspend fun onDiscardResult(result: PokerAsrDiscardResult) {
        if (result.sessionId != sessionId || result.target != target || result.operationId != pendingOperationId) return
        var stopCapture = false
        var flushFailed = false
        var keepCapturing = false
        var hadReservedAudio = false
        var deletesLast = false
        audioMutex.withLock {
            if (result.sessionId != sessionId || result.target != target || result.operationId != pendingOperationId) {
                return@withLock
            }
            deletesLast = pendingDeletesLast
            hadReservedAudio = reservedAudio.isNotEmpty()
            pendingOperationId = null
            pendingCommit = false
            pendingDeletesLast = false
            if (result.outcome == PokerAsrMutationOutcome.ACKNOWLEDGED) {
                target = result.nextTarget ?: result.target
                currentSliceHasAudio = false
                if (deletesLast) lastCommittedSlice = null
                if (pendingExitOperationId == null) {
                    flushFailed = !flushReservedAudioLocked(result.sessionId)
                    if (flushFailed) {
                        stopCapture = state != PokerAsrState.IDLE
                        clearStateLocked()
                    } else {
                        currentSliceHasAudio = hadReservedAudio
                        keepCapturing = true
                    }
                }
            } else {
                stopCapture = state != PokerAsrState.IDLE
                clearStateLocked()
            }
        }
        if (stopCapture) onCaptureStop()
        if (flushFailed) {
            onFailureNotice("ASR failed")
        } else if (keepCapturing) {
            onCaptureRequired()
        } else if (result.outcome != PokerAsrMutationOutcome.ACKNOWLEDGED) {
            onFailureNotice(asrFailureNotice(result.reason))
        }
    }

    fun onExitResult(result: PokerAsrExitResult) {
        if (result.sessionId != sessionId) return
        if (result.operationId == pendingExitOperationId) {
            pendingExitOperationId = null
            if (result.outcome == PokerAsrMutationOutcome.ACKNOWLEDGED) {
                if (exitWasActive) onExitNotice()
            } else {
                onFailureNotice(asrFailureNotice(result.reason))
            }
            reset()
            return
        }
        if (state != PokerAsrState.IDLE) {
            onFailureNotice(asrFailureNotice(result.reason))
            reset()
        }
    }

    suspend fun cancel() {
        if (state == PokerAsrState.IDLE) return
        exit()
    }

    fun onConnectionLost() = reset()

    suspend fun fail(reason: String = "ASR failed") {
        if (state == PokerAsrState.IDLE) return
        onFailureNotice(reason)
        forceExit()
    }

    suspend fun forceExit() {
        var request: PokerAsrExitRequest? = null
        var stopCapture = false
        audioMutex.withLock {
            val id = sessionId
            val current = target
            if (id != null && current != null && state != PokerAsrState.IDLE) {
                request = PokerAsrExitRequest(
                    target = current,
                    sessionId = id,
                    operationId = UUID.randomUUID().toString(),
                )
                stopCapture = true
                clearStateLocked()
            }
        }
        if (stopCapture) onCaptureStop()
        request?.let { PokerAsrBridge.sendExit(it) }
    }

    fun captureFailureNotice(default: String): String =
        if (default == "ASR failed" && queueOverflowed) {
            "ASR overloaded"
        } else {
            default
        }

    private suspend fun commit() {
        var request: PokerAsrCommitRequest? = null
        var sent = true
        audioMutex.withLock {
            if (state != PokerAsrState.ACTIVE || pendingOperationId != null) return@withLock
            val id = sessionId ?: return@withLock
            val current = target ?: return@withLock
            val operation = UUID.randomUUID().toString()
            pendingOperationId = operation
            pendingCommit = true
            pendingDeletesLast = false
            request = PokerAsrCommitRequest(current, id, nextSampleOffset, operation)
            sent = PokerAsrBridge.sendCommit(checkNotNull(request))
            if (!sent) clearStateLocked()
        }
        if (!sent) {
            onCaptureStop()
            onFailureNotice("ASR failed")
        }
    }

    private suspend fun discard() {
        var request: PokerAsrDiscardRequest? = null
        var sent = true
        audioMutex.withLock {
            if (state != PokerAsrState.ACTIVE || pendingOperationId != null) return@withLock
            val id = sessionId ?: return@withLock
            val current = target ?: return@withLock
            val committed = lastCommittedSlice
            if (!currentSliceHasAudio && committed == null) return@withLock
            val operation = UUID.randomUUID().toString()
            pendingOperationId = operation
            pendingCommit = false
            pendingDeletesLast = !currentSliceHasAudio
            request = if (currentSliceHasAudio) {
                PokerAsrDiscardRequest(
                    target = current,
                    sessionId = id,
                    operationId = operation,
                    fenceSampleOffset = nextSampleOffset,
                )
            } else {
                PokerAsrDiscardRequest(
                    target = current,
                    sessionId = id,
                    operationId = operation,
                    fenceSampleOffset = nextSampleOffset,
                    kind = PokerAsrDiscardKind.LAST_COMMITTED_SLICE,
                    deleteStart = committed!!.start,
                    deleteEndExclusive = committed.endExclusive,
                    expectedText = committed.text,
                )
            }
            sent = PokerAsrBridge.sendDiscard(checkNotNull(request))
            if (!sent) clearStateLocked()
        }
        if (!sent) {
            onCaptureStop()
            onFailureNotice("ASR failed")
        }
    }

    private suspend fun exit() {
        var request: PokerAsrExitRequest? = null
        var stopCapture = false
        var sent = true
        audioMutex.withLock {
            val id = sessionId ?: return@withLock
            val current = target ?: return@withLock
            if (state == PokerAsrState.IDLE || state == PokerAsrState.EXITING) return@withLock
            exitWasActive = state == PokerAsrState.ACTIVE
            state = PokerAsrState.EXITING
            val operation = UUID.randomUUID().toString()
            pendingExitOperationId = operation
            request = PokerAsrExitRequest(current, id, operation)
            stopCapture = true
            sent = PokerAsrBridge.sendExit(checkNotNull(request))
            if (!sent) clearStateLocked()
        }
        if (stopCapture) onCaptureStop()
        if (!sent) reset()
    }

    private fun reset() {
        if (state != PokerAsrState.IDLE) onCaptureStop()
        clearStateLocked()
    }

    private fun clearStateLocked() {
        state = PokerAsrState.IDLE
        sessionId = null
        target = null
        nextSampleOffset = 0
        pendingOperationId = null
        pendingCommit = false
        pendingDeletesLast = false
        pendingExitOperationId = null
        exitWasActive = false
        currentSliceHasAudio = false
        lastCommittedSlice = null
        reservedAudio.clear()
        reservedAudioBytes = 0
    }

    private suspend fun flushReservedAudioLocked(id: String): Boolean {
        while (reservedAudio.isNotEmpty()) {
            val frame = reservedAudio.removeFirst()
            reservedAudioBytes -= frame.pcm16.size
            if (!PokerAsrBridge.sendAudio(id, frame.offset, frame.pcm16)) return false
        }
        return true
    }

    private fun asrFailureNotice(reason: String?): String = when {
        reason?.contains("overflow", ignoreCase = true) == true -> "ASR overloaded"
        reason?.contains("permission", ignoreCase = true) == true -> "ASR unavailable"
        reason?.contains("control", ignoreCase = true) == true -> "ASR control changed"
        reason == "ASR unavailable" -> reason
        else -> "ASR failed"
    }

    private fun committedSlice(baseTarget: PokerAsrTarget?, text: String): CommittedSlice? {
        if (baseTarget == null || text.isEmpty()) return null
        val count = ComposerDraft.fromText(text).visibleUnits().size
        if (count == 0) return null
        return CommittedSlice(
            start = baseTarget.cursorPosition,
            endExclusive = baseTarget.cursorPosition + count,
            text = text,
        )
    }

    private fun focusedTarget(): PokerAsrTarget? {
        val locator = navigation.metadata().focused ?: return null
        val anchor = navigation.anchor(locator) ?: return null
        if (anchor.mode == PokerNavigationMode.REQUEST_PANEL) {
            return userInput.focusedAsrTarget()
        }
        if (anchor.mode != PokerNavigationMode.COMPOSER) return null
        val composer = navigation.layout(locator)?.composer ?: return null
        val draft = composer.draft ?: return null
        if (!composer.hasDealerClaim || composer.modeSession.isBlank()) return null
        return PokerAsrTarget(
            locator = locator,
            field = PokerAsrTargetField.COMPOSER,
            targetRevision = draft.revision,
            cursorPosition = anchor.cursorPosition.coerceIn(0, draft.cursorCount - 1),
            controlGeneration = composer.controlGeneration,
            connectionEpoch = composer.connectionEpoch,
            modeSession = composer.modeSession,
        )
    }
}
