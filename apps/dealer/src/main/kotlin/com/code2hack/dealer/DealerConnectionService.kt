package com.code2hack.dealer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import com.code2hack.dealer.asr.DealerAsrProcess
import com.code2hack.dealer.asr.DealerAsrDownloadState
import com.code2hack.dealer.asr.DealerAsrSliceSession
import com.code2hack.dealer.asr.dealerAsrCursorAfter
import com.code2hack.dealer.asr.insertDealerAsrText
import com.code2hack.tailnet.embeddedtailnet.Engine
import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardRevisionStore
import com.code2hack.pokerdealer.domain.CardSource
import com.code2hack.pokerdealer.domain.CardState
import com.code2hack.pokerdealer.domain.CodexHost
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.CommandApprovalDecision
import com.code2hack.pokerdealer.domain.CommandApprovalRequest
import com.code2hack.pokerdealer.domain.CommandApprovalState
import com.code2hack.pokerdealer.domain.ComposerAction
import com.code2hack.pokerdealer.domain.ComposerEditResult
import com.code2hack.pokerdealer.domain.ComposerEditorState
import com.code2hack.pokerdealer.domain.ComposerDraft
import com.code2hack.pokerdealer.domain.ComposerElement
import com.code2hack.pokerdealer.domain.ComposerSurface
import com.code2hack.pokerdealer.domain.MorseMutationOutcome
import com.code2hack.pokerdealer.domain.MorseMutationKind
import com.code2hack.pokerdealer.domain.ControlSurface
import com.code2hack.pokerdealer.domain.DeliveryState
import com.code2hack.pokerdealer.domain.DiscoveredThread
import com.code2hack.pokerdealer.domain.FileApprovalDecision
import com.code2hack.pokerdealer.domain.FileApprovalState
import com.code2hack.pokerdealer.domain.HostConnectionRoute
import com.code2hack.pokerdealer.domain.InitialCodexHosts
import com.code2hack.pokerdealer.domain.PokerBindingController
import com.code2hack.pokerdealer.domain.PokerBindingDevice
import com.code2hack.pokerdealer.domain.PokerBindingInstallResult
import com.code2hack.pokerdealer.domain.PokerBindingState
import com.code2hack.pokerdealer.domain.PokerOperation
import com.code2hack.pokerdealer.domain.PokerPrimaryAction
import com.code2hack.pokerdealer.domain.ThreadStartCatalog
import com.code2hack.pokerdealer.domain.ThreadStartSelection
import com.code2hack.pokerdealer.domain.RevisionApplication
import com.code2hack.pokerdealer.domain.ThreadAttachmentState
import com.code2hack.pokerdealer.domain.ThreadActionState
import com.code2hack.pokerdealer.domain.ThreadCascadePreflight
import com.code2hack.pokerdealer.domain.ThreadLifecycleAction
import com.code2hack.pokerdealer.domain.ThreadWorkState
import com.code2hack.pokerdealer.domain.RequestResolutionState
import com.code2hack.pokerdealer.domain.ServerRequestLocator
import com.code2hack.pokerdealer.domain.UserInputOutcome
import com.code2hack.pokerdealer.domain.UserInputAnswerBuffer
import com.code2hack.pokerdealer.domain.UserInputAnswerEdit
import com.code2hack.pokerdealer.domain.UserInputAnswerState
import com.code2hack.pokerdealer.domain.UserInputRequest
import com.code2hack.pokerdealer.domain.UserInputRequestState
import com.code2hack.pokerdealer.protocol.appserver.M1OneHostDealerSlice
import com.code2hack.pokerdealer.protocol.appserver.M1ConnectionPhase
import com.code2hack.pokerdealer.protocol.appserver.M1FailurePhase
import com.code2hack.pokerdealer.protocol.appserver.M1RecoveryUpdate
import com.code2hack.pokerdealer.protocol.appserver.M1TurnOutcome
import com.code2hack.pokerdealer.protocol.appserver.M1TurnRecoveryException
import com.code2hack.pokerdealer.protocol.appserver.M1TurnInput
import com.code2hack.pokerdealer.protocol.appserver.AppServerTurnInput
import com.code2hack.pokerdealer.protocol.appserver.HostSessionConnectionConfig
import com.code2hack.pokerdealer.protocol.appserver.HostSessionManager
import com.code2hack.pokerdealer.protocol.appserver.HostSessionState
import com.code2hack.pokerdealer.protocol.appserver.HostSessionStatus
import com.code2hack.pokerdealer.protocol.appserver.HostThreadDiscovery
import com.code2hack.pokerdealer.protocol.appserver.HostThreadLifecycle
import com.code2hack.pokerdealer.protocol.appserver.HostThreadStartSettings
import com.code2hack.pokerdealer.protocol.appserver.InitializedHostSessionConnector
import com.code2hack.pokerdealer.protocol.appserver.AppServerThreadProjection
import com.code2hack.pokerdealer.protocol.appserver.AppServerStructuredCardProjection
import com.code2hack.pokerdealer.protocol.appserver.AppServerRequest
import com.code2hack.pokerdealer.protocol.appserver.CommandApprovalParseResult
import com.code2hack.pokerdealer.protocol.appserver.CommandApprovalProtocol
import com.code2hack.pokerdealer.protocol.appserver.CodexAppServerSession
import com.code2hack.pokerdealer.protocol.appserver.COMMAND_APPROVAL_METHOD
import com.code2hack.pokerdealer.protocol.appserver.FILE_APPROVAL_METHOD
import com.code2hack.pokerdealer.protocol.appserver.FileApprovalParseResult
import com.code2hack.pokerdealer.protocol.appserver.FileApprovalProtocol
import com.code2hack.pokerdealer.protocol.appserver.JsonRpcRemoteException
import com.code2hack.pokerdealer.protocol.appserver.TermuxCommunityCodexDaemon
import com.code2hack.pokerdealer.protocol.appserver.RetainedCardStore
import com.code2hack.pokerdealer.protocol.appserver.USER_INPUT_REQUEST_METHOD
import com.code2hack.pokerdealer.protocol.appserver.UpstreamCodexDaemon
import com.code2hack.pokerdealer.protocol.appserver.UserInputParseResult
import com.code2hack.pokerdealer.protocol.appserver.UserInputProtocol
import com.code2hack.pokerdealer.protocol.appserver.m1FailurePhase
import com.code2hack.pokerdealer.protocol.PokerClock
import com.code2hack.pokerdealer.protocol.ComposerMutationKind
import com.code2hack.pokerdealer.protocol.ComposerMutationOutcome
import com.code2hack.pokerdealer.protocol.ComposerMutationRequest
import com.code2hack.pokerdealer.protocol.ComposerMutationResult
import com.code2hack.pokerdealer.protocol.ComposerDraftProjection
import com.code2hack.pokerdealer.protocol.POKER_USER_INPUT_MUTATION_RESULT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_USER_INPUT_MUTATION_TYPE
import com.code2hack.pokerdealer.protocol.POKER_USER_INPUT_PROJECTION_TYPE
import com.code2hack.pokerdealer.protocol.POKER_APPROVAL_PROJECTION_TYPE
import com.code2hack.pokerdealer.protocol.POKER_PRIMARY_ACTION_CAPABILITY
import com.code2hack.pokerdealer.protocol.POKER_MORSE_CAPABILITY
import com.code2hack.pokerdealer.protocol.POKER_PRIMARY_ACTION_RESULT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_PRIMARY_ACTION_TYPE
import com.code2hack.pokerdealer.protocol.POKER_PHOTO_CAPABILITY
import com.code2hack.pokerdealer.protocol.POKER_PHOTO_CANCEL_TYPE
import com.code2hack.pokerdealer.protocol.POKER_PHOTO_CAPTURE_BEGIN_TYPE
import com.code2hack.pokerdealer.protocol.POKER_PHOTO_CAPTURE_CHUNK_TYPE
import com.code2hack.pokerdealer.protocol.POKER_PHOTO_CAPTURE_COMPLETE_TYPE
import com.code2hack.pokerdealer.protocol.POKER_PHOTO_CAPTURE_RESULT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_PHOTO_DELETE_RESULT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_PHOTO_DELETE_TYPE
import com.code2hack.pokerdealer.protocol.POKER_PHOTO_START_RESULT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_PHOTO_START_TYPE
import com.code2hack.pokerdealer.protocol.PhotoAssetCodec
import com.code2hack.pokerdealer.protocol.PhotoAssetTarget
import com.code2hack.pokerdealer.protocol.PhotoCaptureBegin
import com.code2hack.pokerdealer.protocol.PhotoCaptureChunk
import com.code2hack.pokerdealer.protocol.PhotoCaptureComplete
import com.code2hack.pokerdealer.protocol.PhotoCaptureOutcome
import com.code2hack.pokerdealer.protocol.PhotoCaptureResult
import com.code2hack.pokerdealer.protocol.PhotoDeleteResult
import com.code2hack.pokerdealer.protocol.PhotoStartOutcome
import com.code2hack.pokerdealer.protocol.PhotoStartResult
import com.code2hack.pokerdealer.protocol.PhotoStartTarget
import com.code2hack.pokerdealer.protocol.POKER_ASR_AUDIO_TYPE
import com.code2hack.pokerdealer.protocol.POKER_ASR_AVAILABILITY_TYPE
import com.code2hack.pokerdealer.protocol.POKER_ASR_CAPABILITY
import com.code2hack.pokerdealer.protocol.POKER_ASR_COMMIT_RESULT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_ASR_COMMIT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_ASR_DISCARD_RESULT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_ASR_DISCARD_TYPE
import com.code2hack.pokerdealer.protocol.POKER_ASR_EXIT_RESULT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_ASR_EXIT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_ASR_PROJECTION_TYPE
import com.code2hack.pokerdealer.protocol.POKER_ASR_START_RESULT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_ASR_START_TYPE
import com.code2hack.pokerdealer.protocol.PokerAsrAudioFrame
import com.code2hack.pokerdealer.protocol.PokerAsrAvailability
import com.code2hack.pokerdealer.protocol.PokerAsrCommitRequest
import com.code2hack.pokerdealer.protocol.PokerAsrCommitResult
import com.code2hack.pokerdealer.protocol.PokerAsrDiscardRequest
import com.code2hack.pokerdealer.protocol.PokerAsrDiscardResult
import com.code2hack.pokerdealer.protocol.PokerAsrExitRequest
import com.code2hack.pokerdealer.protocol.PokerAsrExitResult
import com.code2hack.pokerdealer.protocol.PokerAsrMutationOutcome
import com.code2hack.pokerdealer.protocol.PokerAsrProjection
import com.code2hack.pokerdealer.protocol.PokerAsrStartOutcome
import com.code2hack.pokerdealer.protocol.PokerAsrStartRequest
import com.code2hack.pokerdealer.protocol.PokerAsrStartResult
import com.code2hack.pokerdealer.protocol.PokerAsrTarget
import com.code2hack.pokerdealer.protocol.PokerAsrTargetField
import com.code2hack.pokerdealer.protocol.PokerPrimaryActionOutcome
import com.code2hack.pokerdealer.protocol.PokerPrimaryActionResult
import com.code2hack.pokerdealer.protocol.PokerPrimaryActionTarget
import com.code2hack.pokerdealer.protocol.UserInputAnswerMutationKind
import com.code2hack.pokerdealer.protocol.UserInputAnswerMutationOutcome
import com.code2hack.pokerdealer.protocol.UserInputAnswerMutationRequest
import com.code2hack.pokerdealer.protocol.UserInputAnswerMutationResult
import com.code2hack.pokerdealer.protocol.UserInputAnswerMutationTarget
import com.code2hack.pokerdealer.protocol.UserInputRequestProjection
import com.code2hack.pokerdealer.protocol.PokerApprovalRequestProjection
import com.code2hack.pokerdealer.protocol.toCommandApprovalDecision
import com.code2hack.pokerdealer.protocol.toFileApprovalDecision
import com.code2hack.pokerdealer.protocol.toPokerApprovalProjection
import com.code2hack.pokerdealer.protocol.PokerConnectionOwner
import com.code2hack.pokerdealer.protocol.PokerConnectionEpoch
import com.code2hack.pokerdealer.protocol.PokerDiagnosticsProtocol
import com.code2hack.pokerdealer.protocol.FilePokerFontScaleStore
import com.code2hack.pokerdealer.protocol.PokerFontScaleInstallResult
import com.code2hack.pokerdealer.protocol.PokerFontScaleProtocol
import com.code2hack.pokerdealer.protocol.PokerFontScaleState
import com.code2hack.pokerdealer.protocol.PokerPairingController
import com.code2hack.pokerdealer.protocol.PokerWakeCapability
import com.code2hack.pokerdealer.protocol.PokerProtocolJson
import com.code2hack.pokerdealer.protocol.ProtocolEnvelope
import com.code2hack.pokerdealer.protocol.POKER_COMPOSER_DRAFT_PROJECTION_TYPE
import com.code2hack.pokerdealer.protocol.POKER_COMPOSER_MUTATION_RESULT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_COMPOSER_MUTATION_TYPE
import com.code2hack.pokerdealer.protocol.MorseMutationRequest
import com.code2hack.pokerdealer.protocol.MorseMutationResult
import com.code2hack.pokerdealer.protocol.POKER_MORSE_MUTATION_RESULT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_MORSE_MUTATION_TYPE
import com.code2hack.pokerdealer.protocol.CoroutinePokerScheduler
import com.code2hack.pokerdealer.protocol.PokerProtocolOffer
import com.code2hack.pokerdealer.protocol.PokerReconnectController
import com.code2hack.pokerdealer.protocol.PokerReconnectTrigger
import com.code2hack.pokerdealer.protocol.PokerBindingProtocol
import com.code2hack.pokerdealer.protocol.POKER_BINDINGS_ACK_TYPE
import com.code2hack.pokerdealer.protocol.POKER_BINDINGS_CAPABILITY
import com.code2hack.pokerdealer.protocol.POKER_BINDINGS_LEARN_TYPE
import com.code2hack.pokerdealer.protocol.POKER_BINDINGS_LEARNING_TYPE
import com.code2hack.pokerdealer.protocol.POKER_BINDINGS_REMOTE_OBSERVED_TYPE
import com.code2hack.pokerdealer.protocol.POKER_BINDINGS_REMOTE_FORGOTTEN_TYPE
import com.code2hack.pokerdealer.protocol.POKER_BINDINGS_SNAPSHOT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_DIAGNOSTICS_CAPABILITY
import com.code2hack.pokerdealer.protocol.POKER_DIAGNOSTICS_TYPE
import com.code2hack.pokerdealer.protocol.POKER_FONT_SCALE_ACK_TYPE
import com.code2hack.pokerdealer.protocol.POKER_FONT_SCALE_CAPABILITY
import com.code2hack.pokerdealer.protocol.POKER_FONT_SCALE_TYPE
import com.code2hack.pokerdealer.protocol.POKER_PROTOCOL_MAJOR
import com.code2hack.pokerdealer.protocol.PokerConnectionState
import com.code2hack.pokerdealer.protocol.sendBindingSnapshot
import com.code2hack.pokerdealer.protocol.PokerSnapshotConnectionHandler
import com.code2hack.pokerdealer.protocol.PokerSnapshotRole
import com.code2hack.pokerdealer.protocol.POKER_SNAPSHOT_CAPABILITY
import com.code2hack.pokerdealer.protocol.POKER_LIVE_DELTA_CAPABILITY
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
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
    private lateinit var pokerConnectionOwner: PokerConnectionOwner<Unit>
    private lateinit var pokerSnapshotSource: DealerPokerSnapshotSource
    private lateinit var pokerSnapshotHandler: PokerSnapshotConnectionHandler
    private lateinit var pokerPairing: PokerPairingController
    private lateinit var pokerFontStore: FilePokerFontScaleStore
    private val pokerSnapshotReady = CompletableDeferred<Unit>()
    private var pokerNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private val pokerBindingSendMutex = Mutex()
    private val pokerFontSendMutex = Mutex()
    @Volatile
    private var pendingPokerRemoteForget: String? = null
    private var pokerComposerEpoch: PokerConnectionEpoch? = null
    private val pokerComposerBindings = mutableMapOf<CodexThreadLocator, PokerComposerBinding>()
    private val pokerComposerResults = mutableMapOf<CodexThreadLocator, ComposerMutationResult>()
    private val pokerPrimaryResults = mutableMapOf<String, PokerPrimaryActionResult>()
    private val pokerUserInputBindings = mutableMapOf<ServerRequestLocator, PokerUserInputBinding>()
    private val pokerUserInputResults = mutableMapOf<String, UserInputAnswerMutationResult>()
    private lateinit var photoAssets: DealerPhotoAssetStore
    private val photoSessions = mutableMapOf<String, DealerPhotoSession>()
    private val photoTransfers = mutableMapOf<String, DealerPhotoTransfer>()
    private val photoResults = mutableMapOf<String, PhotoCaptureResult>()
    private val photoDeleteResults = mutableMapOf<String, PhotoDeleteResult>()
    private val pokerMorseResults = mutableMapOf<String, MorseMutationResult>()
    private val pokerApprovalBindings = mutableMapOf<ServerRequestLocator, PokerApprovalBinding>()
    private val pokerAsrResults = mutableMapOf<String, Any>()
    private lateinit var asrProcess: DealerAsrProcess
    private var pokerAsrSession: DealerAsrSliceSession? = null
    private var pokerAsrPreparing: PokerAsrStartRequest? = null
    private val pokerAsrCancelledSessions = mutableSetOf<String>()
    private val tailnetEngine = Engine()
    private val hostSessionConfigs = mutableMapOf<String, HostSessionConnectionConfig>()
    private val hostSessionSecrets = mutableMapOf<String, StoredHostConnection>()
    private lateinit var hostSessions: HostSessionManager
    private lateinit var hostConnectionProfiles: DealerHostConnectionProfileStore
    private lateinit var threadAttachmentStore: DealerThreadAttachmentStore
    private lateinit var retainedCardStore: RetainedCardStore
    private lateinit var stateRecoveryStore: DealerStateRecoveryStore
    private val attachmentMutex = Mutex()
    private val draftMutex = Mutex()
    private val pendingRequestPersistenceMutex = Mutex()
    private val pokerBindingPersistenceMutex = Mutex()
    private val notificationJobs = mutableMapOf<String, Job>()
    private val requestJobs = mutableMapOf<String, Job>()
    private val hostGenerations = mutableMapOf<String, Long>()
    private val wireCommandApprovals = mutableMapOf<ServerRequestLocator, AppServerRequest>()
    private val wireUserInputs = mutableMapOf<ServerRequestLocator, AppServerRequest>()
    private val userInputTimeoutJobs = mutableMapOf<ServerRequestLocator, Job>()
    private val wireFileApprovals = mutableMapOf<ServerRequestLocator, AppServerRequest>()
    private val dealerOriginatedTurns = mutableSetOf<Pair<CodexThreadLocator, String>>()
    private val threadNotificationTracker = ThreadTransitionNotificationTracker()
    private val threadNotificationTargets = mutableMapOf<String, CodexThreadLocator>()
    private val pokerBindings = PokerBindingController()
    private var connectedHostIds = emptySet<String>()
    private var activityVisible = false
    private var pokerBindingPersistenceAvailable = true
    private var pokerFontPersistenceAvailable = true
    private var pendingRequestPersistenceAvailable = true

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
        val pairingIdentity = AndroidKeystorePairingIdentity()
        pokerPairing = pairingIdentity.pairingController(this)
        pokerFontStore = FilePokerFontScaleStore(
            noBackupFilesDir.resolve("poker-font-v1.json"),
        )
        val restoredPokerFont = runCatching { pokerFontStore.load() }
            .onFailure { pokerFontPersistenceAvailable = false }
            .getOrDefault(PokerFontScaleState())
        val pokerScheduler = CoroutinePokerScheduler(scope)
        pokerSnapshotSource = DealerPokerSnapshotSource { mutableState.value }
        pokerSnapshotHandler = PokerSnapshotConnectionHandler(
            role = PokerSnapshotRole.DEALER,
            snapshotSource = {
                pokerSnapshotReady.await()
                pokerSnapshotSource.current()
            },
            scheduler = pokerScheduler,
            scope = scope,
        )
        pokerConnectionOwner = PokerConnectionOwner(
            factory = null,
            connector = AndroidPokerClientConnector(pairingIdentity, pokerPairing),
            scope = scope,
            localOffer = PokerProtocolOffer(
                major = POKER_PROTOCOL_MAJOR,
                capabilities = setOf(
                    POKER_BINDINGS_CAPABILITY,
                    POKER_SNAPSHOT_CAPABILITY,
                    POKER_LIVE_DELTA_CAPABILITY,
                    POKER_PRIMARY_ACTION_CAPABILITY,
                    POKER_PHOTO_CAPABILITY,
                    POKER_MORSE_CAPABILITY,
                    POKER_ASR_CAPABILITY,
                    POKER_FONT_SCALE_CAPABILITY,
                    POKER_DIAGNOSTICS_CAPABILITY,
                ),
            ),
            scheduler = pokerScheduler,
            clock = PokerClock { System.currentTimeMillis() },
            reconnect = PokerReconnectController(),
            onConnected = { epoch, negotiation ->
                onPokerConnected(epoch, negotiation)
                sendCurrentPokerBindingsNow()
                sendCurrentPokerFontNow()
            },
            onEnvelope = { epoch, envelope ->
                onPokerEnvelope(epoch, envelope)
                handlePokerBindingEnvelope(envelope)
            },
            onStateChanged = ::handlePokerConnectionState,
            callbacks = pokerSnapshotHandler,
        )
        asrProcess = DealerAsrProcess.shared(this)
        scope.launch { runCatching { asrProcess.start() } }
        hostConnectionProfiles = DealerHostConnectionProfileStore(this)
        threadAttachmentStore = DealerThreadAttachmentStore(this)
        photoAssets = DealerPhotoAssetStore(this)
        retainedCardStore = RetainedCardStore(noBackupFilesDir.resolve("thread-cards"))
        stateRecoveryStore = DealerStateRecoveryStore(noBackupFilesDir.resolve("recovery"))
        val hostConnectionIntents = HostConnectionIntentDataStore(this)
        hostSessions = HostSessionManager(
            hostIds = InitialCodexHosts.all.map(CodexHost::id).toSet(),
            intentStore = hostConnectionIntents,
            connector = InitializedHostSessionConnector { hostId ->
                synchronized(hostSessionConfigs) { hostSessionConfigs[hostId] }
                    ?: cacheHostSession(hostConnectionProfiles.load(hostId))
            },
            scope = scope,
        )
        registerPokerNetworkCallback()
        pokerConnectionOwner.start()
        scope.launch {
            val restoreErrors = mutableListOf<String>()
            val (restoredAttachments, restoredActions) = try {
                threadAttachmentStore.read() to threadAttachmentStore.readActions()
            } catch (failure: Throwable) {
                restoreErrors += "Unable to restore Dealer thread state: ${failure.message}"
                emptySet<CodexThreadLocator>() to ThreadActionState()
            }
            val recovered = stateRecoveryStore.read()
            restoreErrors += recovered.errors
            pokerBindingPersistenceAvailable = recovered.pokerBindingsWritable
            runCatching {
                pokerBindings.restore(
                    map = recovered.pokerBindings.map,
                    knownRemoteDescriptors = recovered.pokerBindings.knownRemoteDescriptors,
                )
            }.onFailure { failure ->
                restoreErrors += "Unable to restore Poker bindings: ${failure.message}"
                pokerBindings.restore(
                    map = recovered.pokerBindings.map,
                    knownRemoteDescriptors = emptyList(),
                )
            }
            val restoredCards = restoredAttachments.flatMap { locator ->
                runCatching { retainedCardStore.read(locator) }
                    .onFailure { failure ->
                        restoreErrors += "Unable to restore retained cards: ${failure.message}"
                    }
                    .getOrDefault(emptyList())
            }
            mutableState.value = DealerUiState().restoreAfterProcessDeath(
                attachments = restoredAttachments,
                actions = restoredActions,
                cards = restoredCards,
                projection = recovered.projection,
                pendingRequests = recovered.pendingRequests,
                error = restoreErrors.takeIf(List<String>::isNotEmpty)?.joinToString("; "),
            ).copy(
                pokerBindings = pokerBindings.state,
                pokerFont = restoredPokerFont,
                pokerDiagnostics = DealerPokerDiagnostics(
                    pairing = pokerPairing.status.state,
                    fontRevision = restoredPokerFont.revision,
                ),
            )
            handlePokerConnectionState(pokerConnectionOwner.connectionState)
            sendCurrentPokerBindings()
            sendCurrentPokerFont()
            pokerComposerEpoch?.let { epoch ->
                restoredAttachments.forEach { locator -> sendPokerProjection(epoch, locator) }
                mutableState.value.userInputRequests.requests.values
                    .filter { it.thread in restoredAttachments && it.resolution != RequestResolutionState.RESOLVED }
                    .forEach { request -> sendPokerUserInputProjection(epoch, request.locator) }
                mutableState.value.commandApprovals.requests.values
                    .filter { it.thread in restoredAttachments }
                    .forEach { request -> sendPokerApprovalProjection(epoch, request.locator) }
                mutableState.value.fileApprovals.requests.values
                    .filter { it.thread in restoredAttachments }
                    .forEach { request -> sendPokerApprovalProjection(epoch, request.locator) }
            }
            pokerSnapshotReady.complete(Unit)
            startRecoveryPersistence(recovered.pendingRequestsWritable)
            if (hostConnectionIntents.readEnabledHostIds().any {
                    hostConnectionProfiles.hasConfiguredTailnetRoute(it)
                }
            ) {
                startEmbeddedTailnet()
            }
            hostSessions.start()
            hostSessions.state.collect { sessions ->
                mutableState.update { it.copy(hostSessions = sessions) }
                val connected = sessions.filterValues {
                    it.status == HostSessionStatus.CONNECTED
                }.keys
                (connectedHostIds - connected).forEach { hostId ->
                    notificationJobs.remove(hostId)?.cancel()
                    requestJobs.remove(hostId)?.cancel()
                    val generation = hostGenerations[hostId] ?: 0
                    wireCommandApprovals.keys.removeAll {
                        it.hostId == hostId && it.appServerGeneration == generation
                    }
                    wireUserInputs.keys.removeAll {
                        it.hostId == hostId && it.appServerGeneration == generation
                    }
                    wireFileApprovals.keys.removeAll {
                        it.hostId == hostId && it.appServerGeneration == generation
                    }
                    userInputTimeoutJobs.keys
                        .filter { it.hostId == hostId && it.appServerGeneration == generation }
                        .forEach { userInputTimeoutJobs.remove(it)?.cancel() }
                    mutableState.update {
                        it.withApprovals(
                            commandApprovals = it.commandApprovals.connectionLost(hostId, generation),
                            fileApprovals = it.fileApprovals.connectionLost(hostId, generation),
                        ).withUserInputRequests(
                            it.userInputRequests.connectionLost(hostId, generation),
                        )
                    }
                    mutableState.value.userInputRequests.unresolved(hostId)
                        .map(UserInputRequest::thread)
                        .distinct()
                        .forEach(::refreshPokerUserInputProjection)
                    (mutableState.value.commandApprovals.unresolved(hostId).map(CommandApprovalRequest::thread) +
                        mutableState.value.fileApprovals.unresolved(hostId).map { it.thread })
                        .distinct()
                        .forEach(::refreshPokerApprovalProjection)
                }
                (connected - connectedHostIds).forEach { hostId ->
                    val generation = hostGenerations.getOrDefault(hostId, 0) + 1
                    hostGenerations[hostId] = generation
                    threadNotificationTracker.beginReconciliation(hostId)
                    observeNotifications(hostId, generation)
                    observeServerRequests(hostId, generation)
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
            ACTION_RETRY_POKER -> {
                ensureForeground()
                pokerConnectionOwner.retry(PokerReconnectTrigger.MANUAL_RETRY)
            }
            else -> ensureForeground()
        }
        return START_NOT_STICKY
    }

    fun selectPokerBindingDevice(device: PokerBindingDevice) {
        if (!canEditPokerBindings()) return
        pokerBindings.selectDevice(device)
        publishPokerBindings(sendSnapshot = false)
    }

    fun beginPokerBinding(operation: PokerOperation): Boolean {
        if (!canEditPokerBindings()) {
            mutableState.update {
                it.copy(error = "Poker must be connected and idle before binding")
            }
            return false
        }
        if (!pokerBindings.beginLearning(operation)) {
            publishPokerBindings(sendSnapshot = false)
            return false
        }
        val target = checkNotNull(pokerBindings.learningTarget)
        publishPokerBindings(sendSnapshot = false)
        scope.launch {
            runCatching {
                check(sendPokerBindingMessage(
                    POKER_BINDINGS_LEARN_TYPE,
                    PokerBindingProtocol.learnPayload(target.device.descriptor, target.operation),
                )) { "Poker learning request was not sent" }
            }.onFailure {
                pokerBindings.connectionLost()
                publishPokerBindings(sendSnapshot = false)
            }
        }
        return true
    }

    fun removePokerBinding(operation: PokerOperation) {
        if (!canEditPokerBindings()) return
        pokerBindings.remove(operation)
        publishPokerBindings()
    }

    fun resetPokerGlassesDefaults() {
        if (!canEditPokerBindings()) return
        pokerBindings.resetGlassesDefaults()
        publishPokerBindings()
    }

    fun clearPokerRemote() {
        if (!canEditPokerBindings()) return
        pokerBindings.clearSelectedRemote()
        publishPokerBindings()
    }

    fun forgetPokerRemote(descriptor: String) {
        if (!canEditPokerBindings()) return
        pokerBindings.forgetRemote(descriptor)
        pendingPokerRemoteForget = descriptor
        publishPokerBindings()
    }

    /** Called by the Poker connection when an exact Android descriptor is first observed. */
    fun observePokerRemote(descriptor: String) {
        if (pokerBindings.observeRemote(descriptor)) publishPokerBindings()
    }

    fun setPokerFontScale(percent: Int) {
        val current = mutableState.value.pokerFont
        val next = runCatching {
            PokerFontScaleState(current.revision + 1, percent)
        }.getOrElse {
            mutableState.update {
                it.copy(pokerDiagnostics = it.pokerDiagnostics.copy(lastFailure = PokerDiagnosticFailure.FONT))
            }
            return
        }
        if (next.percent == current.percent) return
        mutableState.update {
            it.copy(
                pokerFont = next,
                pokerDiagnostics = it.pokerDiagnostics.copy(
                    fontSync = PokerFontSyncStatus.PENDING,
                    fontRevision = next.revision,
                    lastFailure = PokerDiagnosticFailure.NONE,
                ),
            )
        }
        if (pokerFontPersistenceAvailable) {
            scope.launch {
                runCatching {
                    pokerFontSendMutex.withLock { pokerFontStore.save(next) }
                }.onFailure {
                    pokerFontPersistenceAvailable = false
                    mutableState.update {
                        it.copy(
                            pokerDiagnostics = it.pokerDiagnostics.copy(
                                fontSync = PokerFontSyncStatus.UNSYNCHRONIZED,
                                lastFailure = PokerDiagnosticFailure.STORAGE,
                            ),
                        )
                    }
                }
            }
        }
        sendCurrentPokerFont()
        scope.launch {
            val snapshot = pokerSnapshotSource.current()
            pokerSnapshotHandler.publish(snapshot)
            recordPokerSnapshotRevision(snapshot)
        }
    }

    /** Called after the complete map has been acknowledged by Poker. */
    fun acknowledgePokerBindings(revision: Long) {
        if (pokerBindings.acknowledge(revision)) publishPokerBindings(sendSnapshot = false)
    }

    private fun canEditPokerBindings(): Boolean =
        pokerConnectionOwner.isConnected && pokerBindings.learningTarget == null

    private fun publishPokerBindings(sendSnapshot: Boolean = true) {
        val state = pokerBindings.state
        mutableState.update {
            it.copy(
                pokerBindings = state,
                pokerDiagnostics = it.pokerDiagnostics.copy(bindingSync = state.syncStatus),
            )
        }
        if (pokerBindingPersistenceAvailable) {
            scope.launch {
                runCatching {
                    pokerBindingPersistenceMutex.withLock {
                        stateRecoveryStore.writePokerBindings(
                            DealerPokerBindingSnapshot(
                                map = state.map,
                                knownRemoteDescriptors = state.knownRemoteDescriptors,
                            ),
                        )
                    }
                }.onFailure { failure ->
                    pokerBindingPersistenceAvailable = false
                    mutableState.update {
                        it.copy(error = "Unable to retain Poker bindings: ${failure.message}")
                    }
                }
            }
        }
        if (sendSnapshot) sendCurrentPokerBindings()
    }

    private fun sendCurrentPokerBindings() {
        if (!pokerConnectionOwner.isConnected) return
        scope.launch { sendCurrentPokerBindingsNow() }
    }

    private suspend fun sendCurrentPokerBindingsNow() = pokerBindingSendMutex.withLock {
        if (!pokerConnectionOwner.isConnected) return@withLock
        runCatching {
            check(pokerConnectionOwner.sendBindingSnapshot(pokerBindings.map)) {
                "Poker binding snapshot was not sent"
            }
            pendingPokerRemoteForget?.let { descriptor ->
                check(pokerConnectionOwner.send(
                    POKER_BINDINGS_REMOTE_FORGOTTEN_TYPE,
                    PokerBindingProtocol.remoteForgottenPayload(descriptor),
                    requireWritable = false,
                )) { "Poker remote forget was not sent" }
                pendingPokerRemoteForget = null
            }
        }.onFailure {
            pokerBindings.connectionLost()
            publishPokerBindings(sendSnapshot = false)
        }
    }

    private fun sendCurrentPokerFont() {
        if (!pokerConnectionOwner.isConnected) return
        scope.launch { sendCurrentPokerFontNow() }
    }

    private suspend fun sendCurrentPokerFontNow() = pokerFontSendMutex.withLock {
        if (!pokerConnectionOwner.isConnected) return@withLock
        val state = mutableState.value.pokerFont
        val sent = pokerConnectionOwner.send(
            POKER_FONT_SCALE_TYPE,
            PokerFontScaleProtocol.updatePayload(state),
            requireWritable = false,
        )
        if (!sent) {
            mutableState.update {
                it.copy(
                    pokerDiagnostics = it.pokerDiagnostics.copy(
                        fontSync = PokerFontSyncStatus.UNSYNCHRONIZED,
                        lastFailure = PokerDiagnosticFailure.PROTOCOL,
                    ),
                )
            }
        }
    }

    private suspend fun sendPokerBindingMessage(
        type: String,
        payload: JsonObject,
        replyTo: String? = null,
    ) = pokerBindingSendMutex.withLock {
        pokerConnectionOwner.send(type, payload, replyTo, requireWritable = false)
    }

    private fun handlePokerConnectionState(state: PokerConnectionState) {
        mutableState.update {
            it.copy(
                pokerConnected = state == PokerConnectionState.CONNECTED,
                pokerDiagnostics = it.pokerDiagnostics.copy(
                    pairing = pokerPairing.status.state,
                    connection = state,
                    connectionEpoch = if (state == PokerConnectionState.CONNECTED) {
                        it.pokerDiagnostics.connectionEpoch
                    } else {
                        null
                    },
                    capabilities = if (state == PokerConnectionState.CONNECTED) {
                        it.pokerDiagnostics.capabilities
                    } else {
                        emptySet()
                    },
                    fontSync = if (state == PokerConnectionState.CONNECTED) {
                        PokerFontSyncStatus.PENDING
                    } else {
                        PokerFontSyncStatus.UNSYNCHRONIZED
                    },
                    acknowledgedFontRevision = if (state == PokerConnectionState.CONNECTED) {
                        it.pokerDiagnostics.acknowledgedFontRevision
                    } else {
                        null
                    },
                    bindingSync = if (state == PokerConnectionState.CONNECTED) {
                        it.pokerBindings.syncStatus
                    } else {
                        com.code2hack.pokerdealer.domain.PokerBindingSyncStatus.UNSYNCHRONIZED
                    },
                    unreadCount = if (state == PokerConnectionState.CONNECTED) {
                        it.pokerDiagnostics.unreadCount
                    } else {
                        null
                    },
                    wakeCapability = if (state == PokerConnectionState.CONNECTED) {
                        it.pokerDiagnostics.wakeCapability
                    } else {
                        PokerWakeCapability.UNKNOWN
                    },
                ),
            )
        }
        if (state != PokerConnectionState.CONNECTED) {
            pokerBindings.connectionLost()
            publishPokerBindings(sendSnapshot = false)
            pokerAsrPreparing?.sessionId?.let(pokerAsrCancelledSessions::add)
            pokerAsrPreparing = null
            closePokerAsrSession()
        }
    }

    private suspend fun handlePokerBindingEnvelope(envelope: com.code2hack.pokerdealer.protocol.ProtocolEnvelope) {
        when (envelope.type) {
            POKER_BINDINGS_REMOTE_OBSERVED_TYPE -> {
                val observed = runCatching { PokerBindingProtocol.decodeRemoteObserved(envelope) }
                    .getOrNull() ?: return
                if (pokerBindings.observeRemote(observed.descriptor)) publishPokerBindings()
            }

            POKER_BINDINGS_REMOTE_FORGOTTEN_TYPE -> Unit

            POKER_BINDINGS_SNAPSHOT_TYPE -> {
                val candidate = runCatching { PokerBindingProtocol.decodeSnapshot(envelope) }
                    .getOrNull() ?: return
                when (val result = PokerBindingProtocol.installSnapshot(pokerBindings, envelope)) {
                    PokerBindingInstallResult.INSTALLED -> publishPokerBindings()
                    else -> {
                        sendPokerBindingAck(candidate.revision, result, envelope.messageId)
                        if (result != PokerBindingInstallResult.DUPLICATE) {
                            sendCurrentPokerBindingsNow()
                        }
                    }
                }
            }

            POKER_BINDINGS_ACK_TYPE -> {
                val ack = runCatching { PokerBindingProtocol.decodeAck(envelope) }.getOrNull() ?: return
                if (ack.result in setOf(
                        PokerBindingInstallResult.INSTALLED,
                        PokerBindingInstallResult.DUPLICATE,
                    )
                ) {
                    acknowledgePokerBindings(ack.revision)
                }
            }

            POKER_BINDINGS_LEARNING_TYPE -> {
                val learning = runCatching { PokerBindingProtocol.decodeLearning(envelope) }
                    .getOrNull() ?: return
                if (!learning.active && pokerBindings.learningTarget != null) {
                    pokerBindings.cancelLearning()
                    publishPokerBindings(sendSnapshot = false)
                }
            }

            else -> Unit
        }
    }

    private suspend fun sendPokerBindingAck(
        revision: Long,
        result: PokerBindingInstallResult,
        replyTo: String,
    ) {
        runCatching {
            sendPokerBindingMessage(
                POKER_BINDINGS_ACK_TYPE,
                PokerBindingProtocol.ackPayload(revision, result),
                replyTo = replyTo,
            )
        }
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

    /** Requests one immediate, fenced Dealer-to-Poker reconnect attempt. */
    fun retryPokerConnection(): Long? =
        pokerConnectionOwner.retry(PokerReconnectTrigger.MANUAL_RETRY)

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
            settleRequestsForDisconnect(hostId)
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

    private suspend fun settleRequestsForDisconnect(hostId: String) {
        val appServer = hostSessions.connectedSession(hostId)?.appServer ?: return
        val generation = hostGenerations[hostId] ?: return
        val currentCommands = mutableState.value.commandApprovals.unresolved(hostId)
            .filter { it.locator.appServerGeneration == generation }
        val currentFiles = mutableState.value.fileApprovals.unresolved(hostId)
            .filter { it.locator.appServerGeneration == generation }
        val currentQuestions = mutableState.value.userInputRequests.unresolved(hostId)
            .filter { it.locator.appServerGeneration == generation }
        val interruptedTurns = mutableSetOf<String>()
        currentCommands.filter { it.resolution == RequestResolutionState.PENDING }.forEach { request ->
            if (CommandApprovalDecision.CANCEL in request.offeredDecisions) {
                mutableState.update {
                    it.withApprovals(
                        commandApprovals = it.commandApprovals.begin(
                            request.locator,
                            CommandApprovalDecision.CANCEL,
                        ),
                    )
                }
                val wire = wireCommandApprovals[request.locator]
                if (wire == null) {
                    mutableState.update {
                        it.withApprovals(
                            commandApprovals = it.commandApprovals.unknown(request.locator),
                        )
                    }
                } else {
                    runCatching {
                        persistPendingRequestState()
                        appServer.respond(
                            wire,
                            CommandApprovalProtocol.response(request, CommandApprovalDecision.CANCEL),
                        )
                    }.onFailure {
                        mutableState.update { state ->
                            state.withApprovals(
                                commandApprovals = state.commandApprovals.unknown(request.locator),
                            )
                        }
                    }
                }
            } else if (interruptedTurns.add(request.turnId)) {
                mutableState.update {
                    it.withApprovals(
                        commandApprovals = it.commandApprovals.unknown(request.locator),
                    )
                }
                runCatching { appServer.turnInterrupt(request.thread.threadId, request.turnId) }
            }
        }
        currentFiles.filter { it.resolution == RequestResolutionState.PENDING }.forEach { request ->
            mutableState.update {
                it.withApprovals(
                    fileApprovals = it.fileApprovals.begin(
                        request.locator,
                        FileApprovalDecision.CANCEL,
                    ),
                )
            }
            val wire = wireFileApprovals[request.locator]
            if (wire == null) {
                mutableState.update {
                    it.withApprovals(
                        fileApprovals = it.fileApprovals.unknown(request.locator),
                    )
                }
            } else {
                runCatching {
                    persistPendingRequestState()
                    appServer.respond(wire, FileApprovalProtocol.response(FileApprovalDecision.CANCEL))
                }.onFailure {
                    mutableState.update { state ->
                        state.withApprovals(
                            fileApprovals = state.fileApprovals.unknown(request.locator),
                        )
                    }
                }
            }
        }
        currentQuestions.filter { it.resolution == RequestResolutionState.PENDING }.forEach { request ->
            userInputTimeoutJobs.remove(request.locator)?.cancel()
            mutableState.update {
                it.withUserInputRequests(
                    it.userInputRequests.begin(request.locator, UserInputOutcome.NO_ANSWER),
                )
            }
            val wire = wireUserInputs[request.locator]
            if (wire == null) {
                mutableState.update {
                    it.withUserInputRequests(it.userInputRequests.unknown(request.locator))
                }
            } else {
                runCatching {
                    persistPendingRequestState()
                    appServer.respond(wire, UserInputProtocol.response(request, emptyMap()))
                }.onFailure {
                    mutableState.update { state ->
                        state.withUserInputRequests(
                            state.userInputRequests.unknown(request.locator),
                        )
                    }
                }
            }
        }
        if (currentCommands.any { it.resolution in DISCONNECT_WAIT_STATES } ||
            currentFiles.any { it.resolution in DISCONNECT_WAIT_STATES } ||
            currentQuestions.any { it.resolution in DISCONNECT_WAIT_STATES }
        ) {
            delay(DISCONNECT_RESOLUTION_WAIT_MILLIS)
        }
        mutableState.update { state ->
            var commands = state.commandApprovals
            var files = state.fileApprovals
            var questions = state.userInputRequests
            currentCommands.forEach { request ->
                if (commands.requests[request.locator]?.resolution != RequestResolutionState.RESOLVED) {
                    commands = commands.unknown(request.locator)
                }
            }
            currentFiles.forEach { request ->
                if (files.requests[request.locator]?.resolution != RequestResolutionState.RESOLVED) {
                    files = files.unknown(request.locator)
                }
            }
            currentQuestions.forEach { request ->
                if (questions.requests[request.locator]?.resolution != RequestResolutionState.RESOLVED) {
                    questions = questions.unknown(request.locator)
                }
            }
            state.withApprovals(commands, files).withUserInputRequests(questions)
        }
        currentQuestions
            .map(UserInputRequest::thread)
            .distinct()
            .forEach(::refreshPokerUserInputProjection)
    }

    fun updateDraft(locator: CodexThreadLocator, text: String) =
        updateDraft(locator, ComposerDraft.fromText(text))

    fun updateDraft(locator: CodexThreadLocator, draft: ComposerDraft) {
        mutableState.update { it.copy(threadActions = it.threadActions.editComposerDraft(locator, draft)) }
        scope.launch {
            draftMutex.withLock {
                threadAttachmentStore.writeDraft(locator, draft)
            }
            pokerComposerEpoch?.let { epoch -> sendPokerProjection(epoch, locator) }
        }
    }

    private fun refreshPokerProjection(locator: CodexThreadLocator) {
        pokerComposerEpoch?.let { epoch ->
            scope.launch {
                sendPokerProjection(epoch, locator)
                val snapshot = pokerSnapshotSource.current()
                pokerSnapshotHandler.publish(snapshot)
                recordPokerSnapshotRevision(snapshot)
            }
        }
        refreshPokerUserInputProjection(locator)
        refreshPokerApprovalProjection(locator)
    }

    private suspend fun onPokerConnected(
        epoch: PokerConnectionEpoch,
        negotiation: com.code2hack.pokerdealer.protocol.PokerProtocolNegotiation,
    ) {
        photoTransfers.values.forEach { transfer -> photoAssets.delete(transfer.target.assetId) }
        photoTransfers.clear()
        photoSessions.clear()
        photoResults.clear()
        photoDeleteResults.clear()
        pokerComposerEpoch = epoch
        pokerComposerBindings.clear()
        pokerComposerResults.clear()
        pokerPrimaryResults.clear()
        pokerUserInputBindings.clear()
        pokerUserInputResults.clear()
        pokerMorseResults.clear()
        pokerApprovalBindings.clear()
        val snapshot = pokerSnapshotSource.current()
        mutableState.update {
            it.copy(
                pokerDiagnostics = it.pokerDiagnostics.copy(
                    pairing = pokerPairing.status.state,
                    connection = PokerConnectionState.CONNECTED,
                    connectionEpoch = epoch.value,
                    capabilities = negotiation.capabilities,
                    snapshotRevision = snapshot.revision,
                    deltaRevision = null,
                    bindingSync = it.pokerBindings.syncStatus,
                    fontSync = PokerFontSyncStatus.PENDING,
                    fontRevision = it.pokerFont.revision,
                    acknowledgedFontRevision = null,
                    unreadCount = null,
                    wakeCapability = PokerWakeCapability.UNKNOWN,
                    lastFailure = PokerDiagnosticFailure.NONE,
                ),
            )
        }
        mutableState.value.threadAttachments.attached
            .toList()
            .forEach { locator -> sendPokerProjection(epoch, locator) }
        mutableState.value.userInputRequests.requests.values
            .filter { it.thread in mutableState.value.threadAttachments.attached }
            .forEach { request -> sendPokerUserInputProjection(epoch, request.locator) }
        mutableState.value.commandApprovals.requests.values
            .filter { it.thread in mutableState.value.threadAttachments.attached }
            .forEach { request -> sendPokerApprovalProjection(epoch, request.locator) }
        mutableState.value.fileApprovals.requests.values
            .filter { it.thread in mutableState.value.threadAttachments.attached }
            .forEach { request -> sendPokerApprovalProjection(epoch, request.locator) }
        sendPokerAsrAvailability(epoch)
    }

    private fun handlePokerFontAcknowledgement(envelope: ProtocolEnvelope) {
        val acknowledgement = runCatching {
            PokerFontScaleProtocol.decodeAcknowledgement(envelope)
        }.getOrNull() ?: return
        val state = mutableState.value
        val current = state.pokerFont
        if (acknowledgement.state.revision < current.revision ||
            acknowledgement.state.revision <
            (state.pokerDiagnostics.acknowledgedFontRevision ?: Long.MIN_VALUE)
        ) {
            return
        }
        val synchronized = acknowledgement.state == current && acknowledgement.result in setOf(
            PokerFontScaleInstallResult.INSTALLED,
            PokerFontScaleInstallResult.DUPLICATE,
        )
        mutableState.update {
            it.copy(
                pokerDiagnostics = it.pokerDiagnostics.copy(
                    fontSync = if (synchronized) {
                        PokerFontSyncStatus.SYNCHRONIZED
                    } else {
                        PokerFontSyncStatus.UNSYNCHRONIZED
                    },
                    acknowledgedFontRevision = acknowledgement.state.revision.takeIf { synchronized },
                    lastFailure = if (synchronized) {
                        PokerDiagnosticFailure.NONE
                    } else {
                        PokerDiagnosticFailure.FONT
                    },
                ),
            )
        }
    }

    private fun handlePokerClientDiagnostics(envelope: ProtocolEnvelope) {
        val diagnostics = runCatching { PokerDiagnosticsProtocol.decode(envelope) }.getOrNull() ?: return
        val state = mutableState.value
        val current = state.pokerFont
        val acknowledged = when {
            diagnostics.font == current -> diagnostics.font.revision
            diagnostics.font.revision <
                (state.pokerDiagnostics.acknowledgedFontRevision ?: Long.MIN_VALUE) ->
                state.pokerDiagnostics.acknowledgedFontRevision
            else -> null
        }
        mutableState.update {
            it.copy(
                pokerDiagnostics = it.pokerDiagnostics.copy(
                    unreadCount = diagnostics.unreadCount,
                    wakeCapability = diagnostics.wakeCapability,
                    acknowledgedFontRevision = acknowledged,
                    fontSync = when {
                        diagnostics.font == current -> PokerFontSyncStatus.SYNCHRONIZED
                        diagnostics.font.revision < current.revision -> PokerFontSyncStatus.PENDING
                        else -> PokerFontSyncStatus.UNSYNCHRONIZED
                    },
                ),
            )
        }
    }

    private suspend fun onPokerEnvelope(epoch: PokerConnectionEpoch, envelope: ProtocolEnvelope) {
        when (envelope.type) {
            POKER_FONT_SCALE_ACK_TYPE -> handlePokerFontAcknowledgement(envelope)
            POKER_DIAGNOSTICS_TYPE -> handlePokerClientDiagnostics(envelope)
            POKER_COMPOSER_MUTATION_TYPE -> {
                val request = runCatching {
                    PokerProtocolJson.decodeFromJsonElement(
                        ComposerMutationRequest.serializer(),
                        envelope.payload,
                    )
                }.getOrNull() ?: return
                handlePokerComposerMutation(epoch, envelope, request)
            }
            POKER_USER_INPUT_MUTATION_TYPE -> {
                val request = runCatching {
                    PokerProtocolJson.decodeFromJsonElement(
                        UserInputAnswerMutationRequest.serializer(),
                        envelope.payload,
                    )
                }.getOrNull() ?: return
                handlePokerUserInputMutation(epoch, envelope, request)
            }
            POKER_MORSE_MUTATION_TYPE -> {
                val request = runCatching {
                    PokerProtocolJson.decodeFromJsonElement(
                        MorseMutationRequest.serializer(),
                        envelope.payload,
                    )
                }.getOrNull() ?: return
                handlePokerMorseMutation(epoch, envelope, request)
            }
            POKER_PRIMARY_ACTION_TYPE -> {
                val target = runCatching {
                    PokerProtocolJson.decodeFromJsonElement(
                        PokerPrimaryActionTarget.serializer(),
                        envelope.payload,
                    )
                }.getOrNull() ?: return
                handlePokerPrimaryAction(epoch, envelope, target)
            }
            POKER_PHOTO_START_TYPE -> {
                val target = runCatching {
                    PokerProtocolJson.decodeFromJsonElement(
                        PhotoStartTarget.serializer(),
                        envelope.payload,
                    )
                }.getOrNull() ?: return
                handlePokerPhotoStart(epoch, envelope, target)
            }
            POKER_PHOTO_CAPTURE_BEGIN_TYPE -> {
                val begin = runCatching {
                    PokerProtocolJson.decodeFromJsonElement(
                        PhotoCaptureBegin.serializer(),
                        envelope.payload,
                    )
                }.getOrNull() ?: return
                handlePokerPhotoCaptureBegin(epoch, envelope, begin)
            }
            POKER_PHOTO_CAPTURE_CHUNK_TYPE -> {
                val chunk = runCatching {
                    PokerProtocolJson.decodeFromJsonElement(
                        PhotoCaptureChunk.serializer(),
                        envelope.payload,
                    )
                }.getOrNull() ?: return
                handlePokerPhotoCaptureChunk(epoch, envelope, chunk)
            }
            POKER_PHOTO_CAPTURE_COMPLETE_TYPE -> {
                val complete = runCatching {
                    PokerProtocolJson.decodeFromJsonElement(
                        PhotoCaptureComplete.serializer(),
                        envelope.payload,
                    )
                }.getOrNull() ?: return
                handlePokerPhotoCaptureComplete(epoch, envelope, complete)
            }
            POKER_PHOTO_DELETE_TYPE -> {
                val target = runCatching {
                    PokerProtocolJson.decodeFromJsonElement(
                        PhotoAssetTarget.serializer(),
                        envelope.payload,
                    )
                }.getOrNull() ?: return
                handlePokerPhotoDelete(epoch, envelope, target)
            }
            POKER_PHOTO_CANCEL_TYPE -> {
                val target = runCatching {
                    PokerProtocolJson.decodeFromJsonElement(
                        PhotoStartTarget.serializer(),
                        envelope.payload,
                    )
                }.getOrNull() ?: return
                handlePokerPhotoCancel(epoch, target)
            }
            POKER_ASR_START_TYPE -> decodeAsr<PokerAsrStartRequest>(envelope, PokerAsrStartRequest.serializer())
                ?.let { handlePokerAsrStart(epoch, envelope, it) }
            POKER_ASR_AUDIO_TYPE -> decodeAsr<PokerAsrAudioFrame>(envelope, PokerAsrAudioFrame.serializer())
                ?.let { handlePokerAsrAudio(epoch, it) }
            POKER_ASR_COMMIT_TYPE -> decodeAsr<PokerAsrCommitRequest>(envelope, PokerAsrCommitRequest.serializer())
                ?.let { handlePokerAsrCommit(epoch, envelope, it) }
            POKER_ASR_DISCARD_TYPE -> decodeAsr<PokerAsrDiscardRequest>(envelope, PokerAsrDiscardRequest.serializer())
                ?.let { handlePokerAsrDiscard(epoch, envelope, it) }
            POKER_ASR_EXIT_TYPE -> decodeAsr<PokerAsrExitRequest>(envelope, PokerAsrExitRequest.serializer())
                ?.let { handlePokerAsrExit(epoch, envelope, it) }
        }
    }

    private inline fun <reified T> decodeAsr(
        envelope: ProtocolEnvelope,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T? = runCatching {
        PokerProtocolJson.decodeFromJsonElement(serializer, envelope.payload)
    }.getOrNull()

    private suspend fun sendPokerAsrAvailability(epoch: PokerConnectionEpoch) {
        if (pokerComposerEpoch != epoch) return
        val availability = runCatching { asrProcess.availability() }
            .getOrElse { PokerAsrAvailability(false, reason = "runtime-load-failed") }
        sendPokerAsr(
            epoch = epoch,
            type = POKER_ASR_AVAILABILITY_TYPE,
            serializer = PokerAsrAvailability.serializer(),
            value = availability,
        )
    }

    private suspend fun handlePokerAsrStart(
        epoch: PokerConnectionEpoch,
        envelope: ProtocolEnvelope,
        request: PokerAsrStartRequest,
    ) {
        (pokerAsrResults[request.sessionId] as? PokerAsrStartResult)?.let {
            sendPokerAsrResult(envelope, POKER_ASR_START_RESULT_TYPE, PokerAsrStartResult.serializer(), it)
            return
        }
        val rejection = validatePokerAsrTarget(epoch, request.target)
        if (rejection != null) {
            val result = PokerAsrStartResult(request.target, request.sessionId, PokerAsrStartOutcome.REJECTED, reason = rejection)
            pokerAsrResults[request.sessionId] = result
            sendPokerAsrResult(envelope, POKER_ASR_START_RESULT_TYPE, PokerAsrStartResult.serializer(), result)
            return
        }
        val existing = pokerAsrSession
        if (existing != null) {
            val result = if (existing.sessionId == request.sessionId && existing.target == request.target) {
                PokerAsrStartResult(
                    request.target,
                    request.sessionId,
                    PokerAsrStartOutcome.READY,
                    existing.pack,
                )
            } else {
                PokerAsrStartResult(
                    request.target,
                    request.sessionId,
                    PokerAsrStartOutcome.REJECTED,
                    reason = "ASR session is already active",
                )
            }
            pokerAsrResults[request.sessionId] = result
            sendPokerAsrResult(envelope, POKER_ASR_START_RESULT_TYPE, PokerAsrStartResult.serializer(), result)
            return
        }

        pokerAsrPreparing = request
        val result = try {
            val availability = asrProcess.availability()
            val pack = availability.pack ?: error(availability.reason ?: "model-pack-not-installed")
            val opened = asrProcess.open(expected = pack)
            if (request.sessionId in pokerAsrCancelledSessions) {
                pokerAsrCancelledSessions -= request.sessionId
                opened.close()
                PokerAsrStartResult(request.target, request.sessionId, PokerAsrStartOutcome.CANCELLED)
            } else {
                pokerAsrSession = DealerAsrSliceSession(
                    sessionId = request.sessionId,
                    target = request.target,
                    pack = pack,
                    recognizer = opened,
                )
                mutableState.update {
                    it.copy(
                        asr = DealerAsrUiState(
                            active = true,
                            sessionId = request.sessionId,
                            target = request.target,
                            pack = pack,
                        ),
                    )
                }
                PokerAsrStartResult(request.target, request.sessionId, PokerAsrStartOutcome.READY, pack)
            }
        } catch (failure: Throwable) {
            PokerAsrStartResult(
                request.target,
                request.sessionId,
                PokerAsrStartOutcome.REJECTED,
                reason = failure.message ?: "ASR runtime unavailable",
            )
        } finally {
            pokerAsrPreparing = null
        }
        pokerAsrResults[request.sessionId] = result
        sendPokerAsrResult(envelope, POKER_ASR_START_RESULT_TYPE, PokerAsrStartResult.serializer(), result)
        if (result.outcome == PokerAsrStartOutcome.READY) {
            pokerAsrSession?.let { sendPokerAsrProjection(epoch, it, immediate = true) }
        }
    }

    private suspend fun handlePokerAsrAudio(epoch: PokerConnectionEpoch, frame: PokerAsrAudioFrame) {
        val session = pokerAsrSession ?: return
        if (pokerComposerEpoch != epoch) {
            terminatePokerAsr(epoch, "audio-session-invalid")
            return
        }
        session.accept(frame)?.let { reason ->
            terminatePokerAsr(epoch, reason)
            return
        }
        val text = runCatching { session.provisionalText() }.getOrElse {
            terminatePokerAsr(epoch, "runtime-decode-failed")
            return
        }
        mutableState.update { it.copy(asr = it.asr.copy(provisionalText = text)) }
        sendPokerAsrProjection(epoch, session, immediate = false)
    }

    private suspend fun handlePokerAsrCommit(
        epoch: PokerConnectionEpoch,
        envelope: ProtocolEnvelope,
        request: PokerAsrCommitRequest,
    ) {
        (pokerAsrResults[request.operationId] as? PokerAsrCommitResult)?.let {
            sendPokerAsrResult(envelope, POKER_ASR_COMMIT_RESULT_TYPE, PokerAsrCommitResult.serializer(), it)
            return
        }
        val session = pokerAsrSession
        val rejection = when {
            session == null -> "ASR session is no longer active"
            session.sessionId != request.sessionId -> "ASR session is stale"
            session.target != request.target -> "ASR target is stale"
            request.fenceSampleOffset != session.nextSampleOffset -> "ASR commit fence is stale"
            else -> validatePokerAsrTarget(epoch, request.target)
        }
        if (rejection != null) {
            val result = PokerAsrCommitResult(
                request.target,
                request.sessionId,
                request.operationId,
                PokerAsrMutationOutcome.REJECTED,
                reason = rejection,
            )
            pokerAsrResults[request.operationId] = result
            sendPokerAsrResult(envelope, POKER_ASR_COMMIT_RESULT_TYPE, PokerAsrCommitResult.serializer(), result)
            return
        }

        val active = checkNotNull(session)
        val committedText = try {
            active.commitSlice(request.fenceSampleOffset)
        } catch (failure: Throwable) {
            terminatePokerAsr(epoch, "runtime-decode-failed")
            val result = PokerAsrCommitResult(
                request.target,
                request.sessionId,
                request.operationId,
                PokerAsrMutationOutcome.UNCERTAIN,
                reason = failure.message ?: "ASR commit is uncertain",
            )
            pokerAsrResults[request.operationId] = result
            sendPokerAsrResult(envelope, POKER_ASR_COMMIT_RESULT_TYPE, PokerAsrCommitResult.serializer(), result)
            return
        }
        val nextTarget = try {
            commitPokerAsrText(request.target, committedText)
        } catch (failure: Throwable) {
            terminatePokerAsr(epoch, "composer-durability-uncertain")
            val result = PokerAsrCommitResult(
                request.target,
                request.sessionId,
                request.operationId,
                PokerAsrMutationOutcome.UNCERTAIN,
                committedText = committedText,
                reason = failure.message ?: "ASR commit durability is uncertain",
            )
            pokerAsrResults[request.operationId] = result
            sendPokerAsrResult(envelope, POKER_ASR_COMMIT_RESULT_TYPE, PokerAsrCommitResult.serializer(), result)
            return
        }
        active.target = nextTarget
        mutableState.update {
            it.copy(asr = it.asr.copy(target = nextTarget, provisionalText = ""))
        }
        val result = PokerAsrCommitResult(
            request.target,
            request.sessionId,
            request.operationId,
            PokerAsrMutationOutcome.ACKNOWLEDGED,
            committedText = committedText,
            nextTarget = nextTarget,
        )
        pokerAsrResults[request.operationId] = result
        sendPokerAsrResult(envelope, POKER_ASR_COMMIT_RESULT_TYPE, PokerAsrCommitResult.serializer(), result)
        sendPokerAsrProjection(epoch, active, immediate = true)
    }

    private suspend fun handlePokerAsrDiscard(
        epoch: PokerConnectionEpoch,
        envelope: ProtocolEnvelope,
        request: PokerAsrDiscardRequest,
    ) {
        (pokerAsrResults[request.operationId] as? PokerAsrDiscardResult)?.let {
            sendPokerAsrResult(envelope, POKER_ASR_DISCARD_RESULT_TYPE, PokerAsrDiscardResult.serializer(), it)
            return
        }
        val session = pokerAsrSession
        val rejection = when {
            session == null -> "ASR session is no longer active"
            session.sessionId != request.sessionId -> "ASR session is stale"
            session.target != request.target -> "ASR target is stale"
            else -> validatePokerAsrTarget(epoch, request.target)
        }
        if (rejection != null) {
            val result = PokerAsrDiscardResult(
                request.target,
                request.sessionId,
                request.operationId,
                PokerAsrMutationOutcome.REJECTED,
                reason = rejection,
            )
            pokerAsrResults[request.operationId] = result
            sendPokerAsrResult(envelope, POKER_ASR_DISCARD_RESULT_TYPE, PokerAsrDiscardResult.serializer(), result)
            return
        }
        val active = checkNotNull(session)
        try {
            active.discardSlice()
        } catch (failure: Throwable) {
            terminatePokerAsr(epoch, "runtime-decode-failed")
            val result = PokerAsrDiscardResult(
                request.target,
                request.sessionId,
                request.operationId,
                PokerAsrMutationOutcome.UNCERTAIN,
                reason = failure.message ?: "ASR discard is uncertain",
            )
            pokerAsrResults[request.operationId] = result
            sendPokerAsrResult(envelope, POKER_ASR_DISCARD_RESULT_TYPE, PokerAsrDiscardResult.serializer(), result)
            return
        }
        mutableState.update { it.copy(asr = it.asr.copy(provisionalText = "")) }
        val result = PokerAsrDiscardResult(
            request.target,
            request.sessionId,
            request.operationId,
            PokerAsrMutationOutcome.ACKNOWLEDGED,
            nextTarget = active.target,
        )
        pokerAsrResults[request.operationId] = result
        sendPokerAsrResult(envelope, POKER_ASR_DISCARD_RESULT_TYPE, PokerAsrDiscardResult.serializer(), result)
        sendPokerAsrProjection(epoch, active, immediate = true)
    }

    private suspend fun handlePokerAsrExit(
        epoch: PokerConnectionEpoch,
        envelope: ProtocolEnvelope,
        request: PokerAsrExitRequest,
    ) {
        (pokerAsrResults[request.operationId] as? PokerAsrExitResult)?.let {
            sendPokerAsrResult(envelope, POKER_ASR_EXIT_RESULT_TYPE, PokerAsrExitResult.serializer(), it)
            return
        }
        if (pokerAsrPreparing?.sessionId == request.sessionId) {
            pokerAsrCancelledSessions += request.sessionId
            val result = PokerAsrExitResult(
                request.target,
                request.sessionId,
                request.operationId,
                PokerAsrMutationOutcome.ACKNOWLEDGED,
            )
            pokerAsrResults[request.operationId] = result
            sendPokerAsrResult(envelope, POKER_ASR_EXIT_RESULT_TYPE, PokerAsrExitResult.serializer(), result)
            return
        }
        val session = pokerAsrSession
        val rejection = when {
            session == null -> "ASR session is no longer active"
            session.sessionId != request.sessionId -> "ASR session is stale"
            session.target != request.target -> "ASR target is stale"
            else -> validatePokerAsrTarget(epoch, request.target)
        }
        val result = if (rejection != null) {
            PokerAsrExitResult(
                request.target,
                request.sessionId,
                request.operationId,
                PokerAsrMutationOutcome.REJECTED,
                reason = rejection,
            )
        } else {
                closePokerAsrSession()
            PokerAsrExitResult(
                request.target,
                request.sessionId,
                request.operationId,
                PokerAsrMutationOutcome.ACKNOWLEDGED,
            )
        }
        pokerAsrResults[request.operationId] = result
        sendPokerAsrResult(envelope, POKER_ASR_EXIT_RESULT_TYPE, PokerAsrExitResult.serializer(), result)
    }

    private fun validatePokerAsrTarget(
        epoch: PokerConnectionEpoch,
        target: PokerAsrTarget,
    ): String? {
        val state = mutableState.value
        if (target.connectionEpoch != epoch.value || pokerComposerEpoch != epoch) {
            return "ASR connection epoch is stale"
        }
        if (target.locator !in state.threadAttachments.attached ||
            !state.threadAttachments.hasDealerClaim(target.locator)
        ) return "Dealer control is unavailable"
        return when (target.field) {
            PokerAsrTargetField.COMPOSER -> {
                val draft = state.threadActions.composerDraft(target.locator)
                val binding = pokerComposerBindings[target.locator]
                when {
                    binding == null || binding.epoch != epoch.value ||
                        binding.controlGeneration != target.controlGeneration ||
                        binding.modeSession != target.modeSession -> "ASR composer target is stale"
                    target.targetRevision != draft.revision -> "ASR composer revision is stale"
                    target.cursorPosition !in 0 until draft.cursorCount -> "ASR composer cursor is stale"
                    else -> null
                }
            }
            PokerAsrTargetField.REQUEST_TEXT -> {
                val requestLocator = target.requestLocator
                val request = requestLocator?.let { state.userInputRequests.requests[it] }
                val buffer = requestLocator?.let(state.userInputAnswers::buffer)
                val question = request?.questions?.firstOrNull { it.id == target.questionId }
                val binding = requestLocator?.let(pokerUserInputBindings::get)
                when {
                    requestLocator == null || request == null || request.thread != target.locator ->
                        "ASR request target is stale"
                    request.resolution != RequestResolutionState.PENDING ->
                        "User-input request is no longer editable"
                    question == null -> "ASR question target is stale"
                    question.options != null &&
                        (!question.isOther || buffer?.answer(question.id)?.selectedOption != null) ->
                        "ASR question is not a text field"
                    buffer == null || target.targetRevision != buffer.revision ->
                        "ASR request revision is stale"
                    target.cursorPosition !in 0..ComposerDraft.fromText(buffer.activeValue(question)).cursorCount - 1 ->
                        "ASR request cursor is stale"
                    binding == null || binding.epoch != epoch.value ||
                        binding.controlGeneration != target.controlGeneration ||
                        binding.modeSession != target.modeSession -> "ASR request target is stale"
                    else -> null
                }
            }
        }
    }

    private suspend fun commitPokerAsrText(target: PokerAsrTarget, text: String): PokerAsrTarget {
        if (text.isEmpty()) return target
        return when (target.field) {
            PokerAsrTargetField.COMPOSER -> {
                val draft = mutableState.value.threadActions.composerDraft(target.locator)
                val next = insertDealerAsrText(draft, target.cursorPosition, text)
                draftMutex.withLock { threadAttachmentStore.writeDraft(target.locator, next) }
                mutableState.update {
                    it.copy(threadActions = it.threadActions.editComposerDraft(target.locator, next))
                }
                pokerComposerEpoch?.let { epoch -> sendPokerProjection(epoch, target.locator) }
                target.copy(
                    targetRevision = next.revision,
                    cursorPosition = dealerAsrCursorAfter(target.cursorPosition, text),
                )
            }
            PokerAsrTargetField.REQUEST_TEXT -> {
                val requestLocator = checkNotNull(target.requestLocator)
                val state = mutableState.value
                val request = state.userInputRequests.requests.getValue(requestLocator)
                val buffer = state.userInputAnswers.buffer(requestLocator)
                val question = request.questions.first { it.id == target.questionId }
                val currentText = buffer.activeValue(question)
                val nextText = insertDealerAsrText(currentText, target.cursorPosition, text)
                val next = buffer.edit(request, question.id, UserInputAnswerEdit.SetText(nextText))
                mutableState.update { it.copy(userInputAnswers = it.userInputAnswers.copy(buffers = it.userInputAnswers.buffers + (requestLocator to next))) }
                pokerComposerEpoch?.let { epoch -> sendPokerUserInputProjection(epoch, requestLocator) }
                target.copy(
                    targetRevision = next.revision,
                    cursorPosition = dealerAsrCursorAfter(target.cursorPosition, text),
                )
            }
        }
    }

    private suspend fun sendPokerAsrProjection(
        epoch: PokerConnectionEpoch,
        session: DealerAsrSliceSession,
        immediate: Boolean,
    ) {
        if (pokerComposerEpoch != epoch) return
        val projection = session.projection(immediate) ?: return
        sendPokerAsr(
            epoch = epoch,
            type = POKER_ASR_PROJECTION_TYPE,
            serializer = PokerAsrProjection.serializer(),
            value = projection,
        )
    }

    private suspend fun terminatePokerAsr(epoch: PokerConnectionEpoch, reason: String) {
        val session = pokerAsrSession ?: return
        val operationId = UUID.randomUUID().toString()
        val target = session.target
        closePokerAsrSession()
        sendPokerAsr(
            epoch = epoch,
            type = POKER_ASR_EXIT_RESULT_TYPE,
            serializer = PokerAsrExitResult.serializer(),
            value = PokerAsrExitResult(
                target = target,
                sessionId = session.sessionId,
                operationId = operationId,
                outcome = PokerAsrMutationOutcome.REJECTED,
                reason = reason,
            ),
        )
    }

    private fun closePokerAsrSession() {
        val session = pokerAsrSession ?: return
        pokerAsrSession = null
        mutableState.update { it.copy(asr = DealerAsrUiState()) }
        scope.launch {
            runCatching { session.close() }
        }
    }

    private suspend fun <T> sendPokerAsr(
        epoch: PokerConnectionEpoch,
        type: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        value: T,
    ) {
        if (pokerComposerEpoch != epoch) return
        pokerConnectionOwner.send(
            type = type,
            payload = PokerProtocolJson.encodeToJsonElement(serializer, value).jsonObject,
            requireWritable = false,
        )
    }

    private suspend fun <T> sendPokerAsrResult(
        envelope: ProtocolEnvelope,
        type: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        value: T,
    ) {
        pokerConnectionOwner.send(
            type = type,
            payload = PokerProtocolJson.encodeToJsonElement(serializer, value).jsonObject,
            replyTo = envelope.messageId,
            requireWritable = false,
        )
    }

    private suspend fun sendPokerProjection(
        epoch: PokerConnectionEpoch,
        locator: CodexThreadLocator,
    ) {
        if (pokerComposerEpoch != epoch) return
        val state = mutableState.value
        if (locator !in state.threadAttachments.attached) return
        val controlGeneration = state.threadAttachments.controlGeneration(locator)
        val current = pokerComposerBindings[locator]
        val modeSession = current
            ?.takeIf {
                it.epoch == epoch.value && it.controlGeneration == controlGeneration
            }
            ?.modeSession
            ?: UUID.randomUUID().toString()
        pokerComposerBindings[locator] = PokerComposerBinding(
            epoch = epoch.value,
            controlGeneration = controlGeneration,
            modeSession = modeSession,
        )
        val projection = ComposerDraftProjection(
            locator = locator,
            draft = state.threadActions.composerDraft(locator),
            controlGeneration = controlGeneration,
            connectionEpoch = epoch.value,
            modeSession = modeSession,
            activeTurnId = state.threads[locator]?.activeTurnId,
            hasDealerClaim = state.threadAttachments.hasDealerClaim(locator),
        )
        val payload = PokerProtocolJson.encodeToJsonElement(
            ComposerDraftProjection.serializer(),
            projection,
        ).jsonObject
        pokerConnectionOwner.send(POKER_COMPOSER_DRAFT_PROJECTION_TYPE, payload)
    }

    private suspend fun handlePokerPhotoStart(
        epoch: PokerConnectionEpoch,
        envelope: ProtocolEnvelope,
        target: PhotoStartTarget,
    ) {
        val state = mutableState.value
        val draft = state.threadActions.composerDraft(target.locator)
        val binding = pokerComposerBindings[target.locator]
        val existing = photoSessions[target.sessionId]
        val rejection = when {
            target.connectionEpoch != epoch.value || pokerComposerEpoch != epoch ->
                "Photo connection epoch is stale"
            target.locator !in state.threadAttachments.attached ||
                !state.threadAttachments.hasDealerClaim(target.locator) ->
                "Dealer control is unavailable"
            target.locator !in state.threads ||
                state.threads[target.locator]?.workState !in setOf(
                    ThreadWorkState.READY,
                    ThreadWorkState.BUSY,
                ) -> "Composer is not editable"
            state.threadActions.pendingInputs.containsKey(target.locator) ||
                state.threadActions.pendingInterrupts.containsKey(target.locator) ->
                "Composer has a pending action"
            binding == null || binding.epoch != epoch.value ||
                binding.controlGeneration != target.controlGeneration ||
                binding.modeSession != target.modeSession ->
                "Photo control target is stale"
            target.draftRevision != draft.revision -> "Composer draft revision is stale"
            target.cursorPosition !in 0 until draft.cursorCount -> "Composer cursor is stale"
            existing != null && existing.target != target -> "Photo session already exists"
            else -> null
        }
        val result = if (rejection == null) {
            if (existing == null) {
                photoSessions[target.sessionId] = DealerPhotoSession(
                    target = target,
                    cursorPosition = target.cursorPosition,
                )
            }
            PhotoStartResult(target, PhotoStartOutcome.ACCEPTED)
        } else {
            PhotoStartResult(target, PhotoStartOutcome.REJECTED, rejection)
        }
        pokerConnectionOwner.send(
            type = POKER_PHOTO_START_RESULT_TYPE,
            payload = PokerProtocolJson.encodeToJsonElement(
                PhotoStartResult.serializer(),
                result,
            ).jsonObject,
            replyTo = envelope.messageId,
        )
    }

    private suspend fun handlePokerPhotoCaptureBegin(
        epoch: PokerConnectionEpoch,
        envelope: ProtocolEnvelope,
        begin: PhotoCaptureBegin,
    ) {
        val target = begin.target
        val cached = photoResults[target.operationId]?.takeIf { it.target == target }
        if (cached != null) {
            sendPokerPhotoCaptureResult(envelope, cached)
            return
        }
        val state = mutableState.value
        val session = photoSessions[target.sessionId]
        val draft = state.threadActions.composerDraft(target.locator)
        val rejection = when {
            target.connectionEpoch != epoch.value || pokerComposerEpoch != epoch ->
                "Photo connection epoch is stale"
            session == null || session.target.connectionEpoch != epoch.value ->
                "Photo session is no longer active"
            target.locator != session.target.locator ||
                target.controlGeneration != session.target.controlGeneration ||
                target.modeSession != session.target.modeSession ->
                "Photo session target is stale"
            target.draftRevision != draft.revision ||
                target.cursorPosition != session.cursorPosition ||
                target.cursorPosition !in 0 until draft.cursorCount ->
                "Photo draft cursor is stale"
            target.assetId in session.committedAssetIds ||
                photoTransfers.values.any { it.target.assetId == target.assetId } ->
                "Photo asset is already in flight"
            else -> null
        }
        if (rejection != null) {
            sendPokerPhotoCaptureResult(
                envelope,
                PhotoCaptureResult(target, PhotoCaptureOutcome.REJECTED, draft, rejection),
            )
            return
        }
        if (!photoAssets.begin(target.assetId, begin.expectedLength)) {
            sendPokerPhotoCaptureResult(
                envelope,
                PhotoCaptureResult(
                    target,
                    PhotoCaptureOutcome.REJECTED,
                    draft,
                    "Insufficient storage",
                ),
            )
            return
        }
        val transfer = DealerPhotoTransfer(
            target = target,
            mimeType = begin.mimeType,
            expectedLength = begin.expectedLength,
            timeout = scope.launch {
                delay(PHOTO_TRANSFER_TIMEOUT_MS)
                val expired = photoTransfers.remove(target.operationId) ?: return@launch
                photoAssets.delete(expired.target.assetId)
                val result = PhotoCaptureResult(
                    expired.target,
                    PhotoCaptureOutcome.REJECTED,
                    mutableState.value.threadActions.composerDraft(expired.target.locator),
                    "Photo transfer timed out",
                )
                photoResults[expired.target.operationId] = result
                if (pokerComposerEpoch == epoch) sendPokerPhotoCaptureResult(null, result)
            },
        )
        photoTransfers[target.operationId] = transfer
    }

    private suspend fun handlePokerPhotoCaptureChunk(
        epoch: PokerConnectionEpoch,
        envelope: ProtocolEnvelope,
        chunk: PhotoCaptureChunk,
    ) {
        val transfer = photoTransfers[chunk.target.operationId] ?: return
        if (transfer.target != chunk.target || chunk.target.connectionEpoch != epoch.value) return
        val bytes = runCatching { PhotoAssetCodec.decode(chunk.data) }.getOrNull()
        if (bytes == null || chunk.offset != transfer.nextOffset ||
            chunk.offset + bytes.size > transfer.expectedLength ||
            !photoAssets.append(chunk.target.assetId, chunk.offset, bytes)
        ) {
            rejectPhotoTransfer(epoch, envelope, transfer, "Photo transfer is invalid")
            return
        }
        transfer.nextOffset += bytes.size
    }

    private suspend fun handlePokerPhotoCaptureComplete(
        epoch: PokerConnectionEpoch,
        envelope: ProtocolEnvelope,
        complete: PhotoCaptureComplete,
    ) {
        val target = complete.target
        photoResults[target.operationId]?.takeIf { it.target == target }?.let {
            sendPokerPhotoCaptureResult(envelope, it)
            return
        }
        val transfer = photoTransfers.remove(target.operationId) ?: return
        transfer.timeout.cancel()
        val state = mutableState.value
        val session = photoSessions[target.sessionId]
        val current = state.threadActions.composerDraft(target.locator)
        if (transfer.target != target || session == null || target.connectionEpoch != epoch.value ||
            complete.length != transfer.expectedLength || transfer.nextOffset != complete.length ||
            target.draftRevision != current.revision ||
            target.cursorPosition != session.cursorPosition
        ) {
            photoAssets.delete(target.assetId)
            rejectPhotoResult(
                epoch,
                envelope,
                target,
                current,
                "Photo transfer is stale or incomplete",
            )
            return
        }
        val committed = photoAssets.commit(
            assetId = target.assetId,
            mimeType = transfer.mimeType,
            expectedLength = complete.length,
            expectedSha256 = complete.sha256,
        )
        if (committed == null) {
            rejectPhotoResult(epoch, envelope, target, current, "Photo not added")
            return
        }
        val next = current
            .insertPhoto(target.cursorPosition, target.assetId)
            .withRevision(current.revision + 1)
        val response = try {
            draftMutex.withLock { threadAttachmentStore.writeDraft(target.locator, next) }
            mutableState.update {
                it.copy(threadActions = it.threadActions.editComposerDraft(target.locator, next))
            }
            session.cursorPosition = target.cursorPosition + 1
            session.committedAssetIds += target.assetId
            PhotoCaptureResult(target, PhotoCaptureOutcome.ACKNOWLEDGED, next)
        } catch (failure: Throwable) {
            PhotoCaptureResult(
                target,
                PhotoCaptureOutcome.UNCERTAIN,
                current,
                "Photo durability is uncertain: ${failure.message}",
            )
        }
        photoResults[target.operationId] = response
        sendPokerPhotoCaptureResult(envelope, response)
        if (response.outcome == PhotoCaptureOutcome.ACKNOWLEDGED) {
            sendPokerProjection(epoch, target.locator)
        }
    }

    private suspend fun handlePokerPhotoDelete(
        epoch: PokerConnectionEpoch,
        envelope: ProtocolEnvelope,
        target: PhotoAssetTarget,
    ) {
        photoDeleteResults[target.operationId]?.takeIf { it.target == target }?.let {
            sendPokerPhotoDeleteResult(envelope, it)
            return
        }
        val state = mutableState.value
        val current = state.threadActions.composerDraft(target.locator)
        val session = photoSessions[target.sessionId]
        val unit = current.visibleUnits().getOrNull(target.cursorPosition)
        val rejection = when {
            target.connectionEpoch != epoch.value || pokerComposerEpoch != epoch ->
                "Photo connection epoch is stale"
            session == null || session.target.modeSession != target.modeSession ->
                "Photo session is no longer active"
            target.draftRevision != current.revision ||
                target.cursorPosition !in 0 until current.cursorCount ->
                "Photo draft target is stale"
            unit?.photoAssetId != target.assetId -> "Photo token is no longer present"
            else -> null
        }
        if (rejection != null) {
            val result = PhotoDeleteResult(target, PhotoCaptureOutcome.REJECTED, current, rejection)
            photoDeleteResults[target.operationId] = result
            sendPokerPhotoDeleteResult(envelope, result)
            return
        }
        val next = current
            .replaceUnits(target.cursorPosition, target.cursorPosition + 1)
            .withRevision(current.revision + 1)
        val result = try {
            draftMutex.withLock { threadAttachmentStore.writeDraft(target.locator, next) }
            mutableState.update {
                it.copy(threadActions = it.threadActions.editComposerDraft(target.locator, next))
            }
            photoAssets.delete(target.assetId)
            session?.committedAssetIds?.remove(target.assetId)
            session?.cursorPosition = target.cursorPosition
            PhotoDeleteResult(target, PhotoCaptureOutcome.ACKNOWLEDGED, next)
        } catch (failure: Throwable) {
            PhotoDeleteResult(
                target,
                PhotoCaptureOutcome.UNCERTAIN,
                current,
                "Photo deletion is uncertain: ${failure.message}",
            )
        }
        photoDeleteResults[target.operationId] = result
        sendPokerPhotoDeleteResult(envelope, result)
        if (result.outcome == PhotoCaptureOutcome.ACKNOWLEDGED) {
            sendPokerProjection(epoch, target.locator)
        }
    }

    private suspend fun handlePokerPhotoCancel(
        epoch: PokerConnectionEpoch,
        target: PhotoStartTarget,
    ) {
        if (pokerComposerEpoch != epoch || target.connectionEpoch != epoch.value) return
        photoSessions.remove(target.sessionId)
        photoTransfers.values
            .filter { it.target.sessionId == target.sessionId }
            .toList()
            .forEach { transfer ->
                photoTransfers.remove(transfer.target.operationId)
                transfer.timeout.cancel()
                photoAssets.delete(transfer.target.assetId)
            }
    }

    private suspend fun rejectPhotoTransfer(
        epoch: PokerConnectionEpoch,
        envelope: ProtocolEnvelope,
        transfer: DealerPhotoTransfer,
        reason: String,
    ) {
        photoTransfers.remove(transfer.target.operationId)
        transfer.timeout.cancel()
        photoAssets.delete(transfer.target.assetId)
        rejectPhotoResult(
            epoch,
            envelope,
            transfer.target,
            mutableState.value.threadActions.composerDraft(transfer.target.locator),
            reason,
        )
    }

    private suspend fun rejectPhotoResult(
        epoch: PokerConnectionEpoch,
        envelope: ProtocolEnvelope?,
        target: PhotoAssetTarget,
        draft: ComposerDraft,
        reason: String,
    ) {
        val result = PhotoCaptureResult(target, PhotoCaptureOutcome.REJECTED, draft, reason)
        photoResults[target.operationId] = result
        if (pokerComposerEpoch == epoch) sendPokerPhotoCaptureResult(envelope, result)
    }

    private suspend fun sendPokerPhotoCaptureResult(
        envelope: ProtocolEnvelope?,
        result: PhotoCaptureResult,
    ) {
        pokerConnectionOwner.send(
            type = POKER_PHOTO_CAPTURE_RESULT_TYPE,
            payload = PokerProtocolJson.encodeToJsonElement(
                PhotoCaptureResult.serializer(),
                result,
            ).jsonObject,
            replyTo = envelope?.messageId,
        )
    }

    private suspend fun sendPokerPhotoDeleteResult(
        envelope: ProtocolEnvelope,
        result: PhotoDeleteResult,
    ) {
        pokerConnectionOwner.send(
            type = POKER_PHOTO_DELETE_RESULT_TYPE,
            payload = PokerProtocolJson.encodeToJsonElement(
                PhotoDeleteResult.serializer(),
                result,
            ).jsonObject,
            replyTo = envelope.messageId,
        )
    }

    private suspend fun sendPokerUserInputProjection(
        epoch: PokerConnectionEpoch,
        locator: ServerRequestLocator,
    ) {
        if (pokerComposerEpoch != epoch) return
        val state = mutableState.value
        val request = state.userInputRequests.requests[locator] ?: return
        if (request.thread !in state.threadAttachments.attached) {
            return
        }
        val controlGeneration = state.threadAttachments.controlGeneration(request.thread)
        val current = pokerUserInputBindings[locator]
        val modeSession = current
            ?.takeIf {
                it.epoch == epoch.value && it.controlGeneration == controlGeneration
            }
            ?.modeSession
            ?: UUID.randomUUID().toString()
        pokerUserInputBindings[locator] = PokerUserInputBinding(
            epoch = epoch.value,
            controlGeneration = controlGeneration,
            modeSession = modeSession,
        )
        val projection = UserInputRequestProjection(
            request = request,
            buffer = state.userInputAnswers.buffer(locator).takeUnless {
                request.resolution == RequestResolutionState.UNKNOWN ||
                    request.resolution == RequestResolutionState.RESOLVED
            } ?: UserInputAnswerBuffer(),
            cardId = request.itemId,
            controlGeneration = controlGeneration,
            connectionEpoch = epoch.value,
            modeSession = modeSession,
            hasDealerClaim = state.threadAttachments.hasDealerClaim(request.thread),
        )
        val payload = PokerProtocolJson.encodeToJsonElement(
            UserInputRequestProjection.serializer(),
            projection,
        ).jsonObject
        pokerConnectionOwner.send(POKER_USER_INPUT_PROJECTION_TYPE, payload)
    }

    private suspend fun sendPokerApprovalProjection(
        epoch: PokerConnectionEpoch,
        locator: ServerRequestLocator,
    ) {
        if (pokerComposerEpoch != epoch) return
        val state = mutableState.value
        val command = state.commandApprovals.requests[locator]
        val file = state.fileApprovals.requests[locator]
        val thread = command?.thread ?: file?.thread ?: return
        if (thread !in state.threadAttachments.attached) return
        val controlGeneration = state.threadAttachments.controlGeneration(thread)
        val current = pokerApprovalBindings[locator]
        val modeSession = current
            ?.takeIf { it.epoch == epoch.value && it.controlGeneration == controlGeneration }
            ?.modeSession
            ?: UUID.randomUUID().toString()
        pokerApprovalBindings[locator] = PokerApprovalBinding(
            epoch = epoch.value,
            controlGeneration = controlGeneration,
            modeSession = modeSession,
        )
        val projection = command?.toPokerApprovalProjection(
            controlGeneration = controlGeneration,
            connectionEpoch = epoch.value,
            modeSession = modeSession,
            hasDealerClaim = state.threadAttachments.hasDealerClaim(thread),
        ) ?: file!!.toPokerApprovalProjection(
            controlGeneration = controlGeneration,
            connectionEpoch = epoch.value,
            modeSession = modeSession,
            hasDealerClaim = state.threadAttachments.hasDealerClaim(thread),
        )
        val payload = PokerProtocolJson.encodeToJsonElement(
            PokerApprovalRequestProjection.serializer(),
            projection,
        ).jsonObject
        pokerConnectionOwner.send(POKER_APPROVAL_PROJECTION_TYPE, payload)
    }

    private fun refreshPokerUserInputProjection(locator: CodexThreadLocator) {
        pokerComposerEpoch?.let { epoch ->
            scope.launch {
                mutableState.value.userInputRequests.requests.values
                    .filter { it.thread == locator }
                    .forEach { request -> sendPokerUserInputProjection(epoch, request.locator) }
            }
        }
    }

    private fun refreshPokerApprovalProjection(locator: CodexThreadLocator) {
        pokerComposerEpoch?.let { epoch ->
            scope.launch {
                mutableState.value.commandApprovals.requests.values
                    .filter { it.thread == locator }
                    .forEach { request -> sendPokerApprovalProjection(epoch, request.locator) }
                mutableState.value.fileApprovals.requests.values
                    .filter { it.thread == locator }
                    .forEach { request -> sendPokerApprovalProjection(epoch, request.locator) }
            }
        }
    }

    private suspend fun handlePokerUserInputMutation(
        epoch: PokerConnectionEpoch,
        envelope: ProtocolEnvelope,
        request: UserInputAnswerMutationRequest,
    ) {
        val target = request.target
        val state = mutableState.value
        val pendingRequest = state.userInputRequests.requests[target.locator]
        val current = pendingRequest?.let { state.userInputAnswers.buffer(target.locator) }
            ?: UserInputAnswerBuffer()
        fun result(
            outcome: UserInputAnswerMutationOutcome,
            buffer: UserInputAnswerBuffer = current,
            reason: String? = null,
        ) = UserInputAnswerMutationResult(target, outcome, buffer, reason)

        pokerUserInputResults[target.operationId]
            ?.takeIf { it.target == target }
            ?.let { cached ->
                sendPokerUserInputMutationResult(envelope, cached)
                return
            }
        val binding = pokerUserInputBindings[target.locator]
        val rejection = when {
            pendingRequest == null -> result(
                UserInputAnswerMutationOutcome.REJECTED,
                reason = "User-input request is no longer known",
            )
            pendingRequest.resolution != RequestResolutionState.PENDING -> result(
                UserInputAnswerMutationOutcome.REJECTED,
                reason = "User-input request is no longer editable",
            )
            target.connectionEpoch != epoch.value || pokerComposerEpoch != epoch -> result(
                UserInputAnswerMutationOutcome.REJECTED,
                reason = "User-input connection epoch is stale",
            )
            target.locator.hostId != pendingRequest.thread.hostId -> result(
                UserInputAnswerMutationOutcome.REJECTED,
                reason = "User-input host target is stale",
            )
            !state.threadAttachments.hasDealerClaim(pendingRequest.thread) -> result(
                UserInputAnswerMutationOutcome.REJECTED,
                reason = "Dealer control is unavailable",
            )
            binding == null || binding.epoch != epoch.value ||
                binding.controlGeneration != target.controlGeneration ||
                binding.modeSession != target.modeSession -> result(
                UserInputAnswerMutationOutcome.REJECTED,
                reason = "User-input control target is stale",
            )
            target.answerRevision != current.revision -> result(
                UserInputAnswerMutationOutcome.REJECTED,
                reason = "User-input answer revision is stale",
            )
            else -> null
        }
        if (rejection != null) {
            pokerUserInputResults[target.operationId] = rejection
            sendPokerUserInputMutationResult(envelope, rejection)
            if (pendingRequest != null) sendPokerUserInputProjection(epoch, target.locator)
            return
        }

        val edit = try {
            when (request.kind) {
                UserInputAnswerMutationKind.SELECT_OPTION ->
                    UserInputAnswerEdit.SelectOption(request.value ?: error("Option is missing"))
                UserInputAnswerMutationKind.SELECT_OTHER -> UserInputAnswerEdit.SelectOther
                UserInputAnswerMutationKind.SET_TEXT ->
                    UserInputAnswerEdit.SetText(request.value ?: error("Text is missing"))
            }
        } catch (failure: Throwable) {
            val response = result(
                UserInputAnswerMutationOutcome.REJECTED,
                reason = failure.message ?: "User-input mutation is malformed",
            )
            pokerUserInputResults[target.operationId] = response
            sendPokerUserInputMutationResult(envelope, response)
            if (pendingRequest != null) sendPokerUserInputProjection(epoch, target.locator)
            return
        }
        val liveRequest = pendingRequest ?: return
        val next = try {
            current.edit(liveRequest, target.questionId, edit)
        } catch (failure: Throwable) {
            val response = result(
                UserInputAnswerMutationOutcome.REJECTED,
                reason = failure.message ?: "User-input mutation is stale",
            )
            pokerUserInputResults[target.operationId] = response
            sendPokerUserInputMutationResult(envelope, response)
            sendPokerUserInputProjection(epoch, target.locator)
            return
        }
        mutableState.update {
            it.copy(
                userInputAnswers = it.userInputAnswers.copy(
                    buffers = it.userInputAnswers.buffers + (target.locator to next),
                ),
            )
        }
        val response = result(UserInputAnswerMutationOutcome.ACKNOWLEDGED, next)
        pokerUserInputResults[target.operationId] = response
        sendPokerUserInputMutationResult(envelope, response)
        sendPokerUserInputProjection(epoch, target.locator)
    }

    private suspend fun sendPokerUserInputMutationResult(
        envelope: ProtocolEnvelope,
        result: UserInputAnswerMutationResult,
    ) {
        val payload = PokerProtocolJson.encodeToJsonElement(
            UserInputAnswerMutationResult.serializer(),
            result,
        ).jsonObject
        pokerConnectionOwner.send(
            type = POKER_USER_INPUT_MUTATION_RESULT_TYPE,
            payload = payload,
            replyTo = envelope.messageId,
        )
    }

    private suspend fun handlePokerMorseMutation(
        epoch: PokerConnectionEpoch,
        envelope: ProtocolEnvelope,
        request: MorseMutationRequest,
    ) {
        val target = request.target
        val mode = target.mode
        val state = mutableState.value
        val currentDraft = state.threadActions.composerDraft(mode.locator)
        val requestLocator = mode.requestLocator
        val pendingRequest = requestLocator?.let { state.userInputRequests.requests[it] }
        val currentBuffer = requestLocator?.let { state.userInputAnswers.buffer(it) }
            ?: UserInputAnswerBuffer()
        val question = pendingRequest?.questions?.firstOrNull { it.id == mode.questionId }
        val currentField = question?.let {
            ComposerDraft.fromText(currentBuffer.activeValue(it), currentBuffer.revision)
        }

        fun authoritativeCursor(): Int = when (mode.surface) {
            ComposerSurface.THREAD_COMPOSER -> currentDraft.cursorCount - 1
            ComposerSurface.REQUEST_PANEL -> currentField?.cursorCount?.minus(1) ?: 0
        }

        fun result(
            outcome: MorseMutationOutcome,
            draft: ComposerDraft = currentDraft,
            buffer: UserInputAnswerBuffer = currentBuffer,
            revision: Long = if (mode.surface == ComposerSurface.THREAD_COMPOSER) {
                draft.revision
            } else {
                buffer.revision
            },
            cursor: Int = authoritativeCursor(),
            reason: String? = null,
        ) = MorseMutationResult(
            target = target,
            outcome = outcome,
            composerDraft = draft.takeIf { mode.surface == ComposerSurface.THREAD_COMPOSER },
            answerBuffer = buffer.takeIf { mode.surface == ComposerSurface.REQUEST_PANEL },
            fieldRevision = revision,
            cursorPosition = cursor,
            reason = reason,
        )

        pokerMorseResults[target.operationId]?.let { cached ->
            if (cached.target == target) {
                sendPokerMorseMutationResult(envelope, cached)
            } else {
                sendPokerMorseMutationResult(
                    envelope,
                    result(
                        MorseMutationOutcome.REJECTED,
                        reason = "Morse operation ID was reused for another target",
                    ),
                )
            }
            return
        }

        val binding = when (mode.surface) {
            ComposerSurface.THREAD_COMPOSER -> pokerComposerBindings[mode.locator]
            ComposerSurface.REQUEST_PANEL -> requestLocator?.let(pokerUserInputBindings::get)
        }
        val rejection = when {
            mode.connectionEpoch != epoch.value || pokerComposerEpoch != epoch ->
                result(MorseMutationOutcome.REJECTED, reason = "Morse connection epoch is stale")
            mode.surface == ComposerSurface.THREAD_COMPOSER &&
                (requestLocator != null || mode.questionId != null || mode.requestFingerprint != null) ->
                result(MorseMutationOutcome.REJECTED, reason = "Morse composer target is malformed")
            mode.surface == ComposerSurface.REQUEST_PANEL &&
                (requestLocator == null || mode.questionId.isNullOrBlank() ||
                    mode.requestFingerprint.isNullOrBlank()) ->
                result(MorseMutationOutcome.REJECTED, reason = "Morse request target is malformed")
            mode.locator !in state.threadAttachments.attached ||
                !state.threadAttachments.hasDealerClaim(mode.locator) ->
                result(MorseMutationOutcome.REJECTED, reason = "Dealer control is unavailable")
            mode.surface == ComposerSurface.THREAD_COMPOSER &&
                (binding !is PokerComposerBinding || binding.epoch != epoch.value ||
                    binding.controlGeneration != mode.controlGeneration ||
                    binding.modeSession != mode.bindingModeSession) ->
                result(MorseMutationOutcome.REJECTED, reason = "Morse binding target is stale")
            mode.surface == ComposerSurface.REQUEST_PANEL &&
                (binding !is PokerUserInputBinding || binding.epoch != epoch.value ||
                    binding.controlGeneration != mode.controlGeneration ||
                    binding.modeSession != mode.bindingModeSession) ->
                result(MorseMutationOutcome.REJECTED, reason = "Morse binding target is stale")
            mode.surface == ComposerSurface.THREAD_COMPOSER &&
                (mode.revision != currentDraft.revision ||
                    mode.cursorPosition !in 0 until currentDraft.cursorCount) ->
                result(MorseMutationOutcome.REJECTED, reason = "Morse composer revision is stale")
            mode.surface == ComposerSurface.REQUEST_PANEL &&
                (pendingRequest == null || pendingRequest.resolution != RequestResolutionState.PENDING) ->
                result(MorseMutationOutcome.REJECTED, reason = "Morse request is no longer editable")
            mode.surface == ComposerSurface.REQUEST_PANEL &&
                (pendingRequest?.thread != mode.locator ||
                    pendingRequest?.fingerprint != mode.requestFingerprint ||
                    question == null ||
                    !(question.options == null ||
                        (question.isOther && currentBuffer.answer(question.id).selectedOption == null)) ||
                    currentBuffer.revision != mode.revision ||
                    mode.cursorPosition !in 0 until (currentField?.cursorCount ?: 0)) ->
                result(MorseMutationOutcome.REJECTED, reason = "Morse request target is stale")
            else -> null
        }
        if (rejection != null) {
            pokerMorseResults[target.operationId] = rejection
            sendPokerMorseMutationResult(envelope, rejection)
            if (mode.surface == ComposerSurface.THREAD_COMPOSER) {
                sendPokerProjection(epoch, mode.locator)
            } else {
                requestLocator?.let { sendPokerUserInputProjection(epoch, it) }
            }
            return
        }

        val operation = try {
            when (request.kind) {
                MorseMutationKind.COMMIT_WORD -> {
                    val text = request.text ?: error("Morse commit text is missing")
                    require(text.endsWith(" ") && text.dropLast(1).isNotBlank()) {
                        "Morse commit text is malformed"
                    }
                    val next = when (mode.surface) {
                        ComposerSurface.THREAD_COMPOSER -> currentDraft
                            .insertText(mode.cursorPosition, text)
                            .withRevision(currentDraft.revision + 1)
                        ComposerSurface.REQUEST_PANEL -> {
                            val field = checkNotNull(currentField)
                            val nextText = field.insertText(mode.cursorPosition, text).displayText
                            checkNotNull(pendingRequest).let {
                                currentBuffer.edit(
                                    it,
                                    checkNotNull(mode.questionId),
                                    UserInputAnswerEdit.SetText(nextText),
                                )
                            }
                        }
                    }
                    next
                }
                MorseMutationKind.DELETE_COMMITTED_WORD -> {
                    val start = request.deleteStart ?: error("Morse deletion start is missing")
                    val end = request.deleteEndExclusive ?: error("Morse deletion end is missing")
                    val expected = request.expectedText ?: error("Morse deletion text is missing")
                    require(end == mode.cursorPosition) { "Morse deletion must end at the Morse cursor" }
                    when (mode.surface) {
                        ComposerSurface.THREAD_COMPOSER -> {
                            val units = currentDraft.visibleUnits()
                            require(start in 0 until end && end <= units.size)
                            require(units.subList(start, end).all { !it.isPhoto }) {
                                "Morse deletion cannot cross a photo"
                            }
                            require(units.subList(start, end).joinToString("") { it.text.orEmpty() } == expected) {
                                "Morse deletion text is stale"
                            }
                            currentDraft.replaceUnits(start, end)
                                .withRevision(currentDraft.revision + 1)
                        }
                        ComposerSurface.REQUEST_PANEL -> {
                            val field = checkNotNull(currentField)
                            val units = field.visibleUnits()
                            require(start in 0 until end && end <= units.size)
                            require(units.subList(start, end).all { !it.isPhoto })
                            require(units.subList(start, end).joinToString("") { it.text.orEmpty() } == expected) {
                                "Morse deletion text is stale"
                            }
                            val nextText = field.replaceUnits(start, end).displayText
                            checkNotNull(pendingRequest).let {
                                currentBuffer.edit(
                                    it,
                                    checkNotNull(mode.questionId),
                                    UserInputAnswerEdit.SetText(nextText),
                                )
                            }
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            result(
                MorseMutationOutcome.REJECTED,
                reason = failure.message ?: "Morse mutation is stale",
            ).also { response ->
                pokerMorseResults[target.operationId] = response
                sendPokerMorseMutationResult(envelope, response)
            }
            return
        }

        val response = when (mode.surface) {
            ComposerSurface.THREAD_COMPOSER -> {
                val nextDraft = operation as ComposerDraft
                val persistenceFailure = runCatching {
                    draftMutex.withLock { threadAttachmentStore.writeDraft(mode.locator, nextDraft) }
                }.exceptionOrNull()
                if (persistenceFailure != null) {
                    result(
                        MorseMutationOutcome.UNCERTAIN,
                        reason = "Composer durability is uncertain: ${persistenceFailure.message}",
                    )
                } else {
                    mutableState.update {
                        it.copy(threadActions = it.threadActions.editComposerDraft(mode.locator, nextDraft))
                    }
                    result(
                        MorseMutationOutcome.ACKNOWLEDGED,
                        draft = nextDraft,
                        revision = nextDraft.revision,
                        cursor = mode.cursorPosition + (nextDraft.cursorCount - currentDraft.cursorCount),
                    )
                }
            }
            ComposerSurface.REQUEST_PANEL -> {
                val nextBuffer = operation as UserInputAnswerBuffer
                mutableState.update {
                    it.copy(
                        userInputAnswers = it.userInputAnswers.copy(
                            buffers = it.userInputAnswers.buffers + (checkNotNull(requestLocator) to nextBuffer),
                        ),
                    )
                }
                result(
                    MorseMutationOutcome.ACKNOWLEDGED,
                    buffer = nextBuffer,
                    revision = nextBuffer.revision,
                    cursor = mode.cursorPosition +
                        (ComposerDraft.fromText(nextBuffer.activeValue(checkNotNull(question))).cursorCount -
                            currentField!!.cursorCount),
                )
            }
        }
        pokerMorseResults[target.operationId] = response
        sendPokerMorseMutationResult(envelope, response)
        if (response.outcome == MorseMutationOutcome.ACKNOWLEDGED) {
            if (mode.surface == ComposerSurface.THREAD_COMPOSER) {
                sendPokerProjection(epoch, mode.locator)
            } else {
                sendPokerUserInputProjection(epoch, checkNotNull(requestLocator))
            }
        }
    }

    private suspend fun sendPokerMorseMutationResult(
        envelope: ProtocolEnvelope,
        result: MorseMutationResult,
    ) {
        val payload = PokerProtocolJson.encodeToJsonElement(
            MorseMutationResult.serializer(),
            result,
        ).jsonObject
        pokerConnectionOwner.send(
            type = POKER_MORSE_MUTATION_RESULT_TYPE,
            payload = payload,
            replyTo = envelope.messageId,
        )
    }

    private suspend fun handlePokerPrimaryAction(
        epoch: PokerConnectionEpoch,
        envelope: ProtocolEnvelope,
        target: PokerPrimaryActionTarget,
    ) {
        pokerPrimaryResults[target.operationId]?.let { cached ->
            if (cached.target == target) {
                sendPokerPrimaryActionResult(envelope, cached)
            } else {
                sendPokerPrimaryActionResult(
                    envelope,
                    PokerPrimaryActionResult(
                        target = target,
                        outcome = PokerPrimaryActionOutcome.REJECTED,
                        reason = "Primary operation ID was reused for another target",
                    ),
                )
            }
            return
        }
        val state = mutableState.value
        val rejection = when {
            target.connectionEpoch != epoch.value || pokerComposerEpoch != epoch ->
                "Primary connection epoch is stale"
            target.action == PokerPrimaryAction.REQUEST -> {
                val requestLocator = target.requestLocator
                if (target.approvalDecision != null) {
                    val command = requestLocator?.let { state.commandApprovals.requests[it] }
                    val file = requestLocator?.let { state.fileApprovals.requests[it] }
                    val thread = command?.thread ?: file?.thread
                    val binding = requestLocator?.let(pokerApprovalBindings::get)
                    val projection = command?.toPokerApprovalProjection(
                        controlGeneration = target.controlGeneration,
                        connectionEpoch = target.connectionEpoch,
                        modeSession = target.modeSession,
                        hasDealerClaim = thread?.let(state.threadAttachments::hasDealerClaim) == true,
                    ) ?: file?.toPokerApprovalProjection(
                        controlGeneration = target.controlGeneration,
                        connectionEpoch = target.connectionEpoch,
                        modeSession = target.modeSession,
                        hasDealerClaim = thread?.let(state.threadAttachments::hasDealerClaim) == true,
                    )
                    when {
                        requestLocator == null || thread == null || projection == null ->
                            "Approval request is no longer known"
                        target.locator != thread -> "Primary approval thread target is stale"
                        projection.resolution != RequestResolutionState.PENDING ->
                            "Approval request is no longer pending"
                        !state.threadAttachments.hasDealerClaim(thread) ->
                            "Dealer control is unavailable"
                        binding == null || binding.epoch != epoch.value ||
                            binding.controlGeneration != target.controlGeneration ||
                            binding.modeSession != target.modeSession ->
                            "Primary approval control target is stale"
                        target.answerRevision != null -> "Approval target has an answer revision"
                        target.requestFingerprint != projection.fingerprint ->
                            "Approval request fingerprint is stale"
                        !projection.actionable -> "Approval scope is incomplete or unsafe"
                        target.approvalDecision !in projection.choices ->
                            "Approval choice is unavailable"
                        else -> null
                    }
                } else {
                    val request = requestLocator?.let { state.userInputRequests.requests[it] }
                    val binding = requestLocator?.let(pokerUserInputBindings::get)
                    val buffer = requestLocator?.let(state.userInputAnswers::buffer)
                    when {
                        requestLocator == null || request == null -> "User-input request is no longer known"
                        target.locator != request.thread -> "Primary request thread target is stale"
                        request.resolution != RequestResolutionState.PENDING ->
                            "User-input request is no longer editable"
                        !state.threadAttachments.hasDealerClaim(request.thread) ->
                            "Dealer control is unavailable"
                        binding == null || binding.epoch != epoch.value ||
                            binding.controlGeneration != target.controlGeneration ||
                            binding.modeSession != target.modeSession ->
                            "Primary request control target is stale"
                        target.answerRevision != buffer?.revision -> "User-input answer revision is stale"
                        target.requestFingerprint != request.fingerprint ->
                            "User-input request fingerprint is stale"
                        buffer == null || !buffer.isComplete(request) ->
                            "User-input response is incomplete"
                        else -> null
                    }
                }
            }
            else -> {
                val locator = target.locator
                val thread = state.threads[locator]
                val draft = state.threadActions.composerDraft(locator)
                val binding = pokerComposerBindings[locator]
                val expectedAction = when (thread?.workState) {
                    ThreadWorkState.READY -> PokerPrimaryAction.SEND.takeIf { draft.isSubmittable }
                    ThreadWorkState.BUSY -> when {
                        thread.activeTurnId == null -> null
                        draft.isSubmittable -> PokerPrimaryAction.STEER
                        else -> PokerPrimaryAction.INTERRUPT
                    }
                    ThreadWorkState.ATTENTION_REQUIRED,
                    null,
                    -> null
                }
                when {
                    locator !in state.threadAttachments.attached ||
                        !state.threadAttachments.hasDealerClaim(locator) ->
                        "Dealer control is unavailable"
                    binding == null || binding.epoch != epoch.value ||
                        binding.controlGeneration != target.controlGeneration ||
                        binding.modeSession != target.modeSession ->
                        "Primary composer control target is stale"
                    expectedAction != target.action ->
                        "Primary semantic action is stale"
                    target.draftRevision != null && target.draftRevision != draft.revision ->
                        "Composer draft revision is stale"
                    target.cursorPosition != null &&
                        target.cursorPosition !in 0 until draft.cursorCount ->
                        "Composer cursor is stale"
                    target.action == PokerPrimaryAction.STEER &&
                        target.expectedTurnId != thread?.activeTurnId ->
                        "Steer turn target is stale"
                    target.action == PokerPrimaryAction.INTERRUPT &&
                        target.expectedTurnId != thread?.activeTurnId ->
                        "Interrupt turn target is stale"
                    else -> null
                }
            }
        }
        if (rejection != null) {
            sendPokerPrimaryActionResult(
                envelope,
                PokerPrimaryActionResult(
                    target = target,
                    outcome = PokerPrimaryActionOutcome.REJECTED,
                    reason = rejection,
                ),
            )
            return
        }

        pokerPrimaryResults[target.operationId] = PokerPrimaryActionResult(
            target = target,
            outcome = PokerPrimaryActionOutcome.UNKNOWN,
            reason = "Primary action is in flight",
        )

        val complete: (PokerPrimaryActionOutcome) -> Unit = { outcome ->
            scope.launch {
                sendPokerPrimaryActionResult(
                    envelope,
                    PokerPrimaryActionResult(target, outcome),
                )
            }
        }
        when (target.action) {
            PokerPrimaryAction.REQUEST -> {
                val requestLocator = checkNotNull(target.requestLocator)
                val approvalDecision = target.approvalDecision
                if (approvalDecision != null) {
                    val command = state.commandApprovals.requests[requestLocator]
                    val file = state.fileApprovals.requests[requestLocator]
                    if (command != null) {
                        resolveCommandApproval(
                            locator = requestLocator,
                            decision = approvalDecision.toCommandApprovalDecision(),
                            onOutcome = complete,
                        )
                    } else if (file != null) {
                        resolveFileApproval(
                            locator = requestLocator,
                            decision = approvalDecision.toFileApprovalDecision(),
                            onOutcome = complete,
                        )
                    } else {
                        complete(PokerPrimaryActionOutcome.REJECTED)
                    }
                } else {
                    val answers = state.userInputAnswers.buffer(requestLocator)
                        .response(state.userInputRequests.requests.getValue(requestLocator))
                    respondUserInput(
                        locator = requestLocator,
                        answers = answers,
                        outcome = UserInputOutcome.ANSWERED,
                        requireControl = true,
                        onOutcome = complete,
                    )
                }
            }
            PokerPrimaryAction.SEND -> submitDraft(
                locator = target.locator,
                expectedDraftRevision = target.draftRevision,
                expectedCursorPosition = target.cursorPosition,
                expectedAction = ComposerAction.START,
                onOutcome = complete,
            )
            PokerPrimaryAction.STEER -> submitDraft(
                locator = target.locator,
                expectedDraftRevision = target.draftRevision,
                expectedCursorPosition = target.cursorPosition,
                expectedAction = ComposerAction.STEER,
                expectedTurnId = target.expectedTurnId,
                onOutcome = complete,
            )
            PokerPrimaryAction.INTERRUPT -> interrupt(
                locator = target.locator,
                expectedTurnId = target.expectedTurnId,
                onOutcome = complete,
            )
        }
    }

    private suspend fun sendPokerPrimaryActionResult(
        envelope: ProtocolEnvelope,
        result: PokerPrimaryActionResult,
    ) {
        pokerPrimaryResults[result.target.operationId] = result
        val payload = PokerProtocolJson.encodeToJsonElement(
            PokerPrimaryActionResult.serializer(),
            result,
        ).jsonObject
        pokerConnectionOwner.send(
            type = POKER_PRIMARY_ACTION_RESULT_TYPE,
            payload = payload,
            replyTo = envelope.messageId,
        )
    }

    private suspend fun handlePokerComposerMutation(
        epoch: PokerConnectionEpoch,
        envelope: ProtocolEnvelope,
        request: ComposerMutationRequest,
    ) {
        val target = request.target
        val current = mutableState.value.threadActions.composerDraft(target.locator)
        fun result(
            outcome: ComposerMutationOutcome,
            draft: ComposerDraft = current,
            reason: String? = null,
        ) = ComposerMutationResult(target, outcome, draft, reason)

        val binding = pokerComposerBindings[target.locator]
        val rejection = when {
            request.kind !in setOf(
                ComposerMutationKind.DELETE_THROUGH_NEXT_WORD,
                ComposerMutationKind.DELETE_PHOTO,
            ) ->
                result(ComposerMutationOutcome.REJECTED, reason = "Unsupported composer mutation")
            target.surface != ComposerSurface.THREAD_COMPOSER ->
                result(ComposerMutationOutcome.REJECTED, reason = "Request panels are not composers")
            target.connectionEpoch != epoch.value || pokerComposerEpoch != epoch ->
                result(ComposerMutationOutcome.REJECTED, reason = "Composer connection epoch is stale")
            target.locator !in mutableState.value.threadAttachments.attached ||
                !mutableState.value.threadAttachments.hasDealerClaim(target.locator) ->
                result(ComposerMutationOutcome.REJECTED, reason = "Dealer control is unavailable")
            binding == null || binding.epoch != epoch.value ||
                binding.controlGeneration != target.controlGeneration ||
                binding.modeSession != target.modeSession ->
                result(ComposerMutationOutcome.REJECTED, reason = "Composer control target is stale")
            else -> null
        }
        if (rejection != null) {
            sendPokerMutationResult(envelope, rejection)
            return
        }

        pokerComposerResults[target.locator]
            ?.takeIf { it.target == target }
            ?.let {
                sendPokerMutationResult(envelope, it)
                return
            }

        if (request.kind == ComposerMutationKind.DELETE_PHOTO) {
            val assetId = request.assetId
            val unit = current.visibleUnits().getOrNull(target.cursorPosition)
            val response = when {
                assetId.isNullOrBlank() -> result(
                    ComposerMutationOutcome.REJECTED,
                    reason = "Photo asset is missing",
                )
                target.draftRevision != current.revision ||
                    unit?.photoAssetId != assetId -> result(
                    ComposerMutationOutcome.REJECTED,
                    reason = "Photo token is stale",
                )
                else -> {
                    val next = current
                        .replaceUnits(target.cursorPosition, target.cursorPosition + 1)
                        .withRevision(current.revision + 1)
                    runCatching {
                        draftMutex.withLock {
                            threadAttachmentStore.writeDraft(target.locator, next)
                        }
                        mutableState.update {
                            it.copy(threadActions = it.threadActions.editComposerDraft(target.locator, next))
                        }
                        photoAssets.delete(assetId)
                        result(ComposerMutationOutcome.ACKNOWLEDGED, next)
                    }.getOrElse { failure ->
                        result(
                            ComposerMutationOutcome.UNCERTAIN,
                            reason = "Photo deletion is uncertain: ${failure.message}",
                        )
                    }
                }
            }
            pokerComposerResults[target.locator] = response
            sendPokerMutationResult(envelope, response)
            if (response.outcome == ComposerMutationOutcome.ACKNOWLEDGED) {
                sendPokerProjection(epoch, target.locator)
            }
            return
        }

        val editor = try {
            ComposerEditorState.atEnd(
                locator = target.locator,
                draft = current,
                controlGeneration = target.controlGeneration,
                connectionEpoch = target.connectionEpoch,
                modeSession = target.modeSession,
            ).copy(cursorPosition = target.cursorPosition)
        } catch (failure: IllegalArgumentException) {
            val response = result(
                ComposerMutationOutcome.REJECTED,
                reason = failure.message ?: "Composer cursor is stale",
            )
            sendPokerMutationResult(envelope, response)
            return
        }
        val edit = try {
            editor.beginTextDeletion(target)
        } catch (failure: IllegalArgumentException) {
            val response = result(
                ComposerMutationOutcome.REJECTED,
                reason = failure.message ?: "Composer target is stale",
            )
            sendPokerMutationResult(envelope, response)
            return
        }
        val response = when (edit) {
            is ComposerEditResult.NoChange -> result(
                ComposerMutationOutcome.REJECTED,
                reason = "Composer has no next word",
            )
            is ComposerEditResult.PhotoTokenBoundary -> result(
                ComposerMutationOutcome.REJECTED,
                reason = "Photo tokens require their own deletion transaction",
            )
            is ComposerEditResult.Started -> {
                var persistenceFailure: Throwable? = null
                try {
                    draftMutex.withLock {
                        threadAttachmentStore.writeDraft(target.locator, edit.mutation.optimistic)
                    }
                } catch (failure: Throwable) {
                    persistenceFailure = failure
                }
                if (persistenceFailure == null) {
                    mutableState.update {
                        it.copy(
                            threadActions = it.threadActions.editComposerDraft(
                                target.locator,
                                edit.mutation.optimistic,
                            ),
                        )
                    }
                    result(ComposerMutationOutcome.ACKNOWLEDGED, edit.mutation.optimistic)
                } else {
                    result(
                        ComposerMutationOutcome.UNCERTAIN,
                        reason = "Composer durability is uncertain: ${persistenceFailure.message}",
                    )
                }
            }
        }
        pokerComposerResults[target.locator] = response
        sendPokerMutationResult(envelope, response)
        if (response.outcome == ComposerMutationOutcome.ACKNOWLEDGED) {
            sendPokerProjection(epoch, target.locator)
        }
    }

    private suspend fun sendPokerMutationResult(
        envelope: ProtocolEnvelope,
        result: ComposerMutationResult,
    ) {
        val payload = PokerProtocolJson.encodeToJsonElement(
            ComposerMutationResult.serializer(),
            result,
        ).jsonObject
        pokerConnectionOwner.send(
            type = POKER_COMPOSER_MUTATION_RESULT_TYPE,
            payload = payload,
            replyTo = envelope.messageId,
        )
    }

    fun submitDraft(
        locator: CodexThreadLocator,
        expectedDraftRevision: Long? = null,
        expectedCursorPosition: Int? = null,
        expectedAction: ComposerAction? = null,
        expectedTurnId: String? = null,
        onOutcome: (PokerPrimaryActionOutcome) -> Unit = {},
    ) {
        val state = mutableState.value
        val thread = state.threads[locator]
        val draft = state.threadActions.composerDraft(locator)
        val appServer = hostSessions.connectedSession(locator.hostId)?.appServer
        if (appServer == null) {
            mutableState.update { it.copy(error = "Connect ${locator.hostId} before sending") }
            onOutcome(PokerPrimaryActionOutcome.REJECTED)
            return
        }
        if (expectedDraftRevision != null && expectedDraftRevision != draft.revision) {
            mutableState.update { it.copy(error = "Composer draft revision is stale") }
            onOutcome(PokerPrimaryActionOutcome.REJECTED)
            return
        }
        if (expectedCursorPosition != null && expectedCursorPosition !in 0 until draft.cursorCount) {
            mutableState.update { it.copy(error = "Composer cursor is stale") }
            onOutcome(PokerPrimaryActionOutcome.REJECTED)
            return
        }
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
            onOutcome(PokerPrimaryActionOutcome.REJECTED)
            return
        }
        if (expectedAction != null && pending.action != expectedAction ||
            expectedTurnId != null && pending.expectedTurnId != expectedTurnId
        ) {
            mutableState.update { it.copy(error = "Primary semantic action is stale") }
            onOutcome(PokerPrimaryActionOutcome.REJECTED)
            return
        }
        val text = pending.draftText
        val reasoningEffort = actions.pendingReasoningEfforts[locator]
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
                onOutcome(PokerPrimaryActionOutcome.REJECTED)
                return@launch
            }
            try {
                val input = pending.draft.elements.map { element ->
                    when (element) {
                        is ComposerElement.Text -> AppServerTurnInput.text(element.value)
                        is ComposerElement.Photo -> {
                            val image = photoAssets.read(element.assetId)
                                ?: error("Photo asset ${element.assetId} is unavailable")
                            AppServerTurnInput.image(
                                PhotoAssetCodec.dataUrl(image.mimeType, image.bytes),
                            )
                        }
                    }
                }
                val response = when (pending.action) {
                    ComposerAction.START -> appServer.turnStart(
                        locator.threadId,
                        input,
                        clientId,
                        effort = reasoningEffort,
                    )
                    ComposerAction.STEER -> appServer.turnSteer(
                        locator.threadId,
                        pending.expectedTurnId!!,
                        input,
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
                recordThreadTransition(locator)
                if (clearAcceptedDraft) {
                    val retainedDraft = mutableState.value.threadActions.composerDraft(locator)
                    runCatching {
                        draftMutex.withLock {
                            threadAttachmentStore.writeDraft(locator, retainedDraft)
                            threadAttachmentStore.writePendingInput(locator, null)
                            threadAttachmentStore.writeReasoningEffort(
                                locator,
                                mutableState.value.threadActions.pendingReasoningEfforts[locator],
                            )
                        }
                    }.onFailure { failure ->
                        mutableState.update {
                            it.copy(error = "Input was accepted, but local cleanup failed: ${failure.message}")
                        }
                    }
                }
                purgeUnusedPhotoAssets()
                refreshPokerProjection(locator)
                onOutcome(PokerPrimaryActionOutcome.ACCEPTED)
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
                onOutcome(
                    if (cleanupFailure == null) {
                        PokerPrimaryActionOutcome.REJECTED
                    } else {
                        PokerPrimaryActionOutcome.UNKNOWN
                    },
                )
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
                onOutcome(PokerPrimaryActionOutcome.UNKNOWN)
            }
        }
    }

    private suspend fun purgeUnusedPhotoAssets() {
        val actions = mutableState.value.threadActions
        val assetIds = (actions.composerDrafts.values + actions.pendingInputs.values.map { it.draft })
            .flatMap { draft ->
                draft.elements.mapNotNull { element ->
                    (element as? ComposerElement.Photo)?.assetId
                }
            }
            .toSet() + photoTransfers.values.map { it.target.assetId }
        photoAssets.purgeExcept(assetIds)
    }

    fun interrupt(
        locator: CodexThreadLocator,
        expectedTurnId: String? = null,
        onOutcome: (PokerPrimaryActionOutcome) -> Unit = {},
    ) {
        val state = mutableState.value
        val currentTurnId = state.threads[locator]?.activeTurnId
        if (expectedTurnId != null && expectedTurnId != currentTurnId) {
            mutableState.update { it.copy(error = "Interrupt turn target is stale") }
            onOutcome(PokerPrimaryActionOutcome.REJECTED)
            return
        }
        val appServer = hostSessions.connectedSession(locator.hostId)?.appServer ?: run {
            mutableState.update { it.copy(error = "Connect ${locator.hostId} before interrupting") }
            onOutcome(PokerPrimaryActionOutcome.REJECTED)
            return
        }
        val (actions, turnId) = try {
            state.threadActions.beginInterrupt(
                locator,
                state.threads[locator]?.activeTurnId,
                state.threadAttachments.hasDealerClaim(locator),
            )
        } catch (failure: IllegalArgumentException) {
            mutableState.update { it.copy(error = failure.message) }
            onOutcome(PokerPrimaryActionOutcome.REJECTED)
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
                onOutcome(PokerPrimaryActionOutcome.REJECTED)
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
                onOutcome(PokerPrimaryActionOutcome.REJECTED)
            } catch (failure: Throwable) {
                mutableState.update {
                    it.copy(error = "${failure.message ?: failure::class.java.simpleName}; interrupt was not replayed")
                }
                browseThread(locator)
                onOutcome(PokerPrimaryActionOutcome.UNKNOWN)
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
                val discovered = HostThreadDiscovery(appServer).discover(hostId)
                mutableState.update { it.withDiscoveredThreads(hostId, discovered) }
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
                threadNotificationTracker.reconciled(
                    hostId,
                    mutableState.value.threads.values,
                )
            }
        }
    }

    fun beginNewThread(hostId: String) {
        val observed = mutableState.value.threads.values
            .asSequence()
            .filter { it.locator.hostId == hostId }
            .mapNotNull(DiscoveredThread::workingDirectory)
            .distinct()
            .sorted()
            .toList()
        beginThreadReview(hostId, null, observed, observed.firstOrNull().orEmpty())
    }

    fun beginForkThread(locator: CodexThreadLocator) {
        val source = mutableState.value.threads[locator]
        if (source == null) {
            mutableState.update { it.copy(error = "Unknown thread ${locator.threadId}") }
            return
        }
        if (!source.canFork()) {
            mutableState.update { it.copy(error = "Only a READY thread can be forked") }
            return
        }
        val workingDirectory = source.workingDirectory
        if (workingDirectory == null) {
            mutableState.update { it.copy(error = "Refresh the thread working directory before forking") }
            return
        }
        val observed = mutableState.value.threads.values
            .asSequence()
            .filter { it.locator.hostId == locator.hostId }
            .mapNotNull(DiscoveredThread::workingDirectory)
            .plus(workingDirectory)
            .distinct()
            .sorted()
            .toList()
        beginThreadReview(locator.hostId, locator, observed, workingDirectory)
    }

    private fun beginThreadReview(
        hostId: String,
        sourceLocator: CodexThreadLocator?,
        observedWorkingDirectories: List<String>,
        workingDirectory: String,
    ) {
        mutableState.update {
            it.copy(
                newThread = NewThreadUiState(
                    hostId = hostId,
                    observedWorkingDirectories = observedWorkingDirectories,
                    workingDirectory = workingDirectory,
                    sourceLocator = sourceLocator,
                ),
                error = null,
            )
        }
        if (workingDirectory.isNotEmpty()) reviewNewThread(hostId, workingDirectory)
    }

    fun reviewNewThread(hostId: String, workingDirectory: String) {
        if (!workingDirectory.startsWith('/') || '\u0000' in workingDirectory) {
            mutableState.update {
                it.copy(
                    newThread = it.newThread?.copy(
                        workingDirectory = workingDirectory,
                        catalog = null,
                        error = "Working directory must be an absolute host path",
                    ),
                )
            }
            return
        }
        val appServer = hostSessions.connectedSession(hostId)?.appServer ?: run {
            mutableState.update {
                it.copy(newThread = it.newThread?.copy(error = "Connect $hostId before creating a thread"))
            }
            return
        }
        mutableState.update {
            it.copy(
                newThread = it.newThread?.copy(
                    hostId = hostId,
                    workingDirectory = workingDirectory,
                    catalog = null,
                    loading = true,
                    error = null,
                ),
            )
        }
        scope.launch {
            try {
                val catalog = HostThreadStartSettings(appServer).read(workingDirectory)
                mutableState.update { state ->
                    val current = state.newThread
                    if (current?.hostId == hostId &&
                        current.workingDirectory == workingDirectory
                    ) {
                        state.copy(newThread = current.copy(catalog = catalog, loading = false))
                    } else {
                        state
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.update { state ->
                    val current = state.newThread
                    if (current?.hostId == hostId &&
                        current.workingDirectory == workingDirectory
                    ) {
                        state.copy(
                            newThread = current.copy(
                                loading = false,
                                error = failure.message ?: failure::class.java.simpleName,
                            ),
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    fun createThread(selection: ThreadStartSelection) {
        val review = mutableState.value.newThread ?: return
        if (review.creating) return
        val sourceLocator = review.sourceLocator
        if (sourceLocator != null && mutableState.value.threads[sourceLocator]?.canFork() != true) {
            mutableState.update {
                it.copy(newThread = it.newThread?.copy(error = "Only a READY thread can be forked"))
            }
            return
        }
        val catalog = review.catalog ?: run {
            mutableState.update {
                it.copy(newThread = it.newThread?.copy(error = "Review host settings before creating"))
            }
            return
        }
        val validated = try {
            selection.validated(catalog)
        } catch (failure: IllegalArgumentException) {
            mutableState.update {
                it.copy(newThread = it.newThread?.copy(error = failure.message))
            }
            return
        }
        val appServer = hostSessions.connectedSession(review.hostId)?.appServer ?: run {
            mutableState.update {
                it.copy(newThread = it.newThread?.copy(error = "Connect ${review.hostId} before creating"))
            }
            return
        }
        mutableState.update { it.copy(newThread = it.newThread?.copy(creating = true, error = null)) }
        scope.launch {
            try {
                val response = sourceLocator?.let {
                    appServer.threadFork(it.threadId, validated)
                } ?: appServer.threadStart(validated)
                val thread = response["thread"] as? JsonObject
                    ?: error("Thread operation response did not include a thread")
                require(AppServerThreadProjection.authoritativeState(response).workState == ThreadWorkState.READY) {
                    "Thread operation did not return a READY thread"
                }
                require(sourceLocator != null || AppServerThreadProjection.cards(response, "").isEmpty()) {
                    "thread/start did not return an empty thread"
                }
                val threadId = (thread["id"] as? JsonPrimitive)?.contentOrNull
                    ?: error("Thread operation response did not include a thread ID")
                require(threadId != sourceLocator?.threadId) {
                    "thread/fork did not return a new thread"
                }
                val locator = CodexThreadLocator(review.hostId, threadId)
                attachmentMutex.withLock {
                    try {
                        threadAttachmentStore.attach(locator)
                        threadAttachmentStore.writeReasoningEffort(locator, validated.reasoningEffort)
                    } catch (failure: Throwable) {
                        runCatching { appServer.threadUnsubscribe(threadId) }
                        runCatching { threadAttachmentStore.detach(locator) }
                        throw failure
                    }
                    mutableState.update { state ->
                        state.withCreatedThread(
                            locator = locator,
                            name = (thread["name"] as? JsonPrimitive)?.contentOrNull,
                            preview = (thread["preview"] as? JsonPrimitive)?.contentOrNull,
                            selection = validated,
                        )
                    }
                    refreshPokerProjection(locator)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.update {
                    it.withThreadCreationFailure(failure.message ?: failure::class.java.simpleName)
                }
            }
        }
    }

    fun renameThread(locator: CodexThreadLocator, name: String) {
        if (locator !in mutableState.value.threads) {
            mutableState.update { it.copy(error = "Unknown thread ${locator.threadId}") }
            return
        }
        val appServer = hostSessions.connectedSession(locator.hostId)?.appServer ?: run {
            mutableState.update { it.copy(error = "Connect ${locator.hostId} before renaming") }
            return
        }
        scope.launch {
            try {
                appServer.threadNameSet(locator.threadId, name)
                mutableState.update { it.withRenamedThread(locator, name).copy(error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.update {
                    it.copy(error = failure.message ?: failure::class.java.simpleName)
                }
            }
        }
    }

    fun beginThreadLifecycle(action: ThreadLifecycleAction, locator: CodexThreadLocator) {
        val state = mutableState.value
        val thread = state.threads[locator] ?: run {
            mutableState.update { it.copy(error = "Unknown thread ${locator.threadId}") }
            return
        }
        val session = hostSessions.connectedSession(locator.hostId) ?: run {
            mutableState.update { it.copy(error = "Connect ${locator.hostId} before changing thread lifecycle") }
            return
        }
        if (!session.descendantFilterQualified) {
            mutableState.update {
                it.copy(
                    error = "Archive/Delete unavailable: descendant filtering is not qualified " +
                        "for ${locator.hostId} app-server ${session.appServerVersion ?: "unknown"}",
                )
            }
            return
        }
        if (action == ThreadLifecycleAction.ARCHIVE && thread.archived) {
            mutableState.update { it.copy(error = "The selected thread is already archived") }
            return
        }
        val appServer = session.appServer ?: return
        mutableState.update {
            it.copy(
                lifecycleReview = ThreadLifecycleReviewUiState(
                    action = action,
                    locator = locator,
                    loading = true,
                ),
                error = null,
            )
        }
        scope.launch {
            try {
                val preflight = HostThreadLifecycle(appServer, descendantFilterQualified = true)
                    .preflight(locator.hostId, locator.threadId, thread.archived)
                mutableState.update { current ->
                    if (current.lifecycleReview?.locator == locator &&
                        current.lifecycleReview.action == action
                    ) {
                        current.copy(
                            lifecycleReview = current.lifecycleReview.copy(
                                preflight = preflight,
                                loading = false,
                            ),
                        )
                    } else {
                        current
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.update {
                    it.copy(
                        lifecycleReview = it.lifecycleReview?.takeIf { review ->
                            review.locator == locator && review.action == action
                        }?.copy(
                            loading = false,
                            error = failure.message ?: failure::class.java.simpleName,
                        ),
                    )
                }
            }
        }
    }

    fun confirmThreadLifecycle() {
        val review = mutableState.value.lifecycleReview ?: return
        val reviewed = review.preflight ?: return
        if (review.committing || !reviewed.eligible) return
        val session = hostSessions.connectedSession(review.locator.hostId) ?: run {
            mutableState.update {
                it.copy(lifecycleReview = it.lifecycleReview?.copy(error = "Host disconnected before confirmation"))
            }
            return
        }
        if (!session.descendantFilterQualified) {
            mutableState.update {
                it.copy(
                    lifecycleReview = it.lifecycleReview?.copy(
                        error = "Descendant filtering is no longer qualified for this connection",
                    ),
                )
            }
            return
        }
        val appServer = session.appServer ?: return
        mutableState.update {
            it.copy(lifecycleReview = it.lifecycleReview?.copy(committing = true, error = null))
        }
        scope.launch {
            try {
                val fresh = HostThreadLifecycle(appServer, descendantFilterQualified = true)
                    .preflight(
                        review.locator.hostId,
                        review.locator.threadId,
                        reviewed.selected.archived,
                    )
                require(fresh.eligible) { fresh.blockingReason ?: "Thread lifecycle action is unavailable" }
                if (!reviewed.safetyMatches(fresh)) {
                    mutableState.update {
                        it.copy(
                            lifecycleReview = review.copy(
                                preflight = fresh,
                                committing = false,
                                error = "Thread state or descendant scope changed; review the confirmation again",
                            ),
                        )
                    }
                    return@launch
                }
                when (review.action) {
                    ThreadLifecycleAction.ARCHIVE -> appServer.threadArchive(review.locator.threadId)
                    ThreadLifecycleAction.DELETE -> appServer.threadDelete(review.locator.threadId)
                }
                mutableState.update { it.copy(lifecycleReview = null, error = null) }
                try {
                    reconcileThreadLifecycleReadback(
                        review.action,
                        fresh.affected.mapTo(mutableSetOf(), DiscoveredThread::locator),
                        review.locator,
                        appServer,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    mutableState.update {
                        it.copy(
                            error = "Host accepted ${review.action.name.lowercase()} but " +
                                "authoritative reconciliation failed: " +
                                (failure.message ?: failure::class.java.simpleName),
                        )
                    }
                    refreshThreads(review.locator.hostId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.update {
                    it.copy(
                        lifecycleReview = it.lifecycleReview?.copy(
                            committing = false,
                            error = failure.message ?: failure::class.java.simpleName,
                        ),
                    )
                }
                refreshThreads(review.locator.hostId)
            }
        }
    }

    private suspend fun reconcileThreadLifecycleReadback(
        action: ThreadLifecycleAction,
        reviewedLocators: Set<CodexThreadLocator>,
        selectedLocator: CodexThreadLocator,
        appServer: CodexAppServerSession,
    ) {
        val lifecycleRows = HostThreadLifecycle(appServer, descendantFilterQualified = true)
            .discoverAllSources(selectedLocator.hostId)
        val confirmed = confirmedLifecycleLocators(action, reviewedLocators, lifecycleRows)
        require(selectedLocator in confirmed) {
            "authoritative readback did not confirm the selected thread"
        }
        val discovered = HostThreadDiscovery(appServer).discover(selectedLocator.hostId)
        attachmentMutex.withLock {
            val cleanupFailures = confirmed.mapNotNull { locator ->
                runCatching {
                    when (action) {
                        ThreadLifecycleAction.ARCHIVE -> threadAttachmentStore.detach(locator)
                        ThreadLifecycleAction.DELETE -> {
                            threadAttachmentStore.purge(locator)
                            retainedCardStore.delete(locator)
                        }
                    }
                }.exceptionOrNull()
            }
            mutableState.update { state ->
                val reconciled = when (action) {
                    ThreadLifecycleAction.ARCHIVE -> state.withArchivedThreads(confirmed)
                    ThreadLifecycleAction.DELETE -> state.withDeletedThreads(confirmed)
                }.withDiscoveredThreads(selectedLocator.hostId, discovered)
                reconciled.copy(
                    error = cleanupFailures.firstOrNull()?.let { failure ->
                        "Host confirmed ${action.name.lowercase()} but local cleanup failed: " +
                            (failure.message ?: failure::class.java.simpleName)
                    } ?: reconciled.error,
                )
            }
        }
        threadNotificationTracker.reconciled(
            selectedLocator.hostId,
            mutableState.value.threads.values,
        )
    }

    fun dismissThreadLifecycle() {
        if (mutableState.value.lifecycleReview?.committing == true) return
        mutableState.update { it.copy(lifecycleReview = null) }
    }

    fun restoreThread(locator: CodexThreadLocator) {
        val thread = mutableState.value.threads[locator]
        if (thread?.archived != true) {
            mutableState.update { it.copy(error = "Restore applies only to an archived thread") }
            return
        }
        val appServer = hostSessions.connectedSession(locator.hostId)?.appServer ?: run {
            mutableState.update { it.copy(error = "Connect ${locator.hostId} before restoring") }
            return
        }
        scope.launch {
            try {
                val restored = appServer.threadUnarchive(locator.threadId)
                val restoredId = (restored["thread"] as? JsonObject)
                    ?.get("id")
                    ?.let { it as? JsonPrimitive }
                    ?.contentOrNull
                require(restoredId == locator.threadId) { "thread/unarchive returned a different thread" }
                mutableState.update { it.copy(error = null) }
                refreshThreads(locator.hostId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.update { it.copy(error = failure.message ?: failure::class.java.simpleName) }
                refreshThreads(locator.hostId)
            }
        }
    }

    fun dismissNewThread() {
        if (mutableState.value.newThread?.creating == true) return
        mutableState.update { it.copy(newThread = null) }
    }

    fun attachThread(locator: CodexThreadLocator) {
        val thread = mutableState.value.threads[locator] ?: run {
            mutableState.update { it.copy(error = "Refresh ${locator.hostId} before attaching") }
            return
        }
        val workingDirectory = thread.workingDirectory ?: run {
            mutableState.update { it.copy(error = "The stored thread has no working directory") }
            return
        }
        val observed = mutableState.value.threads.values
            .asSequence()
            .filter { it.locator.hostId == locator.hostId }
            .mapNotNull(DiscoveredThread::workingDirectory)
            .distinct()
            .sorted()
            .toList()
        mutableState.update {
            it.copy(
                resumeThread = ResumeThreadUiState(
                    locator = locator,
                    observedWorkingDirectories = observed,
                    workingDirectory = workingDirectory,
                ),
                error = null,
            )
        }
        reviewResumeThread(locator, workingDirectory)
    }

    fun reviewResumeThread(locator: CodexThreadLocator, workingDirectory: String) {
        if (!workingDirectory.startsWith('/') || '\u0000' in workingDirectory) {
            mutableState.update {
                it.copy(
                    resumeThread = it.resumeThread?.copy(
                        workingDirectory = workingDirectory,
                        catalog = null,
                        error = "Working directory must be an absolute host path",
                    ),
                )
            }
            return
        }
        val appServer = hostSessions.connectedSession(locator.hostId)?.appServer ?: run {
            mutableState.update {
                it.copy(
                    resumeThread = it.resumeThread?.copy(
                        error = "Connect ${locator.hostId} before attaching",
                    ),
                )
            }
            return
        }
        mutableState.update {
            it.copy(
                resumeThread = it.resumeThread?.copy(
                    workingDirectory = workingDirectory,
                    catalog = null,
                    loading = true,
                    error = null,
                ),
            )
        }
        scope.launch {
            try {
                val catalog = HostThreadStartSettings(appServer).read(workingDirectory)
                mutableState.update { state ->
                    val current = state.resumeThread
                    if (current?.locator == locator &&
                        current.workingDirectory == workingDirectory
                    ) {
                        state.copy(resumeThread = current.copy(catalog = catalog, loading = false))
                    } else {
                        state
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.update { state ->
                    val current = state.resumeThread
                    if (current?.locator == locator &&
                        current.workingDirectory == workingDirectory
                    ) {
                        state.copy(
                            resumeThread = current.copy(
                                loading = false,
                                error = failure.message ?: failure::class.java.simpleName,
                            ),
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    fun setResumeControlClaim(claimed: Boolean) {
        mutableState.update { it.withResumeControlClaim(claimed) }
    }

    fun resumeThread(selection: ThreadStartSelection) {
        val review = mutableState.value.resumeThread ?: return
        if (review.resuming) return
        val catalog = review.catalog ?: run {
            mutableState.update {
                it.copy(resumeThread = it.resumeThread?.copy(error = "Review host settings before attaching"))
            }
            return
        }
        val validated = try {
            selection.validated(catalog)
        } catch (failure: IllegalArgumentException) {
            mutableState.update {
                it.copy(resumeThread = it.resumeThread?.copy(error = failure.message))
            }
            return
        }
        val controlBearing = validated.hasControlOverrides()
        mutableState.value.resumeControlError(validated)?.let { error ->
            mutableState.update {
                it.copy(resumeThread = it.resumeThread?.copy(error = error))
            }
            return
        }
        val appServer = hostSessions.connectedSession(review.locator.hostId)?.appServer ?: run {
            mutableState.update {
                it.copy(
                    resumeThread = it.resumeThread?.copy(
                        error = "Connect ${review.locator.hostId} before attaching",
                    ),
                )
            }
            return
        }
        mutableState.update {
            it.copy(resumeThread = it.resumeThread?.copy(resuming = true, error = null))
        }
        scope.launch {
            attachmentMutex.withLock {
                if (review.locator in mutableState.value.threadAttachments.attached) {
                    mutableState.update { it.copy(resumeThread = null) }
                    return@withLock
                }
                try {
                    appServer.threadResume(review.locator.threadId, validated)
                    try {
                        threadAttachmentStore.attach(review.locator)
                        threadAttachmentStore.writeReasoningEffort(
                            review.locator,
                            validated.reasoningEffort,
                        )
                    } catch (failure: Throwable) {
                        runCatching { appServer.threadUnsubscribe(review.locator.threadId) }
                        runCatching { threadAttachmentStore.detach(review.locator) }
                        throw failure
                    }
                    mutableState.update {
                        it.withResumedThread(
                            review.locator,
                            validated,
                            grantControl = controlBearing && review.controlClaimed,
                        )
                    }
                    refreshPokerProjection(review.locator)
                    browseThread(review.locator)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    mutableState.update {
                        it.withThreadResumeFailure(
                            "Resume settings unavailable: " +
                                (failure.message ?: failure::class.java.simpleName),
                        )
                    }
                }
            }
        }
    }

    fun dismissResumeThread() {
        if (mutableState.value.resumeThread?.resuming == true) return
        mutableState.update { it.copy(resumeThread = null) }
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

    private fun observeServerRequests(hostId: String, generation: Long) {
        val appServer = hostSessions.connectedSession(hostId)?.appServer ?: return
        requestJobs.remove(hostId)?.cancel()
        requestJobs[hostId] = scope.launch {
            while (true) {
                val wire = appServer.receiveServerRequest() ?: return@launch
                when (wire.method) {
                    FILE_APPROVAL_METHOD -> receiveFileApproval(hostId, generation, wire)
                    COMMAND_APPROVAL_METHOD -> {
                        when (val parsed = CommandApprovalProtocol.parse(hostId, generation, wire)) {
                            is CommandApprovalParseResult.Accepted -> {
                                try {
                                    mutableState.update {
                                        it.withCommandApprovals(
                                            it.commandApprovals.receive(
                                                parsed.request,
                                                sameIdReissueQualified = false,
                                            ),
                                        )
                                    }
                                    wireCommandApprovals[parsed.request.locator] = wire
                                    recordThreadTransition(parsed.request.thread)
                                    refreshPokerApprovalProjection(parsed.request.thread)
                                } catch (failure: IllegalArgumentException) {
                                    appServer.reject(
                                        wire,
                                        failure.message ?: "Command approval identity conflict",
                                    )
                                }
                            }
                            is CommandApprovalParseResult.Rejected ->
                                appServer.reject(wire, parsed.reason)
                        }
                    }
                    USER_INPUT_REQUEST_METHOD -> {
                        when (
                            val parsed = UserInputProtocol.parse(
                                hostId,
                                generation,
                                wire,
                                System.currentTimeMillis(),
                            )
                        ) {
                            is UserInputParseResult.Accepted -> {
                                val existing = mutableState.value.userInputRequests
                                    .requests[parsed.request.locator]
                                if (existing != null) {
                                    appServer.reject(
                                        wire,
                                        if (existing.fingerprint == parsed.request.fingerprint) {
                                            "Duplicate user-input request"
                                        } else {
                                            "User-input request identity conflict"
                                        },
                                    )
                                    continue
                                }
                                try {
                                    val previousReissueLocator = mutableState.value.userInputRequests.requests.values
                                        .firstOrNull { previous ->
                                            previous.locator.hostId == parsed.request.locator.hostId &&
                                                previous.locator.requestId == parsed.request.locator.requestId &&
                                                previous.locator.appServerGeneration != parsed.request.locator.appServerGeneration &&
                                                previous.fingerprint == parsed.request.fingerprint &&
                                                previous.resolution == RequestResolutionState.UNKNOWN
                                        }?.locator
                                    val sameIdReissueQualified = previousReissueLocator != null
                                    mutableState.update {
                                        val requests = it.userInputRequests.receive(
                                            parsed.request,
                                            sameIdReissueQualified = sameIdReissueQualified,
                                        )
                                        it.withUserInputRequests(requests).copy(
                                            userInputAnswers = it.userInputAnswers.receive(
                                                it.userInputRequests,
                                                parsed.request,
                                                sameIdReissueQualified = sameIdReissueQualified,
                                            ),
                                        )
                                    }
                                    previousReissueLocator?.let { oldLocator ->
                                        wireUserInputs.remove(oldLocator)
                                        userInputTimeoutJobs.remove(oldLocator)?.cancel()
                                    }
                                    wireUserInputs[parsed.request.locator] = wire
                                    mutableState.value.userInputRequests.requests[parsed.request.locator]
                                        ?.let(::scheduleUserInputTimeout)
                                    recordThreadTransition(parsed.request.thread)
                                    refreshPokerUserInputProjection(parsed.request.thread)
                                } catch (failure: IllegalArgumentException) {
                                    appServer.reject(
                                        wire,
                                        failure.message ?: "User-input request identity conflict",
                                    )
                                }
                            }
                            is UserInputParseResult.Rejected ->
                                appServer.reject(wire, parsed.reason)
                        }
                    }
                    else -> appServer.reject(wire, "Unsupported server request")
                }
            }
        }
    }

    private fun receiveFileApproval(
        hostId: String,
        generation: Long,
        wire: AppServerRequest,
    ) {
        val appServer = hostSessions.connectedSession(hostId)?.appServer ?: return
        val initial = FileApprovalProtocol.parse(
            hostId,
            generation,
            wire,
            reviewCard = mutableState.value.fileReviewCard(hostId, wire),
        )
        when (initial) {
            is FileApprovalParseResult.Accepted -> {
                try {
                    mutableState.update {
                        it.withApprovals(
                            fileApprovals = it.fileApprovals.receive(
                                initial.request,
                                sameIdReissueQualified = false,
                            ),
                        )
                    }
                    wireFileApprovals[initial.request.locator] = wire
                    recordThreadTransition(initial.request.thread)
                    refreshPokerApprovalProjection(initial.request.thread)
                } catch (failure: IllegalArgumentException) {
                    scope.launch {
                        appServer.reject(wire, failure.message ?: "File approval identity conflict")
                    }
                }
            }
            is FileApprovalParseResult.Incomplete -> {
                mutableState.update {
                    it.withApprovals(
                        fileApprovals = it.fileApprovals.receive(
                            initial.request,
                            sameIdReissueQualified = false,
                        ),
                    )
                }
                wireFileApprovals[initial.request.locator] = wire
                recordThreadTransition(initial.request.thread)
                browseThread(initial.request.thread)
                refreshPokerApprovalProjection(initial.request.thread)
                scope.launch {
                    delay(INCOMPLETE_CARD_REREAD_DELAY_MILLIS)
                    if (hostGenerations[hostId] != generation ||
                        wireFileApprovals[initial.request.locator] != wire ||
                        mutableState.value.fileApprovals.requests[initial.request.locator]?.resolution !=
                        RequestResolutionState.PENDING
                    ) {
                        return@launch
                    }
                    val reparsed = FileApprovalProtocol.parse(
                        hostId,
                        generation,
                        wire,
                        reviewCard = mutableState.value.fileReviewCard(hostId, wire),
                    )
                    if (reparsed is FileApprovalParseResult.Accepted) {
                        mutableState.update {
                            it.withApprovals(
                                fileApprovals = it.fileApprovals.receive(
                                    reparsed.request,
                                    sameIdReissueQualified = false,
                                ),
                            )
                        }
                        refreshPokerApprovalProjection(initial.request.thread)
                    } else {
                        val reason = "File approval diff remains incomplete after authoritative reread"
                        runCatching { appServer.reject(wire, reason) }
                            .onSuccess {
                                wireFileApprovals.remove(initial.request.locator)
                                mutableState.update {
                                    it.withApprovals(
                                        fileApprovals = it.fileApprovals.failClosed(
                                            initial.request.locator,
                                            reason,
                                        ),
                                    )
                                }
                                recordThreadTransition(initial.request.thread)
                                refreshPokerApprovalProjection(initial.request.thread)
                            }
                            .onFailure {
                                mutableState.update {
                                    it.withApprovals(
                                        fileApprovals = it.fileApprovals.unknown(initial.request.locator),
                                    )
                                }
                                refreshPokerApprovalProjection(initial.request.thread)
                            }
                    }
                }
            }
            is FileApprovalParseResult.Rejected ->
                scope.launch { appServer.reject(wire, initial.reason) }
        }
    }

    @Synchronized
    fun resolveCommandApproval(
        locator: ServerRequestLocator,
        decision: CommandApprovalDecision,
        onOutcome: (PokerPrimaryActionOutcome) -> Unit = {},
    ) {
        val state = mutableState.value
        val request = state.commandApprovals.requests[locator] ?: run {
            onOutcome(PokerPrimaryActionOutcome.REJECTED)
            return
        }
        if (!state.threadAttachments.hasDealerClaim(request.thread)) {
            mutableState.update { it.copy(error = "Take control before resolving this request") }
            onOutcome(PokerPrimaryActionOutcome.REJECTED)
            return
        }
        val appServer = hostSessions.connectedSession(locator.hostId)?.appServer
        val wire = wireCommandApprovals[locator]
        if (appServer == null || hostGenerations[locator.hostId] != locator.appServerGeneration || wire == null) {
            mutableState.update {
                it.withCommandApprovals(it.commandApprovals.unknown(locator))
                    .copy(error = "Command approval is no longer connected; no response was replayed")
            }
            refreshPokerApprovalProjection(request.thread)
            onOutcome(PokerPrimaryActionOutcome.UNKNOWN)
            return
        }
        val responding = try {
            state.commandApprovals.begin(locator, decision)
        } catch (failure: IllegalArgumentException) {
            mutableState.update { it.copy(error = failure.message) }
            onOutcome(PokerPrimaryActionOutcome.REJECTED)
            return
        }
        if (responding == state.commandApprovals) {
            onOutcome(PokerPrimaryActionOutcome.REJECTED)
            return
        }
        mutableState.update { it.withCommandApprovals(responding).copy(error = null) }
        refreshPokerApprovalProjection(request.thread)
        scope.launch {
            try {
                persistPendingRequestState()
            } catch (failure: Throwable) {
                mutableState.update {
                    it.withCommandApprovals(it.commandApprovals.unknown(locator))
                        .copy(error = "Approval was not sent because recovery storage failed: ${failure.message}")
                }
                refreshPokerApprovalProjection(request.thread)
                onOutcome(PokerPrimaryActionOutcome.UNKNOWN)
                return@launch
            }
            try {
                appServer.respond(wire, CommandApprovalProtocol.response(request, decision))
                onOutcome(PokerPrimaryActionOutcome.ACCEPTED)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.update {
                    it.withCommandApprovals(it.commandApprovals.unknown(locator))
                        .copy(
                            error = "${failure.message ?: failure::class.java.simpleName}; " +
                                "approval response was not replayed",
                        )
                }
                refreshPokerApprovalProjection(request.thread)
                onOutcome(PokerPrimaryActionOutcome.UNKNOWN)
            }
        }
    }

    fun resolveUserInput(
        locator: ServerRequestLocator,
        answers: Map<String, List<String>>,
    ) {
        respondUserInput(locator, answers, UserInputOutcome.ANSWERED, requireControl = true)
    }

    fun resolveUserInput(locator: ServerRequestLocator) {
        val state = mutableState.value
        val request = state.userInputRequests.requests[locator] ?: return
        respondUserInput(
            locator,
            state.userInputAnswers.buffer(locator).response(request),
            UserInputOutcome.ANSWERED,
            requireControl = true,
        )
    }

    @Synchronized
    fun updateUserInputAnswer(
        locator: ServerRequestLocator,
        questionId: String,
        edit: UserInputAnswerEdit,
    ): Boolean {
        val state = mutableState.value
        val request = state.userInputRequests.requests[locator]
        if (request == null || request.resolution != RequestResolutionState.PENDING) {
            return false
        }
        if (!state.threadAttachments.hasDealerClaim(request.thread)) {
            mutableState.update { it.copy(error = "Take control before editing this request") }
            return false
        }
        val updated = try {
            state.userInputAnswers.edit(request, questionId, edit)
        } catch (failure: IllegalArgumentException) {
            mutableState.update { it.copy(error = failure.message) }
            return false
        }
        mutableState.update { it.copy(userInputAnswers = updated, error = null) }
        refreshPokerUserInputProjection(request.thread)
        return true
    }

    fun resolveUserInputWithoutAnswer(locator: ServerRequestLocator) {
        respondUserInput(locator, emptyMap(), UserInputOutcome.NO_ANSWER, requireControl = false)
    }

    private fun scheduleUserInputTimeout(request: UserInputRequest) {
        val deadlineAtMs = request.deadlineAtMs ?: return
        userInputTimeoutJobs.remove(request.locator)?.cancel()
        userInputTimeoutJobs[request.locator] = scope.launch {
            delay((deadlineAtMs - System.currentTimeMillis()).coerceAtLeast(0))
            respondUserInput(
                request.locator,
                emptyMap(),
                UserInputOutcome.AUTO_RESOLVED,
                requireControl = false,
            )
        }
    }

    @Synchronized
    private fun respondUserInput(
        locator: ServerRequestLocator,
        answers: Map<String, List<String>>,
        outcome: UserInputOutcome,
        requireControl: Boolean,
        onOutcome: (PokerPrimaryActionOutcome) -> Unit = {},
    ) {
        val state = mutableState.value
        val request = state.userInputRequests.requests[locator] ?: run {
            onOutcome(PokerPrimaryActionOutcome.REJECTED)
            return
        }
        if (requireControl && !state.threadAttachments.hasDealerClaim(request.thread)) {
            mutableState.update { it.copy(error = "Take control before answering this request") }
            onOutcome(PokerPrimaryActionOutcome.REJECTED)
            return
        }
        val response = try {
            UserInputProtocol.response(request, answers)
        } catch (failure: IllegalArgumentException) {
            mutableState.update { it.copy(error = failure.message) }
            onOutcome(PokerPrimaryActionOutcome.REJECTED)
            return
        }
        val appServer = hostSessions.connectedSession(locator.hostId)?.appServer
        val wire = wireUserInputs[locator]
        if (appServer == null ||
            hostGenerations[locator.hostId] != locator.appServerGeneration ||
            wire == null
        ) {
            mutableState.update {
                it.withUserInputRequests(it.userInputRequests.unknown(locator))
                    .copy(error = "User-input request is no longer connected; no response was replayed")
            }
            refreshPokerUserInputProjection(request.thread)
            onOutcome(PokerPrimaryActionOutcome.UNKNOWN)
            return
        }
        val responding = state.userInputRequests.begin(locator, outcome)
        if (responding == state.userInputRequests) {
            onOutcome(PokerPrimaryActionOutcome.REJECTED)
            return
        }
        userInputTimeoutJobs.remove(locator)?.let {
            if (outcome != UserInputOutcome.AUTO_RESOLVED) it.cancel()
        }
        mutableState.update { state ->
            state.withUserInputRequests(responding).copy(
                userInputAnswers = if (outcome == UserInputOutcome.ANSWERED) {
                    state.userInputAnswers
                } else {
                    state.userInputAnswers.remove(locator)
                },
                error = null,
            )
        }
        refreshPokerUserInputProjection(request.thread)
        scope.launch {
            try {
                persistPendingRequestState()
            } catch (failure: Throwable) {
                mutableState.update {
                    it.withUserInputRequests(it.userInputRequests.unknown(locator))
                        .copy(error = "Response was not sent because recovery storage failed: ${failure.message}")
                }
                refreshPokerUserInputProjection(request.thread)
                onOutcome(PokerPrimaryActionOutcome.UNKNOWN)
                return@launch
            }
            try {
                appServer.respond(wire, response)
                onOutcome(PokerPrimaryActionOutcome.ACCEPTED)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.update {
                    it.withUserInputRequests(it.userInputRequests.unknown(locator))
                        .copy(
                            error = "${failure.message ?: failure::class.java.simpleName}; " +
                                "user-input response was not replayed",
                        )
                }
                refreshPokerUserInputProjection(request.thread)
                onOutcome(PokerPrimaryActionOutcome.UNKNOWN)
            }
        }
    }

    @Synchronized
    fun resolveFileApproval(
        locator: ServerRequestLocator,
        decision: FileApprovalDecision,
        onOutcome: (PokerPrimaryActionOutcome) -> Unit = {},
    ) {
        val state = mutableState.value
        val request = state.fileApprovals.requests[locator] ?: run {
            onOutcome(PokerPrimaryActionOutcome.REJECTED)
            return
        }
        if (!state.threadAttachments.hasDealerClaim(request.thread)) {
            mutableState.update { it.copy(error = "Take control before resolving this request") }
            onOutcome(PokerPrimaryActionOutcome.REJECTED)
            return
        }
        val appServer = hostSessions.connectedSession(locator.hostId)?.appServer
        val wire = wireFileApprovals[locator]
        if (appServer == null || hostGenerations[locator.hostId] != locator.appServerGeneration || wire == null) {
            mutableState.update {
                it.withApprovals(fileApprovals = it.fileApprovals.unknown(locator))
                    .copy(error = "File approval is no longer connected; no response was replayed")
            }
            refreshPokerApprovalProjection(request.thread)
            onOutcome(PokerPrimaryActionOutcome.UNKNOWN)
            return
        }
        val responding = try {
            state.fileApprovals.begin(locator, decision)
        } catch (failure: IllegalArgumentException) {
            mutableState.update { it.copy(error = failure.message) }
            onOutcome(PokerPrimaryActionOutcome.REJECTED)
            return
        }
        if (responding == state.fileApprovals) {
            onOutcome(PokerPrimaryActionOutcome.REJECTED)
            return
        }
        mutableState.update { it.withApprovals(fileApprovals = responding).copy(error = null) }
        refreshPokerApprovalProjection(request.thread)
        scope.launch {
            try {
                persistPendingRequestState()
            } catch (failure: Throwable) {
                mutableState.update {
                    it.withApprovals(fileApprovals = it.fileApprovals.unknown(locator))
                        .copy(error = "Approval was not sent because recovery storage failed: ${failure.message}")
                }
                refreshPokerApprovalProjection(request.thread)
                onOutcome(PokerPrimaryActionOutcome.UNKNOWN)
                return@launch
            }
            try {
                appServer.respond(wire, FileApprovalProtocol.response(decision))
                onOutcome(PokerPrimaryActionOutcome.ACCEPTED)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.update {
                    it.withApprovals(fileApprovals = it.fileApprovals.unknown(locator))
                        .copy(
                            error = "${failure.message ?: failure::class.java.simpleName}; " +
                                "file approval response was not replayed",
                        )
                }
                refreshPokerApprovalProjection(request.thread)
                onOutcome(PokerPrimaryActionOutcome.UNKNOWN)
            }
        }
    }

    private fun observeNotifications(hostId: String, generation: Long) {
        val appServer = hostSessions.connectedSession(hostId)?.appServer ?: return
        notificationJobs.remove(hostId)?.cancel()
        notificationJobs[hostId] = scope.launch {
            while (true) {
                val notification = appServer.receiveNotification() ?: return@launch
                val resolved = CommandApprovalProtocol.resolved(notification)
                if (resolved != null) {
                    wireCommandApprovals.keys.removeAll {
                        it.hostId == hostId &&
                            it.appServerGeneration == generation &&
                            it.requestId == resolved.requestId
                    }
                    wireUserInputs.keys
                        .filter {
                            it.hostId == hostId &&
                                it.appServerGeneration == generation &&
                                it.requestId == resolved.requestId
                        }
                        .forEach { locator ->
                            wireUserInputs.remove(locator)
                            userInputTimeoutJobs.remove(locator)?.cancel()
                        }
                    wireFileApprovals.keys.removeAll {
                        it.hostId == hostId &&
                            it.appServerGeneration == generation &&
                            it.requestId == resolved.requestId
                    }
                    val resolvedQuestionLocator = mutableState.value.userInputRequests.requests.values
                        .firstOrNull {
                            it.locator.hostId == hostId &&
                                it.locator.appServerGeneration == generation &&
                                it.locator.requestId == resolved.requestId &&
                                it.thread.threadId == resolved.threadId
                        }?.locator
                    mutableState.update {
                        val questions = it.userInputRequests.resolved(
                            hostId,
                            generation,
                            resolved.requestId,
                            resolved.threadId,
                        )
                        it.withApprovals(
                            commandApprovals = it.commandApprovals.resolved(
                                hostId,
                                generation,
                                resolved.requestId,
                                resolved.threadId,
                            ),
                            fileApprovals = it.fileApprovals.resolved(
                                hostId,
                                generation,
                                resolved.requestId,
                                resolved.threadId,
                            ),
                        ).withUserInputRequests(questions)
                    }
                    pokerUserInputBindings.keys.removeAll {
                        it.hostId == hostId &&
                            it.appServerGeneration == generation &&
                            it.requestId == resolved.requestId
                    }
                    pokerApprovalBindings.keys.removeAll {
                        it.hostId == hostId &&
                            it.appServerGeneration == generation &&
                            it.requestId == resolved.requestId
                    }
                    resolvedQuestionLocator?.let { questionLocator ->
                        pokerComposerEpoch?.let { pokerEpoch ->
                            sendPokerUserInputProjection(pokerEpoch, questionLocator)
                        }
                    }
                    refreshPokerApprovalProjection(CodexThreadLocator(hostId, resolved.threadId))
                    recordThreadTransition(CodexThreadLocator(hostId, resolved.threadId))
                    continue
                }
                val params = notification.params as? JsonObject ?: continue
                val turn = params["turn"] as? JsonObject
                val threadId = (params["threadId"] as? JsonPrimitive)?.contentOrNull
                    ?: (turn?.get("threadId") as? JsonPrimitive)?.contentOrNull
                    ?: continue
                val locator = CodexThreadLocator(hostId, threadId)
                val turnId = (turn?.get("id") as? JsonPrimitive)?.contentOrNull
                    ?: (params["turnId"] as? JsonPrimitive)?.contentOrNull
                if (notification.method in STRUCTURED_CARD_NOTIFICATIONS) {
                    val conversationId = "${locator.hostId}/${locator.threadId}"
                    val projected = AppServerStructuredCardProjection.apply(
                        current = mutableState.value.cards.filter { it.conversationId == conversationId },
                        notification = notification,
                        conversationId = conversationId,
                    )
                    val retained = retainCards(locator, projected.cards)
                    mutableState.update { state ->
                        state.copy(
                            cards = state.cards.filterNot { it.conversationId == conversationId } +
                                retained.cards,
                            error = retained.error ?: state.error,
                        )
                    }
                    refreshPokerProjection(locator)
                    if (projected.requiresReread) {
                        scope.launch {
                            delay(INCOMPLETE_CARD_REREAD_DELAY_MILLIS)
                            browseThread(locator)
                        }
                    }
                }
                when (notification.method) {
                    "thread/archived", "thread/unarchived", "thread/deleted" -> {
                        reconcileThreadLifecycle(notification.method, locator)
                    }
                    "thread/name/updated" -> {
                        val name = (params["threadName"] as? JsonPrimitive)?.contentOrNull
                        mutableState.update { it.withRenamedThread(locator, name) }
                    }
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
                        userInputTimeoutJobs.keys
                            .filter { requestLocator ->
                                val request = mutableState.value.userInputRequests.requests[requestLocator]
                                request?.thread == locator && request.turnId == turnId
                            }
                            .forEach { userInputTimeoutJobs.remove(it)?.cancel() }
                        val settledQuestionLocators = mutableState.value.userInputRequests.requests.values
                            .filter { it.thread == locator && it.turnId == turnId }
                            .map(UserInputRequest::locator)
                        mutableState.update { state ->
                            val questions = turnId?.let {
                                state.userInputRequests.turnSettled(locator, it)
                            } ?: state.userInputRequests
                            val answers = settledQuestionLocators.fold(state.userInputAnswers) { current, requestLocator ->
                                current.remove(requestLocator)
                            }
                            state.withApprovals(
                                commandApprovals = turnId?.let {
                                    state.commandApprovals.turnSettled(locator, it)
                                } ?: state.commandApprovals,
                                fileApprovals = turnId?.let {
                                    state.fileApprovals.turnSettled(locator, it)
                                } ?: state.fileApprovals,
                            ).withUserInputRequests(questions).copy(
                                userInputAnswers = answers,
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
                        pokerUserInputBindings.keys.removeAll { it in settledQuestionLocators }
                        refreshPokerUserInputProjection(locator)
                        refreshPokerApprovalProjection(locator)
                        recordThreadTransition(locator)
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

    private suspend fun reconcileThreadLifecycle(
        method: String,
        locator: CodexThreadLocator,
    ) {
        attachmentMutex.withLock {
            val persistenceFailure = when (method) {
                "thread/deleted" -> runCatching {
                    threadAttachmentStore.purge(locator)
                    retainedCardStore.delete(locator)
                }.exceptionOrNull()
                else -> runCatching { threadAttachmentStore.detach(locator) }.exceptionOrNull()
            }
            mutableState.update { state ->
                val reconciled = when (method) {
                    "thread/archived" -> state.withArchivedThreads(setOf(locator))
                    "thread/unarchived" -> state.withRestoredThread(locator)
                    "thread/deleted" -> state.withDeletedThreads(setOf(locator))
                    else -> state
                }
                reconciled.copy(
                    error = persistenceFailure?.let {
                        "Host confirmed ${method.substringAfter('/')} but local cleanup failed: " +
                            (it.message ?: it::class.java.simpleName)
                    } ?: reconciled.error,
                )
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
        recordThreadTransition(locator)
        refreshPokerProjection(locator)
    }

    fun browseThread(locator: CodexThreadLocator, request: ServerRequestLocator? = null) {
        mutableState.update {
            it.copy(
                browsedThread = locator,
                browsedRequest = request,
            )
        }
        val appServer = hostSessions.connectedSession(locator.hostId)?.appServer ?: return
        scope.launch {
            try {
                val conversationId = "${locator.hostId}/${locator.threadId}"
                val response = appServer.threadRead(locator.threadId)
                val authoritative = AppServerThreadProjection.authoritativeState(response)
                val cards = AppServerThreadProjection.cards(response, conversationId)
                val prior = mutableState.value.cards
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
                val retained = retainCards(locator, localOnly + reconciled)
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
                    state.copy(
                        threadActions = actions,
                        cards = state.cards.filterNot { it.conversationId == conversationId } +
                            retained.cards,
                        threads = state.threads[locator]?.let { row ->
                            state.threads + (
                                locator to row.copy(
                                    status = when (authoritative.workState) {
                                        ThreadWorkState.BUSY, ThreadWorkState.ATTENTION_REQUIRED -> "active"
                                        ThreadWorkState.READY -> "idle"
                                        null -> row.status
                                    },
                                    workState = if (locator in state.knownBlockingRequestThreads) {
                                        ThreadWorkState.ATTENTION_REQUIRED
                                    } else {
                                        authoritative.workState
                                    },
                                    activeTurnId = authoritative.activeTurnId,
                                )
                            )
                        } ?: state.threads,
                        browsedThread = locator,
                        browsedRequest = request,
                        threadDiscoveryErrors = state.threadDiscoveryErrors - locator.hostId,
                        error = retained.error,
                    )
                }
                if (clearDraft) {
                    val retainedDraft = mutableState.value.threadActions.composerDraft(locator)
                    draftMutex.withLock {
                        threadAttachmentStore.writeDraft(locator, retainedDraft)
                        threadAttachmentStore.writePendingInput(locator, null)
                        threadAttachmentStore.writeReasoningEffort(
                            locator,
                            mutableState.value.threadActions.pendingReasoningEfforts[locator],
                        )
                    }
                }
                if (clearInterrupt) {
                    draftMutex.withLock { threadAttachmentStore.writePendingInterrupt(locator, null) }
                }
                refreshPokerProjection(locator)
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

    private suspend fun retainCards(
        locator: CodexThreadLocator,
        cards: List<Card>,
    ): RetainedCards {
        if (locator !in mutableState.value.threadAttachments.attached) return RetainedCards(cards)
        val clean = cards.map { card ->
            if (card.source in STRUCTURED_CARD_SOURCES) {
                card.copy(
                    contentComplete = card.reviewMaterialPresent(),
                    storageError = null,
                )
            } else {
                card
            }
        }
        return try {
            retainedCardStore.write(locator, clean)
            RetainedCards(clean)
        } catch (failure: Throwable) {
            val message = failure.message ?: failure::class.java.simpleName
            RetainedCards(
                cards = clean.map { card ->
                    if (card.source in STRUCTURED_CARD_SOURCES) {
                        card.copy(contentComplete = false, storageError = message)
                    } else {
                        card
                    }
                },
                error = "Unable to retain complete command/file content: $message",
            )
        }
    }

    private fun startRecoveryPersistence(pendingRequestsWritable: Boolean) {
        pendingRequestPersistenceAvailable = pendingRequestsWritable
        scope.launch {
            mutableState
                .map { state ->
                    DurableDealerState(
                        projection = DealerProjectionSnapshot(
                            threads = state.threadAttachments.attached
                                .mapNotNull(state.threads::get)
                                .map {
                                    it.copy(
                                        attached = true,
                                        intendedControlSurface = ControlSurface.NONE,
                                    )
                                }
                                .sortedWith(
                                    compareBy<DiscoveredThread>(
                                        { it.locator.hostId },
                                        { it.locator.threadId },
                                    ),
                                ),
                        ),
                        pendingRequests = state.pendingRequestSnapshot(),
                    )
                }
                .distinctUntilChanged()
                .collect { durable ->
                    val failures = mutableListOf<String>()
                    runCatching { stateRecoveryStore.writeProjection(durable.projection) }
                        .onFailure {
                            failures += "Unable to retain cached thread projection: ${it.message}"
                        }
                    if (pendingRequestPersistenceAvailable) {
                        runCatching {
                            pendingRequestPersistenceMutex.withLock {
                                stateRecoveryStore.writePendingRequests(durable.pendingRequests)
                            }
                        }.onFailure {
                            pendingRequestPersistenceAvailable = false
                            failures += "Unable to retain pending request state: ${it.message}"
                        }
                    }
                    if (failures.isNotEmpty()) {
                        mutableState.update { it.copy(error = failures.joinToString("; ")) }
                    }
                }
        }
    }

    private suspend fun persistPendingRequestState() {
        check(pendingRequestPersistenceAvailable) {
            "Pending request recovery storage is unavailable"
        }
        try {
            pendingRequestPersistenceMutex.withLock {
                stateRecoveryStore.writePendingRequests(mutableState.value.pendingRequestSnapshot())
            }
        } catch (failure: Throwable) {
            pendingRequestPersistenceAvailable = false
            throw failure
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
            qualifiedDescendantFilterVersions = if (host == InitialCodexHosts.spark) {
                setOf("0.146.0")
            } else {
                emptySet()
            },
            qualifiedServerRequestVersions = if (host == InitialCodexHosts.spark) {
                mapOf(USER_INPUT_REQUEST_METHOD to setOf("0.146.0"))
            } else {
                emptyMap()
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
        refreshPokerProjection(locator)
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
        refreshPokerProjection(locator)
        return true
    }

    @Synchronized
    fun setActivityVisible(visible: Boolean) {
        activityVisible = visible
        if (visible && isScreenInteractive()) {
            val manager = getSystemService(NotificationManager::class.java)
            threadNotificationTargets.keys.forEach {
                manager.cancel(it, THREAD_NOTIFICATION_ID)
            }
            threadNotificationTargets.clear()
        }
    }

    @Synchronized
    fun openThreadNotification(key: String): Boolean {
        val locator = threadNotificationTargets[key] ?: return false
        browseThread(locator, mutableState.value.unresolvedRequest(locator))
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

    private fun registerPokerNetworkCallback() {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = requestPokerNetworkRetry()

            override fun onLost(network: Network) = requestPokerNetworkRetry()

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: android.net.LinkProperties,
            ) = requestPokerNetworkRetry()
        }
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onSuccess { pokerNetworkCallback = callback }
    }

    private fun requestPokerNetworkRetry() {
        if (pokerConnectionOwner.isRunning) {
            pokerConnectionOwner.retry(PokerReconnectTrigger.NETWORK_CHANGE)
        }
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
        pokerNetworkCallback?.let { callback ->
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        }
        pokerNetworkCallback = null
        pokerComposerEpoch = null
        pokerComposerBindings.clear()
        pokerComposerResults.clear()
        pokerPrimaryResults.clear()
        pokerUserInputBindings.clear()
        pokerUserInputResults.clear()
        photoTransfers.values.forEach { transfer ->
            transfer.timeout.cancel()
            scope.launch { photoAssets.delete(transfer.target.assetId) }
        }
        photoTransfers.clear()
        photoSessions.clear()
        photoResults.clear()
        photoDeleteResults.clear()
        pokerMorseResults.clear()
        pokerApprovalBindings.clear()
        pokerAsrSession?.let { session ->
            pokerAsrSession = null
            scope.launch { runCatching { session.close() } }
        }
        if (::asrProcess.isInitialized) DealerAsrProcess.closeShared(asrProcess)
        pokerConnectionOwner.stop()
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

    private fun recordThreadTransition(locator: CodexThreadLocator) {
        scope.launch {
            val snapshot = pokerSnapshotSource.current()
            pokerSnapshotHandler.publish(snapshot)
            recordPokerSnapshotRevision(snapshot)
        }
        val state = mutableState.value
        val thread = state.threads[locator] ?: return
        val hostLabel = InitialCodexHosts.all.firstOrNull { it.id == locator.hostId }?.displayName ?: return
        val alert = threadNotificationTracker.transition(
            thread = thread,
            hostLabel = hostLabel,
            request = state.unresolvedRequest(locator),
            activityVisible = activityVisible,
            screenInteractive = isScreenInteractive(),
        )
        if (alert == null) {
            if (thread.workState == ThreadWorkState.BUSY) cancelThreadNotification(locator)
            return
        }
        threadNotificationTargets[alert.key] = alert.target.thread
        postThreadNotification(alert)
    }

    private fun recordPokerSnapshotRevision(snapshot: com.code2hack.pokerdealer.protocol.PokerSnapshot) {
        mutableState.update {
            it.copy(
                pokerDiagnostics = it.pokerDiagnostics.copy(
                    snapshotRevision = snapshot.revision,
                    deltaRevision = snapshot.revision,
                ),
            )
        }
    }

    private fun postThreadNotification(alert: ThreadTransitionNotification) {
        val manager = getSystemService(NotificationManager::class.java)
        val channelId = when (alert.priority) {
            ThreadNotificationPriority.HIGH -> THREAD_ATTENTION_CHANNEL
            ThreadNotificationPriority.NORMAL -> THREAD_READY_CHANNEL
        }
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                if (alert.priority == ThreadNotificationPriority.HIGH) {
                    "Dealer attention required"
                } else {
                    "Dealer thread ready"
                },
                if (alert.priority == ThreadNotificationPriority.HIGH) {
                    NotificationManager.IMPORTANCE_HIGH
                } else {
                    NotificationManager.IMPORTANCE_DEFAULT
                },
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, DealerActivity::class.java)
                .setAction("$ACTION_OPEN_THREAD_NOTIFICATION.${alert.key}")
                .putExtra(EXTRA_THREAD_NOTIFICATION_KEY, alert.key)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val publicNotification = Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_dealer_connection)
            .setContentTitle(alert.content.publicTitle)
            .setContentText(alert.content.publicText)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_dealer_connection)
            .setContentTitle(alert.content.title)
            .setContentText(alert.content.text)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .build()
        manager.notify(alert.key, THREAD_NOTIFICATION_ID, notification)
    }

    private fun cancelThreadNotification(locator: CodexThreadLocator) {
        val key = threadNotificationKey(locator)
        if (threadNotificationTargets.remove(key) != null) {
            getSystemService(NotificationManager::class.java).cancel(key, THREAD_NOTIFICATION_ID)
        }
    }

    private fun isScreenInteractive(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive

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
        internal const val ACTION_OPEN_THREAD_NOTIFICATION =
            "com.code2hack.dealer.action.OPEN_THREAD_NOTIFICATION"
        internal const val EXTRA_THREAD_NOTIFICATION_KEY = "threadNotificationKey"

        const val NOTIFICATION_CHANNEL = "dealer-host-connection"
        const val THREAD_ATTENTION_CHANNEL = "dealer-thread-attention"
        const val THREAD_READY_CHANNEL = "dealer-thread-ready"
        const val NOTIFICATION_ID = 4090
        const val THREAD_NOTIFICATION_ID = 29
        const val ACTION_CANCEL = "com.code2hack.dealer.action.CANCEL_M1"
        const val ACTION_RETRY_POKER = "com.code2hack.dealer.action.RETRY_POKER"
        const val TAILNET_STATUS_INTERVAL_MILLIS = 1_000L
        const val INCOMPLETE_CARD_REREAD_DELAY_MILLIS = 500L
        const val DISCONNECT_RESOLUTION_WAIT_MILLIS = 1_000L
        val DISCONNECT_WAIT_STATES = setOf(
            RequestResolutionState.PENDING,
            RequestResolutionState.RESPONDING,
        )
        val STRUCTURED_CARD_NOTIFICATIONS = setOf(
            "item/started",
            "item/completed",
            "item/agentMessage/delta",
            "item/commandExecution/outputDelta",
            "item/fileChange/outputDelta",
            "item/fileChange/patchUpdated",
            "turn/diff/updated",
            "turn/completed",
        )
        val STRUCTURED_CARD_SOURCES = setOf(
            CardSource.CODEX_COMMAND,
            CardSource.CODEX_FILE_CHANGE,
        )
    }
}

private const val PHOTO_TRANSFER_TIMEOUT_MS = 15_000L

private data class DealerPhotoSession(
    val target: PhotoStartTarget,
    var cursorPosition: Int,
    val committedAssetIds: MutableSet<String> = linkedSetOf(),
)

private data class DealerPhotoTransfer(
    val target: PhotoAssetTarget,
    val mimeType: String,
    val expectedLength: Long,
    val timeout: Job,
    var nextOffset: Long = 0L,
)

private data class RetainedCards(
    val cards: List<Card>,
    val error: String? = null,
)

private data class DurableDealerState(
    val projection: DealerProjectionSnapshot,
    val pendingRequests: DealerPendingRequestSnapshot,
)

private fun DealerUiState.pendingRequestSnapshot() = DealerPendingRequestSnapshot(
    commandApprovals = commandApprovals,
    fileApprovals = fileApprovals,
    userInputRequests = userInputRequests,
)

internal fun DealerUiState.restoreAfterProcessDeath(
    attachments: Set<CodexThreadLocator>,
    actions: ThreadActionState,
    cards: List<Card>,
    projection: DealerProjectionSnapshot,
    pendingRequests: DealerPendingRequestSnapshot,
    error: String? = null,
): DealerUiState {
    val threads = projection.threads
        .filter { it.locator in attachments }
        .associate {
            it.locator to it.copy(
                attached = true,
                intendedControlSurface = ControlSurface.NONE,
            )
        }
    return DealerUiState(
        cards = cards.distinctBy { it.conversationId to it.id },
        threadAttachments = ThreadAttachmentState(attached = attachments),
        threadActions = actions,
        commandApprovals = pendingRequests.commandApprovals.afterProcessDeath(),
        userInputRequests = pendingRequests.userInputRequests.afterProcessDeath(),
        fileApprovals = pendingRequests.fileApprovals.afterProcessDeath(),
        threads = threads,
        error = error,
    ).withBlockingRequests()
}

private fun CommandApprovalState.afterProcessDeath(): CommandApprovalState = copy(
    requests = requests.mapValues { (_, request) ->
        if (request.resolution == RequestResolutionState.RESOLVED) {
            request
        } else {
            request.copy(resolution = RequestResolutionState.UNKNOWN)
        }
    },
)

private fun FileApprovalState.afterProcessDeath(): FileApprovalState = copy(
    requests = requests.mapValues { (_, request) ->
        if (request.resolution == RequestResolutionState.RESOLVED) {
            request
        } else {
            request.copy(resolution = RequestResolutionState.UNKNOWN)
        }
    },
)

private fun UserInputRequestState.afterProcessDeath(): UserInputRequestState = copy(
    requests = requests.mapValues { (_, request) ->
        if (request.resolution == RequestResolutionState.RESOLVED) {
            request
        } else {
            request.copy(resolution = RequestResolutionState.UNKNOWN)
        }
    },
)

private fun DealerUiState.withCommandApprovals(approvals: CommandApprovalState): DealerUiState {
    return copy(commandApprovals = approvals).withBlockingRequests()
}

private fun DealerUiState.withUserInputRequests(requests: UserInputRequestState): DealerUiState {
    return copy(
        userInputRequests = requests,
        userInputAnswers = userInputAnswers.copy(
            buffers = userInputAnswers.buffers.filterKeys {
                requests.requests[it]?.resolution?.let { resolution ->
                    resolution != RequestResolutionState.RESOLVED
                } == true
            },
        ),
    ).withBlockingRequests()
}

internal fun DealerUiState.withApprovals(
    commandApprovals: CommandApprovalState = this.commandApprovals,
    fileApprovals: FileApprovalState = this.fileApprovals,
): DealerUiState = copy(
        commandApprovals = commandApprovals,
        fileApprovals = fileApprovals,
    ).withBlockingRequests()

private fun DealerUiState.withBlockingRequests(): DealerUiState {
    val blocking = commandApprovals.unresolvedThreads() +
        fileApprovals.unresolvedThreads() +
        userInputRequests.unresolvedThreads()
    return copy(
        knownBlockingRequestThreads = blocking,
        threads = threads.mapValues { (locator, thread) ->
            when {
                locator in blocking -> thread.copy(workState = ThreadWorkState.ATTENTION_REQUIRED)
                locator in knownBlockingRequestThreads -> thread.copy(
                    workState = if (thread.activeTurnId == null) {
                        ThreadWorkState.READY
                    } else {
                        ThreadWorkState.BUSY
                    },
                )
                else -> thread
            }
        },
    )
}

private fun DealerUiState.unresolvedRequest(thread: CodexThreadLocator): ServerRequestLocator? = buildList {
    commandApprovals.unresolved()
        .filter { it.thread == thread }
        .forEach { add(it.createdAtMs to it.locator) }
    fileApprovals.unresolved()
        .filter { it.thread == thread }
        .forEach { add(it.createdAtMs to it.locator) }
    userInputRequests.unresolved()
        .filter { it.thread == thread }
        .forEach { add(it.receivedAtMs to it.locator) }
}.minByOrNull(Pair<Long, ServerRequestLocator>::first)?.second

private fun DealerUiState.fileReviewCard(hostId: String, wire: AppServerRequest): Card? {
    val params = wire.params as? JsonObject ?: return null
    val threadId = (params["threadId"] as? JsonPrimitive)?.contentOrNull ?: return null
    val itemId = (params["itemId"] as? JsonPrimitive)?.contentOrNull ?: return null
    return cards.singleOrNull {
        it.conversationId == "$hostId/$threadId" &&
            it.id == itemId &&
            it.source == CardSource.CODEX_FILE_CHANGE
    }
}

private fun Card.reviewMaterialPresent(): Boolean = when (source) {
    CardSource.CODEX_COMMAND -> command != null && workingDirectory != null && status != null
    CardSource.CODEX_FILE_CHANGE -> status != null && fileChanges.isNotEmpty()
    else -> true
}

private data class PokerComposerBinding(
    val epoch: Long,
    val controlGeneration: Long,
    val modeSession: String,
)

private data class PokerUserInputBinding(
    val epoch: Long,
    val controlGeneration: Long,
    val modeSession: String,
)

private data class PokerApprovalBinding(
    val epoch: Long,
    val controlGeneration: Long,
    val modeSession: String,
)

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

data class DealerAsrUiState(
    val active: Boolean = false,
    val sessionId: String? = null,
    val target: PokerAsrTarget? = null,
    val pack: com.code2hack.pokerdealer.protocol.PokerAsrPackSelection? = null,
    val provisionalText: String = "",
)

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
    val commandApprovals: CommandApprovalState = CommandApprovalState(),
    val userInputRequests: UserInputRequestState = UserInputRequestState(),
    val userInputAnswers: UserInputAnswerState = UserInputAnswerState(),
    val fileApprovals: FileApprovalState = FileApprovalState(),
    val knownBlockingRequestThreads: Set<CodexThreadLocator> = emptySet(),
    val tailnet: EmbeddedTailnetUiState = EmbeddedTailnetUiState(),
    val pokerBindings: PokerBindingState = PokerBindingState(),
    val pokerConnected: Boolean = false,
    val asr: DealerAsrUiState = DealerAsrUiState(),
    val pokerFont: PokerFontScaleState = PokerFontScaleState(),
    val pokerDiagnostics: DealerPokerDiagnostics = DealerPokerDiagnostics(),
    val hostSessions: Map<String, HostSessionState> = emptyMap(),
    val threads: Map<CodexThreadLocator, DiscoveredThread> = emptyMap(),
    val refreshingThreadHosts: Set<String> = emptySet(),
    val threadDiscoveryErrors: Map<String, String> = emptyMap(),
    val browsedThread: CodexThreadLocator? = null,
    val newThread: NewThreadUiState? = null,
    val resumeThread: ResumeThreadUiState? = null,
    val lifecycleReview: ThreadLifecycleReviewUiState? = null,
    val browsedRequest: ServerRequestLocator? = null,
    val error: String? = null,
) {
    val running: Boolean
        get() = status.active
}

data class NewThreadUiState(
    val hostId: String,
    val observedWorkingDirectories: List<String>,
    val workingDirectory: String,
    val sourceLocator: CodexThreadLocator? = null,
    val catalog: ThreadStartCatalog? = null,
    val loading: Boolean = false,
    val creating: Boolean = false,
    val error: String? = null,
)

data class ResumeThreadUiState(
    val locator: CodexThreadLocator,
    val observedWorkingDirectories: List<String>,
    val workingDirectory: String,
    val catalog: ThreadStartCatalog? = null,
    val controlClaimed: Boolean = false,
    val loading: Boolean = false,
    val resuming: Boolean = false,
    val error: String? = null,
)

data class ThreadLifecycleReviewUiState(
    val action: ThreadLifecycleAction,
    val locator: CodexThreadLocator,
    val preflight: ThreadCascadePreflight? = null,
    val loading: Boolean = false,
    val committing: Boolean = false,
    val error: String? = null,
)

internal fun DealerUiState.withThreadCreationFailure(message: String): DealerUiState = copy(
    newThread = newThread?.copy(creating = false, error = message),
)

internal fun DealerUiState.withThreadResumeFailure(message: String): DealerUiState = copy(
    resumeThread = resumeThread?.copy(resuming = false, error = message),
)

internal fun DealerUiState.withResumeControlClaim(claimed: Boolean): DealerUiState {
    val review = resumeThread ?: return this
    val workState = threads[review.locator]?.workState
    return if (claimed && workState != ThreadWorkState.READY) {
        copy(resumeThread = review.copy(error = "Resume overrides require a READY thread"))
    } else {
        copy(resumeThread = review.copy(controlClaimed = claimed, error = null))
    }
}

internal fun DealerUiState.resumeControlError(selection: ThreadStartSelection): String? {
    if (!selection.hasControlOverrides()) return null
    val review = resumeThread ?: return "Review Resume settings before attaching"
    return when {
        threads[review.locator]?.workState != ThreadWorkState.READY ->
            "Resume overrides require a READY thread"
        !review.controlClaimed -> "Take Dealer control before applying Resume overrides"
        else -> null
    }
}

internal fun DealerUiState.withRenamedThread(
    locator: CodexThreadLocator,
    name: String?,
): DealerUiState = copy(
    threads = threads[locator]?.let { threads + (locator to it.copy(name = name)) } ?: threads,
)

internal fun DiscoveredThread.canFork(): Boolean = workState == ThreadWorkState.READY

internal fun DealerUiState.withArchivedThreads(
    locators: Set<CodexThreadLocator>,
): DealerUiState = copy(
    threadAttachments = locators.fold(threadAttachments) { state, locator -> state.detach(locator) },
    threads = threads.mapValues { (locator, thread) ->
        if (locator in locators) {
            thread.copy(
                archived = true,
                attached = false,
                intendedControlSurface = ControlSurface.NONE,
            )
        } else {
            thread
        }
    },
    browsedThread = browsedThread?.takeUnless { it in locators },
)

internal fun DealerUiState.withRestoredThread(locator: CodexThreadLocator): DealerUiState = copy(
    threadAttachments = threadAttachments.detach(locator),
    threads = threads[locator]?.let {
        threads + (
            locator to it.copy(
                archived = false,
                attached = false,
                intendedControlSurface = ControlSurface.NONE,
            )
        )
    } ?: threads,
    browsedThread = browsedThread?.takeUnless { it == locator },
)

internal fun DealerUiState.withDeletedThreads(
    locators: Set<CodexThreadLocator>,
): DealerUiState {
    val conversationIds = locators.mapTo(mutableSetOf()) { "${it.hostId}/${it.threadId}" }
    return copy(
        threadAttachments = locators.fold(threadAttachments) { state, locator -> state.detach(locator) },
        threadActions = threadActions.purge(locators),
        knownBlockingRequestThreads = knownBlockingRequestThreads - locators,
        threads = threads - locators,
        cards = cards.filterNot { it.conversationId in conversationIds },
        browsedThread = browsedThread?.takeUnless { it in locators },
    )
}

internal fun confirmedLifecycleLocators(
    action: ThreadLifecycleAction,
    reviewedLocators: Set<CodexThreadLocator>,
    discovered: List<DiscoveredThread>,
): Set<CodexThreadLocator> {
    val rows = discovered.associateBy(DiscoveredThread::locator)
    return reviewedLocators.filterTo(mutableSetOf()) { locator ->
        when (action) {
            ThreadLifecycleAction.ARCHIVE -> rows[locator]?.archived == true
            ThreadLifecycleAction.DELETE -> locator !in rows
        }
    }
}

internal fun DealerUiState.withDiscoveredThreads(
    hostId: String,
    discovered: List<DiscoveredThread>,
): DealerUiState {
    val rows = discovered.associateBy(DiscoveredThread::locator).mapValues { (locator, row) ->
        row.copy(
            activeTurnId = threads[locator]?.activeTurnId
                .takeIf { row.workState == ThreadWorkState.BUSY },
            attached = locator in threadAttachments.attached,
            unreadCount = threads[locator]?.unreadCount ?: row.unreadCount,
            intendedControlSurface = if (threadAttachments.hasDealerClaim(locator)) {
                ControlSurface.DEALER
            } else {
                ControlSurface.NONE
            },
        )
    }
    return copy(threads = threads.filterKeys { it.hostId != hostId } + rows)
}

internal fun DealerUiState.withCreatedThread(
    locator: CodexThreadLocator,
    name: String?,
    preview: String?,
    selection: ThreadStartSelection,
): DealerUiState = copy(
    threadAttachments = threadAttachments.attach(locator).claim(locator),
    threadActions = threadActions.setPendingReasoningEffort(locator, selection.reasoningEffort),
    threads = threads + (
        locator to DiscoveredThread(
            locator = locator,
            name = name,
            preview = preview,
            workingDirectory = selection.workingDirectory,
            status = "idle",
            workState = ThreadWorkState.READY,
            attached = true,
            intendedControlSurface = ControlSurface.DEALER,
        )
    ),
    browsedThread = locator,
    newThread = null,
    error = null,
)

internal fun DealerUiState.withResumedThread(
    locator: CodexThreadLocator,
    selection: ThreadStartSelection,
    grantControl: Boolean,
): DealerUiState {
    val attached = threadAttachments.attach(locator).let {
        if (grantControl) it.claim(locator) else it
    }
    return copy(
        threadAttachments = attached,
        threadActions = threadActions.setPendingReasoningEffort(locator, selection.reasoningEffort),
        threads = threads[locator]?.let { thread ->
            threads + (
                locator to thread.copy(
                    workingDirectory = selection.workingDirectory,
                    attached = true,
                    intendedControlSurface = if (grantControl) {
                        ControlSurface.DEALER
                    } else {
                        ControlSurface.NONE
                    },
                )
            )
        } ?: threads,
        browsedThread = locator,
        resumeThread = null,
        error = null,
    )
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
