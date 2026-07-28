package com.code2hack.pokerdealer.domain

enum class ThreadWorkState {
    BUSY,
    ATTENTION_REQUIRED,
    READY,
}

enum class TurnOutcome {
    COMPLETED,
    FAILED,
    INTERRUPTED,
}

data class ThreadWorkEvidence(
    val activeTurn: Boolean?,
    val unresolvedRequestCount: Int?,
)

fun ThreadWorkEvidence.workState(): ThreadWorkState? = when {
    activeTurn == null || unresolvedRequestCount == null || unresolvedRequestCount < 0 -> null
    !activeTurn && unresolvedRequestCount > 0 -> null
    unresolvedRequestCount > 0 -> ThreadWorkState.ATTENTION_REQUIRED
    activeTurn -> ThreadWorkState.BUSY
    else -> ThreadWorkState.READY
}

data class ThreadPile(
    val locator: CodexThreadLocator,
    val attachmentOrder: Long,
    val workState: ThreadWorkState?,
    val stateChangedAtMs: Long,
    val available: Boolean,
    val outcome: TurnOutcome? = null,
)

data class PokerPileMetadata(
    val orderedPiles: List<ThreadPile>,
    val unknownWorkState: List<ThreadPile>,
    val hudVisible: Boolean,
    val focused: CodexThreadLocator?,
)

class ThreadPileReducer {
    private val piles = linkedMapOf<CodexThreadLocator, ThreadPile>()
    private var nextAttachmentOrder = 0L
    private var hudVisible = false
    private var focused: CodexThreadLocator? = null
    private var lastViewed: CodexThreadLocator? = null

    fun attach(
        locator: CodexThreadLocator,
        evidence: ThreadWorkEvidence,
        atMs: Long,
        available: Boolean = true,
    ) {
        if (locator in piles) return
        piles[locator] = ThreadPile(
            locator = locator,
            attachmentOrder = nextAttachmentOrder++,
            workState = evidence.workState(),
            stateChangedAtMs = atMs,
            available = available,
        )
    }

    fun detach(locator: CodexThreadLocator) {
        piles.remove(locator)
        if (focused == locator) {
            focused = null
            hudVisible = false
        }
        if (lastViewed == locator) lastViewed = null
    }

    fun reconcile(
        locator: CodexThreadLocator,
        evidence: ThreadWorkEvidence,
        atMs: Long,
        available: Boolean,
    ) {
        update(locator, evidence.workState(), atMs, available, outcome = null, wake = false)
    }

    fun transition(
        locator: CodexThreadLocator,
        evidence: ThreadWorkEvidence,
        atMs: Long,
    ) {
        update(locator, evidence.workState(), atMs, available = null, outcome = null, wake = true)
    }

    fun turnEnded(
        locator: CodexThreadLocator,
        outcome: TurnOutcome,
        atMs: Long,
    ) {
        update(locator, ThreadWorkState.READY, atMs, available = null, outcome = outcome, wake = true)
    }

    fun acceptedPromptOrSteer(locator: CodexThreadLocator, atMs: Long) {
        update(locator, ThreadWorkState.BUSY, atMs, available = null, outcome = null, wake = false)
        if (focused != locator) return
        focused = automaticFocus()
        hudVisible = focused != null
        focused?.let { lastViewed = it }
    }

    fun setAvailable(locator: CodexThreadLocator, available: Boolean) {
        piles[locator]?.let { piles[locator] = it.copy(available = available) }
    }

    fun manualHide() {
        hudVisible = false
        focused?.let { lastViewed = it }
        focused = null
    }

    fun manualWake() {
        val target = automaticFocus()
            ?: lastViewed?.takeIf { piles[it]?.let { pile -> pile.available && pile.workState == ThreadWorkState.BUSY } == true }
            ?: knownPiles().firstOrNull { it.available && it.workState == ThreadWorkState.BUSY }?.locator
        focused = target
        hudVisible = target != null
        target?.let { lastViewed = it }
    }

    fun view(locator: CodexThreadLocator) {
        require(locator in piles) { "Thread is not attached" }
        focused = locator
        lastViewed = locator
        hudVisible = true
    }

    fun readyGatedActionAllowed(locator: CodexThreadLocator): Boolean =
        piles[locator]?.workState == ThreadWorkState.READY

    fun metadata(): PokerPileMetadata {
        val known = knownPiles()
        return PokerPileMetadata(
            orderedPiles = known,
            unknownWorkState = piles.values.filter { it.workState == null }.sortedBy(ThreadPile::attachmentOrder),
            hudVisible = hudVisible,
            focused = focused,
        )
    }

    private fun update(
        locator: CodexThreadLocator,
        workState: ThreadWorkState?,
        atMs: Long,
        available: Boolean?,
        outcome: TurnOutcome?,
        wake: Boolean,
    ) {
        val current = piles[locator] ?: return
        val changed = current.workState != workState ||
            outcome in PROMINENT_OUTCOMES && outcome != current.outcome
        piles[locator] = current.copy(
            workState = workState,
            stateChangedAtMs = if (changed) atMs else current.stateChangedAtMs,
            available = available ?: current.available,
            outcome = outcome ?: current.outcome.takeUnless { workState == ThreadWorkState.BUSY },
        )
        if (wake && changed && !hudVisible && workState in ELIGIBLE_STATES) {
            focused = automaticFocus()
            hudVisible = focused != null
            focused?.let { lastViewed = it }
        }
    }

    private fun knownPiles(): List<ThreadPile> = piles.values
        .filter { it.workState != null }
        .sortedWith(
            compareBy<ThreadPile> { STATE_ORDER.getValue(it.workState!!) }
                .thenBy { if (it.workState == ThreadWorkState.BUSY) it.attachmentOrder else it.stateChangedAtMs }
                .thenBy(ThreadPile::attachmentOrder),
        )

    private fun automaticFocus(): CodexThreadLocator? = knownPiles()
        .firstOrNull { it.available && it.workState == ThreadWorkState.ATTENTION_REQUIRED }
        ?.locator
        ?: knownPiles().firstOrNull { it.available && it.workState == ThreadWorkState.READY }?.locator

    private companion object {
        val ELIGIBLE_STATES = setOf(ThreadWorkState.ATTENTION_REQUIRED, ThreadWorkState.READY)
        val PROMINENT_OUTCOMES = setOf(TurnOutcome.FAILED, TurnOutcome.INTERRUPTED)
        val STATE_ORDER = mapOf(
            ThreadWorkState.BUSY to 0,
            ThreadWorkState.ATTENTION_REQUIRED to 1,
            ThreadWorkState.READY to 2,
        )
    }
}
