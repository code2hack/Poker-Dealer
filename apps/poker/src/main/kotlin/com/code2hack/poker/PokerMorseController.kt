package com.code2hack.poker

import com.code2hack.pokerdealer.domain.MorseInputController
import com.code2hack.pokerdealer.domain.MorseInputEvent
import com.code2hack.pokerdealer.domain.MorseCompletionEngine
import com.code2hack.pokerdealer.domain.MorseMutationOutcome
import com.code2hack.pokerdealer.domain.PokerNavigationReducer
import com.code2hack.pokerdealer.domain.PokerWheelContext
import com.code2hack.pokerdealer.domain.PokerWheelAction
import com.code2hack.pokerdealer.domain.PokerWheelSelection
import com.code2hack.pokerdealer.protocol.MorseMutationRequest
import com.code2hack.pokerdealer.protocol.MorseMutationResult
import com.code2hack.pokerdealer.protocol.MorseCompletionRequest
import com.code2hack.pokerdealer.protocol.MorseCompletionProjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** Coordinates local Morse timing with one authoritative Dealer field mutation at a time. */
internal class PokerMorseController(
    private val navigation: PokerNavigationReducer,
    private val composer: PokerComposerController,
    private val userInput: PokerUserInputController,
    private val wheelContext: () -> PokerWheelContext,
    private val scope: CoroutineScope,
    private val sendMutation: suspend (MorseMutationRequest) -> Boolean,
    private val sendCompletion: suspend (MorseCompletionRequest) -> Boolean,
    longPressTimeoutMs: Long,
    private val onNotice: (String, Long) -> Unit = { _, _ -> },
) {
    private companion object {
        const val ACK_RETRY_DELAY_MS = 5_000L
    }

    val input = MorseInputController(longPressTimeoutMs = longPressTimeoutMs)

    fun begin(selection: PokerWheelSelection, atMs: Long): Boolean {
        if (selection.action != PokerWheelAction.MORSE || !selection.context.morseAvailable) return false
        if (wheelContext() != selection.context) return false
        if (input.isActive) return false
        val modeSession = UUID.randomUUID().toString()
        val locator = navigation.metadata().focused ?: return false
        val target = when (navigation.anchor(locator)?.mode) {
            com.code2hack.pokerdealer.domain.PokerNavigationMode.COMPOSER ->
                composer.morseTarget(modeSession)
            com.code2hack.pokerdealer.domain.PokerNavigationMode.REQUEST_PANEL ->
                userInput.morseTarget(modeSession)
            else -> null
        } ?: return false
        if (target.bindingModeSession != selection.context.modeSession ||
            target.controlGeneration != selection.context.controlGeneration ||
            target.connectionEpoch != selection.context.connectionEpoch
        ) return false
        input.begin(target, atMs)
        return true
    }

    fun handle(event: MorseInputEvent?) {
        when (event) {
            is MorseInputEvent.MutationRequested -> send(event)
            is MorseInputEvent.CharacterFinished,
            MorseInputEvent.CharacterDeleted,
            -> requestCompletion()
            is MorseInputEvent.Exited -> if (!event.forced) onNotice("Morse exited", 500L)
            MorseInputEvent.Interrupted -> onNotice("Morse interrupted", 1_000L)
            else -> Unit
        }
    }

    fun apply(result: MorseMutationResult) {
        if (!input.isActive) return
        val target = result.target
        if (input.pendingTarget() != target) return
        val installed = when (target.mode.surface) {
            com.code2hack.pokerdealer.domain.ComposerSurface.THREAD_COMPOSER ->
                result.composerDraft?.let { composer.installMorseDraft(target, it, result.cursorPosition ?: 0) }
                    ?: false
            com.code2hack.pokerdealer.domain.ComposerSurface.REQUEST_PANEL ->
                result.answerBuffer?.let { userInput.installMorseBuffer(target, it) } ?: false
        }
        val outcome = if (installed) result.outcome else MorseMutationOutcome.UNCERTAIN
        handle(input.applyMutation(target, outcome, result.fieldRevision, result.cursorPosition))
    }

    fun applyCompletion(projection: MorseCompletionProjection) {
        input.applyCompletion(projection.target, projection.prefix, projection.suffix)
    }

    fun tick(atMs: Long) {
        handle(input.advance(atMs))
    }

    fun abort() {
        handle(input.abort())
    }

    private fun send(event: MorseInputEvent.MutationRequested) {
        val intent = event.intent
        scope.launch {
            val request = MorseMutationRequest(
                target = intent.target,
                kind = intent.kind,
                text = intent.text,
                deleteStart = intent.deleteStart,
                deleteEndExclusive = intent.deleteEndExclusive,
                expectedText = intent.expectedText,
            )
            if (!sendOrFalse(request)) {
                delay(ACK_RETRY_DELAY_MS)
                if (input.pendingTarget() == intent.target && sendOrFalse(request)) return@launch
                handle(
                    input.applyMutation(
                        target = intent.target,
                        outcome = MorseMutationOutcome.UNCERTAIN,
                        fieldRevision = null,
                        cursorPosition = null,
                    ),
                )
                return@launch
            }
            delay(ACK_RETRY_DELAY_MS)
            if (input.pendingTarget() == intent.target && !sendOrFalse(request)) {
                handle(
                    input.applyMutation(
                        target = intent.target,
                        outcome = MorseMutationOutcome.UNCERTAIN,
                        fieldRevision = null,
                        cursorPosition = null,
                    ),
                )
            }
        }
    }

    private fun requestCompletion() {
        val state = input.state()
        val target = state.target ?: return
        if (!MorseCompletionEngine.isEligiblePrefix(state.word)) return
        scope.launch {
            runCatching {
                sendCompletion(
                    MorseCompletionRequest(
                        target = target,
                        prefix = state.word,
                    ),
                )
            }
        }
    }

    private suspend fun sendOrFalse(request: MorseMutationRequest): Boolean = try {
        sendMutation(request)
    } catch (_: Throwable) {
        false
    }
}
