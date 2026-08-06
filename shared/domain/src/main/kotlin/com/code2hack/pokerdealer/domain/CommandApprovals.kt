package com.code2hack.pokerdealer.domain

import kotlinx.serialization.Serializable

@Serializable
data class ServerRequestLocator(
    val hostId: String,
    val appServerGeneration: Long,
    val requestId: String,
)

@Serializable
enum class RequestResolutionState {
    PENDING,
    RESPONDING,
    RESOLVED,
    UNKNOWN,
}

@Serializable
enum class CommandApprovalDecision(val wireName: String) {
    ACCEPT("accept"),
    ACCEPT_FOR_SESSION("acceptForSession"),
    ACCEPT_WITH_EXECPOLICY_AMENDMENT("acceptWithExecpolicyAmendment"),
    DECLINE("decline"),
    CANCEL("cancel"),
}

@Serializable
data class CommandApprovalScope(
    val command: String?,
    val workingDirectory: String?,
    val networkHost: String?,
    val networkProtocol: String?,
)

@Serializable
data class CommandApprovalRequest(
    val locator: ServerRequestLocator,
    val thread: CodexThreadLocator,
    val turnId: String,
    val itemId: String,
    val approvalId: String?,
    val scope: CommandApprovalScope,
    val proposedExecpolicyAmendment: List<String>?,
    val offeredDecisions: Set<CommandApprovalDecision>,
    /** Preserves the server's safe-choice order for clients that render every choice. */
    val offeredDecisionOrder: List<CommandApprovalDecision> = emptyList(),
    val fingerprint: String,
    val createdAtMs: Long,
    val resolution: RequestResolutionState = RequestResolutionState.PENDING,
    val decision: CommandApprovalDecision? = null,
    val resolvedElsewhere: Boolean = false,
)

@Serializable
data class CommandApprovalState(
    val requests: Map<ServerRequestLocator, CommandApprovalRequest> = emptyMap(),
) {
    fun receive(
        request: CommandApprovalRequest,
        sameIdReissueQualified: Boolean,
    ): CommandApprovalState {
        val current = requests[request.locator]
        require(current == null || current.fingerprint == request.fingerprint) {
            "Server request identity was reused with different command scope"
        }
        if (current != null) return this

        val reissued = requests.values.lastOrNull {
            sameIdReissueQualified &&
                it.locator.hostId == request.locator.hostId &&
                it.locator.requestId == request.locator.requestId &&
                it.locator.appServerGeneration != request.locator.appServerGeneration &&
                it.fingerprint == request.fingerprint &&
                it.resolution != RequestResolutionState.RESOLVED
        }
        return copy(
            requests = (reissued?.let { requests - it.locator } ?: requests) + (
                request.locator to request.copy(
                    resolution = RequestResolutionState.PENDING,
                    decision = null,
                )
            ),
        )
    }

    fun begin(
        locator: ServerRequestLocator,
        decision: CommandApprovalDecision,
    ): CommandApprovalState {
        val request = requests[locator] ?: return this
        if (request.resolution != RequestResolutionState.PENDING) return this
        require(decision in request.offeredDecisions) { "Decision is unavailable for this request" }
        return update(request.copy(resolution = RequestResolutionState.RESPONDING, decision = decision))
    }

    fun unknown(locator: ServerRequestLocator): CommandApprovalState {
        val request = requests[locator] ?: return this
        if (request.resolution == RequestResolutionState.RESOLVED) return this
        return update(request.copy(resolution = RequestResolutionState.UNKNOWN))
    }

    fun resolved(
        hostId: String,
        appServerGeneration: Long,
        requestId: String,
        threadId: String,
    ): CommandApprovalState {
        val request = requests.values.lastOrNull {
            it.locator.hostId == hostId &&
                it.locator.appServerGeneration == appServerGeneration &&
                it.locator.requestId == requestId &&
                it.thread.threadId == threadId &&
                it.resolution != RequestResolutionState.RESOLVED
        } ?: return this
        return update(
            request.copy(
                resolution = RequestResolutionState.RESOLVED,
                resolvedElsewhere = request.resolution != RequestResolutionState.RESPONDING,
                decision = request.decision.takeIf {
                    request.resolution == RequestResolutionState.RESPONDING
                },
            ),
        )
    }

    fun connectionLost(hostId: String, appServerGeneration: Long): CommandApprovalState =
        copy(
            requests = requests.mapValues { (_, request) ->
                if (request.locator.hostId == hostId &&
                    request.locator.appServerGeneration == appServerGeneration &&
                    request.resolution in ACTIVE_STATES
                ) {
                    request.copy(resolution = RequestResolutionState.UNKNOWN)
                } else {
                    request
                }
            },
        )

    fun turnSettled(thread: CodexThreadLocator, turnId: String): CommandApprovalState =
        copy(
            requests = requests.mapValues { (_, request) ->
                if (request.thread == thread &&
                    request.turnId == turnId &&
                    request.resolution != RequestResolutionState.RESOLVED
                ) {
                    request.copy(
                        resolution = RequestResolutionState.RESOLVED,
                        resolvedElsewhere = request.resolution != RequestResolutionState.RESPONDING,
                        decision = request.decision.takeIf {
                            request.resolution == RequestResolutionState.RESPONDING
                        },
                    )
                } else {
                    request
                }
            },
        )

    fun unresolved(hostId: String? = null): List<CommandApprovalRequest> =
        requests.values.filter {
            it.resolution != RequestResolutionState.RESOLVED &&
                (hostId == null || it.locator.hostId == hostId)
        }

    fun unresolvedThreads(): Set<CodexThreadLocator> =
        unresolved().mapTo(mutableSetOf(), CommandApprovalRequest::thread)

    private fun update(request: CommandApprovalRequest): CommandApprovalState =
        copy(requests = requests + (request.locator to request))

    private companion object {
        val ACTIVE_STATES = setOf(RequestResolutionState.PENDING, RequestResolutionState.RESPONDING)
    }
}
