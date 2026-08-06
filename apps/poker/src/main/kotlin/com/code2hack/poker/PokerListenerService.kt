package com.code2hack.poker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import com.code2hack.pokerdealer.domain.PokerBindingInstallResult
import com.code2hack.pokerdealer.protocol.CoroutinePokerScheduler
import com.code2hack.pokerdealer.protocol.PokerReconnectController
import com.code2hack.pokerdealer.protocol.PokerReconnectTrigger
import com.code2hack.pokerdealer.protocol.PokerClock
import com.code2hack.pokerdealer.protocol.PokerConnectionOwner
import com.code2hack.pokerdealer.protocol.PokerBindingProtocol
import com.code2hack.pokerdealer.protocol.PokerBindingLearningState
import com.code2hack.pokerdealer.protocol.POKER_BINDINGS_ACK_TYPE
import com.code2hack.pokerdealer.protocol.POKER_BINDINGS_CAPABILITY
import com.code2hack.pokerdealer.protocol.POKER_BINDINGS_LEARN_TYPE
import com.code2hack.pokerdealer.protocol.POKER_BINDINGS_REMOTE_OBSERVED_TYPE
import com.code2hack.pokerdealer.protocol.POKER_BINDINGS_REMOTE_FORGOTTEN_TYPE
import com.code2hack.pokerdealer.protocol.POKER_BINDINGS_SNAPSHOT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_BINDINGS_LEARNING_TYPE
import com.code2hack.pokerdealer.protocol.POKER_PROTOCOL_MAJOR
import com.code2hack.pokerdealer.protocol.PokerProtocolOffer
import com.code2hack.pokerdealer.protocol.PokerProtocolAccess
import com.code2hack.pokerdealer.protocol.sendBindingSnapshot
import com.code2hack.pokerdealer.protocol.PokerSnapshotConnectionHandler
import com.code2hack.pokerdealer.protocol.PokerSnapshotInstaller
import com.code2hack.pokerdealer.protocol.PokerSnapshotRole
import com.code2hack.pokerdealer.protocol.POKER_SNAPSHOT_CAPABILITY
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns only listener lifecycle state; synchronized card content is never stored here. */
class PokerListenerService : Service() {
    private lateinit var serviceScope: CoroutineScope
    private lateinit var owner: PokerConnectionOwner<Unit>
    private lateinit var pokerSnapshotHandler: PokerSnapshotConnectionHandler
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var foregroundStarted = false
    private val bindingSendMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val identity = AndroidKeystorePairingIdentity()
        val pairing = identity.pairingController(this)
        PokerBindingRuntime.attachService { serviceScope.launch { sendBindingState() } }
        PokerSnapshotRuntime.clearForRestart()
        pokerSnapshotHandler = PokerSnapshotConnectionHandler(
            role = PokerSnapshotRole.POKER,
            installer = PokerSnapshotInstaller(),
            onInstalled = PokerSnapshotRuntime::install,
        )
        owner = PokerConnectionOwner(
            factory = AndroidPokerListenerFactory(this, identity, pairing),
            scope = serviceScope,
            localOffer = PokerProtocolOffer(
                major = POKER_PROTOCOL_MAJOR,
                capabilities = setOf(POKER_BINDINGS_CAPABILITY, POKER_SNAPSHOT_CAPABILITY),
            ),
            scheduler = CoroutinePokerScheduler(serviceScope),
            clock = PokerClock { System.currentTimeMillis() },
            reconnect = PokerReconnectController(),
            onConnected = { _, negotiation ->
                if (negotiation.access == PokerProtocolAccess.READ_WRITE &&
                    negotiation.supports(POKER_BINDINGS_CAPABILITY)
                ) {
                    sendBindingState()
                }
            },
            onStateChanged = { state ->
                if (state != com.code2hack.pokerdealer.protocol.PokerConnectionState.CONNECTED) {
                    PokerBindingRuntime.notifyConnectionLost()
                }
            },
            onEnvelope = { _, envelope ->
                PokerComposerBridge.receive(envelope)
                handleBindingEnvelope(envelope)
            },
            callbacks = pokerSnapshotHandler,
        )
        PokerComposerBridge.attach { type, payload, requireWritable ->
            owner.send(type, payload, requireWritable = requireWritable)
        }
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISABLE -> {
                setEnabled(this, false)
                owner.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
                stopSelfResult(startId)
                return START_NOT_STICKY
            }

            ACTION_ENABLE -> {
                setEnabled(this, true)
                startForegroundCompat()
                owner.start()
            }

            ACTION_RETRY -> if (isEnabled(this)) {
                startForegroundCompat()
                if (owner.isRunning) {
                    owner.retry(PokerReconnectTrigger.MANUAL_RETRY)
                } else {
                    owner.start()
                }
            } else {
                stopSelfResult(startId)
                return START_NOT_STICKY
            }

            null -> if (isEnabled(this)) {
                startForegroundCompat()
                owner.start()
            } else {
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        networkCallback?.let { callback ->
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        }
        networkCallback = null
        PokerComposerBridge.detach()
        owner.stop()
        PokerBindingRuntime.notifyConnectionLost()
        PokerBindingRuntime.detachService()
        serviceScope.cancel()
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerNetworkCallback() {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                requestNetworkRetry()
            }

            override fun onLost(network: Network) {
                requestNetworkRetry()
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: android.net.LinkProperties,
            ) {
                requestNetworkRetry()
            }
        }
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    private fun requestNetworkRetry() {
        if (isEnabled(this)) owner.retry(PokerReconnectTrigger.NETWORK_CHANGE)
    }

    private suspend fun sendBindingState() {
        if (!owner.isConnected) return
        bindingSendMutex.withLock {
            if (!owner.isConnected) return@withLock
            sendBindingStateLocked()
        }
    }

    private suspend fun sendBindingStateLocked() {
        val controller = PokerBindingRuntime.controller
        controller.state.knownRemoteDescriptors.singleOrNull()?.let { descriptor ->
            owner.send(
                POKER_BINDINGS_REMOTE_OBSERVED_TYPE,
                PokerBindingProtocol.remoteObservedPayload(descriptor),
            )
        }
        owner.sendBindingSnapshot(controller.map)
        val target = controller.learningTarget
        owner.send(
            POKER_BINDINGS_LEARNING_TYPE,
            PokerBindingProtocol.learningPayload(
                target?.let {
                    PokerBindingLearningState(it.device.descriptor, it.operation)
                } ?: PokerBindingLearningState(),
            ),
        )
    }

    private suspend fun sendBindingMessage(
        type: String,
        payload: kotlinx.serialization.json.JsonObject,
        replyTo: String? = null,
    ) = bindingSendMutex.withLock {
        owner.send(type, payload, replyTo)
    }

    private suspend fun handleBindingEnvelope(envelope: com.code2hack.pokerdealer.protocol.ProtocolEnvelope) {
        val controller = PokerBindingRuntime.controller
        when (envelope.type) {
            POKER_BINDINGS_LEARN_TYPE -> {
                val request = runCatching { PokerBindingProtocol.decodeLearn(envelope) }.getOrNull()
                val accepted = request != null && PokerBindingRuntime.isForeground &&
                    controller.beginLearning(
                        com.code2hack.pokerdealer.domain.PokerBindingDevice.remote(request.descriptor),
                        request.operation,
                    )
                sendBindingState()
                if (!accepted) {
                    sendBindingMessage(
                        POKER_BINDINGS_LEARNING_TYPE,
                        PokerBindingProtocol.learningPayload(PokerBindingLearningState()),
                        replyTo = envelope.messageId,
                    )
                }
            }

            POKER_BINDINGS_SNAPSHOT_TYPE -> {
                val candidate = runCatching { PokerBindingProtocol.decodeSnapshot(envelope) }.getOrNull()
                val result = if (candidate == null) {
                    PokerBindingInstallResult.REJECTED
                } else {
                    PokerBindingProtocol.installSnapshot(controller, envelope, authoritative = true)
                }
                sendBindingMessage(
                    POKER_BINDINGS_ACK_TYPE,
                    PokerBindingProtocol.ackPayload(
                        candidate?.revision ?: controller.map.revision,
                        result,
                    ),
                    replyTo = envelope.messageId,
                )
            }

            POKER_BINDINGS_REMOTE_FORGOTTEN_TYPE -> {
                val forgotten = runCatching { PokerBindingProtocol.decodeRemoteForgotten(envelope) }
                    .getOrNull() ?: return
                controller.forgetRemote(forgotten.descriptor)
                sendBindingState()
            }

            else -> Unit
        }
    }

    private fun startForegroundCompat() {
        if (foregroundStarted) return
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Poker listener")
            .setContentText("Listener enabled")
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, PokerActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Poker listener",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val ACTION_ENABLE = "com.code2hack.poker.action.ENABLE_LISTENER"
        const val ACTION_DISABLE = "com.code2hack.poker.action.DISABLE_LISTENER"
        const val ACTION_RETRY = "com.code2hack.poker.action.RETRY_LISTENER"
        private const val PREFS = "poker_listener"
        private const val ENABLED = "enabled"
        private const val CHANNEL_ID = "poker_listener"
        private const val NOTIFICATION_ID = 4_201

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(ENABLED, enabled)
                .apply()
        }

        fun enable(context: Context) {
            setEnabled(context, true)
            start(context, ACTION_ENABLE)
        }

        fun disable(context: Context) {
            setEnabled(context, false)
            context.stopService(Intent(context, PokerListenerService::class.java))
        }

        fun retry(context: Context) {
            if (isEnabled(context)) start(context, ACTION_RETRY)
        }

        private fun start(context: Context, action: String) {
            val intent = Intent(context, PokerListenerService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
