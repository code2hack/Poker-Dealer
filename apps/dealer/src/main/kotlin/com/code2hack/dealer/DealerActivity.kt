package com.code2hack.dealer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.code2hack.dealer.asr.DealerAsrCatalogStore
import com.code2hack.dealer.asr.DealerAsrCatalogEntry
import com.code2hack.dealer.asr.DealerAsrCatalogUiState
import com.code2hack.dealer.asr.DealerAsrDownloadState
import com.code2hack.dealer.asr.DealerAsrDownloadUiState
import com.code2hack.dealer.asr.DealerAsrDownloadManager
import com.code2hack.dealer.asr.DealerAsrPackKey
import com.code2hack.dealer.asr.DealerAsrMode
import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardSource
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.CommandApprovalDecision
import com.code2hack.pokerdealer.domain.CommandApprovalRequest
import com.code2hack.pokerdealer.domain.ComposerAction
import com.code2hack.pokerdealer.domain.ControlSurface
import com.code2hack.pokerdealer.domain.DeliveryState
import com.code2hack.pokerdealer.domain.DiscoveredThread
import com.code2hack.pokerdealer.domain.FileApprovalDecision
import com.code2hack.pokerdealer.domain.FileApprovalRequest
import com.code2hack.pokerdealer.domain.InitialCodexHosts
import com.code2hack.pokerdealer.domain.PermissionPreset
import com.code2hack.pokerdealer.domain.PokerBindingDevice
import com.code2hack.pokerdealer.domain.PokerOperation
import com.code2hack.pokerdealer.domain.ThreadStartCatalog
import com.code2hack.pokerdealer.domain.ThreadLifecycleAction
import com.code2hack.pokerdealer.domain.ThreadStartSelection
import com.code2hack.pokerdealer.domain.ThreadWorkState
import com.code2hack.pokerdealer.domain.RequestResolutionState
import com.code2hack.pokerdealer.domain.ServerRequestLocator
import com.code2hack.pokerdealer.domain.UserInputOutcome
import com.code2hack.pokerdealer.domain.UserInputAnswerEdit
import com.code2hack.pokerdealer.domain.UserInputAnswerBuffer
import com.code2hack.pokerdealer.domain.UserInputRequest
import com.code2hack.pokerdealer.domain.composerAction
import com.code2hack.pokerdealer.protocol.appserver.HostSessionState
import com.code2hack.pokerdealer.protocol.appserver.HostSessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class DealerActivity : ComponentActivity() {
    private val uiState = MutableStateFlow(DealerUiState())
    private val setupState = MutableStateFlow(DealerSetupState())
    private val asrCatalogState = MutableStateFlow(DealerAsrCatalogUiState())
    private val asrDownloadState = MutableStateFlow(DealerAsrDownloadUiState())
    private var privateKey: ByteArray? = null
    private var knownHosts: ByteArray? = null
    private var service: DealerConnectionService? = null
    private lateinit var asrCatalogStore: DealerAsrCatalogStore
    private lateinit var asrDownloadManager: DealerAsrDownloadManager
    private var serviceStateJob: Job? = null
    private var asrCatalogJob: Job? = null
    private var asrDownloadJob: Job? = null
    private var pendingThreadNotificationKey: String? = null
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val connected = (binder as DealerConnectionService.LocalBinder).service
            service = connected
            pendingThreadNotificationKey?.let {
                if (connected.openThreadNotification(it)) pendingThreadNotificationKey = null
            }
            connected.setActivityVisible(true)
            setupState.update { it.copy(serviceReady = true, error = null) }
            serviceStateJob?.cancel()
            serviceStateJob = lifecycleScope.launch {
                connected.state.collect { uiState.value = it }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceStateJob?.cancel()
            service = null
            setupState.update { it.copy(serviceReady = false) }
            uiState.update {
                if (it.running) it.copy(status = DealerRunState.DISCONNECTED, route = null) else it
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openThreadNotification(intent)
        asrCatalogStore = DealerAsrCatalogStore(this)
        asrDownloadManager = DealerAsrDownloadManager(this)
        asrCatalogJob = lifecycleScope.launch {
            val loaded = asrCatalogStore.load()
            asrCatalogState.value = DealerAsrCatalogUiState(
                catalog = loaded.catalog,
                error = loaded.error,
            )
        }
        asrDownloadJob = lifecycleScope.launch {
            asrDownloadManager.start()
            asrDownloadManager.stateFlow.collect { asrDownloadState.value = it }
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by uiState.collectAsState()
                    val setup by setupState.collectAsState()
                    val asrCatalog by asrCatalogState.collectAsState()
                    val asrDownloads by asrDownloadState.collectAsState()
                    DealerApp(
                        state = state,
                        setup = setup,
                        asrCatalog = asrCatalog,
                        asrDownloads = asrDownloads,
                        onPrivateKey = { loadCredential(it, CredentialKind.PRIVATE_KEY) },
                        onKnownHosts = { loadCredential(it, CredentialKind.KNOWN_HOSTS) },
                        onRun = ::runM1,
                        onCancel = { service?.cancelRun() },
                        onEnableHost = ::enableHost,
                        onDisableHost = { service?.disableHost(it) },
                        onRefreshThreads = { service?.refreshThreads(it) },
                        onBeginNewThread = { service?.beginNewThread(it) },
                        onReviewNewThread = { hostId, cwd -> service?.reviewNewThread(hostId, cwd) },
                        onCreateThread = { service?.createThread(it) },
                        onDismissNewThread = { service?.dismissNewThread() },
                        onReviewResumeThread = { locator, cwd ->
                            service?.reviewResumeThread(locator, cwd)
                        },
                        onSetResumeControlClaim = { service?.setResumeControlClaim(it) },
                        onResumeThread = { service?.resumeThread(it) },
                        onDismissResumeThread = { service?.dismissResumeThread() },
                        onRenameThread = { locator, name -> service?.renameThread(locator, name) },
                        onBeginForkThread = { service?.beginForkThread(it) },
                        onBeginThreadLifecycle = { action, locator ->
                            service?.beginThreadLifecycle(action, locator)
                        },
                        onConfirmThreadLifecycle = { service?.confirmThreadLifecycle() },
                        onDismissThreadLifecycle = { service?.dismissThreadLifecycle() },
                        onRestoreThread = { service?.restoreThread(it) },
                        onBrowseThread = { service?.browseThread(it) },
                        onAttachThread = { service?.attachThread(it) },
                        onDetachThread = { service?.detachThread(it) },
                        onTakeControl = { hostId, threadId -> service?.takeControl(hostId, threadId) },
                        onYieldControl = { hostId, threadId -> service?.yieldControl(hostId, threadId) },
                        onDraftChange = { locator, text -> service?.updateDraft(locator, text) },
                        onSubmit = { service?.submitDraft(it) },
                        onInterrupt = { service?.interrupt(it) },
                        onCommandApproval = { locator, decision ->
                            service?.resolveCommandApproval(locator, decision)
                        },
                        onUserInputEdit = { locator, questionId, edit ->
                            service?.updateUserInputAnswer(locator, questionId, edit)
                        },
                        onUserInput = { locator -> service?.resolveUserInput(locator) },
                        onUserInputNoAnswer = { service?.resolveUserInputWithoutAnswer(it) },
                        onFileApproval = { locator, decision ->
                            service?.resolveFileApproval(locator, decision)
                        },
                        onStartTailnet = ::startEmbeddedTailnet,
                        onStopTailnet = { service?.stopEmbeddedTailnet() },
                        onResetTailnet = ::resetEmbeddedTailnet,
                        onLoginTailnet = ::openEmbeddedTailnetLogin,
                        onSelectPokerBindingDevice = { service?.selectPokerBindingDevice(it) },
                        onBeginPokerBinding = { service?.beginPokerBinding(it) },
                        onRemovePokerBinding = { service?.removePokerBinding(it) },
                        onResetPokerGlassesDefaults = { service?.resetPokerGlassesDefaults() },
                        onClearPokerRemote = { service?.clearPokerRemote() },
                        onRefreshAsrCatalog = ::refreshAsrCatalog,
                        onQueueAsrPack = { entry ->
                            lifecycleScope.launch { asrDownloadManager.queue(entry) }
                        },
                        onPauseAsrPack = { key ->
                            lifecycleScope.launch { asrDownloadManager.pause(key) }
                        },
                        onResumeAsrPack = { key ->
                            lifecycleScope.launch { asrDownloadManager.resume(key) }
                        },
                        onCancelAsrPack = { key ->
                            lifecycleScope.launch { asrDownloadManager.cancel(key) }
                        },
                        onSetDefaultAsrPack = { key ->
                            lifecycleScope.launch { asrDownloadManager.setDefault(key) }
                        },
                        onSetAsrMirror = { value ->
                            lifecycleScope.launch {
                                runCatching { asrDownloadManager.setMirrorBaseUrl(value) }
                                    .onFailure { asrDownloadState.update { it.copy(error = "mirror-url-invalid") } }
                            }
                        },
                    )
                }
            }
        }
        requestNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(
            Intent(this, DealerConnectionService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onStop() {
        service?.setActivityVisible(false)
        serviceStateJob?.cancel()
        serviceStateJob = null
        service = null
        if (bound) unbindService(serviceConnection)
        bound = false
        setupState.update { it.copy(serviceReady = false) }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openThreadNotification(intent)
    }

    override fun onDestroy() {
        asrCatalogJob?.cancel()
        asrDownloadJob?.cancel()
        if (::asrDownloadManager.isInitialized) asrDownloadManager.close()
        privateKey?.fill(0)
        knownHosts?.fill(0)
        super.onDestroy()
    }

    private fun loadCredential(uri: Uri, kind: CredentialKind) {
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Unable to open selected file")
                }
                require(bytes.isNotEmpty()) { "Selected file is empty" }
                when (kind) {
                    CredentialKind.PRIVATE_KEY -> {
                        privateKey?.fill(0)
                        privateKey = bytes
                    }
                    CredentialKind.KNOWN_HOSTS -> {
                        knownHosts?.fill(0)
                        knownHosts = bytes
                    }
                }
                setupState.update {
                    it.copy(
                        privateKeyLoaded = privateKey != null,
                        knownHostsLoaded = knownHosts != null,
                        error = null,
                    )
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                setupState.update { it.copy(error = failure.message ?: failure::class.java.simpleName) }
            }
        }
    }

    private fun runM1(config: DealerRunConfig) {
        val connected = service
        val key = privateKey?.copyOf()
        val pins = knownHosts?.copyOf()
        if (connected == null || key == null || pins == null) {
            key?.fill(0)
            pins?.fill(0)
            return
        }
        try {
            startForegroundService(Intent(this, DealerConnectionService::class.java))
            connected.runM1(config, key, pins)
        } catch (failure: Throwable) {
            key.fill(0)
            pins.fill(0)
            setupState.update { it.copy(error = failure.message ?: failure::class.java.simpleName) }
        }
    }

    private fun enableHost(config: DealerHostConnectionConfig) {
        val key = privateKey?.copyOf() ?: return
        val pins = knownHosts?.copyOf() ?: return
        val connected = service
        if (connected == null) {
            key.fill(0)
            pins.fill(0)
            return
        }
        startForegroundService(Intent(this, DealerConnectionService::class.java))
        connected.enableHost(config, key, pins)
    }

    private fun startEmbeddedTailnet() {
        startForegroundService(
            Intent(this, DealerConnectionService::class.java)
                .setAction(DealerConnectionService.ACTION_START_TAILNET),
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    private fun openEmbeddedTailnetLogin(loginUrl: String) {
        try {
            val uri = Uri.parse(loginUrl)
            require(uri.scheme == "https") { "Tailnet login URL must use HTTPS" }
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (failure: Throwable) {
            setupState.update { it.copy(error = failure.message ?: failure::class.java.simpleName) }
        }
    }

    private fun resetEmbeddedTailnet() {
        startForegroundService(
            Intent(this, DealerConnectionService::class.java)
                .setAction(DealerConnectionService.ACTION_RESET_TAILNET),
        )
    }

    private fun refreshAsrCatalog() {
        if (asrCatalogState.value.refreshing) return
        asrCatalogJob?.cancel()
        asrCatalogState.update { it.copy(refreshing = true, error = null) }
        asrCatalogJob = lifecycleScope.launch {
            val result = asrCatalogStore.refresh()
            asrCatalogState.value = DealerAsrCatalogUiState(
                catalog = result.catalog,
                error = result.error,
            )
        }
    }

    private fun openThreadNotification(intent: Intent?) {
        val key = intent?.getStringExtra(DealerConnectionService.EXTRA_THREAD_NOTIFICATION_KEY) ?: return
        pendingThreadNotificationKey = key
        service?.let {
            if (it.openThreadNotification(key)) pendingThreadNotificationKey = null
        }
    }
}

internal const val DEALER_RECOVERY_LOSS_BOUNDARY =
    "Dealer recovery is private to this phone and excluded from backup. Uninstall, Clear data, " +
        "factory reset, storage failure, or phone loss removes Dealer-only drafts and uncertain " +
        "actions; host-retained threads remain rediscoverable."

private enum class CredentialKind {
    PRIVATE_KEY,
    KNOWN_HOSTS,
}

private data class DealerSetupState(
    val privateKeyLoaded: Boolean = false,
    val knownHostsLoaded: Boolean = false,
    val serviceReady: Boolean = false,
    val error: String? = null,
)

@Composable
private fun DealerApp(
    state: DealerUiState,
    setup: DealerSetupState,
    asrCatalog: DealerAsrCatalogUiState,
    asrDownloads: DealerAsrDownloadUiState,
    onPrivateKey: (Uri) -> Unit,
    onKnownHosts: (Uri) -> Unit,
    onRun: (DealerRunConfig) -> Unit,
    onCancel: () -> Unit,
    onEnableHost: (DealerHostConnectionConfig) -> Unit,
    onDisableHost: (String) -> Unit,
    onRefreshThreads: (String) -> Unit,
    onBeginNewThread: (String) -> Unit,
    onReviewNewThread: (String, String) -> Unit,
    onCreateThread: (ThreadStartSelection) -> Unit,
    onDismissNewThread: () -> Unit,
    onReviewResumeThread: (CodexThreadLocator, String) -> Unit,
    onSetResumeControlClaim: (Boolean) -> Unit,
    onResumeThread: (ThreadStartSelection) -> Unit,
    onDismissResumeThread: () -> Unit,
    onRenameThread: (CodexThreadLocator, String) -> Unit,
    onBeginForkThread: (CodexThreadLocator) -> Unit,
    onBeginThreadLifecycle: (ThreadLifecycleAction, CodexThreadLocator) -> Unit,
    onConfirmThreadLifecycle: () -> Unit,
    onDismissThreadLifecycle: () -> Unit,
    onRestoreThread: (CodexThreadLocator) -> Unit,
    onBrowseThread: (CodexThreadLocator) -> Unit,
    onAttachThread: (CodexThreadLocator) -> Unit,
    onDetachThread: (CodexThreadLocator) -> Unit,
    onTakeControl: (String, String) -> Unit,
    onYieldControl: (String, String) -> Unit,
    onDraftChange: (CodexThreadLocator, String) -> Unit,
    onSubmit: (CodexThreadLocator) -> Unit,
    onInterrupt: (CodexThreadLocator) -> Unit,
    onCommandApproval: (ServerRequestLocator, CommandApprovalDecision) -> Unit,
    onUserInputEdit: (ServerRequestLocator, String, UserInputAnswerEdit) -> Unit,
    onUserInput: (ServerRequestLocator) -> Unit,
    onUserInputNoAnswer: (ServerRequestLocator) -> Unit,
    onFileApproval: (ServerRequestLocator, FileApprovalDecision) -> Unit,
    onStartTailnet: () -> Unit,
    onStopTailnet: () -> Unit,
    onResetTailnet: () -> Unit,
    onLoginTailnet: (String) -> Unit,
    onSelectPokerBindingDevice: (PokerBindingDevice) -> Unit,
    onBeginPokerBinding: (PokerOperation) -> Unit,
    onRemovePokerBinding: (PokerOperation) -> Unit,
    onResetPokerGlassesDefaults: () -> Unit,
    onClearPokerRemote: () -> Unit,
    onRefreshAsrCatalog: () -> Unit,
    onQueueAsrPack: (DealerAsrCatalogEntry) -> Unit,
    onPauseAsrPack: (DealerAsrPackKey) -> Unit,
    onResumeAsrPack: (DealerAsrPackKey) -> Unit,
    onCancelAsrPack: (DealerAsrPackKey) -> Unit,
    onSetDefaultAsrPack: (DealerAsrPackKey) -> Unit,
    onSetAsrMirror: (String?) -> Unit,
) {
    var selectedHostId by remember(state.browsedThread?.hostId, state.hostId) {
        mutableStateOf(state.browsedThread?.hostId ?: state.hostId ?: "u4090")
    }
    var lanHost by remember { mutableStateOf("") }
    var tailnetHost by remember { mutableStateOf("") }
    var loopbackSshPort by remember { mutableStateOf("") }
    var sshUser by remember { mutableStateOf("") }
    var threadId by remember { mutableStateOf("") }
    var confirmTailnetReset by remember { mutableStateOf(false) }
    var confirmDisconnectHostId by remember { mutableStateOf<String?>(null) }
    val privateKeyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(onPrivateKey)
    }
    val knownHostsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(onKnownHosts)
    }
    val locator = state.browsedThread
        ?.takeIf { it.hostId == selectedHostId }
        ?: CodexThreadLocator(selectedHostId, threadId.trim())
    val selectedHost = InitialCodexHosts.all.single { it.id == selectedHostId }
    val isTermux = selectedHost == InitialCodexHosts.fold6Termux
    val validRoute = if (isTermux) {
        loopbackSshPort.toIntOrNull()?.let { it in 1..65_535 } == true
    } else {
        lanHost.isNotBlank() || tailnetHost.isNotBlank()
    }
    val currentControlSurface = if (state.threadAttachments.hasDealerClaim(locator)) {
        ControlSurface.DEALER
    } else {
        ControlSurface.NONE
    }
    val hasDealerControl = currentControlSurface == ControlSurface.DEALER
    val hasUnsettledAction = state.hasUnsettledAction(locator)
    val thread = state.threads[locator]
    val composerAction = thread?.workState.composerAction()
    val draft = state.threadActions.drafts[locator].orEmpty()
    val pendingInput = state.threadActions.pendingInputs[locator]
    val hostSession = state.hostSessions[selectedHostId]
    val enabledHostSession = hostSession?.takeIf(HostSessionState::enabled)
    val selectedLegacyRun = state.hostId == selectedHostId
    val canRun = setup.serviceReady &&
        !state.running &&
        hostSession?.enabled != true &&
        !hasUnsettledAction &&
        hasDealerControl &&
        validRoute &&
        sshUser.isNotBlank() &&
        threadId.isNotBlank() &&
        draft.isNotBlank() &&
        setup.privateKeyLoaded &&
        setup.knownHostsLoaded
    val canSubmit = setup.serviceReady &&
        enabledHostSession?.status == HostSessionStatus.CONNECTED &&
        hasDealerControl &&
        locator in state.threadAttachments.attached &&
        composerAction != ComposerAction.BLOCKED &&
        (composerAction != ComposerAction.STEER || thread?.activeTurnId != null) &&
        pendingInput == null &&
        draft.isNotBlank()
    val canInterrupt = setup.serviceReady &&
        enabledHostSession?.status == HostSessionStatus.CONNECTED &&
        hasDealerControl &&
        thread?.activeTurnId != null &&
        locator !in state.threadActions.pendingInterrupts
    val canEnableHost = setup.serviceReady &&
        hostSession?.enabled != true &&
        validRoute &&
        sshUser.isNotBlank() &&
        setup.privateKeyLoaded &&
        setup.knownHostsLoaded
    val discoveredThreads = state.threads.values
        .filter { it.locator.hostId == selectedHostId }
        .sortedWith(
            compareBy<DiscoveredThread> { it.archived }
                .thenByDescending { it.updatedAtSeconds ?: Long.MIN_VALUE },
        )

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF20252B))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                selectedHost.displayName,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Text(
                "${enabledHostSession?.status ?: if (selectedLegacyRun) state.status.label else "Disconnected"} | " +
                    "${enabledHostSession?.route ?: state.route?.takeIf { selectedLegacyRun } ?: "no active route"}",
                color = if (state.error == null && setup.error == null) Color(0xFF8EE7B2) else Color(0xFFFFA8A8),
            )
            Text(
                if (isTermux) {
                    "Android/Termux ARM64 | community distribution | opportunistic"
                } else {
                    "Route order: LAN > embedded tsnet > external Tailscale"
                },
                color = Color(0xFFBBC8D6),
                style = MaterialTheme.typography.labelMedium,
            )
            if (isTermux) {
                Text(
                    "Route: loopback SSH only",
                    color = Color(0xFFBBC8D6),
                    style = MaterialTheme.typography.labelMedium,
                )
            } else {
                Text(
                    "SSH_EMBEDDED_TSNET: ${state.tailnet.connectionLabel}",
                    color = if (state.tailnet.state == EmbeddedTailnetState.ERROR) {
                        Color(0xFFFFA8A8)
                    } else {
                        Color(0xFFBBC8D6)
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
                state.tailnet.error?.let {
                    Text(it, color = Color(0xFFFFA8A8), style = MaterialTheme.typography.labelSmall)
                }
                state.tailnet.nodeName?.let {
                    Text("Node: $it", color = Color(0xFFBBC8D6), style = MaterialTheme.typography.labelSmall)
                }
                state.tailnet.health.forEach {
                    Text(it, color = Color(0xFFFFC38B), style = MaterialTheme.typography.labelSmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onStartTailnet,
                        enabled = !state.tailnet.active,
                    ) {
                        Text("Start tailnet")
                    }
                    OutlinedButton(
                        onClick = onStopTailnet,
                        enabled = state.tailnet.active &&
                            state.tailnet.state !in setOf(
                                EmbeddedTailnetState.STOPPING,
                                EmbeddedTailnetState.RESETTING,
                            ),
                    ) {
                        Text("Stop tailnet")
                    }
                    OutlinedButton(
                        onClick = { confirmTailnetReset = true },
                        enabled = state.tailnet.state !in setOf(
                            EmbeddedTailnetState.STOPPING,
                            EmbeddedTailnetState.RESETTING,
                        ),
                    ) {
                        Text("Reset identity")
                    }
                    state.tailnet.loginUrl?.let { loginUrl ->
                        OutlinedButton(onClick = { onLoginTailnet(loginUrl) }) {
                            Text("Log in")
                        }
                    }
                }
            }
            (enabledHostSession?.diagnostics ?: state.routeDiagnostics.takeIf { selectedLegacyRun }.orEmpty())
                .distinctBy { it.route to it.failure to it.capability }
                .forEach {
                    Text(
                        "${it.route}: ${it.failure ?: if (it.attempted) "selected" else it.capability.name}",
                        color = if (it.failure == null) Color(0xFFBBC8D6) else Color(0xFFFFC38B),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            state.threadId?.takeIf { selectedLegacyRun }?.let {
                Text(it, color = Color(0xFFBBC8D6), style = MaterialTheme.typography.labelSmall)
            }
            state.appServerVersion?.takeIf { selectedLegacyRun }?.let {
                Text("app-server $it", color = Color(0xFFBBC8D6), style = MaterialTheme.typography.labelSmall)
            }
            state.recovery?.takeIf { selectedLegacyRun }?.let {
                Text(
                    buildString {
                        append("Recovery: ").append(it.phase)
                        if (it.failedAttempt != null && it.maxAttempts != null) {
                            append(" | attempt ").append(it.failedAttempt).append('/').append(it.maxAttempts)
                        }
                        if (it.retryInMs != null) append(" | retry in ").append(it.retryInMs).append("ms")
                    },
                    color = Color(0xFFFFC38B),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(it.action, color = Color(0xFFFFC38B), style = MaterialTheme.typography.labelSmall)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { selectedHostId = "spark" },
                    enabled = !state.running && selectedHostId != "spark",
                ) {
                    Text("DGX Spark")
                }
                OutlinedButton(
                    onClick = { selectedHostId = "u4090" },
                    enabled = !state.running && selectedHostId != "u4090",
                ) {
                    Text("u4090")
                }
                OutlinedButton(
                    onClick = { selectedHostId = "fold6-termux" },
                    enabled = !state.running && selectedHostId != "fold6-termux",
                ) {
                    Text("Fold6 Termux")
                }
            }
            PokerBindingsPanel(
                state = state.pokerBindings,
                connected = state.pokerConnected,
                onSelectDevice = onSelectPokerBindingDevice,
                onBeginBinding = onBeginPokerBinding,
                onRemoveBinding = onRemovePokerBinding,
                onResetGlassesDefaults = onResetPokerGlassesDefaults,
                onClearRemote = onClearPokerRemote,
            )
            if (hostSession?.enabled == true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (state.commandApprovals.unresolved(selectedHostId).isEmpty() &&
                                state.fileApprovals.unresolved(selectedHostId).isEmpty()
                            ) {
                                onDisableHost(selectedHostId)
                            } else {
                                confirmDisconnectHostId = selectedHostId
                            }
                        },
                    ) {
                        Text("Disconnect host")
                    }
                    OutlinedButton(
                        onClick = { onRefreshThreads(selectedHostId) },
                        enabled = hostSession.status == HostSessionStatus.CONNECTED &&
                            selectedHostId !in state.refreshingThreadHosts,
                    ) {
                        Text(if (selectedHostId in state.refreshingThreadHosts) "Refreshing…" else "Refresh threads")
                    }
                    Button(
                        onClick = { onBeginNewThread(selectedHostId) },
                        enabled = hostSession.status == HostSessionStatus.CONNECTED,
                    ) {
                        Text("New thread")
                    }
                }
                hostSession.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            } else {
                Button(
                    onClick = {
                        onEnableHost(
                            DealerHostConnectionConfig(
                                hostId = selectedHostId,
                                lanHost = lanHost.trim(),
                                tailnetHost = tailnetHost.trim(),
                                sshUser = sshUser.trim(),
                                loopbackSshPort = loopbackSshPort.toIntOrNull() ?: 0,
                            ),
                        )
                    },
                    enabled = canEnableHost,
                ) {
                    Text("Connect host")
                }
            }
            state.threadDiscoveryErrors[selectedHostId]?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            DealerAsrCatalogPanel(
                state = asrCatalog,
                downloads = asrDownloads,
                onRefresh = onRefreshAsrCatalog,
                onQueue = onQueueAsrPack,
                onPause = onPauseAsrPack,
                onResume = onResumeAsrPack,
                onCancel = onCancelAsrPack,
                onSetDefault = onSetDefaultAsrPack,
                onSetMirror = onSetAsrMirror,
            )
            discoveredThreads.forEach { thread ->
                ThreadRow(
                    thread = thread,
                    onBrowseThread = onBrowseThread,
                    onAttachThread = onAttachThread,
                    onDetachThread = onDetachThread,
                    onTakeControl = onTakeControl,
                    onYieldControl = onYieldControl,
                    onRenameThread = onRenameThread,
                    onBeginForkThread = onBeginForkThread,
                    descendantFilterQualified = hostSession?.descendantFilterQualified == true,
                    appServerVersion = hostSession?.appServerVersion,
                    lifecycleBusy = state.lifecycleReview != null,
                    onBeginThreadLifecycle = onBeginThreadLifecycle,
                    onRestoreThread = onRestoreThread,
                )
            }
            if (isTermux) {
                OutlinedTextField(
                    value = loopbackSshPort,
                    onValueChange = { loopbackSshPort = it },
                    enabled = !state.running,
                    label = { Text("Termux loopback SSH port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = lanHost,
                    onValueChange = { lanHost = it },
                    enabled = !state.running,
                    label = { Text("LAN host") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tailnetHost,
                    onValueChange = { tailnetHost = it },
                    enabled = !state.running,
                    label = { Text("Tailnet host") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = sshUser,
                onValueChange = { sshUser = it },
                enabled = !state.running,
                label = { Text("SSH user") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = threadId,
                onValueChange = { threadId = it },
                enabled = !state.running,
                label = { Text("Thread ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasDealerControl) {
                    OutlinedButton(
                        onClick = { onYieldControl(locator.hostId, locator.threadId) },
                        enabled = !state.running,
                    ) {
                        Text("Yield to local TUI")
                    }
                } else {
                    Button(
                        onClick = { onTakeControl(locator.hostId, locator.threadId) },
                        enabled = !state.running && locator in state.threadAttachments.attached,
                    ) {
                        Text("Take control")
                    }
                }
                Text(
                    "Control: $currentControlSurface",
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { privateKeyPicker.launch(arrayOf("*/*")) },
                    enabled = !state.running,
                ) {
                    Text(
                        if (setup.privateKeyLoaded) {
                            "SSH key selected"
                        } else if (isTermux) {
                            "Select dedicated Termux SSH key"
                        } else {
                            "Select SSH key"
                        },
                    )
                }
                OutlinedButton(
                    onClick = { knownHostsPicker.launch(arrayOf("text/*", "*/*")) },
                    enabled = !state.running,
                ) {
                    Text(if (setup.knownHostsLoaded) "Host key selected" else "Select host key")
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { onDraftChange(locator, it) },
                enabled = !state.running && locator.threadId.isNotBlank(),
                label = { Text("Draft") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.running) {
                Button(onClick = onCancel) {
                    Text("Cancel")
                }
            } else if (enabledHostSession != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onSubmit(locator) },
                        enabled = canSubmit,
                    ) {
                        Text(composerAction.label)
                    }
                    OutlinedButton(
                        onClick = { onInterrupt(locator) },
                        enabled = canInterrupt,
                    ) {
                        Text(if (locator in state.threadActions.pendingInterrupts) "Interrupting…" else "Interrupt")
                    }
                }
                if (composerAction == ComposerAction.BLOCKED) {
                    Text(
                        if (thread?.workState == null) {
                            "Reconcile thread state before sending."
                        } else {
                            "Resolve the pending request or interrupt the turn before sending."
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (pendingInput?.uncertain == true) {
                    Text(
                        "Input acceptance is unknown; Dealer will not submit this draft again until reconciliation.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Button(
                    onClick = {
                        val config = DealerRunConfig(
                            hostId = selectedHostId,
                            lanHost = lanHost.trim(),
                            tailnetHost = tailnetHost.trim(),
                            sshUser = sshUser.trim(),
                            threadId = threadId.trim(),
                            turnText = draft,
                            loopbackSshPort = loopbackSshPort.toIntOrNull() ?: 0,
                        )
                        onRun(config)
                    },
                    enabled = canRun,
                ) {
                    Text("Run turn")
                }
            }
            setup.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (hasUnsettledAction) {
                Text(
                    "Restore the host and reconcile this accepted/unknown action before sending another turn.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                DEALER_RECOVERY_LOSS_BOUNDARY,
                color = Color(0xFF56616D),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        HorizontalDivider()
        val browsed = state.browsedThread?.takeIf { it.hostId == selectedHostId }
        fun requestVisible(request: ServerRequestLocator, thread: CodexThreadLocator): Boolean =
            (state.browsedRequest == null || request == state.browsedRequest) &&
                (browsed?.let { thread == it } ?: (thread.hostId == selectedHostId))
        DealerCards(
            state.cards.filter {
                state.browsedRequest == null &&
                    (browsed?.let { thread -> it.conversationId == "${thread.hostId}/${thread.threadId}" }
                        ?: (it.conversationId.substringBefore('/') == selectedHostId))
            },
            state.commandApprovals.requests.values.filter { requestVisible(it.locator, it.thread) },
            state.userInputRequests.requests.values.filter { requestVisible(it.locator, it.thread) },
            state.userInputAnswers,
            state.fileApprovals.requests.values.filter { requestVisible(it.locator, it.thread) },
            state.threadAttachments.dealerClaims,
            onCommandApproval,
            onUserInputEdit,
            onUserInput,
            onUserInputNoAnswer,
            onFileApproval,
            Modifier.weight(1f),
        )
    }
    confirmDisconnectHostId?.let { hostId ->
        val affectedCommands = state.commandApprovals.unresolved(hostId)
        val affectedFiles = state.fileApprovals.unresolved(hostId)
        val affectedQuestions = state.userInputRequests.unresolved(hostId)
        AlertDialog(
            onDismissRequest = { confirmDisconnectHostId = null },
            title = { Text("Disconnect $hostId?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Dealer will cancel each request at most once where supported, wait briefly, then disconnect.")
                    affectedCommands.forEach { request ->
                        Text(request.disconnectScope(), fontFamily = FontFamily.Monospace)
                    }
                    affectedFiles.forEach { request ->
                        Text(request.disconnectScope(), fontFamily = FontFamily.Monospace)
                    }
                    affectedQuestions.forEach { request ->
                        Text(request.disconnectScope(), fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDisconnectHostId = null
                        onDisableHost(hostId)
                    },
                ) {
                    Text("Cancel requests and disconnect")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmDisconnectHostId = null }) {
                    Text("Keep connected")
                }
            },
        )
    }
    if (confirmTailnetReset) {
        AlertDialog(
            onDismissRequest = { confirmTailnetReset = false },
            title = { Text("Reset tailnet identity?") },
            text = {
                Text("Dealer will close its tailnet connections and require a new login. Hosts and threads stay configured.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmTailnetReset = false
                        onResetTailnet()
                    },
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmTailnetReset = false }) {
                    Text("Cancel")
                }
            },
        )
    }
    state.newThread?.let { review ->
        ThreadSettingsDialog(
            hostId = review.hostId,
            observedWorkingDirectories = review.observedWorkingDirectories,
            initialWorkingDirectory = review.workingDirectory,
            catalog = review.catalog,
            loading = review.loading,
            submitting = review.creating,
            error = review.error,
            title = if (review.sourceLocator == null) {
                "New thread on ${review.hostId}"
            } else {
                "Fork thread on ${review.hostId}"
            },
            confirmText = if (review.sourceLocator == null) "Create empty thread" else "Fork thread",
            onReview = { onReviewNewThread(review.hostId, it) },
            onConfirm = onCreateThread,
            onDismiss = onDismissNewThread,
        )
    }
    state.resumeThread?.let { review ->
        ThreadSettingsDialog(
            hostId = review.locator.hostId,
            observedWorkingDirectories = review.observedWorkingDirectories,
            initialWorkingDirectory = review.workingDirectory,
            catalog = review.catalog,
            loading = review.loading,
            submitting = review.resuming,
            error = review.error,
            title = "Resume ${review.locator.threadId}",
            confirmText = "Attach",
            workState = state.threads[review.locator]?.workState,
            requireControlClaimForOverrides = true,
            controlClaimed = review.controlClaimed,
            onControlClaimChange = onSetResumeControlClaim,
            onReview = { onReviewResumeThread(review.locator, it) },
            onConfirm = onResumeThread,
            onDismiss = onDismissResumeThread,
        )
    }
    state.lifecycleReview?.let {
        ThreadLifecycleDialog(
            review = it,
            onConfirm = onConfirmThreadLifecycle,
            onDismiss = onDismissThreadLifecycle,
        )
    }
}

@Composable
private fun DealerAsrCatalogPanel(
    state: DealerAsrCatalogUiState,
    downloads: DealerAsrDownloadUiState,
    onRefresh: () -> Unit,
    onQueue: (DealerAsrCatalogEntry) -> Unit,
    onPause: (DealerAsrPackKey) -> Unit,
    onResume: (DealerAsrPackKey) -> Unit,
    onCancel: (DealerAsrPackKey) -> Unit,
    onSetDefault: (DealerAsrPackKey) -> Unit,
    onSetMirror: (String?) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var selectedLanguage by remember { mutableStateOf<String?>(null) }
    var selectedMode by remember { mutableStateOf<DealerAsrMode?>(null) }
    var showCatalog by remember { mutableStateOf(false) }
    var mirrorUrl by remember(downloads.mirrorBaseUrl) {
        mutableStateOf(downloads.mirrorBaseUrl.orEmpty())
    }
    val languages = state.catalog.entries
        .flatMap { it.languages }
        .distinct()
        .sorted()
    val entries = state.catalog.filtered(search, selectedLanguage, selectedMode)
    val activeKeys = downloads.jobs
        .filter { it.state != DealerAsrDownloadState.FAILED }
        .map { it.key }
        .toSet()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF3F5F7))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ASR model catalog", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { showCatalog = !showCatalog }) {
                Text(if (showCatalog) "Close catalog" else "+ Add pack")
            }
            OutlinedButton(onClick = onRefresh, enabled = !state.refreshing) {
                Text(if (state.refreshing) "Updating…" else "Update")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = mirrorUrl,
                onValueChange = { mirrorUrl = it },
                label = { Text("Optional HTTPS mirror base") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { onSetMirror(mirrorUrl.takeIf(String::isNotBlank)) }) {
                Text("Save")
            }
        }
        downloads.error?.let {
            Text("Download state: $it", color = MaterialTheme.colorScheme.error)
        }
        downloads.jobs.forEach { job ->
            var menuExpanded by remember(job.key) { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .clickable(
                        enabled = job.state in setOf(
                            DealerAsrDownloadState.QUEUED,
                            DealerAsrDownloadState.DOWNLOADING,
                            DealerAsrDownloadState.PAUSED,
                        ),
                    ) {
                        if (job.state == DealerAsrDownloadState.PAUSED) onResume(job.key) else onPause(job.key)
                    }
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(job.displayName)
                when (job.state) {
                    DealerAsrDownloadState.READY -> {
                        Text(if (job.key == downloads.defaultPack) "Ready · default" else "Ready")
                        job.warning?.let {
                            Text(it, color = Color(0xFF9A5B00), style = MaterialTheme.typography.labelSmall)
                        }
                        if (job.key != downloads.defaultPack) {
                            OutlinedButton(onClick = { onSetDefault(job.key) }) {
                                Text("Use as default")
                            }
                        }
                    }
                    DealerAsrDownloadState.FAILED -> {
                        Text("Failed: ${job.error ?: "download-failed"}", color = MaterialTheme.colorScheme.error)
                        job.warning?.let {
                            Text(it, color = Color(0xFF9A5B00), style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(onClick = { onResume(job.key) }) { Text("Retry") }
                    }
                    DealerAsrDownloadState.QUEUED,
                    DealerAsrDownloadState.DOWNLOADING,
                    DealerAsrDownloadState.PAUSED,
                    -> {
                        LinearProgressIndicator(
                            progress = { job.progressFraction },
                            color = Color(0xFF2E9B57),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${job.percentage}% · ${job.etaMillis?.let { DateUtils.formatElapsedTime(it / 1000) } ?: "ETA …"} · " +
                                (job.currentSource ?: "queued"),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        job.warning?.let {
                            Text(it, color = Color(0xFF9A5B00), style = MaterialTheme.typography.labelSmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (job.state == DealerAsrDownloadState.PAUSED) {
                                OutlinedButton(onClick = { onResume(job.key) }) { Text("Resume") }
                            } else {
                                OutlinedButton(onClick = { onPause(job.key) }) { Text("Pause") }
                            }
                            Box {
                                OutlinedButton(onClick = { menuExpanded = true }) { Text("⋮") }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Cancel") },
                                        onClick = {
                                            menuExpanded = false
                                            onCancel(job.key)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showCatalog) {
            Text("Add a compatible model pack", style = MaterialTheme.typography.titleSmall)
        }
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Search name, family, or language") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { selectedLanguage = null }) {
                Text(if (selectedLanguage == null) "Language: all" else "Language")
            }
            languages.forEach { language ->
                OutlinedButton(onClick = { selectedLanguage = language }) {
                    Text(if (selectedLanguage == language) "Language: $language" else language)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { selectedMode = null }) {
                Text(if (selectedMode == null) "Mode: all" else "Mode")
            }
            DealerAsrMode.entries.forEach { mode ->
                OutlinedButton(onClick = { selectedMode = mode }) {
                    Text(if (selectedMode == mode) "Mode: ${mode.name.lowercase()}" else mode.name.lowercase())
                }
            }
        }
        if (showCatalog) entries.filter { DealerAsrPackKey(it.id, it.revision) !in activeKeys }.forEach { entry ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(entry.displayName)
                Text(
                    "${entry.family} | ${entry.mode.name.lowercase()} | " +
                        entry.languages.joinToString(),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "Size: ${entry.installedBytes.asrDisplaySize()} | " +
                        "${entry.licenses.joinToString()} | ${entry.backend}",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "${entry.sourceRepository}@${entry.sourceRevision.take(12)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
                Button(onClick = { onQueue(entry) }) { Text("Queue") }
            }
        }
        if (showCatalog && entries.none { DealerAsrPackKey(it.id, it.revision) !in activeKeys }) {
            Text("No compatible model packs match the current filters.")
        }
        state.error?.let {
            Text("Catalog update failed: $it", color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun Long.asrDisplaySize(): String = when {
    this >= 1024L * 1024 * 1024 -> "%.1f GiB".format(Locale.ROOT, this / (1024.0 * 1024 * 1024))
    this >= 1024L * 1024 -> "%.1f MiB".format(Locale.ROOT, this / (1024.0 * 1024))
    else -> "$this B"
}

@Composable
private fun ThreadSettingsDialog(
    hostId: String,
    observedWorkingDirectories: List<String>,
    initialWorkingDirectory: String,
    catalog: ThreadStartCatalog?,
    loading: Boolean,
    submitting: Boolean,
    error: String?,
    title: String,
    confirmText: String,
    workState: ThreadWorkState? = null,
    requireControlClaimForOverrides: Boolean = false,
    controlClaimed: Boolean = false,
    onControlClaimChange: (Boolean) -> Unit = {},
    onReview: (String) -> Unit,
    onConfirm: (ThreadStartSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    var workingDirectory by remember(hostId, initialWorkingDirectory) {
        mutableStateOf(initialWorkingDirectory)
    }
    var providerOverride by remember(catalog) { mutableStateOf("") }
    var modelOverride by remember(catalog) { mutableStateOf("") }
    var reasoningEffort by remember(catalog) { mutableStateOf<String?>(null) }
    var permissionPreset by remember(catalog) { mutableStateOf(PermissionPreset.HOST_DEFAULT) }
    val controlOverridesEnabled = !requireControlClaimForOverrides ||
        (workState == ThreadWorkState.READY && controlClaimed)
    val selectedModel = modelOverride.ifBlank { catalog?.defaultModel.orEmpty() }
    val reasoningChoices = catalog?.models
        ?.singleOrNull { it.model == selectedModel }
        ?.reasoningEfforts
        .orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Working directory")
                observedWorkingDirectories.forEach { path ->
                    OutlinedButton(
                        onClick = { workingDirectory = path },
                        enabled = !loading && !submitting,
                    ) {
                        Text(path, fontFamily = FontFamily.Monospace)
                    }
                }
                OutlinedTextField(
                    value = workingDirectory,
                    onValueChange = { workingDirectory = it },
                    label = { Text("Absolute host path") },
                    singleLine = true,
                    enabled = !loading && !submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (catalog == null || catalog.workingDirectory != workingDirectory) {
                    Button(
                        onClick = { onReview(workingDirectory) },
                        enabled = !loading && !submitting,
                    ) {
                        Text(if (loading) "Loading…" else "Review host settings")
                    }
                } else {
                    if (requireControlClaimForOverrides) {
                        OutlinedButton(
                            onClick = {
                                onControlClaimChange(!controlClaimed)
                                if (controlClaimed) {
                                    providerOverride = ""
                                    modelOverride = ""
                                    permissionPreset = PermissionPreset.HOST_DEFAULT
                                }
                            },
                            enabled = workState == ThreadWorkState.READY &&
                                !submitting,
                        ) {
                            Text(
                                if (controlClaimed) {
                                    "Dealer control selected"
                                } else {
                                    "Take Dealer control for overrides"
                                },
                            )
                        }
                        if (workState != ThreadWorkState.READY) {
                            Text("Provider, model, and permission overrides are read-only until READY.")
                        }
                    }
                    Text("Provider: inherit ${catalog.defaultProviderId ?: "host default"}")
                    catalog.providers.forEach { provider ->
                        OutlinedButton(
                            onClick = { providerOverride = provider.id },
                            enabled = !submitting && controlOverridesEnabled,
                        ) {
                            Text("${provider.label} (${provider.id})")
                        }
                    }
                    OutlinedTextField(
                        value = providerOverride,
                        onValueChange = { providerOverride = it },
                        label = { Text("Provider ID (blank inherits)") },
                        singleLine = true,
                        enabled = !submitting && controlOverridesEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Model: inherit ${catalog.defaultModel ?: "host default"}")
                    Text(
                        "Host catalog suggestions; app-server validates the provider/model combination.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    catalog.models.forEach { model ->
                        OutlinedButton(
                            onClick = {
                                modelOverride = model.model
                                reasoningEffort = null
                            },
                            enabled = !submitting && controlOverridesEnabled,
                        ) {
                            Text("${model.displayName} (${model.model})")
                        }
                    }
                    OutlinedTextField(
                        value = modelOverride,
                        onValueChange = {
                            modelOverride = it
                            reasoningEffort = null
                        },
                        label = { Text("Model wire value (blank inherits)") },
                        singleLine = true,
                        enabled = !submitting && controlOverridesEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Reasoning effort: ${reasoningEffort ?: "inherit " +
                            (catalog.defaultReasoningEffort ?: "host default")}",
                    )
                    if (reasoningChoices.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { reasoningEffort = null },
                                enabled = !submitting,
                            ) {
                                Text("Inherit")
                            }
                            reasoningChoices.forEach { effort ->
                                OutlinedButton(
                                    onClick = { reasoningEffort = effort },
                                    enabled = !submitting,
                                ) {
                                    Text(effort)
                                }
                            }
                        }
                    }
                    Text(
                        "Permissions: inherit " + listOfNotNull(
                            catalog.defaultSandbox,
                            catalog.defaultApprovalPolicy,
                            catalog.defaultApprovalsReviewer,
                        ).joinToString(" / ").ifEmpty { "host default" },
                    )
                    PermissionPreset.entries.forEach { preset ->
                        val unavailable = preset.unavailableReason(catalog.requirements)
                        OutlinedButton(
                            onClick = { permissionPreset = preset },
                            enabled = !submitting &&
                                unavailable == null &&
                                (preset == PermissionPreset.HOST_DEFAULT || controlOverridesEnabled),
                        ) {
                            Text(
                                buildString {
                                    if (permissionPreset == preset) append("Selected: ")
                                    append(preset.label)
                                    unavailable?.let { append(" — ").append(it) }
                                },
                            )
                        }
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        ThreadStartSelection(
                            workingDirectory = workingDirectory,
                            providerOverride = providerOverride,
                            modelOverride = modelOverride,
                            reasoningEffort = reasoningEffort,
                            permissionPreset = permissionPreset,
                        ),
                    )
                },
                enabled = catalog?.workingDirectory == workingDirectory &&
                    !loading &&
                    !submitting,
            ) {
                Text(if (submitting) "Working…" else confirmText)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !submitting) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ThreadRow(
    thread: DiscoveredThread,
    onBrowseThread: (CodexThreadLocator) -> Unit,
    onAttachThread: (CodexThreadLocator) -> Unit,
    onDetachThread: (CodexThreadLocator) -> Unit,
    onTakeControl: (String, String) -> Unit,
    onYieldControl: (String, String) -> Unit,
    onRenameThread: (CodexThreadLocator, String) -> Unit,
    onBeginForkThread: (CodexThreadLocator) -> Unit,
    descendantFilterQualified: Boolean,
    appServerVersion: String?,
    lifecycleBusy: Boolean,
    onBeginThreadLifecycle: (ThreadLifecycleAction, CodexThreadLocator) -> Unit,
    onRestoreThread: (CodexThreadLocator) -> Unit,
) {
    var renaming by remember(thread.locator) { mutableStateOf(false) }
    var name by remember(thread.locator, thread.name) { mutableStateOf(thread.name.orEmpty()) }
    val lifecycleUnavailable = when {
        !descendantFilterQualified ->
            "Archive/Delete unavailable: descendant filtering is not qualified for app-server " +
                (appServerVersion ?: "unknown")
        thread.workState != com.code2hack.pokerdealer.domain.ThreadWorkState.READY ->
            "Archive/Delete unavailable until the selected thread is READY"
        thread.ephemeral != false ->
            "Archive/Delete unavailable because ephemeral state is " +
                if (thread.ephemeral == true) "true" else "unknown"
        else -> null
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF3F5F7))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(thread.name ?: thread.preview ?: thread.locator.threadId)
        Text(
            listOfNotNull(
                thread.locator.hostId,
                thread.status,
                "loaded".takeIf { thread.loaded },
                "archived".takeIf { thread.archived },
                "attached".takeIf { thread.attached },
                "unread ${thread.unreadCount}".takeIf { thread.unreadCount > 0 },
                "control ${thread.intendedControlSurface}".takeIf {
                    thread.intendedControlSurface != ControlSurface.NONE
                },
            ).joinToString(" | "),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF56616D),
        )
        Text(thread.locator.threadId, style = MaterialTheme.typography.labelSmall)
        thread.workingDirectory?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        }
        thread.updatedAtSeconds?.let {
            Text(
                DateUtils.getRelativeTimeSpanString(it * 1_000).toString(),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onBrowseThread(thread.locator) }) {
                Text("Browse history")
            }
            OutlinedButton(onClick = { renaming = true }) {
                Text("Rename")
            }
            OutlinedButton(
                onClick = { onBeginForkThread(thread.locator) },
                enabled = thread.canFork(),
            ) {
                Text("Fork")
            }
            if (thread.attached) {
                OutlinedButton(onClick = { onDetachThread(thread.locator) }) {
                    Text("Detach")
                }
                if (thread.intendedControlSurface == ControlSurface.DEALER) {
                    OutlinedButton(
                        onClick = {
                            onYieldControl(thread.locator.hostId, thread.locator.threadId)
                        },
                    ) {
                        Text("Release control")
                    }
                } else {
                    Button(
                        onClick = {
                            onTakeControl(thread.locator.hostId, thread.locator.threadId)
                        },
                    ) {
                        Text("Take control")
                    }
                }
            } else if (!thread.archived) {
                Button(onClick = { onAttachThread(thread.locator) }) {
                    Text("Attach")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (thread.archived) {
                OutlinedButton(
                    onClick = { onRestoreThread(thread.locator) },
                    enabled = !lifecycleBusy,
                ) {
                    Text("Restore")
                }
            } else {
                OutlinedButton(
                    onClick = {
                        onBeginThreadLifecycle(ThreadLifecycleAction.ARCHIVE, thread.locator)
                    },
                    enabled = lifecycleUnavailable == null && !lifecycleBusy,
                ) {
                    Text("Archive")
                }
            }
            OutlinedButton(
                onClick = {
                    onBeginThreadLifecycle(ThreadLifecycleAction.DELETE, thread.locator)
                },
                enabled = lifecycleUnavailable == null && !lifecycleBusy,
            ) {
                Text("Delete")
            }
        }
        lifecycleUnavailable?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
    }
    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename thread") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Thread name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        renaming = false
                        onRenameThread(thread.locator, name)
                    },
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { renaming = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ThreadLifecycleDialog(
    review: ThreadLifecycleReviewUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val preflight = review.preflight
    val selected = preflight?.selected
    val action = when (review.action) {
        ThreadLifecycleAction.ARCHIVE -> "Archive"
        ThreadLifecycleAction.DELETE -> "Permanently delete"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$action thread?") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (review.loading) {
                    Text("Checking the complete active and archived descendant scope…")
                }
                selected?.let { thread ->
                    Text("Host: ${thread.locator.hostId}")
                    Text("Thread: ${thread.name ?: thread.preview ?: "(unnamed)"}")
                    Text("Thread ID: ${thread.locator.threadId}", fontFamily = FontFamily.Monospace)
                    Text(
                        "Working directory: ${thread.workingDirectory ?: "(unknown)"}",
                        fontFamily = FontFamily.Monospace,
                    )
                    Text("Descendants affected: ${preflight.descendants.size}")
                    if (preflight.descendants.isNotEmpty()) {
                        Text("This action cascades to spawned descendants.")
                    }
                    if (review.action == ThreadLifecycleAction.DELETE) {
                        Text(
                            "This permanently deletes the selected thread and affected descendants. " +
                                "It cannot be undone.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    preflight.blockingReason?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
                review.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = preflight?.eligible == true && !review.loading && !review.committing,
            ) {
                Text(if (review.committing) "$action…" else action)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !review.committing) {
                Text("Cancel")
            }
        },
    )
}

internal fun DealerUiState.hasUnsettledAction(locator: CodexThreadLocator): Boolean = cards.any {
    it.conversationId == "${locator.hostId}/${locator.threadId}" &&
        it.delivery in setOf(DeliveryState.ACCEPTED, DeliveryState.UNKNOWN)
}

@Composable
private fun DealerCards(
    cards: List<Card>,
    approvals: List<CommandApprovalRequest>,
    userInputs: List<UserInputRequest>,
    userInputAnswers: com.code2hack.pokerdealer.domain.UserInputAnswerState,
    fileApprovals: List<FileApprovalRequest>,
    dealerClaims: Set<CodexThreadLocator>,
    onCommandApproval: (ServerRequestLocator, CommandApprovalDecision) -> Unit,
    onUserInputEdit: (ServerRequestLocator, String, UserInputAnswerEdit) -> Unit,
    onUserInput: (ServerRequestLocator) -> Unit,
    onUserInputNoAnswer: (ServerRequestLocator) -> Unit,
    onFileApproval: (ServerRequestLocator, FileApprovalDecision) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(cards, key = Card::id) { card ->
            var expanded by remember(card.id) { mutableStateOf(false) }
            val structured = card.source == CardSource.CODEX_COMMAND ||
                card.source == CardSource.CODEX_FILE_CHANGE
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    listOfNotNull(
                        card.role.name,
                        card.state.name,
                        card.status,
                        card.turnOutcome?.name,
                        card.delivery?.name,
                    ).joinToString(" | "),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF56616D),
                )
                when (card.source) {
                    CardSource.CODEX_COMMAND -> {
                        Text(card.command ?: "Command unavailable", fontFamily = FontFamily.Monospace)
                        Text(
                            listOfNotNull(
                                card.workingDirectory,
                                card.exitCode?.let { "exit $it" },
                            ).joinToString(" | "),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    CardSource.CODEX_FILE_CHANGE -> {
                        Text(
                            "${card.fileChanges.size} affected path(s)",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        card.fileChanges.forEach {
                            Text("${it.kind}: ${it.path}", fontFamily = FontFamily.Monospace)
                        }
                    }
                    else -> Unit
                }
                if (!card.contentComplete) {
                    Text(
                        "Incomplete review material; approval is disabled.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                card.storageError?.let {
                    Text(
                        "Retention failed: $it",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (structured && card.fullText.isNotEmpty()) {
                    OutlinedButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Collapse" else "Expand complete content")
                    }
                }
                if (!structured || expanded) {
                    Text(
                        card.fullText,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            HorizontalDivider()
        }
        items(approvals, key = { "approval:${it.locator}" }) { request ->
            CommandApprovalCard(request, onCommandApproval)
            HorizontalDivider()
        }
        items(userInputs, key = { "user-input:${it.locator}" }) { request ->
            UserInputCard(
                request,
                userInputAnswers.buffer(request.locator),
                request.thread in dealerClaims,
                onUserInputEdit,
                onUserInput,
                onUserInputNoAnswer,
            )
            HorizontalDivider()
        }
        items(fileApprovals, key = { "file-approval:${it.locator}" }) { request ->
            FileApprovalCard(request, onFileApproval)
            HorizontalDivider()
        }
    }
}

@Composable
private fun CommandApprovalCard(
    request: CommandApprovalRequest,
    onDecision: (ServerRequestLocator, CommandApprovalDecision) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "COMMAND APPROVAL | ${request.resolution}",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF56616D),
        )
        request.scope.command?.let {
            Text(it, fontFamily = FontFamily.Monospace)
        }
        request.scope.workingDirectory?.let {
            Text("cwd: $it", fontFamily = FontFamily.Monospace)
        }
        if (request.scope.networkHost != null && request.scope.networkProtocol != null) {
            Text(
                "network: ${request.scope.networkProtocol}://${request.scope.networkHost}",
                fontFamily = FontFamily.Monospace,
            )
        }
        request.proposedExecpolicyAmendment?.let { amendment ->
            Text("Proposed execpolicy amendment (exact tokens):")
            amendment.forEachIndexed { index, token ->
                Text("[$index] $token", fontFamily = FontFamily.Monospace)
            }
        }
        Text(
            "turn ${request.turnId} | item ${request.itemId}" +
                request.approvalId?.let { " | approval $it" }.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
        )
        when (request.resolution) {
            RequestResolutionState.PENDING -> request.offeredDecisions.forEach { decision ->
                OutlinedButton(onClick = { onDecision(request.locator, decision) }) {
                    Text(decision.label())
                }
            }
            RequestResolutionState.RESPONDING ->
                Text("Sending decision; controls are locked.")
            RequestResolutionState.UNKNOWN ->
                Text(
                    "Decision acceptance is unknown; Dealer will not replay it.",
                    color = MaterialTheme.colorScheme.error,
                )
            RequestResolutionState.RESOLVED ->
                Text(
                    if (request.resolvedElsewhere) {
                        "Resolved elsewhere"
                    } else {
                        "Resolved: ${request.decision?.label() ?: "decision unavailable"}"
                    },
                )
        }
    }
}

@Composable
private fun UserInputCard(
    request: UserInputRequest,
    buffer: UserInputAnswerBuffer,
    canAnswer: Boolean,
    onEdit: (ServerRequestLocator, String, UserInputAnswerEdit) -> Unit,
    onAnswer: (ServerRequestLocator) -> Unit,
    onNoAnswer: (ServerRequestLocator) -> Unit,
) {
    val complete = buffer.isComplete(request)
    val remainingMs by produceState<Long?>(
        request.deadlineAtMs?.let {
            (it - System.currentTimeMillis()).coerceAtLeast(0)
        },
        request.deadlineAtMs,
        request.resolution,
    ) {
        val deadlineAtMs = request.deadlineAtMs ?: return@produceState
        while (request.resolution == RequestResolutionState.PENDING && value != 0L) {
            delay(1_000)
            value = (deadlineAtMs - System.currentTimeMillis()).coerceAtLeast(0)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "USER INPUT | ${request.resolution}",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF56616D),
        )
        remainingMs?.let {
            Text("Auto no-answer in ${(it + 999) / 1_000}s", style = MaterialTheme.typography.labelSmall)
        }
        request.questions.forEach { question ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(question.header, style = MaterialTheme.typography.labelMedium)
                Text(question.question)
                question.options?.forEach { option ->
                    Row {
                        RadioButton(
                            selected = buffer.answer(question.id).selectedOption == option.label,
                            onClick = {
                                onEdit(
                                    request.locator,
                                    question.id,
                                    UserInputAnswerEdit.SelectOption(option.label),
                                )
                            },
                            enabled = request.resolution == RequestResolutionState.PENDING,
                        )
                        Column {
                            Text(option.label)
                            if (option.description.isNotEmpty()) {
                                Text(option.description, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (question.options != null && question.isOther) {
                    Row {
                        RadioButton(
                            selected = buffer.answer(question.id).selectedOption == null,
                            onClick = {
                                onEdit(
                                    request.locator,
                                    question.id,
                                    UserInputAnswerEdit.SelectOther,
                                )
                            },
                            enabled = request.resolution == RequestResolutionState.PENDING,
                        )
                        Text("Other")
                    }
                }
                if (question.options == null ||
                    (question.options != null && question.isOther && buffer.answer(question.id).selectedOption == null)
                ) {
                    val value = buffer.answer(question.id).otherText
                    OutlinedTextField(
                        value = value,
                        onValueChange = {
                            onEdit(
                                request.locator,
                                question.id,
                                UserInputAnswerEdit.SetText(it),
                            )
                        },
                        label = { Text("Answer") },
                        enabled = request.resolution == RequestResolutionState.PENDING,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Text(
            "turn ${request.turnId} | item ${request.itemId}",
            style = MaterialTheme.typography.labelSmall,
        )
        when (request.resolution) {
            RequestResolutionState.PENDING -> {
                Button(
                    onClick = {
                        onAnswer(request.locator)
                    },
                    enabled = canAnswer && complete,
                ) {
                    Text("Send answers")
                }
                OutlinedButton(
                    onClick = {
                        onNoAnswer(request.locator)
                    },
                ) {
                    Text("No answer")
                }
                if (!canAnswer) {
                    Text("Take control to send entered answers.", style = MaterialTheme.typography.bodySmall)
                }
            }
            RequestResolutionState.RESPONDING ->
                Text("Sending response; controls are locked.")
            RequestResolutionState.UNKNOWN ->
                Text(
                    "Response acceptance is unknown; Dealer will not replay it.",
                    color = MaterialTheme.colorScheme.error,
                )
            RequestResolutionState.RESOLVED ->
                Text(
                    if (request.resolvedElsewhere) {
                        "Resolved elsewhere"
                    } else {
                        request.outcome?.label() ?: "Resolved"
                    },
                )
        }
    }
}

private fun CommandApprovalDecision.label(): String = when (this) {
    CommandApprovalDecision.ACCEPT -> "Accept once"
    CommandApprovalDecision.ACCEPT_FOR_SESSION -> "Accept for session"
    CommandApprovalDecision.ACCEPT_WITH_EXECPOLICY_AMENDMENT -> "Accept exact execpolicy amendment"
    CommandApprovalDecision.DECLINE -> "Decline"
    CommandApprovalDecision.CANCEL -> "Cancel turn"
}

private fun UserInputOutcome.label(): String = when (this) {
    UserInputOutcome.ANSWERED -> "Answered"
    UserInputOutcome.NO_ANSWER -> "No answer"
    UserInputOutcome.AUTO_RESOLVED -> "Auto-resolved with no answer"
}

@Composable
private fun FileApprovalCard(
    request: FileApprovalRequest,
    onDecision: (ServerRequestLocator, FileApprovalDecision) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "FILE APPROVAL | ${request.resolution}",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF56616D),
        )
        request.reason?.let { Text(it) }
        request.grantRoot?.let {
            Text("requested session root: $it", fontFamily = FontFamily.Monospace)
        }
        request.fileChanges.forEach { change ->
            Text("${change.kind}: ${change.path}", fontFamily = FontFamily.Monospace)
            Text(change.diff, fontFamily = FontFamily.Monospace)
        }
        Text(
            "turn ${request.turnId} | item ${request.itemId}",
            style = MaterialTheme.typography.labelSmall,
        )
        when {
            request.failureReason != null ->
                Text("Rejected safely: ${request.failureReason}", color = MaterialTheme.colorScheme.error)
            request.resolution == RequestResolutionState.PENDING && !request.reviewComplete ->
                Text("Restoring complete review material; controls are disabled.")
            request.resolution == RequestResolutionState.PENDING ->
                FileApprovalDecision.entries.forEach { decision ->
                    OutlinedButton(onClick = { onDecision(request.locator, decision) }) {
                        Text(decision.label())
                    }
                }
            request.resolution == RequestResolutionState.RESPONDING ->
                Text("Sending decision; controls are locked.")
            request.resolution == RequestResolutionState.UNKNOWN ->
                Text(
                    "Decision acceptance is unknown; Dealer will not replay it.",
                    color = MaterialTheme.colorScheme.error,
                )
            else ->
                Text(
                    if (request.resolvedElsewhere) {
                        "Resolved elsewhere"
                    } else {
                        "Resolved: ${request.decision?.label() ?: "decision unavailable"}"
                    },
                )
        }
    }
}

private fun FileApprovalDecision.label(): String = when (this) {
    FileApprovalDecision.ACCEPT -> "Accept once"
    FileApprovalDecision.ACCEPT_FOR_SESSION -> "Accept for session"
    FileApprovalDecision.DECLINE -> "Decline"
    FileApprovalDecision.CANCEL -> "Cancel turn"
}

private fun CommandApprovalRequest.disconnectScope(): String = buildString {
    append(thread.threadId).append(": ")
    append(scope.command ?: "network request")
    scope.workingDirectory?.let { append(" | cwd ").append(it) }
    if (scope.networkHost != null && scope.networkProtocol != null) {
        append(" | ").append(scope.networkProtocol).append("://").append(scope.networkHost)
    }
}

private fun UserInputRequest.disconnectScope(): String =
    "${thread.threadId}: ${questions.joinToString { it.header }}"

private fun FileApprovalRequest.disconnectScope(): String = buildString {
    append(thread.threadId).append(": ")
    append(fileChanges.joinToString { it.path }.ifEmpty { "file change (review incomplete)" })
}
