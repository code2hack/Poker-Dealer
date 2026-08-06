package com.code2hack.poker

import com.code2hack.pokerdealer.domain.ComposerEditResult
import com.code2hack.pokerdealer.domain.ComposerEditorState
import com.code2hack.pokerdealer.domain.ComposerSurface
import com.code2hack.pokerdealer.domain.ComposerDeletionRequest
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.MorseModeTarget
import com.code2hack.pokerdealer.domain.MorseMutationTarget
import com.code2hack.pokerdealer.domain.PokerComposerLayout
import com.code2hack.pokerdealer.domain.PokerNavigationReducer
import com.code2hack.pokerdealer.domain.PokerPileLayout
import com.code2hack.pokerdealer.protocol.ComposerDraftProjection
import com.code2hack.pokerdealer.protocol.ComposerMutationRequest
import com.code2hack.pokerdealer.protocol.ComposerMutationOutcome
import com.code2hack.pokerdealer.protocol.ComposerMutationResult
import com.code2hack.pokerdealer.domain.PokerPrimaryAction
import com.code2hack.pokerdealer.protocol.PokerPrimaryActionOutcome
import com.code2hack.pokerdealer.protocol.PokerPrimaryActionResult
import com.code2hack.pokerdealer.protocol.PokerPrimaryActionTarget

/** Applies Dealer projections locally and serializes one exact optimistic edit at a time. */
internal class PokerComposerController(
    private val navigation: PokerNavigationReducer,
    private val sendMutation: suspend (ComposerMutationRequest) -> Boolean,
) {
    private val editors = mutableMapOf<CodexThreadLocator, ComposerEditorState>()
    private val pendingPrimary = mutableMapOf<CodexThreadLocator, PokerPrimaryActionTarget>()

    fun applyProjection(projection: ComposerDraftProjection) {
        pendingPrimary[projection.locator]?.let { target ->
            if (target.controlGeneration != projection.controlGeneration ||
                target.connectionEpoch != projection.connectionEpoch ||
                target.modeSession != projection.modeSession ||
                (target.action == PokerPrimaryAction.INTERRUPT &&
                    target.expectedTurnId != projection.activeTurnId) ||
                (target.action != PokerPrimaryAction.INTERRUPT &&
                    target.draftRevision != projection.draft.revision)
            ) {
                pendingPrimary.remove(projection.locator)
            }
        }
        val editor = if (projection.modeSession.isBlank()) {
            null
        } else {
            val current = editors[projection.locator]
            if (
                current != null &&
                current.draft.revision == projection.draft.revision &&
                current.draft.elements == projection.draft.elements &&
                current.controlGeneration == projection.controlGeneration &&
                current.connectionEpoch == projection.connectionEpoch &&
                current.modeSession == projection.modeSession
            ) {
                current
            } else {
                ComposerEditorState.atEnd(
                    locator = projection.locator,
                    draft = projection.draft,
                    controlGeneration = projection.controlGeneration,
                    connectionEpoch = projection.connectionEpoch,
                    modeSession = projection.modeSession,
                )
            }
        }
        if (editor != null) editors[projection.locator] = editor
        updateLayout(projection, editor?.draft ?: projection.draft)
    }

    suspend fun requestDeletion(request: ComposerDeletionRequest): Boolean {
        val target = request.target ?: return false
        if (target.surface != ComposerSurface.THREAD_COMPOSER) return false
        if (pendingPrimary[target.locator]?.action in setOf(
            PokerPrimaryAction.SEND,
            PokerPrimaryAction.STEER,
        )) return false
        val current = editors[target.locator] ?: return false
        val edit = try {
            current.copy(cursorPosition = target.cursorPosition).beginTextDeletion(target)
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (edit !is ComposerEditResult.Started) return false
        editors[target.locator] = edit.editor
        updateLayoutFor(target.locator, edit.editor.draft, edit.editor)
        return sendMutation(
            ComposerMutationRequest(
                target = target,
                kind = com.code2hack.pokerdealer.protocol.ComposerMutationKind.DELETE_THROUGH_NEXT_WORD,
            ),
        )
    }

    fun beginPrimary(target: PokerPrimaryActionTarget): Boolean {
        if (target.action == PokerPrimaryAction.REQUEST) return false
        if (pendingPrimary[target.locator] != null) return false
        val current = editors[target.locator] ?: return false
        if (navigation.layout(target.locator)?.composer?.hasDealerClaim != true) return false
        if (current.controlGeneration != target.controlGeneration ||
            current.connectionEpoch != target.connectionEpoch ||
            current.modeSession != target.modeSession
        ) return false
        when (target.action) {
            PokerPrimaryAction.SEND,
            PokerPrimaryAction.STEER,
            -> if (target.draftRevision != current.draft.revision ||
                target.cursorPosition != current.cursorPosition
            ) return false
            PokerPrimaryAction.INTERRUPT -> {
                val activeTurnId = navigation.layout(target.locator)?.composer?.activeTurnId
                if (activeTurnId != target.expectedTurnId) return false
            }
            PokerPrimaryAction.REQUEST -> return false
        }
        pendingPrimary[target.locator] = target
        setPrimaryLock(
            target.locator,
            target.action == PokerPrimaryAction.SEND || target.action == PokerPrimaryAction.STEER,
        )
        return true
    }

    fun applyPrimaryResult(result: PokerPrimaryActionResult) {
        val target = pendingPrimary[result.target.locator] ?: return
        if (target != result.target) return
        when (result.outcome) {
            PokerPrimaryActionOutcome.REJECTED -> {
                pendingPrimary.remove(target.locator)
                setPrimaryLock(target.locator, false)
            }
            PokerPrimaryActionOutcome.ACCEPTED,
            PokerPrimaryActionOutcome.UNKNOWN -> Unit
        }
    }

    fun isPrimaryLocked(locator: CodexThreadLocator): Boolean = pendingPrimary[locator] != null

    fun morseTarget(freshModeSession: String): MorseModeTarget? {
        val locator = navigation.metadata().focused ?: return null
        val anchor = navigation.anchor(locator)
            ?.takeIf { it.mode == com.code2hack.pokerdealer.domain.PokerNavigationMode.COMPOSER }
            ?: return null
        val layout = navigation.layout(locator)?.composer ?: return null
        val draft = layout.draft ?: return null
        val editor = editors[locator] ?: return null
        if (freshModeSession.isBlank() || layout.modeSession.isBlank() ||
            !layout.hasDealerClaim || layout.primaryActionLocked ||
            pendingPrimary[locator] != null || editor.pendingMutation != null
        ) return null
        if (anchor.cursorPosition !in 0 until draft.cursorCount) return null
        return MorseModeTarget(
            locator = locator,
            surface = ComposerSurface.THREAD_COMPOSER,
            revision = draft.revision,
            cursorPosition = anchor.cursorPosition,
            controlGeneration = layout.controlGeneration,
            connectionEpoch = layout.connectionEpoch,
            bindingModeSession = layout.modeSession,
            modeSession = freshModeSession,
        )
    }

    fun installMorseDraft(
        target: MorseMutationTarget,
        draft: com.code2hack.pokerdealer.domain.ComposerDraft,
        cursorPosition: Int,
    ): Boolean {
        val mode = target.mode
        if (mode.surface != ComposerSurface.THREAD_COMPOSER) return false
        val current = editors[mode.locator] ?: return false
        if (current.draft.revision != mode.revision ||
            current.controlGeneration != mode.controlGeneration ||
            current.connectionEpoch != mode.connectionEpoch ||
            current.modeSession != mode.bindingModeSession
        ) return false
        val installed = draft.normalized()
        val next = ComposerEditorState(
            locator = mode.locator,
            draft = installed,
            cursorPosition = cursorPosition.coerceIn(0, installed.cursorCount - 1),
            controlGeneration = mode.controlGeneration,
            connectionEpoch = mode.connectionEpoch,
            modeSession = mode.bindingModeSession,
        )
        editors[mode.locator] = next
        updateLayoutFor(mode.locator, installed, next)
        return true
    }

    fun applyResult(result: ComposerMutationResult) {
        val current = editors[result.target.locator] ?: return
        val pending = current.pendingMutation ?: return
        if (pending.target != result.target) return
        val next = when (result.outcome) {
            ComposerMutationOutcome.ACKNOWLEDGED -> current.acknowledge(result.target, result.draft)
            ComposerMutationOutcome.REJECTED,
            ComposerMutationOutcome.UNCERTAIN,
            -> current.rejectOrUncertain(result.target, result.draft)
        }
        editors[result.target.locator] = next
        updateLayoutFor(result.target.locator, next.draft, next)
    }

    private fun updateLayout(
        projection: ComposerDraftProjection,
        draft: com.code2hack.pokerdealer.domain.ComposerDraft,
    ) {
        val editor = editors[projection.locator]
        updateLayoutFor(projection.locator, draft, editor, projection)
    }

    private fun updateLayoutFor(
        locator: CodexThreadLocator,
        draft: com.code2hack.pokerdealer.domain.ComposerDraft,
        editor: ComposerEditorState?,
        projection: ComposerDraftProjection? = null,
    ) {
        val existing = navigation.layout(locator) ?: return
        val oldComposer = existing.composer
        val composer = PokerComposerLayout(
            positionCount = oldComposer?.positionCount ?: 1,
            draft = draft,
            controlGeneration = projection?.controlGeneration
                ?: editor?.controlGeneration
                ?: oldComposer?.controlGeneration
                ?: 0L,
            connectionEpoch = projection?.connectionEpoch
                ?: editor?.connectionEpoch
                ?: oldComposer?.connectionEpoch
                ?: 0L,
            modeSession = (
                projection?.modeSession
                    ?: editor?.modeSession
                    ?: oldComposer?.modeSession
            ).orEmpty(),
            activeTurnId = projection?.activeTurnId ?: oldComposer?.activeTurnId,
            primaryActionLocked = pendingPrimary[locator]?.action in setOf(
                PokerPrimaryAction.SEND,
                PokerPrimaryAction.STEER,
            ),
            hasDealerClaim = projection?.hasDealerClaim ?: oldComposer?.hasDealerClaim ?: true,
        )
        navigation.setLayout(locator, PokerPileLayout(existing.cards, composer))
        editor?.let { navigation.setComposerCursor(locator, it.cursorPosition) }
    }

    private fun setPrimaryLock(locator: CodexThreadLocator, locked: Boolean) {
        val existing = navigation.layout(locator) ?: return
        val composer = existing.composer ?: return
        navigation.setLayout(
            locator,
            PokerPileLayout(existing.cards, composer.copy(primaryActionLocked = locked)),
        )
    }
}
