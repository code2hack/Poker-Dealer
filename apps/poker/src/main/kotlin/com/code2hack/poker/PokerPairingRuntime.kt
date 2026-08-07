package com.code2hack.poker

import com.code2hack.pokerdealer.protocol.POKER_LISTENER_PORT
import com.code2hack.pokerdealer.protocol.PokerPairingConfirmation
import com.code2hack.pokerdealer.protocol.PokerPairingEnrollment
import com.code2hack.pokerdealer.protocol.PokerPairingFailure
import com.code2hack.pokerdealer.protocol.PokerPairingRejected
import com.code2hack.pokerdealer.protocol.PokerPairingState
import com.code2hack.pokerdealer.protocol.PokerPairingWire
import com.code2hack.pokerdealer.protocol.PokerPairingController
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class PokerPairingUiState {
    data object Unpaired : PokerPairingUiState()

    class EnrollmentOpen(
        val host: String,
        val port: Int,
        val displayCode: String,
        val replacement: Boolean,
        val expiresAtMs: Long,
        val failedAttempts: Int = 0,
        val failure: PokerPairingFailure = PokerPairingFailure.NONE,
    ) : PokerPairingUiState() {
        override fun toString(): String =
            "PokerPairingUiState.EnrollmentOpen(host=<redacted>, port=$port, " +
                "displayCode=<redacted>, replacement=$replacement, " +
                "expiresAtMs=<redacted>, failedAttempts=$failedAttempts, failure=$failure)"
    }

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
            PokerPairingState.ENROLLMENT_OPEN -> mutableState.value
        }
    }

    fun publishEnrollment(
        enrollment: PokerPairingEnrollment,
        host: String,
        failedAttempts: Int = 0,
        failure: PokerPairingFailure = PokerPairingFailure.NONE,
    ) {
        mutableState.value = PokerPairingUiState.EnrollmentOpen(
            host = host,
            port = POKER_LISTENER_PORT,
            displayCode = enrollment.displayCode,
            replacement = enrollment.challenge.replacement,
            expiresAtMs = enrollment.challenge.expiresAtMs,
            failedAttempts = failedAttempts,
            failure = failure,
        )
    }

    fun publishFailure(failure: PokerPairingFailure) {
        mutableState.value = PokerPairingUiState.Failed(failure)
    }

    fun clear() {
        mutableState.value = PokerPairingUiState.Unpaired
    }
}

internal class PokerEnrollmentServer(
    private val addressProvider: () -> Inet4Address,
    private val enrollment: PokerPairingEnrollment,
    private val pairing: PokerPairingController,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val onFailure: (PokerPairingFailure, Int) -> Unit,
    private val onComplete: (PokerPairingConfirmation) -> Unit,
    private val port: Int = POKER_LISTENER_PORT,
) {
    private var server: ServerSocket? = null
    private var job: Job? = null

    fun start(): String {
        val address = addressProvider()
        val opened = ServerSocket()
        try {
            opened.reuseAddress = true
            opened.bind(InetSocketAddress(address, port))
            server = opened
            job = scope.launch(Dispatchers.IO) { acceptLoop(opened) }
            return checkNotNull(address.hostAddress)
        } catch (failure: Exception) {
            runCatching { opened.close() }
            throw failure
        }
    }

    fun stop() {
        closeServer()
        job?.cancel()
        job = null
    }

    private fun closeServer() {
        runCatching { server?.close() }
        server = null
    }

    private suspend fun acceptLoop(opened: ServerSocket) {
        while (currentCoroutineContext().isActive && pairing.status.state == PokerPairingState.ENROLLMENT_OPEN) {
            val socket = try {
                opened.accept()
            } catch (_: Exception) {
                return
            }
            val confirmation = socket.use(::handle)
            if (confirmation != null) {
                closeServer()
                onComplete(confirmation)
                return
            }
        }
    }

    private fun handle(socket: Socket): PokerPairingConfirmation? {
        socket.soTimeout = 10_000
        try {
            PokerPairingWire.write(socket.getOutputStream(), PokerPairingWire.challenge(enrollment.challenge))
            val request = PokerPairingWire.read(socket.getInputStream())
                ?: return null
            val response = try {
                PokerPairingWire.decodeResponse(request)
            } catch (_: Exception) {
                PokerPairingWire.write(
                    socket.getOutputStream(),
                    PokerPairingWire.failure(PokerPairingFailure.INVALID_CHALLENGE, pairing.status.failedAttempts),
                )
                return null
            }
            val confirmation = try {
                pairing.acceptEnrollment(response, nowMs())
            } catch (failure: PokerPairingRejected) {
                PokerPairingWire.write(
                    socket.getOutputStream(),
                    PokerPairingWire.failure(failure.reason, failure.failedAttempts),
                )
                onFailure(failure.reason, failure.failedAttempts)
                return null
            }
            runCatching {
                PokerPairingWire.write(
                    socket.getOutputStream(),
                    PokerPairingWire.confirmation(confirmation),
                )
            }
            return confirmation
        } catch (_: Exception) {
            // The peer receives no pairing material from a failed transport.
            return null
        }
    }
}
