package com.code2hack.poker

import com.code2hack.pokerdealer.domain.ComposerDraft
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.PokerCardLayout
import com.code2hack.pokerdealer.domain.PokerComposerLayout
import com.code2hack.pokerdealer.domain.PokerNavigationEffect
import com.code2hack.pokerdealer.domain.PokerNavigationReducer
import com.code2hack.pokerdealer.domain.PokerOperation
import com.code2hack.pokerdealer.domain.PokerPileLayout
import com.code2hack.pokerdealer.domain.PokerPrimaryAction
import com.code2hack.pokerdealer.domain.PokerWheelAction
import com.code2hack.pokerdealer.domain.PokerWheelSelection
import com.code2hack.pokerdealer.domain.ServerRequestLocator
import com.code2hack.pokerdealer.domain.ThreadWorkEvidence
import com.code2hack.pokerdealer.domain.UserInputAnswerBuffer
import com.code2hack.pokerdealer.domain.UserInputAnswerEdit
import com.code2hack.pokerdealer.domain.UserInputQuestion
import com.code2hack.pokerdealer.domain.UserInputRequest
import com.code2hack.pokerdealer.protocol.ComposerDraftProjection
import com.code2hack.pokerdealer.protocol.PokerPrimaryActionOutcome
import com.code2hack.pokerdealer.protocol.PokerPrimaryActionResult
import com.code2hack.pokerdealer.protocol.PokerPrimaryActionTarget
import com.code2hack.pokerdealer.protocol.UserInputRequestProjection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PokerPrimaryActionControllerTest {
    @Test
    fun `primary meaning follows request ready busy and attention state`() = runBlocking {
        val ready = controller(
            activeTurnId = null,
            draft = ComposerDraft.fromText("send me"),
            activeTurn = false,
        )
        assertEquals(PokerPrimaryAction.SEND, ready.primary.wheelContext().primaryAction)

        val steer = controller(
            activeTurnId = "turn-1",
            draft = ComposerDraft.fromText("steer me"),
            activeTurn = true,
        )
        assertEquals(PokerPrimaryAction.STEER, steer.primary.wheelContext().primaryAction)

        val interrupt = controller(
            activeTurnId = "turn-1",
            draft = ComposerDraft(),
            activeTurn = true,
        )
        assertEquals(PokerPrimaryAction.INTERRUPT, interrupt.primary.wheelContext().primaryAction)

        val attention = controller(
            activeTurnId = "turn-1",
            draft = ComposerDraft.fromText("not yet"),
            activeTurn = true,
            unresolvedRequestCount = 1,
        )
        assertNull(attention.primary.wheelContext().primaryAction)
    }

    @Test
    fun `takeover fences an old wheel selection before the action is sent`() = runBlocking {
        val fixture = controller(
            activeTurnId = null,
            draft = ComposerDraft.fromText("send me"),
            activeTurn = false,
        )
        val oldContext = fixture.primary.wheelContext()
        val selection = PokerWheelSelection(
            sessionId = "wheel-old",
            action = PokerWheelAction.PRIMARY,
            primaryAction = PokerPrimaryAction.SEND,
            context = oldContext,
        )

        fixture.composer.applyProjection(
            ComposerDraftProjection(
                locator = fixture.locator,
                draft = ComposerDraft.fromText("send me"),
                controlGeneration = 2,
                connectionEpoch = 2,
                modeSession = "mode",
            ),
        )

        assertFalse(fixture.primary.submit(selection))
        assertTrue(fixture.sent.isEmpty())
    }

    @Test
    fun `request Primary submits a complete answer once and locks the panel`() = runBlocking {
        val thread = CodexThreadLocator("spark", "request-thread")
        val navigation = PokerNavigationReducer(viewportLineCount = 4)
        navigation.attach(
            locator = thread,
            evidence = ThreadWorkEvidence(activeTurn = true, unresolvedRequestCount = 1),
            atMs = 1,
            layout = PokerPileLayout(listOf(PokerCardLayout("card", collapsedLineCount = 1))),
        )
        val request = UserInputRequest(
            locator = ServerRequestLocator("spark", 1, "request"),
            thread = thread,
            turnId = "turn-1",
            itemId = "item",
            questions = listOf(
                UserInputQuestion(
                    id = "choice",
                    header = "Host",
                    question = "Which host?",
                    options = listOf(com.code2hack.pokerdealer.domain.UserInputOption("Spark", "DGX")),
                    isOther = false,
                    isSecret = false,
                ),
            ),
            autoResolutionMs = null,
            receivedAtMs = 1,
            fingerprint = "fingerprint",
        )
        val buffer = UserInputAnswerBuffer().edit(
            request,
            "choice",
            UserInputAnswerEdit.SelectOption("Spark"),
        )
        val userInput = PokerUserInputController(navigation) { true }
        userInput.applyProjection(
            UserInputRequestProjection(
                request = request,
                buffer = buffer,
                cardId = "card",
                controlGeneration = 1,
                connectionEpoch = 2,
                modeSession = "mode",
            ),
        )
        navigation.view(thread)
        assertEquals(PokerNavigationEffect.ENTERED_REQUEST_PANEL, navigation.apply(PokerOperation.DOWN))
        val composer = PokerComposerController(navigation) { true }
        val sent = mutableListOf<PokerPrimaryActionTarget>()
        val primary = PokerPrimaryActionController(
            navigation = navigation,
            composer = composer,
            userInput = userInput,
            sendAction = { sent += it; true },
        )
        val context = primary.wheelContext()
        val selection = PokerWheelSelection(
            sessionId = "wheel-request",
            action = PokerWheelAction.PRIMARY,
            primaryAction = PokerPrimaryAction.REQUEST,
            context = context,
        )

        assertEquals(PokerPrimaryAction.REQUEST, context.primaryAction)
        assertTrue(primary.submit(selection))
        assertTrue(userInput.isPrimaryLocked(request.locator))
        assertFalse(primary.submit(selection))
        assertEquals(1, sent.size)
    }

    @Test
    fun `unknown Send stays locked until draft reconciliation and never replays`() = runBlocking {
        val fixture = controller(
            activeTurnId = null,
            draft = ComposerDraft.fromText("send me"),
            activeTurn = false,
        )
        val firstContext = fixture.primary.wheelContext()
        val firstSelection = PokerWheelSelection(
            sessionId = "wheel-send",
            action = PokerWheelAction.PRIMARY,
            primaryAction = PokerPrimaryAction.SEND,
            context = firstContext,
        )
        assertTrue(fixture.primary.submit(firstSelection))
        val target = fixture.sent.single()

        fixture.primary.applyResult(
            PokerPrimaryActionResult(target, PokerPrimaryActionOutcome.UNKNOWN),
        )
        assertNull(fixture.primary.wheelContext().primaryAction)
        assertEquals(PokerNavigationEffect.NONE, fixture.navigation.apply(PokerOperation.UP))
        assertFalse(fixture.primary.submit(firstSelection))
        assertEquals(1, fixture.sent.size)

        fixture.composer.applyProjection(
            ComposerDraftProjection(
                locator = fixture.locator,
                draft = ComposerDraft.fromText("send me again").withRevision(1),
                controlGeneration = 1,
                connectionEpoch = 2,
                modeSession = "mode",
            ),
        )
        val reconciledContext = fixture.primary.wheelContext()
        assertEquals(PokerPrimaryAction.SEND, reconciledContext.primaryAction)
        assertTrue(
            fixture.primary.submit(
                PokerWheelSelection(
                    "wheel-reconciled",
                    PokerWheelAction.PRIMARY,
                    PokerPrimaryAction.SEND,
                    reconciledContext,
                ),
            ),
        )
        assertEquals(2, fixture.sent.size)
    }

    @Test
    fun `unknown Interrupt locks only Primary and never submits text prepared meanwhile`() = runBlocking {
        val fixture = controller(
            activeTurnId = "turn-1",
            draft = ComposerDraft(),
            activeTurn = true,
        )
        val context = fixture.primary.wheelContext()
        val selection = PokerWheelSelection(
            sessionId = "wheel-interrupt",
            action = PokerWheelAction.PRIMARY,
            primaryAction = PokerPrimaryAction.INTERRUPT,
            context = context,
        )
        assertTrue(fixture.primary.submit(selection))
        fixture.primary.applyResult(
            PokerPrimaryActionResult(fixture.sent.single(), PokerPrimaryActionOutcome.UNKNOWN),
        )
        assertNull(fixture.primary.wheelContext().primaryAction)
        assertFalse(checkNotNull(fixture.navigation.layout(fixture.locator)?.composer).primaryActionLocked)

        fixture.composer.applyProjection(
            ComposerDraftProjection(
                locator = fixture.locator,
                draft = ComposerDraft.fromText("prepared").withRevision(1),
                controlGeneration = 1,
                connectionEpoch = 2,
                modeSession = "mode",
                activeTurnId = null,
            ),
        )
        fixture.navigation.reconcile(
            fixture.locator,
            ThreadWorkEvidence(activeTurn = false, unresolvedRequestCount = 0),
            atMs = 3,
            available = true,
        )
        assertEquals(PokerPrimaryAction.SEND, fixture.primary.wheelContext().primaryAction)
        assertEquals(1, fixture.sent.size)
    }

    private data class ComposerFixture(
        val locator: CodexThreadLocator,
        val navigation: PokerNavigationReducer,
        val composer: PokerComposerController,
        val primary: PokerPrimaryActionController,
        val sent: MutableList<PokerPrimaryActionTarget>,
    )

    private fun controller(
        activeTurnId: String?,
        draft: ComposerDraft,
        activeTurn: Boolean,
        unresolvedRequestCount: Int = 0,
    ): ComposerFixture {
        val locator = CodexThreadLocator("spark", "thread-${activeTurnId ?: "ready"}")
        val navigation = PokerNavigationReducer(viewportLineCount = 4)
        navigation.attach(
            locator = locator,
            evidence = ThreadWorkEvidence(activeTurn = activeTurn, unresolvedRequestCount = unresolvedRequestCount),
            atMs = 1,
            layout = PokerPileLayout(
                cards = listOf(PokerCardLayout("card", collapsedLineCount = 1)),
                composer = PokerComposerLayout(),
            ),
        )
        val composer = PokerComposerController(navigation) { true }
        composer.applyProjection(
            ComposerDraftProjection(
                locator = locator,
                draft = draft,
                controlGeneration = 1,
                connectionEpoch = 2,
                modeSession = "mode",
                activeTurnId = activeTurnId,
            ),
        )
        navigation.view(locator)
        assertEquals(PokerNavigationEffect.ENTERED_COMPOSER, navigation.apply(PokerOperation.DOWN))
        val userInput = PokerUserInputController(navigation) { true }
        val sent = mutableListOf<PokerPrimaryActionTarget>()
        val primary = PokerPrimaryActionController(
            navigation = navigation,
            composer = composer,
            userInput = userInput,
            sendAction = { sent += it; true },
        )
        return ComposerFixture(locator, navigation, composer, primary, sent)
    }
}
