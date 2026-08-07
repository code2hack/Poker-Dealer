package com.code2hack.dealer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.code2hack.pokerdealer.protocol.POKER_BOND_BOOTSTRAP_SERVICE_UUID
import com.code2hack.pokerdealer.protocol.PokerBondBootstrapProtocol
import com.code2hack.pokerdealer.protocol.PokerBondBootstrapWire
import com.code2hack.pokerdealer.protocol.PokerPairingController
import com.code2hack.pokerdealer.protocol.PokerPairingFailure
import java.io.Closeable
import java.io.EOFException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class DealerPokerBluetoothBootstrap(
    context: Context,
    private val identity: AndroidKeystorePairingIdentity,
    private val pairing: PokerPairingController,
    private val scope: CoroutineScope,
    private val onBusy: (Boolean) -> Unit,
    private val onProvisioned: () -> Unit,
    private val onFailure: (PokerPairingFailure) -> Unit,
) : Closeable {
    private val context = context.applicationContext
    private val lock = Any()
    private var job: Job? = null

    val isRunning: Boolean
        get() = synchronized(lock) { job?.isActive == true }

    fun refresh() {
        val next = synchronized(lock) {
            job?.cancel()
            scope.launch { discoverAndBootstrap() }.also { job = it }
        }
        next.invokeOnCompletion {
            synchronized(lock) {
                if (job === next) job = null
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            job?.cancel()
            job = null
        }
        onBusy(false)
    }

    private suspend fun discoverAndBootstrap() {
        if (!context.hasDealerBluetoothConnectPermission()) {
            onBusy(false)
            onFailure(PokerPairingFailure.BLUETOOTH_PERMISSION_REQUIRED)
            return
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            onBusy(false)
            onFailure(PokerPairingFailure.BLUETOOTH_NOT_BONDED)
            return
        }

        val bonded = try {
            adapter.bondedDevices.filter { it.bondState == BluetoothDevice.BOND_BONDED }
        } catch (_: SecurityException) {
            onBusy(false)
            onFailure(PokerPairingFailure.BLUETOOTH_PERMISSION_REQUIRED)
            return
        }
        val remembered = pairing.bondedPeerId
        val byId = bonded.associateBy(BluetoothDevice::getAddress)
        when (
            val initial = selectDealerPokerBootstrapPeer(
                rememberedPeerId = remembered,
                bondedPeerIds = byId.keys,
                responsivePeerIds = emptySet(),
            )
        ) {
            is DealerPokerBootstrapPeerSelection.Selected -> {
                onBusy(true)
                bootstrap(checkNotNull(byId[initial.peerId]))
                return
            }
            DealerPokerBootstrapPeerSelection.RememberedMissing -> {
                pairing.revokeBondTrust(remembered)
                onBusy(false)
                onFailure(PokerPairingFailure.BLUETOOTH_NOT_BONDED)
                return
            }
            DealerPokerBootstrapPeerSelection.None,
            DealerPokerBootstrapPeerSelection.Ambiguous -> Unit
        }
        if (bonded.isEmpty()) {
            onBusy(false)
            onFailure(PokerPairingFailure.BLUETOOTH_NOT_BONDED)
            return
        }

        onBusy(true)
        val responsive = mutableListOf<BluetoothDevice>()
        for (device in bonded) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (probe(device)) responsive += device
            if (responsive.size > 1) break
        }
        when (
            val selected = selectDealerPokerBootstrapPeer(
                rememberedPeerId = null,
                bondedPeerIds = byId.keys,
                responsivePeerIds = responsive.mapTo(linkedSetOf(), BluetoothDevice::getAddress),
            )
        ) {
            is DealerPokerBootstrapPeerSelection.Selected -> bootstrap(checkNotNull(byId[selected.peerId]))
            DealerPokerBootstrapPeerSelection.Ambiguous -> {
                onBusy(false)
                onFailure(PokerPairingFailure.BLUETOOTH_AMBIGUOUS)
            }
            DealerPokerBootstrapPeerSelection.None,
            DealerPokerBootstrapPeerSelection.RememberedMissing -> {
                onBusy(false)
                onFailure(PokerPairingFailure.BLUETOOTH_NOT_BONDED)
            }
        }
    }

    private suspend fun probe(device: BluetoothDevice): Boolean {
        val socket = connect(device, PROBE_TIMEOUT_MS) ?: return false
        runCatching { socket.close() }
        return true
    }

    private suspend fun bootstrap(device: BluetoothDevice) {
        val socket = connect(device, CONNECT_TIMEOUT_MS)
        if (socket == null) {
            onBusy(false)
            onFailure(PokerPairingFailure.BOOTSTRAP_INVALID)
            return
        }
        try {
            identity.ensureCreatedForBondBootstrap()
            val hello = PokerBondBootstrapProtocol.createHello(identity)
            PokerBondBootstrapWire.writeHello(socket.outputStream, hello)
            val offer = PokerBondBootstrapWire.readOffer(socket.inputStream)
            if (!PokerBondBootstrapProtocol.verifyOffer(hello, offer)) {
                onFailure(PokerPairingFailure.BOOTSTRAP_INVALID)
                return
            }
            val confirmation = PokerBondBootstrapProtocol.createConfirmation(identity, hello, offer)
            PokerBondBootstrapWire.writeConfirmation(socket.outputStream, confirmation)
            val ack = PokerBondBootstrapWire.readAck(socket.inputStream)
            if (!ack.accepted) {
                onFailure(PokerPairingFailure.BOOTSTRAP_INVALID)
                return
            }
            pairing.provisionFromTrustedBond(
                bondedPeerId = device.address,
                peerPublicKey = offer.pokerPublicKey,
                endpoint = offer.endpoint,
            )
            onFailure(PokerPairingFailure.NONE)
            onProvisioned()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: EOFException) {
            onFailure(PokerPairingFailure.BOOTSTRAP_INVALID)
        } catch (_: SecurityException) {
            onFailure(PokerPairingFailure.BLUETOOTH_PERMISSION_REQUIRED)
        } catch (_: Throwable) {
            onFailure(PokerPairingFailure.BOOTSTRAP_INVALID)
        } finally {
            runCatching { socket.close() }
            onBusy(false)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connect(device: BluetoothDevice, timeoutMs: Long): BluetoothSocket? {
        if (!context.hasDealerBluetoothConnectPermission()) return null
        return withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { continuation ->
                    val socket = try {
                        device.createRfcommSocketToServiceRecord(
                            UUID.fromString(POKER_BOND_BOOTSTRAP_SERVICE_UUID),
                        )
                    } catch (failure: Throwable) {
                        continuation.resumeWithException(failure)
                        return@suspendCancellableCoroutine
                    }
                    continuation.invokeOnCancellation { runCatching { socket.close() } }
                    try {
                        socket.connect()
                        if (continuation.isActive) continuation.resume(socket)
                        else runCatching { socket.close() }
                    } catch (failure: Throwable) {
                        runCatching { socket.close() }
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
            }
        }
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 2_000L
        const val CONNECT_TIMEOUT_MS = 8_000L
    }
}

internal sealed interface DealerPokerBootstrapPeerSelection {
    data class Selected(val peerId: String) : DealerPokerBootstrapPeerSelection
    data object None : DealerPokerBootstrapPeerSelection
    data object Ambiguous : DealerPokerBootstrapPeerSelection
    data object RememberedMissing : DealerPokerBootstrapPeerSelection
}

internal fun selectDealerPokerBootstrapPeer(
    rememberedPeerId: String?,
    bondedPeerIds: Set<String>,
    responsivePeerIds: Set<String>,
): DealerPokerBootstrapPeerSelection {
    if (rememberedPeerId != null) {
        return if (rememberedPeerId in bondedPeerIds) {
            DealerPokerBootstrapPeerSelection.Selected(rememberedPeerId)
        } else {
            DealerPokerBootstrapPeerSelection.RememberedMissing
        }
    }
    val candidates = responsivePeerIds.intersect(bondedPeerIds)
    return when (candidates.size) {
        0 -> DealerPokerBootstrapPeerSelection.None
        1 -> DealerPokerBootstrapPeerSelection.Selected(candidates.single())
        else -> DealerPokerBootstrapPeerSelection.Ambiguous
    }
}

internal fun Context.hasDealerBluetoothConnectPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
