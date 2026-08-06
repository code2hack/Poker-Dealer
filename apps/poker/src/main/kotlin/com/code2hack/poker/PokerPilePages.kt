package com.code2hack.poker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.PokerPileAnchor
import com.code2hack.pokerdealer.domain.PokerPileMetadata
import com.code2hack.pokerdealer.domain.ThreadWorkState
import com.code2hack.pokerdealer.protocol.UserInputRequestProjection

internal data class PokerPilePage(
    val locator: CodexThreadLocator,
    val workState: ThreadWorkState,
    val cardText: String,
    val anchor: PokerPileAnchor? = null,
    val composerText: String? = null,
    val requestProjections: List<UserInputRequestProjection> = emptyList(),
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
) {
    val projection = metadata.toPokerPileRenderProjection(
        cardTextByLocator,
        anchorByLocator,
        composerTextByLocator,
        requestProjectionsByLocator,
    )
    val page = projection.visiblePage ?: return
    val lines = remember(page.locator, page.cardText) { page.cardText.split('\n') }
    val listState: LazyListState = rememberLazyListState()
    val scrollOffset = page.anchor?.scrollOffset?.coerceIn(0, lines.lastIndex.coerceAtLeast(0)) ?: 0

    LaunchedEffect(page.locator, page.cardText, scrollOffset) {
        listState.scrollToItem(scrollOffset)
    }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            itemsIndexed(
                items = lines,
                key = { index, _ -> "${page.locator.hostId}:${page.locator.threadId}:$index" },
            ) { _, line ->
                Text(
                    text = if (line.isEmpty()) " " else line,
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
    }
}
