package com.code2hack.dealer.asr

import com.code2hack.pokerdealer.domain.ComposerDraft
import com.code2hack.pokerdealer.domain.ComposerElement
import org.junit.Assert.assertEquals
import org.junit.Test

class DealerAsrTextCommitTest {
    @Test
    fun `composer commit preserves cursor target and advances draft revision`() {
        val draft = ComposerDraft(
            revision = 4,
            elements = listOf(ComposerElement.Text("ab")),
        )

        val next = insertDealerAsrText(draft, cursorPosition = 1, text = "X.")

        assertEquals(5L, next.revision)
        assertEquals("aX.b", next.displayText)
        assertEquals(3, dealerAsrCursorAfter(cursorPosition = 1, text = "X."))
    }

    @Test
    fun `request text commit inserts at the selected answer cursor`() {
        assertEquals(
            "aXb",
            insertDealerAsrText(currentText = "ab", cursorPosition = 1, text = "X"),
        )
        assertEquals(
            "你好世界",
            insertDealerAsrText(currentText = "你好", cursorPosition = 2, text = "世界"),
        )
    }
}
