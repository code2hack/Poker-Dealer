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
)

data class ThreadActionState(
    val drafts: Map<CodexThreadLocator, String> = emptyMap(),
    val pendingInputs: Map<CodexThreadLocator, PendingThreadInput> = emptyMap(),
    val pendingInterrupts: Map<CodexThreadLocator, String> = emptyMap(),
) {
    fun editDraft(locator: CodexThreadLocator, text: String): ThreadActionState =
        copy(drafts = if (text.isEmpty()) drafts - locator else drafts + (locator to text))

    fun beginInput(
        locator: CodexThreadLocator,
        workState: ThreadWorkState?,
        activeTurnId: String?,
        hasDealerClaim: Boolean,
        clientId: String,
    ): Pair<ThreadActionState, PendingThreadInput> {
        require(hasDealerClaim) { "Take control before sending" }
        require(locator !in pendingInputs) { "Reconcile the previous input before sending again" }
        require(drafts[locator].orEmpty().isNotBlank()) { "Draft is empty" }
        val action = workState.composerAction()
        require(action != ComposerAction.BLOCKED) { "Prompt submission is unavailable in the current thread state" }
        val expectedTurnId = activeTurnId.takeIf { action == ComposerAction.STEER }
        require(action != ComposerAction.STEER || expectedTurnId != null) {
            "Reconcile the active turn before steering"
        }
        val pending = PendingThreadInput(clientId, action, expectedTurnId, drafts.getValue(locator))
        return copy(pendingInputs = pendingInputs + (locator to pending)) to pending
    }

    fun inputAccepted(locator: CodexThreadLocator, clientId: String): ThreadActionState {
        require(pendingInputs[locator]?.clientId == clientId) { "Input action no longer matches" }
        val pending = pendingInputs.getValue(locator)
        return copy(
            drafts = if (drafts[locator] == pending.draftText) drafts - locator else drafts,
            pendingInputs = pendingInputs - locator,
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
}
