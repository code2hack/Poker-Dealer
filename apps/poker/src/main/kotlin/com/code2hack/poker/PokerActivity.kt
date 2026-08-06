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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.PokerCardLayout
import com.code2hack.pokerdealer.domain.PokerInputController
import com.code2hack.pokerdealer.domain.PokerNavigationReducer
import com.code2hack.pokerdealer.domain.PokerPileLayout
import com.code2hack.pokerdealer.domain.ThreadPile
import com.code2hack.pokerdealer.domain.ThreadWorkEvidence
import com.code2hack.pokerdealer.domain.ThreadWorkState
import com.code2hack.pokerdealer.testing.LoopbackPokerTransport
import com.code2hack.pokerdealer.testing.MockDeck

class PokerActivity : ComponentActivity() {
    private lateinit var input: PokerAndroidInputAdapter
    private lateinit var screenState: MutableState<PokerScreenState>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val navigation = mockNavigation()
        screenState = mutableStateOf(navigation.snapshot())
        input = PokerAndroidInputAdapter(
            PokerBuiltInInputAdapter(
                controller = PokerInputController(navigation),
                onNavigationChanged = { screenState.value = navigation.snapshot() },
            ),
        )
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    PokerMockCardReader(screenState.value)
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

@Composable
private fun PokerMockCardReader(state: PokerScreenState) {
    val transport = remember { LoopbackPokerTransport() }

    LaunchedEffect(transport) { transport.connect() }

    PokerPilePages(
        metadata = state.metadata,
        cardTextByLocator = MockDeck.cardTextByLocator,
        anchorByLocator = state.anchors,
        modifier = Modifier.fillMaxSize(),
    )
}

private data class PokerScreenState(
    val metadata: com.code2hack.pokerdealer.domain.PokerPileMetadata,
    val anchors: Map<CodexThreadLocator, com.code2hack.pokerdealer.domain.PokerPileAnchor>,
)

private fun PokerNavigationReducer.snapshot(): PokerScreenState = PokerScreenState(
    metadata = metadata(),
    anchors = anchors(),
)

private fun mockNavigation(): PokerNavigationReducer = PokerNavigationReducer(viewportLineCount = 12).also { navigation ->
    MockDeck.pileMetadata.orderedPiles.forEach { pile ->
        val text = MockDeck.cardTextByLocator[pile.locator].orEmpty()
        navigation.attach(
            locator = pile.locator,
            evidence = pile.evidence(),
            atMs = pile.stateChangedAtMs,
            available = pile.available,
            layout = PokerPileLayout(
                cards = listOf(
                    PokerCardLayout(
                        id = "${pile.locator.hostId}:${pile.locator.threadId}:card",
                        collapsedLineCount = (text.count { it == '\n' } + 1).coerceAtLeast(1),
                    ),
                ),
            ),
        )
    }
    MockDeck.pileMetadata.focused?.let(navigation::view)
}

private fun ThreadPile.evidence(): ThreadWorkEvidence = when (workState) {
    ThreadWorkState.BUSY -> ThreadWorkEvidence(activeTurn = true, unresolvedRequestCount = 0)
    ThreadWorkState.ATTENTION_REQUIRED -> ThreadWorkEvidence(activeTurn = true, unresolvedRequestCount = 1)
    ThreadWorkState.READY -> ThreadWorkEvidence(activeTurn = false, unresolvedRequestCount = 0)
    null -> ThreadWorkEvidence(activeTurn = null, unresolvedRequestCount = null)
}
