package com.code2hack.poker

import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ThreadPileReducer
import com.code2hack.pokerdealer.domain.ThreadWorkEvidence
import com.code2hack.pokerdealer.domain.ThreadWorkState
import com.code2hack.pokerdealer.testing.MockDeck
import org.junit.Assert.assertEquals
import org.junit.Test

class PokerPileLineRenderingTest {
    @Test
    fun `pile line renders ordered host-qualified piles and locator focus`() {
        val entries = MockDeck.pileMetadata.pileLineEntries()

        assertEquals(
            listOf(
                "spark/thr_mock_spark_busy",
                "u4090/thr_mock_poker_dealer",
                "fold6-termux/thr_mock_termux_ready",
            ),
            entries.map(PokerPileLineEntry::label),
        )
        assertEquals(
            listOf(ThreadWorkState.BUSY, ThreadWorkState.ATTENTION_REQUIRED, ThreadWorkState.READY),
            entries.map(PokerPileLineEntry::workState),
        )
        assertEquals(listOf(false, true, false), entries.map(PokerPileLineEntry::focused))
    }

    @Test
    fun `pile line omits unknown work state instead of creating another surface`() {
        val reducer = ThreadPileReducer()
        val unknown = CodexThreadLocator("spark", "unknown")
        val ready = CodexThreadLocator("spark", "ready")
        reducer.attach(unknown, ThreadWorkEvidence(activeTurn = null, unresolvedRequestCount = 0), atMs = 1)
        reducer.attach(ready, ThreadWorkEvidence(activeTurn = false, unresolvedRequestCount = 0), atMs = 2)

        assertEquals(listOf("spark/ready"), reducer.metadata().pileLineEntries().map(PokerPileLineEntry::label))
    }
}
