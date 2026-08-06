package com.code2hack.poker

import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.PokerNavigationMode
import com.code2hack.pokerdealer.domain.PokerNavigationReducer
import com.code2hack.pokerdealer.domain.PokerPrimaryAction
import com.code2hack.pokerdealer.domain.PokerWheelContext
import com.code2hack.pokerdealer.domain.PokerWheelSelection
import com.code2hack.pokerdealer.domain.ThreadWorkState
import com.code2hack.pokerdealer.protocol.PokerPrimaryActionResult
import com.code2hack.pokerdealer.protocol.PokerPrimaryActionTarget
import java.util.UUID

/** Derives the displayed Primary meaning from the focused, authoritative projection. */
internal class PokerPrimaryActionController(
    private val navigation: PokerNavigationReducer,
    private val composer: PokerComposerController,
    private val userInput: PokerUserInputController,
    private val approvals: PokerApprovalController,
    private val sendAction: suspend (PokerPrimaryActionTarget) -> Boolean,
) {
    private var consumedWheelSession: String? = null
    private data class Candidate(
        val locator: CodexThreadLocator,
        val context: PokerWheelContext,
        val requestLocator: com.code2hack.pokerdealer.domain.ServerRequestLocator? = null,
        val approval: PokerApprovalSubmission? = null,
    )

    fun wheelContext(): PokerWheelContext = candidate()?.context ?: PokerWheelContext()

    suspend fun submit(selection: PokerWheelSelection): Boolean {
        if (selection.action != com.code2hack.pokerdealer.domain.PokerWheelAction.PRIMARY) return false
        val candidate = candidate() ?: return false
        if (candidate.context != selection.context ||
            candidate.context.primaryAction != selection.primaryAction
        ) return false
        val action = selection.primaryAction ?: return false
        if (consumedWheelSession == selection.sessionId) return false
        consumedWheelSession = selection.sessionId
        val target = target(candidate, action, selection.sessionId)
        val locked = if (action == PokerPrimaryAction.REQUEST) {
            if (candidate.approval != null) approvals.beginPrimary(target) else userInput.beginPrimary(target)
        } else {
            composer.beginPrimary(target)
        }
        if (!locked) return false
        if (sendAction(target)) return true
        applyResult(
            PokerPrimaryActionResult(
                target = target,
                outcome = com.code2hack.pokerdealer.protocol.PokerPrimaryActionOutcome.REJECTED,
                reason = "Primary action was not sent",
            ),
        )
        return false
    }

    fun applyResult(result: PokerPrimaryActionResult) {
        if (result.target.action == PokerPrimaryAction.REQUEST) {
            if (result.target.approvalDecision != null) {
                approvals.applyPrimaryResult(result)
            } else {
                userInput.applyPrimaryResult(result)
            }
        } else {
            composer.applyPrimaryResult(result)
        }
    }

    private fun candidate(): Candidate? {
        val metadata = navigation.metadata()
        val locator = metadata.focused ?: return null
        val pile = metadata.orderedPiles.firstOrNull { it.locator == locator } ?: return null
        val layout = navigation.layout(locator) ?: return null
        val anchor = navigation.anchor(locator) ?: return null

        if (anchor.mode == PokerNavigationMode.REQUEST_PANEL) {
            val approval = approvals.focusedSubmission()
            if (approval != null) {
                val projection = approval.projection
                val requestLocator = projection.locator
                if (approvals.isPrimaryLocked(requestLocator)) return null
                val targetId = listOf(
                    locator,
                    "approval",
                    requestLocator,
                    projection.fingerprint,
                    approval.decision,
                    projection.controlGeneration,
                    projection.connectionEpoch,
                    projection.modeSession,
                    projection.hasDealerClaim,
                ).joinToString("|")
                return Candidate(
                    locator = locator,
                    requestLocator = requestLocator,
                    approval = approval,
                    context = PokerWheelContext(
                        targetId = targetId,
                        controlGeneration = projection.controlGeneration,
                        connectionEpoch = projection.connectionEpoch,
                        modeSession = projection.modeSession,
                        primaryAction = PokerPrimaryAction.REQUEST,
                    ),
                )
            }
            val projection = userInput.focusedProjection() ?: return null
            if (projection.modeSession.isBlank()) return null
            if (!projection.hasDealerClaim) return null
            val requestLocator = projection.request.locator
            val morseAvailable = userInput.morseTarget("wheel-preview") != null
            val primary = projection.takeIf {
                !userInput.isPrimaryLocked(requestLocator) &&
                    projection.request.resolution == com.code2hack.pokerdealer.domain.RequestResolutionState.PENDING &&
                    projection.buffer.isComplete(projection.request)
            }?.let { PokerPrimaryAction.REQUEST }
            val targetId = listOf(
                locator,
                "request",
                requestLocator,
                projection.request.fingerprint,
                anchor.cursorPosition,
                projection.buffer.revision,
                projection.controlGeneration,
                projection.connectionEpoch,
                projection.modeSession,
                projection.hasDealerClaim,
                morseAvailable,
            ).joinToString("|")
            return Candidate(
                locator = locator,
                requestLocator = requestLocator,
                context = PokerWheelContext(
                    targetId = targetId,
                    controlGeneration = projection.controlGeneration,
                    connectionEpoch = projection.connectionEpoch,
                    modeSession = projection.modeSession,
                    primaryAction = primary,
                    morseAvailable = morseAvailable,
                ),
            )
        }

        if (anchor.mode != PokerNavigationMode.COMPOSER) return null
        val composerLayout = layout.composer ?: return null
        if (composerLayout.modeSession.isBlank()) return null
        if (!composerLayout.hasDealerClaim) return null
        val draft = composerLayout.draft ?: return null
        val morseAvailable = composer.morseTarget("wheel-preview") != null
        val primary = when (pile.workState) {
            ThreadWorkState.READY -> PokerPrimaryAction.SEND.takeIf { draft.isSubmittable }
            ThreadWorkState.BUSY -> when {
                composerLayout.activeTurnId == null -> null
                draft.isSubmittable -> PokerPrimaryAction.STEER
                else -> PokerPrimaryAction.INTERRUPT
            }
            ThreadWorkState.ATTENTION_REQUIRED -> null
            null -> null
        }
        if (primary == null && !morseAvailable) return null
        if (composer.isPrimaryLocked(locator) && !morseAvailable) return null
        val targetId = listOf(
            locator,
            "composer",
            draft.revision,
            anchor.cursorPosition,
            composerLayout.controlGeneration,
            composerLayout.connectionEpoch,
            composerLayout.modeSession,
            composerLayout.activeTurnId.orEmpty(),
            composerLayout.hasDealerClaim,
            primary,
            morseAvailable,
        ).joinToString("|")
        return Candidate(
            locator = locator,
            context = PokerWheelContext(
                targetId = targetId,
                controlGeneration = composerLayout.controlGeneration,
                connectionEpoch = composerLayout.connectionEpoch,
                modeSession = composerLayout.modeSession,
                photoAvailable = true,
                primaryAction = primary,
                morseAvailable = morseAvailable,
            ),
        )
    }

    private fun target(
        candidate: Candidate,
        action: PokerPrimaryAction,
        wheelSession: String,
    ): PokerPrimaryActionTarget {
        val layout = navigation.layout(candidate.locator)
        val anchor = navigation.anchor(candidate.locator)
        val composerLayout = layout?.composer
        val request = candidate.requestLocator
        val composerDraftTarget = action == PokerPrimaryAction.SEND || action == PokerPrimaryAction.STEER
        val turnTarget = action == PokerPrimaryAction.STEER || action == PokerPrimaryAction.INTERRUPT
        return PokerPrimaryActionTarget(
            locator = candidate.locator,
            action = action,
            wheelSession = wheelSession,
            controlGeneration = candidate.context.controlGeneration,
            connectionEpoch = candidate.context.connectionEpoch,
            modeSession = candidate.context.modeSession,
            draftRevision = composerLayout?.draft?.revision.takeIf { composerDraftTarget },
            cursorPosition = anchor?.cursorPosition.takeIf { composerDraftTarget },
            expectedTurnId = composerLayout?.activeTurnId.takeIf { turnTarget },
            requestLocator = request,
            approvalDecision = candidate.approval?.decision,
            answerRevision = candidate.approval?.let { null }
                ?: request?.let { userInput.projection(it)?.buffer?.revision },
            requestFingerprint = candidate.approval?.projection?.fingerprint
                ?: request?.let { userInput.projection(it)?.request?.fingerprint },
            operationId = UUID.randomUUID().toString(),
        )
    }
}
