package com.code2hack.pokerdealer.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThreadWorkProjectionTest {
    private val busy = CodexThreadLocator("u4090", "busy")
    private val attentionOld = CodexThreadLocator("spark", "attention-old")
    private val attentionTie = CodexThreadLocator("u4090", "attention-tie")
    private val readyOld = CodexThreadLocator("spark", "ready-old")
    private val readyTie = CodexThreadLocator("u4090", "ready-tie")
    private val busyOld = CodexThreadLocator("spark", "busy-old")

    @Test
    fun `authoritative evidence derives only the three work states`() {
        assertEquals(ThreadWorkState.BUSY, evidence(active = true).workState())
        assertEquals(ThreadWorkState.ATTENTION_REQUIRED, evidence(active = true, requests = 1).workState())
        assertEquals(ThreadWorkState.READY, evidence(active = false).workState())
        assertNull(evidence(active = null).workState())
        assertNull(evidence(active = false, requests = 1).workState())
    }

    @Test
    fun `piles use state age with stable attachment order ties`() {
        val reducer = ThreadPileReducer()
        reducer.attach(readyTie, evidence(false), atMs = 30)
        reducer.attach(attentionTie, evidence(true), atMs = 20)
        reducer.attach(busy, evidence(true), atMs = 99)
        reducer.attach(attentionOld, evidence(true), atMs = 10)
        reducer.attach(readyOld, evidence(false), atMs = 30)
        reducer.attach(busyOld, evidence(true), atMs = 1)
        reducer.transition(attentionTie, evidence(true, 1), atMs = 40)
        reducer.transition(attentionOld, evidence(true, 1), atMs = 40)

        assertEquals(
            listOf(busyOld, busy, attentionTie, attentionOld, readyTie, readyOld),
            reducer.metadata().orderedPiles.map(ThreadPile::locator),
        )

        reducer.acceptedPromptOrSteer(busyOld, atMs = 100)
        assertEquals(
            listOf(busy, busyOld, attentionTie, attentionOld, readyTie, readyOld),
            reducer.metadata().orderedPiles.map(ThreadPile::locator),
        )

        reducer.transition(busyOld, evidence(true), atMs = 101)
        assertEquals(
            listOf(busy, busyOld, attentionTie, attentionOld, readyTie, readyOld),
            reducer.metadata().orderedPiles.map(ThreadPile::locator),
        )
    }

    @Test
    fun `unknown state is separate and cannot pass a ready gate`() {
        val reducer = ThreadPileReducer()
        reducer.attach(busy, evidence(active = null), atMs = 1)

        assertEquals(listOf(busy), reducer.metadata().unknownWorkState.map(ThreadPile::locator))
        assertFalse(reducer.readyGatedActionAllowed(busy))
    }

    @Test
    fun `manual hide survives existing eligibility and a new transition wakes highest priority`() {
        val reducer = ThreadPileReducer()
        reducer.attach(readyOld, evidence(false), atMs = 1)
        reducer.attach(attentionOld, evidence(true), atMs = 2)
        reducer.manualWake()
        assertEquals(readyOld, reducer.metadata().focused)

        reducer.manualHide()
        reducer.reconcile(readyOld, evidence(false), atMs = 3, available = true)
        assertFalse(reducer.metadata().hudVisible)

        reducer.transition(attentionOld, evidence(true, 1), atMs = 4)
        assertTrue(reducer.metadata().hudVisible)
        assertEquals(attentionOld, reducer.metadata().focused)
    }

    @Test
    fun `visible HUD preserves focus through reorder and accepted input`() {
        val reducer = ThreadPileReducer()
        reducer.attach(readyOld, evidence(false), atMs = 1)
        reducer.attach(attentionOld, evidence(true), atMs = 2)
        reducer.attach(readyTie, evidence(false), atMs = 3)
        reducer.manualWake()

        reducer.transition(attentionOld, evidence(true, 1), atMs = 4)
        assertEquals(readyOld, reducer.metadata().focused)

        reducer.manualHide()
        reducer.manualWake()
        assertEquals(attentionOld, reducer.metadata().focused)
        reducer.view(readyOld)
        reducer.transition(readyOld, evidence(true), atMs = 5)
        assertEquals(readyOld, reducer.metadata().focused)
        assertTrue(reducer.metadata().hudVisible)
        reducer.acceptedPromptOrSteer(readyOld, atMs = 6)
        assertEquals(readyOld, reducer.metadata().focused)
        reducer.acceptedPromptOrSteer(attentionOld, atMs = 7)
        assertEquals(readyOld, reducer.metadata().focused)
        reducer.acceptedPromptOrSteer(readyTie, atMs = 8)
        assertEquals(readyOld, reducer.metadata().focused)
        assertTrue(reducer.metadata().hudVisible)
    }

    @Test
    fun `focus movement stops at horizontal boundaries`() {
        val reducer = ThreadPileReducer()
        reducer.attach(busy, evidence(true), atMs = 1)
        reducer.attach(attentionOld, evidence(true, 1), atMs = 2)
        reducer.attach(readyOld, evidence(false), atMs = 3)
        reducer.view(attentionOld)

        assertTrue(reducer.moveFocus(PileDirection.LEFT))
        assertEquals(busy, reducer.metadata().focused)
        assertFalse(reducer.moveFocus(PileDirection.LEFT))
        assertEquals(busy, reducer.metadata().focused)
        assertTrue(reducer.moveFocus(PileDirection.RIGHT))
        assertEquals(attentionOld, reducer.metadata().focused)
        assertTrue(reducer.moveFocus(PileDirection.RIGHT))
        assertEquals(readyOld, reducer.metadata().focused)
        assertFalse(reducer.moveFocus(PileDirection.RIGHT))
        assertEquals(readyOld, reducer.metadata().focused)
    }

    @Test
    fun `removing the focused pile selects the new occupant then the preceding pile`() {
        val reducer = ThreadPileReducer()
        reducer.attach(busy, evidence(true), atMs = 1)
        reducer.attach(attentionOld, evidence(true, 1), atMs = 2)
        reducer.attach(readyOld, evidence(false), atMs = 3)

        reducer.view(attentionOld)
        reducer.detach(attentionOld)
        assertEquals(readyOld, reducer.metadata().focused)
        assertTrue(reducer.metadata().hudVisible)

        reducer.detach(readyOld)
        assertEquals(busy, reducer.metadata().focused)
        reducer.detach(busy)
        assertNull(reducer.metadata().focused)
        assertFalse(reducer.metadata().hudVisible)
    }

    @Test
    fun `failure interruption availability and reconnect baseline follow focus policy`() {
        val reducer = ThreadPileReducer()
        reducer.attach(busy, evidence(true), atMs = 1)
        reducer.attach(readyOld, evidence(false), atMs = 2, available = false)
        reducer.reconcile(busy, evidence(false), atMs = 3, available = true)
        assertFalse(reducer.metadata().hudVisible)

        reducer.turnEnded(busy, TurnOutcome.FAILED, atMs = 4)
        assertTrue(reducer.metadata().hudVisible)
        assertEquals(TurnOutcome.FAILED, reducer.metadata().orderedPiles.single { it.locator == busy }.outcome)

        reducer.manualHide()
        reducer.transition(busy, evidence(true), atMs = 5)
        reducer.turnEnded(busy, TurnOutcome.INTERRUPTED, atMs = 6)
        assertEquals(busy, reducer.metadata().focused)
        assertEquals(TurnOutcome.INTERRUPTED, reducer.metadata().orderedPiles.single { it.locator == busy }.outcome)

        reducer.setAvailable(busy, false)
        assertTrue(reducer.metadata().hudVisible)
        assertEquals(busy, reducer.metadata().focused)
        reducer.manualHide()
        reducer.manualWake()
        assertFalse(reducer.metadata().hudVisible)
        reducer.view(readyOld)
        assertEquals(readyOld, reducer.metadata().focused)
    }

    private fun evidence(active: Boolean?, requests: Int? = 0) =
        ThreadWorkEvidence(activeTurn = active, unresolvedRequestCount = requests)
}
