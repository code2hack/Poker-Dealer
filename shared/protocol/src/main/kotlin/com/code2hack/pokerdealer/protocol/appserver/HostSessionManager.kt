package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.HostConnectionRoute
import com.code2hack.pokerdealer.protocol.host.RouteDiagnostic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

enum class HostSessionStatus {
    DISABLED,
    CONNECTING,
    CONNECTED,
    BACKING_OFF,
    ERROR,
}

enum class HostSessionPhase {
    TCP,
    SSH,
    DAEMON,
    PROXY,
    WEBSOCKET,
    INITIALIZE,
    CONNECTED,
}

data class HostSessionState(
    val enabled: Boolean = false,
    val status: HostSessionStatus = HostSessionStatus.DISABLED,
    val phase: HostSessionPhase? = null,
    val route: HostConnectionRoute? = null,
    val failedAttempts: Int = 0,
    val retryInMs: Long? = null,
    val diagnostics: List<RouteDiagnostic> = emptyList(),
    val appServerVersion: String? = null,
    val descendantFilterQualified: Boolean = false,
    val error: String? = null,
)

data class HostSessionBackoff(
    val initialMs: Long = 1_000,
    val maxMs: Long = 60_000,
) {
    init {
        require(initialMs > 0)
        require(maxMs >= initialMs)
    }

    fun delayMs(failedAttempts: Int): Long {
        require(failedAttempts > 0)
        var value = initialMs
        repeat(minOf(failedAttempts - 1, 62)) {
            value = minOf(maxMs, value.saturatedDouble())
        }
        return value
    }

    private fun Long.saturatedDouble(): Long =
        if (this > Long.MAX_VALUE / 2) Long.MAX_VALUE else this * 2
}

class HostSessionConnectionException(
    val phase: HostSessionPhase,
    val diagnostics: List<RouteDiagnostic>,
    val retryable: Boolean = true,
    cause: Throwable? = null,
) : IllegalStateException(
    cause?.message ?: diagnostics.lastOrNull()?.failure ?: "Host connection failed",
    cause,
)

interface HostSession {
    val appServer: CodexAppServerSession?
    val route: HostConnectionRoute?
    val diagnostics: List<RouteDiagnostic>
    val appServerVersion: String?
        get() = null
    val descendantFilterQualified: Boolean
        get() = false
    suspend fun awaitDisconnect(): Nothing
    suspend fun close()
}

fun interface HostSessionConnector {
    suspend fun connect(hostId: String): HostSession
}

interface HostConnectionIntentStore {
    suspend fun readEnabledHostIds(): Set<String>
    suspend fun writeEnabledHostIds(hostIds: Set<String>)
}

class HostSessionManager(
    private val hostIds: Set<String>,
    private val intentStore: HostConnectionIntentStore,
    private val connector: HostSessionConnector,
    scope: CoroutineScope,
    private val backoff: HostSessionBackoff = HostSessionBackoff(),
) {
    private val managerJob = SupervisorJob(scope.coroutineContext[Job])
    private val scope = CoroutineScope(scope.coroutineContext + managerJob)
    private val lock = Mutex()
    private val jobs = mutableMapOf<String, Job>()
    private val sessions = ConcurrentHashMap<String, HostSession>()
    private val mutableState = MutableStateFlow(
        hostIds.associateWith { HostSessionState() },
    )
    val state: StateFlow<Map<String, HostSessionState>> = mutableState.asStateFlow()

    fun connectedSession(hostId: String): HostSession? = sessions[hostId]

    suspend fun start() {
        val enabled = intentStore.readEnabledHostIds().intersect(hostIds)
        lock.withLock {
            enabled.forEach(::launchLocked)
        }
    }

    suspend fun setEnabled(hostId: String, enabled: Boolean) {
        require(hostId in hostIds) { "Unknown host $hostId" }
        val stopped = lock.withLock {
            val current = mutableState.value.filterValues(HostSessionState::enabled).keys
            intentStore.writeEnabledHostIds(
                if (enabled) current + hostId else current - hostId,
            )
            if (enabled) {
                launchLocked(hostId)
                null
            } else {
                jobs.remove(hostId)?.also { it.cancel(CancellationException("Host disabled")) }
            }
        }
        stopped?.join()
        if (!enabled) setState(hostId, HostSessionState())
    }

    suspend fun close() {
        val active = lock.withLock {
            jobs.values.toList().also {
                it.forEach(Job::cancel)
                jobs.clear()
            }
        }
        active.joinAll()
        managerJob.cancel()
    }

    private fun launchLocked(hostId: String) {
        if (jobs[hostId]?.isActive == true) return
        setState(hostId, HostSessionState(enabled = true, status = HostSessionStatus.CONNECTING))
        jobs[hostId] = scope.launch {
            var failures = 0
            while (true) {
                var session: HostSession? = null
                var retryInMs: Long? = null
                try {
                    setState(
                        hostId,
                        HostSessionState(
                            enabled = true,
                            status = HostSessionStatus.CONNECTING,
                            phase = HostSessionPhase.TCP,
                            failedAttempts = failures,
                        ),
                    )
                    session = connector.connect(hostId)
                    sessions[hostId] = session
                    failures = 0
                    setState(
                        hostId,
                        HostSessionState(
                            enabled = true,
                            status = HostSessionStatus.CONNECTED,
                            phase = HostSessionPhase.CONNECTED,
                            route = session.route,
                            diagnostics = session.diagnostics,
                            appServerVersion = session.appServerVersion,
                            descendantFilterQualified = session.descendantFilterQualified,
                        ),
                    )
                    session.awaitDisconnect()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    failures++
                    val connectionFailure = failure as? HostSessionConnectionException
                    retryInMs = if (connectionFailure?.retryable == false) {
                        null
                    } else {
                        backoff.delayMs(failures)
                    }
                    setState(
                        hostId,
                        HostSessionState(
                            enabled = true,
                            status = if (retryInMs == null) {
                                HostSessionStatus.ERROR
                            } else {
                                HostSessionStatus.BACKING_OFF
                            },
                            phase = connectionFailure?.phase,
                            failedAttempts = failures,
                            retryInMs = retryInMs,
                            diagnostics = connectionFailure?.diagnostics.orEmpty(),
                            error = failure.message ?: failure::class.java.simpleName,
                        ),
                    )
                } finally {
                    session?.let { sessions.remove(hostId, it) }
                    withContext(NonCancellable) { runCatching { session?.close() } }
                }
                val delayMs = retryInMs ?: return@launch
                delay(delayMs)
            }
        }
    }

    private fun setState(hostId: String, state: HostSessionState) {
        mutableState.update { it + (hostId to state) }
    }
}
