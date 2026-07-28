package com.code2hack.pokerdealer.domain

data class ThreadAttachmentState(
    val attached: Set<CodexThreadLocator> = emptySet(),
    val dealerClaims: Set<CodexThreadLocator> = emptySet(),
) {
    fun attach(locator: CodexThreadLocator): ThreadAttachmentState = copy(
        attached = attached + locator,
        dealerClaims = dealerClaims - locator,
    )

    fun detach(
        locator: CodexThreadLocator,
        hasKnownBlockingRequest: Boolean = false,
    ): ThreadAttachmentState {
        require(!hasKnownBlockingRequest) {
            "Resolve, cancel, or interrupt the pending request before detaching"
        }
        return copy(attached = attached - locator, dealerClaims = dealerClaims - locator)
    }

    fun claim(locator: CodexThreadLocator): ThreadAttachmentState {
        require(locator in attached) { "Attach the thread before taking control" }
        return copy(dealerClaims = dealerClaims + locator)
    }

    fun release(locator: CodexThreadLocator): ThreadAttachmentState =
        copy(dealerClaims = dealerClaims - locator)

    fun releaseHost(hostId: String): ThreadAttachmentState =
        copy(dealerClaims = dealerClaims.filterTo(mutableSetOf()) { it.hostId != hostId })

    fun externalTurnStarted(
        locator: CodexThreadLocator,
        dealerOriginated: Boolean,
    ): ThreadAttachmentState =
        if (dealerOriginated) this else release(locator)

    fun hasDealerClaim(locator: CodexThreadLocator): Boolean =
        locator in attached && locator in dealerClaims
}
