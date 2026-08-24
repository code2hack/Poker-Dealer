package com.code2hack.pokerdealer.domain

enum class ThreadWorkState {
    BUSY,
    ATTENTION_REQUIRED,
    READY,
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

/**
 * Transport- and presentation-neutral Dealer work-state derivation.
 *
 * Unknown authoritative evidence remains unknown. A blocking request without an active turn is
 * internally inconsistent and therefore also remains unknown rather than inventing a fourth state.
 */
fun ThreadWorkEvidence.workState(): ThreadWorkState? = when {
    activeTurn == null || unresolvedRequestCount == null || unresolvedRequestCount < 0 -> null
    !activeTurn && unresolvedRequestCount > 0 -> null
    unresolvedRequestCount > 0 -> ThreadWorkState.ATTENTION_REQUIRED
    activeTurn -> ThreadWorkState.BUSY
    else -> ThreadWorkState.READY
}
