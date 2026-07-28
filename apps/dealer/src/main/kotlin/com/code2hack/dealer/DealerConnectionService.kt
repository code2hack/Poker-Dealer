package com.code2hack.dealer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.os.Binder
import android.os.IBinder
import com.code2hack.tailnet.embeddedtailnet.Engine
import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardRevisionStore
import com.code2hack.pokerdealer.domain.CardState
import com.code2hack.pokerdealer.domain.CodexHost
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ComposerAction
import com.code2hack.pokerdealer.domain.ControlSurface
import com.code2hack.pokerdealer.domain.DeliveryState
import com.code2hack.pokerdealer.domain.DiscoveredThread
import com.code2hack.pokerdealer.domain.HostConnectionRoute
import com.code2hack.pokerdealer.domain.InitialCodexHosts
import com.code2hack.pokerdealer.domain.RevisionApplication
import com.code2hack.pokerdealer.domain.ThreadAttachmentState
import com.code2hack.pokerdealer.domain.ThreadActionState
import com.code2hack.pokerdealer.domain.ThreadWorkState
import com.code2hack.pokerdealer.protocol.appserver.M1OneHostDealerSlice
import com.code2hack.pokerdealer.protocol.appserver.M1ConnectionPhase
import com.code2hack.pokerdealer.protocol.appserver.M1FailurePhase
import com.code2hack.pokerdealer.protocol.appserver.M1RecoveryUpdate
import com.code2hack.pokerdealer.protocol.appserver.M1TurnOutcome
import com.code2hack.pokerdealer.protocol.appserver.M1TurnRecoveryException
import com.code2hack.pokerdealer.protocol.appserver.M1TurnInput
import com.code2hack.pokerdealer.protocol.appserver.HostSessionConnectionConfig
import com.code2hack.pokerdealer.protocol.appserver.HostSessionManager
import com.code2hack.pokerdealer.protocol.appserver.HostSessionState
import com.code2hack.pokerdealer.protocol.appserver.HostSessionStatus
import com.code2hack.pokerdealer.protocol.appserver.HostThreadDiscovery
import com.code2hack.pokerdealer.protocol.appserver.InitializedHostSessionConnector
import com.code2hack.pokerdealer.protocol.appserver.AppServerThreadProjection
import com.code2hack.pokerdealer.protocol.appserver.JsonRpcRemoteException
import com.code2hack.pokerdealer.protocol.appserver.ThreadDiscoveryLocalState
import com.code2hack.pokerdealer.protocol.appserver.TermuxCommunityCodexDaemon
import com.code2hack.pokerdealer.protocol.appserver.UpstreamCodexDaemon
import com.code2hack.pokerdealer.protocol.appserver.m1FailurePhase
import com.code2hack.pokerdealer.protocol.host.HostIdentityException
import com.code2hack.pokerdealer.protocol.host.HostTcpDialer
import com.code2hack.pokerdealer.protocol.host.JschHostSshClient
import com.code2hack.pokerdealer.protocol.host.RouteEndpoint
import com.code2hack.pokerdealer.protocol.host.RouteConnectionException
import com.code2hack.pokerdealer.protocol.host.RouteCapability
import com.code2hack.pokerdealer.protocol.host.RouteDiagnostic
import com.code2hack.pokerdealer.protocol.host.SocketHostTcpDialer
import com.code2hack.pokerdealer.protocol.host.SshHostAuthentication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

class DealerConnectionService : Service() {
    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runJob: Job? = null
    private var tailnetJob: Job? = null
    private val tailnetEngine = Engine()
    private val hostSessionConfigs = mutableMapOf<String, HostSessionConnectionConfig>()
    private val hostSessionSecrets = mutableMapOf<String, StoredHostConnection>()
    private lateinit var hostSessions: HostSessionManager
    private lateinit var hostConnectionProfiles: DealerHostConnectionProfileStore
    private lateinit var threadAttachmentStore: DealerThreadAttachmentStore
    private val attachmentMutex = Mutex()
    private val draftMutex = Mutex()
    private val notificationJobs = mutableMapOf<String, Job>()
    private val dealerOriginatedTurns = mutableSetOf<Pair<CodexThreadLocator, String>>()
    private var connectedHostIds = emptySet<String>()

    private val mutableState: MutableStateFlow<DealerUiState>
        get() = DealerServiceState.mutableState
    val state: StateFlow<DealerUiState>
        get() = DealerServiceState.state

    inner class LocalBinder : Binder() {
        val service: DealerConnectionService
            get() = this@DealerConnectionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        hostConnectionProfiles = DealerHostConnectionProfileStore(this)
        threadAttachmentStore = DealerThreadAttachmentStore(this)
        hostSessions = HostSessionManager(
            hostIds = InitialCodexHosts.all.map(CodexHost::id).toSet(),
            intentStore = HostConnectionIntentDataStore(this),
            connector = InitializedHostSessionConnector { hostId ->
                synchronized(hostSessionConfigs) { hostSessionConfigs[hostId] }
                    ?: cacheHostSession(hostConnectionProfiles.load(hostId))
            },
            scope = scope,
        )
        scope.launch {
            val (restoredAttachments, restoredActions) = try {
                threadAttachmentStore.read() to threadAttachmentStore.readActions()
            } catch (failure: Throwable) {
                mutableState.update {
                    it.copy(error = "Unable to restore Dealer thread state: ${failure.message}")
                }
                emptySet<CodexThreadLocator>() to ThreadActionState()
            }
            mutableState.update {
                it.copy(
                    threadAttachments = ThreadAttachmentState(
                        attached = restoredAttachments,
                        dealerClaims = it.threadAttachments.dealerClaims.intersect(restoredAttachments),
                    ),
                    threadActions = restoredActions,
                )
            }
            hostSessions.start()
            hostSessions.state.collect { sessions ->
                mutableState.update { it.copy(hostSessions = sessions) }
                val connected = sessions.filterValues {
                    it.status == HostSessionStatus.CONNECTED
                }.keys
                (connectedHostIds - connected).forEach { hostId ->
                    notificationJobs.remove(hostId)?.cancel()
                }
                (connected - connectedHostIds).forEach { hostId ->
                    observeNotifications(hostId)
                    restoreAttachments(hostId)
                }
                connectedHostIds = connected
                if (sessions.values.any(HostSessionState::enabled)) {
                    ensureForeground()
                } else stopIfIdle()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelRun()
                stopEmbeddedTailnet()
            }
            ACTION_START_TAILNET -> startEmbeddedTailnet()
            ACTION_RESET_TAILNET -> resetEmbeddedTailnet()
            else -> ensureForeground()
        }
        return START_NOT_STICKY
    }

    @Synchronized
    fun runM1(
        config: DealerRunConfig,
        privateKey: ByteArray,
        knownHosts: ByteArray,
    ): Boolean {
        if (runJob != null ||
            mutableState.value.hostSessions[config.hostId]?.enabled == true ||
            !mutableState.value.hasDealerControl(config)
        ) {
            privateKey.fill(0)
            knownHosts.fill(0)
            if (runJob == null) {
                mutableState.update {
                    it.copy(
                        error = if (it.hostSessions[config.hostId]?.enabled == true) {
                            "Disconnect the long-lived host session before using the legacy one-shot turn"
                        } else {
                            "Take control of this host thread before sending"
                        },
                    )
                }
            }
            return false
        }
        ensureForeground()
        runJob = scope.launch {
            val host = InitialCodexHosts.all.firstOrNull { it.id == config.hostId }
                ?: error("Unsupported host ${config.hostId}")
            val conversationId = "${host.id}/${config.threadId}"
            val cards = CardRevisionStore().also { store ->
                mutableState.value.cards
                    .filter { it.conversationId == conversationId }
                    .forEach(store::apply)
            }
            val input = M1TurnInput(
                text = config.turnText,
                threadId = config.threadId,
                clientUserMessageId = UUID.randomUUID().toString(),
            )
            cards.apply(
                input.pendingUserCard(
                    conversationId = conversationId,
                    sequence = Long.MAX_VALUE,
                ),
            )
            mutableState.update {
                it.copy(
                    status = DealerRunState.CONNECTING,
                    hostId = host.id,
                    route = null,
                    threadId = config.threadId,
                    appServerVersion = null,
                    cards = cards.values(),
                    routeDiagnostics = emptyList(),
                    recovery = null,
                    error = null,
                )
            }
            try {
                val connection = createHostSessionConfig(
                    DealerHostConnectionConfig(
                        host.id,
                        config.lanHost,
                        config.tailnetHost,
                        config.sshUser,
                        config.loopbackSshPort,
                    ),
                    privateKey,
                    knownHosts,
                )
                val slice = M1OneHostDealerSlice(
                    host = host,
                    dialer = connection.dialer,
                    sshClient = connection.sshClient,
                    daemon = connection.daemon,
                )
                val result = slice.run(
                    input,
                    onCard = { card ->
                        if (cards.apply(card) != RevisionApplication.IGNORED_STALE) {
                            mutableState.update { it.copy(cards = cards.values()) }
                        }
                    },
                    onPhase = { phase ->
                        mutableState.update { it.withPhase(phase) }
                    },
                    onRoute = { route, diagnostics ->
                        mutableState.update { it.withActiveRoute(route, diagnostics) }
                    },
                    onRecovery = { recovery ->
                        mutableState.update { it.withRecovery(host, recovery) }
                    },
                )
                mutableState.update {
                    it.afterRun(
                        recovered = result.recoveredAfterDisconnect,
                        threadId = result.threadId,
                        appServerVersion = result.daemonVersions.appServerVersion,
                        routeDiagnostics = result.routeDiagnostics,
                    )
                }
            } catch (failure: CancellationException) {
                mutableState.update {
                    it.copy(status = DealerRunState.CANCELLED, route = null, recovery = null, error = null)
                }
                throw failure
            } catch (failure: M1TurnRecoveryException) {
                mutableState.update {
                    it.copy(
                        status = failure.outcome.toDealerRunState(),
                        route = null,
                        recovery = null,
                        error = failure.message,
                    )
                }
            } catch (failure: Throwable) {
                mutableState.update { state ->
                    state.copy(
                        status = DealerRunState.ERROR,
                        route = null,
                        routeDiagnostics = failure.routeDiagnostics().ifEmpty { state.routeDiagnostics },
                        recovery = DealerRecoveryUiState(
                            phase = failure.m1FailurePhase(),
                            action = host.recoveryAction(failure.m1FailurePhase()),
                        ),
                        error = failure.message ?: failure::class.java.simpleName,
                    )
                }
            } finally {
                privateKey.fill(0)
                knownHosts.fill(0)
                synchronized(this@DealerConnectionService) {
                    runJob = null
                }
                stopIfIdle()
            }
        }
        return true
    }

    @Synchronized
    fun cancelRun(): Boolean {
        val activeRun = runJob ?: return false
        activeRun.cancel(CancellationException("Cancelled by user"))
        return true
    }

    fun enableHost(
        config: DealerHostConnectionConfig,
        privateKey: ByteArray,
        knownHosts: ByteArray,
    ) {
        scope.launch {
            try {
                hostConnectionProfiles.save(config, privateKey, knownHosts)
                cacheHostSession(StoredHostConnection(config, privateKey, knownHosts))
                hostSessions.setEnabled(config.hostId, true)
            } catch (cancelled: CancellationException) {
                privateKey.fill(0)
                knownHosts.fill(0)
                throw cancelled
            } catch (failure: Throwable) {
                privateKey.fill(0)
                knownHosts.fill(0)
                mutableState.update { it.copy(error = failure.message ?: failure::class.java.simpleName) }
            }
        }
    }

    fun disableHost(hostId: String) {
        scope.launch {
            hostSessions.setEnabled(hostId, false)
            synchronized(hostSessionConfigs) {
                hostSessionConfigs.remove(hostId)
                hostSessionSecrets.remove(hostId)?.let {
                    it.privateKey.fill(0)
                    it.knownHosts.fill(0)
                }
            }
            mutableState.update {
                it.copy(
                    threadAttachments = it.threadAttachments.releaseHost(hostId),
                    threads = it.threads.mapValues { (locator, thread) ->
                        if (locator.hostId == hostId) {
                            thread.copy(intendedControlSurface = ControlSurface.NONE)
                        } else {
                            thread
                        }
                    },
                )
            }
        }
    }

    fun updateDraft(locator: CodexThreadLocator, text: String) {
        mutableState.update { it.copy(threadActions = it.threadActions.editDraft(locator, text)) }
        scope.launch {
            draftMutex.withLock {
                threadAttachmentStore.writeDraft(locator, text)
            }
        }
    }

    fun submitDraft(locator: CodexThreadLocator) {
        val state = mutableState.value
        val thread = state.threads[locator]
        val clientId = UUID.randomUUID().toString()
        val (actions, pending) = try {
            state.threadActions.beginInput(
                locator = locator,
                workState = thread?.workState,
                activeTurnId = thread?.activeTurnId,
                hasDealerClaim = state.threadAttachments.hasDealerClaim(locator),
                clientId = clientId,
            )
        } catch (failure: IllegalArgumentException) {
            mutableState.update { it.copy(error = failure.message) }
            return
        }
        val appServer = hostSessions.connectedSession(locator.hostId)?.appServer ?: run {
            mutableState.update { it.copy(error = "Connect ${locator.hostId} before sending") }
            return
        }
        val text = actions.drafts.getValue(locator)
        val conversationId = "${locator.hostId}/${locator.threadId}"
        val sequence = state.cards
            .filter { it.conversationId == conversationId }
            .maxOfOrNull(Card::sequence)
            ?.plus(1)
            ?: 1
        val pendingCard = M1TurnInput(text, locator.threadId, clientId)
            .pendingUserCard(conversationId, sequence)
        mutableState.update {
            it.copy(
                threadActions = actions,
                cards = it.cards.filterNot { card -> card.id == clientId } + pendingCard,
                error = null,
            )
        }
        scope.launch {
            try {
                draftMutex.withLock { threadAttachmentStore.writePendingInput(locator, pending) }
            } catch (failure: Throwable) {
                mutableState.update { current ->
                    val matching = current.threadActions.pendingInputs[locator]?.clientId == clientId
                    current.copy(
                        threadActions = if (matching) {
                            current.threadActions.inputRejected(locator, clientId)
                        } else {
                            current.threadActions
                        },
                        cards = current.cards.updateDelivery(clientId, DeliveryState.REJECTED),
                        error = "Unable to persist the pending input; nothing was sent: ${failure.message}",
                    )
                }
                return@launch
            }
            try {
                val response = when (pending.action) {
                    ComposerAction.START -> appServer.turnStart(locator.threadId, text, clientId)
                    ComposerAction.STEER -> appServer.turnSteer(
                        locator.threadId,
                        pending.expectedTurnId!!,
                        text,
                        clientId,
                    )
                    ComposerAction.BLOCKED -> error("Blocked input cannot be submitted")
                }
                val turnId = if (pending.action == ComposerAction.START) {
                    ((response["turn"] as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
                } else {
                    (response["turnId"] as? JsonPrimitive)?.contentOrNull
                } ?: error("${pending.action.label} response did not include a turn ID")
                require(pending.expectedTurnId == null || pending.expectedTurnId == turnId) {
                    "Steer response did not match the expected active turn"
                }
                dealerOriginatedTurns += locator to turnId
                var clearAcceptedDraft = false
                mutableState.update { current ->
                    val matching = current.threadActions.pendingInputs[locator]?.clientId == clientId
                    clearAcceptedDraft = matching
                    current.copy(
                        threadActions = if (matching) {
                            current.threadActions.inputAccepted(locator, clientId)
                        } else {
                            current.threadActions
                        },
                        cards = current.cards.updateDelivery(clientId, DeliveryState.ACCEPTED),
                        threads = current.threads[locator]?.let { row ->
                            current.threads + (
                                locator to row.copy(
                                    status = "active",
                                    workState = ThreadWorkState.BUSY,
                                    activeTurnId = turnId,
                                )
                            )
                        } ?: current.threads,
                        error = null,
                    )
                }
                if (clearAcceptedDraft) {
                    val retainedDraft = mutableState.value.threadActions.drafts[locator].orEmpty()
                    runCatching {
                        draftMutex.withLock {
                            threadAttachmentStore.writeDraft(locator, retainedDraft)
                            threadAttachmentStore.writePendingInput(locator, null)
                        }
                    }.onFailure { failure ->
                        mutableState.update {
                            it.copy(error = "Input was accepted, but local cleanup failed: ${failure.message}")
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rejected: JsonRpcRemoteException) {
                val cleanupFailure = runCatching {
                    draftMutex.withLock { threadAttachmentStore.writePendingInput(locator, null) }
                }.exceptionOrNull()
                mutableState.update { current ->
                    val matching = current.threadActions.pendingInputs[locator]?.clientId == clientId
                    current.copy(
                        threadActions = if (matching && cleanupFailure == null) {
                            current.threadActions.inputRejected(locator, clientId)
                        } else if (matching) {
                            current.threadActions.inputUncertain(locator, clientId)
                        } else {
                            current.threadActions
                        },
                        cards = current.cards.updateDelivery(clientId, DeliveryState.REJECTED),
                        error = cleanupFailure?.let {
                            "${rejected.message}; local action lock cleanup failed: ${it.message}"
                        } ?: rejected.message,
                    )
                }
                browseThread(locator)
            } catch (failure: Throwable) {
                mutableState.update { current ->
                    val matching = current.threadActions.pendingInputs[locator]?.clientId == clientId
                    current.copy(
                        threadActions = if (matching) {
                            current.threadActions.inputUncertain(locator, clientId)
                        } else {
                            current.threadActions
                        },
                        cards = current.cards.updateDelivery(clientId, DeliveryState.UNKNOWN),
                        error = "${failure.message ?: failure::class.java.simpleName}; input was not replayed",
                    )
                }
                browseThread(locator)
            }
        }
    }

    fun interrupt(locator: CodexThreadLocator) {
        val state = mutableState.value
        val (actions, turnId) = try {
            state.threadActions.beginInterrupt(
                locator,
                state.threads[locator]?.activeTurnId,
                state.threadAttachments.hasDealerClaim(locator),
            )
        } catch (failure: IllegalArgumentException) {
            mutableState.update { it.copy(error = failure.message) }
            return
        }
        val appServer = hostSessions.connectedSession(locator.hostId)?.appServer ?: run {
            mutableState.update { it.copy(error = "Connect ${locator.hostId} before interrupting") }
            return
        }
        mutableState.update { it.copy(threadActions = actions, error = null) }
        scope.launch {
            try {
                draftMutex.withLock { threadAttachmentStore.writePendingInterrupt(locator, turnId) }
            } catch (failure: Throwable) {
                mutableState.update {
                    it.copy(
                        threadActions = it.threadActions.reconcileInterrupt(locator, null),
                        error = "Unable to persist Interrupt; nothing was sent: ${failure.message}",
                    )
                }
                return@launch
            }
            try {
                appServer.turnInterrupt(locator.threadId, turnId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rejected: JsonRpcRemoteException) {
                val cleanupFailure = runCatching {
                    draftMutex.withLock { threadAttachmentStore.writePendingInterrupt(locator, null) }
                }.exceptionOrNull()
                mutableState.update {
                    it.copy(
                        threadActions = if (cleanupFailure == null) {
                            it.threadActions.reconcileInterrupt(locator, null)
                        } else {
                            it.threadActions
                        },
                        error = cleanupFailure?.let { failure ->
                            "${rejected.message}; local Interrupt lock cleanup failed: ${failure.message}"
                        } ?: rejected.message,
                    )
                }
                browseThread(locator)
            } catch (failure: Throwable) {
                mutableState.update {
                    it.copy(error = "${failure.message ?: failure::class.java.simpleName}; interrupt was not replayed")
                }
                browseThread(locator)
            }
        }
    }

    fun refreshThreads(hostId: String) {
        if (mutableState.value.refreshingThreadHosts.contains(hostId)) return
        val appServer = hostSessions.connectedSession(hostId)?.appServer ?: return
        scope.launch {
            mutableState.update {
                it.copy(
                    refreshingThreadHosts = it.refreshingThreadHosts + hostId,
                    threadDiscoveryErrors = it.threadDiscoveryErrors - hostId,
                )
            }
            try {
                val discovered = HostThreadDiscovery(appServer).discover(hostId) { locator ->
                    val state = mutableState.value
                    val existing = state.threads[locator]
                    ThreadDiscoveryLocalState(
                        attached = locator in state.threadAttachments.attached,
                        unreadCount = existing?.unreadCount ?: 0,
                        intendedControlSurface = if (state.threadAttachments.hasDealerClaim(locator)) {
                            ControlSurface.DEALER
                        } else {
                            ControlSurface.NONE
                        },
                    )
                }
                mutableState.update { state ->
                    val rows = discovered.associateBy(DiscoveredThread::locator).mapValues { (locator, row) ->
                        row.copy(
                            activeTurnId = state.threads[locator]?.activeTurnId
                                .takeIf { row.workState == ThreadWorkState.BUSY },
                        )
                    }
                    state.copy(
                        threads = state.threads.filterKeys { it.hostId != hostId } +
                            rows,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.update {
                    it.copy(
                        threadDiscoveryErrors = it.threadDiscoveryErrors +
                            (hostId to (failure.message ?: failure::class.java.simpleName)),
                    )
                }
            } finally {
                mutableState.update {
                    it.copy(refreshingThreadHosts = it.refreshingThreadHosts - hostId)
                }
            }
        }
    }

    fun attachThread(locator: CodexThreadLocator) {
        val appServer = hostSessions.connectedSession(locator.hostId)?.appServer ?: run {
            mutableState.update { it.copy(error = "Connect ${locator.hostId} before attaching") }
            return
        }
        scope.launch {
            attachmentMutex.withLock {
                if (locator in mutableState.value.threadAttachments.attached) return@withLock
                try {
                    appServer.threadResume(locator.threadId)
                    try {
                        threadAttachmentStore.attach(locator)
                    } catch (failure: Throwable) {
                        runCatching { appServer.threadUnsubscribe(locator.threadId) }
                        throw failure
                    }
                    mutableState.update { state ->
                        val attached = state.threadAttachments.attach(locator)
                        state.copy(
                            threadAttachments = attached,
                            threads = state.threads[locator]?.let { thread ->
                                state.threads + (
                                    locator to thread.copy(
                                        attached = true,
                                        intendedControlSurface = ControlSurface.NONE,
                                    )
                                )
                            } ?: state.threads,
                            error = null,
                        )
                    }
                    browseThread(locator)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    mutableState.update {
                        it.copy(error = failure.message ?: failure::class.java.simpleName)
                    }
                }
            }
        }
    }

    fun detachThread(locator: CodexThreadLocator) {
        scope.launch {
            attachmentMutex.withLock {
                val state = mutableState.value
                if (locator !in state.threadAttachments.attached) return@withLock
                if (locator in state.knownBlockingRequestThreads) {
                    mutableState.update {
                        it.copy(error = "Resolve, cancel, or interrupt the pending request before detaching")
                    }
                    return@withLock
                }
                try {
                    val appServer = hostSessions.connectedSession(locator.hostId)?.appServer
                    appServer?.threadUnsubscribe(locator.threadId)
                    try {
                        threadAttachmentStore.detach(locator)
                    } catch (failure: Throwable) {
                        runCatching { appServer?.threadResume(locator.threadId) }
                        throw failure
                    }
                    mutableState.update {
                        val detached = it.threadAttachments.detach(locator)
                        it.copy(
                            threadAttachments = detached,
                            threads = it.threads[locator]?.let { thread ->
                                it.threads + (
                                    locator to thread.copy(
                                        attached = false,
                                        intendedControlSurface = ControlSurface.NONE,
                                    )
                                )
                            } ?: it.threads,
                            browsedThread = it.browsedThread?.takeUnless { browsed -> browsed == locator },
                            error = null,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    mutableState.update {
                        it.copy(error = failure.message ?: failure::class.java.simpleName)
                    }
                }
            }
        }
    }

    private fun restoreAttachments(hostId: String) {
        val appServer = hostSessions.connectedSession(hostId)?.appServer ?: return
        scope.launch {
            mutableState.value.threadAttachments.attached
                .filter { it.hostId == hostId }
                .forEach { locator ->
                    runCatching { appServer.threadResume(locator.threadId) }
                        .onSuccess { browseThread(locator) }
                        .onFailure { failure ->
                            mutableState.update {
                                it.copy(
                                    threadDiscoveryErrors = it.threadDiscoveryErrors +
                                        (hostId to (failure.message ?: failure::class.java.simpleName)),
                                )
                            }
                        }
                }
            refreshThreads(hostId)
        }
    }

    private fun observeNotifications(hostId: String) {
        val appServer = hostSessions.connectedSession(hostId)?.appServer ?: return
        notificationJobs.remove(hostId)?.cancel()
        notificationJobs[hostId] = scope.launch {
            while (true) {
                val notification = appServer.receiveNotification() ?: return@launch
                val params = notification.params as? JsonObject ?: continue
                val turn = params["turn"] as? JsonObject
                val threadId = (params["threadId"] as? JsonPrimitive)?.contentOrNull
                    ?: (turn?.get("threadId") as? JsonPrimitive)?.contentOrNull
                    ?: continue
                val locator = CodexThreadLocator(hostId, threadId)
                val turnId = (turn?.get("id") as? JsonPrimitive)?.contentOrNull
                    ?: (params["turnId"] as? JsonPrimitive)?.contentOrNull
                when (notification.method) {
                    "turn/started" -> {
                        val clientIds = (turn?.get("items") as? kotlinx.serialization.json.JsonArray)
                            .orEmpty()
                            .mapNotNull { item ->
                                ((item as? JsonObject)?.get("clientId") as? JsonPrimitive)?.contentOrNull
                            }
                        val pendingClient = mutableState.value.threadActions.pendingInputs[locator]?.clientId
                        val dealerOriginated = turnId != null &&
                            (dealerOriginatedTurns.remove(locator to turnId) || pendingClient in clientIds)
                        externalTurnStarted(locator, dealerOriginated, turnId)
                    }
                    "turn/completed" -> {
                        turnId?.let { dealerOriginatedTurns.remove(locator to it) }
                        mutableState.update { state ->
                            state.copy(
                                threadActions = state.threadActions.reconcileInterrupt(locator, null),
                                threads = state.threads[locator]?.let { row ->
                                    state.threads + (
                                        locator to row.copy(
                                            status = "idle",
                                            workState = ThreadWorkState.READY,
                                            activeTurnId = null,
                                        )
                                    )
                                } ?: state.threads,
                            )
                        }
                        scope.launch {
                            draftMutex.withLock {
                                threadAttachmentStore.writePendingInterrupt(locator, null)
                            }
                        }
                        browseThread(locator)
                    }
                }
            }
        }
    }

    internal fun externalTurnStarted(
        locator: CodexThreadLocator,
        dealerOriginated: Boolean = false,
        turnId: String? = null,
    ) {
        mutableState.update { state ->
            val attachments = state.threadAttachments.externalTurnStarted(locator, dealerOriginated)
            state.copy(
                threadAttachments = attachments,
                threads = state.threads[locator]?.let { thread ->
                    state.threads + (
                        locator to thread.copy(
                            status = "active",
                            workState = ThreadWorkState.BUSY,
                            activeTurnId = turnId ?: thread.activeTurnId,
                            intendedControlSurface = if (attachments.hasDealerClaim(locator)) {
                                ControlSurface.DEALER
                            } else {
                                ControlSurface.NONE
                            },
                        )
                    )
                } ?: state.threads,
            )
        }
    }

    fun browseThread(locator: CodexThreadLocator) {
        val appServer = hostSessions.connectedSession(locator.hostId)?.appServer ?: return
        scope.launch {
            try {
                val conversationId = "${locator.hostId}/${locator.threadId}"
                val response = appServer.threadRead(locator.threadId)
                val authoritative = AppServerThreadProjection.authoritativeState(response)
                val cards = AppServerThreadProjection.cards(response, conversationId)
                var clearDraft = false
                var clearInterrupt = false
                mutableState.update { state ->
                    val pending = state.threadActions.pendingInputs[locator]
                    val delivered = pending != null &&
                        AppServerThreadProjection.countUserClientId(response, pending.clientId) == 1
                    clearDraft = delivered
                    clearInterrupt = state.threadActions.pendingInterrupts[locator] != null &&
                        state.threadActions.pendingInterrupts[locator] != authoritative.activeTurnId
                    val actions = when {
                        delivered -> state.threadActions.inputAccepted(locator, pending.clientId)
                        else -> state.threadActions
                    }.reconcileInterrupt(locator, authoritative.activeTurnId)
                    val prior = state.cards
                        .filter { it.conversationId == conversationId }
                        .associateBy(Card::id)
                    val reconciled = cards.map { card ->
                        prior[card.id]?.let {
                            card.copy(
                                sequence = it.sequence,
                                revision = it.revision + 1,
                                createdAtMs = it.createdAtMs,
                            )
                        } ?: card
                    }
                    val localOnly = prior.values.filter { card ->
                        card.id !in reconciled.mapTo(mutableSetOf(), Card::id) &&
                            card.delivery != null
                    }
                    state.copy(
                        threadActions = actions,
                        cards = state.cards.filterNot { it.conversationId == conversationId } +
                            localOnly + reconciled,
                        threads = state.threads[locator]?.let { row ->
                            state.threads + (
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
                        } ?: state.threads,
                        browsedThread = locator,
                        threadDiscoveryErrors = state.threadDiscoveryErrors - locator.hostId,
                        error = null,
                    )
                }
                if (clearDraft) {
                    val retainedDraft = mutableState.value.threadActions.drafts[locator].orEmpty()
                    draftMutex.withLock {
                        threadAttachmentStore.writeDraft(locator, retainedDraft)
                        threadAttachmentStore.writePendingInput(locator, null)
                    }
                }
                if (clearInterrupt) {
                    draftMutex.withLock { threadAttachmentStore.writePendingInterrupt(locator, null) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.update {
                    it.copy(
                        threadDiscoveryErrors = it.threadDiscoveryErrors +
                            (locator.hostId to (failure.message ?: failure::class.java.simpleName)),
                    )
                }
            }
        }
    }

    private fun cacheHostSession(stored: StoredHostConnection): HostSessionConnectionConfig {
        val config = stored.config
        val host = InitialCodexHosts.all.single { it.id == config.hostId }
        return synchronized(hostSessionConfigs) {
            hostSessionSecrets.remove(host.id)?.let {
                it.privateKey.fill(0)
                it.knownHosts.fill(0)
            }
            hostSessionSecrets[host.id] = stored
            createHostSessionConfig(config, stored.privateKey, stored.knownHosts)
                .also { hostSessionConfigs[host.id] = it }
        }
    }

    private fun createHostSessionConfig(
        config: DealerHostConnectionConfig,
        privateKey: ByteArray,
        knownHosts: ByteArray,
    ): HostSessionConnectionConfig {
        val host = InitialCodexHosts.all.single { it.id == config.hostId }
        return HostSessionConnectionConfig(
            host = host,
            dialer = if (host == InitialCodexHosts.fold6Termux) {
                SocketHostTcpDialer(
                    mapOf(
                        (host.id to HostConnectionRoute.SSH_LOOPBACK) to
                            RouteEndpoint("127.0.0.1", config.loopbackSshPort),
                    ),
                )
            } else {
                routeDialer(
                    SocketHostTcpDialer(
                        endpoints = if (config.lanHost.isBlank()) emptyMap() else mapOf(
                            (host.id to HostConnectionRoute.SSH_LAN) to RouteEndpoint(config.lanHost),
                        ),
                    ),
                    EmbeddedTailnetHostTcpDialer(
                        tailnetEngine,
                        if (config.tailnetHost.isBlank()) emptyMap() else mapOf(host.id to config.tailnetHost),
                        state = { mutableState.value.tailnet.state },
                    ),
                )
            },
            sshClient = JschHostSshClient(
                mapOf(
                    host.id to SshHostAuthentication(
                        username = config.sshUser,
                        privateKey = privateKey,
                        knownHosts = knownHosts,
                    ),
                ),
            ),
            daemon = if (host == InitialCodexHosts.fold6Termux) {
                TermuxCommunityCodexDaemon()
            } else {
                UpstreamCodexDaemon()
            },
        )
    }

    @Synchronized
    fun takeControl(hostId: String, threadId: String): Boolean {
        if (runJob != null || threadId.isBlank() || InitialCodexHosts.all.none { it.id == hostId }) {
            return false
        }
        val locator = CodexThreadLocator(hostId, threadId)
        if (locator !in mutableState.value.threadAttachments.attached) return false
        mutableState.update { state ->
            val attachments = state.threadAttachments.claim(locator)
            state.copy(
                threadAttachments = attachments,
                threads = state.threads[locator]?.let { thread ->
                    state.threads + (
                        locator to thread.copy(intendedControlSurface = ControlSurface.DEALER)
                    )
                } ?: state.threads,
                error = null,
            )
        }
        return true
    }

    @Synchronized
    fun yieldControl(hostId: String, threadId: String): Boolean {
        val locator = CodexThreadLocator(hostId, threadId)
        if (runJob != null || !mutableState.value.threadAttachments.hasDealerClaim(locator)) return false
        mutableState.update { state ->
            state.copy(
                threadAttachments = state.threadAttachments.release(locator),
                threads = state.threads[locator]?.let { thread ->
                    state.threads + (
                        locator to thread.copy(intendedControlSurface = ControlSurface.NONE)
                    )
                } ?: state.threads,
            )
        }
        return true
    }

    @Synchronized
    fun startEmbeddedTailnet(): Boolean {
        if (tailnetJob != null || mutableState.value.tailnet.active) return false
        ensureForeground()
        mutableState.update {
            it.copy(tailnet = EmbeddedTailnetUiState(EmbeddedTailnetState.STARTING))
        }
        tailnetJob = scope.launch(Dispatchers.IO) {
            try {
                updateEmbeddedTailnetNetwork()
                var nativeStatus = tailnetEngine.start(
                    filesDir.resolve("embedded-tailnet").absolutePath,
                )
                while (true) {
                    mutableState.update { it.copy(tailnet = nativeStatus.toEmbeddedTailnetUiState()) }
                    delay(TAILNET_STATUS_INTERVAL_MILLIS)
                    updateEmbeddedTailnetNetwork()
                    nativeStatus = tailnetEngine.status()
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                runCatching { tailnetEngine.stop() }
                mutableState.update {
                    it.copy(
                        tailnet = EmbeddedTailnetUiState(
                            state = EmbeddedTailnetState.ERROR,
                            error = failure.message ?: failure::class.java.simpleName,
                        ),
                    )
                }
                stopIfIdle()
            } finally {
                synchronized(this@DealerConnectionService) {
                    tailnetJob = null
                }
            }
        }
        return true
    }

    private fun updateEmbeddedTailnetNetwork() {
        val manager = getSystemService(ConnectivityManager::class.java)
        val properties = manager.getLinkProperties(manager.activeNetwork)
        val interfaceName = properties?.interfaceName.orEmpty()
        val addresses = buildJsonArray {
            properties?.linkAddresses.orEmpty().forEach {
                val address = it.address.hostAddress?.substringBefore('%') ?: return@forEach
                add(JsonPrimitive("$address/${it.prefixLength}"))
            }
        }
        val gateway = properties?.routes
            ?.firstOrNull { it.isDefaultRoute }
            ?.gateway
            ?.hostAddress
            ?.substringBefore('%')
            .orEmpty()
        tailnetEngine.setNetwork(interfaceName, addresses.toString(), gateway)
    }

    @Synchronized
    fun stopEmbeddedTailnet(): Boolean {
        if (mutableState.value.tailnet.state in setOf(
                EmbeddedTailnetState.STOPPING,
                EmbeddedTailnetState.RESETTING,
            )
        ) {
            return false
        }
        tailnetJob?.cancel()
        mutableState.update {
            it.copy(tailnet = EmbeddedTailnetUiState(EmbeddedTailnetState.STOPPING))
        }
        scope.launch(Dispatchers.IO) {
            try {
                tailnetEngine.stop()
                mutableState.update {
                    it.copy(tailnet = EmbeddedTailnetUiState(EmbeddedTailnetState.STOPPED))
                }
                stopIfIdle()
            } catch (failure: Throwable) {
                mutableState.update {
                    it.copy(
                        tailnet = EmbeddedTailnetUiState(
                            state = EmbeddedTailnetState.ERROR,
                            error = failure.message ?: failure::class.java.simpleName,
                        ),
                    )
                }
            }
        }
        return true
    }

    @Synchronized
    fun resetEmbeddedTailnet(): Boolean {
        if (mutableState.value.tailnet.state in setOf(
                EmbeddedTailnetState.STOPPING,
                EmbeddedTailnetState.RESETTING,
            )
        ) {
            return false
        }
        cancelRun()
        tailnetJob?.cancel()
        ensureForeground()
        mutableState.update {
            it.copy(tailnet = EmbeddedTailnetUiState(EmbeddedTailnetState.RESETTING))
        }
        scope.launch(Dispatchers.IO) {
            try {
                tailnetEngine.reset(filesDir.resolve("embedded-tailnet").absolutePath)
                mutableState.update {
                    it.copy(tailnet = EmbeddedTailnetUiState(EmbeddedTailnetState.STOPPED))
                }
                stopIfIdle()
            } catch (failure: Throwable) {
                mutableState.update {
                    it.copy(
                        tailnet = EmbeddedTailnetUiState(
                            state = EmbeddedTailnetState.ERROR,
                            error = failure.message ?: failure::class.java.simpleName,
                        ),
                    )
                }
            }
        }
        return true
    }

    override fun onDestroy() {
        runCatching { tailnetEngine.stop() }
        synchronized(hostSessionConfigs) {
            hostSessionSecrets.values.forEach {
                it.privateKey.fill(0)
                it.knownHosts.fill(0)
            }
            hostSessionSecrets.clear()
            hostSessionConfigs.clear()
        }
        scope.cancel()
        threadAttachmentStore.close()
        super.onDestroy()
    }

    private fun ensureForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Dealer host connection",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val builder = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_dealer_connection)
            .setContentTitle("Dealer")
            .setContentText("Dealer connection active")
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, DealerActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        if (mutableState.value.hostSessions.values.none(HostSessionState::enabled)) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_dealer_connection),
                    "Cancel",
                    PendingIntent.getService(
                        this,
                        0,
                        Intent(this, DealerConnectionService::class.java).setAction(ACTION_CANCEL),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).build(),
            )
        }
        startForeground(NOTIFICATION_ID, builder.build())
    }

    private fun stopIfIdle() {
        if (runJob == null &&
            !mutableState.value.tailnet.active &&
            mutableState.value.hostSessions.values.none(HostSessionState::enabled)
        ) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    companion object {
        internal const val ACTION_START_TAILNET = "com.code2hack.dealer.action.START_TAILNET"
        internal const val ACTION_RESET_TAILNET = "com.code2hack.dealer.action.RESET_TAILNET"

        const val NOTIFICATION_CHANNEL = "dealer-host-connection"
        const val NOTIFICATION_ID = 4090
        const val ACTION_CANCEL = "com.code2hack.dealer.action.CANCEL_M1"
        const val TAILNET_STATUS_INTERVAL_MILLIS = 1_000L
    }
}

enum class DealerRunState(
    val label: String,
    val active: Boolean = false,
) {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting", active = true),
    RUNNING("Running", active = true),
    RECONNECTING("Reconnecting", active = true),
    BACKING_OFF("Waiting to retry", active = true),
    COMPLETED("Completed"),
    RECOVERED("Recovered"),
    INTERRUPTED("Interrupted"),
    FAILED("Failed"),
    UNKNOWN("Unknown outcome"),
    CANCELLED("Cancelled"),
    ERROR("Error"),
}

data class DealerUiState(
    val status: DealerRunState = DealerRunState.DISCONNECTED,
    val hostId: String? = null,
    val route: HostConnectionRoute? = null,
    val threadId: String? = null,
    val appServerVersion: String? = null,
    val cards: List<Card> = emptyList(),
    val routeDiagnostics: List<RouteDiagnostic> = emptyList(),
    val recovery: DealerRecoveryUiState? = null,
    val threadAttachments: ThreadAttachmentState = ThreadAttachmentState(),
    val threadActions: ThreadActionState = ThreadActionState(),
    val knownBlockingRequestThreads: Set<CodexThreadLocator> = emptySet(),
    val tailnet: EmbeddedTailnetUiState = EmbeddedTailnetUiState(),
    val hostSessions: Map<String, HostSessionState> = emptyMap(),
    val threads: Map<CodexThreadLocator, DiscoveredThread> = emptyMap(),
    val refreshingThreadHosts: Set<String> = emptySet(),
    val threadDiscoveryErrors: Map<String, String> = emptyMap(),
    val browsedThread: CodexThreadLocator? = null,
    val error: String? = null,
) {
    val running: Boolean
        get() = status.active
}

data class DealerRecoveryUiState(
    val phase: M1FailurePhase,
    val action: String,
    val failedAttempt: Int? = null,
    val maxAttempts: Int? = null,
    val retryInMs: Long? = null,
)

private fun List<Card>.updateDelivery(cardId: String, delivery: DeliveryState): List<Card> = map { card ->
    if (card.id == cardId) {
        val current = card.delivery
        if (current == DeliveryState.DELIVERED ||
            current == DeliveryState.REJECTED ||
            current == DeliveryState.UNKNOWN && delivery != DeliveryState.DELIVERED
        ) {
            card
        } else {
            card.copy(
                revision = card.revision + 1,
                state = if (delivery == DeliveryState.DELIVERED) CardState.COMMITTED else CardState.OPEN,
                delivery = delivery,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    } else {
        card
    }
}

internal object DealerServiceState {
    val mutableState = MutableStateFlow(DealerUiState())
    val state: StateFlow<DealerUiState> = mutableState.asStateFlow()
}

internal fun DealerUiState.afterRun(
    recovered: Boolean,
    threadId: String,
    appServerVersion: String?,
    routeDiagnostics: List<RouteDiagnostic>,
): DealerUiState = copy(
    status = if (recovered) DealerRunState.RECOVERED else DealerRunState.COMPLETED,
    route = null,
    threadId = threadId,
    appServerVersion = appServerVersion,
    routeDiagnostics = routeDiagnostics,
    recovery = null,
    error = null,
)

enum class EmbeddedTailnetState(
    val label: String,
    val active: Boolean = false,
) {
    STOPPED("Stopped"),
    STARTING("Starting", active = true),
    STOPPING("Stopping", active = true),
    RESETTING("Resetting", active = true),
    LOGIN_REQUIRED("Login required", active = true),
    CONNECTED("Connected", active = true),
    DEGRADED("Degraded", active = true),
    UNAVAILABLE("Unavailable", active = true),
    ERROR("Error"),
}

data class EmbeddedTailnetUiState(
    val state: EmbeddedTailnetState = EmbeddedTailnetState.STOPPED,
    val loginUrl: String? = null,
    val nodeName: String? = null,
    val path: String? = null,
    val relay: String? = null,
    val health: List<String> = emptyList(),
    val error: String? = null,
) {
    val active: Boolean
        get() = state.active

    val connectionLabel: String
        get() = when (path) {
            "direct" -> "${state.label} (direct)"
            "relayed" -> "${state.label} (DERP${relay?.let { " $it" }.orEmpty()})"
            else -> state.label
        }
}

internal fun String.toEmbeddedTailnetUiState(): EmbeddedTailnetUiState {
    val status = Json.parseToJsonElement(this).jsonObject
    val state = when (status.getValue("state").jsonPrimitive.content) {
        "stopped" -> EmbeddedTailnetState.STOPPED
        "starting" -> EmbeddedTailnetState.STARTING
        "login_required" -> EmbeddedTailnetState.LOGIN_REQUIRED
        "connected" -> EmbeddedTailnetState.CONNECTED
        "degraded" -> EmbeddedTailnetState.DEGRADED
        "unavailable" -> EmbeddedTailnetState.UNAVAILABLE
        else -> EmbeddedTailnetState.ERROR
    }
    return EmbeddedTailnetUiState(
        state = state,
        loginUrl = status["loginUrl"]?.jsonPrimitive?.content,
        nodeName = status["nodeName"]?.jsonPrimitive?.content,
        path = status["path"]?.jsonPrimitive?.content,
        relay = status["relay"]?.jsonPrimitive?.content,
        health = status["health"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
    )
}

internal fun DealerUiState.withPhase(phase: M1ConnectionPhase): DealerUiState = copy(
    status = phase.toDealerRunState(),
    route = if (phase == M1ConnectionPhase.RECONNECTING) null else route,
    recovery = if (phase == M1ConnectionPhase.RECONNECTING) recovery else null,
)

internal fun DealerUiState.withActiveRoute(
    route: HostConnectionRoute,
    diagnostics: List<RouteDiagnostic>,
): DealerUiState = copy(
    route = route,
    routeDiagnostics = diagnostics,
)

private fun M1ConnectionPhase.toDealerRunState(): DealerRunState = when (this) {
    M1ConnectionPhase.CONNECTING -> DealerRunState.CONNECTING
    M1ConnectionPhase.RUNNING -> DealerRunState.RUNNING
    M1ConnectionPhase.RECONNECTING -> DealerRunState.RECONNECTING
}

internal fun DealerUiState.withRecovery(
    host: CodexHost,
    update: M1RecoveryUpdate,
): DealerUiState = copy(
    status = DealerRunState.BACKING_OFF,
    route = null,
    recovery = DealerRecoveryUiState(
        phase = update.failurePhase,
        action = host.recoveryAction(update.failurePhase),
        failedAttempt = update.failedAttempt,
        maxAttempts = update.maxAttempts,
        retryInMs = update.retryInMs,
    ),
)

private fun M1TurnOutcome.toDealerRunState(): DealerRunState = when (this) {
    M1TurnOutcome.INTERRUPTED -> DealerRunState.INTERRUPTED
    M1TurnOutcome.FAILED -> DealerRunState.FAILED
    M1TurnOutcome.UNKNOWN -> DealerRunState.UNKNOWN
}

internal fun CodexHost.recoveryAction(phase: M1FailurePhase): String =
    if (this == InitialCodexHosts.fold6Termux) {
        when (phase) {
            M1FailurePhase.TCP_CONNECT ->
                "Open Termux after Android suspension or process stop, then restore sshd."
            M1FailurePhase.SSH_CONNECT ->
                "Restore Termux sshd and verify the dedicated Dealer key and host pin."
            M1FailurePhase.DAEMON ->
                "Start the Termux app-server daemon; repair the community distribution if lifecycle checks fail."
            M1FailurePhase.PROXY, M1FailurePhase.WEBSOCKET ->
                "Restart the Termux app-server daemon and its proxy, then retry."
            M1FailurePhase.APP_SERVER_INITIALIZE, M1FailurePhase.APP_SERVER_REQUEST ->
                "Retry after app-server recovery; repair an unsupported community distribution if this repeats."
            M1FailurePhase.TURN_START, M1FailurePhase.TURN_NOTIFICATIONS ->
                "Restore Termux and retry recovery; Dealer will inspect the thread without replaying turn/start."
            M1FailurePhase.RECONNECT_INSPECTION ->
                "Restore Termux, then retry recovery after the bounded inspection window."
        }
    } else {
        "Restore the failing ${phase.name.lowercase().replace('_', ' ')} phase and retry."
    }

internal fun Throwable.routeDiagnostics(): List<RouteDiagnostic> =
    when (this) {
        is RouteConnectionException -> diagnostics
        is HostIdentityException -> diagnostics
        else -> emptyList()
    } + suppressed.flatMap { it.routeDiagnostics() } + cause?.routeDiagnostics().orEmpty()

data class DealerRunConfig(
    val hostId: String,
    val lanHost: String,
    val tailnetHost: String,
    val sshUser: String,
    val threadId: String,
    val turnText: String,
    val loopbackSshPort: Int = 0,
)

data class DealerHostConnectionConfig(
    val hostId: String,
    val lanHost: String,
    val tailnetHost: String,
    val sshUser: String,
    val loopbackSshPort: Int = 0,
)

internal fun DealerUiState.hasDealerControl(config: DealerRunConfig): Boolean =
    threadAttachments.hasDealerClaim(CodexThreadLocator(config.hostId, config.threadId))

private fun routeDialer(
    lan: HostTcpDialer,
    embedded: HostTcpDialer,
): HostTcpDialer = object : HostTcpDialer {
    override fun capability(
        host: CodexHost,
        route: HostConnectionRoute,
    ) = when (route) {
        HostConnectionRoute.SSH_LAN -> lan.capability(host, route)
        HostConnectionRoute.SSH_EMBEDDED_TSNET -> embedded.capability(host, route)
        else -> RouteCapability.DISABLED
    }

    override suspend fun connect(
        host: CodexHost,
        route: HostConnectionRoute,
        port: Int,
    ) = when (route) {
        HostConnectionRoute.SSH_LAN -> lan.connect(host, route, port)
        HostConnectionRoute.SSH_EMBEDDED_TSNET -> embedded.connect(host, route, port)
        else -> error("Route $route is disabled")
    }
}
