package com.code2hack.pokerdealer.domain

data class UserInputOption(
    val label: String,
    val description: String,
)

data class UserInputQuestion(
    val id: String,
    val header: String,
    val question: String,
    val options: List<UserInputOption>?,
    val isOther: Boolean,
    val isSecret: Boolean,
)

enum class UserInputOutcome {
    ANSWERED,
    NO_ANSWER,
    AUTO_RESOLVED,
}

data class UserInputRequest(
    val locator: ServerRequestLocator,
    val thread: CodexThreadLocator,
    val turnId: String,
    val itemId: String,
    val questions: List<UserInputQuestion>,
    val autoResolutionMs: Long?,
    val receivedAtMs: Long,
    val fingerprint: String,
    val resolution: RequestResolutionState = RequestResolutionState.PENDING,
    val outcome: UserInputOutcome? = null,
    val resolvedElsewhere: Boolean = false,
) {
    val deadlineAtMs: Long?
        get() = autoResolutionMs?.let {
            if (receivedAtMs > Long.MAX_VALUE - it) Long.MAX_VALUE else receivedAtMs + it
        }
}

data class UserInputRequestState(
    val requests: Map<ServerRequestLocator, UserInputRequest> = emptyMap(),
) {
    fun receive(
        request: UserInputRequest,
        sameIdReissueQualified: Boolean,
    ): UserInputRequestState {
        val current = requests[request.locator]
        require(current == null || current.fingerprint == request.fingerprint) {
            "Server request identity was reused with different question scope"
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
                    outcome = null,
                )
            ),
        )
    }

    fun begin(
        locator: ServerRequestLocator,
        outcome: UserInputOutcome,
    ): UserInputRequestState {
        val request = requests[locator] ?: return this
        if (request.resolution != RequestResolutionState.PENDING) return this
        return update(request.copy(resolution = RequestResolutionState.RESPONDING, outcome = outcome))
    }

    fun unknown(locator: ServerRequestLocator): UserInputRequestState {
        val request = requests[locator] ?: return this
        if (request.resolution == RequestResolutionState.RESOLVED) return this
        return update(request.copy(resolution = RequestResolutionState.UNKNOWN))
    }

    fun resolved(
        hostId: String,
        appServerGeneration: Long,
        requestId: String,
        threadId: String,
    ): UserInputRequestState {
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
                outcome = request.outcome.takeIf {
                    request.resolution == RequestResolutionState.RESPONDING
                },
            ),
        )
    }

    fun connectionLost(hostId: String, appServerGeneration: Long): UserInputRequestState =
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

    fun turnSettled(thread: CodexThreadLocator, turnId: String): UserInputRequestState =
        copy(
            requests = requests.mapValues { (_, request) ->
                if (request.thread == thread &&
                    request.turnId == turnId &&
                    request.resolution != RequestResolutionState.RESOLVED
                ) {
                    request.copy(
                        resolution = RequestResolutionState.RESOLVED,
                        resolvedElsewhere = request.resolution != RequestResolutionState.RESPONDING,
                        outcome = request.outcome.takeIf {
                            request.resolution == RequestResolutionState.RESPONDING
                        },
                    )
                } else {
                    request
                }
            },
        )

    fun unresolved(hostId: String? = null): List<UserInputRequest> =
        requests.values.filter {
            it.resolution != RequestResolutionState.RESOLVED &&
                (hostId == null || it.locator.hostId == hostId)
        }

    fun unresolvedThreads(): Set<CodexThreadLocator> =
        unresolved().mapTo(mutableSetOf(), UserInputRequest::thread)

    private fun update(request: UserInputRequest): UserInputRequestState =
        copy(requests = requests + (request.locator to request))

    private companion object {
        val ACTIVE_STATES = setOf(RequestResolutionState.PENDING, RequestResolutionState.RESPONDING)
    }
}
