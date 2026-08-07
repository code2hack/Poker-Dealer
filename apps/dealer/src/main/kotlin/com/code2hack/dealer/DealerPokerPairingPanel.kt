package com.code2hack.dealer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.code2hack.pokerdealer.protocol.PokerPairingState

@Composable
internal fun DealerPokerPairingPanel(
    state: DealerUiState,
    serviceReady: Boolean,
    onBegin: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val pairing = state.pokerDiagnostics.pairing
    val valid = code.length == 6 && code.all { it in '0'..'9' }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Poker pairing", style = MaterialTheme.typography.titleMedium)
        Text(
            "On Poker, tap Pair Dealer or Replace Dealer, then enter only its one-time code here. Dealer discovers Poker automatically on the local Wi-Fi network.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("State: $pairing")
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(6) },
            enabled = !state.pokerPairingBusy,
            label = { Text("Poker one-time code") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onBegin(code) },
            enabled = serviceReady && valid && !state.pokerPairingBusy,
        ) {
            Text(
                when {
                    state.pokerPairingBusy -> "Pairing…"
                    pairing == PokerPairingState.PAIRED -> "Replace Dealer"
                    else -> "Pair Dealer"
                },
            )
        }
    }
}
