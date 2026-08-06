package com.code2hack.pokerdealer.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PokerInputControllerTest {
    private val locator = CodexThreadLocator("spark", "thread")
    private val context = PokerWheelContext(
        targetId = "thread|composer|0|SEND",
        controlGeneration = 1L,
        connectionEpoch = 2L,
        modeSession = "mode-1",
        primaryAction = PokerPrimaryAction.SEND,
    )

    @Test
    fun `short FN requests deletion while long FN confirms the stable Primary sector`() {
        val navigation = navigation()
        val controller = PokerInputController(navigation, wheelContext = { context })
        controller.updatePosture(PokerPostureSample(0f, 0f, 0L))

        assertNotNull(controller.reduce(fn(PokerInteractionPhase.BEGIN, 10L)))
        val shortRelease = checkNotNull(controller.reduce(fn(PokerInteractionPhase.RELEASE, 100L)))
        assertEquals(PokerNavigationEffect.COMPOSER_DELETE_REQUESTED, shortRelease.navigationEffect)
        assertNotNull(shortRelease.composerDeletion)
        assertNull(shortRelease.wheelSelection)
        assertTrue(shortRelease.wheelState.cancelled)

        controller.updatePosture(PokerPostureSample(0f, 0f, 200L))
        assertNotNull(controller.reduce(fn(PokerInteractionPhase.BEGIN, 200L)))
        controller.updatePosture(PokerPostureSample(-30f, 0f, 700L))
        controller.updatePosture(PokerPostureSample(-30f, 0f, 800L))
        val longRelease = checkNotNull(controller.reduce(fn(PokerInteractionPhase.RELEASE, 801L)))

        assertEquals(PokerWheelAction.PRIMARY, longRelease.wheelSelection?.action)
        assertEquals(PokerPrimaryAction.SEND, longRelease.wheelSelection?.primaryAction)
    }

    @Test
    fun `focus loss cancels a held wheel and cannot release it afterward`() {
        val controller = PokerInputController(navigation(), wheelContext = { context })
        controller.updatePosture(PokerPostureSample(0f, 0f, 0L))
        controller.reduce(fn(PokerInteractionPhase.BEGIN, 10L))
        controller.updatePosture(PokerPostureSample(-30f, 0f, 600L))

        val cancelled = checkNotNull(controller.onFocusLost(601L))
        assertTrue(cancelled.wheelState.cancelled)
        assertNull(controller.reduce(fn(PokerInteractionPhase.RELEASE, 700L)))
    }

    private fun fn(phase: PokerInteractionPhase, eventTimeMs: Long) = PokerInteraction(
        source = PokerInputSource.GLASSES,
        operation = PokerOperation.FN,
        phase = phase,
        eventTimeMs = eventTimeMs,
    )

    private fun navigation() = PokerNavigationReducer(viewportLineCount = 3).also {
        it.attach(
            locator = locator,
            evidence = ThreadWorkEvidence(activeTurn = false, unresolvedRequestCount = 0),
            atMs = 1L,
            layout = PokerPileLayout(
                cards = listOf(PokerCardLayout("card", collapsedLineCount = 1)),
                composer = PokerComposerLayout(
                    draft = ComposerDraft.fromText("hello world"),
                    controlGeneration = context.controlGeneration,
                    connectionEpoch = context.connectionEpoch,
                    modeSession = context.modeSession,
                ),
            ),
        )
        it.view(locator)
        assertEquals(PokerNavigationEffect.ENTERED_COMPOSER, it.apply(PokerOperation.DOWN))
        assertEquals(PokerNavigationEffect.SCROLLED, it.apply(PokerOperation.UP))
    }
}
