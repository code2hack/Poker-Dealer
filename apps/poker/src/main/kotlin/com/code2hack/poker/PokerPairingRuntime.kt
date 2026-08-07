package com.code2hack.poker

import com.code2hack.pokerdealer.protocol.PokerPairingFailure
import com.code2hack.pokerdealer.protocol.PokerPairingState

sealed class PokerPairingUiState {
    data object Unpaired : PokerPairingUiState()
    data object Paired : PokerPairingUiState()

    class Failed(val failure: PokerPairingFailure) : PokerPairingUiState() {
        override fun toString(): String = "PokerPairingUiState.Failed(failure=$failure)"
    }
}

internal object PokerPairingRuntime {
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow<PokerPairingUiState>(
        PokerPairingUiState.Unpaired,
    )
    val state: kotlinx.coroutines.flow.StateFlow<PokerPairingUiState> = mutableState

    fun publish(status: com.code2hack.pokerdealer.protocol.PokerPairingStatus) {
        mutableState.value = when (status.state) {
            PokerPairingState.PAIRED -> PokerPairingUiState.Paired
            PokerPairingState.UNPAIRED -> if (status.failure == PokerPairingFailure.NONE) {
                PokerPairingUiState.Unpaired
            } else {
                PokerPairingUiState.Failed(status.failure)
            }
            PokerPairingState.ENROLLMENT_OPEN -> PokerPairingUiState.Failed(
                PokerPairingFailure.BOOTSTRAP_INVALID,
            )
        }
    }

    fun publishFailure(failure: PokerPairingFailure) {
        mutableState.value = PokerPairingUiState.Failed(failure)
    }

    fun clear() {
        mutableState.value = PokerPairingUiState.Unpaired
    }
}
