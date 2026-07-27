package com.code2hack.pokerdealer.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
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
            connectionRoute = HostConnectionRoute.SSH_LOOPBACK,
            availabilityClass = HostAvailabilityClass.OPPORTUNISTIC,
            connectionState = HostConnectionState.WAITING_FOR_HOST_APP,
            daemonState = DaemonState.STOPPED,
            codexVersion = "community-build",
        )

        val encoded = Json.encodeToString(host)
        val decoded = Json.decodeFromString<CodexHost>(encoded)

        assertEquals(host, decoded)
        assertEquals(CodexHostKind.TERMUX_ANDROID, decoded.kind)
        assertEquals(HostConnectionRoute.SSH_LOOPBACK, decoded.connectionRoute)
        assertEquals(HostAvailabilityClass.OPPORTUNISTIC, decoded.availabilityClass)
    }

    @Test
    fun `thread identity remains host qualified`() {
        val spark = CodexThreadLocator(hostId = "spark", threadId = "thr_same")
        val termux = CodexThreadLocator(hostId = "fold6-termux", threadId = "thr_same")

        assertEquals("thr_same", spark.threadId)
        assertEquals("thr_same", termux.threadId)
        assert(spark != termux)
    }
}
