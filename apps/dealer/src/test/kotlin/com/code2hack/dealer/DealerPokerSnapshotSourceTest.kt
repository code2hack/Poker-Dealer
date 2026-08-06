package com.code2hack.dealer

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardRole
import com.code2hack.pokerdealer.domain.CardSource
import com.code2hack.pokerdealer.domain.CardState
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.CommandApprovalDecision
import com.code2hack.pokerdealer.domain.CommandApprovalRequest
import com.code2hack.pokerdealer.domain.CommandApprovalScope
import com.code2hack.pokerdealer.domain.CommandApprovalState
import com.code2hack.pokerdealer.domain.DiscoveredThread
import com.code2hack.pokerdealer.domain.ServerRequestLocator
import com.code2hack.pokerdealer.domain.ThreadAttachmentState
import com.code2hack.pokerdealer.domain.ThreadWorkState
import com.code2hack.pokerdealer.protocol.appserver.HostSessionState
import com.code2hack.pokerdealer.protocol.appserver.HostSessionStatus
import com.code2hack.pokerdealer.protocol.pokerUnreadRequestKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DealerPokerSnapshotSourceTest {
    @Test
    fun `source includes every attached qualified pile and retained card projection`() {
        val busy = CodexThreadLocator("spark", "busy")
        val ready = CodexThreadLocator("u4090", "ready")
        var state = DealerUiState(
            threadAttachments = ThreadAttachmentState().attach(busy).attach(ready),
            threads = mapOf(
                busy to DiscoveredThread(
                    locator = busy,
                    name = "  Busy\nthread  ",
                    workState = ThreadWorkState.BUSY,
                    updatedAtSeconds = 2,
                    attached = true,
                ),
                ready to DiscoveredThread(
                    locator = ready,
                    workState = ThreadWorkState.READY,
                    updatedAtSeconds = 3,
                    attached = true,
                ),
            ),
            cards = listOf(card(busy, "busy-card"), card(ready, "ready-card")),
            commandApprovals = CommandApprovalState(
                requests = mapOf(
                    ServerRequestLocator("spark", 1, "request-1") to CommandApprovalRequest(
                        locator = ServerRequestLocator("spark", 1, "request-1"),
                        thread = busy,
                        turnId = "turn",
                        itemId = "busy-card",
                        approvalId = null,
                        scope = CommandApprovalScope("ls", "/tmp", null, null),
                        proposedExecpolicyAmendment = null,
                        offeredDecisions = setOf(CommandApprovalDecision.ACCEPT),
                        fingerprint = "fingerprint",
                        createdAtMs = 1,
                    ),
                ),
            ),
            hostSessions = mapOf(
                "spark" to HostSessionState(status = HostSessionStatus.CONNECTED),
                "u4090" to HostSessionState(status = HostSessionStatus.DISABLED),
            ),
        )
        val source = DealerPokerSnapshotSource { state }

        val snapshot = source.current()

        assertEquals(1L, snapshot.revision)
        assertEquals(listOf(busy, ready), snapshot.projection.orderedPiles.map { it.locator })
        assertEquals(setOf(busy, ready), snapshot.piles.map { it.metadata.locator }.toSet())
        assertEquals(listOf("busy-card"), snapshot.piles.single { it.metadata.locator == busy }
            .cards.map(Card::id))
        assertEquals("spark/busy", snapshot.piles.single { it.metadata.locator == busy }
            .cards.single().conversationId)
        assertTrue(snapshot.piles.single { it.metadata.locator == busy }.metadata.available)
        assertTrue(!snapshot.piles.single { it.metadata.locator == ready }.metadata.available)
        assertEquals("DGX Spark", snapshot.piles.single { it.metadata.locator == busy }.metadata.hostLabel)
        assertEquals("  Busy\nthread  ", snapshot.piles.single { it.metadata.locator == busy }.metadata.threadName)
        assertEquals(
            listOf(pokerUnreadRequestKey("command", "request-1", "fingerprint")),
            snapshot.piles.single { it.metadata.locator == busy }.requestCards.map { it.key },
        )

        state = state.copy(cards = state.cards + card(busy, "new-card", sequence = 2))
        assertNotEquals(snapshot.revision, source.current().revision)
    }

    private fun card(
        locator: CodexThreadLocator,
        id: String,
        sequence: Long = 1,
    ) = Card(
        id = id,
        conversationId = "${locator.hostId}/${locator.threadId}",
        sequence = sequence,
        revision = 1,
        role = CardRole.AGENT,
        state = CardState.COMMITTED,
        fullText = id,
        createdAtMs = sequence,
        updatedAtMs = sequence,
        source = CardSource.CODEX_AGENT_MESSAGE,
    )
}
