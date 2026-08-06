package com.code2hack.poker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.PokerPileMetadata
import com.code2hack.pokerdealer.domain.ThreadWorkState

internal data class PokerPileLineEntry(
    val locator: CodexThreadLocator,
    val workState: ThreadWorkState,
    val focused: Boolean,
) {
    val label: String get() = "${locator.hostId}/${locator.threadId}"
}

internal fun PokerPileMetadata.pileLineEntries(): List<PokerPileLineEntry> =
    orderedPiles.mapNotNull { pile ->
        pile.workState?.let { state ->
            PokerPileLineEntry(
                locator = pile.locator,
                workState = state,
                focused = pile.locator == focused,
            )
        }
    }

@Composable
internal fun PokerPileLine(
    metadata: PokerPileMetadata,
    modifier: Modifier = Modifier,
) {
    val entries = metadata.pileLineEntries()
    if (entries.isEmpty()) return

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.forEachIndexed { index, entry ->
            if (index > 0) {
                Text("|", color = Color(0xFF607487))
            }
            Text(
                text = "${if (entry.focused) "▸ " else ""}${entry.label} · ${entry.workState.name}",
                color = if (entry.focused) Color.White else Color(0xFFAFC4D8),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
    }
}
