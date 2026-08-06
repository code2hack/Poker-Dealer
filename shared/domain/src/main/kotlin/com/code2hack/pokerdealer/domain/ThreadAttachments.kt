package com.code2hack.pokerdealer.domain

data class ThreadAttachmentState(
    val attached: Set<CodexThreadLocator> = emptySet(),
    val dealerClaims: Set<CodexThreadLocator> = emptySet(),
    val controlGenerations: Map<CodexThreadLocator, Long> = emptyMap(),
) {
    fun controlGeneration(locator: CodexThreadLocator): Long =
        controlGenerations[locator] ?: 0L

    fun attach(locator: CodexThreadLocator): ThreadAttachmentState = copy(
        attached = attached + locator,
        dealerClaims = dealerClaims - locator,
        controlGenerations = nextGeneration(locator),
    )

    fun detach(
        locator: CodexThreadLocator,
        hasKnownBlockingRequest: Boolean = false,
    ): ThreadAttachmentState {
        require(!hasKnownBlockingRequest) {
            "Resolve, cancel, or interrupt the pending request before detaching"
        }
        return copy(
            attached = attached - locator,
            dealerClaims = dealerClaims - locator,
            controlGenerations = nextGeneration(locator),
        )
    }

    fun claim(locator: CodexThreadLocator): ThreadAttachmentState {
        require(locator in attached) { "Attach the thread before taking control" }
        return copy(
            dealerClaims = dealerClaims + locator,
            controlGenerations = nextGeneration(locator),
        )
    }

    fun release(locator: CodexThreadLocator): ThreadAttachmentState =
        copy(
            dealerClaims = dealerClaims - locator,
            controlGenerations = nextGeneration(locator),
        )

    fun releaseHost(hostId: String): ThreadAttachmentState =
        dealerClaims
            .filter { it.hostId == hostId }
            .fold(this) { state, locator -> state.release(locator) }

    fun externalTurnStarted(
        locator: CodexThreadLocator,
        dealerOriginated: Boolean,
    ): ThreadAttachmentState =
        if (dealerOriginated) this else release(locator)

    fun hasDealerClaim(locator: CodexThreadLocator): Boolean =
        locator in attached && locator in dealerClaims

    private fun nextGeneration(locator: CodexThreadLocator): Map<CodexThreadLocator, Long> =
        controlGenerations + (locator to controlGeneration(locator) + 1)
}
