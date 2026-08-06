package com.code2hack.pokerdealer.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
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
    fun `ITU printable table decodes every written character and rejects procedural signals`() {
        val expected = mapOf(
            ".-" to 'A', "-..." to 'B', "-.-." to 'C', "-.." to 'D', "." to 'E',
            "..-." to 'F', "--." to 'G', "...." to 'H', ".." to 'I', ".---" to 'J',
            "-.-" to 'K', ".-.." to 'L', "--" to 'M', "-." to 'N', "---" to 'O',
            ".--." to 'P', "--.-" to 'Q', ".-." to 'R', "..." to 'S', "-" to 'T',
            "..-" to 'U', "...-" to 'V', ".--" to 'W', "-..-" to 'X', "-.--" to 'Y',
            "--.." to 'Z', "..-.." to 'É',
            ".----" to '1', "..---" to '2', "...--" to '3', "....-" to '4', "....." to '5',
            "-...." to '6', "--..." to '7', "---.." to '8', "----." to '9', "-----" to '0',
            ".-.-.-" to '.', "--..--" to ',', "---..." to ':', "..--.." to '?',
            ".----." to '’', "-....-" to '–', "-..-." to '/', "-.--." to '(',
            "-.--.-" to ')', ".-..-." to '"', "-...-" to '=', ".-.-." to '+',
            ".--.-." to '@',
        )

        expected.forEach { (sequence, character) ->
            assertEquals(character, MorseCode.decode(sequence), sequence)
        }
        assertEquals(expected.values.toSet(), MorseCode.printableCharacters)
        listOf("...-.", "........", ".-...", "...-.-", "-.-.-").forEach { sequence ->
            assertNull(MorseCode.decode(sequence), sequence)
        }
    }

    @Test
    fun `completion ranks commonness then shorter suffix then alphabetically`() {
        val dictionary = listOf(
            MorseDictionaryEntry("cater", 10),
            MorseDictionaryEntry("cat", 10),
            MorseDictionaryEntry("cab", 20),
            MorseDictionaryEntry("can", 20),
            MorseDictionaryEntry("cabin", 20),
        )

        assertEquals("cat", MorseCompletionEngine.suggest("ca", dictionary)?.word)
        assertEquals("t", MorseCompletionEngine.suggest("ca", dictionary)?.suffix)
        assertEquals("cabin", MorseCompletionEngine.suggest("cab", dictionary)?.word)
        assertEquals(
            "cab",
            MorseCompletionEngine.suggest(
                "ca",
                listOf(MorseDictionaryEntry("can", 10), MorseDictionaryEntry("cab", 10)),
            )?.word,
        )
        assertNull(MorseCompletionEngine.suggest("ca1", dictionary))
        assertNull(MorseCompletionEngine.suggest("c.", dictionary))
        assertNull(MorseCompletionEngine.suggest("c", dictionary))
    }

    @Test
    fun `completion preserves dictionary suffix casing and parser keeps best commonness`() {
        assertEquals(
            "ple",
            MorseCompletionEngine.suggest(
                "AP",
                listOf(MorseDictionaryEntry("Apple", 10)),
            )?.suffix,
        )
        val parsed = MorseCompletionDictionary.parse(
            sequenceOf(
                "# pinned dictionary",
                "20\tcat",
                "10\tcat",
                "10\tcab",
            ),
        )
        assertEquals(listOf(MorseDictionaryEntry("cab", 10), MorseDictionaryEntry("cat", 10)), parsed)
    }

    @Test
    fun `down commits displayed completion while up ignores it`() {
        val down = controllerWithCa()
        assertTrue(down.applyCompletion(target, "ca", "t"))
        down.reduce(interaction(PokerOperation.DOWN, PokerInteractionPhase.BEGIN, 3_500))
        val downCommit = down.reduce(
            interaction(PokerOperation.DOWN, PokerInteractionPhase.RELEASE, 3_600, 100),
        ) as MorseInputEvent.MutationRequested
        assertEquals("cat ", downCommit.intent.text)

        val up = controllerWithCa()
        assertTrue(up.applyCompletion(target, "ca", "t"))
        up.reduce(interaction(PokerOperation.UP, PokerInteractionPhase.BEGIN, 3_500))
        val upCommit = up.reduce(
            interaction(PokerOperation.UP, PokerInteractionPhase.RELEASE, 3_600, 100),
        ) as MorseInputEvent.MutationRequested
        assertEquals("ca ", upCommit.intent.text)
    }

    @Test
    fun `deletion and target changes fence an asynchronous completion`() {
        val controller = controllerWithCa()
        assertTrue(controller.applyCompletion(target, "ca", "t"))
        controller.reduce(interaction(PokerOperation.TAPTAP, PokerInteractionPhase.BEGIN, 3_500))
        assertEquals(
            MorseInputEvent.CharacterDeleted,
            controller.reduce(interaction(PokerOperation.TAPTAP, PokerInteractionPhase.RELEASE, 3_600, 100)),
        )
        assertNull(controller.state().completion)
        assertFalse(controller.applyCompletion(target, "ca", "t"))
        assertFalse(controller.applyCompletion(target.copy(revision = 1), "c", "at"))
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
    fun `deletion starts at the Morse cursor and cannot reach pre-session text`() {
        val sessionTarget = target.copy(revision = 9, cursorPosition = 5)
        val controller = MorseInputController(sessionId = { "operation" })
        controller.begin(sessionTarget)
        tap(controller, atMs = 100, durationMs = 100)
        assertEquals(MorseInputEvent.CharacterFinished('E'), controller.advance(900))

        controller.reduce(interaction(PokerOperation.UP, PokerInteractionPhase.BEGIN, 1_000))
        val commit = controller.reduce(
            interaction(PokerOperation.UP, PokerInteractionPhase.RELEASE, 1_100, durationMs = 100),
        ) as MorseInputEvent.MutationRequested
        assertEquals(5, commit.intent.target.mode.cursorPosition)
        assertEquals("e ", commit.intent.text)
        assertEquals(
            MorseInputEvent.MutationAcknowledged,
            controller.applyMutation(commit.intent.target, MorseMutationOutcome.ACKNOWLEDGED, 10, 7),
        )

        controller.reduce(interaction(PokerOperation.FN, PokerInteractionPhase.BEGIN, 1_200))
        val deletion = controller.reduce(
            interaction(PokerOperation.FN, PokerInteractionPhase.RELEASE, 1_300, durationMs = 100),
        ) as MorseInputEvent.MutationRequested
        assertEquals(MorseMutationKind.DELETE_COMMITTED_WORD, deletion.intent.kind)
        assertEquals(5, deletion.intent.deleteStart)
        assertEquals(7, deletion.intent.deleteEndExclusive)
        assertEquals("e ", deletion.intent.expectedText)
    }

    @Test
    fun `commit preserves exact composer and request targets`() {
        val requestTarget = target.copy(
            surface = ComposerSurface.REQUEST_PANEL,
            requestLocator = ServerRequestLocator("spark", 7, "request"),
            questionId = "question",
            requestFingerprint = "fingerprint",
            revision = 2,
            cursorPosition = 4,
        )
        listOf(target, requestTarget).forEachIndexed { index, modeTarget ->
            val controller = MorseInputController(sessionId = { "operation-$index" })
            controller.begin(modeTarget)
            tap(controller, atMs = 100, durationMs = 100)
            assertEquals(MorseInputEvent.CharacterFinished('E'), controller.advance(900))
            controller.reduce(interaction(PokerOperation.UP, PokerInteractionPhase.BEGIN, 1_000))
            val commit = controller.reduce(
                interaction(PokerOperation.UP, PokerInteractionPhase.RELEASE, 1_100, durationMs = 100),
            ) as MorseInputEvent.MutationRequested

            assertEquals(modeTarget, commit.intent.target.mode)
            assertEquals(
                MorseInputEvent.MutationAcknowledged,
                controller.applyMutation(commit.intent.target, MorseMutationOutcome.ACKNOWLEDGED, 3, 6),
            )
        }
    }

    @Test
    fun `duplicate late and uncertain mutation results never replay`() {
        val controller = MorseInputController(sessionId = { "operation" })
        controller.begin(target)
        tap(controller, atMs = 100, durationMs = 100)
        assertEquals(MorseInputEvent.CharacterFinished('E'), controller.advance(900))
        controller.reduce(interaction(PokerOperation.UP, PokerInteractionPhase.BEGIN, 1_000))
        val commit = controller.reduce(
            interaction(PokerOperation.UP, PokerInteractionPhase.RELEASE, 1_100, durationMs = 100),
        ) as MorseInputEvent.MutationRequested

        assertEquals(
            MorseInputEvent.MutationAcknowledged,
            controller.applyMutation(commit.intent.target, MorseMutationOutcome.ACKNOWLEDGED, 1, 2),
        )
        assertNull(controller.applyMutation(commit.intent.target, MorseMutationOutcome.ACKNOWLEDGED, 2, 3))
        assertEquals(1, controller.state().committedWords.size)

        controller.reduce(interaction(PokerOperation.FN, PokerInteractionPhase.BEGIN, 1_200))
        val deletion = controller.reduce(
            interaction(PokerOperation.FN, PokerInteractionPhase.RELEASE, 1_300, durationMs = 100),
        ) as MorseInputEvent.MutationRequested
        assertNull(controller.applyMutation(commit.intent.target, MorseMutationOutcome.ACKNOWLEDGED, 3, 4))
        assertEquals(deletion.intent.target, controller.pendingTarget())
        assertEquals(
            MorseInputEvent.Interrupted,
            controller.applyMutation(deletion.intent.target, MorseMutationOutcome.UNCERTAIN, null, null),
        )
        assertFalse(controller.isActive)
        assertNull(controller.applyMutation(deletion.intent.target, MorseMutationOutcome.ACKNOWLEDGED, 4, 5))
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

    private fun controllerWithCa(): MorseInputController {
        val controller = MorseInputController(sessionId = { "operation" })
        controller.begin(target)
        tap(controller, 100, 500)
        tap(controller, 600, 100)
        tap(controller, 700, 500)
        tap(controller, 1_200, 100)
        assertNotNull(controller.advance(2_000))
        tap(controller, 2_100, 100)
        tap(controller, 2_200, 500)
        assertNotNull(controller.advance(3_400))
        assertEquals("ca", controller.state().word)
        return controller
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
