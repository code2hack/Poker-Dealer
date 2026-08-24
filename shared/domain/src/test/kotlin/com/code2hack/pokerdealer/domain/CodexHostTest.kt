package com.code2hack.pokerdealer.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class CodexHostTest {
    @Test
    fun `termux host round trips with distribution and loopback route`() {
        val host = InitialCodexHosts.fold6Termux

        val encoded = Json.encodeToString(host)
        val decoded = Json.decodeFromString<CodexHost>(encoded)

        assertEquals(host, decoded)
        assertEquals(HostArchitecture.ANDROID_ARM64, decoded.architecture)
        assertEquals(CodexHostKind.TERMUX_ANDROID, decoded.kind)
        assertEquals(CodexDistribution.TERMUX_COMMUNITY, decoded.distribution)
        assertEquals(listOf(HostConnectionRoute.SSH_LOOPBACK), decoded.connectionRoutes)
        assertEquals(HostAvailabilityClass.OPPORTUNISTIC, decoded.availabilityClass)
        assertEquals(decoded, InitialCodexHosts.all.single { it.id == "fold6-termux" })
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
