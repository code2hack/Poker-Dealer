package com.code2hack.dealer

import com.code2hack.pokerdealer.domain.HostConnectionRoute
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ControlSurface
import com.code2hack.pokerdealer.protocol.appserver.M1ConnectionPhase
import com.code2hack.pokerdealer.protocol.host.RouteCapability
import com.code2hack.pokerdealer.protocol.host.RouteConnectionException
import com.code2hack.pokerdealer.protocol.host.RouteDiagnostic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DealerUiStateTest {
    @Test
    fun successfulRunEndsCompletedAndDisconnected() {
        val state = DealerUiState(
            status = DealerRunState.RUNNING,
            route = HostConnectionRoute.SSH_LAN,
        ).afterRun(
            recovered = false,
            threadId = "thread",
            appServerVersion = "0.145.0",
            routeDiagnostics = emptyList(),
        )

        assertEquals(DealerRunState.COMPLETED, state.status)
        assertFalse(state.running)
        assertEquals(null, state.route)
    }

    @Test
    fun recoveredRunEndsRecoveredAndDisconnected() {
        val state = DealerUiState(
            status = DealerRunState.RECONNECTING,
            route = HostConnectionRoute.SSH_LAN,
        ).afterRun(
            recovered = true,
            threadId = "thread",
            appServerVersion = "0.145.0",
            routeDiagnostics = emptyList(),
        )

        assertEquals(DealerRunState.RECOVERED, state.status)
        assertFalse(state.running)
        assertEquals(null, state.route)
    }

    @Test
    fun activeRouteIsVisibleWhileRunningAndClearedWhileReconnecting() {
        val running = DealerUiState(status = DealerRunState.CONNECTING)
            .withActiveRoute(HostConnectionRoute.SSH_LAN, emptyList())
            .withPhase(M1ConnectionPhase.RUNNING)

        assertEquals(HostConnectionRoute.SSH_LAN, running.route)
        assertEquals(DealerRunState.RUNNING, running.status)
        assertEquals(null, running.withPhase(M1ConnectionPhase.RECONNECTING).route)
    }

    @Test
    fun terminalStateSurvivesServiceStateReattachment() {
        val previous = DealerServiceState.mutableState.value
        try {
            DealerServiceState.mutableState.value = DealerUiState(status = DealerRunState.COMPLETED)

            assertEquals(DealerRunState.COMPLETED, DealerServiceState.state.value.status)
        } finally {
            DealerServiceState.mutableState.value = previous
        }
    }

    @Test
    fun reconnectRouteDiagnosticsSurviveAsSuppressedFailureContext() {
        val diagnostic = RouteDiagnostic(
            route = HostConnectionRoute.SSH_LAN,
            capability = RouteCapability.SUPPORTED_CONFIGURED,
            attempted = true,
            failure = "connection refused",
        )
        val initialFailure = IllegalStateException("proxy disconnected")
        initialFailure.addSuppressed(RouteConnectionException("u4090", listOf(diagnostic), null))

        assertEquals(listOf(diagnostic), initialFailure.routeDiagnostics())
    }

    @Test
    fun nativeTailnetStatusPreservesEnrollmentDiagnostics() {
        val login = """
            {"state":"login_required","loginUrl":"https://login.tailscale.com/a/test"}
        """.trimIndent().toEmbeddedTailnetUiState()
        val connected = """
            {"state":"degraded","nodeName":"dealer-fold6","path":"relayed","relay":"hkg","health":["Workstation path uses DERP; direct connectivity is unavailable"]}
        """.trimIndent().toEmbeddedTailnetUiState()

        assertEquals(EmbeddedTailnetState.LOGIN_REQUIRED, login.state)
        assertEquals("https://login.tailscale.com/a/test", login.loginUrl)
        assertEquals(EmbeddedTailnetState.DEGRADED, connected.state)
        assertEquals("dealer-fold6", connected.nodeName)
        assertEquals("Degraded (DERP hkg)", connected.connectionLabel)
    }

    @Test
    fun runRequiresDealerControlForTheExactHostQualifiedThread() {
        val state = DealerUiState(
            control = DealerControlState(
                CodexThreadLocator("spark", "thread"),
                ControlSurface.DEALER,
            ),
        )
        val config = DealerRunConfig("spark", "", "spark", "user", "thread", "turn")

        assertEquals(true, state.hasDealerControl(config))
        assertEquals(false, state.hasDealerControl(config.copy(hostId = "u4090")))
        assertEquals(false, state.hasDealerControl(config.copy(threadId = "other")))
        assertEquals(
            false,
            state.copy(control = state.control?.copy(surface = ControlSurface.LOCAL_TUI))
                .hasDealerControl(config),
        )
    }
}
