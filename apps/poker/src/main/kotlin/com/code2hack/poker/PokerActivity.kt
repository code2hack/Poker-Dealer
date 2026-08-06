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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        navigation = PokerNavigationReducer(viewportLineCount = 12)
        composerController = PokerComposerController(navigation, PokerComposerBridge::sendMutation)
        screenState = mutableStateOf(navigation.snapshot(cardTextByLocator))
        lifecycleScope.launch {
            PokerSnapshotRuntime.snapshot.collect { snapshot ->
                installSnapshot(snapshot)
                PokerComposerBridge.projections.value.values.forEach(composerController::applyProjection)
                screenState.value = navigation.snapshot(cardTextByLocator)
            }
        }
        lifecycleScope.launch {
            PokerComposerBridge.projections.collect { projections ->
                projections.values.forEach(composerController::applyProjection)
                screenState.value = navigation.snapshot(cardTextByLocator)
            }
        }
        lifecycleScope.launch {
            PokerComposerBridge.results.collect { results ->
                results.values.forEach(composerController::applyResult)
                screenState.value = navigation.snapshot(cardTextByLocator)
            }
        }
        input = PokerAndroidInputAdapter(
            PokerBuiltInInputAdapter(
                controller = PokerInputController(navigation),
                onNavigationChanged = { screenState.value = navigation.snapshot(cardTextByLocator) },
                onResult = { result ->
                    result.composerDeletion?.let { deletion ->
                        lifecycleScope.launch {
                            composerController.requestDeletion(deletion)
                            screenState.value = navigation.snapshot(cardTextByLocator)
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

    private fun installSnapshot(snapshot: PokerSnapshot?) {
        val currentLocators = navigation.metadata().orderedPiles
            .plus(navigation.metadata().unknownWorkState)
            .map { it.locator }
        currentLocators.forEach(navigation::detach)

        val piles = snapshot?.piles.orEmpty()
        cardTextByLocator = piles.associate { pile ->
            pile.metadata.locator to pile.cards.joinToString("\n\n") { it.fullText }
        }
        val ordered = snapshot?.projection?.orderedPiles.orEmpty()
        val unknown = snapshot?.projection?.unknownWorkState.orEmpty()
        (ordered + unknown).forEach { metadata ->
            val pile = piles.single { it.metadata.locator == metadata.locator }
            navigation.attach(
                locator = metadata.locator,
                evidence = metadata.evidence(),
                atMs = metadata.stateChangedAtMs,
                available = metadata.available,
                layout = pile.layout(),
            )
        }
        val focus = snapshot?.projection?.focused
        if (snapshot?.projection?.hudVisible == true && focus != null) {
            runCatching { navigation.view(focus) }
        } else {
            navigation.manualHide()
        }
    }
}

@Composable
private fun PokerCardReader(state: PokerScreenState) {
    PokerPilePages(
        metadata = state.metadata,
        cardTextByLocator = state.cardTextByLocator,
        anchorByLocator = state.anchors,
        composerTextByLocator = state.composerTextByLocator,
        modifier = Modifier.fillMaxSize(),
    )
}

private data class PokerScreenState(
    val metadata: com.code2hack.pokerdealer.domain.PokerPileMetadata,
    val anchors: Map<CodexThreadLocator, com.code2hack.pokerdealer.domain.PokerPileAnchor>,
    val cardTextByLocator: Map<CodexThreadLocator, String>,
    val composerTextByLocator: Map<CodexThreadLocator, String>,
)

private fun PokerNavigationReducer.snapshot(
    cardTextByLocator: Map<CodexThreadLocator, String>,
): PokerScreenState {
    val metadata = metadata()
    return PokerScreenState(
        metadata = metadata,
        anchors = anchors(),
        cardTextByLocator = cardTextByLocator,
        composerTextByLocator = metadata.orderedPiles.mapNotNull { pile ->
            layout(pile.locator)?.composer?.draft?.displayText?.let { pile.locator to it }
        }.toMap(),
    )
}

private fun PokerSnapshotPileMetadata.evidence(): ThreadWorkEvidence = when (workState) {
    "BUSY" -> ThreadWorkEvidence(activeTurn = true, unresolvedRequestCount = 0)
    "ATTENTION_REQUIRED" -> ThreadWorkEvidence(activeTurn = true, unresolvedRequestCount = 1)
    "READY" -> ThreadWorkEvidence(activeTurn = false, unresolvedRequestCount = 0)
    null -> ThreadWorkEvidence(activeTurn = null, unresolvedRequestCount = null)
    else -> error("Unknown snapshot work state: $workState")
}

private fun PokerSnapshotPile.layout(): PokerPileLayout = PokerPileLayout(
    cards = cards.map { card ->
        PokerCardLayout(
            id = card.id,
            collapsedLineCount = (card.fullText.count { it == '\n' } + 1).coerceAtLeast(1),
        )
    },
)
