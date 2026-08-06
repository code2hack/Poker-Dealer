package com.code2hack.poker

import com.code2hack.pokerdealer.domain.ComposerDraft
import com.code2hack.pokerdealer.domain.ComposerElement
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.PokerCardLayout
import com.code2hack.pokerdealer.domain.PokerComposerLayout
import com.code2hack.pokerdealer.domain.PokerNavigationReducer
import com.code2hack.pokerdealer.domain.PokerPileLayout
import com.code2hack.pokerdealer.domain.ThreadWorkEvidence
import com.code2hack.pokerdealer.domain.shortComposerDeletion
import com.code2hack.pokerdealer.protocol.ComposerDraftProjection
import com.code2hack.pokerdealer.protocol.ComposerMutationRequest
import com.code2hack.pokerdealer.protocol.ComposerMutationOutcome
import com.code2hack.pokerdealer.protocol.ComposerMutationResult
import com.code2hack.pokerdealer.protocol.ComposerMutationKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PokerComposerControllerTest {
    @Test
    fun `projection is edited optimistically and blocks a second pending mutation`() = runBlocking {
        val locator = CodexThreadLocator("spark", "thread")
        val navigation = PokerNavigationReducer(viewportLineCount = 4)
        navigation.attach(
            locator = locator,
            evidence = ThreadWorkEvidence(activeTurn = false, unresolvedRequestCount = 0),
            atMs = 1,
            layout = PokerPileLayout(
                cards = listOf(PokerCardLayout("card", collapsedLineCount = 1)),
                composer = PokerComposerLayout(),
            ),
        )
        var sent = 0
        val controller = PokerComposerController(navigation) {
            sent++
            true
        }
        controller.applyProjection(
            ComposerDraftProjection(
                locator = locator,
                draft = ComposerDraft.fromText("hello world"),
                controlGeneration = 2,
                connectionEpoch = 4,
                modeSession = "mode-1",
            ),
        )
        navigation.view(locator)
        navigation.apply(com.code2hack.pokerdealer.domain.PokerOperation.DOWN)
        navigation.setComposerCursor(locator, 0)

        val first = checkNotNull(navigation.shortComposerDeletion(locator))
        val firstTarget = checkNotNull(first.target)
        assertFalse(
            controller.requestDeletion(
                first.copy(target = firstTarget.copy(
                    surface = com.code2hack.pokerdealer.domain.ComposerSurface.REQUEST_PANEL,
                )),
            ),
        )
        assertFalse(
            controller.requestDeletion(
                first.copy(target = firstTarget.copy(controlGeneration = 3)),
            ),
        )
        assertTrue(controller.requestDeletion(first))
        assertEquals("world", navigation.layout(locator)?.composer?.draft?.displayText)

        val second = checkNotNull(navigation.shortComposerDeletion(locator))
        assertFalse(controller.requestDeletion(second))
        assertEquals(1, sent)

        controller.applyResult(
            ComposerMutationResult(
                target = firstTarget,
                outcome = ComposerMutationOutcome.ACKNOWLEDGED,
                draft = ComposerDraft.fromText("world").withRevision(1),
            ),
        )
        assertEquals("world", navigation.layout(locator)?.composer?.draft?.displayText)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `photo deletion is pessimistic, locks the draft, and fences timeout`() = runTest {
        val locator = CodexThreadLocator("spark", "photo-thread")
        val navigation = PokerNavigationReducer(viewportLineCount = 4)
        val draft = ComposerDraft(
            elements = listOf(ComposerElement.Photo("asset-1"), ComposerElement.Text("text")),
        )
        navigation.attach(
            locator = locator,
            evidence = ThreadWorkEvidence(activeTurn = false, unresolvedRequestCount = 0),
            atMs = 1,
            layout = PokerPileLayout(
                cards = listOf(PokerCardLayout("card", collapsedLineCount = 1)),
                composer = PokerComposerLayout(),
            ),
        )
        val sent = mutableListOf<ComposerMutationRequest>()
        val notices = mutableListOf<Pair<String, Long>>()
        val controller = PokerComposerController(
            navigation = navigation,
            scope = this,
            sendMutation = { request -> sent += request; true },
            onNotice = { message, durationMs -> notices += message to durationMs },
        )
        controller.applyProjection(
            ComposerDraftProjection(
                locator = locator,
                draft = draft,
                controlGeneration = 2,
                connectionEpoch = 3,
                modeSession = "mode",
            ),
        )
        navigation.view(locator)
        navigation.apply(com.code2hack.pokerdealer.domain.PokerOperation.DOWN)
        navigation.setComposerCursor(locator, 0)

        val deletion = checkNotNull(navigation.shortComposerDeletion(locator))
        assertTrue(controller.requestDeletion(deletion))
        runCurrent()
        assertEquals(draft, navigation.layout(locator)?.composer?.draft)
        assertEquals(1, sent.size)
        assertEquals(ComposerMutationKind.DELETE_PHOTO, sent.single().kind)
        assertTrue(controller.hasPendingDraftMutation(locator))
        assertFalse(controller.photoAvailable(locator))

        advanceTimeBy(POKER_PHOTO_DELETE_TIMEOUT_MS)
        runCurrent()
        assertFalse(controller.hasPendingDraftMutation(locator))
        assertEquals(draft, navigation.layout(locator)?.composer?.draft)
        assertEquals(listOf("Photo not deleted" to 1_000L), notices)

        controller.applyResult(
            ComposerMutationResult(
                target = checkNotNull(deletion.target),
                outcome = ComposerMutationOutcome.ACKNOWLEDGED,
                draft = ComposerDraft(revision = 1, elements = listOf(ComposerElement.Text("text"))),
            ),
        )
        assertEquals(draft, navigation.layout(locator)?.composer?.draft)
    }
}
