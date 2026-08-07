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
import com.code2hack.pokerdealer.protocol.POKER_LIVE_DELTA_CAPABILITY
import com.code2hack.pokerdealer.protocol.pokerPairingFingerprint
import com.code2hack.pokerdealer.protocol.POKER_PRIMARY_ACTION_CAPABILITY
import com.code2hack.pokerdealer.protocol.POKER_PHOTO_CAPABILITY
import com.code2hack.pokerdealer.protocol.POKER_MORSE_CAPABILITY
import com.code2hack.pokerdealer.protocol.POKER_DIAGNOSTICS_CAPABILITY
import com.code2hack.pokerdealer.protocol.POKER_DIAGNOSTICS_TYPE
import com.code2hack.pokerdealer.protocol.POKER_FONT_SCALE_ACK_TYPE
import com.code2hack.pokerdealer.protocol.POKER_FONT_SCALE_CAPABILITY
import com.code2hack.pokerdealer.protocol.POKER_FONT_SCALE_TYPE
import com.code2hack.pokerdealer.protocol.POKER_TRANSIENT_NOTICE_TYPE
import com.code2hack.pokerdealer.protocol.PokerDiagnosticsProtocol
import com.code2hack.pokerdealer.protocol.PokerClientDiagnostics
import com.code2hack.pokerdealer.protocol.PokerFontScaleProtocol
import com.code2hack.pokerdealer.protocol.PokerFontScaleInstallResult
import com.code2hack.pokerdealer.protocol.PokerTransientNoticeProtocol
import com.code2hack.pokerdealer.protocol.PokerPairingController
import com.code2hack.pokerdealer.protocol.PokerPairingEnrollment
import com.code2hack.pokerdealer.protocol.PokerPairingFailure
import com.code2hack.pokerdealer.protocol.PokerPairingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns only listener lifecycle state; synchronized card content is never stored here. */
class PokerListenerService : Service() {
    private lateinit var serviceScope: CoroutineScope
    private lateinit var owner: PokerConnectionOwner<Unit>
    private lateinit var pokerSnapshotHandler: PokerSnapshotConnectionHandler
    private lateinit var pairingIdentity: AndroidKeystorePairingIdentity
    private lateinit var pairing: PokerPairingController
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var foregroundStarted = false
    private var pairingServer: PokerEnrollmentServer? = null
    private lateinit var enrollmentAdvertiser: PokerEnrollmentNsdAdvertiser
    private var activeEnrollment: PokerPairingEnrollment? = null
    private var enrollmentExpiryJob: Job? = null
    private val bindingSendMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        pairingIdentity = AndroidKeystorePairingIdentity()
        pairing = pairingIdentity.pairingController(this)
        enrollmentAdvertiser = PokerEnrollmentNsdAdvertiser(this)
        val pokerScheduler = CoroutinePokerScheduler(serviceScope)
        PokerBindingRuntime.attachService { serviceScope.launch { sendBindingState() } }
        PokerSnapshotRuntime.clearForRestart()
        PokerPresentationRuntime.initialize(this)
        PokerSnapshotRuntime.initializeUnread(
            this,
            runCatching {
                pairing.pinnedPeerPublicKey?.let { peer ->
                    pokerPairingFingerprint(pairingIdentity.publicKey, peer)
                }
            }.getOrNull(),
        )
        PokerSnapshotRuntime.attachForegroundRequester {
            PokerForegroundWake.request(this)
        }
        PokerSnapshotRuntime.attachDiagnosticsRequester {
            serviceScope.launch { sendClientDiagnostics() }
        }
        PokerPresentationRuntime.attachDiagnosticsRequester {
            serviceScope.launch { sendClientDiagnostics() }
        }
        pokerSnapshotHandler = PokerSnapshotConnectionHandler(
            role = PokerSnapshotRole.POKER,
            installer = PokerSnapshotInstaller(),
            onInstalled = PokerSnapshotRuntime::install,
            scheduler = pokerScheduler,
            scope = serviceScope,
        )
        owner = PokerConnectionOwner(
            factory = AndroidPokerListenerFactory(this, pairingIdentity, pairing),
            scope = serviceScope,
            localOffer = PokerProtocolOffer(
                major = POKER_PROTOCOL_MAJOR,
                capabilities = setOf(
                    POKER_BINDINGS_CAPABILITY,
                    POKER_SNAPSHOT_CAPABILITY,
                    POKER_LIVE_DELTA_CAPABILITY,
                    POKER_PRIMARY_ACTION_CAPABILITY,
                    POKER_PHOTO_CAPABILITY,
                    POKER_MORSE_CAPABILITY,
                    com.code2hack.pokerdealer.protocol.POKER_ASR_CAPABILITY,
                    POKER_FONT_SCALE_CAPABILITY,
                    POKER_DIAGNOSTICS_CAPABILITY,
                ),
            ),
            scheduler = pokerScheduler,
            clock = PokerClock { System.currentTimeMillis() },
            reconnect = PokerReconnectController(),
            onConnected = { _, negotiation ->
                if (negotiation.access == PokerProtocolAccess.READ_WRITE &&
                    negotiation.supports(POKER_BINDINGS_CAPABILITY)
                ) {
                    sendBindingState()
                }
                sendClientDiagnostics()
            },
            onStateChanged = { state ->
                if (state != com.code2hack.pokerdealer.protocol.PokerConnectionState.CONNECTED) {
                    PokerBindingRuntime.notifyConnectionLost()
                    PokerAsrBridge.connectionLost()
                }
            },
            onEnvelope = { _, envelope ->
                PokerComposerBridge.receive(envelope)
                PokerAsrBridge.receive(envelope)
                handleBindingEnvelope(envelope)
                handlePresentationEnvelope(envelope)
            },
            callbacks = pokerSnapshotHandler,
        )
        PokerComposerBridge.attach { type, payload, requireWritable ->
            owner.send(type, payload, requireWritable = requireWritable)
        }
        PokerPairingRuntime.publish(pairing.status)
        PokerAsrBridge.attach { type, payload, requireWritable ->
            owner.send(type, payload, requireWritable = requireWritable)
        }
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISABLE -> {
                setEnabled(this, false)
                owner.stop()
                stopEnrollmentServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
                PokerPairingRuntime.publish(pairing.status)
                stopSelfResult(startId)
                return START_NOT_STICKY
            }

            ACTION_ENABLE -> {
                setEnabled(this, true)
                startForegroundCompat()
                startConfiguredRuntime()
            }

            ACTION_OPEN_ENROLLMENT -> {
                setEnabled(this, true)
                startForegroundCompat()
                openEnrollment(intent.getBooleanExtra(EXTRA_REPLACEMENT, false))
            }

            ACTION_RETRY -> if (isEnabled(this)) {
                startForegroundCompat()
                if (pairing.status.state == PokerPairingState.PAIRED && owner.isRunning) {
                    owner.retry(PokerReconnectTrigger.MANUAL_RETRY)
                } else startConfiguredRuntime()
            } else {
                stopSelfResult(startId)
                return START_NOT_STICKY
            }

            null -> if (isEnabled(this)) {
                startForegroundCompat()
                startConfiguredRuntime()
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
        PokerAsrBridge.detach()
        PokerSnapshotRuntime.detachForegroundRequester()
        PokerSnapshotRuntime.detachDiagnosticsRequester()
        PokerPresentationRuntime.detachDiagnosticsRequester()
        stopEnrollmentServer()
        owner.stop()
        PokerBindingRuntime.notifyConnectionLost()
        PokerBindingRuntime.detachService()
        PokerPairingRuntime.clear()
        serviceScope.cancel()
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startConfiguredRuntime() {
        when (pairing.status.state) {
            PokerPairingState.PAIRED -> owner.start()
            PokerPairingState.ENROLLMENT_OPEN -> activeEnrollment?.let(::startEnrollmentServer)
            PokerPairingState.UNPAIRED -> PokerPairingRuntime.publish(pairing.status)
        }
    }

    private fun openEnrollment(replacement: Boolean) {
        owner.stop()
        stopEnrollmentServer()
        runCatching {
            pairingIdentity.createForExplicitEnrollment()
            pairing.openEnrollment(
                nowMs = System.currentTimeMillis(),
                physicalEnrollmentConfirmed = true,
                physicalReplacementConfirmed = replacement,
            )
        }.onSuccess { enrollment ->
            activeEnrollment = enrollment
            scheduleEnrollmentExpiry(enrollment)
            startEnrollmentServer(enrollment)
        }.onFailure { failure ->
            PokerPairingRuntime.publishFailure(
                (failure as? com.code2hack.pokerdealer.protocol.PokerPairingRejected)?.reason
                    ?: PokerPairingFailure.KEYSTORE_INVALID,
            )
        }
    }

    private fun startEnrollmentServer(enrollment: PokerPairingEnrollment) {
        if (pairingServer != null) return
        val server = PokerEnrollmentServer(
            addressProvider = { activeWifiAddress(this) },
            enrollment = enrollment,
            pairing = pairing,
            scope = serviceScope,
            nowMs = System::currentTimeMillis,
            onFailure = { reason, attempts ->
                if (pairing.status.state == PokerPairingState.ENROLLMENT_OPEN) {
                    PokerPairingRuntime.publishEnrollment(
                        enrollment = enrollment,
                        failedAttempts = attempts,
                        failure = reason,
                    )
                    if (attempts >= com.code2hack.pokerdealer.protocol.POKER_PAIRING_MAX_ATTEMPTS) {
                        activeEnrollment = null
                        stopEnrollmentServer()
                    }
                } else {
                    PokerPairingRuntime.publish(pairing.status)
                }
            },
            onComplete = {
                activeEnrollment = null
                stopEnrollmentServer()
                enrollmentExpiryJob?.cancel()
                enrollmentExpiryJob = null
                PokerPairingRuntime.publish(pairing.status)
                owner.start()
            },
        )
        runCatching {
            server.start()
            pairingServer = server
            PokerPairingRuntime.publishEnrollment(enrollment)
            enrollmentAdvertiser.register {
                serviceScope.launch {
                    if (activeEnrollment?.challenge?.challengeId == enrollment.challenge.challengeId) {
                        pairing.cancelEnrollment(PokerPairingFailure.ENROLLMENT_DISCOVERY_FAILED)
                        activeEnrollment = null
                        enrollmentExpiryJob?.cancel()
                        enrollmentExpiryJob = null
                        stopEnrollmentServer()
                        PokerPairingRuntime.publishFailure(PokerPairingFailure.ENROLLMENT_DISCOVERY_FAILED)
                    }
                }
            }
        }.onFailure {
            server.stop()
            PokerPairingRuntime.publishFailure(PokerPairingFailure.INVALID_ENDPOINT)
        }
    }

    private fun stopEnrollmentServer() {
        enrollmentAdvertiser.unregister()
        pairingServer?.stop()
        pairingServer = null
    }

    private fun scheduleEnrollmentExpiry(enrollment: PokerPairingEnrollment) {
        enrollmentExpiryJob?.cancel()
        enrollmentExpiryJob = serviceScope.launch {
            delay((enrollment.challenge.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0L))
            if (pairing.closeExpiredEnrollment(System.currentTimeMillis())) {
                activeEnrollment = null
                stopEnrollmentServer()
                PokerPairingRuntime.publish(pairing.status)
            }
        }
    }

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
        if (!isEnabled(this)) return
        if (pairing.status.state == PokerPairingState.ENROLLMENT_OPEN) {
            stopEnrollmentServer()
            activeEnrollment?.let(::startEnrollmentServer)
        } else {
            owner.retry(PokerReconnectTrigger.NETWORK_CHANGE)
        }
    }

    private suspend fun sendBindingState() {
        if (!owner.isConnected) return
        bindingSendMutex.withLock {
            if (!owner.isConnected) return@withLock
            sendBindingStateLocked()
        }
    }

    private suspend fun sendClientDiagnostics() {
        if (!owner.isConnected) return
        owner.send(
            POKER_DIAGNOSTICS_TYPE,
            PokerDiagnosticsProtocol.payload(
                PokerClientDiagnostics(
                    unreadCount = PokerSnapshotRuntime.unreadCount.value,
                    wakeCapability = PokerForegroundWake.capability(this),
                    font = PokerPresentationRuntime.fontScale.value,
                ),
            ),
            requireWritable = false,
        )
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

    private suspend fun handlePresentationEnvelope(
        envelope: com.code2hack.pokerdealer.protocol.ProtocolEnvelope,
    ) {
        when (envelope.type) {
            POKER_FONT_SCALE_TYPE -> {
                val candidate = runCatching { PokerFontScaleProtocol.decodeUpdate(envelope) }
                    .getOrNull() ?: return
                val result = PokerPresentationRuntime.install(candidate)
                owner.send(
                    POKER_FONT_SCALE_ACK_TYPE,
                    PokerFontScaleProtocol.acknowledgementPayload(
                        PokerPresentationRuntime.fontScale.value,
                        result,
                    ),
                    replyTo = envelope.messageId,
                    requireWritable = false,
                )
                sendClientDiagnostics()
            }

            POKER_TRANSIENT_NOTICE_TYPE -> {
                runCatching { PokerTransientNoticeProtocol.decode(envelope) }
                    .onSuccess(PokerNoticeRuntime::show)
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
        const val ACTION_OPEN_ENROLLMENT = "com.code2hack.poker.action.OPEN_ENROLLMENT"
        const val EXTRA_REPLACEMENT = "replacement"
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

        fun openEnrollment(context: Context, replacement: Boolean) {
            setEnabled(context, true)
            start(
                context,
                intentFor(context.packageName, launchSpec(ACTION_OPEN_ENROLLMENT, replacement)),
            )
        }

        fun disable(context: Context) {
            setEnabled(context, false)
            context.stopService(Intent(context, PokerListenerService::class.java))
        }

        fun retry(context: Context) {
            resume(context)
        }

        fun resume(context: Context) {
            if (activityResumeAction(isEnabled(context)) != null) {
                start(context, ACTION_RETRY)
            }
        }

        internal fun activityResumeAction(enabled: Boolean): String? =
            ACTION_RETRY.takeIf { enabled }

        internal fun activityStartAction(enabled: Boolean): String =
            if (enabled) ACTION_RETRY else ACTION_ENABLE

        internal data class LaunchSpec(val action: String, val replacement: Boolean = false)

        internal fun launchSpec(action: String, replacement: Boolean = false): LaunchSpec =
            LaunchSpec(action, replacement)

        internal fun intentFor(packageName: String, spec: LaunchSpec): Intent = Intent()
            .setClassName(packageName, PokerListenerService::class.java.name)
            .setAction(spec.action)
            .putExtra(EXTRA_REPLACEMENT, spec.replacement)

        private fun start(context: Context, action: String) {
            start(context, intentFor(context.packageName, launchSpec(action)))
        }

        private fun start(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
