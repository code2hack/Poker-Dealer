package com.code2hack.dealer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.code2hack.pokerdealer.domain.CodexHost
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ComposerDraft
import com.code2hack.pokerdealer.domain.InitialCodexHosts
import com.code2hack.pokerdealer.protocol.appserver.HostSessionManager
import com.code2hack.pokerdealer.protocol.appserver.InitializedHostSessionConnector
import com.code2hack.pokerdealer.protocol.appserver.RetainedCardStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Minimal foreground-service host for the retained Dealer ↔ Codex core. */
internal class DealerCoreService : Service() {
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.Default)
    private val binder = LocalBinder()
    private lateinit var threadStore: DealerThreadAttachmentStore
    private lateinit var core: DealerCore
    private val mutableOperation = MutableStateFlow("Starting Dealer core")
    val operation: StateFlow<String> = mutableOperation.asStateFlow()

    val state: StateFlow<DealerCoreState>
        get() = core.state

    inner class LocalBinder : Binder() {
        val service: DealerCoreService
            get() = this@DealerCoreService
    }

    override fun onCreate() {
        super.onCreate()
        ensureForeground()
        val profiles = DealerHostConnectionProfileStore(this)
        val factory = DealerHostSessionFactory(profiles)
        val hostSessions = HostSessionManager(
            hostIds = InitialCodexHosts.all.map(CodexHost::id).toSet(),
            intentStore = HostConnectionIntentDataStore(this),
            connector = InitializedHostSessionConnector(factory::create),
            scope = scope,
        )
        threadStore = DealerThreadAttachmentStore(this)
        core = DealerCore(
            hostSessions = hostSessions,
            threadStore = threadStore,
            recoveryStore = DealerStateRecoveryStore(noBackupFilesDir.resolve("recovery")),
            retainedCardStore = RetainedCardStore(noBackupFilesDir.resolve("thread-cards")),
            scope = scope,
        )
        scope.launch {
            runCatching { core.start() }
                .onSuccess { mutableOperation.value = "Dealer core ready" }
                .onFailure { mutableOperation.value = "Dealer core failed: ${it.message}" }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        runBlocking {
            runCatching { core.close() }
            runCatching { threadStore.close() }
        }
        scope.cancel()
        super.onDestroy()
    }

    fun setHostEnabled(hostId: String, enabled: Boolean) {
        launchOperation(if (enabled) "Enable $hostId" else "Disable $hostId") {
            core.setHostEnabled(hostId, enabled)
        }
    }

    fun refreshHost(hostId: String) {
        launchOperation("Refresh $hostId") { core.refreshThreads(hostId) }
    }

    fun attachAndTakeControl(hostId: String, threadId: String) {
        val locator = locator(hostId, threadId) ?: return
        launchOperation("Attach ${locator.threadId}") {
            check(core.attachThread(locator, takeControl = true)) {
                core.state.value.error ?: "Attach failed"
            }
        }
    }

    fun submitDraft(hostId: String, threadId: String, text: String) {
        val locator = locator(hostId, threadId) ?: return
        launchOperation("Submit ${locator.threadId}") {
            check(core.takeControl(locator)) { core.state.value.error ?: "Unable to take control" }
            check(core.editDraft(locator, ComposerDraft.fromText(text))) {
                core.state.value.error ?: "Unable to retain draft"
            }
            mutableOperation.value = "Submit outcome: ${core.submitDraft(locator)}"
        }
    }

    fun interrupt(hostId: String, threadId: String) {
        val locator = locator(hostId, threadId) ?: return
        launchOperation("Interrupt ${locator.threadId}") {
            mutableOperation.value = "Interrupt outcome: ${core.interrupt(locator)}"
        }
    }

    private fun locator(hostId: String, threadId: String): CodexThreadLocator? {
        if (hostId.isBlank() || threadId.isBlank()) {
            mutableOperation.value = "Host ID and thread ID are required"
            return null
        }
        return CodexThreadLocator(hostId.trim(), threadId.trim())
    }

    private fun launchOperation(label: String, block: suspend () -> Unit) {
        mutableOperation.value = "$label…"
        scope.launch {
            runCatching { block() }
                .onSuccess {
                    if (mutableOperation.value == "$label…") mutableOperation.value = "$label complete"
                }
                .onFailure { mutableOperation.value = "$label failed: ${it.message}" }
        }
    }

    private fun ensureForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Dealer Codex connection",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, DealerDiagnosticsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Dealer")
            .setContentText("Codex host connections and recovery are active")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val NOTIFICATION_CHANNEL = "dealer-codex"
        const val NOTIFICATION_ID = 1701
    }
}
