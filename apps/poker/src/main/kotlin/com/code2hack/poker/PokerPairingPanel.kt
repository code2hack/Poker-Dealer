package com.code2hack.poker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.code2hack.pokerdealer.protocol.PokerPairingFailure

@Composable
internal fun PokerPairingPanel(state: PokerPairingUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        when (state) {
            PokerPairingUiState.Unpaired -> Text(
                "Waiting for a Bluetooth-paired Dealer",
                color = Color.White,
            )

            is PokerPairingUiState.Failed -> Text(
                text = when (state.failure) {
                    PokerPairingFailure.BLUETOOTH_PERMISSION_REQUIRED ->
                        "Bluetooth permission required"
                    PokerPairingFailure.BLUETOOTH_NOT_BONDED ->
                        "Waiting for a Bluetooth-paired Dealer"
                    PokerPairingFailure.BLUETOOTH_AMBIGUOUS ->
                        "Multiple Poker-Dealer Bluetooth peers found"
                    else -> "Bluetooth bootstrap unavailable: ${state.failure}"
                },
                color = Color(0xFFFFA8A8),
            )

            PokerPairingUiState.Paired -> Text(
                "Bluetooth trust ready",
                color = Color.White,
            )
        }
    }
}
