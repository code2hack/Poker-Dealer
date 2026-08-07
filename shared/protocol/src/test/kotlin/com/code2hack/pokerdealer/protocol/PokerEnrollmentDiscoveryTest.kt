package com.code2hack.pokerdealer.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PokerEnrollmentDiscoveryTest {
    @Test
    fun `zero active services is not found`() {
        assertEquals(
            PokerEnrollmentDiscoveryResult.NotFound,
            PokerEnrollmentCandidates().result(),
        )
    }

    @Test
    fun `one resolved active service returns only the fixed listener port`() {
        val candidates = PokerEnrollmentCandidates()
        candidates.found("poker-one")
        candidates.resolved("poker-one", "192.0.2.10", POKER_LISTENER_PORT)

        assertEquals(
            PokerEnrollmentDiscoveryResult.Found(
                PokerHotspotEndpoint("192.0.2.10", POKER_LISTENER_PORT),
            ),
            candidates.result(),
        )
    }

    @Test
    fun `multiple active services fail closed before endpoint selection`() {
        val candidates = PokerEnrollmentCandidates()
        candidates.found("poker-one")
        candidates.found("poker-two")
        candidates.resolved("poker-one", "192.0.2.10", POKER_LISTENER_PORT)
        candidates.resolved("poker-two", "192.0.2.11", POKER_LISTENER_PORT)

        assertEquals(PokerEnrollmentDiscoveryResult.Ambiguous, candidates.result())
    }

    @Test
    fun `lost service is removed before selection`() {
        val candidates = PokerEnrollmentCandidates()
        candidates.found("stale")
        candidates.resolved("stale", "192.0.2.10", POKER_LISTENER_PORT)
        candidates.lost("stale")

        assertEquals(PokerEnrollmentDiscoveryResult.NotFound, candidates.result())
    }
}
