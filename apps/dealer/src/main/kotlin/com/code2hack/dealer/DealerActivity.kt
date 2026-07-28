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
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ControlSurface
import com.code2hack.pokerdealer.domain.InitialCodexHosts
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
                        onTakeControl = { hostId, threadId -> service?.takeControl(hostId, threadId) },
                        onYieldControl = { hostId, threadId -> service?.yieldControl(hostId, threadId) },
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
    onTakeControl: (String, String) -> Unit,
    onYieldControl: (String, String) -> Unit,
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
    var turnText by remember { mutableStateOf("") }
    var confirmTailnetReset by remember { mutableStateOf(false) }
    val privateKeyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(onPrivateKey)
    }
    val knownHostsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(onKnownHosts)
    }
    val locator = CodexThreadLocator(selectedHostId, threadId.trim())
    val selectedHost = InitialCodexHosts.all.single { it.id == selectedHostId }
    val isTermux = selectedHost == InitialCodexHosts.fold6Termux
    val validRoute = if (isTermux) {
        loopbackSshPort.toIntOrNull()?.let { it in 1..65_535 } == true
    } else {
        lanHost.isNotBlank() || tailnetHost.isNotBlank()
    }
    val currentControlSurface = state.control
        ?.takeIf { it.locator == locator }
        ?.surface
        ?: ControlSurface.NONE
    val hasDealerControl = currentControlSurface == ControlSurface.DEALER
    val canRun = setup.serviceReady &&
        !state.running &&
        hasDealerControl &&
        validRoute &&
        sshUser.isNotBlank() &&
        threadId.isNotBlank() &&
        turnText.isNotBlank() &&
        setup.privateKeyLoaded &&
        setup.knownHostsLoaded

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
                "${state.status.label} | ${state.route ?: "no active route"}",
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
            state.routeDiagnostics
                .distinctBy { it.route to it.failure to it.capability }
                .forEach {
                    Text(
                        "${it.route}: ${it.failure ?: if (it.attempted) "selected" else it.capability.name}",
                        color = if (it.failure == null) Color(0xFFBBC8D6) else Color(0xFFFFC38B),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            state.threadId?.let {
                Text(it, color = Color(0xFFBBC8D6), style = MaterialTheme.typography.labelSmall)
            }
            state.appServerVersion?.let {
                Text("app-server $it", color = Color(0xFFBBC8D6), style = MaterialTheme.typography.labelSmall)
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
                        enabled = !state.running && locator.threadId.isNotBlank(),
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
                value = turnText,
                onValueChange = { turnText = it },
                enabled = !state.running,
                label = { Text("Turn") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.running) {
                Button(onClick = onCancel) {
                    Text("Cancel")
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
                            turnText = turnText,
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
        }

        HorizontalDivider()
        DealerCards(state.cards, Modifier.weight(1f))
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
}

@Composable
private fun DealerCards(cards: List<Card>, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(cards, key = Card::id) { card ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    listOfNotNull(card.role.name, card.state.name, card.delivery?.name).joinToString(" | "),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF56616D),
                )
                Text(
                    card.fullText,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider()
        }
    }
}
