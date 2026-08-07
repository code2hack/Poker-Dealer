package com.code2hack.dealer.asr

import com.code2hack.pokerdealer.protocol.PokerAsrSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DealerAsrSourceSessionTest {
    @Test
    fun `phone source falls back to glasses before capture starts and stays future setting`() {
        val session = DealerAsrSourceSession(PokerAsrSource.DEALER_PHONE)

        val decision = session.start(
            requestSource = PokerAsrSource.DEALER_PHONE,
            phoneAvailable = false,
            glassesAvailable = true,
        )

        assertEquals(PokerAsrSource.GLASSES, decision.source)
        assertTrue(decision.fellBackToGlasses)
        assertEquals(PokerAsrSource.DEALER_PHONE, session.futureSource)
        assertEquals(PokerAsrSource.GLASSES, session.activeSource)
    }

    @Test
    fun `unavailable phone and glasses source remains unavailable with sanitized reason`() {
        val session = DealerAsrSourceSession(PokerAsrSource.DEALER_PHONE)

        val decision = session.start(
            requestSource = PokerAsrSource.DEALER_PHONE,
            phoneAvailable = false,
            glassesAvailable = false,
        )

        assertEquals(DealerAsrStartMode.UNAVAILABLE, decision.mode)
        assertNull(decision.source)
        assertEquals("dealer-phone-and-glasses-unavailable", decision.reason)
        assertNull(session.activeSource)
    }

    @Test
    fun `active source stays fixed while setting changes for the next session`() {
        val session = DealerAsrSourceSession()
        assertTrue(dealerAsrSourceSelectionEnabled(activeSession = true))
        session.setFutureSource(PokerAsrSource.DEALER_PHONE)
        assertEquals(
            PokerAsrSource.DEALER_PHONE,
            session.start(PokerAsrSource.GLASSES, phoneAvailable = true, glassesAvailable = true).source,
        )

        session.setFutureSource(PokerAsrSource.GLASSES)
        assertEquals(PokerAsrSource.GLASSES, session.futureSource)
        assertEquals(PokerAsrSource.DEALER_PHONE, session.activeSource)

        session.end()
        assertEquals(
            PokerAsrSource.GLASSES,
            session.start(PokerAsrSource.GLASSES, phoneAvailable = false, glassesAvailable = true).source,
        )
    }

    @Test
    fun `dealer asks only for missing phone permission`() {
        assertTrue(shouldRequestDealerAsrPhonePermission(PokerAsrSource.DEALER_PHONE, permissionGranted = false))
        assertFalse(shouldRequestDealerAsrPhonePermission(PokerAsrSource.DEALER_PHONE, permissionGranted = true))
        assertFalse(shouldRequestDealerAsrPhonePermission(PokerAsrSource.GLASSES, permissionGranted = false))
    }
}
