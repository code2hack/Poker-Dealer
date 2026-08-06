package com.code2hack.poker

import com.code2hack.pokerdealer.domain.ComposerDraft
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.PokerCardLayout
import com.code2hack.pokerdealer.domain.PokerComposerLayout
import com.code2hack.pokerdealer.domain.PokerInteraction
import com.code2hack.pokerdealer.domain.PokerInteractionPhase
import com.code2hack.pokerdealer.domain.PokerNavigationReducer
import com.code2hack.pokerdealer.domain.PokerOperation
import com.code2hack.pokerdealer.domain.PokerPileLayout
import com.code2hack.pokerdealer.domain.PokerRequestPanelLayout
import com.code2hack.pokerdealer.domain.ServerRequestLocator
import com.code2hack.pokerdealer.domain.ThreadWorkEvidence
import com.code2hack.pokerdealer.domain.UserInputQuestion
import com.code2hack.pokerdealer.domain.UserInputRequest
import com.code2hack.pokerdealer.protocol.POKER_ASR_AUDIO_TYPE
import com.code2hack.pokerdealer.protocol.POKER_ASR_AVAILABILITY_TYPE
import com.code2hack.pokerdealer.protocol.POKER_ASR_COMMIT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_ASR_EXIT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_ASR_START_TYPE
import com.code2hack.pokerdealer.protocol.PokerAsrAvailability
import com.code2hack.pokerdealer.protocol.PokerAsrAudioFrame
import com.code2hack.pokerdealer.protocol.PokerAsrCommitRequest
import com.code2hack.pokerdealer.protocol.PokerAsrCommitResult
import com.code2hack.pokerdealer.protocol.PokerAsrExitRequest
import com.code2hack.pokerdealer.protocol.PokerAsrExitResult
import com.code2hack.pokerdealer.protocol.PokerAsrMutationOutcome
import com.code2hack.pokerdealer.protocol.PokerAsrPackSelection
import com.code2hack.pokerdealer.protocol.PokerAsrSource
import com.code2hack.pokerdealer.protocol.PokerAsrStartOutcome
import com.code2hack.pokerdealer.protocol.PokerAsrStartRequest
import com.code2hack.pokerdealer.protocol.PokerAsrStartResult
import com.code2hack.pokerdealer.protocol.PokerAsrTarget
import com.code2hack.pokerdealer.protocol.PokerAsrTargetField
import com.code2hack.pokerdealer.protocol.PokerProtocolJson
import com.code2hack.pokerdealer.protocol.ProtocolEnvelope
import com.code2hack.pokerdealer.protocol.UserInputRequestProjection
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PokerAsrControllerTest {
    private val sent = mutableListOf<SentMessage>()

    @Before
    fun setUp() {
        PokerAsrBridge.attach { type, payload, _ ->
            sent += SentMessage(type, payload)
            true
        }
        PokerAsrBridge.receive(
            envelope(
                POKER_ASR_AVAILABILITY_TYPE,
                PokerAsrAvailability(true, pack = pack),
            ),
        )
    }

    @After
    fun tearDown() {
        PokerAsrBridge.detach()
    }

    @Test
    fun `preparing binds composer target and commit keeps ASR active at the next cursor`() = runBlocking {
        val harness = composerHarness()
        assertTrue(harness.controller.start())
        assertEquals(PokerAsrState.PREPARING, harness.controller.state)
        assertEquals(0, harness.counters.captures)

        val start = message<PokerAsrStartRequest>(POKER_ASR_START_TYPE)
        assertEquals(harness.target, start.target)
        assertEquals(PokerAsrSource.GLASSES, start.source)
        assertTrue(start.sessionId.isNotBlank())
        assertEquals(PokerAsrTargetField.COMPOSER, start.target.field)
        assertEquals(7L, start.target.targetRevision)
        assertEquals(2, start.target.cursorPosition)
        assertEquals(4L, start.target.controlGeneration)
        assertEquals(5L, start.target.connectionEpoch)
        assertEquals("composer-mode", start.target.modeSession)

        harness.controller.onStartResult(
            PokerAsrStartResult(
                target = start.target.copy(cursorPosition = 0),
                sessionId = start.sessionId,
                outcome = PokerAsrStartOutcome.READY,
                pack = pack,
            ),
        )
        assertEquals(PokerAsrState.PREPARING, harness.controller.state)
        assertEquals(0, harness.counters.captures)

        harness.controller.onStartResult(
            PokerAsrStartResult(start.target, start.sessionId, PokerAsrStartOutcome.READY, pack),
        )
        assertEquals(PokerAsrState.ACTIVE, harness.controller.state)
        assertEquals(1, harness.counters.captures)

        assertTrue(harness.controller.sendAudio(byteArrayOf(1, 0, 2, 0)))
        assertTrue(harness.controller.sendAudio(byteArrayOf(3, 0, 4, 0)))
        assertEquals(listOf(0L, 2L), sent.filterType(POKER_ASR_AUDIO_TYPE)
            .map { message<PokerAsrAudioFrame>(POKER_ASR_AUDIO_TYPE, it).firstSampleOffset })

        harness.controller.handleInteraction(release(PokerOperation.DOWN))
        assertEquals(1, harness.counters.captureStops)
        val commit = message<PokerAsrCommitRequest>(POKER_ASR_COMMIT_TYPE)
        assertEquals(4L, commit.fenceSampleOffset)
        assertTrue(harness.controller.sendAudio(byteArrayOf(5, 0)))
        assertEquals(2, sent.count { it.type == POKER_ASR_AUDIO_TYPE })

        val nextTarget = start.target.copy(targetRevision = 8, cursorPosition = 4)
        harness.controller.onCommitResult(
            PokerAsrCommitResult(
                target = start.target,
                sessionId = start.sessionId,
                operationId = commit.operationId,
                outcome = PokerAsrMutationOutcome.ACKNOWLEDGED,
                committedText = "ok.",
                nextTarget = nextTarget,
            ),
        )
        assertEquals(PokerAsrState.ACTIVE, harness.controller.state)
        assertEquals(2, harness.counters.captures)
        assertEquals(1, harness.counters.captureStops)
    }

    @Test
    fun `request target is exact and preparation exit has no notice`() = runBlocking {
        val locator = CodexThreadLocator("spark", "thread")
        val requestLocator = ServerRequestLocator("spark", 3, UUID.randomUUID().toString())
        val navigation = PokerNavigationReducer(viewportLineCount = 4)
        navigation.attach(
            locator,
            ThreadWorkEvidence(activeTurn = false, unresolvedRequestCount = 1),
            atMs = 1,
            layout = PokerPileLayout(
                cards = listOf(
                    PokerCardLayout(
                        "request-card",
                        collapsedLineCount = 1,
                        requestPanel = PokerRequestPanelLayout("request-panel"),
                    ),
                ),
            ),
        )
        navigation.view(locator)
        val userInput = PokerUserInputController(navigation) { true }
        val request = UserInputRequest(
            locator = requestLocator,
            thread = locator,
            turnId = "turn",
            itemId = "item",
            questions = listOf(
                UserInputQuestion(
                    id = "answer",
                    header = "Answer",
                    question = "What?",
                    options = null,
                    isOther = false,
                    isSecret = false,
                ),
            ),
            autoResolutionMs = null,
            receivedAtMs = 1,
            fingerprint = "fingerprint",
        )
        userInput.applyProjection(
            UserInputRequestProjection(
                request = request,
                cardId = "request-card",
                controlGeneration = 9,
                connectionEpoch = 10,
                modeSession = "request-mode",
            ),
        )
        navigation.apply(PokerOperation.DOWN)
        val target = checkNotNull(userInput.focusedAsrTarget())
        assertEquals(PokerAsrTargetField.REQUEST_TEXT, target.field)
        assertEquals(requestLocator, target.requestLocator)
        assertEquals("answer", target.questionId)
        assertEquals(0L, target.targetRevision)
        assertEquals(0, target.cursorPosition)

        var notices = 0
        val controller = PokerAsrController(
            navigation = navigation,
            userInput = userInput,
            onCaptureRequired = {},
            onCaptureStop = {},
            onExitNotice = { notices++ },
        )
        assertTrue(controller.start())
        val start = message<PokerAsrStartRequest>(POKER_ASR_START_TYPE)
        controller.handleInteraction(release(PokerOperation.FN, durationMs = 500))
        assertEquals(PokerAsrState.EXITING, controller.state)
        val exit = message<PokerAsrExitRequest>(POKER_ASR_EXIT_TYPE)
        controller.onExitResult(
            PokerAsrExitResult(
                target = start.target,
                sessionId = start.sessionId,
                operationId = exit.operationId,
                outcome = PokerAsrMutationOutcome.ACKNOWLEDGED,
            ),
        )
        assertEquals(PokerAsrState.IDLE, controller.state)
        assertEquals(0, notices)
    }

    @Test
    fun `active deliberate exit preserves notice boundary`() = runBlocking {
        val harness = composerHarness()
        assertTrue(harness.controller.start())
        val start = message<PokerAsrStartRequest>(POKER_ASR_START_TYPE)
        harness.controller.onStartResult(
            PokerAsrStartResult(start.target, start.sessionId, PokerAsrStartOutcome.READY, pack),
        )

        harness.controller.handleInteraction(release(PokerOperation.FN, durationMs = 500))
        assertEquals(PokerAsrState.EXITING, harness.controller.state)
        val exit = message<PokerAsrExitRequest>(POKER_ASR_EXIT_TYPE)
        harness.controller.onExitResult(
            PokerAsrExitResult(
                target = start.target,
                sessionId = start.sessionId,
                operationId = exit.operationId,
                outcome = PokerAsrMutationOutcome.ACKNOWLEDGED,
            ),
        )
        assertEquals(PokerAsrState.IDLE, harness.controller.state)
        assertEquals(1, harness.counters.notices)
    }

    private fun composerHarness(): Harness {
        val locator = CodexThreadLocator("spark", "thread")
        val navigation = PokerNavigationReducer(viewportLineCount = 4)
        navigation.attach(
            locator,
            ThreadWorkEvidence(activeTurn = false, unresolvedRequestCount = 0),
            atMs = 1,
            layout = PokerPileLayout(
                cards = listOf(PokerCardLayout("card", collapsedLineCount = 1)),
                composer = PokerComposerLayout(
                    draft = ComposerDraft(revision = 7, elements = listOf(com.code2hack.pokerdealer.domain.ComposerElement.Text("hi"))),
                    controlGeneration = 4,
                    connectionEpoch = 5,
                    modeSession = "composer-mode",
                ),
            ),
        )
        navigation.view(locator)
        assertEquals(
            com.code2hack.pokerdealer.domain.PokerNavigationEffect.ENTERED_COMPOSER,
            navigation.apply(PokerOperation.DOWN),
        )
        val userInput = PokerUserInputController(navigation) { true }
        val counters = Counters()
        return Harness(
            target = PokerAsrTarget(
                locator = locator,
                field = PokerAsrTargetField.COMPOSER,
                targetRevision = 7,
                cursorPosition = 2,
                controlGeneration = 4,
                connectionEpoch = 5,
                modeSession = "composer-mode",
            ),
            controller = PokerAsrController(
                navigation = navigation,
                userInput = userInput,
                onCaptureRequired = { counters.captures++ },
                onCaptureStop = { counters.captureStops++ },
                onExitNotice = { counters.notices++ },
            ),
            counters = counters,
        )
    }

    private fun release(operation: PokerOperation, durationMs: Long = 0) = PokerInteraction(
        source = com.code2hack.pokerdealer.domain.PokerInputSource.GLASSES,
        operation = operation,
        phase = PokerInteractionPhase.RELEASE,
        eventTimeMs = durationMs,
        durationMs = durationMs,
    )

    private inline fun <reified T> message(type: String, message: SentMessage? = null): T {
        val selected = message ?: sent.last { it.type == type }
        return PokerProtocolJson.decodeFromJsonElement(
            serializer<T>(),
            selected.payload,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> serializer(): KSerializer<T> = when (T::class) {
        PokerAsrStartRequest::class -> PokerAsrStartRequest.serializer()
        PokerAsrAudioFrame::class -> PokerAsrAudioFrame.serializer()
        PokerAsrCommitRequest::class -> PokerAsrCommitRequest.serializer()
        PokerAsrExitRequest::class -> PokerAsrExitRequest.serializer()
        else -> error("missing test serializer")
    } as KSerializer<T>

    private fun envelope(type: String, value: PokerAsrAvailability): ProtocolEnvelope = ProtocolEnvelope(
        type = type,
        messageId = UUID.randomUUID().toString(),
        sessionId = "connection",
        sentAtMs = 1,
        sequence = 1,
        payload = PokerProtocolJson.encodeToJsonElement(PokerAsrAvailability.serializer(), value).jsonObject,
    )

    private fun List<SentMessage>.filterType(type: String) = filter { it.type == type }

    private data class SentMessage(val type: String, val payload: JsonObject)

    private data class Harness(
        val target: PokerAsrTarget,
        val controller: PokerAsrController,
        val counters: Counters,
    )

    private data class Counters(
        var captures: Int = 0,
        var captureStops: Int = 0,
        var notices: Int = 0,
    )

    private companion object {
        val pack = PokerAsrPackSelection(
            packId = "parakeet",
            revision = "r1",
            profile = JsonObject(mapOf("pausePunctuation" to JsonPrimitive("."))),
        )
    }
}
