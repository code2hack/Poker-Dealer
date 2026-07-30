package com.code2hack.pokerdealer.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThreadActionsTest {
    private val thread = CodexThreadLocator("u4090", "thread")

    @Test
    fun `composer starts ready work steers the observed busy turn and blocks attention`() {
        val draft = ThreadActionState().editDraft(thread, "hello")
        val (_, start) = draft.beginInput(thread, ThreadWorkState.READY, null, true, "start")
        val (_, steer) = draft.beginInput(thread, ThreadWorkState.BUSY, "turn-1", true, "steer")

        assertEquals(ComposerAction.START, start.action)
        assertEquals(null, start.expectedTurnId)
        assertEquals(ComposerAction.STEER, steer.action)
        assertEquals("turn-1", steer.expectedTurnId)
        assertThrows(IllegalArgumentException::class.java) {
            draft.beginInput(thread, ThreadWorkState.ATTENTION_REQUIRED, "turn-1", true, "blocked")
        }
        assertThrows(IllegalArgumentException::class.java) {
            draft.beginInput(thread, ThreadWorkState.BUSY, null, true, "stale")
        }
    }

    @Test
    fun `draft clears only for the exact accepted action and uncertain input locks duplicates`() {
        val (pending, action) = ThreadActionState()
            .editDraft(thread, "durable")
            .beginInput(thread, ThreadWorkState.READY, null, true, "client-1")
        val uncertain = pending.inputUncertain(thread, action.clientId)

        assertEquals("durable", uncertain.drafts[thread])
        assertTrue(uncertain.pendingInputs.getValue(thread).uncertain)
        assertThrows(IllegalArgumentException::class.java) {
            uncertain.beginInput(thread, ThreadWorkState.READY, null, true, "client-2")
        }
        assertEquals("durable", pending.inputRejected(thread, action.clientId).drafts[thread])
        assertEquals(null, pending.inputAccepted(thread, action.clientId).drafts[thread])
        assertEquals(
            "new edit",
            pending.editDraft(thread, "new edit").inputAccepted(thread, action.clientId).drafts[thread],
        )
    }

    @Test
    fun `interrupt stays bound to one observed turn until authoritative state changes`() {
        val (pending, turnId) = ThreadActionState().beginInterrupt(thread, "turn-1", true)

        assertEquals("turn-1", turnId)
        assertThrows(IllegalArgumentException::class.java) {
            pending.beginInterrupt(thread, "turn-2", true)
        }
        assertEquals("turn-1", pending.reconcileInterrupt(thread, "turn-1").pendingInterrupts[thread])
        assertEquals(null, pending.reconcileInterrupt(thread, "turn-2").pendingInterrupts[thread])
    }

    @Test
    fun `drafts and locks are isolated by host qualified thread`() {
        val sameIdOtherHost = CodexThreadLocator("spark", "thread")
        val state = ThreadActionState()
            .editDraft(thread, "u4090")
            .editDraft(sameIdOtherHost, "spark")

        assertEquals("u4090", state.drafts[thread])
        assertEquals("spark", state.drafts[sameIdOtherHost])
    }

    @Test
    fun `reasoning effort is consumed by an accepted new turn but not a steer`() {
        val ready = ThreadActionState()
            .setPendingReasoningEffort(thread, "high")
            .editDraft(thread, "prompt")
        val (starting, start) = ready.beginInput(thread, ThreadWorkState.READY, null, true, "start")
        val (steering, steer) = ready.beginInput(thread, ThreadWorkState.BUSY, "turn", true, "steer")

        assertEquals(null, starting.inputAccepted(thread, start.clientId).pendingReasoningEfforts[thread])
        assertEquals("high", steering.inputAccepted(thread, steer.clientId).pendingReasoningEfforts[thread])
    }

    @Test
    fun `purge removes only deleted host qualified thread state`() {
        val retained = CodexThreadLocator("spark", "thread")
        val state = ThreadActionState(
            drafts = mapOf(thread to "delete", retained to "keep"),
            pendingInterrupts = mapOf(thread to "turn-delete", retained to "turn-keep"),
            pendingReasoningEfforts = mapOf(thread to "high", retained to "low"),
        ).purge(setOf(thread))

        assertEquals(mapOf(retained to "keep"), state.drafts)
        assertEquals(mapOf(retained to "turn-keep"), state.pendingInterrupts)
        assertEquals(mapOf(retained to "low"), state.pendingReasoningEfforts)
    }
}
