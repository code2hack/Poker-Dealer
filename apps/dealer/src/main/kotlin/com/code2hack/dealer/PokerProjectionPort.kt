package com.code2hack.dealer

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ThreadWorkState

/**
 * Transport-neutral semantic projection emitted by Dealer toward the future Poker transport.
 *
 * This deliberately does not encode CXR, sockets, pairing, input bindings, or Rokid SDK types.
 */
internal data class PokerProjectionSnapshot(
    val threads: List<PokerThreadProjection>,
)

internal data class PokerThreadProjection(
    val locator: CodexThreadLocator,
    val name: String?,
    val workState: ThreadWorkState?,
    val activeTurnId: String?,
    val controlGeneration: Long,
    val cards: List<Card>,
)

internal fun interface PokerProjectionPort {
    suspend fun publish(snapshot: PokerProjectionSnapshot)
}

internal object NoOpPokerProjectionPort : PokerProjectionPort {
    override suspend fun publish(snapshot: PokerProjectionSnapshot) = Unit
}

/**
 * Lowest project-owned transport boundary. A future qualified CXR implementation belongs below
 * this interface, never in Dealer core.
 */
internal fun interface PokerTransport {
    suspend fun send(message: PokerTransportMessage)
}

internal sealed interface PokerTransportMessage {
    data class Projection(val snapshot: PokerProjectionSnapshot) : PokerTransportMessage
}

internal class TransportPokerProjectionPort(
    private val transport: PokerTransport,
) : PokerProjectionPort {
    override suspend fun publish(snapshot: PokerProjectionSnapshot) {
        transport.send(PokerTransportMessage.Projection(snapshot))
    }
}

internal object NoOpPokerTransport : PokerTransport {
    override suspend fun send(message: PokerTransportMessage) = Unit
}

internal fun DealerCoreState.toPokerProjectionSnapshot(): PokerProjectionSnapshot =
    PokerProjectionSnapshot(
        threads = threadAttachments.attached
            .sortedWith(compareBy({ it.hostId }, { it.threadId }))
            .map { locator ->
                val thread = threads[locator]
                val conversationId = "${locator.hostId}/${locator.threadId}"
                PokerThreadProjection(
                    locator = locator,
                    name = thread?.name,
                    workState = thread?.workState,
                    activeTurnId = thread?.activeTurnId,
                    controlGeneration = threadAttachments.controlGeneration(locator),
                    cards = cards.filter { it.conversationId == conversationId }
                        .sortedBy(Card::sequence),
                )
            },
    )
