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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardSource
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ComposerAction
import com.code2hack.pokerdealer.domain.ControlSurface
import com.code2hack.pokerdealer.domain.DeliveryState
import com.code2hack.pokerdealer.domain.DiscoveredThread
import com.code2hack.pokerdealer.domain.InitialCodexHosts
import com.code2hack.pokerdealer.domain.PermissionPreset
import com.code2hack.pokerdealer.domain.ThreadStartSelection
import com.code2hack.pokerdealer.domain.composerAction
import com.code2hack.pokerdealer.protocol.appserver.HostSessionState
import com.code2hack.pokerdealer.protocol.appserver.HostSessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DealerActivity : ComponentActivity() {
    private val uiState = MutableStateFlow(DealerUiState())
    private val setupState = MutableStateFlow(DealerSetupState())
    private var privateKey: ByteArray? = null
    private var knownHosts: ByteArray? = null
    private var service: DealerConnectionService? = null
    private var serviceStateJob: Job? = null
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val connected = (binder as DealerConnectionService.LocalBinder).service
            service = connected
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
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by uiState.collectAsState()
                    val setup by setupState.collectAsState()
                    DealerApp(
                        state = state,
                        setup = setup,
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
                        onRenameThread = { locator, name -> service?.renameThread(locator, name) },
                        onBeginForkThread = { service?.beginForkThread(it) },
                        onBrowseThread = { service?.browseThread(it) },
                        onAttachThread = { service?.attachThread(it) },
                        onDetachThread = { service?.detachThread(it) },
                        onTakeControl = { hostId, threadId -> service?.takeControl(hostId, threadId) },
                        onYieldControl = { hostId, threadId -> service?.yieldControl(hostId, threadId) },
                        onDraftChange = { locator, text -> service?.updateDraft(locator, text) },
                        onSubmit = { service?.submitDraft(it) },
                        onInterrupt = { service?.interrupt(it) },
                        onStartTailnet = ::startEmbeddedTailnet,
                        onStopTailnet = { service?.stopEmbeddedTailnet() },
                        onResetTailnet = ::resetEmbeddedTailnet,
                        onLoginTailnet = ::openEmbeddedTailnetLogin,
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
        serviceStateJob?.cancel()
        serviceStateJob = null
        service = null
        if (bound) unbindService(serviceConnection)
        bound = false
        setupState.update { it.copy(serviceReady = false) }
        super.onStop()
    }

    override fun onDestroy() {
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
}

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
    onRenameThread: (CodexThreadLocator, String) -> Unit,
    onBeginForkThread: (CodexThreadLocator) -> Unit,
    onBrowseThread: (CodexThreadLocator) -> Unit,
    onAttachThread: (CodexThreadLocator) -> Unit,
    onDetachThread: (CodexThreadLocator) -> Unit,
    onTakeControl: (String, String) -> Unit,
    onYieldControl: (String, String) -> Unit,
    onDraftChange: (CodexThreadLocator, String) -> Unit,
    onSubmit: (CodexThreadLocator) -> Unit,
    onInterrupt: (CodexThreadLocator) -> Unit,
    onStartTailnet: () -> Unit,
    onStopTailnet: () -> Unit,
    onResetTailnet: () -> Unit,
    onLoginTailnet: (String) -> Unit,
) {
    var selectedHostId by remember(state.hostId) { mutableStateOf(state.hostId ?: "u4090") }
    var lanHost by remember { mutableStateOf("") }
    var tailnetHost by remember { mutableStateOf("") }
    var loopbackSshPort by remember { mutableStateOf("") }
    var sshUser by remember { mutableStateOf("") }
    var threadId by remember { mutableStateOf("") }
    var confirmTailnetReset by remember { mutableStateOf(false) }
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
            if (hostSession?.enabled == true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onDisableHost(selectedHostId) }) {
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
        }

        HorizontalDivider()
        DealerCards(
            state.cards.filter {
                state.browsedThread?.takeIf { browsed -> browsed.hostId == selectedHostId }?.let { browsed ->
                    it.conversationId == "${browsed.hostId}/${browsed.threadId}"
                } ?: (it.conversationId.substringBefore('/') == selectedHostId)
            },
            Modifier.weight(1f),
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
        NewThreadDialog(
            review = review,
            onReview = onReviewNewThread,
            onCreate = onCreateThread,
            onDismiss = onDismissNewThread,
        )
    }
}

@Composable
private fun NewThreadDialog(
    review: NewThreadUiState,
    onReview: (String, String) -> Unit,
    onCreate: (ThreadStartSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    var workingDirectory by remember(review.hostId, review.workingDirectory) {
        mutableStateOf(review.workingDirectory)
    }
    var providerOverride by remember(review.catalog) { mutableStateOf("") }
    var modelOverride by remember(review.catalog) { mutableStateOf("") }
    var reasoningEffort by remember(review.catalog) { mutableStateOf<String?>(null) }
    var permissionPreset by remember(review.catalog) { mutableStateOf(PermissionPreset.HOST_DEFAULT) }
    val catalog = review.catalog
    val selectedModel = modelOverride.ifBlank { catalog?.defaultModel.orEmpty() }
    val reasoningChoices = catalog?.models
        ?.singleOrNull { it.model == selectedModel }
        ?.reasoningEfforts
        .orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (review.sourceLocator == null) {
                    "New thread on ${review.hostId}"
                } else {
                    "Fork thread on ${review.hostId}"
                },
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Working directory")
                review.observedWorkingDirectories.forEach { path ->
                    OutlinedButton(
                        onClick = { workingDirectory = path },
                        enabled = !review.loading && !review.creating,
                    ) {
                        Text(path, fontFamily = FontFamily.Monospace)
                    }
                }
                OutlinedTextField(
                    value = workingDirectory,
                    onValueChange = { workingDirectory = it },
                    label = { Text("Absolute host path") },
                    singleLine = true,
                    enabled = !review.loading && !review.creating,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (catalog == null || catalog.workingDirectory != workingDirectory) {
                    Button(
                        onClick = { onReview(review.hostId, workingDirectory) },
                        enabled = !review.loading && !review.creating,
                    ) {
                        Text(if (review.loading) "Loading…" else "Review host settings")
                    }
                } else {
                    Text("Provider: inherit ${catalog.defaultProviderId ?: "host default"}")
                    catalog.providers.forEach { provider ->
                        OutlinedButton(
                            onClick = { providerOverride = provider.id },
                            enabled = !review.creating,
                        ) {
                            Text("${provider.label} (${provider.id})")
                        }
                    }
                    OutlinedTextField(
                        value = providerOverride,
                        onValueChange = { providerOverride = it },
                        label = { Text("Provider ID (blank inherits)") },
                        singleLine = true,
                        enabled = !review.creating,
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
                            enabled = !review.creating,
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
                        enabled = !review.creating,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (reasoningChoices.isNotEmpty()) {
                        Text("Reasoning effort: ${reasoningEffort ?: "inherit"}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { reasoningEffort = null },
                                enabled = !review.creating,
                            ) {
                                Text("Inherit")
                            }
                            reasoningChoices.forEach { effort ->
                                OutlinedButton(
                                    onClick = { reasoningEffort = effort },
                                    enabled = !review.creating,
                                ) {
                                    Text(effort)
                                }
                            }
                        }
                    }
                    Text("Permissions")
                    PermissionPreset.entries.forEach { preset ->
                        val unavailable = preset.unavailableReason(catalog.requirements)
                        OutlinedButton(
                            onClick = { permissionPreset = preset },
                            enabled = !review.creating && unavailable == null,
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
                review.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(
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
                    !review.loading &&
                    !review.creating,
            ) {
                Text(
                    when {
                        review.creating -> "Creating…"
                        review.sourceLocator != null -> "Fork thread"
                        else -> "Create empty thread"
                    },
                )
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !review.creating) {
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
) {
    var renaming by remember(thread.locator) { mutableStateOf(false) }
    var name by remember(thread.locator, thread.name) { mutableStateOf(thread.name.orEmpty()) }
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

internal fun DealerUiState.hasUnsettledAction(locator: CodexThreadLocator): Boolean = cards.any {
    it.conversationId == "${locator.hostId}/${locator.threadId}" &&
        it.delivery in setOf(DeliveryState.ACCEPTED, DeliveryState.UNKNOWN)
}

@Composable
private fun DealerCards(cards: List<Card>, modifier: Modifier = Modifier) {
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
    }
}
