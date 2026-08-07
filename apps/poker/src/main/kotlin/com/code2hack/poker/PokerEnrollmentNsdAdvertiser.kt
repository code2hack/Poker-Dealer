package com.code2hack.poker

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.code2hack.pokerdealer.protocol.POKER_ENROLLMENT_SERVICE_TYPE
import com.code2hack.pokerdealer.protocol.POKER_LISTENER_PORT

internal class PokerEnrollmentNsdAdvertiser internal constructor(
    private val registerService: (NsdManager.RegistrationListener) -> Unit,
    private val unregisterService: (NsdManager.RegistrationListener) -> Unit,
) {
    constructor(context: Context) : this(
        registerService = { listener ->
            val manager = checkNotNull(context.getSystemService(NsdManager::class.java)) {
                "Android NSD service is unavailable"
            }
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = SERVICE_NAME
                serviceType = POKER_ENROLLMENT_SERVICE_TYPE
                port = POKER_LISTENER_PORT
            }
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        },
        unregisterService = { listener ->
            context.getSystemService(NsdManager::class.java)?.unregisterService(listener)
        },
    )

    private var registration: NsdManager.RegistrationListener? = null

    fun register(onFailure: () -> Unit) {
        unregister()
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (registration === this) {
                    registration = null
                    onFailure()
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                if (registration === this) registration = null
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (registration === this) registration = null
            }
        }
        registration = listener
        runCatching { registerService(listener) }
            .onFailure {
                if (registration === listener) {
                    registration = null
                    onFailure()
                }
            }
    }

    fun unregister() {
        val listener = registration ?: return
        registration = null
        runCatching { unregisterService(listener) }
    }

    private companion object {
        const val SERVICE_NAME = "Poker-Dealer Enrollment"
    }
}
