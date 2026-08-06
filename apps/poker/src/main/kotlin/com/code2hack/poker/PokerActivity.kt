package com.code2hack.poker

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.PokerCardLayout
import com.code2hack.pokerdealer.domain.PokerInputController
import com.code2hack.pokerdealer.domain.PokerNavigationReducer
import com.code2hack.pokerdealer.domain.PokerPileLayout
import com.code2hack.pokerdealer.domain.ThreadWorkEvidence
import com.code2hack.pokerdealer.domain.ThreadWorkState
import com.code2hack.pokerdealer.protocol.PokerSnapshot
import com.code2hack.pokerdealer.protocol.PokerSnapshotPile
import com.code2hack.pokerdealer.protocol.PokerSnapshotPileMetadata
import kotlinx.coroutines.launch

class PokerActivity : ComponentActivity() {
    private lateinit var input: PokerAndroidInputAdapter
    private lateinit var screenState: MutableState<PokerScreenState>
    private lateinit var navigation: PokerNavigationReducer
    private lateinit var composerController: PokerComposerController
    private var cardTextByLocator: Map<CodexThreadLocator, String> = emptyMap()
    private lateinit var userInputController: PokerUserInputController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        navigation = PokerNavigationReducer(viewportLineCount = 12)
        composerController = PokerComposerController(navigation, PokerComposerBridge::sendMutation)
        userInputController = PokerUserInputController(navigation, PokerComposerBridge::sendUserInputMutation)
        screenState = mutableStateOf(navigation.snapshot(cardTextByLocator, currentRequestProjections()))
        lifecycleScope.launch {
            PokerSnapshotRuntime.snapshot.collect { snapshot ->
                cardTextByLocator = navigation.installPokerSnapshot(snapshot)
                PokerComposerBridge.projections.value.values.forEach(composerController::applyProjection)
                PokerComposerBridge.userInputProjections.value.values.forEach(userInputController::applyProjection)
                screenState.value = navigation.snapshot(cardTextByLocator, currentRequestProjections())
            }
        }
        lifecycleScope.launch {
            PokerComposerBridge.projections.collect { projections ->
                projections.values.forEach(composerController::applyProjection)
                screenState.value = navigation.snapshot(cardTextByLocator, currentRequestProjections())
            }
        }
        lifecycleScope.launch {
            PokerComposerBridge.results.collect { results ->
                results.values.forEach(composerController::applyResult)
                screenState.value = navigation.snapshot(cardTextByLocator, currentRequestProjections())
            }
        }
        lifecycleScope.launch {
            PokerComposerBridge.userInputProjections.collect { projections ->
                projections.values.forEach(userInputController::applyProjection)
                screenState.value = navigation.snapshot(cardTextByLocator, currentRequestProjections())
            }
        }
        lifecycleScope.launch {
            PokerComposerBridge.userInputResults.collect { results ->
                results.values.forEach(userInputController::applyResult)
                screenState.value = navigation.snapshot(cardTextByLocator, currentRequestProjections())
            }
        }
        input = PokerAndroidInputAdapter(
            PokerBuiltInInputAdapter(
                controller = PokerInputController(navigation),
                onNavigationChanged = { screenState.value = navigation.snapshot(cardTextByLocator, currentRequestProjections()) },
                onResult = { result ->
                    if (
                        result.interaction.phase == com.code2hack.pokerdealer.domain.PokerInteractionPhase.RELEASE &&
                        result.interaction.operation == com.code2hack.pokerdealer.domain.PokerOperation.TAP
                    ) {
                        lifecycleScope.launch {
                            userInputController.selectFocused()
                            screenState.value = navigation.snapshot(cardTextByLocator, currentRequestProjections())
                        }
                    }
                    result.composerDeletion?.let { deletion ->
                        lifecycleScope.launch {
                            composerController.requestDeletion(deletion)
                            screenState.value = navigation.snapshot(cardTextByLocator, currentRequestProjections())
                        }
                    }
                },
            ),
        )
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    PokerCardReader(screenState.value)
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
        if (!hasFocus && ::input.isInitialized) input.onFocusLost()
    }

    override fun onDestroy() {
        if (::input.isInitialized) input.onDisconnected()
        super.onDestroy()
    }
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
private fun PokerCardReader(state: PokerScreenState) {
    PokerPilePages(
        metadata = state.metadata,
        cardTextByLocator = state.cardTextByLocator,
        anchorByLocator = state.anchors,
        composerTextByLocator = state.composerTextByLocator,
        requestProjectionsByLocator = state.requestProjectionsByLocator,
        modifier = Modifier.fillMaxSize(),
    )
}

private data class PokerScreenState(
    val metadata: com.code2hack.pokerdealer.domain.PokerPileMetadata,
    val anchors: Map<CodexThreadLocator, com.code2hack.pokerdealer.domain.PokerPileAnchor>,
    val cardTextByLocator: Map<CodexThreadLocator, String>,
    val composerTextByLocator: Map<CodexThreadLocator, String>,
    val requestProjectionsByLocator: Map<CodexThreadLocator, List<com.code2hack.pokerdealer.protocol.UserInputRequestProjection>>,
)

private fun PokerNavigationReducer.snapshot(
    cardTextByLocator: Map<CodexThreadLocator, String>,
    requestProjectionsByLocator: Map<CodexThreadLocator, List<com.code2hack.pokerdealer.protocol.UserInputRequestProjection>> = emptyMap(),
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
    )
}

private fun PokerSnapshotPileMetadata.evidence(): ThreadWorkEvidence = when (workState) {
    "BUSY" -> ThreadWorkEvidence(activeTurn = true, unresolvedRequestCount = 0)
    "ATTENTION_REQUIRED" -> ThreadWorkEvidence(activeTurn = true, unresolvedRequestCount = 1)
    "READY" -> ThreadWorkEvidence(activeTurn = false, unresolvedRequestCount = 0)
    null -> ThreadWorkEvidence(activeTurn = null, unresolvedRequestCount = null)
    else -> error("Unknown snapshot work state: $workState")
}

private fun currentRequestProjections(): Map<CodexThreadLocator, List<com.code2hack.pokerdealer.protocol.UserInputRequestProjection>> =
    PokerComposerBridge.userInputProjections.value.values.groupBy { it.request.thread }

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
