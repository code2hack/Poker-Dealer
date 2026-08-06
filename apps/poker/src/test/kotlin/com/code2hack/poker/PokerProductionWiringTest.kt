package com.code2hack.poker

import com.code2hack.pokerdealer.protocol.PokerPairingFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PokerProductionWiringTest {
    @Test
    fun `activity starts the private listener and resumes it when already enabled`() {
        assertEquals(
            PokerListenerService.ACTION_RETRY,
            PokerListenerService.activityStartAction(enabled = true),
        )
        assertEquals(
            PokerListenerService.ACTION_ENABLE,
            PokerListenerService.activityStartAction(enabled = false),
        )
    }

    @Test
    fun `pair and replacement are explicit listener service actions`() {
        val pair = PokerListenerService.launchSpec(
            action = PokerListenerService.ACTION_OPEN_ENROLLMENT,
        )
        val replacement = PokerListenerService.launchSpec(
            action = PokerListenerService.ACTION_OPEN_ENROLLMENT,
            replacement = true,
        )

        assertEquals(PokerListenerService.ACTION_OPEN_ENROLLMENT, pair.action)
        assertFalse(pair.replacement)
        assertTrue(replacement.replacement)
    }

    @Test
    fun `pairing display state redacts one-time code across lifecycle diagnostics`() {
        val enrollment = PokerPairingUiState.EnrollmentOpen(
            host = "192.0.2.1",
            port = 8_341,
            displayCode = "123456",
            replacement = false,
            expiresAtMs = 123L,
            failure = PokerPairingFailure.INVALID_CODE,
        )

        assertFalse(enrollment.toString().contains("123456"))
        assertTrue(enrollment.toString().contains("redacted"))
    }
}
