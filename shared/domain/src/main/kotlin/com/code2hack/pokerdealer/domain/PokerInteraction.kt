package com.code2hack.pokerdealer.domain

import java.util.UUID

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
    val operation: PokerOperation?,
    val phase: PokerInteractionPhase,
    val eventTimeMs: Long,
    val durationMs: Long = 0L,
    val cancellationReason: PokerCancellationReason? = null,
) {
    init {
        require(eventTimeMs >= 0) { "Interaction event time must be non-negative" }
        require(durationMs >= 0) { "Interaction duration must be non-negative" }
    }
}

fun glassesInteraction(
    gesture: PokerGlassesGesture,
    phase: PokerInteractionPhase,
    eventTimeMs: Long,
): PokerInteraction = PokerInteraction(
    source = PokerInputSource.GLASSES,
    operation = gesture.toOperation(),
    phase = phase,
    eventTimeMs = eventTimeMs,
)

/** Grants one source exclusive ownership until its interaction ends. */
class PokerInteractionReducer {
    private data class ActiveInteraction(
        val source: PokerInputSource,
        val operation: PokerOperation?,
        val startedAtMs: Long,
        val lastEventTimeMs: Long,
    )

    private var active: ActiveInteraction? = null
    private val lastEventTimeBySource = mutableMapOf<PokerInputSource, Long>()

    fun reduce(interaction: PokerInteraction): PokerInteraction? {
        val current = active
        return when (interaction.phase) {
            PokerInteractionPhase.BEGIN -> if (current == null && isMonotonic(interaction)) {
                active = ActiveInteraction(
                    source = interaction.source,
                    operation = interaction.operation,
                    startedAtMs = interaction.eventTimeMs,
                    lastEventTimeMs = interaction.eventTimeMs,
                )
                lastEventTimeBySource[interaction.source] = interaction.eventTimeMs
                interaction.copy(durationMs = 0L, cancellationReason = null)
            } else {
                null
            }

            PokerInteractionPhase.UPDATE -> interaction.takeIf { it.matches(current) && isMonotonic(it) }
                ?.also { updateTimestamp(it) }
                ?.withDuration(current)

            PokerInteractionPhase.RELEASE -> interaction.takeIf { it.matches(current) && isMonotonic(it) }
                ?.also { updateTimestamp(it); active = null }
                ?.withDuration(current)

            PokerInteractionPhase.CANCEL -> interaction.takeIf { it.matches(current) && isMonotonic(it) }
                ?.also { updateTimestamp(it); active = null }
                ?.withDuration(current)
        }
    }

    fun cancelActive(reason: PokerCancellationReason, eventTimeMs: Long? = null): PokerInteraction? {
        val current = active ?: return null
        val timestamp = maxOf(current.lastEventTimeMs, eventTimeMs ?: current.lastEventTimeMs)
        active = null
        lastEventTimeBySource[current.source] = timestamp
        return PokerInteraction(
            source = current.source,
            operation = current.operation,
            phase = PokerInteractionPhase.CANCEL,
            eventTimeMs = timestamp,
            durationMs = timestamp - current.startedAtMs,
            cancellationReason = reason,
        )
    }

    fun isActive(): Boolean = active != null

    private fun isMonotonic(interaction: PokerInteraction): Boolean {
        val current = active
        return interaction.eventTimeMs >= (lastEventTimeBySource[interaction.source] ?: 0L) &&
            (current == null || interaction.eventTimeMs >= current.lastEventTimeMs)
    }

    private fun updateTimestamp(interaction: PokerInteraction) {
        lastEventTimeBySource[interaction.source] = interaction.eventTimeMs
        active = active?.let { current ->
            current.copy(
                operation = interaction.operation ?: current.operation,
                lastEventTimeMs = interaction.eventTimeMs,
            )
        }
    }

    private fun PokerInteraction.withDuration(active: ActiveInteraction?): PokerInteraction =
        copy(durationMs = eventTimeMs - (active?.startedAtMs ?: eventTimeMs))

    private fun PokerInteraction.matches(active: ActiveInteraction?): Boolean =
        active != null && source == active.source &&
            (active.operation == null || operation == active.operation)
}

enum class PokerNavigationMode {
    NAVIGATION,
    COMPOSER,
    REQUEST_PANEL,
}

data class PokerRequestQuestionLayout(
    val id: String,
    val controlCount: Int,
    val optionLabels: List<String> = emptyList(),
    val hasOther: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "Request question id must not be blank" }
        require(controlCount > 0) { "Request question control count must be positive" }
        require(optionLabels.distinct().size == optionLabels.size) {
            "Request question option labels must be unique"
        }
    }
}

data class PokerRequestControl(
    val questionId: String,
    val optionLabel: String? = null,
    val isOther: Boolean = false,
)

data class PokerRequestPanelLayout(
    val id: String,
    val positionCount: Int = 1,
    val questions: List<PokerRequestQuestionLayout> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "Request-panel id must not be blank" }
        require(positionCount > 0) { "Request-panel position count must be positive" }
        require(questions.map(PokerRequestQuestionLayout::id).distinct().size == questions.size) {
            "Request question ids must be unique"
        }
        if (questions.isNotEmpty()) {
            require(questions.sumOf(PokerRequestQuestionLayout::controlCount) == positionCount) {
                "Request question controls must cover the request panel"
            }
        }
    }

    fun questionAt(position: Int): PokerRequestQuestionLayout? {
        require(position in 0 until positionCount) { "Request-panel position is outside the panel" }
        var start = 0
        return questions.firstOrNull { question ->
            val contains = position in start until (start + question.controlCount)
            start += question.controlCount
            contains
        }
    }

    fun controlAt(position: Int): PokerRequestControl? {
        require(position in 0 until positionCount) { "Request-panel position is outside the panel" }
        var start = 0
        return questions.firstNotNullOfOrNull { question ->
            val local = position - start
            start += question.controlCount
            if (local !in 0 until question.controlCount) {
                null
            } else {
                PokerRequestControl(
                    questionId = question.id,
                    optionLabel = question.optionLabels.getOrNull(local),
                    isOther = question.hasOther && local == question.controlCount - 1,
                )
            }
        }
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
    val draft: ComposerDraft? = null,
    val controlGeneration: Long = 0L,
    val connectionEpoch: Long = 0L,
    val modeSession: String = "",
) {
    init {
        require(positionCount > 0) { "Composer position count must be positive" }
        require(controlGeneration >= 0) { "Composer control generation must not be negative" }
        require(connectionEpoch >= 0) { "Composer connection epoch must not be negative" }
    }

    val cursorPositionCount: Int
        get() = draft?.cursorCount ?: positionCount

    val endCursorPosition: Int
        get() = cursorPositionCount - 1
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
    COMPOSER_DELETE_REQUESTED,
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
        val previousComposer = layouts[locator]?.composer
        val currentAnchor = anchors[locator]
        layouts[locator] = layout
        val newComposer = layout.composer
        if (
            currentAnchor?.mode == PokerNavigationMode.COMPOSER &&
            previousComposer?.draft != newComposer?.draft &&
            newComposer?.draft != null
        ) {
            anchors[locator] = currentAnchor.copy(cursorPosition = newComposer.endCursorPosition)
        }
        reanchor(locator)
    }

    fun layout(locator: CodexThreadLocator): PokerPileLayout? = layouts[locator]

    fun anchor(locator: CodexThreadLocator): PokerPileAnchor? = anchors[locator]

    fun setComposerCursor(locator: CodexThreadLocator, cursorPosition: Int) {
        val current = anchors[locator]?.takeIf { it.mode == PokerNavigationMode.COMPOSER } ?: return
        val composer = layouts[locator]?.composer ?: return
        anchors[locator] = current.copy(
            cursorPosition = cursorPosition.coerceIn(0, composer.endCursorPosition),
        )
    }

    fun anchors(): Map<CodexThreadLocator, PokerPileAnchor> = anchors.toMap()

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
                    cursorPosition = composer.endCursorPosition,
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
        composer.draft?.let { draft ->
            val target = if (delta > 0) {
                draft.nextWordStart(current.cursorPosition)
            } else {
                draft.previousWordStart(current.cursorPosition)
            }
            if (target != current.cursorPosition) {
                anchors[locator] = current.copy(cursorPosition = target)
                return PokerNavigationEffect.SCROLLED
            }
            if (delta < 0 && current.cursorPosition == 0) {
                exitInputToCardEnd(locator, layout, current)
                return PokerNavigationEffect.EXITED_INPUT
            }
            return PokerNavigationEffect.NONE
        }
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
                PokerNavigationMode.COMPOSER -> layout.composer!!.cursorPositionCount - 1
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

data class ComposerDeletionRequest(
    val locator: CodexThreadLocator,
    val draftRevision: Long,
    val start: Int,
    val endExclusive: Int,
    val target: ComposerEditTarget? = null,
)

private const val SHORT_FN_MAX_DURATION_MS = 500L

fun PokerNavigationReducer.shortComposerDeletion(
    locator: CodexThreadLocator,
): ComposerDeletionRequest? {
    val anchor = anchor(locator)?.takeIf { it.mode == PokerNavigationMode.COMPOSER } ?: return null
    val composer = layout(locator)?.composer ?: return null
    val draft = composer.draft ?: return null
    val deletion = draft.deleteThroughNextWord(anchor.cursorPosition) ?: return null
    if (deletion.containsPhoto) return null
    val target = composer.modeSession.takeIf(String::isNotBlank)?.let { modeSession ->
        ComposerEditTarget(
            locator = locator,
            draftRevision = draft.revision,
            cursorPosition = anchor.cursorPosition,
            controlGeneration = composer.controlGeneration,
            connectionEpoch = composer.connectionEpoch,
            modeSession = modeSession,
            operationId = UUID.randomUUID().toString(),
        )
    }
    return ComposerDeletionRequest(locator, draft.revision, deletion.start, deletion.endExclusive, target)
}

class PokerInputController(
    private val navigation: PokerNavigationReducer,
    private val interactions: PokerInteractionReducer = PokerInteractionReducer(),
) {
    data class Result(
        val interaction: PokerInteraction,
        val navigationEffect: PokerNavigationEffect,
        val composerDeletion: ComposerDeletionRequest? = null,
    )

    fun reduce(interaction: PokerInteraction): Result? {
        val accepted = interactions.reduce(interaction) ?: return null
        val deletion = if (
            accepted.phase == PokerInteractionPhase.RELEASE &&
            accepted.operation == PokerOperation.FN &&
            accepted.durationMs < SHORT_FN_MAX_DURATION_MS
        ) {
            navigation.metadata().focused?.let(navigation::shortComposerDeletion)
        } else {
            null
        }
        val effect = if (deletion != null) {
            PokerNavigationEffect.COMPOSER_DELETE_REQUESTED
        } else if (accepted.phase == PokerInteractionPhase.RELEASE) {
            accepted.operation?.let(navigation::apply) ?: PokerNavigationEffect.NONE
        } else {
            PokerNavigationEffect.NONE
        }
        return Result(accepted, effect, deletion)
    }

    fun cancel(reason: PokerCancellationReason, eventTimeMs: Long? = null): Result? =
        interactions.cancelActive(reason, eventTimeMs)?.let { Result(it, PokerNavigationEffect.NONE) }

    fun onFocusLost(eventTimeMs: Long? = null): Result? = cancel(PokerCancellationReason.FOCUS_LOST, eventTimeMs)

    fun onDisconnected(eventTimeMs: Long? = null): Result? = cancel(PokerCancellationReason.DISCONNECTED, eventTimeMs)
}
