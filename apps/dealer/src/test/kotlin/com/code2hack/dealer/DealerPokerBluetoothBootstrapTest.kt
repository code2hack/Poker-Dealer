package com.code2hack.dealer

import org.junit.Assert.assertEquals
import org.junit.Test

class DealerPokerBluetoothBootstrapTest {
    @Test
    fun `remembered bonded Poker wins without probing friendly names`() {
        assertEquals(
            DealerPokerBootstrapPeerSelection.Selected("rg-id"),
            selectDealerPokerBootstrapPeer(
                rememberedPeerId = "rg-id",
                bondedPeerIds = setOf("other", "rg-id"),
                responsivePeerIds = emptySet(),
            ),
        )
    }

    @Test
    fun `missing remembered bond requires revocation instead of replacement`() {
        assertEquals(
            DealerPokerBootstrapPeerSelection.RememberedMissing,
            selectDealerPokerBootstrapPeer(
                rememberedPeerId = "rg-id",
                bondedPeerIds = setOf("other"),
                responsivePeerIds = setOf("other"),
            ),
        )
    }

    @Test
    fun `exactly one responsive bonded Poker is adopted automatically`() {
        assertEquals(
            DealerPokerBootstrapPeerSelection.Selected("rg-id"),
            selectDealerPokerBootstrapPeer(
                rememberedPeerId = null,
                bondedPeerIds = setOf("headphones", "rg-id"),
                responsivePeerIds = setOf("rg-id"),
            ),
        )
    }

    @Test
    fun `multiple responsive bonded Poker peers fail ambiguous`() {
        assertEquals(
            DealerPokerBootstrapPeerSelection.Ambiguous,
            selectDealerPokerBootstrapPeer(
                rememberedPeerId = null,
                bondedPeerIds = setOf("rg-a", "rg-b"),
                responsivePeerIds = setOf("rg-a", "rg-b"),
            ),
        )
    }

    @Test
    fun `unbonded responsive device is never a trust candidate`() {
        assertEquals(
            DealerPokerBootstrapPeerSelection.None,
            selectDealerPokerBootstrapPeer(
                rememberedPeerId = null,
                bondedPeerIds = setOf("headphones"),
                responsivePeerIds = setOf("rg-id"),
            ),
        )
    }
}
