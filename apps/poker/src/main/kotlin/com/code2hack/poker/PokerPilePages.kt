package com.code2hack.poker

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardState
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.PokerPileAnchor
import com.code2hack.pokerdealer.domain.PokerPileMetadata
import com.code2hack.pokerdealer.domain.PokerWheelAction
import com.code2hack.pokerdealer.domain.PokerWheelState
import com.code2hack.pokerdealer.domain.ThreadWorkState
import com.code2hack.pokerdealer.protocol.PokerSnapshotPileMetadata
import com.code2hack.pokerdealer.protocol.UserInputRequestProjection

internal data class PokerPilePage(
    val locator: CodexThreadLocator,
    val workState: ThreadWorkState,
    val cardText: String,
    val anchor: PokerPileAnchor? = null,
    val composerText: String? = null,
    val requestProjections: List<UserInputRequestProjection> = emptyList(),
    val cards: List<Card> = emptyList(),
    val available: Boolean = true,
    val hostLabel: String = "",
    val threadLabel: String = "",
    val unreadCount: Int = 0,
)

internal data class PokerPileRenderProjection(
    val orderedPages: List<PokerPilePage>,
    val focusedLocator: CodexThreadLocator?,
) {
    val visiblePage: PokerPilePage?
        get() = orderedPages.firstOrNull { it.locator == focusedLocator }
}

internal fun PokerPileMetadata.toPokerPileRenderProjection(
    cardTextByLocator: Map<CodexThreadLocator, String>,
    anchorByLocator: Map<CodexThreadLocator, PokerPileAnchor> = emptyMap(),
    composerTextByLocator: Map<CodexThreadLocator, String> = emptyMap(),
    requestProjectionsByLocator: Map<CodexThreadLocator, List<UserInputRequestProjection>> = emptyMap(),
    cardsByLocator: Map<CodexThreadLocator, List<Card>> = emptyMap(),
    metadataByLocator: Map<CodexThreadLocator, PokerSnapshotPileMetadata> = emptyMap(),
    unreadCount: Int = 0,
): PokerPileRenderProjection = PokerPileRenderProjection(
    orderedPages = orderedPiles.mapNotNull { pile ->
        pile.workState?.let { state ->
            PokerPilePage(
                locator = pile.locator,
                workState = state,
                cardText = cardTextByLocator[pile.locator].orEmpty(),
                anchor = anchorByLocator[pile.locator],
                composerText = composerTextByLocator[pile.locator],
                requestProjections = requestProjectionsByLocator[pile.locator].orEmpty(),
                cards = cardsByLocator[pile.locator].orEmpty(),
                available = metadataByLocator[pile.locator]?.available ?: true,
                hostLabel = metadataByLocator[pile.locator]?.hostLabel.orEmpty(),
                threadLabel = metadataByLocator[pile.locator]?.threadLabel().orEmpty(),
                unreadCount = unreadCount,
            )
        }
    },
    focusedLocator = focused,
)

@Composable
internal fun PokerPilePages(
    metadata: PokerPileMetadata,
    cardTextByLocator: Map<CodexThreadLocator, String>,
    modifier: Modifier = Modifier,
    anchorByLocator: Map<CodexThreadLocator, PokerPileAnchor> = emptyMap(),
    composerTextByLocator: Map<CodexThreadLocator, String> = emptyMap(),
    requestProjectionsByLocator: Map<CodexThreadLocator, List<UserInputRequestProjection>> = emptyMap(),
    cardsByLocator: Map<CodexThreadLocator, List<Card>> = emptyMap(),
    metadataByLocator: Map<CodexThreadLocator, PokerSnapshotPileMetadata> = emptyMap(),
    unreadCount: Int = 0,
    onCardFinalLineVisible: (CodexThreadLocator, String) -> Unit = { _, _ -> },
    wheelState: PokerWheelState = PokerWheelState(),
) {
    val projection = metadata.toPokerPileRenderProjection(
        cardTextByLocator,
        anchorByLocator,
        composerTextByLocator,
        requestProjectionsByLocator,
        cardsByLocator,
        metadataByLocator,
        unreadCount,
    )
    val page = projection.visiblePage ?: return
    val lines = remember(page.locator, page.cardText, page.cards) { page.renderLines() }
    val listState: LazyListState = rememberLazyListState()
    val scrollOffset = page.anchor?.scrollOffset?.coerceIn(0, lines.lastIndex.coerceAtLeast(0)) ?: 0

    LaunchedEffect(page.locator, lines, scrollOffset) {
        listState.scrollToItem(scrollOffset)
    }

    LaunchedEffect(page.locator, lines) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapTo(mutableSetOf()) { it.index } }
            .collect { visibleIndexes ->
                lines.forEachIndexed { index, line ->
                    if (index in visibleIndexes && line.finalLineForCard != null) {
                        onCardFinalLineVisible(page.locator, line.finalLineForCard)
                    }
                }
            }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
            itemsIndexed(
                items = lines,
                key = { index, line ->
                    "${page.locator.hostId}:${page.locator.threadId}:$index:${line.finalLineForCard.orEmpty()}"
                },
            ) { _, line ->
                Text(
                    text = if (line.text.isEmpty()) " " else line.text,
                    color = Color(0xFFE8EEF4),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            }

            page.composerText?.let { draft ->
                Text(
                    text = "Draft: $draft",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    color = Color(0xFFB7E3C0),
                )
            }

            page.requestProjections.forEach { projection ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                ) {
                Text(
                    "Request ${projection.request.locator.requestId} | ${projection.request.resolution}",
                    color = Color(0xFFFFD18A),
                )
                var controlPosition = 0
                projection.request.questions.forEach { question ->
                    Text(question.header, color = Color(0xFFE8EEF4))
                    Text(question.question, color = Color(0xFFE8EEF4))
                    question.options?.forEach { option ->
                        val selected = projection.buffer.answer(question.id).selectedOption == option.label
                        val highlighted = page.anchor?.let { anchor ->
                            anchor.mode == com.code2hack.pokerdealer.domain.PokerNavigationMode.REQUEST_PANEL &&
                                anchor.inputId == projection.request.panelId &&
                                anchor.cursorPosition == controlPosition
                        } == true
                        Text(
                            "${if (highlighted) "▶" else if (selected) "✓" else "·"} " +
                                "${option.label}: ${option.description}",
                            color = Color(0xFFE8EEF4),
                        )
                        controlPosition++
                    }
                    if (question.options != null && question.isOther) {
                        val answer = projection.buffer.answer(question.id)
                        val highlighted = page.anchor?.let { anchor ->
                            anchor.mode == com.code2hack.pokerdealer.domain.PokerNavigationMode.REQUEST_PANEL &&
                                anchor.inputId == projection.request.panelId &&
                                anchor.cursorPosition == controlPosition
                        } == true
                        Text(
                            "${if (highlighted) "▶" else if (answer.selectedOption == null) "✓" else "·"} " +
                                "Other: ${answer.otherText}",
                            color = Color(0xFFE8EEF4),
                        )
                        controlPosition++
                    } else if (question.options == null) {
                        val highlighted = page.anchor?.let { anchor ->
                            anchor.mode == com.code2hack.pokerdealer.domain.PokerNavigationMode.REQUEST_PANEL &&
                                anchor.inputId == projection.request.panelId &&
                                anchor.cursorPosition == controlPosition
                        } == true
                        Text(
                            "${if (highlighted) "▶ " else ""}Answer: " +
                                projection.buffer.answer(question.id).otherText,
                        )
                        controlPosition++
                    }
                }
                }
            }

            Text(
                "Swipe/drag to scroll · full Codex text retained",
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF101820))
                    .padding(12.dp),
                color = Color(0xFFAFC4D8),
            )
            if (metadata.orderedPiles.size > 1) {
                PokerPileFooter(page)
            }
        }

        if (wheelState.opened) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xEE18232D))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                wheelLabel(wheelState, PokerWheelAction.PHOTO, "Photo")
                Row {
                    wheelLabel(wheelState, PokerWheelAction.MORSE, "Morse")
                    wheelLabel(wheelState, PokerWheelAction.ASR, "ASR")
                }
                wheelLabel(wheelState, PokerWheelAction.PRIMARY, "Primary")
            }
        }
    }
}

private data class PokerRenderLine(
    val text: String,
    val finalLineForCard: String? = null,
)

private fun PokerPilePage.renderLines(): List<PokerRenderLine> {
    if (cards.isEmpty()) {
        return cardText.split('\n').map(::PokerRenderLine)
    }
    return buildList {
        cards.forEachIndexed { index, card ->
            if (index > 0) add(PokerRenderLine(""))
            val cardLines = card.fullText.split('\n')
            cardLines.forEachIndexed { lineIndex, text ->
                add(
                    PokerRenderLine(
                        text = text,
                        finalLineForCard = card.id.takeIf {
                            lineIndex == cardLines.lastIndex && card.isFinalized()
                        },
                    ),
                )
            }
        }
    }
}

private fun Card.isFinalized(): Boolean = contentComplete && state in setOf(
    CardState.COMMITTED,
    CardState.CORRECTED,
    CardState.FAILED,
)

@Composable
private fun PokerPileFooter(page: PokerPilePage) {
    val hostLabel = collapseWhitespace(page.hostLabel).ifBlank { page.locator.hostId }
    val threadLabel = collapseWhitespace(page.threadLabel).ifBlank { page.locator.threadId }
    val leading = buildString {
        if (!page.available) append("🔌·")
        if (page.unreadCount > 0) {
            val noun = if (page.unreadCount == 1) "card" else "cards"
            append("${page.unreadCount} $noun unread·")
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF101820))
            .padding(12.dp),
    ) {
        if (leading.isNotEmpty()) Text(leading, color = Color(0xFFAFC4D8))
        Text(hostLabel, color = Color(0xFFAFC4D8), maxLines = 1, overflow = TextOverflow.Clip)
        Text(":", color = Color(0xFFAFC4D8))
        Text(
            threadLabel,
            modifier = Modifier.weight(1f).basicMarquee(),
            color = Color(0xFFAFC4D8),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

internal fun pokerFooterText(
    available: Boolean,
    unreadCount: Int,
    hostLabel: String,
    threadLabel: String,
): String {
    val host = collapseWhitespace(hostLabel).ifBlank { "unknown" }
    val thread = collapseWhitespace(threadLabel).ifBlank { "unknown" }
    val unread = if (unreadCount > 0) {
        val noun = if (unreadCount == 1) "card" else "cards"
        "$unreadCount $noun unread·"
    } else {
        ""
    }
    return buildString {
        if (!available) append("🔌·")
        append(unread)
        append(host)
        append(':')
        append(thread)
    }
}

private fun PokerSnapshotPileMetadata.threadLabel(): String =
    collapseWhitespace(threadName.orEmpty())
        .ifBlank { collapseWhitespace(threadPreview.orEmpty()) }
        .ifBlank { locator.threadId }

private fun collapseWhitespace(value: String): String = value.trim().replace(Regex("\\s+"), " ")

@Composable
private fun wheelLabel(state: PokerWheelState, action: PokerWheelAction, label: String) {
    val available = state.context.isAvailable(action)
    Text(
        text = if (state.highlightedAction == action) "▶ $label" else label,
        color = when {
            !available -> Color(0xFF65727C)
            state.highlightedAction == action -> Color(0xFFFFD18A)
            else -> Color(0xFFE8EEF4)
        },
        modifier = Modifier.width(96.dp).padding(6.dp),
    )
}
