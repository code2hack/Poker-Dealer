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
import com.code2hack.pokerdealer.protocol.PokerAsrExitRequest
import com.code2hack.pokerdealer.protocol.PokerAsrExitResult
import com.code2hack.pokerdealer.protocol.PokerAsrMutationOutcome
import com.code2hack.pokerdealer.protocol.PokerAsrProjection
import com.code2hack.pokerdealer.protocol.PokerAsrStartOutcome
import com.code2hack.pokerdealer.protocol.PokerAsrStartRequest
import com.code2hack.pokerdealer.protocol.PokerAsrStartResult
import com.code2hack.pokerdealer.protocol.PokerAsrTarget
import com.code2hack.pokerdealer.protocol.PokerAsrTargetField
import java.util.UUID

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
) {
    var state: PokerAsrState = PokerAsrState.IDLE
        private set
    private var sessionId: String? = null
    private var target: PokerAsrTarget? = null
    private var nextSampleOffset = 0L
    private var pendingOperationId: String? = null
    private var pendingExitOperationId: String? = null
    private var exitWasActive = false

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
        pendingExitOperationId = null
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
        if (state != PokerAsrState.ACTIVE || pendingOperationId != null) return
        when (interaction.operation) {
            PokerOperation.DOWN -> commit()
            PokerOperation.FN -> if (interaction.durationMs >= 500L) exit() else discard()
            else -> Unit
        }
    }

    suspend fun sendAudio(pcm16: ByteArray): Boolean {
        if (state != PokerAsrState.ACTIVE || pcm16.isEmpty() || pcm16.size % 2 != 0) return false
        if (pendingOperationId != null) return true
        if (pcm16.size > com.code2hack.pokerdealer.protocol.POKER_ASR_MAX_AUDIO_BYTES) return false
        val id = sessionId ?: return false
        val offset = nextSampleOffset
        val sent = PokerAsrBridge.sendAudio(id, offset, pcm16)
        if (sent) nextSampleOffset += pcm16.size / 2
        return sent
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
        projection?.takeIf { it.sessionId == sessionId }?.let { target = it.target }
    }

    fun onCommitResult(result: PokerAsrCommitResult) {
        if (result.sessionId != sessionId || result.target != target || result.operationId != pendingOperationId) return
        pendingOperationId = null
        if (result.outcome == PokerAsrMutationOutcome.ACKNOWLEDGED) {
            target = result.nextTarget ?: result.target
            onCaptureRequired()
        } else {
            reset()
        }
    }

    fun onDiscardResult(result: PokerAsrDiscardResult) {
        if (result.sessionId != sessionId || result.target != target || result.operationId != pendingOperationId) return
        pendingOperationId = null
        if (result.outcome == PokerAsrMutationOutcome.ACKNOWLEDGED) {
            target = result.nextTarget ?: result.target
            onCaptureRequired()
        } else {
            reset()
        }
    }

    fun onExitResult(result: PokerAsrExitResult) {
        if (result.sessionId != sessionId || result.target != target || result.operationId != pendingExitOperationId) return
        pendingExitOperationId = null
        if (exitWasActive && result.outcome == PokerAsrMutationOutcome.ACKNOWLEDGED) onExitNotice()
        reset()
    }

    suspend fun cancel() {
        if (state == PokerAsrState.IDLE) return
        exit()
    }

    fun onConnectionLost() = reset()

    private suspend fun commit() {
        val id = sessionId ?: return
        val current = target ?: return
        val operation = UUID.randomUUID().toString()
        onCaptureStop()
        pendingOperationId = operation
        if (!PokerAsrBridge.sendCommit(PokerAsrCommitRequest(current, id, nextSampleOffset, operation))) {
            pendingOperationId = null
            reset()
        }
    }

    private suspend fun discard() {
        val id = sessionId ?: return
        val current = target ?: return
        val operation = UUID.randomUUID().toString()
        onCaptureStop()
        pendingOperationId = operation
        if (!PokerAsrBridge.sendDiscard(PokerAsrDiscardRequest(current, id, operation))) {
            pendingOperationId = null
            reset()
        }
    }

    private suspend fun exit() {
        val id = sessionId ?: return
        val current = target ?: return
        if (state == PokerAsrState.EXITING) return
        onCaptureStop()
        exitWasActive = state == PokerAsrState.ACTIVE
        state = PokerAsrState.EXITING
        val operation = UUID.randomUUID().toString()
        pendingExitOperationId = operation
        if (!PokerAsrBridge.sendExit(PokerAsrExitRequest(current, id, operation))) reset()
    }

    private fun reset() {
        if (state != PokerAsrState.IDLE) onCaptureStop()
        state = PokerAsrState.IDLE
        sessionId = null
        target = null
        nextSampleOffset = 0
        pendingOperationId = null
        pendingExitOperationId = null
        exitWasActive = false
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
