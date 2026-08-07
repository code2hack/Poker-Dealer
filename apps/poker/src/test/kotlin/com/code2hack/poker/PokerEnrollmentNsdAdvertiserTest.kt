package com.code2hack.poker

import android.net.nsd.NsdManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PokerEnrollmentNsdAdvertiserTest {
    @Test
    fun `registration is unregistered exactly once when enrollment closes`() {
        var registered: NsdManager.RegistrationListener? = null
        var unregisterCount = 0
        var failures = 0
        val advertiser = PokerEnrollmentNsdAdvertiser(
            registerService = { registered = it },
            unregisterService = { unregisterCount++ },
        )

        advertiser.register { failures++ }
        assertNotNull(registered)
        advertiser.unregister()
        advertiser.unregister()

        assertEquals(1, unregisterCount)
        assertEquals(0, failures)
    }

    @Test
    fun `opening a new enrollment unregisters the previous advertisement`() {
        var registerCount = 0
        var unregisterCount = 0
        val advertiser = PokerEnrollmentNsdAdvertiser(
            registerService = { registerCount++ },
            unregisterService = { unregisterCount++ },
        )

        advertiser.register { error("unexpected failure") }
        advertiser.register { error("unexpected failure") }

        assertEquals(2, registerCount)
        assertEquals(1, unregisterCount)
    }

    @Test
    fun `registration failure is surfaced and leaves no advertisement to unregister`() {
        var failures = 0
        var unregisterCount = 0
        val advertiser = PokerEnrollmentNsdAdvertiser(
            registerService = { throw IllegalStateException("NSD unavailable") },
            unregisterService = { unregisterCount++ },
        )

        advertiser.register { failures++ }
        advertiser.unregister()

        assertEquals(1, failures)
        assertEquals(0, unregisterCount)
    }
}
