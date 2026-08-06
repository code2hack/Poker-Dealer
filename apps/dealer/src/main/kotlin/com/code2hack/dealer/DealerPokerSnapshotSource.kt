package com.code2hack.dealer

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.RequestResolutionState
import com.code2hack.pokerdealer.domain.ThreadPile
import com.code2hack.pokerdealer.domain.ThreadWorkState
import com.code2hack.pokerdealer.protocol.PokerSnapshot
import com.code2hack.pokerdealer.protocol.PokerSnapshotPile
import com.code2hack.pokerdealer.protocol.PokerSnapshotProjection
import com.code2hack.pokerdealer.protocol.PokerSnapshotRequestCard
import com.code2hack.pokerdealer.protocol.PokerSnapshotWire
import com.code2hack.pokerdealer.protocol.pokerUnreadRequestKey
import com.code2hack.pokerdealer.protocol.toPokerSnapshotMetadata
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
                metadata = pile.toPokerSnapshotMetadata().copy(
                    hostLabel = InitialCodexHosts.all.firstOrNull { it.id == locator.hostId }
                        ?.displayName
                        ?: locator.hostId,
                    threadName = thread?.name,
                    threadPreview = thread?.preview,
                ),
                cards = cards,
                requestCards = dealerState.requestCards(locator),
            )
        }
        val projectionPiles = piles.map(PokerSnapshotPile::metadata)
        val projection = PokerSnapshotProjection(
            orderedPiles = projectionPiles
                .filter { it.workState != null }
                .sortedWith(
                    compareBy { WORK_STATE_ORDER.getValue(ThreadWorkState.valueOf(it.workState!!)) }
                        .thenBy { it.stateChangedAtMs }
                        .thenBy { it.attachmentOrder },
                ),
            unknownWorkState = projectionPiles.sortedBy { it.attachmentOrder }
                .filter { it.workState == null },
            hudVisible = false,
            focused = null,
        )
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

private fun DealerUiState.requestCards(
    locator: CodexThreadLocator,
): List<PokerSnapshotRequestCard> = buildList {
    commandApprovals.requests.values
        .filter { it.thread == locator }
        .sortedByDescending { it.locator.appServerGeneration }
        .forEach { request ->
            add(
                PokerSnapshotRequestCard(
                    key = pokerUnreadRequestKey(
                        "command",
                        request.locator.requestId,
                        request.fingerprint,
                    ),
                    cardId = request.itemId,
                    finalized = request.resolution.isFinalized(),
                ),
            )
        }
    fileApprovals.requests.values
        .filter { it.thread == locator }
        .sortedByDescending { it.locator.appServerGeneration }
        .forEach { request ->
            add(
                PokerSnapshotRequestCard(
                    key = pokerUnreadRequestKey(
                        "file",
                        request.locator.requestId,
                        request.fingerprint,
                    ),
                    cardId = request.itemId,
                    finalized = request.resolution.isFinalized(),
                ),
            )
        }
    userInputRequests.requests.values
        .filter { it.thread == locator }
        .sortedByDescending { it.locator.appServerGeneration }
        .forEach { request ->
            add(
                PokerSnapshotRequestCard(
                    key = pokerUnreadRequestKey(
                        "user-input",
                        request.locator.requestId,
                        request.fingerprint,
                    ),
                    cardId = request.itemId,
                    finalized = request.resolution.isFinalized(),
                ),
            )
        }
}.distinctBy(PokerSnapshotRequestCard::key)

private fun RequestResolutionState.isFinalized(): Boolean = this != RequestResolutionState.PENDING &&
    this != RequestResolutionState.RESPONDING
