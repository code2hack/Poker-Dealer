package com.code2hack.pokerdealer.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class CodexHostTest {
    @Test
    fun `android local host round trips with distribution and loopback route`() {
        val host = androidLocalHost("android-local")

        val encoded = Json.encodeToString(host)
        val decoded = Json.decodeFromString<CodexHost>(encoded)

        assertEquals(host, decoded)
        assertEquals(HostArchitecture.ANDROID_ARM64, decoded.architecture)
        assertEquals(CodexHostKind.TERMUX_ANDROID, decoded.kind)
        assertEquals(CodexDistribution.TERMUX_COMMUNITY, decoded.distribution)
        assertEquals(listOf(HostConnectionRoute.SSH_LOOPBACK), decoded.connectionRoutes)
        assertEquals(HostAvailabilityClass.OPPORTUNISTIC, decoded.availabilityClass)
    }

    @Test
    fun `workstation preserves ordered route preference independent of architecture`() {
        listOf(HostArchitecture.LINUX_ARM64, HostArchitecture.LINUX_X86_64).forEach { architecture ->
            val host = workstation("host-$architecture", architecture)
                .copy(activeConnectionRoute = HostConnectionRoute.SSH_EMBEDDED_TSNET)

            assertEquals(HostConnectionRoute.SSH_LAN, host.connectionRoutes.first())
            assertEquals(HostConnectionRoute.SSH_EMBEDDED_TSNET, host.connectionRoutes[1])
            assertEquals(architecture, host.architecture)
            assertEquals(HostConnectionRoute.SSH_EMBEDDED_TSNET, host.activeConnectionRoute)
        }
    }

    @Test
    fun `thread identity remains host qualified`() {
        val first = CodexThreadLocator(hostId = "host-a", threadId = "thr_same")
        val second = CodexThreadLocator(hostId = "host-b", threadId = "thr_same")

        assertEquals("thr_same", first.threadId)
        assertEquals("thr_same", second.threadId)
        assertNotEquals(first, second)
    }

    private fun workstation(id: String, architecture: HostArchitecture) = CodexHost(
        id = id,
        displayName = id,
        kind = CodexHostKind.LINUX_WORKSTATION,
        architecture = architecture,
        distribution = CodexDistribution.OPENAI_UPSTREAM,
        connectionRoutes = listOf(
            HostConnectionRoute.SSH_LAN,
            HostConnectionRoute.SSH_EMBEDDED_TSNET,
            HostConnectionRoute.SSH_EXTERNAL_TAILSCALE,
        ),
        availabilityClass = HostAvailabilityClass.PERSISTENT,
        connectionState = HostConnectionState.DISCONNECTED,
    )

    private fun androidLocalHost(id: String) = CodexHost(
        id = id,
        displayName = id,
        kind = CodexHostKind.TERMUX_ANDROID,
        architecture = HostArchitecture.ANDROID_ARM64,
        distribution = CodexDistribution.TERMUX_COMMUNITY,
        connectionRoutes = listOf(HostConnectionRoute.SSH_LOOPBACK),
        availabilityClass = HostAvailabilityClass.OPPORTUNISTIC,
        connectionState = HostConnectionState.DISCONNECTED,
    )
}
