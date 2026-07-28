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
import com.code2hack.pokerdealer.domain.CodexHost
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ControlSurface
import com.code2hack.pokerdealer.domain.HostConnectionRoute
import com.code2hack.pokerdealer.domain.InitialCodexHosts
import com.code2hack.pokerdealer.domain.RevisionApplication
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
import com.code2hack.pokerdealer.protocol.appserver.InitializedHostSessionConnector
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
            hostSessions.start()
            hostSessions.state.collect { sessions ->
                mutableState.update { it.copy(hostSessions = sessions) }
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
                it.copy(control = it.control?.takeUnless { control -> control.locator.hostId == hostId })
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
        mutableState.update {
            it.copy(
                control = DealerControlState(
                    CodexThreadLocator(hostId, threadId),
                    ControlSurface.DEALER,
                ),
                error = null,
            )
        }
        return true
    }

    @Synchronized
    fun yieldControl(hostId: String, threadId: String): Boolean {
        val locator = CodexThreadLocator(hostId, threadId)
        if (runJob != null || mutableState.value.control?.locator != locator) return false
        mutableState.update {
            it.copy(control = DealerControlState(locator, ControlSurface.LOCAL_TUI))
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
    // ponytail: process-local soft control; persist it when host/thread Room storage lands.
    val control: DealerControlState? = null,
    val tailnet: EmbeddedTailnetUiState = EmbeddedTailnetUiState(),
    val hostSessions: Map<String, HostSessionState> = emptyMap(),
    val error: String? = null,
) {
    val running: Boolean
        get() = status.active
}

data class DealerControlState(
    val locator: CodexThreadLocator,
    val surface: ControlSurface,
)

data class DealerRecoveryUiState(
    val phase: M1FailurePhase,
    val action: String,
    val failedAttempt: Int? = null,
    val maxAttempts: Int? = null,
    val retryInMs: Long? = null,
)

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
    control == DealerControlState(
        CodexThreadLocator(config.hostId, config.threadId),
        ControlSurface.DEALER,
    )

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
