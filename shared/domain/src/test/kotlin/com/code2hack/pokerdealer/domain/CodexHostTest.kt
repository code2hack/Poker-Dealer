package com.code2hack.pokerdealer.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class CodexHostTest {
    @Test
    fun `termux host round trips with distribution and loopback route`() {
        val host = CodexHost(
            id = "fold6-termux",
            displayName = "Fold6 Termux",
            kind = CodexHostKind.TERMUX_ANDROID,
            architecture = HostArchitecture.ANDROID_ARM64,
            distribution = CodexDistribution.TERMUX_COMMUNITY,
            connectionRoutes = listOf(HostConnectionRoute.SSH_LOOPBACK),
            activeConnectionRoute = HostConnectionRoute.SSH_LOOPBACK,
            availabilityClass = HostAvailabilityClass.OPPORTUNISTIC,
            connectionState = HostConnectionState.WAITING_FOR_HOST_APP,
            daemonState = DaemonState.STOPPED,
            codexVersion = "community-build",
        )

        val encoded = Json.encodeToString(host)
        val decoded = Json.decodeFromString<CodexHost>(encoded)

        assertEquals(host, decoded)
        assertEquals(CodexHostKind.TERMUX_ANDROID, decoded.kind)
        assertEquals(listOf(HostConnectionRoute.SSH_LOOPBACK), decoded.connectionRoutes)
        assertEquals(HostAvailabilityClass.OPPORTUNISTIC, decoded.availabilityClass)
    }

    @Test
    fun `workstation preserves ordered route preference`() {
        val host = InitialCodexHosts.u4090.copy(activeConnectionRoute = HostConnectionRoute.SSH_EMBEDDED_TSNET)

        assertEquals(HostConnectionRoute.SSH_LAN, host.connectionRoutes.first())
        assertEquals(HostConnectionRoute.SSH_EMBEDDED_TSNET, host.connectionRoutes[1])
        assertEquals(HostArchitecture.LINUX_X86_64, host.architecture)
        assertEquals(HostConnectionRoute.SSH_EMBEDDED_TSNET, host.activeConnectionRoute)
    }

    @Test
    fun `initial workstation catalog supports both architectures through the same routes`() {
        assertEquals(
            mapOf(
                "spark" to HostArchitecture.LINUX_ARM64,
                "u4090" to HostArchitecture.LINUX_X86_64,
            ),
            InitialCodexHosts.workstations.associate { it.id to it.architecture },
        )
        InitialCodexHosts.workstations.forEach {
            assertEquals(
                listOf(
                    HostConnectionRoute.SSH_LAN,
                    HostConnectionRoute.SSH_EMBEDDED_TSNET,
                    HostConnectionRoute.SSH_EXTERNAL_TAILSCALE,
                ),
                it.connectionRoutes,
            )
        }
    }

    @Test
    fun `thread identity remains host qualified`() {
        val spark = CodexThreadLocator(hostId = "spark", threadId = "thr_same")
        val termux = CodexThreadLocator(hostId = "fold6-termux", threadId = "thr_same")

        assertEquals("thr_same", spark.threadId)
        assertEquals("thr_same", termux.threadId)
        assertNotEquals(spark, termux)
    }
}
