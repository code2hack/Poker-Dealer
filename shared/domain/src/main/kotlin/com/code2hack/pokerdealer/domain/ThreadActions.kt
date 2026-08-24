package com.code2hack.pokerdealer.domain

enum class ComposerAction(val label: String) {
    START("Send"),
    STEER("Steer active turn"),
    BLOCKED("Send unavailable"),
}

fun ThreadWorkState?.composerAction(): ComposerAction = when (this) {
    ThreadWorkState.READY -> ComposerAction.START
    ThreadWorkState.BUSY -> ComposerAction.STEER
    ThreadWorkState.ATTENTION_REQUIRED, null -> ComposerAction.BLOCKED
}

data class PendingThreadInput(
    val clientId: String,
    val action: ComposerAction,
    val expectedTurnId: String?,
    val draftText: String,
    val uncertain: Boolean = false,
    val draft: ComposerDraft = ComposerDraft.fromText(draftText),
)

data class ThreadActionState(
    /** Legacy display projection retained for the existing Dealer UI and app-server text path. */
    val drafts: Map<CodexThreadLocator, String> = emptyMap(),
    /** Canonical ordered draft state. */
    val composerDrafts: Map<CodexThreadLocator, ComposerDraft> = emptyMap(),
    val pendingInputs: Map<CodexThreadLocator, PendingThreadInput> = emptyMap(),
    val pendingInterrupts: Map<CodexThreadLocator, String> = emptyMap(),
    val pendingReasoningEfforts: Map<CodexThreadLocator, String> = emptyMap(),
) {
    fun composerDraft(locator: CodexThreadLocator): ComposerDraft =
        composerDrafts[locator] ?: ComposerDraft.fromLegacy(drafts[locator].orEmpty())

    fun editDraft(locator: CodexThreadLocator, text: String): ThreadActionState =
        editComposerDraft(locator, ComposerDraft.fromText(text))

    fun editComposerDraft(locator: CodexThreadLocator, draft: ComposerDraft): ThreadActionState {
        val normalized = draft.normalized()
        val current = composerDrafts[locator] ?: ComposerDraft.fromLegacy(drafts[locator].orEmpty())
        val versioned = if (normalized.elements == current.elements) {
            normalized.withRevision(current.revision)
        } else {
            normalized.withRevision(maxOf(current.revision + 1, normalized.revision))
        }
        return copy(
            drafts = if (versioned.isEmpty) drafts - locator else drafts + (locator to versioned.displayText),
            composerDrafts = if (versioned.isEmpty) {
                composerDrafts - locator
            } else {
                composerDrafts + (locator to versioned)
            },
        )
    }

    fun beginInput(
        locator: CodexThreadLocator,
        workState: ThreadWorkState?,
        activeTurnId: String?,
        hasDealerClaim: Boolean,
        clientId: String,
    ): Pair<ThreadActionState, PendingThreadInput> {
        require(hasDealerClaim) { "Take control before sending" }
        require(locator !in pendingInputs) { "Reconcile the previous input before sending again" }
        val draft = composerDraft(locator)
        require(draft.isSubmittable) { "Draft is empty" }
        val action = workState.composerAction()
        require(action != ComposerAction.BLOCKED) { "Prompt submission is unavailable in the current thread state" }
        val expectedTurnId = activeTurnId.takeIf { action == ComposerAction.STEER }
        require(action != ComposerAction.STEER || expectedTurnId != null) {
            "Reconcile the active turn before steering"
        }
        val pending = PendingThreadInput(
            clientId = clientId,
            action = action,
            expectedTurnId = expectedTurnId,
            draftText = draft.displayText,
            draft = draft,
        )
        return copy(pendingInputs = pendingInputs + (locator to pending)) to pending
    }

    fun inputAccepted(locator: CodexThreadLocator, clientId: String): ThreadActionState {
        require(pendingInputs[locator]?.clientId == clientId) { "Input action no longer matches" }
        val pending = pendingInputs.getValue(locator)
        return copy(
            drafts = if (composerDraft(locator) == pending.draft) drafts - locator else drafts,
            composerDrafts = if (composerDraft(locator) == pending.draft) {
                composerDrafts - locator
            } else {
                composerDrafts
            },
            pendingInputs = pendingInputs - locator,
            pendingReasoningEfforts = if (pending.action == ComposerAction.START) {
                pendingReasoningEfforts - locator
            } else {
                pendingReasoningEfforts
            },
        )
    }

    fun inputRejected(locator: CodexThreadLocator, clientId: String): ThreadActionState {
        require(pendingInputs[locator]?.clientId == clientId) { "Input action no longer matches" }
        return copy(pendingInputs = pendingInputs - locator)
    }

    fun inputUncertain(locator: CodexThreadLocator, clientId: String): ThreadActionState {
        val pending = pendingInputs[locator]
        require(pending?.clientId == clientId) { "Input action no longer matches" }
        return copy(pendingInputs = pendingInputs + (locator to pending.copy(uncertain = true)))
    }

    fun reconcileInput(locator: CodexThreadLocator, clientId: String): ThreadActionState =
        if (pendingInputs[locator]?.clientId == clientId) {
            copy(pendingInputs = pendingInputs - locator)
        } else {
            this
        }

    fun beginInterrupt(
        locator: CodexThreadLocator,
        activeTurnId: String?,
        hasDealerClaim: Boolean,
    ): Pair<ThreadActionState, String> {
        require(hasDealerClaim) { "Take control before interrupting" }
        require(activeTurnId != null) { "Reconcile the active turn before interrupting" }
        require(locator !in pendingInterrupts) { "Interrupt is already pending" }
        return copy(pendingInterrupts = pendingInterrupts + (locator to activeTurnId)) to activeTurnId
    }

    fun reconcileInterrupt(locator: CodexThreadLocator, activeTurnId: String?): ThreadActionState =
        if (pendingInterrupts[locator] != activeTurnId) {
            copy(pendingInterrupts = pendingInterrupts - locator)
        } else {
            this
        }

    fun setPendingReasoningEffort(
        locator: CodexThreadLocator,
        effort: String?,
    ): ThreadActionState = copy(
        pendingReasoningEfforts = if (effort == null) {
            pendingReasoningEfforts - locator
        } else {
            pendingReasoningEfforts + (locator to effort)
        },
    )

    fun purge(locators: Set<CodexThreadLocator>): ThreadActionState = copy(
        drafts = drafts - locators,
        composerDrafts = composerDrafts - locators,
        pendingInputs = pendingInputs - locators,
        pendingInterrupts = pendingInterrupts - locators,
        pendingReasoningEfforts = pendingReasoningEfforts - locators,
    )
}
