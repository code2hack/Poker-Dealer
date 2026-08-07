package com.code2hack.poker

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class PokerProductionWiringTest {
    @Test
    fun `foreground activity starts the private listener and retries it when already enabled`() {
        assertEquals(
            PokerListenerService.ACTION_RETRY,
            PokerListenerService.activityForegroundAction(enabled = true),
        )
        assertEquals(
            PokerListenerService.ACTION_ENABLE,
            PokerListenerService.activityForegroundAction(enabled = false),
        )
        assertEquals(
            PokerListenerService.ACTION_RETRY,
            PokerListenerService.launchSpec(PokerListenerService.ACTION_RETRY).action,
        )
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

    private fun ipv4(value: String): Inet4Address = InetAddress.getByName(value) as Inet4Address
}
