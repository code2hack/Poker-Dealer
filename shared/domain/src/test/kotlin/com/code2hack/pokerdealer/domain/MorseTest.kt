package com.code2hack.pokerdealer.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MorseTest {
    private val target = MorseModeTarget(
        locator = CodexThreadLocator("spark", "thread"),
        surface = ComposerSurface.THREAD_COMPOSER,
        revision = 0,
        cursorPosition = 0,
        controlGeneration = 1,
        connectionEpoch = 2,
        bindingModeSession = "binding",
        modeSession = "morse",
    )

    @Test
    fun `written table decodes letters figures punctuation and rejects procedural signals`() {
        assertEquals('A', MorseCode.decode(".-"))
        assertEquals('X', MorseCode.decode("-..-"))
        assertEquals('É', MorseCode.decode("..-.."))
        assertEquals('?', MorseCode.decode("..--.."))
        assertEquals('–', MorseCode.decode("-....-"))
        assertEquals('@', MorseCode.decode(".--.-."))
        assertNull(MorseCode.decode("...-.-"))
    }

    @Test
    fun `short and long taps use the threshold and quiet interval`() {
        val controller = MorseInputController(sessionId = { "operation" })
        controller.begin(target)

        tap(controller, atMs = 100, durationMs = 499)
        assertNull(controller.advance(1_298))
        assertEquals(MorseInputEvent.CharacterFinished('E'), controller.advance(1_299))
        assertEquals("e", controller.state().word)

        tap(controller, atMs = 1_400, durationMs = 500)
        assertEquals(MorseInputEvent.CharacterFinished('T'), controller.advance(2_600))
        assertEquals("et", controller.state().word)
    }

    @Test
    fun `held ineligible gesture suspends and then restores remaining quiet interval`() {
        val controller = MorseInputController()
        controller.begin(target)
        tap(controller, atMs = 100, durationMs = 100)

        controller.reduce(interaction(PokerOperation.UP, PokerInteractionPhase.BEGIN, 500))
        assertNull(controller.advance(799))
        assertEquals(MorseInputEvent.Ignored, controller.reduce(
            interaction(PokerOperation.UP, PokerInteractionPhase.RELEASE, 550, durationMs = 50),
        ))
        assertNull(controller.advance(949))
        assertEquals(MorseInputEvent.CharacterFinished('E'), controller.advance(950))
    }

    @Test
    fun `invalid sequence is discarded without a character hint`() {
        val controller = MorseInputController()
        controller.begin(target)
        var atMs = 100L
        "......".forEach { symbol ->
            tap(controller, atMs = atMs, durationMs = if (symbol == '.') 100L else 500L)
            atMs += 150L
        }
        assertEquals(MorseInputEvent.CharacterFinished(null), controller.advance(atMs + 700L))
        assertEquals("", controller.state().word)
    }

    @Test
    fun `commit and deletion are exact serial mutations scoped to this mode session`() {
        var operationNumber = 0
        val controller = MorseInputController(sessionId = { "operation-${operationNumber++}" })
        controller.begin(target)
        tap(controller, atMs = 100, durationMs = 100)
        tap(controller, atMs = 200, durationMs = 100)
        tap(controller, atMs = 300, durationMs = 100)
        controller.advance(1_000)

        controller.reduce(interaction(PokerOperation.UP, PokerInteractionPhase.BEGIN, 1_100))
        val commit = controller.reduce(
            interaction(PokerOperation.UP, PokerInteractionPhase.RELEASE, 1_200, durationMs = 100),
        ) as MorseInputEvent.MutationRequested
        assertEquals(MorseMutationKind.COMMIT_WORD, commit.intent.kind)
        assertEquals("s ", commit.intent.text)
        assertEquals(commit.intent.target, controller.pendingTarget())

        assertEquals(
            MorseInputEvent.MutationAcknowledged,
            controller.applyMutation(commit.intent.target, MorseMutationOutcome.ACKNOWLEDGED, 1, 2),
        )
        assertEquals("", controller.state().word)
        assertEquals(1, controller.state().committedWords.size)

        controller.reduce(interaction(PokerOperation.FN, PokerInteractionPhase.BEGIN, 1_300))
        val deletion = controller.reduce(
            interaction(PokerOperation.FN, PokerInteractionPhase.RELEASE, 1_400, durationMs = 100),
        ) as MorseInputEvent.MutationRequested
        assertEquals(MorseMutationKind.DELETE_COMMITTED_WORD, deletion.intent.kind)
        assertEquals("s ", deletion.intent.expectedText)
        assertEquals(0, deletion.intent.deleteStart)
        assertEquals(2, deletion.intent.deleteEndExclusive)
        assertEquals(
            MorseInputEvent.MutationAcknowledged,
            controller.applyMutation(deletion.intent.target, MorseMutationOutcome.ACKNOWLEDGED, 2, 0),
        )
        assertTrue(controller.state().committedWords.isEmpty())

        controller.reduce(interaction(PokerOperation.FN, PokerInteractionPhase.BEGIN, 1_500))
        assertEquals(
            MorseInputEvent.Ignored,
            controller.reduce(interaction(PokerOperation.FN, PokerInteractionPhase.RELEASE, 1_600, 100)),
        )
    }

    @Test
    fun `long function exits empty mode and forced reconciliation interrupts`() {
        val controller = MorseInputController()
        controller.begin(target)
        controller.reduce(interaction(PokerOperation.FN, PokerInteractionPhase.BEGIN, 100))
        assertEquals(
            MorseInputEvent.Exited(),
            controller.reduce(interaction(PokerOperation.FN, PokerInteractionPhase.RELEASE, 600, 500)),
        )
        assertFalse(controller.isActive)

        controller.begin(target.copy(modeSession = "morse-2"))
        tap(controller, atMs = 700, durationMs = 100)
        assertEquals(MorseInputEvent.CharacterFinished('E'), controller.advance(1_500))
        controller.reduce(interaction(PokerOperation.UP, PokerInteractionPhase.BEGIN, 1_600))
        val pending = controller.reduce(interaction(PokerOperation.UP, PokerInteractionPhase.RELEASE, 1_700, 100))
            as MorseInputEvent.MutationRequested
        assertEquals(
            MorseInputEvent.Interrupted,
            controller.applyMutation(pending.intent.target, MorseMutationOutcome.UNCERTAIN, null, null),
        )
        assertFalse(controller.isActive)
    }

    private fun tap(controller: MorseInputController, atMs: Long, durationMs: Long) {
        controller.reduce(interaction(PokerOperation.TAP, PokerInteractionPhase.BEGIN, atMs))
        controller.reduce(interaction(PokerOperation.TAP, PokerInteractionPhase.RELEASE, atMs + durationMs, durationMs))
    }

    private fun interaction(
        operation: PokerOperation,
        phase: PokerInteractionPhase,
        atMs: Long,
        durationMs: Long = 0,
    ) = PokerInteraction(
        source = PokerInputSource.GLASSES,
        operation = operation,
        phase = phase,
        eventTimeMs = atMs,
        durationMs = durationMs,
    )
}
