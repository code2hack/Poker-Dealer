package com.code2hack.pokerdealer.domain

enum class ThreadWorkState {
    BUSY,
    ATTENTION_REQUIRED,
    READY,
}

enum class PileDirection {
    LEFT,
    RIGHT,
}

@kotlinx.serialization.Serializable
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
    private val busyActivityOrder = mutableMapOf<CodexThreadLocator, Long>()
    private var nextAttachmentOrder = 0L
    private var nextBusyActivityOrder = 0L
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
        val workState = evidence.workState()
        piles[locator] = ThreadPile(
            locator = locator,
            attachmentOrder = nextAttachmentOrder++,
            workState = workState,
            stateChangedAtMs = atMs,
            available = available,
        )
        if (workState == ThreadWorkState.BUSY) {
            busyActivityOrder[locator] = nextBusyActivityOrder++
        }
    }

    fun detach(locator: CodexThreadLocator) {
        val oldIndex = knownPiles().indexOfFirst { it.locator == locator }
        val wasFocused = focused == locator
        piles.remove(locator)
        busyActivityOrder.remove(locator)
        if (wasFocused) {
            focusNearest(oldIndex)
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
        update(
            locator,
            ThreadWorkState.BUSY,
            atMs,
            available = null,
            outcome = null,
            wake = false,
            refreshBusyActivity = true,
        )
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
        val target = restoreLastViewed()
        focused = target
        hudVisible = target != null
    }

    fun view(locator: CodexThreadLocator) {
        require(locator in piles) { "Thread is not attached" }
        focused = locator
        lastViewed = locator
        hudVisible = true
    }

    fun moveFocus(direction: PileDirection): Boolean {
        val current = focused ?: return false
        val known = knownPiles()
        val index = known.indexOfFirst { it.locator == current }
        if (index < 0) return false

        val targetIndex = when (direction) {
            PileDirection.LEFT -> index - 1
            PileDirection.RIGHT -> index + 1
        }
        val target = known.getOrNull(targetIndex)?.locator ?: return false
        focused = target
        lastViewed = target
        return true
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
        refreshBusyActivity: Boolean = false,
    ) {
        val current = piles[locator] ?: return
        val oldIndex = if (focused == locator) {
            knownPiles().indexOfFirst { it.locator == locator }
        } else {
            -1
        }
        val changed = current.workState != workState ||
            outcome in PROMINENT_OUTCOMES && outcome != current.outcome
        if (workState == ThreadWorkState.BUSY && (current.workState != workState || refreshBusyActivity)) {
            busyActivityOrder[locator] = nextBusyActivityOrder++
        } else if (workState != ThreadWorkState.BUSY) {
            busyActivityOrder.remove(locator)
        }
        piles[locator] = current.copy(
            workState = workState,
            stateChangedAtMs = if (changed || refreshBusyActivity) atMs else current.stateChangedAtMs,
            available = available ?: current.available,
            outcome = outcome ?: current.outcome.takeUnless { workState == ThreadWorkState.BUSY },
        )
        if (focused == locator && current.workState != null && workState == null) {
            focusNearest(oldIndex)
        }
        if (wake && changed && !hudVisible) {
            focused = restoreLastViewed()
            hudVisible = focused != null
        }
    }

    private fun knownPiles(): List<ThreadPile> = piles.values
        .filter { it.workState != null }
        .sortedWith(
            compareBy<ThreadPile> { STATE_ORDER.getValue(it.workState!!) }
                .thenBy(ThreadPile::stateChangedAtMs)
                .thenBy { if (it.workState == ThreadWorkState.BUSY) busyActivityOrder[it.locator] else null }
                .thenBy(ThreadPile::attachmentOrder),
        )

    private fun focusNearest(oldIndex: Int) {
        val known = knownPiles()
        val replacement = known.getOrNull(oldIndex)?.locator
            ?: known.getOrNull(oldIndex - 1)?.locator
        focused = replacement
        if (replacement == null) {
            lastViewed = null
            hudVisible = false
        } else {
            lastViewed = replacement
        }
    }

    private fun restoreLastViewed(): CodexThreadLocator? = lastViewed?.takeIf { it in piles }

    private companion object {
        val PROMINENT_OUTCOMES = setOf(TurnOutcome.FAILED, TurnOutcome.INTERRUPTED)
        val STATE_ORDER = mapOf(
            ThreadWorkState.BUSY to 0,
            ThreadWorkState.ATTENTION_REQUIRED to 1,
            ThreadWorkState.READY to 2,
        )
    }
}
