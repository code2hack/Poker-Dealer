package com.code2hack.dealer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.code2hack.pokerdealer.protocol.POKER_FONT_SCALE_MAX_PERCENT
import com.code2hack.pokerdealer.protocol.POKER_FONT_SCALE_MIN_PERCENT
import com.code2hack.pokerdealer.protocol.POKER_FONT_SCALE_STEP_PERCENT
import com.code2hack.pokerdealer.protocol.PokerFontScaleState
import kotlin.math.roundToInt

@Composable
internal fun DealerPresentationPanel(
    dealerScale: PokerFontScaleState,
    pokerScale: PokerFontScaleState,
    onDealerScale: (Int) -> Unit,
    onPokerScale: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Presentation", style = MaterialTheme.typography.titleMedium)
        FontScaleSlider("Dealer text scale", dealerScale, onDealerScale)
        FontScaleSlider("Poker text scale", pokerScale, onPokerScale)
    }
}

@Composable
private fun FontScaleSlider(
    label: String,
    scale: PokerFontScaleState,
    onChange: (Int) -> Unit,
) {
    Text("$label: ${scale.percent}%")
    Slider(
        value = scale.factor,
        onValueChange = { onChange(percentForSlider(it)) },
        valueRange = POKER_FONT_SCALE_MIN_PERCENT / 100f..POKER_FONT_SCALE_MAX_PERCENT / 100f,
        steps = (POKER_FONT_SCALE_MAX_PERCENT - POKER_FONT_SCALE_MIN_PERCENT) /
            POKER_FONT_SCALE_STEP_PERCENT - 1,
        modifier = Modifier.semantics {
            contentDescription = label
            stateDescription = "${scale.percent} percent"
        },
    )
}

private fun percentForSlider(value: Float): Int {
    val raw = (value * 100).roundToInt()
        .coerceIn(POKER_FONT_SCALE_MIN_PERCENT, POKER_FONT_SCALE_MAX_PERCENT)
    return POKER_FONT_SCALE_MIN_PERCENT +
        ((raw - POKER_FONT_SCALE_MIN_PERCENT + POKER_FONT_SCALE_STEP_PERCENT / 2) /
            POKER_FONT_SCALE_STEP_PERCENT) * POKER_FONT_SCALE_STEP_PERCENT
}

@Composable
internal fun PokerDiagnosticsPanel(state: DealerPokerDiagnostics) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("Poker diagnostics", style = MaterialTheme.typography.titleMedium)
        Text("Pairing: ${state.pairing}")
        Text(
            "Connection: ${state.connection}" +
                state.connectionEpoch?.let { " · epoch $it" }.orEmpty(),
        )
        Text("Capabilities: ${state.capabilities.sorted().joinToString().ifBlank { "none" }}")
        Text(
            "Snapshot revision: ${state.snapshotRevision ?: "unknown"} · " +
                "delta revision: ${state.deltaRevision ?: "unknown"}",
        )
        Text("Bindings: ${state.bindingSync}")
        Text("Unread: ${state.unreadCount ?: "unknown"} · Wake: ${state.wakeCapability}")
        Text(
            "Font: ${state.fontSync} · revision ${state.fontRevision} · " +
                "ack ${state.acknowledgedFontRevision ?: "none"}",
        )
        if (state.lastFailure != PokerDiagnosticFailure.NONE) {
            Text("Last failure: ${state.lastFailure}", color = MaterialTheme.colorScheme.error)
        }
    }
}
