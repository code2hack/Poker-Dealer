package com.code2hack.poker

import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ThreadPileReducer
import com.code2hack.pokerdealer.domain.ThreadWorkEvidence
import com.code2hack.pokerdealer.domain.ThreadWorkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PokerPilePagesRenderingTest {
    @Test
    fun `visible page uses focused locator content rather than an index or label row`() {
        val busy = CodexThreadLocator("spark", "busy")
        val focused = CodexThreadLocator("u4090", "focused")
        val ready = CodexThreadLocator("fold6-termux", "ready")
        val cardText = mapOf(
            busy to "busy card",
            focused to "focused card",
            ready to "ready card",
        )
        val reducer = ThreadPileReducer()
        reducer.attach(busy, ThreadWorkEvidence(activeTurn = true, unresolvedRequestCount = 0), atMs = 1)
        reducer.attach(focused, ThreadWorkEvidence(activeTurn = false, unresolvedRequestCount = 0), atMs = 2)
        reducer.attach(ready, ThreadWorkEvidence(activeTurn = false, unresolvedRequestCount = 0), atMs = 3)
        reducer.view(focused)

        val projection = reducer.metadata().toPokerPileRenderProjection(cardText)

        assertEquals(listOf(busy, focused, ready), projection.orderedPages.map(PokerPilePage::locator))
        assertEquals(focused, projection.visiblePage?.locator)
        assertEquals("focused card", projection.visiblePage?.cardText)
        assertFalse(projection.visiblePage?.cardText.orEmpty().contains("ATTENTION_REQUIRED"))
        assertFalse(projection.visiblePage?.cardText.orEmpty().contains("u4090/focused"))
    }

    @Test
    fun `focused card content survives work-state reorder by locator`() {
        val busy = CodexThreadLocator("spark", "busy")
        val focused = CodexThreadLocator("u4090", "focused")
        val attention = CodexThreadLocator("fold6-termux", "attention")
        val cardText = mapOf(busy to "busy card", focused to "focused card", attention to "attention card")
        val reducer = ThreadPileReducer()
        reducer.attach(busy, ThreadWorkEvidence(activeTurn = true, unresolvedRequestCount = 0), atMs = 1)
        reducer.attach(focused, ThreadWorkEvidence(activeTurn = false, unresolvedRequestCount = 0), atMs = 2)
        reducer.attach(attention, ThreadWorkEvidence(activeTurn = true, unresolvedRequestCount = 1), atMs = 3)
        reducer.view(focused)

        val before = reducer.metadata().toPokerPileRenderProjection(cardText)
        reducer.acceptedPromptOrSteer(focused, atMs = 4)
        val after = reducer.metadata().toPokerPileRenderProjection(cardText)

        assertNotEquals(before.orderedPages.map(PokerPilePage::locator), after.orderedPages.map(PokerPilePage::locator))
        assertEquals(focused, after.focusedLocator)
        assertEquals(focused, after.visiblePage?.locator)
        assertEquals("focused card", after.visiblePage?.cardText)
        assertEquals(ThreadWorkState.BUSY, after.visiblePage?.workState)
    }

    @Test
    fun `focused page follows reducer nearest neighbor after removal`() {
        val busy = CodexThreadLocator("spark", "busy")
        val focused = CodexThreadLocator("u4090", "focused")
        val ready = CodexThreadLocator("fold6-termux", "ready")
        val cardText = mapOf(busy to "busy card", focused to "focused card", ready to "ready card")
        val reducer = ThreadPileReducer()
        reducer.attach(busy, ThreadWorkEvidence(activeTurn = true, unresolvedRequestCount = 0), atMs = 1)
        reducer.attach(focused, ThreadWorkEvidence(activeTurn = true, unresolvedRequestCount = 1), atMs = 2)
        reducer.attach(ready, ThreadWorkEvidence(activeTurn = false, unresolvedRequestCount = 0), atMs = 3)
        reducer.view(focused)

        reducer.detach(focused)
        val afterRightNeighbor = reducer.metadata().toPokerPileRenderProjection(cardText)
        assertEquals(ready, afterRightNeighbor.focusedLocator)
        assertEquals("ready card", afterRightNeighbor.visiblePage?.cardText)

        reducer.detach(ready)
        val afterPrecedingNeighbor = reducer.metadata().toPokerPileRenderProjection(cardText)
        assertEquals(busy, afterPrecedingNeighbor.focusedLocator)
        assertEquals("busy card", afterPrecedingNeighbor.visiblePage?.cardText)
    }
}
