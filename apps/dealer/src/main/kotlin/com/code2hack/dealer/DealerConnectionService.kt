package com.code2hack.dealer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardRevisionStore
import com.code2hack.pokerdealer.domain.HostConnectionRoute
import com.code2hack.pokerdealer.domain.InitialCodexHosts
import com.code2hack.pokerdealer.domain.RevisionApplication
import com.code2hack.pokerdealer.protocol.appserver.M1OneHostDealerSlice
import com.code2hack.pokerdealer.protocol.appserver.M1TurnInput
import com.code2hack.pokerdealer.protocol.host.JschHostSshClient
import com.code2hack.pokerdealer.protocol.host.RouteEndpoint
import com.code2hack.pokerdealer.protocol.host.SocketHostTcpDialer
import com.code2hack.pokerdealer.protocol.host.SshHostAuthentication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class DealerConnectionService : Service() {
    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(DealerUiState())
    private var runJob: Job? = null

    val state: StateFlow<DealerUiState> = mutableState.asStateFlow()

    inner class LocalBinder : Binder() {
        val service: DealerConnectionService
            get() = this@DealerConnectionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        return START_NOT_STICKY
    }

    @Synchronized
    fun runM1(
        config: DealerRunConfig,
        privateKey: ByteArray,
        knownHosts: ByteArray,
    ): Boolean {
        if (runJob != null) {
            privateKey.fill(0)
            knownHosts.fill(0)
            return false
        }
        ensureForeground()
        runJob = scope.launch {
            val cards = CardRevisionStore()
            mutableState.update {
                it.copy(
                    status = "Connecting",
                    route = null,
                    threadId = null,
                    appServerVersion = null,
                    cards = emptyList(),
                    running = true,
                    error = null,
                )
            }
            try {
                val host = InitialCodexHosts.u4090
                val slice = M1OneHostDealerSlice(
                    host = host,
                    dialer = SocketHostTcpDialer(
                        mapOf(
                            (host.id to HostConnectionRoute.SSH_LAN) to RouteEndpoint(config.lanHost),
                        ),
                    ),
                    sshClient = JschHostSshClient(
                        mapOf(
                            host.id to SshHostAuthentication(
                                username = config.sshUser,
                                privateKey = privateKey,
                                knownHosts = knownHosts,
                            ),
                        ),
                    ),
                )
                val result = slice.run(
                    M1TurnInput(
                        text = config.turnText,
                        threadId = config.threadId,
                        clientUserMessageId = UUID.randomUUID().toString(),
                    ),
                    onCard = { card ->
                        if (cards.apply(card) != RevisionApplication.IGNORED_STALE) {
                            mutableState.update { it.copy(cards = cards.values()) }
                        }
                    },
                )
                mutableState.update {
                    it.copy(
                        status = if (result.recoveredAfterDisconnect) "Recovered" else "Connected",
                        route = result.reconnectRoute,
                        threadId = result.threadId,
                        appServerVersion = result.daemonVersions.appServerVersion,
                    )
                }
            } catch (failure: CancellationException) {
                mutableState.update { it.copy(status = "Interrupted") }
                throw failure
            } catch (failure: Throwable) {
                mutableState.update {
                    it.copy(
                        status = "Error",
                        error = failure.message ?: failure::class.java.simpleName,
                    )
                }
            } finally {
                privateKey.fill(0)
                knownHosts.fill(0)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                synchronized(this@DealerConnectionService) {
                    runJob = null
                }
                mutableState.update { it.copy(running = false) }
            }
        }
        return true
    }

    override fun onDestroy() {
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
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_dealer_connection)
            .setContentTitle("Dealer")
            .setContentText("u4090 turn in progress")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val NOTIFICATION_CHANNEL = "dealer-host-connection"
        const val NOTIFICATION_ID = 4090
    }
}

data class DealerUiState(
    val status: String = "Disconnected",
    val route: HostConnectionRoute? = null,
    val threadId: String? = null,
    val appServerVersion: String? = null,
    val cards: List<Card> = emptyList(),
    val running: Boolean = false,
    val error: String? = null,
)

data class DealerRunConfig(
    val lanHost: String,
    val sshUser: String,
    val threadId: String,
    val turnText: String,
)
