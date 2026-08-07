package com.code2hack.poker

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.code2hack.pokerdealer.protocol.POKER_BOND_BOOTSTRAP_SERVICE_NAME
import com.code2hack.pokerdealer.protocol.POKER_BOND_BOOTSTRAP_SERVICE_UUID
import com.code2hack.pokerdealer.protocol.POKER_LISTENER_PORT
import com.code2hack.pokerdealer.protocol.PokerBondBootstrapAck
import com.code2hack.pokerdealer.protocol.PokerBondBootstrapProtocol
import com.code2hack.pokerdealer.protocol.PokerBondBootstrapWire
import com.code2hack.pokerdealer.protocol.PokerHotspotEndpoint
import com.code2hack.pokerdealer.protocol.PokerPairingController
import com.code2hack.pokerdealer.protocol.PokerPairingFailure
import java.io.Closeable
import java.io.EOFException
import java.net.SocketException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class PokerBluetoothBootstrapServer(
    context: Context,
    private val identity: AndroidKeystorePairingIdentity,
    private val pairing: PokerPairingController,
    private val scope: CoroutineScope,
    private val onProvisioned: () -> Unit,
    private val onFailure: (PokerPairingFailure) -> Unit,
) : Closeable {
    private val context = context.applicationContext
    private val lock = Any()
    private var serverSocket: BluetoothServerSocket? = null
    private var job: Job? = null

    val isRunning: Boolean
        get() = synchronized(lock) { job?.isActive == true }

    fun start(): Boolean {
        synchronized(lock) {
            if (job?.isActive == true) return true
            if (!context.hasPokerBluetoothConnectPermission()) {
                onFailure(PokerPairingFailure.BLUETOOTH_PERMISSION_REQUIRED)
                return false
            }
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                onFailure(PokerPairingFailure.BLUETOOTH_NOT_BONDED)
                return false
            }
            val opened = try {
                adapter.listenUsingRfcommWithServiceRecord(
                    POKER_BOND_BOOTSTRAP_SERVICE_NAME,
                    UUID.fromString(POKER_BOND_BOOTSTRAP_SERVICE_UUID),
                )
            } catch (_: SecurityException) {
                onFailure(PokerPairingFailure.BLUETOOTH_PERMISSION_REQUIRED)
                return false
            } catch (_: Throwable) {
                onFailure(PokerPairingFailure.BOOTSTRAP_INVALID)
                return false
            }
            serverSocket = opened
            job = scope.launch(Dispatchers.IO) { acceptLoop(opened) }
            return true
        }
    }

    fun restart() {
        close()
        start()
    }

    override fun close() {
        val resources = synchronized(lock) {
            val current = serverSocket to job
            serverSocket = null
            job = null
            current
        }
        runCatching { resources.first?.close() }
        resources.second?.cancel()
    }

    private suspend fun acceptLoop(server: BluetoothServerSocket) {
        while (currentCoroutineContext().isActive) {
            val socket = try {
                server.accept()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return
            }
            socket.use { handle(it) }
        }
    }

    private fun handle(socket: BluetoothSocket) {
        try {
            if (!context.hasPokerBluetoothConnectPermission()) {
                onFailure(PokerPairingFailure.BLUETOOTH_PERMISSION_REQUIRED)
                return
            }
            val remote = socket.remoteDevice
            if (remote.bondState != android.bluetooth.BluetoothDevice.BOND_BONDED) {
                onFailure(PokerPairingFailure.BLUETOOTH_NOT_BONDED)
                return
            }
            val remoteId = remote.address
            val remembered = pairing.bondedPeerId
            if (remembered != null && remembered != remoteId) {
                onFailure(PokerPairingFailure.PAIRING_MISMATCH)
                return
            }

            identity.ensureCreatedForBondBootstrap()
            val hello = PokerBondBootstrapWire.readHello(socket.inputStream)
            val endpoint = PokerHotspotEndpoint(
                host = checkNotNull(activeWifiAddress(context).hostAddress),
                port = POKER_LISTENER_PORT,
            )
            val offer = PokerBondBootstrapProtocol.createOffer(identity, hello, endpoint)
            PokerBondBootstrapWire.writeOffer(socket.outputStream, offer)
            val confirmation = PokerBondBootstrapWire.readConfirmation(socket.inputStream)
            if (!PokerBondBootstrapProtocol.verifyConfirmation(hello, offer, confirmation)) {
                onFailure(PokerPairingFailure.BOOTSTRAP_INVALID)
                return
            }

            pairing.provisionFromTrustedBond(
                bondedPeerId = remoteId,
                peerPublicKey = hello.dealerPublicKey,
                endpoint = endpoint,
            )
            PokerBondBootstrapWire.writeAck(socket.outputStream, PokerBondBootstrapAck(accepted = true))
            onProvisioned()
        } catch (_: EOFException) {
            // Dealer uses a connection-only probe to identify the private service before first trust.
        } catch (_: SocketException) {
            // A probe or cancelled bootstrap may close cleanly before the first frame.
        } catch (_: SecurityException) {
            onFailure(PokerPairingFailure.BLUETOOTH_PERMISSION_REQUIRED)
        } catch (_: Throwable) {
            onFailure(PokerPairingFailure.BOOTSTRAP_INVALID)
        }
    }
}

internal fun Context.hasPokerBluetoothConnectPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

@Suppress("DEPRECATION")
internal fun Context.pokerBluetoothAdapter(): BluetoothAdapter? =
    getSystemService(BluetoothManager::class.java)?.adapter
