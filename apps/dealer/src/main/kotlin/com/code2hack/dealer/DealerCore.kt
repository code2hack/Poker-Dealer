package com.code2hack.dealer

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.CommandApprovalDecision
import com.code2hack.pokerdealer.domain.ComposerAction
import com.code2hack.pokerdealer.domain.ComposerDraft
import com.code2hack.pokerdealer.domain.ControlSurface
import com.code2hack.pokerdealer.domain.DiscoveredThread
import com.code2hack.pokerdealer.domain.FileApprovalDecision
import com.code2hack.pokerdealer.domain.ServerRequestLocator
import com.code2hack.pokerdealer.domain.ThreadAttachmentState
import com.code2hack.pokerdealer.domain.ThreadStartSelection
import com.code2hack.pokerdealer.domain.ThreadWorkState
import com.code2hack.pokerdealer.domain.UserInputOutcome
import com.code2hack.pokerdealer.protocol.appserver.AppServerNotification
import com.code2hack.pokerdealer.protocol.appserver.AppServerStructuredCardProjection
import com.code2hack.pokerdealer.protocol.appserver.AppServerThreadProjection
import com.code2hack.pokerdealer.protocol.appserver.CommandApprovalProtocol
import com.code2hack.pokerdealer.protocol.appserver.HostSessionManager
import com.code2hack.pokerdealer.protocol.appserver.HostThreadDiscovery
import com.code2hack.pokerdealer.protocol.appserver.RetainedCardStore
import com.code2hack.pokerdealer.protocol.appserver.ThreadDiscoveryLocalState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Retained Dealer ↔ Codex orchestration core.
 *
 * This class intentionally has no Poker transport, CXR SDK, Compose, NSD, PAKE, or Poker socket
 * dependency. Codex app-server remains the only backend.
 */
internal class DealerCore(
    hostSessions: HostSessionManager,
    private val threadStore: DealerThreadStateStore,
    private val recoveryStore: DealerRecoveryStateStore,
    private val retainedCardStore: RetainedCardStore,
    private val scope: CoroutineScope,
    private val pokerProjectionPort: PokerProjectionPort = NoOpPokerProjectionPort,
) {
    private val mutableState = MutableStateFlow(DealerCoreState())
    val state: StateFlow<DealerCoreState> = mutableState.asStateFlow()

    private val attachmentMutex = Mutex()
    private val requestJobs = mutableMapOf<String, Job>()
    private val notificationJobs = mutableMapOf<String, Job>()
    private var hostStateJob: Job? = null
    private var pokerProjectionJob: Job? = null
    private var started = false
    private lateinit var requests: DealerRequestCoordinator

    private val connections = CodexConnectionCoordinator(
        sessions = hostSessions,
        scope = scope,
        onConnected = ::onHostConnected,
        onDisconnected = ::onHostDisconnected,
    )
    private val mutations = DealerMutationCoordinator(
        state = mutableState,
        store = threadStore,
        appServer = connections::appServer,
    )

    suspend fun start() {
        check(!started) { "Dealer core is already started" }
        started = true
        val restoreErrors = mutableListOf<String>()
        val (attachments, actions) = try {
            threadStore.read() to threadStore.readActions()
        } catch (failure: Throwable) {
            restoreErrors += "Unable to restore Dealer thread state: ${failure.message}"
            emptySet<CodexThreadLocator>() to com.code2hack.pokerdealer.domain.ThreadActionState()
        }
        val recovered = recoveryStore.read()
        restoreErrors += recovered.errors
        val cards = attachments.flatMap { locator ->
            runCatching { retainedCardStore.read(locator) }
                .onFailure { restoreErrors += "Unable to restore retained cards: ${it.message}" }
                .getOrDefault(emptyList())
        }
        mutableState.value = DealerCoreState().restoreAfterProcessDeath(
            attachments = attachments,
            actions = actions,
            retainedCards = cards,
            projection = recovered.projection,
            pendingRequests = recovered.pendingRequests,
            restoreError = restoreErrors.takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
        requests = DealerRequestCoordinator(
            state = mutableState,
            recoveryStore = recoveryStore,
            connections = connections,
            scope = scope,
            rereadThread = ::readThread,
            pendingRequestsWritable = recovered.pendingRequestsWritable,
        )
        hostStateJob = scope.launch {
            connections.state.collect { sessions ->
                mutableState.update { it.copy(hostSessions = sessions) }
            }
        }
        pokerProjectionJob = scope.launch {
            var previous: PokerProjectionSnapshot? = null
            state.collect { current ->
                val projection = current.toPokerProjectionSnapshot()
                if (projection != previous) {
                    previous = projection
                    runCatching { pokerProjectionPort.publish(projection) }
                        .onFailure { setError("Poker projection unavailable: ${it.message}") }
                }
            }
        }
        connections.start()
    }

    suspend fun close() {
        requestJobs.values.forEach(Job::cancel)
        notificationJobs.values.forEach(Job::cancel)
        requestJobs.clear()
        notificationJobs.clear()
        connections.close()
        hostStateJob?.cancel()
        hostStateJob = null
        pokerProjectionJob?.cancel()
        pokerProjectionJob = null
        started = false
    }

    suspend fun setHostEnabled(hostId: String, enabled: Boolean) {
        requireStarted()
        connections.setEnabled(hostId, enabled)
    }

    suspend fun refreshThreads(hostId: String) {
        requireStarted()
        val session = connections.appServer(hostId) ?: return setError("Connect $hostId before refreshing")
        try {
            val discovered = HostThreadDiscovery(session).discover(hostId) { locator ->
                val current = mutableState.value
                ThreadDiscoveryLocalState(
                    attached = locator in current.threadAttachments.attached,
                    unreadCount = current.threads[locator]?.unreadCount ?: 0,
                    intendedControlSurface = if (current.threadAttachments.hasDealerClaim(locator)) {
                        ControlSurface.DEALER
                    } else {
                        ControlSurface.NONE
                    },
                )
            }
            mutableState.update { it.withDiscoveredThreads(hostId, discovered).copy(error = null) }
            persistProjectionBestEffort()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            setError(failure.message ?: failure::class.java.simpleName)
        }
    }

    suspend fun attachThread(locator: CodexThreadLocator, takeControl: Boolean = false): Boolean {
        requireStarted()
        val session = connections.appServer(locator.hostId) ?: return fail("Connect ${locator.hostId} before attaching")
        return attachmentMutex.withLock {
            if (locator in mutableState.value.threadAttachments.attached) {
                if (takeControl) takeControl(locator) else true
            } else {
                try {
                    session.threadResume(locator.threadId)
                    try {
                        threadStore.attach(locator)
                    } catch (failure: Throwable) {
                        runCatching { session.threadUnsubscribe(locator.threadId) }
                        throw failure
                    }
                    mutableState.update { current ->
                        var attachments = current.threadAttachments.attach(locator)
                        if (takeControl) attachments = attachments.claim(locator)
                        current.copy(
                            threadAttachments = attachments,
                            threads = current.threads[locator]?.let { thread ->
                                current.threads + (
                                    locator to thread.copy(
                                        attached = true,
                                        intendedControlSurface = if (takeControl) ControlSurface.DEALER else ControlSurface.NONE,
                                    )
                                )
                            } ?: current.threads,
                            error = null,
                        )
                    }
                    readThread(locator)
                    persistProjectionBestEffort()
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    fail(failure.message ?: failure::class.java.simpleName)
                }
            }
        }
    }

    suspend fun detachThread(locator: CodexThreadLocator): Boolean {
        requireStarted()
        return attachmentMutex.withLock {
            val before = mutableState.value
            if (locator !in before.threadAttachments.attached) return@withLock true
            if (locator in before.blockingRequestThreads) {
                return@withLock fail("Resolve, cancel, or interrupt the pending request before detaching")
            }
            val session = connections.appServer(locator.hostId)
            try {
                session?.threadUnsubscribe(locator.threadId)
                try {
                    threadStore.detach(locator)
                } catch (failure: Throwable) {
                    runCatching { session?.threadResume(locator.threadId) }
                    throw failure
                }
                mutableState.update { current ->
                    val detached = current.threadAttachments.detach(locator)
                    current.copy(
                        threadAttachments = detached,
                        threads = current.threads[locator]?.let { thread ->
                            current.threads + (
                                locator to thread.copy(attached = false, intendedControlSurface = ControlSurface.NONE)
                            )
                        } ?: current.threads,
                        error = null,
                    )
                }
                persistProjectionBestEffort()
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                fail(failure.message ?: failure::class.java.simpleName)
            }
        }
    }

    fun takeControl(locator: CodexThreadLocator): Boolean {
        val before = mutableState.value
        if (locator !in before.threadAttachments.attached) return fail("Attach the thread before taking control")
        mutableState.update { current ->
            val attachments = current.threadAttachments.claim(locator)
            current.copy(
                threadAttachments = attachments,
                threads = current.threads[locator]?.let { thread ->
                    current.threads + (locator to thread.copy(intendedControlSurface = ControlSurface.DEALER))
                } ?: current.threads,
                error = null,
            )
        }
        return true
    }

    fun yieldControl(locator: CodexThreadLocator): Boolean {
        val before = mutableState.value
        if (!before.threadAttachments.hasDealerClaim(locator)) return false
        mutableState.update { current ->
            current.copy(
                threadAttachments = current.threadAttachments.release(locator),
                threads = current.threads[locator]?.let { thread ->
                    current.threads + (locator to thread.copy(intendedControlSurface = ControlSurface.NONE))
                } ?: current.threads,
            )
        }
        return true
    }

    suspend fun editDraft(locator: CodexThreadLocator, draft: ComposerDraft): Boolean {
        requireStarted()
        val next = mutableState.value.threadActions.editComposerDraft(locator, draft)
        return try {
            threadStore.writeDraft(locator, next.composerDraft(locator))
            mutableState.update { it.copy(threadActions = next, error = null) }
            true
        } catch (failure: Throwable) {
            fail("Unable to retain draft: ${failure.message}")
        }
    }

    suspend fun submitDraft(
        locator: CodexThreadLocator,
        expectedDraftRevision: Long? = null,
        expectedAction: ComposerAction? = null,
        expectedTurnId: String? = null,
    ): DealerMutationOutcome {
        requireStarted()
        val result = mutations.submitDraft(locator, expectedDraftRevision, expectedAction, expectedTurnId)
        persistProjectionBestEffort()
        return result
    }

    suspend fun interrupt(
        locator: CodexThreadLocator,
        expectedTurnId: String? = null,
    ): DealerMutationOutcome {
        requireStarted()
        val result = mutations.interrupt(locator, expectedTurnId)
        persistProjectionBestEffort()
        return result
    }

    suspend fun resolveCommandApproval(locator: ServerRequestLocator, decision: CommandApprovalDecision): DealerMutationOutcome {
        requireStarted()
        return requests.resolveCommand(locator, decision)
    }

    suspend fun resolveFileApproval(locator: ServerRequestLocator, decision: FileApprovalDecision): DealerMutationOutcome {
        requireStarted()
        return requests.resolveFile(locator, decision)
    }

    suspend fun resolveUserInput(
        locator: ServerRequestLocator,
        answers: Map<String, List<String>>,
        outcome: UserInputOutcome = UserInputOutcome.ANSWERED,
    ): DealerMutationOutcome {
        requireStarted()
        return requests.resolveUserInput(locator, answers, outcome)
    }

    suspend fun createThread(
        hostId: String,
        selection: ThreadStartSelection,
        forkFrom: CodexThreadLocator? = null,
    ): CodexThreadLocator? {
        requireStarted()
        require(forkFrom == null || forkFrom.hostId == hostId) { "Fork source must be on the same Codex host" }
        val session = connections.appServer(hostId) ?: return null.also { setError("Connect $hostId before creating") }
        return try {
            val response = forkFrom?.let { session.threadFork(it.threadId, selection) }
                ?: session.threadStart(selection)
            val thread = response["thread"] as? JsonObject
                ?: error("Thread operation response did not include a thread")
            require(AppServerThreadProjection.authoritativeState(response).workState == ThreadWorkState.READY) {
                "Thread operation did not return a READY thread"
            }
            val threadId = (thread["id"] as? JsonPrimitive)?.contentOrNull
                ?: error("Thread operation response did not include a thread ID")
            require(threadId != forkFrom?.threadId) { "thread/fork did not return a new thread" }
            val locator = CodexThreadLocator(hostId, threadId)
            attachmentMutex.withLock {
                try {
                    threadStore.attach(locator)
                    threadStore.writeReasoningEffort(locator, selection.reasoningEffort)
                } catch (failure: Throwable) {
                    runCatching { session.threadUnsubscribe(threadId) }
                    runCatching { threadStore.detach(locator) }
                    throw failure
                }
                mutableState.update { current ->
                    val attachments = current.threadAttachments.attach(locator).claim(locator)
                    current.copy(
                        threadAttachments = attachments,
                        threadActions = current.threadActions.setPendingReasoningEffort(locator, selection.reasoningEffort),
                        threads = current.threads + (
                            locator to DiscoveredThread(
                                locator = locator,
                                name = (thread["name"] as? JsonPrimitive)?.contentOrNull,
                                preview = (thread["preview"] as? JsonPrimitive)?.contentOrNull,
                                workingDirectory = selection.workingDirectory,
                                status = "idle",
                                workState = ThreadWorkState.READY,
                                attached = true,
                                intendedControlSurface = ControlSurface.DEALER,
                            )
                        ),
                        error = null,
                    )
                }
            }
            persistProjectionBestEffort()
            locator
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            setError(failure.message ?: failure::class.java.simpleName)
            null
        }
    }

    suspend fun renameThread(locator: CodexThreadLocator, name: String): Boolean =
        performThreadAction(locator, "renaming") { session ->
            session.threadNameSet(locator.threadId, name)
            mutableState.update { current ->
                current.copy(
                    threads = current.threads[locator]?.let { current.threads + (locator to it.copy(name = name)) }
                        ?: current.threads,
                    error = null,
                )
            }
        }

    suspend fun archiveThread(locator: CodexThreadLocator): Boolean =
        performThreadAction(locator, "archiving") { session ->
            session.threadArchive(locator.threadId)
            reconcileLifecycle("thread/archived", locator)
        }

    suspend fun unarchiveThread(locator: CodexThreadLocator): Boolean =
        performThreadAction(locator, "restoring") { session ->
            session.threadUnarchive(locator.threadId)
            mutableState.update { current ->
                current.copy(
                    threads = current.threads[locator]?.let {
                        current.threads + (locator to it.copy(archived = false, attached = false, intendedControlSurface = ControlSurface.NONE))
                    } ?: current.threads,
                    error = null,
                )
            }
        }

    suspend fun deleteThread(locator: CodexThreadLocator): Boolean =
        performThreadAction(locator, "deleting") { session ->
            session.threadDelete(locator.threadId)
            reconcileLifecycle("thread/deleted", locator)
        }

    suspend fun readThread(locator: CodexThreadLocator) {
        val session = connections.appServer(locator.hostId) ?: return
        try {
            val conversationId = "${locator.hostId}/${locator.threadId}"
            val response = session.threadRead(locator.threadId)
            val authoritative = AppServerThreadProjection.authoritativeState(response)
            val authoritativeCards = AppServerThreadProjection.cards(response, conversationId)
            val prior = mutableState.value.cards
                .filter { it.conversationId == conversationId }
                .associateBy(Card::id)
            val reconciled = authoritativeCards.map { card ->
                prior[card.id]?.let {
                    card.copy(
                        sequence = it.sequence,
                        revision = it.revision + 1,
                        createdAtMs = it.createdAtMs,
                    )
                } ?: card
            }
            val localOnly = prior.values.filter { card ->
                card.id !in reconciled.mapTo(mutableSetOf(), Card::id) && card.delivery != null
            }
            val finalCards = localOnly + reconciled
            var clearInput = false
            var clearInterrupt = false
            mutableState.update { current ->
                val pending = current.threadActions.pendingInputs[locator]
                val delivered = pending != null &&
                    AppServerThreadProjection.countUserClientId(response, pending.clientId) == 1
                clearInput = delivered
                clearInterrupt = current.threadActions.pendingInterrupts[locator] != null &&
                    current.threadActions.pendingInterrupts[locator] != authoritative.activeTurnId
                val actions = (if (delivered) {
                    current.threadActions.inputAccepted(locator, checkNotNull(pending).clientId)
                } else {
                    current.threadActions
                }).reconcileInterrupt(locator, authoritative.activeTurnId)
                current.copy(
                    threadActions = actions,
                    cards = current.cards.filterNot { it.conversationId == conversationId } + finalCards,
                    threads = current.threads[locator]?.let { row ->
                        current.threads + (
                            locator to row.copy(
                                status = when (authoritative.workState) {
                                    ThreadWorkState.BUSY, ThreadWorkState.ATTENTION_REQUIRED -> "active"
                                    ThreadWorkState.READY -> "idle"
                                    null -> row.status
                                },
                                workState = authoritative.workState,
                                activeTurnId = authoritative.activeTurnId,
                            )
                        )
                    } ?: current.threads,
                    error = null,
                ).withBlockingRequests()
            }
            if (locator in mutableState.value.threadAttachments.attached) {
                runCatching { retainedCardStore.write(locator, finalCards) }
                    .onFailure { setError("Unable to retain thread cards: ${it.message}") }
            }
            if (clearInput) {
                val draft = mutableState.value.threadActions.composerDraft(locator)
                threadStore.writeDraft(locator, draft)
                threadStore.writePendingInput(locator, null)
                threadStore.writeReasoningEffort(locator, mutableState.value.threadActions.pendingReasoningEfforts[locator])
            }
            if (clearInterrupt) threadStore.writePendingInterrupt(locator, null)
            persistProjectionBestEffort()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            setError(failure.message ?: failure::class.java.simpleName)
        }
    }

    private suspend fun onHostConnected(hostId: String, generation: Long) {
        observeServerRequests(hostId, generation)
        observeNotifications(hostId, generation)
        mutableState.value.threadAttachments.attached
            .filter { it.hostId == hostId }
            .forEach { locator ->
                runCatching { connections.appServer(hostId)?.threadResume(locator.threadId) }
                    .onSuccess { readThread(locator) }
                    .onFailure { setError("Unable to restore ${locator.threadId}: ${it.message}") }
            }
        refreshThreads(hostId)
    }

    private suspend fun onHostDisconnected(hostId: String, generation: Long) {
        requestJobs.remove(hostId)?.cancel()
        notificationJobs.remove(hostId)?.cancel()
        if (::requests.isInitialized) requests.connectionLost(hostId, generation)
    }

    private fun observeServerRequests(hostId: String, generation: Long) {
        val session = connections.appServer(hostId) ?: return
        requestJobs.remove(hostId)?.cancel()
        requestJobs[hostId] = scope.launch {
            while (connections.isCurrent(hostId, generation)) {
                val wire = session.receiveServerRequest() ?: return@launch
                requests.receive(hostId, generation, wire)
            }
        }
    }

    private fun observeNotifications(hostId: String, generation: Long) {
        val session = connections.appServer(hostId) ?: return
        notificationJobs.remove(hostId)?.cancel()
        notificationJobs[hostId] = scope.launch {
            while (connections.isCurrent(hostId, generation)) {
                val notification = session.receiveNotification() ?: return@launch
                handleNotification(hostId, generation, notification)
            }
        }
    }

    private suspend fun handleNotification(
        hostId: String,
        generation: Long,
        notification: AppServerNotification,
    ) {
        CommandApprovalProtocol.resolved(notification)?.let { resolved ->
            requests.serverRequestResolved(hostId, generation, resolved.requestId, resolved.threadId)
            return
        }
        val params = notification.params as? JsonObject ?: return
        val turn = params["turn"] as? JsonObject
        val threadId = (params["threadId"] as? JsonPrimitive)?.contentOrNull
            ?: (turn?.get("threadId") as? JsonPrimitive)?.contentOrNull
            ?: return
        val locator = CodexThreadLocator(hostId, threadId)
        val turnId = (turn?.get("id") as? JsonPrimitive)?.contentOrNull
            ?: (params["turnId"] as? JsonPrimitive)?.contentOrNull

        if (notification.method in STRUCTURED_CARD_NOTIFICATIONS) {
            val conversationId = "${locator.hostId}/${locator.threadId}"
            val projected = AppServerStructuredCardProjection.apply(
                current = mutableState.value.cards.filter { it.conversationId == conversationId },
                notification = notification,
                conversationId = conversationId,
            )
            mutableState.update { current ->
                current.copy(
                    cards = current.cards.filterNot { it.conversationId == conversationId } + projected.cards,
                )
            }
            if (locator in mutableState.value.threadAttachments.attached) {
                runCatching { retainedCardStore.write(locator, projected.cards) }
                    .onFailure { setError("Unable to retain thread cards: ${it.message}") }
            }
            if (projected.requiresReread) {
                scope.launch {
                    delay(INCOMPLETE_CARD_REREAD_DELAY_MS)
                    readThread(locator)
                }
            }
        }

        when (notification.method) {
            "thread/archived", "thread/unarchived", "thread/deleted" -> reconcileLifecycle(notification.method, locator)
            "thread/name/updated" -> {
                val name = (params["threadName"] as? JsonPrimitive)?.contentOrNull
                mutableState.update { current ->
                    current.copy(
                        threads = current.threads[locator]?.let { current.threads + (locator to it.copy(name = name)) }
                            ?: current.threads,
                    )
                }
                persistProjectionBestEffort()
            }
            "turn/started" -> {
                mutableState.update { current ->
                    val dealerOriginated = turnId != null && current.threads[locator]?.activeTurnId == turnId
                    val attachments = current.threadAttachments.externalTurnStarted(locator, dealerOriginated)
                    current.copy(
                        threadAttachments = attachments,
                        threads = current.threads[locator]?.let { row ->
                            current.threads + (
                                locator to row.copy(
                                    status = "active",
                                    workState = ThreadWorkState.BUSY,
                                    activeTurnId = turnId ?: row.activeTurnId,
                                    intendedControlSurface = if (attachments.hasDealerClaim(locator)) {
                                        ControlSurface.DEALER
                                    } else {
                                        ControlSurface.NONE
                                    },
                                )
                            )
                        } ?: current.threads,
                    ).withBlockingRequests()
                }
                persistProjectionBestEffort()
            }
            "turn/completed" -> if (turnId != null) {
                requests.turnSettled(locator, turnId)
                mutableState.update { current ->
                    current.copy(
                        threadActions = current.threadActions.reconcileInterrupt(locator, null),
                        threads = current.threads[locator]?.let { row ->
                            current.threads + (
                                locator to row.copy(status = "idle", workState = ThreadWorkState.READY, activeTurnId = null)
                            )
                        } ?: current.threads,
                    ).withBlockingRequests()
                }
                runCatching { threadStore.writePendingInterrupt(locator, null) }
                readThread(locator)
            }
        }
    }

    private suspend fun reconcileLifecycle(method: String, locator: CodexThreadLocator) {
        attachmentMutex.withLock {
            val persistenceFailure = when (method) {
                "thread/deleted" -> runCatching {
                    threadStore.purge(locator)
                    retainedCardStore.delete(locator)
                }.exceptionOrNull()
                else -> runCatching { threadStore.detach(locator) }.exceptionOrNull()
            }
            mutableState.update { current ->
                val attachments = when (method) {
                    "thread/archived", "thread/unarchived" -> current.threadAttachments.detach(
                        locator,
                        hasKnownBlockingRequest = false,
                    )
                    "thread/deleted" -> current.threadAttachments.detach(locator, hasKnownBlockingRequest = false)
                    else -> current.threadAttachments
                }
                val threads = when (method) {
                    "thread/archived" -> current.threads[locator]?.let {
                        current.threads + (locator to it.copy(archived = true, attached = false, intendedControlSurface = ControlSurface.NONE))
                    } ?: current.threads
                    "thread/unarchived" -> current.threads[locator]?.let {
                        current.threads + (locator to it.copy(archived = false, attached = false, intendedControlSurface = ControlSurface.NONE))
                    } ?: current.threads
                    "thread/deleted" -> current.threads - locator
                    else -> current.threads
                }
                val conversationId = "${locator.hostId}/${locator.threadId}"
                current.copy(
                    threadAttachments = attachments,
                    threadActions = if (method == "thread/deleted") current.threadActions.purge(setOf(locator)) else current.threadActions,
                    threads = threads,
                    cards = if (method == "thread/deleted") current.cards.filterNot { it.conversationId == conversationId } else current.cards,
                    error = persistenceFailure?.let { "Host confirmed ${method.substringAfter('/')} but local cleanup failed: ${it.message}" },
                )
            }
            persistProjectionBestEffort()
        }
    }

    private suspend fun persistProjectionBestEffort() {
        runCatching { recoveryStore.writeProjection(mutableState.value.durableProjection()) }
            .onFailure { setError("Unable to retain cached thread projection: ${it.message}") }
    }

    private suspend fun performThreadAction(
        locator: CodexThreadLocator,
        verb: String,
        action: suspend (com.code2hack.pokerdealer.protocol.appserver.CodexAppServerSession) -> Unit,
    ): Boolean {
        requireStarted()
        val session = connections.appServer(locator.hostId)
            ?: return fail("Connect ${locator.hostId} before $verb")
        return try {
            action(session)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            fail(failure.message ?: failure::class.java.simpleName)
        }
    }

    private fun requireStarted() = check(started) { "Dealer core has not been started" }

    private fun setError(message: String) {
        mutableState.update { it.copy(error = message) }
    }

    private fun fail(message: String): Boolean {
        setError(message)
        return false
    }

    private companion object {
        const val INCOMPLETE_CARD_REREAD_DELAY_MS = 500L
        val STRUCTURED_CARD_NOTIFICATIONS = setOf(
            "item/started",
            "item/completed",
            "item/agentMessage/delta",
            "item/commandExecution/outputDelta",
            "item/fileChange/outputDelta",
            "item/fileChange/patchUpdated",
            "turn/diff/updated",
            "turn/completed",
        )
    }
}
