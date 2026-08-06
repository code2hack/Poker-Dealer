package com.code2hack.dealer

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.PokerPileMetadata
import com.code2hack.pokerdealer.domain.ThreadPile
import com.code2hack.pokerdealer.domain.ThreadWorkState
import com.code2hack.pokerdealer.protocol.PokerSnapshot
import com.code2hack.pokerdealer.protocol.PokerSnapshotPile
import com.code2hack.pokerdealer.protocol.PokerSnapshotWire
import com.code2hack.pokerdealer.protocol.toPokerSnapshotMetadata
import com.code2hack.pokerdealer.protocol.toPokerSnapshotProjection
import com.code2hack.pokerdealer.protocol.appserver.HostSessionStatus

/** Builds the complete Dealer-owned projection requested by a connected Poker. */
internal class DealerPokerSnapshotSource(
    private val state: () -> DealerUiState,
) {
    private val lock = Any()
    private var lastContent: PokerSnapshot? = null
    private var nextRevision = 0L

    fun current(): PokerSnapshot {
        val dealerState = state()
        val locators = dealerState.threadAttachments.attached.sortedWith(
            compareBy<CodexThreadLocator>(CodexThreadLocator::hostId)
                .thenBy(CodexThreadLocator::threadId),
        )
        val attachmentOrder = locators.withIndex().associate { (index, locator) ->
            locator to index.toLong()
        }
        val piles = locators.map { locator ->
            val conversationId = "${locator.hostId}/${locator.threadId}"
            val cards = dealerState.cards.asSequence()
                .filter { it.conversationId == conversationId }
                .sortedWith(
                    compareBy<Card>(Card::sequence)
                        .thenBy(Card::revision)
                        .thenBy(Card::id),
                )
                .toList()
            val thread = dealerState.threads[locator]
            val pile = ThreadPile(
                locator = locator,
                attachmentOrder = attachmentOrder.getValue(locator),
                workState = thread?.workState,
                stateChangedAtMs = (thread?.updatedAtSeconds ?: 0L)
                    .coerceAtLeast(0L)
                    .times(1_000L),
                available = dealerState.hostSessions[locator.hostId]?.status ==
                    HostSessionStatus.CONNECTED,
                outcome = cards.asSequence()
                    .mapNotNull(Card::turnOutcome)
                    .lastOrNull(),
            )
            PokerSnapshotPile(
                metadata = pile.toPokerSnapshotMetadata(),
                cards = cards,
            )
        }
        val projectionPiles = piles.map(PokerSnapshotPile::metadata).map { metadata ->
            ThreadPile(
                locator = metadata.locator,
                attachmentOrder = metadata.attachmentOrder,
                workState = metadata.workState?.let(ThreadWorkState::valueOf),
                stateChangedAtMs = metadata.stateChangedAtMs,
                available = metadata.available,
                outcome = metadata.outcome,
            )
        }
        val projection = PokerPileMetadata(
            orderedPiles = projectionPiles
                .filter { it.workState != null }
                .sortedWith(
                    compareBy<ThreadPile> { WORK_STATE_ORDER.getValue(it.workState!!) }
                        .thenBy(ThreadPile::stateChangedAtMs)
                        .thenBy(ThreadPile::attachmentOrder),
                ),
            unknownWorkState = projectionPiles
                .filter { it.workState == null }
                .sortedBy(ThreadPile::attachmentOrder),
            hudVisible = false,
            focused = null,
        ).toPokerSnapshotProjection()
        val content = PokerSnapshot(
            revision = 0L,
            projection = projection,
            piles = piles,
        )

        return synchronized(lock) {
            if (content != lastContent) {
                lastContent = content
                nextRevision++
            }
            val snapshot = content.copy(revision = nextRevision.coerceAtLeast(1L))
            PokerSnapshotWire.validate(snapshot)
            snapshot
        }
    }

    private companion object {
        val WORK_STATE_ORDER = mapOf(
            ThreadWorkState.BUSY to 0,
            ThreadWorkState.ATTENTION_REQUIRED to 1,
            ThreadWorkState.READY to 2,
        )
    }
}
