package com.code2hack.dealer

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardSource
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.CommandApprovalDecision
import com.code2hack.pokerdealer.domain.FileApprovalDecision
import com.code2hack.pokerdealer.domain.RequestResolutionState
import com.code2hack.pokerdealer.domain.ServerRequestLocator
import com.code2hack.pokerdealer.domain.UserInputOutcome
import com.code2hack.pokerdealer.protocol.appserver.AppServerRequest
import com.code2hack.pokerdealer.protocol.appserver.COMMAND_APPROVAL_METHOD
import com.code2hack.pokerdealer.protocol.appserver.CommandApprovalParseResult
import com.code2hack.pokerdealer.protocol.appserver.CommandApprovalProtocol
import com.code2hack.pokerdealer.protocol.appserver.FILE_APPROVAL_METHOD
import com.code2hack.pokerdealer.protocol.appserver.FileApprovalParseResult
import com.code2hack.pokerdealer.protocol.appserver.FileApprovalProtocol
import com.code2hack.pokerdealer.protocol.appserver.USER_INPUT_REQUEST_METHOD
import com.code2hack.pokerdealer.protocol.appserver.UserInputParseResult
import com.code2hack.pokerdealer.protocol.appserver.UserInputProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Owns structured Codex server requests and exact app-server-generation fencing. */
internal class DealerRequestCoordinator(
    private val state: MutableStateFlow<DealerCoreState>,
    private val recoveryStore: DealerRecoveryStateStore,
    private val connections: CodexConnectionCoordinator,
    private val scope: CoroutineScope,
    private val rereadThread: suspend (CodexThreadLocator) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
    pendingRequestsWritable: Boolean = true,
) {
    private val wireCommands = mutableMapOf<ServerRequestLocator, AppServerRequest>()
    private val wireFiles = mutableMapOf<ServerRequestLocator, AppServerRequest>()
    private val wireUserInputs = mutableMapOf<ServerRequestLocator, AppServerRequest>()
    private val timeoutJobs = mutableMapOf<ServerRequestLocator, Job>()
    private val persistenceMutex = Mutex()
    private var pendingRequestsWritable = pendingRequestsWritable

    suspend fun receive(hostId: String, generation: Long, wire: AppServerRequest) {
        val session = connections.appServer(hostId) ?: return
        if (!connections.isCurrent(hostId, generation)) return
        when (wire.method) {
            COMMAND_APPROVAL_METHOD -> when (val parsed = CommandApprovalProtocol.parse(hostId, generation, wire)) {
                is CommandApprovalParseResult.Accepted -> {
                    try {
                        val reissue = state.value.commandApprovals.requests.values.lastOrNull { prior ->
                            prior.locator.hostId == hostId &&
                                prior.locator.requestId == parsed.request.locator.requestId &&
                                prior.locator.appServerGeneration != generation &&
                                prior.fingerprint == parsed.request.fingerprint &&
                                prior.resolution == RequestResolutionState.UNKNOWN
                        }
                        state.update {
                            it.copy(
                                commandApprovals = it.commandApprovals.receive(
                                    parsed.request,
                                    sameIdReissueQualified = reissue != null,
                                ),
                                error = null,
                            ).withBlockingRequests()
                        }
                        reissue?.let { wireCommands.remove(it.locator) }
                        wireCommands[parsed.request.locator] = wire
                        persistPendingRequestsBestEffort()
                    } catch (failure: IllegalArgumentException) {
                        session.reject(wire, failure.message ?: "Command approval identity conflict")
                    }
                }
                is CommandApprovalParseResult.Rejected -> session.reject(wire, parsed.reason)
            }

            FILE_APPROVAL_METHOD -> receiveFileApproval(hostId, generation, wire)

            USER_INPUT_REQUEST_METHOD -> when (
                val parsed = UserInputProtocol.parse(hostId, generation, wire, nowMs())
            ) {
                is UserInputParseResult.Accepted -> {
                    try {
                        val reissue = state.value.userInputRequests.requests.values.lastOrNull { prior ->
                            prior.locator.hostId == hostId &&
                                prior.locator.requestId == parsed.request.locator.requestId &&
                                prior.locator.appServerGeneration != generation &&
                                prior.fingerprint == parsed.request.fingerprint &&
                                prior.resolution == RequestResolutionState.UNKNOWN
                        }
                        state.update {
                            it.copy(
                                userInputRequests = it.userInputRequests.receive(
                                    parsed.request,
                                    sameIdReissueQualified = reissue != null,
                                ),
                                error = null,
                            ).withBlockingRequests()
                        }
                        reissue?.let { previous ->
                            wireUserInputs.remove(previous.locator)
                            timeoutJobs.remove(previous.locator)?.cancel()
                        }
                        wireUserInputs[parsed.request.locator] = wire
                        scheduleTimeout(parsed.request.locator)
                        persistPendingRequestsBestEffort()
                    } catch (failure: IllegalArgumentException) {
                        session.reject(wire, failure.message ?: "User-input request identity conflict")
                    }
                }
                is UserInputParseResult.Rejected -> session.reject(wire, parsed.reason)
            }

            else -> session.reject(wire, "Unsupported server request")
        }
    }

    suspend fun resolveCommand(
        locator: ServerRequestLocator,
        decision: CommandApprovalDecision,
    ): DealerMutationOutcome {
        val before = state.value
        val request = before.commandApprovals.requests[locator] ?: return DealerMutationOutcome.REJECTED
        if (!before.threadAttachments.hasDealerClaim(request.thread)) {
            return reject("Take control before resolving this request")
        }
        val session = connections.appServer(locator.hostId)
        val wire = wireCommands[locator]
        if (session == null || !connections.isCurrent(locator.hostId, locator.appServerGeneration) || wire == null) {
            state.update {
                it.copy(
                    commandApprovals = it.commandApprovals.unknown(locator),
                    error = "Command approval is no longer connected; no response was replayed",
                ).withBlockingRequests()
            }
            persistPendingRequestsBestEffort()
            return DealerMutationOutcome.UNKNOWN
        }
        val responding = try {
            before.commandApprovals.begin(locator, decision)
        } catch (failure: IllegalArgumentException) {
            return reject(failure.message)
        }
        if (responding == before.commandApprovals) return DealerMutationOutcome.REJECTED
        state.update { it.copy(commandApprovals = responding, error = null).withBlockingRequests() }
        if (!persistBeforeResponse { it.copy(commandApprovals = it.commandApprovals.unknown(locator)).withBlockingRequests() }) {
            return DealerMutationOutcome.UNKNOWN
        }
        return respondWithoutReplay(locator) {
            session.respond(wire, CommandApprovalProtocol.response(request, decision))
        }
    }

    suspend fun resolveFile(
        locator: ServerRequestLocator,
        decision: FileApprovalDecision,
    ): DealerMutationOutcome {
        val before = state.value
        val request = before.fileApprovals.requests[locator] ?: return DealerMutationOutcome.REJECTED
        if (!before.threadAttachments.hasDealerClaim(request.thread)) {
            return reject("Take control before resolving this request")
        }
        val session = connections.appServer(locator.hostId)
        val wire = wireFiles[locator]
        if (session == null || !connections.isCurrent(locator.hostId, locator.appServerGeneration) || wire == null) {
            state.update {
                it.copy(
                    fileApprovals = it.fileApprovals.unknown(locator),
                    error = "File approval is no longer connected; no response was replayed",
                ).withBlockingRequests()
            }
            persistPendingRequestsBestEffort()
            return DealerMutationOutcome.UNKNOWN
        }
        val responding = try {
            before.fileApprovals.begin(locator, decision)
        } catch (failure: IllegalArgumentException) {
            return reject(failure.message)
        }
        if (responding == before.fileApprovals) return DealerMutationOutcome.REJECTED
        state.update { it.copy(fileApprovals = responding, error = null).withBlockingRequests() }
        if (!persistBeforeResponse { it.copy(fileApprovals = it.fileApprovals.unknown(locator)).withBlockingRequests() }) {
            return DealerMutationOutcome.UNKNOWN
        }
        return respondWithoutReplay(locator) {
            session.respond(wire, FileApprovalProtocol.response(decision))
        }
    }

    suspend fun resolveUserInput(
        locator: ServerRequestLocator,
        answers: Map<String, List<String>>,
        outcome: UserInputOutcome = UserInputOutcome.ANSWERED,
        requireControl: Boolean = true,
    ): DealerMutationOutcome {
        val before = state.value
        val request = before.userInputRequests.requests[locator] ?: return DealerMutationOutcome.REJECTED
        if (requireControl && !before.threadAttachments.hasDealerClaim(request.thread)) {
            return reject("Take control before answering this request")
        }
        val response = try {
            UserInputProtocol.response(request, answers)
        } catch (failure: IllegalArgumentException) {
            return reject(failure.message)
        }
        val session = connections.appServer(locator.hostId)
        val wire = wireUserInputs[locator]
        if (session == null || !connections.isCurrent(locator.hostId, locator.appServerGeneration) || wire == null) {
            state.update {
                it.copy(
                    userInputRequests = it.userInputRequests.unknown(locator),
                    error = "User-input request is no longer connected; no response was replayed",
                ).withBlockingRequests()
            }
            persistPendingRequestsBestEffort()
            return DealerMutationOutcome.UNKNOWN
        }
        val responding = before.userInputRequests.begin(locator, outcome)
        if (responding == before.userInputRequests) return DealerMutationOutcome.REJECTED
        timeoutJobs.remove(locator)?.cancel()
        state.update { it.copy(userInputRequests = responding, error = null).withBlockingRequests() }
        if (!persistBeforeResponse { it.copy(userInputRequests = it.userInputRequests.unknown(locator)).withBlockingRequests() }) {
            return DealerMutationOutcome.UNKNOWN
        }
        return respondWithoutReplay(locator) { session.respond(wire, response) }
    }

    suspend fun serverRequestResolved(
        hostId: String,
        generation: Long,
        requestId: String,
        threadId: String,
    ) {
        wireCommands.keys.removeAll { it.hostId == hostId && it.appServerGeneration == generation && it.requestId == requestId }
        wireFiles.keys.removeAll { it.hostId == hostId && it.appServerGeneration == generation && it.requestId == requestId }
        wireUserInputs.keys
            .filter { it.hostId == hostId && it.appServerGeneration == generation && it.requestId == requestId }
            .forEach { locator ->
                wireUserInputs.remove(locator)
                timeoutJobs.remove(locator)?.cancel()
            }
        state.update {
            it.copy(
                commandApprovals = it.commandApprovals.resolved(hostId, generation, requestId, threadId),
                fileApprovals = it.fileApprovals.resolved(hostId, generation, requestId, threadId),
                userInputRequests = it.userInputRequests.resolved(hostId, generation, requestId, threadId),
            ).withBlockingRequests()
        }
        persistPendingRequestsBestEffort()
    }

    suspend fun turnSettled(locator: CodexThreadLocator, turnId: String) {
        timeoutJobs.keys
            .filter { key -> state.value.userInputRequests.requests[key]?.let { it.thread == locator && it.turnId == turnId } == true }
            .forEach { timeoutJobs.remove(it)?.cancel() }
        state.update {
            it.copy(
                commandApprovals = it.commandApprovals.turnSettled(locator, turnId),
                fileApprovals = it.fileApprovals.turnSettled(locator, turnId),
                userInputRequests = it.userInputRequests.turnSettled(locator, turnId),
            ).withBlockingRequests()
        }
        persistPendingRequestsBestEffort()
    }

    suspend fun connectionLost(hostId: String, generation: Long) {
        wireCommands.keys.removeAll { it.hostId == hostId && it.appServerGeneration == generation }
        wireFiles.keys.removeAll { it.hostId == hostId && it.appServerGeneration == generation }
        wireUserInputs.keys.removeAll { it.hostId == hostId && it.appServerGeneration == generation }
        timeoutJobs.keys
            .filter { it.hostId == hostId && it.appServerGeneration == generation }
            .forEach { timeoutJobs.remove(it)?.cancel() }
        state.update {
            it.copy(
                commandApprovals = it.commandApprovals.connectionLost(hostId, generation),
                fileApprovals = it.fileApprovals.connectionLost(hostId, generation),
                userInputRequests = it.userInputRequests.connectionLost(hostId, generation),
                threadAttachments = it.threadAttachments.releaseHost(hostId),
            ).withBlockingRequests()
        }
        persistPendingRequestsBestEffort()
    }

    private suspend fun receiveFileApproval(hostId: String, generation: Long, wire: AppServerRequest) {
        val session = connections.appServer(hostId) ?: return
        val parsed = FileApprovalProtocol.parse(hostId, generation, wire, state.value.fileReviewCard(hostId, wire))
        when (parsed) {
            is FileApprovalParseResult.Accepted -> installFileRequest(parsed.request, wire)
            is FileApprovalParseResult.Incomplete -> {
                installFileRequest(parsed.request, wire)
                scope.launch {
                    delay(INCOMPLETE_REVIEW_REREAD_DELAY_MS)
                    if (!connections.isCurrent(hostId, generation) || wireFiles[parsed.request.locator] != wire ||
                        state.value.fileApprovals.requests[parsed.request.locator]?.resolution != RequestResolutionState.PENDING
                    ) return@launch
                    rereadThread(parsed.request.thread)
                    val reparsed = FileApprovalProtocol.parse(
                        hostId,
                        generation,
                        wire,
                        state.value.fileReviewCard(hostId, wire),
                    )
                    if (reparsed is FileApprovalParseResult.Accepted) {
                        installFileRequest(reparsed.request, wire)
                    } else {
                        val reason = "File approval diff remains incomplete after authoritative reread"
                        try {
                            session.reject(wire, reason)
                            wireFiles.remove(parsed.request.locator)
                            state.update {
                                it.copy(
                                    fileApprovals = it.fileApprovals.failClosed(parsed.request.locator, reason),
                                ).withBlockingRequests()
                            }
                            persistPendingRequestsBestEffort()
                        } catch (failure: Throwable) {
                            state.update {
                                it.copy(fileApprovals = it.fileApprovals.unknown(parsed.request.locator))
                                    .withBlockingRequests()
                            }
                            persistPendingRequestsBestEffort()
                        }
                    }
                }
            }
            is FileApprovalParseResult.Rejected -> session.reject(wire, parsed.reason)
        }
    }

    private suspend fun installFileRequest(
        request: com.code2hack.pokerdealer.domain.FileApprovalRequest,
        wire: AppServerRequest,
    ) {
        try {
            val reissue = state.value.fileApprovals.requests.values.lastOrNull { prior ->
                prior.locator.hostId == request.locator.hostId &&
                    prior.locator.requestId == request.locator.requestId &&
                    prior.locator.appServerGeneration != request.locator.appServerGeneration &&
                    prior.fingerprint == request.fingerprint &&
                    prior.resolution == RequestResolutionState.UNKNOWN
            }
            state.update {
                it.copy(
                    fileApprovals = it.fileApprovals.receive(request, sameIdReissueQualified = reissue != null),
                    error = null,
                ).withBlockingRequests()
            }
            reissue?.let { wireFiles.remove(it.locator) }
            wireFiles[request.locator] = wire
            persistPendingRequestsBestEffort()
        } catch (failure: IllegalArgumentException) {
            connections.appServer(request.locator.hostId)?.reject(
                wire,
                failure.message ?: "File approval identity conflict",
            )
        }
    }

    private fun scheduleTimeout(locator: ServerRequestLocator) {
        val request = state.value.userInputRequests.requests[locator] ?: return
        val deadline = request.deadlineAtMs ?: return
        timeoutJobs.remove(locator)?.cancel()
        timeoutJobs[locator] = scope.launch {
            delay((deadline - nowMs()).coerceAtLeast(0L))
            resolveUserInput(
                locator,
                emptyMap(),
                UserInputOutcome.AUTO_RESOLVED,
                requireControl = false,
            )
        }
    }

    private suspend fun persistBeforeResponse(onFailure: (DealerCoreState) -> DealerCoreState): Boolean {
        if (!pendingRequestsWritable) {
            state.update { onFailure(it).copy(error = "Response was not sent because recovery storage is unavailable") }
            return false
        }
        return try {
            persistenceMutex.withLock { recoveryStore.writePendingRequests(state.value.pendingRequestSnapshot()) }
            true
        } catch (failure: Throwable) {
            pendingRequestsWritable = false
            state.update {
                onFailure(it).copy(error = "Response was not sent because recovery storage failed: ${failure.message}")
            }
            false
        }
    }

    private suspend fun persistPendingRequestsBestEffort() {
        if (!pendingRequestsWritable) return
        try {
            persistenceMutex.withLock { recoveryStore.writePendingRequests(state.value.pendingRequestSnapshot()) }
        } catch (failure: Throwable) {
            pendingRequestsWritable = false
            state.update { it.copy(error = "Unable to retain pending request state: ${failure.message}") }
        }
    }

    private suspend fun respondWithoutReplay(
        locator: ServerRequestLocator,
        respond: suspend () -> Unit,
    ): DealerMutationOutcome = try {
        respond()
        DealerMutationOutcome.ACCEPTED
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        state.update { current ->
            current.copy(
                commandApprovals = current.commandApprovals.unknown(locator),
                fileApprovals = current.fileApprovals.unknown(locator),
                userInputRequests = current.userInputRequests.unknown(locator),
                error = "${failure.message ?: failure::class.java.simpleName}; response was not replayed",
            ).withBlockingRequests()
        }
        persistPendingRequestsBestEffort()
        DealerMutationOutcome.UNKNOWN
    }

    private fun reject(message: String?): DealerMutationOutcome {
        state.update { it.copy(error = message ?: "Request resolution rejected") }
        return DealerMutationOutcome.REJECTED
    }

    private companion object {
        const val INCOMPLETE_REVIEW_REREAD_DELAY_MS = 500L
    }
}

private fun DealerCoreState.fileReviewCard(hostId: String, wire: AppServerRequest): Card? {
    val params = wire.params as? JsonObject ?: return null
    val threadId = (params["threadId"] as? JsonPrimitive)?.contentOrNull ?: return null
    val itemId = (params["itemId"] as? JsonPrimitive)?.contentOrNull ?: return null
    return cards.singleOrNull {
        it.conversationId == "$hostId/$threadId" &&
            it.id == itemId &&
            it.source == CardSource.CODEX_FILE_CHANGE
    }
}
