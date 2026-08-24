package com.code2hack.pokerdealer.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ThreadWorkProjectionTest {
    @Test
    fun `authoritative evidence derives only the three work states`() {
        assertEquals(ThreadWorkState.BUSY, evidence(active = true).workState())
        assertEquals(ThreadWorkState.ATTENTION_REQUIRED, evidence(active = true, requests = 1).workState())
        assertEquals(ThreadWorkState.READY, evidence(active = false).workState())
    }

    @Test
    fun `unknown or inconsistent authoritative evidence stays unknown`() {
        assertNull(evidence(active = null).workState())
        assertNull(evidence(active = true, requests = null).workState())
        assertNull(evidence(active = true, requests = -1).workState())
        assertNull(evidence(active = false, requests = 1).workState())
    }

    private fun evidence(active: Boolean?, requests: Int? = 0) =
        ThreadWorkEvidence(activeTurn = active, unresolvedRequestCount = requests)
}
