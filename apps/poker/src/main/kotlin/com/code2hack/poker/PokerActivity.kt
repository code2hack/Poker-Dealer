package com.code2hack.poker

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Text
import android.view.TextureView
import androidx.lifecycle.lifecycleScope
import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.PokerCardLayout
import com.code2hack.pokerdealer.domain.PokerInputController
import com.code2hack.pokerdealer.domain.PokerNavigationReducer
import com.code2hack.pokerdealer.domain.PokerPileLayout
import com.code2hack.pokerdealer.domain.PokerPostureSample
import com.code2hack.pokerdealer.domain.PokerWheelState
import com.code2hack.pokerdealer.domain.ThreadWorkEvidence
import com.code2hack.pokerdealer.domain.ThreadWorkState
import com.code2hack.pokerdealer.protocol.PokerSnapshot
import com.code2hack.pokerdealer.protocol.PokerSnapshotPile
import com.code2hack.pokerdealer.protocol.PokerSnapshotPileMetadata
import com.code2hack.pokerdealer.protocol.PokerSnapshotRequestCard
import com.code2hack.pokerdealer.protocol.PokerTransientNotice
import com.code2hack.pokerdealer.protocol.UserInputRequestProjection
import com.code2hack.pokerdealer.protocol.pokerUnreadRequestKey
import com.code2hack.pokerdealer.domain.RequestResolutionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PokerActivity : ComponentActivity() {
    private lateinit var input: PokerAndroidInputAdapter
    private lateinit var screenState: MutableState<PokerScreenState>
    private lateinit var navigation: PokerNavigationReducer
    private lateinit var composerController: PokerComposerController
    private lateinit var morseController: PokerMorseController
    private lateinit var primaryActionController: PokerPrimaryActionController
    private lateinit var photoController: PokerPhotoController
    private lateinit var camera: PokerCamera2Controller
    private lateinit var approvalController: PokerApprovalController
    private var postureSensorManager: SensorManager? = null
    private var postureSensor: Sensor? = null
    private val postureRotation = FloatArray(9)
    private val postureOrientation = FloatArray(3)
    private val postureListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!::input.isInitialized || event.values.isEmpty()) return
            SensorManager.getRotationMatrixFromVector(postureRotation, event.values)
            SensorManager.getOrientation(postureRotation, postureOrientation)
            input.onPosture(
                PokerPostureSample(
                    pitchDegrees = Math.toDegrees(postureOrientation[1].toDouble()).toFloat(),
                    rollDegrees = Math.toDegrees(postureOrientation[2].toDouble()).toFloat(),
                    eventTimeMs = SystemClock.uptimeMillis(),
                ),
            )?.let { wheelState ->
                screenState.value = currentScreenState(wheelState)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    private var cardTextByLocator: Map<CodexThreadLocator, String> = emptyMap()
    private var cardsByLocator: Map<CodexThreadLocator, List<Card>> = emptyMap()
    private var metadataByLocator: Map<CodexThreadLocator, PokerSnapshotPileMetadata> = emptyMap()
    private var requestCardsByLocator: Map<CodexThreadLocator, List<PokerSnapshotRequestCard>> = emptyMap()
    private var foreground = false
    private val inputDeviceDescriptors = mutableMapOf<Int, String>()
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = rememberInputDevice(deviceId)

        override fun onInputDeviceChanged(deviceId: Int) = rememberInputDevice(deviceId)

        override fun onInputDeviceRemoved(deviceId: Int) {
            inputDeviceDescriptors.remove(deviceId)?.let { descriptor ->
                if (::input.isInitialized) input.onRemoteDisconnected(descriptor)
            }
        }
    }
    private lateinit var userInputController: PokerUserInputController
    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) camera.open() else photoController.onPermissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        navigation = PokerNavigationReducer(viewportLineCount = 12)
        composerController = PokerComposerController(navigation, PokerComposerBridge::sendMutation)
        userInputController = PokerUserInputController(navigation, PokerComposerBridge::sendUserInputMutation)
        morseController = PokerMorseController(
            navigation = navigation,
            composer = composerController,
            userInput = userInputController,
            wheelContext = { primaryActionController.wheelContext() },
            scope = lifecycleScope,
            sendMutation = PokerComposerBridge::sendMorseMutation,
            longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong(),
            onNotice = { message, durationMs ->
                PokerNoticeRuntime.show(PokerTransientNotice(message, durationMs))
            },
        )
        approvalController = PokerApprovalController(navigation)
        primaryActionController = PokerPrimaryActionController(
            navigation = navigation,
            composer = composerController,
            userInput = userInputController,
            approvals = approvalController,
            sendAction = PokerComposerBridge::sendPrimaryAction,
        )
        camera = PokerCamera2Controller(
            activity = this,
            onPermissionRequired = { cameraPermission.launch(Manifest.permission.CAMERA) },
            onFailure = { photoController.onCameraFailure() },
        )
        photoController = PokerPhotoController(
            navigation = navigation,
            composer = composerController,
            scope = lifecycleScope,
            sendStart = PokerComposerBridge::sendPhotoStart,
            sendBegin = PokerComposerBridge::sendPhotoCaptureBegin,
            sendChunk = PokerComposerBridge::sendPhotoCaptureChunk,
            sendComplete = PokerComposerBridge::sendPhotoCaptureComplete,
            sendDelete = PokerComposerBridge::sendPhotoDelete,
            sendCancel = PokerComposerBridge::sendPhotoCancel,
            openCamera = camera::open,
            closeCamera = camera::close,
            setCameraZoom = camera::setZoom,
            storageAvailable = { pokerPhotoStorageAvailable(this) },
        )
        photoController.setCaptureRequestedCallback {
            try {
                camera.capture()
            } finally {
                camera.close()
            }
        }
        screenState = mutableStateOf(currentScreenState())
        lifecycleScope.launch {
            PokerSnapshotRuntime.snapshot.collect { snapshot ->
                cardsByLocator = snapshot?.piles.orEmpty().associate { it.metadata.locator to it.cards }
                metadataByLocator = snapshot?.piles.orEmpty()
                    .associate { it.metadata.locator to it.metadata }
                requestCardsByLocator = snapshot?.piles.orEmpty()
                    .associate { it.metadata.locator to it.requestCards }
                cardTextByLocator = navigation.installPokerSnapshot(snapshot)
                PokerComposerBridge.projections.value.values.forEach(composerController::applyProjection)
                PokerComposerBridge.userInputProjections.value.values.forEach(userInputController::applyProjection)
                PokerComposerBridge.approvalProjections.value.values.forEach(approvalController::applyProjection)
                screenState.value = currentScreenState()
            }
        }
        lifecycleScope.launch {
            PokerSnapshotRuntime.unreadCount.collect {
                screenState.value = currentScreenState()
            }
        }
        lifecycleScope.launch {
            PokerComposerBridge.projections.collect { projections ->
                projections.values.forEach(composerController::applyProjection)
                screenState.value = currentScreenState()
            }
        }
        lifecycleScope.launch {
            PokerComposerBridge.results.collect { results ->
                results.values.forEach(composerController::applyResult)
                screenState.value = currentScreenState()
            }
        }
        lifecycleScope.launch {
            PokerComposerBridge.userInputProjections.collect { projections ->
                projections.values.forEach(userInputController::applyProjection)
                screenState.value = currentScreenState()
            }
        }
        lifecycleScope.launch {
            PokerComposerBridge.userInputResults.collect { results ->
                results.values.forEach(userInputController::applyResult)
                screenState.value = currentScreenState()
            }
        }
        lifecycleScope.launch {
            PokerComposerBridge.morseResults.collect { results ->
                results.values.forEach(morseController::apply)
                screenState.value = currentScreenState(screenState.value.wheelState)
            }
        }
        lifecycleScope.launch {
            PokerComposerBridge.approvalProjections.collect { projections ->
                projections.values.forEach(approvalController::applyProjection)
                screenState.value = currentScreenState(screenState.value.wheelState)
            }
        }
        lifecycleScope.launch {
            PokerComposerBridge.primaryResults.collect { results ->
                results.values.forEach(primaryActionController::applyResult)
                screenState.value = currentScreenState(screenState.value.wheelState)
            }
        }
        lifecycleScope.launch {
            PokerComposerBridge.photoStartResults.collect { results ->
                results.values.forEach(photoController::onStartResult)
            }
        }
        lifecycleScope.launch {
            PokerComposerBridge.photoCaptureResults.collect { results ->
                results.values.forEach(photoController::onCaptureResult)
            }
        }
        lifecycleScope.launch {
            PokerComposerBridge.photoDeleteResults.collect { results ->
                results.values.forEach(photoController::onDeleteResult)
            }
        }
        lifecycleScope.launch {
            photoController.state.collect {
                screenState.value = currentScreenState(screenState.value.wheelState)
            }
        }
        val bindings = PokerBindingRuntime.controller
        val controller = PokerInputController(
            navigation = navigation,
            wheelContext = primaryActionController::wheelContext,
            longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong(),
            morse = morseController.input,
        )
        fun handleWheelSelection(selection: com.code2hack.pokerdealer.domain.PokerWheelSelection) {
            lifecycleScope.launch {
                when (selection.action) {
                    com.code2hack.pokerdealer.domain.PokerWheelAction.MORSE ->
                        morseController.begin(selection, SystemClock.uptimeMillis())
                    com.code2hack.pokerdealer.domain.PokerWheelAction.PHOTO ->
                        photoController.start(selection)
                    else -> primaryActionController.submit(selection)
                }
                screenState.value = currentScreenState(screenState.value.wheelState)
            }
        }
        val onNavigationChanged = {
            screenState.value = currentScreenState(screenState.value.wheelState)
        }
        val onBindingChanged = {
            screenState.value = currentScreenState()
            PokerBindingRuntime.notifyLocalChange()
        }
        input = PokerAndroidInputAdapter(
            PokerBuiltInInputAdapter(
                controller = controller,
                bindings = bindings,
                onNavigationChanged = onNavigationChanged,
                onResult = { result ->
                    screenState.value = currentScreenState(result.wheelState)
                    result.wheelSelection?.let { selection ->
                        handleWheelSelection(selection)
                    }
                    if (
                        result.morseEvent == null &&
                        result.interaction.phase == com.code2hack.pokerdealer.domain.PokerInteractionPhase.RELEASE &&
                        result.interaction.operation == com.code2hack.pokerdealer.domain.PokerOperation.TAP
                    ) {
                        lifecycleScope.launch {
                            userInputController.selectFocused()
                            screenState.value = currentScreenState()
                        }
                    }
                    result.composerDeletion?.takeIf { result.morseEvent == null }?.let { deletion ->
                        lifecycleScope.launch {
                            composerController.requestDeletion(deletion)
                            screenState.value = currentScreenState()
                        }
                    }
                    morseController.handle(result.morseEvent)
                },
                onWheelChanged = { wheelState ->
                    screenState.value = currentScreenState(wheelState)
                },
                photoHandler = photoController::handleInteraction,
            ),
            remote = PokerRemoteInputAdapter(
                controller = controller,
                bindings = bindings,
                isForeground = { foreground },
                onNavigationChanged = onNavigationChanged,
                onBindingChanged = onBindingChanged,
                onNotice = { text -> PokerNoticeRuntime.show(PokerTransientNotice(text, 500L)) },
                onResult = { result ->
                    screenState.value = currentScreenState(result.wheelState)
                    result.wheelSelection?.let { selection ->
                        handleWheelSelection(selection)
                    }
                    morseController.handle(result.morseEvent)
                },
                onWheelChanged = { wheelState ->
                    screenState.value = currentScreenState(wheelState)
                },
                photoHandler = photoController::handleInteraction,
            ),
        )
        lifecycleScope.launch {
            while (isActive) {
                delay(50L)
                if (morseController.input.isActive) {
                    morseController.tick(SystemClock.uptimeMillis())
                }
            }
        }
        PokerBindingRuntime.attachActivity {
            photoController.onConnectionLost()
            input.onConnectionLost()
            morseController.abort()
        }
        getSystemService(InputManager::class.java)
            ?.registerInputDeviceListener(inputDeviceListener, null)
        android.view.InputDevice.getDeviceIds().forEach(::rememberInputDevice)
        postureSensorManager = getSystemService(SensorManager::class.java)
        postureSensor = postureSensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        postureSensor?.let { sensor ->
            postureSensorManager?.registerListener(postureListener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        setContent {
            val photoState by photoController.state.collectAsState()
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    val fontScale by PokerPresentationRuntime.fontScale.collectAsState()
                    val notice by PokerNoticeRuntime.notice.collectAsState()
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(
                            density = density.density,
                            fontScale = density.fontScale * fontScale.factor,
                        ),
                    ) {
                        PokerCardReader(screenState.value, photoState, camera, notice)
                    }
                }
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        if (::input.isInitialized && input.onTouchEvent(event)) true else super.dispatchTouchEvent(event)

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (::input.isInitialized && input.onKeyEvent(event)) true else super.dispatchKeyEvent(event)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        foreground = hasFocus
        PokerBindingRuntime.setForeground(hasFocus)
        if (!hasFocus) {
            if (::photoController.isInitialized) photoController.exit()
            if (::input.isInitialized) input.onFocusLost()
            if (::morseController.isInitialized) morseController.abort()
        }
    }

    override fun onDestroy() {
        getSystemService(InputManager::class.java)
            ?.unregisterInputDeviceListener(inputDeviceListener)
        postureSensorManager?.unregisterListener(postureListener)
        postureSensorManager = null
        postureSensor = null
        inputDeviceDescriptors.clear()
        PokerBindingRuntime.detachActivity()
        PokerBindingRuntime.setForeground(false)
        if (::input.isInitialized) input.onDisconnected()
        if (::photoController.isInitialized) photoController.close()
        if (::camera.isInitialized) camera.close()
        if (::morseController.isInitialized) morseController.abort()
        super.onDestroy()
    }

    private fun rememberInputDevice(deviceId: Int) {
        val device = android.view.InputDevice.getDevice(deviceId)
        val descriptor = device
            ?.takeIf { pokerAndroidInputDeviceKind(it) == PokerAndroidInputDeviceKind.EXTERNAL_HID }
            ?.descriptor
            ?.takeIf(String::isNotBlank)
        if (descriptor == null) {
            inputDeviceDescriptors.remove(deviceId)
        } else {
            inputDeviceDescriptors[deviceId] = descriptor
        }
    }

    private fun currentScreenState(wheelState: PokerWheelState = PokerWheelState()): PokerScreenState = navigation.snapshot(
        cardTextByLocator = cardTextByLocator,
        requestProjectionsByLocator = currentRequestProjections(),
        cardsByLocator = cardsByLocator,
        metadataByLocator = metadataByLocator,
        requestCardsByLocator = requestCardsByLocator,
        unreadCount = PokerSnapshotRuntime.unreadCount.value,
        wheelState = wheelState,
    )

}

/** Installs Dealer metadata without replacing Poker-local presentation state. */
internal fun PokerNavigationReducer.installPokerSnapshot(
    snapshot: PokerSnapshot?,
): Map<CodexThreadLocator, String> {
    val before = metadata()
    val currentLocators = (before.orderedPiles + before.unknownWorkState).map { it.locator }
    val currentFocusableLocators = before.orderedPiles.map { it.locator }
    val previousLayouts = currentLocators.associateWith(::layout)
    val piles = snapshot?.piles.orEmpty()
    val pilesByLocator = piles.associateBy { it.metadata.locator }
    val nextMetadata = snapshot?.projection?.orderedPiles.orEmpty() +
        snapshot?.projection?.unknownWorkState.orEmpty()
    val nextLocators = nextMetadata.map { it.locator }
    val nextFocusableLocators = snapshot?.projection?.orderedPiles.orEmpty().map { it.locator }
    val nextLocatorSet = nextLocators.toSet()

    currentLocators.filter { it !in nextLocatorSet }.forEach(::detach)
    nextMetadata.forEach { metadata ->
        val pile = pilesByLocator.getValue(metadata.locator)
        val layout = pile.layout(previousLayouts[metadata.locator])
        if (metadata.locator in currentLocators) {
            reconcile(
                locator = metadata.locator,
                evidence = metadata.evidence(),
                atMs = metadata.stateChangedAtMs,
                available = metadata.available,
            )
            setLayout(metadata.locator, layout)
        } else {
            attach(
                locator = metadata.locator,
                evidence = metadata.evidence(),
                atMs = metadata.stateChangedAtMs,
                available = metadata.available,
                layout = layout,
            )
        }
    }

    if (before.hudVisible) {
        val focused = before.focused
        val replacement = when {
            focused == null -> null
            focused in nextFocusableLocators -> focused
            else -> {
                val oldIndex = currentFocusableLocators.indexOf(focused)
                nextFocusableLocators.getOrNull(oldIndex)
                    ?: nextFocusableLocators.getOrNull(oldIndex - 1)
            }
        }
        if (replacement != null) view(replacement) else manualHide()
    }

    return piles.associate { pile ->
        pile.metadata.locator to pile.cards.joinToString("\n\n") { it.fullText }
    }
}

@Composable
private fun PokerCardReader(
    state: PokerScreenState,
    photoState: PokerPhotoState,
    camera: PokerCamera2Controller,
    notice: PokerTransientNotice?,
) {
    if (photoState.phase != PokerPhotoPhase.IDLE) {
        PokerPhotoSurface(photoState, camera)
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            PokerPilePages(
                metadata = state.metadata,
                cardTextByLocator = state.cardTextByLocator,
                anchorByLocator = state.anchors,
                composerTextByLocator = state.composerTextByLocator,
                requestProjectionsByLocator = state.requestProjectionsByLocator,
                approvalProjectionsByLocator = state.approvalProjectionsByLocator,
                cardsByLocator = state.cardsByLocator,
                metadataByLocator = state.metadataByLocator,
                unreadCount = state.unreadCount,
                onCardFinalLineVisible = { locator, cardId ->
                    PokerSnapshotRuntime.markCardRead(
                        locator,
                        cardId,
                        finalized = true,
                        finalLineVisible = true,
                    )
                    state.requestCardsByLocator[locator].orEmpty()
                        .filter { it.cardId == cardId && it.finalized }
                        .forEach { request ->
                            PokerSnapshotRuntime.markRequestRead(
                                locator,
                                request.key,
                                finalized = true,
                                finalLineVisible = true,
                            )
                        }
                    state.requestProjectionsByLocator[locator].orEmpty()
                        .filter {
                            it.cardId == cardId && it.request.resolution.isFinalized()
                        }
                        .forEach { projection ->
                            PokerSnapshotRuntime.markRequestRead(
                                locator,
                                pokerUnreadRequestKey(
                                    "user-input",
                                    projection.request.locator.requestId,
                                    projection.request.fingerprint,
                                ),
                                finalized = true,
                                finalLineVisible = true,
                            )
                        }
                },
                wheelState = state.wheelState,
                notice = notice,
                modifier = Modifier.fillMaxSize(),
            )
            photoState.notice?.let { notice ->
                Text(
                    text = notice,
                    color = Color(0xFFFFD18A),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun PokerPhotoSurface(state: PokerPhotoState, camera: PokerCamera2Controller) {
    Box(modifier = Modifier.fillMaxSize()) {
        val frozen = state.frozenBytes?.let { bytes ->
            remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        }
        if (frozen != null) {
            Image(
                bitmap = frozen.asImageBitmap(),
                contentDescription = "Captured photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (state.phase != PokerPhotoPhase.STARTING) {
            AndroidView(
                factory = { TextureView(it).also(camera::attach) },
                update = camera::attach,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = "Photo ${state.phase.name.lowercase()}  ${"%.2f".format(state.zoom)}x",
            color = Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
        )
        state.notice?.let { notice ->
            Text(
                text = notice,
                color = Color(0xFFFFD18A),
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

private data class PokerScreenState(
    val metadata: com.code2hack.pokerdealer.domain.PokerPileMetadata,
    val anchors: Map<CodexThreadLocator, com.code2hack.pokerdealer.domain.PokerPileAnchor>,
    val cardTextByLocator: Map<CodexThreadLocator, String>,
    val composerTextByLocator: Map<CodexThreadLocator, String>,
    val requestProjectionsByLocator: Map<CodexThreadLocator, List<UserInputRequestProjection>>,
    val cardsByLocator: Map<CodexThreadLocator, List<Card>>,
    val metadataByLocator: Map<CodexThreadLocator, PokerSnapshotPileMetadata>,
    val requestCardsByLocator: Map<CodexThreadLocator, List<PokerSnapshotRequestCard>>,
    val unreadCount: Int,
    val approvalProjectionsByLocator: Map<CodexThreadLocator, List<com.code2hack.pokerdealer.protocol.PokerApprovalRequestProjection>>,
    val wheelState: PokerWheelState = PokerWheelState(),
)

private fun PokerNavigationReducer.snapshot(
    cardTextByLocator: Map<CodexThreadLocator, String>,
    requestProjectionsByLocator: Map<CodexThreadLocator, List<UserInputRequestProjection>> = emptyMap(),
    cardsByLocator: Map<CodexThreadLocator, List<Card>> = emptyMap(),
    metadataByLocator: Map<CodexThreadLocator, PokerSnapshotPileMetadata> = emptyMap(),
    requestCardsByLocator: Map<CodexThreadLocator, List<PokerSnapshotRequestCard>> = emptyMap(),
    unreadCount: Int = PokerSnapshotRuntime.unreadCount.value,
    wheelState: PokerWheelState = PokerWheelState(),
    approvalProjectionsByLocator: Map<CodexThreadLocator, List<com.code2hack.pokerdealer.protocol.PokerApprovalRequestProjection>> = currentApprovalProjections(),
): PokerScreenState {
    val metadata = metadata()
    return PokerScreenState(
        metadata = metadata,
        anchors = anchors(),
        cardTextByLocator = cardTextByLocator,
        composerTextByLocator = metadata.orderedPiles.mapNotNull { pile ->
            layout(pile.locator)?.composer?.draft?.displayText?.let { pile.locator to it }
        }.toMap(),
        requestProjectionsByLocator = requestProjectionsByLocator,
        cardsByLocator = cardsByLocator,
        metadataByLocator = metadataByLocator,
        requestCardsByLocator = requestCardsByLocator,
        unreadCount = unreadCount,
        approvalProjectionsByLocator = approvalProjectionsByLocator,
        wheelState = wheelState,
    )
}

private fun PokerSnapshotPileMetadata.evidence(): ThreadWorkEvidence = when (workState) {
    "BUSY" -> ThreadWorkEvidence(activeTurn = true, unresolvedRequestCount = 0)
    "ATTENTION_REQUIRED" -> ThreadWorkEvidence(activeTurn = true, unresolvedRequestCount = 1)
    "READY" -> ThreadWorkEvidence(activeTurn = false, unresolvedRequestCount = 0)
    null -> ThreadWorkEvidence(activeTurn = null, unresolvedRequestCount = null)
    else -> error("Unknown snapshot work state: $workState")
}

private fun currentRequestProjections(): Map<CodexThreadLocator, List<UserInputRequestProjection>> =
    PokerComposerBridge.userInputProjections.value.values.groupBy { it.request.thread }

private fun RequestResolutionState.isFinalized(): Boolean = this != RequestResolutionState.PENDING &&
    this != RequestResolutionState.RESPONDING
private fun currentApprovalProjections(): Map<CodexThreadLocator, List<com.code2hack.pokerdealer.protocol.PokerApprovalRequestProjection>> =
    PokerComposerBridge.approvalProjections.value.values.groupBy { it.thread }

private fun PokerSnapshotPile.layout(previous: PokerPileLayout? = null): PokerPileLayout {
    val previousCards = previous?.cards.orEmpty().associateBy(PokerCardLayout::id)
    return PokerPileLayout(
        cards = cards.map { card ->
            val previousCard = previousCards[card.id]
            val collapsedLineCount = (card.fullText.count { it == '\n' } + 1).coerceAtLeast(1)
            PokerCardLayout(
                id = card.id,
                collapsedLineCount = collapsedLineCount,
                expandedLineCount = maxOf(
                    collapsedLineCount,
                    previousCard?.expandedLineCount ?: 0,
                ),
                requestPanel = previousCard?.requestPanel,
            )
        },
        composer = previous?.composer,
    )
}
