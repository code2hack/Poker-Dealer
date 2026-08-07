package com.code2hack.dealer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.code2hack.pokerdealer.protocol.POKER_ENROLLMENT_SERVICE_TYPE
import com.code2hack.pokerdealer.protocol.PokerEnrollmentCandidates
import com.code2hack.pokerdealer.protocol.PokerEnrollmentDiscoveryResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean

internal class PokerEnrollmentDiscovery(
    context: Context,
    private val timeoutMs: Long = DISCOVERY_TIMEOUT_MS,
) {
    private val nsd = context.getSystemService(NsdManager::class.java)

    init {
        require(timeoutMs > 0) { "Discovery timeout must be positive" }
    }

    suspend fun discover(): PokerEnrollmentDiscoveryResult {
        val manager = nsd ?: return PokerEnrollmentDiscoveryResult.NotFound
        val parentContext = currentCoroutineContext()
        return suspendCancellableCoroutine { continuation ->
            val candidates = PokerEnrollmentCandidates()
            val finished = AtomicBoolean(false)
            var timeoutJob: Job? = null
            var discoveryListener: NsdManager.DiscoveryListener? = null

            fun stop() {
                timeoutJob?.cancel()
                discoveryListener?.let { listener ->
                    runCatching { manager.stopServiceDiscovery(listener) }
                }
            }

            fun finish(result: PokerEnrollmentDiscoveryResult) {
                if (!finished.compareAndSet(false, true)) return
                stop()
                if (continuation.isActive) continuation.resume(result)
            }

            fun resolve(serviceInfo: NsdServiceInfo) {
                val serviceName = serviceInfo.serviceName.orEmpty()
                if (serviceName.isBlank()) return
                runCatching {
                    manager.resolveService(
                        serviceInfo,
                        object : NsdManager.ResolveListener {
                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                val address = resolved.host as? Inet4Address ?: return
                                val host = address.hostAddress ?: return
                                candidates.resolved(
                                    serviceName = resolved.serviceName.orEmpty().ifBlank { serviceName },
                                    host = host,
                                    port = resolved.port,
                                )
                            }

                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                        },
                    )
                }
            }

            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) = Unit

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.serviceType.orEmpty().trimEnd('.') != POKER_ENROLLMENT_SERVICE_TYPE.trimEnd('.')) return
                    val serviceName = serviceInfo.serviceName.orEmpty()
                    if (serviceName.isBlank()) return
                    candidates.found(serviceName)
                    if (candidates.result() == PokerEnrollmentDiscoveryResult.Ambiguous) {
                        finish(PokerEnrollmentDiscoveryResult.Ambiguous)
                    } else {
                        resolve(serviceInfo)
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    candidates.lost(serviceInfo.serviceName.orEmpty())
                }

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    finish(PokerEnrollmentDiscoveryResult.NotFound)
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
            discoveryListener = listener
            timeoutJob = CoroutineScope(parentContext).launch {
                delay(timeoutMs)
                if (isActive) finish(candidates.result())
            }
            continuation.invokeOnCancellation { stop() }
            runCatching {
                manager.discoverServices(
                    POKER_ENROLLMENT_SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener,
                )
            }.onFailure {
                finish(PokerEnrollmentDiscoveryResult.NotFound)
            }
        }
    }

    private companion object {
        const val DISCOVERY_TIMEOUT_MS = 5_000L
    }
}
