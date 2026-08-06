package com.code2hack.dealer

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
import androidx.compose.ui.unit.dp
import com.code2hack.pokerdealer.domain.PokerBindingControl
import com.code2hack.pokerdealer.domain.PokerBindingDevice
import com.code2hack.pokerdealer.domain.PokerBindingDeviceKind
import com.code2hack.pokerdealer.domain.PokerBindingState
import com.code2hack.pokerdealer.domain.PokerOperation

@Composable
internal fun PokerBindingsPanel(
    state: PokerBindingState,
    connected: Boolean,
    onSelectDevice: (PokerBindingDevice) -> Unit,
    onBeginBinding: (PokerOperation) -> Unit,
    onRemoveBinding: (PokerOperation) -> Unit,
    onResetGlassesDefaults: () -> Unit,
    onClearRemote: () -> Unit,
) {
    val selected = state.selectedDevice
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Poker input bindings", style = MaterialTheme.typography.titleMedium)
        Text(
            "Poker ${if (connected) "connected" else "disconnected"} · " +
                "revision ${state.map.revision} · ${state.syncStatus.name.lowercase()}",
            style = MaterialTheme.typography.labelSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.devices.forEach { device ->
                OutlinedButton(
                    onClick = { onSelectDevice(device) },
                    enabled = connected && device != selected,
                ) {
                    Text(deviceLabel(device))
                }
            }
        }
        Text("Selected: ${deviceLabel(selected)}", style = MaterialTheme.typography.labelMedium)
        state.learning?.let {
            Text(
                "Bind ${it.operation.name}: press one control on ${deviceLabel(it.device)}",
                color = MaterialTheme.colorScheme.primary,
            )
        }
        PokerOperation.entries.forEach { operation ->
            val controls = state.map.controls(selected, operation)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    operation.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    controls.joinToString { it.label() }.ifBlank { "Unbound" },
                    modifier = Modifier.weight(2f),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { onBeginBinding(operation) },
                    enabled = selected.kind == PokerBindingDeviceKind.BLUETOOTH_HID &&
                        connected &&
                        state.learning == null,
                ) {
                    Text("Bind")
                }
                OutlinedButton(
                    onClick = { onRemoveBinding(operation) },
                    enabled = connected && controls.isNotEmpty(),
                ) {
                    Text("Remove")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = onResetGlassesDefaults,
                enabled = connected && state.learning == null,
            ) {
                Text("Reset glasses defaults")
            }
            if (selected.kind == PokerBindingDeviceKind.BLUETOOTH_HID) {
                OutlinedButton(
                    onClick = onClearRemote,
                    enabled = connected && state.learning == null,
                ) {
                    Text("Clear remote")
                }
            }
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun deviceLabel(device: PokerBindingDevice): String = when (device.kind) {
    PokerBindingDeviceKind.GLASSES -> "Glasses"
    PokerBindingDeviceKind.BLUETOOTH_HID -> device.descriptor
}

private fun PokerBindingControl.label(): String = when (device.kind) {
    PokerBindingDeviceKind.GLASSES -> gesture?.name.orEmpty()
    PokerBindingDeviceKind.BLUETOOTH_HID -> "keyCode=$keyCode"
}
