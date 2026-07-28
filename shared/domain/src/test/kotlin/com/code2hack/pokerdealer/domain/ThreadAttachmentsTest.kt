package com.code2hack.pokerdealer.domain

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThreadAttachmentsTest {
    private val spark = CodexThreadLocator("spark", "same")
    private val u4090 = CodexThreadLocator("u4090", "same")

    @Test
    fun `attachment starts observing and claims remain host qualified and independent`() {
        val observing = ThreadAttachmentState().attach(spark).attach(u4090)

        assertFalse(observing.hasDealerClaim(spark))
        val claimed = observing.claim(spark).claim(u4090)
        assertTrue(claimed.hasDealerClaim(spark))
        assertTrue(claimed.hasDealerClaim(u4090))
    }

    @Test
    fun `external turn revokes only its exact thread claim`() {
        val state = ThreadAttachmentState()
            .attach(spark)
            .attach(u4090)
            .claim(spark)
            .claim(u4090)
            .externalTurnStarted(spark, dealerOriginated = false)

        assertFalse(state.hasDealerClaim(spark))
        assertTrue(state.hasDealerClaim(u4090))
    }

    @Test
    fun `busy detach is harmless but a known blocking request prevents detach`() {
        val attached = ThreadAttachmentState().attach(spark).claim(spark)

        assertFalse(attached.detach(spark).attached.contains(spark))
        assertThrows(IllegalArgumentException::class.java) {
            attached.detach(spark, hasKnownBlockingRequest = true)
        }
    }
}
