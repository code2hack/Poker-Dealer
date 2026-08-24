package com.code2hack.pokerdealer.domain

enum class ThreadLifecycleAction {
    ARCHIVE,
    DELETE,
}

data class ThreadCascadePreflight(
    val selected: DiscoveredThread,
    val descendants: List<DiscoveredThread>,
) {
    val affected: List<DiscoveredThread> =
        (listOf(selected) + descendants).distinctBy(DiscoveredThread::locator)

    val blockingReason: String?
        get() {
            val unknownState = affected.firstOrNull { it.workState == null }
            if (unknownState != null) {
                return "Refresh ${unknownState.locator.threadId}: its work state is unknown"
            }
            val active = affected.firstOrNull { it.workState != ThreadWorkState.READY }
            if (active != null) {
                return "${active.locator.threadId} is ${active.workState}; every affected thread must be READY"
            }
            val unknownEphemeral = affected.firstOrNull { it.ephemeral == null }
            if (unknownEphemeral != null) {
                return "Refresh ${unknownEphemeral.locator.threadId}: its ephemeral state is unknown"
            }
            val ephemeral = affected.firstOrNull { it.ephemeral == true }
            if (ephemeral != null) {
                return "${ephemeral.locator.threadId} is ephemeral"
            }
            return null
        }

    val eligible: Boolean
        get() = blockingReason == null

    fun safetyMatches(other: ThreadCascadePreflight): Boolean =
        safetyFingerprint() == other.safetyFingerprint()

    private fun safetyFingerprint(): Set<SafetyMetadata> = affected.mapTo(mutableSetOf()) {
        SafetyMetadata(it.locator, it.archived, it.ephemeral, it.workState)
    }

    private data class SafetyMetadata(
        val locator: CodexThreadLocator,
        val archived: Boolean,
        val ephemeral: Boolean?,
        val workState: ThreadWorkState?,
    )
}
