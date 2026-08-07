package com.code2hack.poker

import com.code2hack.pokerdealer.protocol.PokerPairingFailure
import java.net.InetAddress
import java.net.Inet4Address
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PokerProductionWiringTest {
    @Test
    fun `activity starts the private listener and resumes it when already enabled`() {
        assertEquals(
            PokerListenerService.ACTION_RETRY,
            PokerListenerService.activityStartAction(enabled = true),
        )
        assertEquals(
            PokerListenerService.ACTION_ENABLE,
            PokerListenerService.activityStartAction(enabled = false),
        )
    }

    @Test
    fun `pair and replacement are explicit listener service actions`() {
        val pair = PokerListenerService.launchSpec(
            action = PokerListenerService.ACTION_OPEN_ENROLLMENT,
        )
        val replacement = PokerListenerService.launchSpec(
            action = PokerListenerService.ACTION_OPEN_ENROLLMENT,
            replacement = true,
        )

        assertEquals(PokerListenerService.ACTION_OPEN_ENROLLMENT, pair.action)
        assertFalse(pair.replacement)
        assertTrue(replacement.replacement)
    }

    @Test
    fun `pairing display state redacts one-time code across lifecycle diagnostics`() {
        val enrollment = PokerPairingUiState.EnrollmentOpen(
            displayCode = "123456",
            replacement = false,
            expiresAtMs = 123L,
            failure = PokerPairingFailure.INVALID_CODE,
        )

        assertFalse(enrollment.toString().contains("123456"))
        assertTrue(enrollment.toString().contains("redacted"))
    }

    @Test
    fun `ordinary Wi-Fi selector ignores VPN tun address`() {
        val selected = selectOrdinaryWifiAddress(
            listOf(
                WifiAddressSnapshot(
                    hasWifiTransport = true,
                    hasVpnTransport = true,
                    hasNotVpnCapability = false,
                    addresses = listOf(ipv4("100.64.0.7")),
                ),
                WifiAddressSnapshot(
                    hasWifiTransport = true,
                    hasVpnTransport = false,
                    hasNotVpnCapability = true,
                    addresses = listOf(ipv4("192.168.0.4")),
                ),
            ),
        )

        assertEquals("192.168.0.4", selected?.hostAddress)
    }

    @Test
    fun `ordinary Wi-Fi interface selector ignores VPN tun interface`() {
        val selected = selectOrdinaryWifiInterfaceAddress(
            listOf(
                WifiInterfaceSnapshot(
                    name = "tun1",
                    isUp = true,
                    isLoopback = false,
                    addresses = listOf(ipv4("100.87.122.122")),
                ),
                WifiInterfaceSnapshot(
                    name = "wlan0",
                    isUp = true,
                    isLoopback = false,
                    addresses = listOf(ipv4("10.116.96.154")),
                ),
            ),
        )

        assertEquals("10.116.96.154", selected?.hostAddress)
    }

    private fun ipv4(value: String): Inet4Address =
        InetAddress.getByName(value) as Inet4Address
}
