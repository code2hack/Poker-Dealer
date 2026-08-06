package com.code2hack.poker

import com.code2hack.pokerdealer.domain.PokerNavigationMode
import com.code2hack.pokerdealer.domain.PokerNavigationReducer
import com.code2hack.pokerdealer.domain.PokerOperation
import com.code2hack.pokerdealer.domain.PokerPileLayout
import com.code2hack.pokerdealer.domain.PokerRequestPanelLayout
import com.code2hack.pokerdealer.domain.PokerRequestQuestionLayout
import com.code2hack.pokerdealer.domain.RequestResolutionState
import com.code2hack.pokerdealer.domain.ServerRequestLocator
import com.code2hack.pokerdealer.protocol.PokerApprovalDecision
import com.code2hack.pokerdealer.protocol.PokerApprovalRequestProjection
import com.code2hack.pokerdealer.protocol.PokerPrimaryActionOutcome
import com.code2hack.pokerdealer.protocol.PokerPrimaryActionResult
import com.code2hack.pokerdealer.protocol.PokerPrimaryActionTarget

internal data class PokerApprovalSubmission(
    val projection: PokerApprovalRequestProjection,
    val decision: PokerApprovalDecision,
)

/** Keeps approval choices local while Dealer owns scope, eligibility, and response delivery. */
internal class PokerApprovalController(
    private val navigation: PokerNavigationReducer,
) {
    private val projections = mutableMapOf<ServerRequestLocator, PokerApprovalRequestProjection>()
    private val pendingPrimary = mutableMapOf<ServerRequestLocator, PokerPrimaryActionTarget>()

    fun applyProjection(projection: PokerApprovalRequestProjection) {
        if (projections.keys.any {
                it.hostId == projection.locator.hostId &&
                    it.requestId == projection.locator.requestId &&
                    it.appServerGeneration > projection.locator.appServerGeneration
            }
        ) return
        val current = projections[projection.locator]
        if (current != null && projectionIsStale(current, projection)) return
        projections.keys
            .filter {
                it.hostId == projection.locator.hostId &&
                    it.requestId == projection.locator.requestId &&
                    it != projection.locator
            }
            .toList()
            .forEach { old ->
                projections.remove(old)?.let(::clearLayout)
                pendingPrimary.remove(old)
            }
        val pending = pendingPrimary[projection.locator]
        if (pending != null && (
                projection.resolution == RequestResolutionState.RESOLVED ||
                    pending.requestFingerprint != projection.fingerprint ||
                    pending.controlGeneration != projection.controlGeneration ||
                    pending.connectionEpoch != projection.connectionEpoch ||
                    pending.modeSession != projection.modeSession ||
                    pending.approvalDecision !in projection.choices
                )
        ) pendingPrimary.remove(projection.locator)
        projections[projection.locator] = projection
        updateLayout(projection)
        if (current?.resolution != RequestResolutionState.RESOLVED &&
            projection.resolution == RequestResolutionState.RESOLVED &&
            navigation.metadata().focused == projection.thread
        ) {
            val anchor = navigation.anchor(projection.thread)
            if (anchor?.mode == PokerNavigationMode.REQUEST_PANEL && anchor.inputId == projection.panelId) {
                navigation.apply(PokerOperation.UP)
            }
        }
    }

    fun focusedSubmission(): PokerApprovalSubmission? {
        val thread = navigation.metadata().focused ?: return null
        val anchor = navigation.anchor(thread)
            ?.takeIf { it.mode == PokerNavigationMode.REQUEST_PANEL }
            ?: return null
        val panel = navigation.layout(thread)?.cards
            ?.firstOrNull { it.id == anchor.cardId }
            ?.requestPanel
            ?.takeIf { it.id == anchor.inputId }
            ?: return null
        val projection = projections.values.firstOrNull {
            it.thread == thread && it.panelId == panel.id
        } ?: return null
        val decision = projection.choices.getOrNull(anchor.cursorPosition) ?: return null
        return projection.takeIf {
            it.resolution == RequestResolutionState.PENDING &&
                it.actionable && it.hasDealerClaim && pendingPrimary[it.locator] == null
        }?.let { PokerApprovalSubmission(it, decision) }
    }

    fun beginPrimary(target: PokerPrimaryActionTarget): Boolean {
        if (target.action != com.code2hack.pokerdealer.domain.PokerPrimaryAction.REQUEST) return false
        val locator = target.requestLocator ?: return false
        val decision = target.approvalDecision ?: return false
        val projection = projections[locator] ?: return false
        if (pendingPrimary[locator] != null ||
            projection.resolution != RequestResolutionState.PENDING ||
            !projection.actionable ||
            !projection.hasDealerClaim ||
            target.answerRevision != null ||
            target.requestFingerprint != projection.fingerprint ||
            target.controlGeneration != projection.controlGeneration ||
            target.connectionEpoch != projection.connectionEpoch ||
            target.modeSession != projection.modeSession ||
            decision !in projection.choices
        ) return false
        pendingPrimary[locator] = target
        updateLayout(projection)
        return true
    }

    fun applyPrimaryResult(result: PokerPrimaryActionResult) {
        val locator = result.target.requestLocator ?: return
        if (pendingPrimary[locator] != result.target) return
        if (result.outcome == PokerPrimaryActionOutcome.REJECTED) {
            pendingPrimary.remove(locator)
            projections[locator]?.let(::updateLayout)
        }
    }

    fun isPrimaryLocked(locator: ServerRequestLocator): Boolean = pendingPrimary[locator] != null

    fun projections(): List<PokerApprovalRequestProjection> = projections.values.toList()

    private fun updateLayout(projection: PokerApprovalRequestProjection) {
        val existing = navigation.layout(projection.thread) ?: return
        val cardId = projection.cardId.ifBlank { projection.itemId }
        val targetCardId = existing.cards.firstOrNull { it.id == cardId }?.id
            ?: existing.cards.singleOrNull()?.id
        val panel = projection.takeIf {
            it.resolution != RequestResolutionState.RESOLVED
        }?.let {
            val choices = it.choices
            PokerRequestPanelLayout(
                id = it.panelId,
                positionCount = choices.size.coerceAtLeast(1),
                questions = listOf(
                    PokerRequestQuestionLayout(
                        id = it.panelId,
                        controlCount = choices.size.coerceAtLeast(1),
                        optionLabels = choices.map(PokerApprovalDecision::wireName),
                    ),
                ),
                primaryActionLocked = pendingPrimary[it.locator] != null ||
                    it.resolution != RequestResolutionState.PENDING ||
                    !it.actionable || !it.hasDealerClaim,
                hasDealerClaim = it.hasDealerClaim,
            )
        }
        navigation.setLayout(
            projection.thread,
            PokerPileLayout(
                cards = existing.cards.map { card ->
                    if (card.id == targetCardId) card.copy(requestPanel = panel) else card
                },
                composer = existing.composer,
            ),
        )
    }

    private fun clearLayout(projection: PokerApprovalRequestProjection) {
        val existing = navigation.layout(projection.thread) ?: return
        val cardId = projection.cardId.ifBlank { projection.itemId }
        val targetCardId = existing.cards.firstOrNull { it.id == cardId }?.id
            ?: existing.cards.singleOrNull()?.id
        navigation.setLayout(
            projection.thread,
            PokerPileLayout(
                cards = existing.cards.map { card ->
                    if (card.id == targetCardId && card.requestPanel?.id == projection.panelId) {
                        card.copy(requestPanel = null)
                    } else {
                        card
                    }
                },
                composer = existing.composer,
            ),
        )
    }

    private fun projectionIsStale(
        current: PokerApprovalRequestProjection,
        incoming: PokerApprovalRequestProjection,
    ): Boolean = when {
        incoming.connectionEpoch < current.connectionEpoch -> true
        incoming.connectionEpoch > current.connectionEpoch -> false
        incoming.controlGeneration < current.controlGeneration -> true
        incoming.controlGeneration > current.controlGeneration -> false
        resolutionRank(incoming.resolution) < resolutionRank(current.resolution) -> true
        else -> false
    }

    private fun resolutionRank(resolution: RequestResolutionState): Int = when (resolution) {
        RequestResolutionState.PENDING -> 0
        RequestResolutionState.RESPONDING -> 1
        RequestResolutionState.UNKNOWN -> 2
        RequestResolutionState.RESOLVED -> 3
    }
}
