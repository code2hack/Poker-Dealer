package com.code2hack.pokerdealer.domain

enum class PokerOperation {
    DOWN,
    UP,
    RIGHT,
    LEFT,
    FN,
    TAP,
    TAPTAP,
}

enum class PokerInputSource {
    GLASSES,
    REMOTE,
}

enum class PokerInteractionPhase {
    BEGIN,
    UPDATE,
    RELEASE,
    CANCEL,
}

enum class PokerCancellationReason {
    ACTION_CANCEL,
    FOCUS_LOST,
    DISCONNECTED,
}

enum class PokerGlassesGesture {
    SINGLE_FINGER_SWIPE_FORWARD,
    SINGLE_FINGER_SWIPE_BACKWARD,
    DOUBLE_FINGER_SWIPE_FORWARD,
    DOUBLE_FINGER_SWIPE_BACKWARD,
    FUNCTION_BUTTON,
    SINGLE_FINGER_TAP,
    DUAL_FINGER_TAP,
}

fun PokerGlassesGesture.toOperation(): PokerOperation = when (this) {
    PokerGlassesGesture.SINGLE_FINGER_SWIPE_FORWARD -> PokerOperation.DOWN
    PokerGlassesGesture.SINGLE_FINGER_SWIPE_BACKWARD -> PokerOperation.UP
    PokerGlassesGesture.DOUBLE_FINGER_SWIPE_FORWARD -> PokerOperation.RIGHT
    PokerGlassesGesture.DOUBLE_FINGER_SWIPE_BACKWARD -> PokerOperation.LEFT
    PokerGlassesGesture.FUNCTION_BUTTON -> PokerOperation.FN
    PokerGlassesGesture.SINGLE_FINGER_TAP -> PokerOperation.TAP
    PokerGlassesGesture.DUAL_FINGER_TAP -> PokerOperation.TAPTAP
}

data class PokerInteraction(
    val source: PokerInputSource,
    val operation: PokerOperation,
    val phase: PokerInteractionPhase,
    val cancellationReason: PokerCancellationReason? = null,
)

fun glassesInteraction(
    gesture: PokerGlassesGesture,
    phase: PokerInteractionPhase,
): PokerInteraction = PokerInteraction(
    source = PokerInputSource.GLASSES,
    operation = gesture.toOperation(),
    phase = phase,
)

/** Grants one source exclusive ownership until its interaction ends. */
class PokerInteractionReducer {
    private data class ActiveInteraction(
        val source: PokerInputSource,
        val operation: PokerOperation,
    )

    private var active: ActiveInteraction? = null

    fun reduce(interaction: PokerInteraction): PokerInteraction? {
        val current = active
        return when (interaction.phase) {
            PokerInteractionPhase.BEGIN -> if (current == null) {
                active = ActiveInteraction(interaction.source, interaction.operation)
                interaction.copy(cancellationReason = null)
            } else {
                null
            }

            PokerInteractionPhase.UPDATE -> interaction.takeIf { it.matches(current) }

            PokerInteractionPhase.RELEASE -> interaction.takeIf { it.matches(current) }?.also {
                active = null
            }

            PokerInteractionPhase.CANCEL -> interaction.takeIf { it.matches(current) }?.also {
                active = null
            }
        }
    }

    fun cancelActive(reason: PokerCancellationReason): PokerInteraction? {
        val current = active ?: return null
        active = null
        return PokerInteraction(
            source = current.source,
            operation = current.operation,
            phase = PokerInteractionPhase.CANCEL,
            cancellationReason = reason,
        )
    }

    fun isActive(): Boolean = active != null

    private fun PokerInteraction.matches(active: ActiveInteraction?): Boolean =
        active != null && source == active.source && operation == active.operation
}

enum class PokerNavigationMode {
    NAVIGATION,
    COMPOSER,
    REQUEST_PANEL,
}

data class PokerRequestPanelLayout(
    val id: String,
    val positionCount: Int = 1,
) {
    init {
        require(id.isNotBlank()) { "Request-panel id must not be blank" }
        require(positionCount > 0) { "Request-panel position count must be positive" }
    }
}

data class PokerCardLayout(
    val id: String,
    val collapsedLineCount: Int,
    val expandedLineCount: Int = collapsedLineCount,
    val requestPanel: PokerRequestPanelLayout? = null,
) {
    init {
        require(id.isNotBlank()) { "Card id must not be blank" }
        require(collapsedLineCount > 0) { "Collapsed card line count must be positive" }
        require(expandedLineCount >= collapsedLineCount) {
            "Expanded card line count must not be smaller than collapsed content"
        }
    }

    val expandable: Boolean
        get() = expandedLineCount > collapsedLineCount
}

data class PokerComposerLayout(
    val positionCount: Int = 1,
) {
    init {
        require(positionCount > 0) { "Composer position count must be positive" }
    }
}

data class PokerPileLayout(
    val cards: List<PokerCardLayout>,
    val composer: PokerComposerLayout? = null,
) {
    init {
        require(cards.map(PokerCardLayout::id).toSet().size == cards.size) {
            "Card ids must be unique within a pile"
        }
    }
}

data class PokerPileAnchor(
    val cardId: String,
    val scrollOffset: Int,
    val mode: PokerNavigationMode = PokerNavigationMode.NAVIGATION,
    val inputId: String? = null,
    val cursorPosition: Int = 0,
    val expandedCardIds: Set<String> = emptySet(),
)

enum class PokerNavigationEffect {
    NONE,
    SCROLLED,
    CARD_MOVED,
    PILE_MOVED,
    DETAILS_TOGGLED,
    ENTERED_COMPOSER,
    ENTERED_REQUEST_PANEL,
    EXITED_INPUT,
    HID,
    WOKE,
}

private const val COMPOSER_INPUT_ID = "composer"

/**
 * Keeps focus-independent card/input anchors keyed by the host-qualified pile.
 * Card line counts are already layout-ready; the renderer owns actual text.
 */
class PokerNavigationReducer(
    private val piles: ThreadPileReducer = ThreadPileReducer(),
    private val viewportLineCount: Int = 4,
) {
    private val layouts = mutableMapOf<CodexThreadLocator, PokerPileLayout>()
    private val anchors = mutableMapOf<CodexThreadLocator, PokerPileAnchor>()

    init {
        require(viewportLineCount > 0) { "Viewport line count must be positive" }
    }

    fun attach(
        locator: CodexThreadLocator,
        evidence: ThreadWorkEvidence,
        atMs: Long,
        available: Boolean = true,
        layout: PokerPileLayout = PokerPileLayout(emptyList()),
    ) {
        piles.attach(locator, evidence, atMs, available)
        layouts[locator] = layout
        reanchor(locator)
    }

    fun detach(locator: CodexThreadLocator) {
        piles.detach(locator)
        layouts.remove(locator)
        anchors.remove(locator)
    }

    fun reconcile(
        locator: CodexThreadLocator,
        evidence: ThreadWorkEvidence,
        atMs: Long,
        available: Boolean,
    ) = piles.reconcile(locator, evidence, atMs, available)

    fun transition(locator: CodexThreadLocator, evidence: ThreadWorkEvidence, atMs: Long) =
        piles.transition(locator, evidence, atMs)

    fun turnEnded(locator: CodexThreadLocator, outcome: TurnOutcome, atMs: Long) =
        piles.turnEnded(locator, outcome, atMs)

    fun acceptedPromptOrSteer(locator: CodexThreadLocator, atMs: Long) =
        piles.acceptedPromptOrSteer(locator, atMs)

    fun setAvailable(locator: CodexThreadLocator, available: Boolean) =
        piles.setAvailable(locator, available)

    fun setLayout(locator: CodexThreadLocator, layout: PokerPileLayout) {
        require(locator in layouts || locator in piles.metadata().orderedPiles.map(ThreadPile::locator)) {
            "Thread is not attached"
        }
        layouts[locator] = layout
        reanchor(locator)
    }

    fun layout(locator: CodexThreadLocator): PokerPileLayout? = layouts[locator]

    fun anchor(locator: CodexThreadLocator): PokerPileAnchor? = anchors[locator]

    fun metadata(): PokerPileMetadata {
        piles.metadata().focused?.let(::reanchor)
        return piles.metadata()
    }

    fun view(locator: CodexThreadLocator) {
        piles.view(locator)
        reanchor(locator)
    }

    fun manualHide() = piles.manualHide()

    fun manualWake() {
        piles.manualWake()
        piles.metadata().focused?.let(::reanchor)
    }

    fun moveFocus(direction: PileDirection): Boolean {
        val moved = piles.moveFocus(direction)
        if (moved) piles.metadata().focused?.let(::reanchor)
        return moved
    }

    fun readyGatedActionAllowed(locator: CodexThreadLocator): Boolean =
        piles.readyGatedActionAllowed(locator)

    fun apply(operation: PokerOperation): PokerNavigationEffect {
        val metadata = piles.metadata()
        if (operation == PokerOperation.TAP && !metadata.hudVisible) {
            piles.manualWake()
            val focused = piles.metadata().focused ?: return PokerNavigationEffect.NONE
            reanchor(focused)
            return PokerNavigationEffect.WOKE
        }
        if (!metadata.hudVisible) return PokerNavigationEffect.NONE

        return when (operation) {
            PokerOperation.DOWN -> moveVertically(1)
            PokerOperation.UP -> moveVertically(-1)
            PokerOperation.RIGHT -> movePile(PileDirection.RIGHT)
            PokerOperation.LEFT -> movePile(PileDirection.LEFT)
            PokerOperation.FN -> PokerNavigationEffect.NONE
            PokerOperation.TAP -> toggleDetails()
            PokerOperation.TAPTAP -> {
                piles.manualHide()
                PokerNavigationEffect.HID
            }
        }
    }

    private fun movePile(direction: PileDirection): PokerNavigationEffect =
        if (moveFocus(direction)) PokerNavigationEffect.PILE_MOVED else PokerNavigationEffect.NONE

    private fun moveVertically(delta: Int): PokerNavigationEffect {
        val locator = piles.metadata().focused ?: return PokerNavigationEffect.NONE
        val layout = layouts[locator] ?: return PokerNavigationEffect.NONE
        val current = anchors[locator] ?: return PokerNavigationEffect.NONE
        val cardIndex = layout.cards.indexOfFirst { it.id == current.cardId }
        if (cardIndex < 0) {
            reanchor(locator)
            return PokerNavigationEffect.NONE
        }

        return when (current.mode) {
            PokerNavigationMode.NAVIGATION -> moveCardContent(locator, layout, current, cardIndex, delta)
            PokerNavigationMode.COMPOSER -> moveComposer(locator, layout, current, delta)
            PokerNavigationMode.REQUEST_PANEL -> moveRequestPanel(locator, layout, current, cardIndex, delta)
        }
    }

    private fun moveCardContent(
        locator: CodexThreadLocator,
        layout: PokerPileLayout,
        current: PokerPileAnchor,
        cardIndex: Int,
        delta: Int,
    ): PokerNavigationEffect {
        val card = layout.cards[cardIndex]
        val end = maxScroll(card, current.expandedCardIds)
        if (delta > 0) {
            if (current.scrollOffset < end) {
                anchors[locator] = current.copy(scrollOffset = current.scrollOffset + 1)
                return PokerNavigationEffect.SCROLLED
            }
            card.requestPanel?.let { panel ->
                anchors[locator] = current.copy(
                    mode = PokerNavigationMode.REQUEST_PANEL,
                    inputId = panel.id,
                    cursorPosition = 0,
                )
                return PokerNavigationEffect.ENTERED_REQUEST_PANEL
            }
            if (cardIndex + 1 < layout.cards.size) {
                moveToCardEnd(locator, layout, current, cardIndex + 1)
                return PokerNavigationEffect.CARD_MOVED
            }
            layout.composer?.let { composer ->
                anchors[locator] = current.copy(
                    mode = PokerNavigationMode.COMPOSER,
                    inputId = COMPOSER_INPUT_ID,
                    cursorPosition = 0.coerceAtMost(composer.positionCount - 1),
                )
                return PokerNavigationEffect.ENTERED_COMPOSER
            }
        } else {
            if (current.scrollOffset > 0) {
                anchors[locator] = current.copy(scrollOffset = current.scrollOffset - 1)
                return PokerNavigationEffect.SCROLLED
            }
            if (cardIndex > 0) {
                anchors[locator] = current.copy(
                    cardId = layout.cards[cardIndex - 1].id,
                    scrollOffset = 0,
                    mode = PokerNavigationMode.NAVIGATION,
                    inputId = null,
                    cursorPosition = 0,
                )
                return PokerNavigationEffect.CARD_MOVED
            }
        }
        return PokerNavigationEffect.NONE
    }

    private fun moveComposer(
        locator: CodexThreadLocator,
        layout: PokerPileLayout,
        current: PokerPileAnchor,
        delta: Int,
    ): PokerNavigationEffect {
        val composer = layout.composer ?: return reanchorWithEffect(locator)
        return if (delta > 0 && current.cursorPosition + 1 < composer.positionCount) {
            anchors[locator] = current.copy(cursorPosition = current.cursorPosition + 1)
            PokerNavigationEffect.SCROLLED
        } else if (delta < 0 && current.cursorPosition > 0) {
            anchors[locator] = current.copy(cursorPosition = current.cursorPosition - 1)
            PokerNavigationEffect.SCROLLED
        } else if (delta < 0) {
            exitInputToCardEnd(locator, layout, current)
            PokerNavigationEffect.EXITED_INPUT
        } else {
            PokerNavigationEffect.NONE
        }
    }

    private fun moveRequestPanel(
        locator: CodexThreadLocator,
        layout: PokerPileLayout,
        current: PokerPileAnchor,
        cardIndex: Int,
        delta: Int,
    ): PokerNavigationEffect {
        val panel = layout.cards[cardIndex].requestPanel
            ?.takeIf { it.id == current.inputId }
            ?: return reanchorWithEffect(locator)
        return if (delta > 0 && current.cursorPosition + 1 < panel.positionCount) {
            anchors[locator] = current.copy(cursorPosition = current.cursorPosition + 1)
            PokerNavigationEffect.SCROLLED
        } else if (delta < 0 && current.cursorPosition > 0) {
            anchors[locator] = current.copy(cursorPosition = current.cursorPosition - 1)
            PokerNavigationEffect.SCROLLED
        } else if (delta < 0) {
            exitInputToCardEnd(locator, layout, current)
            PokerNavigationEffect.EXITED_INPUT
        } else if (cardIndex + 1 < layout.cards.size) {
            moveToCardEnd(locator, layout, current, cardIndex + 1)
            PokerNavigationEffect.CARD_MOVED
        } else {
            PokerNavigationEffect.NONE
        }
    }

    private fun toggleDetails(): PokerNavigationEffect {
        val locator = piles.metadata().focused ?: return PokerNavigationEffect.NONE
        val layout = layouts[locator] ?: return PokerNavigationEffect.NONE
        val current = anchors[locator] ?: return PokerNavigationEffect.NONE
        if (current.mode != PokerNavigationMode.NAVIGATION) return PokerNavigationEffect.NONE
        val card = layout.cards.firstOrNull { it.id == current.cardId }
            ?: return reanchorWithEffect(locator)
        if (!card.expandable) return PokerNavigationEffect.NONE
        val expanded = if (card.id in current.expandedCardIds) {
            current.expandedCardIds - card.id
        } else {
            current.expandedCardIds + card.id
        }
        anchors[locator] = current.copy(
            scrollOffset = current.scrollOffset.coerceAtMost(maxScroll(card, expanded)),
            expandedCardIds = expanded,
        )
        return PokerNavigationEffect.DETAILS_TOGGLED
    }

    private fun moveToCardEnd(
        locator: CodexThreadLocator,
        layout: PokerPileLayout,
        current: PokerPileAnchor,
        index: Int,
    ) {
        val card = layout.cards[index]
        anchors[locator] = current.copy(
            cardId = card.id,
            scrollOffset = maxScroll(card, current.expandedCardIds),
            mode = PokerNavigationMode.NAVIGATION,
            inputId = null,
            cursorPosition = 0,
        )
    }

    private fun exitInputToCardEnd(
        locator: CodexThreadLocator,
        layout: PokerPileLayout,
        current: PokerPileAnchor,
    ) {
        val card = layout.cards.firstOrNull { it.id == current.cardId } ?: return reanchor(locator)
        anchors[locator] = current.copy(
            scrollOffset = maxScroll(card, current.expandedCardIds),
            mode = PokerNavigationMode.NAVIGATION,
            inputId = null,
            cursorPosition = 0,
        )
    }

    private fun reanchorWithEffect(locator: CodexThreadLocator): PokerNavigationEffect {
        reanchor(locator)
        return PokerNavigationEffect.NONE
    }

    private fun reanchor(locator: CodexThreadLocator) {
        val layout = layouts[locator] ?: return
        val current = anchors[locator]
        val cardIds = layout.cards.map(PokerCardLayout::id).toSet()
        val expanded = current?.expandedCardIds.orEmpty().intersect(cardIds)
        val currentCard = current?.cardId?.let { id -> layout.cards.firstOrNull { it.id == id } }
        val fallback = layout.cards.lastOrNull()
        if (currentCard == null && fallback == null) {
            anchors.remove(locator)
            return
        }
        val card = currentCard ?: fallback!!
        val validInput = when (current?.mode) {
            PokerNavigationMode.COMPOSER ->
                layout.composer != null && card.id == layout.cards.lastOrNull()?.id &&
                    current.inputId == COMPOSER_INPUT_ID
            PokerNavigationMode.REQUEST_PANEL ->
                card.requestPanel?.id == current.inputId
            else -> true
        }
        if (current != null && validInput && currentCard != null) {
            val maxCursor = when (current.mode) {
                PokerNavigationMode.COMPOSER -> layout.composer!!.positionCount - 1
                PokerNavigationMode.REQUEST_PANEL -> card.requestPanel!!.positionCount - 1
                PokerNavigationMode.NAVIGATION -> 0
            }
            anchors[locator] = current.copy(
                scrollOffset = current.scrollOffset.coerceIn(0, maxScroll(card, expanded)),
                inputId = current.inputId.takeIf { current.mode != PokerNavigationMode.NAVIGATION },
                cursorPosition = current.cursorPosition.coerceIn(0, maxCursor),
                expandedCardIds = expanded,
            )
        } else {
            anchors[locator] = PokerPileAnchor(
                cardId = card.id,
                scrollOffset = maxScroll(card, expanded),
                expandedCardIds = expanded,
            )
        }
    }

    private fun maxScroll(card: PokerCardLayout, expanded: Set<String>): Int =
        ((if (card.id in expanded) card.expandedLineCount else card.collapsedLineCount) - viewportLineCount)
            .coerceAtLeast(0)
}

class PokerInputController(
    private val navigation: PokerNavigationReducer,
    private val interactions: PokerInteractionReducer = PokerInteractionReducer(),
) {
    data class Result(
        val interaction: PokerInteraction,
        val navigationEffect: PokerNavigationEffect,
    )

    fun reduce(interaction: PokerInteraction): Result? {
        val accepted = interactions.reduce(interaction) ?: return null
        val effect = if (accepted.phase == PokerInteractionPhase.RELEASE) {
            navigation.apply(accepted.operation)
        } else {
            PokerNavigationEffect.NONE
        }
        return Result(accepted, effect)
    }

    fun cancel(reason: PokerCancellationReason): Result? =
        interactions.cancelActive(reason)?.let { Result(it, PokerNavigationEffect.NONE) }

    fun onFocusLost(): Result? = cancel(PokerCancellationReason.FOCUS_LOST)

    fun onDisconnected(): Result? = cancel(PokerCancellationReason.DISCONNECTED)
}
