package com.code2hack.dealer

import com.code2hack.pokerdealer.protocol.AuthenticatedPokerPeer
import com.code2hack.pokerdealer.protocol.PokerHotspotEndpoint
import com.code2hack.pokerdealer.protocol.PokerPairingController
import com.code2hack.pokerdealer.protocol.PokerPairingFailure
import com.code2hack.pokerdealer.protocol.PokerPairingRejected
import com.code2hack.pokerdealer.protocol.PokerPairingWire
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class PokerPairingEnrollmentClient(
    private val pairing: PokerPairingController,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
) {
    init {
        require(connectTimeoutMs > 0) { "Pairing connect timeout must be positive" }
    }

    suspend fun enroll(endpoint: PokerHotspotEndpoint, code: String): AuthenticatedPokerPeer =
        withContext(Dispatchers.IO) {
            require(code.trim().length == 6 && code.trim().all { it in '0'..'9' }) {
                "Pairing code must be six digits"
            }
            Socket().use { socket ->
                socket.soTimeout = connectTimeoutMs
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), connectTimeoutMs)
                val challengeMessage = PokerPairingWire.read(socket.getInputStream())
                    ?: throw PokerPairingRejected(PokerPairingFailure.INVALID_CHALLENGE)
                if (challengeMessage.type == FAILURE_TYPE) {
                    throw rejected(PokerPairingWire.decodeFailure(challengeMessage))
                }
                val challenge = PokerPairingWire.decodeChallenge(challengeMessage)
                val response = pairing.respondToEnrollment(
                    challenge = challenge,
                    code = code,
                    nowMs = System.currentTimeMillis(),
                )
                PokerPairingWire.write(socket.getOutputStream(), PokerPairingWire.response(response))
                val confirmationMessage = PokerPairingWire.read(socket.getInputStream())
                    ?: throw PokerPairingRejected(PokerPairingFailure.INVALID_CHALLENGE)
                if (confirmationMessage.type == FAILURE_TYPE) {
                    throw rejected(PokerPairingWire.decodeFailure(confirmationMessage))
                }
                pairing.confirmDealerPairing(PokerPairingWire.decodeConfirmation(confirmationMessage))
            }
        }

    private fun rejected(failure: com.code2hack.pokerdealer.protocol.PokerPairingFailurePayload) =
        PokerPairingRejected(failure.reason, failure.failedAttempts)

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        const val FAILURE_TYPE = "pairing.failure"
    }
}
