package com.code2hack.dealer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.code2hack.dealer.asr.DealerAsrSourceSettings
import com.code2hack.pokerdealer.protocol.PokerAsrSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DealerAsrSourceSettingsTest {
    @Test
    fun `source defaults to glasses and survives store recreation`() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = DealerAsrSourceSettings(context)

        store.save(PokerAsrSource.GLASSES)
        assertEquals(PokerAsrSource.GLASSES, store.read())

        store.save(PokerAsrSource.DEALER_PHONE)
        assertEquals(PokerAsrSource.DEALER_PHONE, DealerAsrSourceSettings(context).read())

        store.save(PokerAsrSource.GLASSES)
    }
}
