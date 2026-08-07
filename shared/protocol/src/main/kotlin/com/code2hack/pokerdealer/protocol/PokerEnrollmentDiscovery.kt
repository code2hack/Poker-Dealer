package com.code2hack.pokerdealer.protocol

// DNS-SD service labels are intentionally short; discovery is not a trust identity.
const val POKER_ENROLLMENT_SERVICE_TYPE = "_pd-enroll._tcp."

sealed interface PokerEnrollmentDiscoveryResult {
    data class Found(val endpoint: PokerHotspotEndpoint) : PokerEnrollmentDiscoveryResult
    data object NotFound : PokerEnrollmentDiscoveryResult
    data object Ambiguous : PokerEnrollmentDiscoveryResult
}

/** Keeps discovery identity separate from the PAKE-authenticated peer identity. */
class PokerEnrollmentCandidates {
    private val activeServices = linkedSetOf<String>()
    private val resolvedEndpoints = linkedMapOf<String, PokerHotspotEndpoint>()

    fun found(serviceName: String) {
        require(serviceName.isNotBlank()) { "Enrollment service name must not be blank" }
        activeServices += serviceName
    }

    fun lost(serviceName: String) {
        activeServices -= serviceName
        resolvedEndpoints -= serviceName
    }

    fun resolved(serviceName: String, host: String, port: Int) {
        if (serviceName !in activeServices || port != POKER_LISTENER_PORT) return
        val endpoint = runCatching { PokerHotspotEndpoint(host, port) }.getOrNull() ?: return
        resolvedEndpoints[serviceName] = endpoint
    }

    fun result(): PokerEnrollmentDiscoveryResult = when (activeServices.size) {
        0 -> PokerEnrollmentDiscoveryResult.NotFound
        1 -> activeServices.single()
            .let(resolvedEndpoints::get)
            ?.let(PokerEnrollmentDiscoveryResult::Found)
            ?: PokerEnrollmentDiscoveryResult.NotFound
        else -> PokerEnrollmentDiscoveryResult.Ambiguous
    }
}
