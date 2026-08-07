package com.code2hack.dealer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.code2hack.pokerdealer.protocol.PokerPairingState

@Composable
internal fun DealerPokerPairingPanel(
    state: DealerUiState,
    serviceReady: Boolean,
    onOpenBluetoothSettings: () -> Unit,
    onRetry: () -> Unit,
) {
    val trust = state.pokerDiagnostics.pairing
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Poker Bluetooth trust", style = MaterialTheme.typography.titleMedium)
        Text(
            when {
                state.pokerPairingBusy -> "Discovering Bluetooth-paired Poker…"
                trust == PokerPairingState.PAIRED ->
                    "Bluetooth bond trusted · Wi-Fi transport credentials provisioned automatically"
                else ->
                    "RG glasses are not connected as a Bluetooth-paired Poker device. Pair them in Android Bluetooth settings; no Poker–Dealer code or IP address is required."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onOpenBluetoothSettings,
                enabled = serviceReady && !state.pokerPairingBusy,
            ) {
                Text("Open Bluetooth settings")
            }
            OutlinedButton(
                onClick = onRetry,
                enabled = serviceReady && !state.pokerPairingBusy,
            ) {
                Text("Retry bootstrap")
            }
        }
    }
}
