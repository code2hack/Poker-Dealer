package com.code2hack.poker

import com.code2hack.pokerdealer.domain.ComposerDraft
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.PokerCardLayout
import com.code2hack.pokerdealer.domain.PokerComposerLayout
import com.code2hack.pokerdealer.domain.PokerNavigationReducer
import com.code2hack.pokerdealer.domain.PokerPileLayout
import com.code2hack.pokerdealer.domain.ThreadWorkEvidence
import com.code2hack.pokerdealer.domain.shortComposerDeletion
import com.code2hack.pokerdealer.protocol.ComposerDraftProjection
import com.code2hack.pokerdealer.protocol.ComposerMutationOutcome
import com.code2hack.pokerdealer.protocol.ComposerMutationResult
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
}
