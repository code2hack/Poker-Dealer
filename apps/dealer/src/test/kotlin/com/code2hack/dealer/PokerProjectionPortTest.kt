package com.code2hack.dealer

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardRole
import com.code2hack.pokerdealer.domain.CardSource
import com.code2hack.pokerdealer.domain.CardState
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.DiscoveredThread
import com.code2hack.pokerdealer.domain.ThreadAttachmentState
import com.code2hack.pokerdealer.domain.ThreadWorkState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PokerProjectionPortTest {
    @Test
    fun projectionContainsOnlyAttachedSemanticStateAndControlGeneration() {
        val attached = CodexThreadLocator("host", "attached")
        val detached = CodexThreadLocator("host", "detached")
        val attachments = ThreadAttachmentState().attach(attached).claim(attached)
        val state = DealerCoreState(
            threads = mapOf(
                attached to DiscoveredThread(
                    locator = attached,
                    name = "Attached",
                    workState = ThreadWorkState.BUSY,
                    activeTurnId = "turn-1",
                    attached = true,
                ),
                detached to DiscoveredThread(
                    locator = detached,
                    name = "Detached",
                    workState = ThreadWorkState.READY,
                ),
            ),
            threadAttachments = attachments,
            cards = listOf(card(attached, 2), card(attached, 1), card(detached, 1)),
        )

        val projection = state.toPokerProjectionSnapshot()

        assertEquals(1, projection.threads.size)
        val thread = projection.threads.single()
        assertEquals(attached, thread.locator)
        assertEquals("Attached", thread.name)
        assertEquals(ThreadWorkState.BUSY, thread.workState)
        assertEquals("turn-1", thread.activeTurnId)
        assertEquals(2L, thread.controlGeneration)
        assertEquals(listOf(1L, 2L), thread.cards.map(Card::sequence))
    }

    @Test
    fun transportPortForwardsProjectOwnedProjectionWithoutTransportAssumptions() = runBlocking {
        val messages = mutableListOf<PokerTransportMessage>()
        val transport = PokerTransport { message -> messages += message }
        val port = TransportPokerProjectionPort(transport)
        val snapshot = PokerProjectionSnapshot(emptyList())

        port.publish(snapshot)

        assertEquals(listOf(PokerTransportMessage.Projection(snapshot)), messages)
        NoOpPokerTransport.send(PokerTransportMessage.Projection(snapshot))
    }

    private fun card(locator: CodexThreadLocator, sequence: Long) = Card(
        id = "card-$sequence",
        conversationId = "${locator.hostId}/${locator.threadId}",
        sequence = sequence,
        revision = 1,
        role = CardRole.AGENT,
        state = CardState.COMMITTED,
        fullText = "text-$sequence",
        createdAtMs = sequence,
        updatedAtMs = sequence,
        source = CardSource.CODEX_AGENT_MESSAGE,
    )
}
