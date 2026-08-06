package com.code2hack.poker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.code2hack.pokerdealer.protocol.PokerPairingFailure

@Composable
internal fun PokerPairingPanel(
    state: PokerPairingUiState,
    onPair: () -> Unit,
    onReplace: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        when (state) {
            PokerPairingUiState.Unpaired -> {
                Text("Poker is not paired", color = Color.White)
                Button(onClick = onPair) { Text("Pair Dealer") }
            }

            is PokerPairingUiState.Failed -> {
                Text("Pairing unavailable: ${state.failure}", color = Color(0xFFFFA8A8))
                Button(onClick = onPair) { Text("Pair Dealer") }
            }

            is PokerPairingUiState.EnrollmentOpen -> {
                Text(
                    if (state.replacement) "Replace Dealer" else "Pair Dealer",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text("Poker address: ${state.host}:${state.port}", color = Color.White)
                Text("One-time code: ${state.displayCode}", color = Color.White)
                Text(
                    "Attempts used: ${state.failedAttempts}/5" +
                        if (state.failure != PokerPairingFailure.NONE) " · ${state.failure}" else "",
                    color = Color(0xFFFFC38B),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = if (state.replacement) onReplace else onPair) {
                        Text(if (state.replacement) "New replacement code" else "New pairing code")
                    }
                }
            }

            PokerPairingUiState.Paired -> {
                Text("Poker is paired", color = Color.White)
                OutlinedButton(onClick = onReplace) { Text("Replace Dealer") }
            }
        }
    }
}
