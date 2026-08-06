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
import com.code2hack.pokerdealer.domain.ThreadWorkEvidence
import com.code2hack.pokerdealer.protocol.ComposerDraftProjection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PokerPrimaryActionControllerTest {
    @Test
    fun `primary meaning follows ready and busy composer state`() = runBlocking {
        val ready = controller(
            activeTurnId = null,
            draft = ComposerDraft.fromText("send me"),
            activeTurn = false,
        )
        assertEquals(PokerPrimaryAction.SEND, ready.second.wheelContext().primaryAction)

        val busy = controller(
            activeTurnId = "turn-1",
            draft = ComposerDraft(),
            activeTurn = true,
        )
        assertEquals(PokerPrimaryAction.INTERRUPT, busy.second.wheelContext().primaryAction)
    }

    private fun controller(
        activeTurnId: String?,
        draft: ComposerDraft,
        activeTurn: Boolean,
    ): Pair<PokerNavigationReducer, PokerPrimaryActionController> {
        val locator = CodexThreadLocator("spark", "thread-${activeTurnId ?: "ready"}")
        val navigation = PokerNavigationReducer(viewportLineCount = 4)
        navigation.attach(
            locator = locator,
            evidence = ThreadWorkEvidence(activeTurn = activeTurn, unresolvedRequestCount = 0),
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
        return navigation to PokerPrimaryActionController(
            navigation = navigation,
            composer = composer,
            userInput = userInput,
            sendAction = { true },
        )
    }
}
