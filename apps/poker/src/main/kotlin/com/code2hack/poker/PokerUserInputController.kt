package com.code2hack.poker

import com.code2hack.pokerdealer.domain.PokerNavigationMode
import com.code2hack.pokerdealer.domain.PokerNavigationReducer
import com.code2hack.pokerdealer.domain.PokerOperation
import com.code2hack.pokerdealer.domain.PokerPileLayout
import com.code2hack.pokerdealer.domain.RequestResolutionState
import com.code2hack.pokerdealer.domain.ServerRequestLocator
import com.code2hack.pokerdealer.domain.UserInputAnswerEdit
import com.code2hack.pokerdealer.domain.PokerPrimaryAction
import com.code2hack.pokerdealer.domain.toPokerRequestPanelLayout
import com.code2hack.pokerdealer.protocol.UserInputAnswerMutationKind
import com.code2hack.pokerdealer.protocol.UserInputAnswerMutationRequest
import com.code2hack.pokerdealer.protocol.UserInputAnswerMutationResult
import com.code2hack.pokerdealer.protocol.UserInputAnswerMutationTarget
import com.code2hack.pokerdealer.protocol.UserInputRequestProjection
import com.code2hack.pokerdealer.protocol.PokerPrimaryActionOutcome
import com.code2hack.pokerdealer.protocol.PokerPrimaryActionResult
import com.code2hack.pokerdealer.protocol.PokerPrimaryActionTarget
import java.util.UUID

/** Keeps request-panel focus local while Dealer remains authoritative for answer content. */
internal class PokerUserInputController(
    private val navigation: PokerNavigationReducer,
    private val sendMutation: suspend (UserInputAnswerMutationRequest) -> Boolean,
) {
    private val projections = mutableMapOf<ServerRequestLocator, UserInputRequestProjection>()
    private val pending = mutableMapOf<ServerRequestLocator, UserInputAnswerMutationTarget>()
    private val pendingPrimary = mutableMapOf<ServerRequestLocator, PokerPrimaryActionTarget>()

    fun applyProjection(projection: UserInputRequestProjection) {
        val supersedingGeneration = projections.keys.any {
            it.hostId == projection.request.locator.hostId &&
                it.requestId == projection.request.locator.requestId &&
                it.appServerGeneration > projection.request.locator.appServerGeneration
        }
        if (supersedingGeneration) return
        val existing = projections[projection.request.locator]
        val resolvedPrimary = projection.request.resolution == RequestResolutionState.RESOLVED &&
            pendingPrimary[projection.request.locator] != null
        if (existing != null && projectionIsStale(existing, projection)) return
        projections.keys
            .filter {
                it.hostId == projection.request.locator.hostId &&
                    it.requestId == projection.request.locator.requestId &&
                    it != projection.request.locator
            }
            .toList()
            .forEach { oldLocator ->
                projections.remove(oldLocator)?.let(::clearLayout)
                pending.remove(oldLocator)
            }
        if (existing != null && existing != projection) {
            val primary = pendingPrimary[projection.request.locator]
            if (primary == null ||
                projection.request.resolution == RequestResolutionState.RESOLVED ||
                primary.requestFingerprint != projection.request.fingerprint ||
                primary.answerRevision != projection.buffer.revision ||
                primary.controlGeneration != projection.controlGeneration ||
                primary.connectionEpoch != projection.connectionEpoch ||
                primary.modeSession != projection.modeSession
            ) {
                pendingPrimary.remove(projection.request.locator)
                pending.remove(projection.request.locator)
            }
        }
        projections[projection.request.locator] = projection
        if (projection.request.resolution == RequestResolutionState.RESOLVED) {
            pendingPrimary.remove(projection.request.locator)
        }
        updateLayout(projection)
        if (resolvedPrimary && navigation.metadata().focused == projection.request.thread) {
            val anchor = navigation.anchor(projection.request.thread)
            if (anchor?.mode == PokerNavigationMode.REQUEST_PANEL &&
                anchor.inputId == projection.request.panelId
            ) {
                navigation.apply(PokerOperation.UP)
            }
        }
    }

    fun focusedSubmission(): UserInputRequestProjection? {
        val thread = navigation.metadata().focused ?: return null
        val anchor = navigation.anchor(thread)
            ?.takeIf { it.mode == PokerNavigationMode.REQUEST_PANEL }
            ?: return null
        val layout = navigation.layout(thread) ?: return null
        val panel = layout.cards.firstOrNull { it.id == anchor.cardId }
            ?.requestPanel
            ?.takeIf { it.id == anchor.inputId }
            ?: return null
        return projections.values.firstOrNull {
                it.request.thread == thread &&
                it.request.panelId == panel.id &&
                it.hasDealerClaim &&
                it.request.resolution == RequestResolutionState.PENDING &&
                it.buffer.isComplete(it.request)
        }
    }

    suspend fun selectFocused(): Boolean {
        val thread = navigation.metadata().focused ?: return false
        val anchor = navigation.anchor(thread)?.takeIf { it.mode == PokerNavigationMode.REQUEST_PANEL }
            ?: return false
        val layout = navigation.layout(thread) ?: return false
        val card = layout.cards.firstOrNull { it.id == anchor.cardId } ?: return false
        val panel = card.requestPanel?.takeIf { it.id == anchor.inputId } ?: return false
        val projection = projections.values.firstOrNull {
            it.request.thread == thread &&
                it.request.panelId == panel.id &&
                it.request.resolution == RequestResolutionState.PENDING
        } ?: return false
        val control = panel.controlAt(anchor.cursorPosition) ?: return false
        val kind = when {
            control.isOther -> UserInputAnswerMutationKind.SELECT_OTHER to null
            control.optionLabel != null ->
                UserInputAnswerMutationKind.SELECT_OPTION to control.optionLabel
            else -> return false
        }
        return edit(
            locator = projection.request.locator,
            questionId = control.questionId,
            kind = kind.first,
            value = kind.second,
            controlGeneration = projection.controlGeneration,
            connectionEpoch = projection.connectionEpoch,
            modeSession = projection.modeSession,
        )
    }

    suspend fun edit(
        locator: ServerRequestLocator,
        questionId: String,
        kind: UserInputAnswerMutationKind,
        value: String? = null,
        controlGeneration: Long,
        connectionEpoch: Long,
        modeSession: String,
    ): Boolean {
        val projection = projections[locator] ?: return false
        if (projection.request.resolution != RequestResolutionState.PENDING) {
            return false
        }
        if (pending[locator] != null || pendingPrimary[locator] != null) return false
        val target = UserInputAnswerMutationTarget(
            locator = locator,
            questionId = questionId,
            answerRevision = projection.buffer.revision,
            controlGeneration = controlGeneration,
            connectionEpoch = connectionEpoch,
            modeSession = modeSession,
            operationId = UUID.randomUUID().toString(),
        )
        val edit = when (kind) {
            UserInputAnswerMutationKind.SELECT_OPTION ->
                value?.let(UserInputAnswerEdit::SelectOption) ?: return false
            UserInputAnswerMutationKind.SELECT_OTHER -> UserInputAnswerEdit.SelectOther
            UserInputAnswerMutationKind.SET_TEXT ->
                value?.let(UserInputAnswerEdit::SetText) ?: return false
        }
        runCatching { projection.buffer.edit(projection.request, questionId, edit) }
            .getOrElse { return false }
        pending[locator] = target
        if (!sendMutation(UserInputAnswerMutationRequest(target, kind, value))) {
            pending.remove(locator)
            return false
        }
        return true
    }

    fun beginPrimary(target: PokerPrimaryActionTarget): Boolean {
        if (target.action != PokerPrimaryAction.REQUEST) return false
        val locator = target.requestLocator ?: return false
        if (pendingPrimary[locator] != null || pending[locator] != null) return false
        val projection = projections[locator] ?: return false
        if (projection.request.resolution != RequestResolutionState.PENDING ||
            projection.buffer.revision != target.answerRevision ||
            projection.request.fingerprint != target.requestFingerprint ||
            projection.controlGeneration != target.controlGeneration ||
            projection.connectionEpoch != target.connectionEpoch ||
            projection.modeSession != target.modeSession ||
            !projection.hasDealerClaim ||
            !projection.buffer.isComplete(projection.request)
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

    fun applyResult(result: UserInputAnswerMutationResult) {
        val projection = projections[result.target.locator] ?: return
        if (pending[result.target.locator] != result.target) return
        pending.remove(result.target.locator)
        projections[result.target.locator] = projection.copy(buffer = result.buffer)
        updateLayout(projections.getValue(result.target.locator))
    }

    fun projection(locator: ServerRequestLocator): UserInputRequestProjection? = projections[locator]

    private fun updateLayout(projection: UserInputRequestProjection) {
        val locator = projection.request.thread
        val existing = navigation.layout(locator) ?: return
        val cardId = projection.cardId.ifBlank { projection.request.itemId }
        val targetCardId = existing.cards.firstOrNull { it.id == cardId }?.id
            ?: existing.cards.singleOrNull()?.id
        val panel = projection.request
            .takeIf {
                it.resolution == RequestResolutionState.PENDING ||
                    it.resolution == RequestResolutionState.RESPONDING
            }
            ?.toPokerRequestPanelLayout()
            ?.copy(
                primaryActionLocked = pendingPrimary[projection.request.locator] != null,
                hasDealerClaim = projection.hasDealerClaim,
            )
        navigation.setLayout(
            locator,
            PokerPileLayout(
                cards = existing.cards.map { card ->
                    if (card.id == targetCardId) card.copy(requestPanel = panel) else card
                },
                composer = existing.composer,
            ),
        )
    }

    private fun clearLayout(projection: UserInputRequestProjection) {
        val existing = navigation.layout(projection.request.thread) ?: return
        val cardId = projection.cardId.ifBlank { projection.request.itemId }
        val targetCardId = existing.cards.firstOrNull { it.id == cardId }?.id
            ?: existing.cards.singleOrNull()?.id
        navigation.setLayout(
            projection.request.thread,
            PokerPileLayout(
                cards = existing.cards.map { card ->
                    if (card.id == targetCardId) card.copy(requestPanel = null) else card
                },
                composer = existing.composer,
            ),
        )
    }

    private fun projectionIsStale(
        current: UserInputRequestProjection,
        incoming: UserInputRequestProjection,
    ): Boolean = when {
        incoming.connectionEpoch < current.connectionEpoch -> true
        incoming.connectionEpoch > current.connectionEpoch -> false
        incoming.controlGeneration < current.controlGeneration -> true
        incoming.controlGeneration > current.controlGeneration -> false
        resolutionRank(incoming.request.resolution) < resolutionRank(current.request.resolution) -> true
        incoming.buffer.revision < current.buffer.revision -> true
        else -> false
    }

    private fun resolutionRank(
        resolution: RequestResolutionState,
    ): Int = when (resolution) {
        RequestResolutionState.PENDING -> 0
        RequestResolutionState.RESPONDING -> 1
        RequestResolutionState.UNKNOWN -> 2
        RequestResolutionState.RESOLVED -> 3
    }
}
