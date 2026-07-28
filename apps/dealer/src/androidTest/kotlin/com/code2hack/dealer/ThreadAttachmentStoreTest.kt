package com.code2hack.dealer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreadAttachmentStoreTest {
    @Test
    fun attachmentsSurviveStoreRecreationWithoutControlClaims() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val locators = setOf(
            CodexThreadLocator("spark", "same"),
            CodexThreadLocator("u4090", "same"),
        )

        val store = DealerThreadAttachmentStore(context)
        locators.forEach { store.detach(it) }
        locators.forEach { store.attach(it) }

        assertEquals(locators, DealerThreadAttachmentStore(context).read().intersect(locators))
    }
}
