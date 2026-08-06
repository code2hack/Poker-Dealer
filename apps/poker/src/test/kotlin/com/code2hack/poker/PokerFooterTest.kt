package com.code2hack.poker

import org.junit.Assert.assertEquals
import org.junit.Test

class PokerFooterTest {
    @Test
    fun `footer uses exact disconnected and unread forms`() {
        assertEquals(
            "🔌·2 cards unread·DGX Spark:Thread name",
            pokerFooterText(false, 2, "DGX Spark", "Thread name"),
        )
        assertEquals(
            "🔌·DGX Spark:Thread name",
            pokerFooterText(false, 0, "DGX Spark", "Thread name"),
        )
        assertEquals(
            "1 card unread·DGX Spark:Thread name",
            pokerFooterText(true, 1, "DGX Spark", "Thread name"),
        )
        assertEquals(
            "DGX Spark:Thread name",
            pokerFooterText(true, 0, "DGX Spark", "Thread name"),
        )
    }

    @Test
    fun `footer callers can provide collapsed fallback labels`() {
        assertEquals(
            "DGX Spark:thread-id",
            pokerFooterText(true, 0, "DGX   Spark", "  thread-id  "),
        )
    }
}
