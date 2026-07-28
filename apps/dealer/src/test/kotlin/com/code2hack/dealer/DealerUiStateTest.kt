package com.code2hack.dealer

import com.code2hack.pokerdealer.domain.HostConnectionRoute
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ControlSurface
import com.code2hack.pokerdealer.domain.DeliveryState
import com.code2hack.pokerdealer.domain.DiscoveredThread
import com.code2hack.pokerdealer.domain.FileApprovalRequest
import com.code2hack.pokerdealer.domain.FileApprovalState
import com.code2hack.pokerdealer.domain.InitialCodexHosts
import com.code2hack.pokerdealer.domain.ServerRequestLocator
import com.code2hack.pokerdealer.domain.ThreadAttachmentState
import com.code2hack.pokerdealer.domain.ThreadWorkState
import com.code2hack.pokerdealer.protocol.appserver.M1ConnectionPhase
import com.code2hack.pokerdealer.protocol.appserver.M1FailurePhase
import com.code2hack.pokerdealer.protocol.appserver.M1RecoveryUpdate
import com.code2hack.pokerdealer.protocol.appserver.M1TurnInput
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
    fun TermuxRecoveryShowsFailingPhaseBackoffAndAction() {
        val state = DealerUiState(status = DealerRunState.RECONNECTING).withRecovery(
            InitialCodexHosts.fold6Termux,
            M1RecoveryUpdate(
                failedAttempt = 1,
                maxAttempts = 4,
                retryInMs = 1_000,
                failurePhase = M1FailurePhase.TCP_CONNECT,
            ),
        )

        assertEquals(DealerRunState.BACKING_OFF, state.status)
        assertEquals(M1FailurePhase.TCP_CONNECT, state.recovery?.phase)
        assertEquals(1_000L, state.recovery?.retryInMs)
        assertEquals(true, state.recovery?.action?.contains("Open Termux"))
        assertEquals(true, state.cards.isEmpty())
    }

    @Test
    fun acceptedOrUnknownActionBlocksAnotherTurnUntilReconciled() {
        val locator = CodexThreadLocator("fold6-termux", "thread")
        val pending = M1TurnInput("turn", "thread", "client")
            .pendingUserCard("fold6-termux/thread", 1)

        assertEquals(false, DealerUiState(cards = listOf(pending)).hasUnsettledAction(locator))
        assertEquals(
            true,
            DealerUiState(
                cards = listOf(pending.copy(delivery = DeliveryState.ACCEPTED)),
            ).hasUnsettledAction(locator),
        )
        assertEquals(
            true,
            DealerUiState(
                cards = listOf(pending.copy(delivery = DeliveryState.UNKNOWN)),
            ).hasUnsettledAction(locator),
        )
    }

    @Test
    fun incompleteFileApprovalFailsClosedWithoutChangingAnUnrelatedBusyTurn() {
        val fileThread = CodexThreadLocator("u4090", "file-thread")
        val busyThread = CodexThreadLocator("u4090", "busy-thread")
        val request = FileApprovalRequest(
            locator = ServerRequestLocator("u4090", 1, "file-request"),
            thread = fileThread,
            turnId = "file-turn",
            itemId = "file-item",
            reason = null,
            grantRoot = null,
            fileChanges = emptyList(),
            wireFingerprint = "wire-file",
            fingerprint = "file",
            createdAtMs = 1,
        )
        val pending = FileApprovalState().receive(request, sameIdReissueQualified = false)
        val state = DealerUiState(
            threads = mapOf(
                fileThread to discovered(fileThread, "file-turn"),
                busyThread to discovered(busyThread, "busy-turn"),
            ),
        ).withApprovals(fileApprovals = pending)

        assertEquals(ThreadWorkState.ATTENTION_REQUIRED, state.threads[fileThread]?.workState)
        assertEquals(ThreadWorkState.BUSY, state.threads[busyThread]?.workState)

        val failed = state.withApprovals(
            fileApprovals = pending.failClosed(request.locator, "diff incomplete"),
        )
        assertEquals(ThreadWorkState.BUSY, failed.threads[fileThread]?.workState)
        assertEquals(ThreadWorkState.BUSY, failed.threads[busyThread]?.workState)
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
            threadAttachments = ThreadAttachmentState()
                .attach(CodexThreadLocator("spark", "thread"))
                .claim(CodexThreadLocator("spark", "thread")),
        )
        val config = DealerRunConfig("spark", "", "spark", "user", "thread", "turn")

        assertEquals(true, state.hasDealerControl(config))
        assertEquals(false, state.hasDealerControl(config.copy(hostId = "u4090")))
        assertEquals(false, state.hasDealerControl(config.copy(threadId = "other")))
        assertEquals(
            false,
            state.copy(threadAttachments = state.threadAttachments.release(CodexThreadLocator("spark", "thread")))
                .hasDealerControl(config),
        )

        val termuxConfig = DealerRunConfig(
            hostId = "fold6-termux",
            lanHost = "",
            tailnetHost = "",
            sshUser = "termux",
            threadId = "phone-thread",
            turnText = "turn",
            loopbackSshPort = 8022,
        )
        val termuxState = DealerUiState(
            threadAttachments = ThreadAttachmentState()
                .attach(CodexThreadLocator("fold6-termux", "phone-thread"))
                .claim(CodexThreadLocator("fold6-termux", "phone-thread")),
        )

        assertEquals(true, termuxState.hasDealerControl(termuxConfig))
    }

    private fun discovered(locator: CodexThreadLocator, turnId: String) = DiscoveredThread(
        locator = locator,
        name = null,
        preview = null,
        workingDirectory = "/work",
        updatedAtSeconds = 1,
        status = "active",
        workState = ThreadWorkState.BUSY,
        activeTurnId = turnId,
    )
}
