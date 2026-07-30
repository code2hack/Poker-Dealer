package com.code2hack.pokerdealer.domain

import kotlinx.serialization.Serializable

@Serializable
enum class FileApprovalDecision(val wireName: String) {
    ACCEPT("accept"),
    ACCEPT_FOR_SESSION("acceptForSession"),
    DECLINE("decline"),
    CANCEL("cancel"),
}

@Serializable
data class FileApprovalRequest(
    val locator: ServerRequestLocator,
    val thread: CodexThreadLocator,
    val turnId: String,
    val itemId: String,
    val reason: String?,
    val grantRoot: String?,
    val fileChanges: List<FileChangeContent>,
    val wireFingerprint: String,
    val fingerprint: String,
    val createdAtMs: Long,
    val reviewComplete: Boolean = false,
    val resolution: RequestResolutionState = RequestResolutionState.PENDING,
    val decision: FileApprovalDecision? = null,
    val resolvedElsewhere: Boolean = false,
    val failureReason: String? = null,
)

@Serializable
data class FileApprovalState(
    val requests: Map<ServerRequestLocator, FileApprovalRequest> = emptyMap(),
) {
    fun receive(
        request: FileApprovalRequest,
        sameIdReissueQualified: Boolean,
    ): FileApprovalState {
        val current = requests[request.locator]
        require(current == null || current.wireFingerprint == request.wireFingerprint) {
            "Server request identity was reused with different file scope"
        }
        if (current != null) {
            return if (current.resolution == RequestResolutionState.PENDING &&
                !current.reviewComplete &&
                request.reviewComplete
            ) {
                update(request)
            } else {
                this
            }
        }

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
        decision: FileApprovalDecision,
    ): FileApprovalState {
        val request = requests[locator] ?: return this
        if (request.resolution != RequestResolutionState.PENDING) return this
        require(request.reviewComplete || decision == FileApprovalDecision.CANCEL) {
            "File review material is incomplete"
        }
        return update(request.copy(resolution = RequestResolutionState.RESPONDING, decision = decision))
    }

    fun failClosed(locator: ServerRequestLocator, reason: String): FileApprovalState {
        val request = requests[locator] ?: return this
        if (request.resolution != RequestResolutionState.PENDING) return this
        return update(
            request.copy(
                resolution = RequestResolutionState.RESOLVED,
                failureReason = reason,
            ),
        )
    }

    fun unknown(locator: ServerRequestLocator): FileApprovalState {
        val request = requests[locator] ?: return this
        if (request.resolution == RequestResolutionState.RESOLVED) return this
        return update(request.copy(resolution = RequestResolutionState.UNKNOWN))
    }

    fun resolved(
        hostId: String,
        appServerGeneration: Long,
        requestId: String,
        threadId: String,
    ): FileApprovalState {
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

    fun connectionLost(hostId: String, appServerGeneration: Long): FileApprovalState =
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

    fun turnSettled(thread: CodexThreadLocator, turnId: String): FileApprovalState =
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

    fun unresolved(hostId: String? = null): List<FileApprovalRequest> =
        requests.values.filter {
            it.resolution != RequestResolutionState.RESOLVED &&
                (hostId == null || it.locator.hostId == hostId)
        }

    fun unresolvedThreads(): Set<CodexThreadLocator> =
        unresolved().mapTo(mutableSetOf(), FileApprovalRequest::thread)

    private fun update(request: FileApprovalRequest): FileApprovalState =
        copy(requests = requests + (request.locator to request))

    private companion object {
        val ACTIVE_STATES = setOf(RequestResolutionState.PENDING, RequestResolutionState.RESPONDING)
    }
}
