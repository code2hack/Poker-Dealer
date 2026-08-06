package com.code2hack.pokerdealer.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PokerInteractionTest {
    @Test
    fun `glasses defaults map to canonical operations`() {
        val expected = mapOf(
            PokerGlassesGesture.SINGLE_FINGER_SWIPE_FORWARD to PokerOperation.DOWN,
            PokerGlassesGesture.SINGLE_FINGER_SWIPE_BACKWARD to PokerOperation.UP,
            PokerGlassesGesture.DOUBLE_FINGER_SWIPE_FORWARD to PokerOperation.RIGHT,
            PokerGlassesGesture.DOUBLE_FINGER_SWIPE_BACKWARD to PokerOperation.LEFT,
            PokerGlassesGesture.FUNCTION_BUTTON to PokerOperation.FN,
            PokerGlassesGesture.SINGLE_FINGER_TAP to PokerOperation.TAP,
            PokerGlassesGesture.DUAL_FINGER_TAP to PokerOperation.TAPTAP,
        )

        assertEquals(expected, PokerGlassesGesture.entries.associateWith { it.toOperation() })
    }

    @Test
    fun `first source owns interaction until one release`() {
        val reducer = PokerInteractionReducer()
        val owner = PokerInteraction(PokerInputSource.GLASSES, PokerOperation.TAP, PokerInteractionPhase.BEGIN, eventTimeMs = 10)
        val competitor = PokerInteraction(PokerInputSource.REMOTE, PokerOperation.DOWN, PokerInteractionPhase.BEGIN, eventTimeMs = 10)

        assertEquals(owner, reducer.reduce(owner))
        assertNull(reducer.reduce(competitor))
        assertNull(reducer.reduce(competitor.copy(phase = PokerInteractionPhase.RELEASE)))
        assertEquals(owner.copy(phase = PokerInteractionPhase.RELEASE, eventTimeMs = 20, durationMs = 10), reducer.reduce(owner.copy(phase = PokerInteractionPhase.RELEASE, eventTimeMs = 20)))
        assertFalse(reducer.isActive())
        assertEquals(competitor, reducer.reduce(competitor))
    }

    @Test
    fun `cancel clears owner and never emits a release`() {
        val reducer = PokerInteractionReducer()
        val begin = PokerInteraction(PokerInputSource.GLASSES, PokerOperation.FN, PokerInteractionPhase.BEGIN, eventTimeMs = 10)
        reducer.reduce(begin)

        val cancelled = reducer.cancelActive(PokerCancellationReason.ACTION_CANCEL, eventTimeMs = 15)

        assertEquals(
            begin.copy(
                phase = PokerInteractionPhase.CANCEL,
                eventTimeMs = 15,
                durationMs = 5,
                cancellationReason = PokerCancellationReason.ACTION_CANCEL,
            ),
            cancelled,
        )
        assertNull(reducer.reduce(begin.copy(phase = PokerInteractionPhase.RELEASE)))
        assertFalse(reducer.isActive())
    }

    @Test
    fun `updates and release must match the operation captured at begin`() {
        val reducer = PokerInteractionReducer()
        val begin = glassesInteraction(
            PokerGlassesGesture.SINGLE_FINGER_TAP,
            PokerInteractionPhase.BEGIN,
            eventTimeMs = 10,
        )

        reducer.reduce(begin)
        assertNull(reducer.reduce(begin.copy(operation = PokerOperation.TAPTAP, phase = PokerInteractionPhase.UPDATE, eventTimeMs = 11)))
        assertNull(reducer.reduce(begin.copy(operation = PokerOperation.TAPTAP, phase = PokerInteractionPhase.RELEASE, eventTimeMs = 11)))
        assertTrue(reducer.isActive())
        assertEquals(begin.copy(phase = PokerInteractionPhase.RELEASE, eventTimeMs = 12, durationMs = 2), reducer.reduce(begin.copy(phase = PokerInteractionPhase.RELEASE, eventTimeMs = 12)))
    }

    @Test
    fun `events that move backwards on one source are ignored`() {
        val reducer = PokerInteractionReducer()
        val begin = PokerInteraction(PokerInputSource.GLASSES, PokerOperation.TAP, PokerInteractionPhase.BEGIN, eventTimeMs = 20)

        assertEquals(begin, reducer.reduce(begin))
        assertNull(reducer.reduce(begin.copy(phase = PokerInteractionPhase.RELEASE, eventTimeMs = 19)))
        assertTrue(reducer.isActive())
        assertEquals(begin.copy(phase = PokerInteractionPhase.RELEASE, eventTimeMs = 21, durationMs = 1), reducer.reduce(begin.copy(phase = PokerInteractionPhase.RELEASE, eventTimeMs = 21)))
    }
}

class PokerNavigationTest {
    private val locator = CodexThreadLocator("spark", "thread")
    private val secondLocator = CodexThreadLocator("u4090", "thread")

    @Test
    fun `right and left move piles without wrapping`() {
        val reducer = navigation()
        reducer.attach(secondLocator, evidence(false), atMs = 2, layout = oneCard("second"))
        reducer.view(locator)

        assertEquals(PokerNavigationEffect.PILE_MOVED, reducer.apply(PokerOperation.RIGHT))
        assertEquals(secondLocator, reducer.metadata().focused)
        assertEquals(PokerNavigationEffect.NONE, reducer.apply(PokerOperation.RIGHT))
        assertEquals(secondLocator, reducer.metadata().focused)
        assertEquals(PokerNavigationEffect.PILE_MOVED, reducer.apply(PokerOperation.LEFT))
        assertEquals(locator, reducer.metadata().focused)
        assertEquals(PokerNavigationEffect.NONE, reducer.apply(PokerOperation.LEFT))
    }

    @Test
    fun `down scrolls and jumps to next card end while up jumps to previous start`() {
        val reducer = PokerNavigationReducer(viewportLineCount = 3)
        reducer.attach(
            locator,
            evidence(false),
            atMs = 1,
            layout = PokerPileLayout(
                cards = listOf(
                    PokerCardLayout("first", collapsedLineCount = 5),
                    PokerCardLayout("short", collapsedLineCount = 1),
                    PokerCardLayout("last", collapsedLineCount = 5),
                ),
            ),
        )
        reducer.view(locator)

        assertEquals(PokerPileAnchor("last", 2), reducer.anchor(locator))
        assertEquals(PokerNavigationEffect.SCROLLED, reducer.apply(PokerOperation.UP))
        assertEquals(PokerPileAnchor("last", 1), reducer.anchor(locator))
        assertEquals(PokerNavigationEffect.SCROLLED, reducer.apply(PokerOperation.UP))
        assertEquals(PokerPileAnchor("last", 0), reducer.anchor(locator))
        assertEquals(PokerNavigationEffect.CARD_MOVED, reducer.apply(PokerOperation.UP))
        assertEquals(PokerPileAnchor("short", 0), reducer.anchor(locator))
        assertEquals(PokerNavigationEffect.CARD_MOVED, reducer.apply(PokerOperation.UP))
        assertEquals(PokerPileAnchor("first", 0), reducer.anchor(locator))
        assertEquals(PokerNavigationEffect.SCROLLED, reducer.apply(PokerOperation.DOWN))
        assertEquals(PokerPileAnchor("first", 1), reducer.anchor(locator))
        assertEquals(PokerNavigationEffect.SCROLLED, reducer.apply(PokerOperation.DOWN))
        assertEquals(PokerPileAnchor("first", 2), reducer.anchor(locator))
        assertEquals(PokerNavigationEffect.CARD_MOVED, reducer.apply(PokerOperation.DOWN))
        assertEquals(PokerPileAnchor("short", 0), reducer.anchor(locator))
        assertEquals(PokerNavigationEffect.CARD_MOVED, reducer.apply(PokerOperation.DOWN))
        assertEquals(PokerPileAnchor("last", 2), reducer.anchor(locator))
        assertEquals(PokerNavigationEffect.NONE, reducer.apply(PokerOperation.DOWN))
    }

    @Test
    fun `short card counts as both boundaries`() {
        val reducer = PokerNavigationReducer(viewportLineCount = 10)
        reducer.attach(
            locator,
            evidence(false),
            atMs = 1,
            layout = PokerPileLayout(listOf(PokerCardLayout("first", 1), PokerCardLayout("last", 1))),
        )
        reducer.view(locator)

        assertEquals(PokerNavigationEffect.CARD_MOVED, reducer.apply(PokerOperation.UP))
        assertEquals(PokerPileAnchor("first", 0), reducer.anchor(locator))
        assertEquals(PokerNavigationEffect.CARD_MOVED, reducer.apply(PokerOperation.DOWN))
        assertEquals(PokerPileAnchor("last", 0), reducer.anchor(locator))
    }

    @Test
    fun `request panel and composer are entered at the bottom boundary`() {
        val requestLocator = CodexThreadLocator("spark", "request")
        val requestReducer = PokerNavigationReducer(viewportLineCount = 4)
        requestReducer.attach(
            requestLocator,
            evidence(true, requests = 1),
            atMs = 1,
            layout = PokerPileLayout(
                cards = listOf(PokerCardLayout("request-card", 1, requestPanel = PokerRequestPanelLayout("request"))),
            ),
        )
        requestReducer.view(requestLocator)
        assertEquals(PokerNavigationEffect.ENTERED_REQUEST_PANEL, requestReducer.apply(PokerOperation.DOWN))
        assertEquals(
            PokerPileAnchor("request-card", 0, PokerNavigationMode.REQUEST_PANEL, "request"),
            requestReducer.anchor(requestLocator),
        )
        assertEquals(PokerNavigationEffect.EXITED_INPUT, requestReducer.apply(PokerOperation.UP))
        assertEquals(PokerNavigationMode.NAVIGATION, requestReducer.anchor(requestLocator)?.mode)

        val composerReducer = navigation()
        composerReducer.setLayout(locator, PokerPileLayout(listOf(PokerCardLayout("card", 1)), PokerComposerLayout()))
        composerReducer.view(locator)
        assertEquals(PokerNavigationEffect.ENTERED_COMPOSER, composerReducer.apply(PokerOperation.DOWN))
        assertEquals(PokerNavigationMode.COMPOSER, composerReducer.anchor(locator)?.mode)
        assertEquals(PokerNavigationEffect.EXITED_INPUT, composerReducer.apply(PokerOperation.UP))
    }

    @Test
    fun `tap expands complete details, navigation FN is a no-op, and taptap hides`() {
        val reducer = PokerNavigationReducer(viewportLineCount = 2)
        reducer.attach(
            locator,
            evidence(false),
            atMs = 1,
            layout = PokerPileLayout(listOf(PokerCardLayout("card", 2, expandedLineCount = 5))),
        )
        reducer.view(locator)

        assertEquals(PokerNavigationEffect.DETAILS_TOGGLED, reducer.apply(PokerOperation.TAP))
        assertEquals(setOf("card"), reducer.anchor(locator)?.expandedCardIds)
        assertEquals(PokerNavigationEffect.NONE, reducer.apply(PokerOperation.FN))
        assertEquals(PokerNavigationEffect.HID, reducer.apply(PokerOperation.TAPTAP))
        assertFalse(reducer.metadata().hudVisible)
        assertEquals(PokerNavigationEffect.WOKE, reducer.apply(PokerOperation.TAP))
        assertTrue(reducer.metadata().hudVisible)
        assertEquals(setOf("card"), reducer.anchor(locator)?.expandedCardIds)
    }

    @Test
    fun `stale card and input targets reanchor to newest card`() {
        val reducer = PokerNavigationReducer(viewportLineCount = 2)
        reducer.attach(
            locator,
            evidence(false),
            atMs = 1,
            layout = PokerPileLayout(
                cards = listOf(
                    PokerCardLayout("old", 1),
                    PokerCardLayout("new", 1),
                ),
                composer = PokerComposerLayout(),
            ),
        )
        reducer.view(locator)
        assertEquals(PokerNavigationEffect.ENTERED_COMPOSER, reducer.apply(PokerOperation.DOWN))

        reducer.setLayout(locator, PokerPileLayout(listOf(PokerCardLayout("replacement", 3))))

        assertEquals(PokerPileAnchor("replacement", 1), reducer.anchor(locator))
        assertEquals(PokerNavigationMode.NAVIGATION, reducer.anchor(locator)?.mode)
    }

    @Test
    fun `anchors are retained per pile while focus changes`() {
        val reducer = navigation()
        reducer.attach(secondLocator, evidence(false), atMs = 2, layout = oneCard("second", lines = 6))
        reducer.view(locator)
        reducer.apply(PokerOperation.DOWN)
        val firstAnchor = reducer.anchor(locator)

        reducer.apply(PokerOperation.RIGHT)
        reducer.apply(PokerOperation.DOWN)
        reducer.apply(PokerOperation.LEFT)

        assertEquals(locator, reducer.metadata().focused)
        assertEquals(firstAnchor, reducer.anchor(locator))
    }

    @Test
    fun `controller cancels without dispatching navigation`() {
        val navigation = navigation()
        navigation.view(locator)
        val controller = PokerInputController(navigation)
        val begin = glassesInteraction(PokerGlassesGesture.SINGLE_FINGER_TAP, PokerInteractionPhase.BEGIN, eventTimeMs = 10)

        controller.reduce(begin)
        val cancelled = controller.onFocusLost()

        assertEquals(PokerInteractionPhase.CANCEL, cancelled?.interaction?.phase)
        assertEquals(PokerCancellationReason.FOCUS_LOST, cancelled?.interaction?.cancellationReason)
        assertEquals(PokerPileAnchor("card", 0), navigation.anchor(locator))
        assertNull(controller.reduce(begin.copy(phase = PokerInteractionPhase.RELEASE)))
    }

    private fun navigation() = PokerNavigationReducer(viewportLineCount = 3).also {
        it.attach(locator, evidence(false), atMs = 1, layout = oneCard("card"))
    }

    private fun oneCard(id: String, lines: Int = 1) = PokerPileLayout(listOf(PokerCardLayout(id, lines)))

    private fun evidence(active: Boolean, requests: Int = 0) =
        ThreadWorkEvidence(activeTurn = active, unresolvedRequestCount = requests)
}
