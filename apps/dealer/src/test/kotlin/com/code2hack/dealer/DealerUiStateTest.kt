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
import com.code2hack.pokerdealer.domain.ThreadStartCatalog
import com.code2hack.pokerdealer.domain.ThreadStartSelection
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

    @Test
    fun threadCreationFailureKeepsReviewedSettingsWithoutAttachingAnything() {
        val state = DealerUiState(
            newThread = NewThreadUiState(
                hostId = "u4090",
                observedWorkingDirectories = listOf("/work/repo"),
                workingDirectory = "/work/repo",
                catalog = ThreadStartCatalog("/work/repo"),
                creating = true,
            ),
        ).withThreadCreationFailure("invalid provider")

        assertEquals("invalid provider", state.newThread?.error)
        assertFalse(state.newThread?.creating ?: true)
        assertEquals(emptySet<CodexThreadLocator>(), state.threadAttachments.attached)
        assertEquals(emptySet<CodexThreadLocator>(), state.threadAttachments.dealerClaims)
    }

    @Test
    fun createdEmptyThreadIsAttachedReadyControlledAndOpensItsComposer() {
        val locator = CodexThreadLocator("u4090", "new-thread")
        val state = DealerUiState(
            newThread = NewThreadUiState(
                hostId = "u4090",
                observedWorkingDirectories = listOf("/work/repo"),
                workingDirectory = "/work/repo",
            ),
        ).withCreatedThread(
            locator = locator,
            name = null,
            preview = "",
            selection = ThreadStartSelection(
                workingDirectory = "/work/repo",
                reasoningEffort = "high",
            ),
        )

        assertEquals(setOf(locator), state.threadAttachments.attached)
        assertEquals(setOf(locator), state.threadAttachments.dealerClaims)
        assertEquals(ThreadWorkState.READY, state.threads.getValue(locator).workState)
        assertEquals(ControlSurface.DEALER, state.threads.getValue(locator).intendedControlSurface)
        assertEquals("high", state.threadActions.pendingReasoningEfforts[locator])
        assertEquals(locator, state.browsedThread)
        assertEquals(null, state.newThread)
    }

    @Test
    fun failedResumeKeepsSettingsOpenWithoutAttachmentOrControl() {
        val locator = CodexThreadLocator("u4090", "loaded")
        val state = DealerUiState(
            resumeThread = ResumeThreadUiState(
                locator = locator,
                observedWorkingDirectories = listOf("/work/repo"),
                workingDirectory = "/work/repo",
                resuming = true,
            ),
        ).withThreadResumeFailure("Resume settings unavailable: override ignored")

        assertEquals("Resume settings unavailable: override ignored", state.resumeThread?.error)
        assertFalse(state.resumeThread?.resuming ?: true)
        assertEquals(emptySet<CodexThreadLocator>(), state.threadAttachments.attached)
        assertEquals(emptySet<CodexThreadLocator>(), state.threadAttachments.dealerClaims)
    }

    @Test
    fun resumeOverridesRequireAnExistingReadyControlClaim() {
        val locator = CodexThreadLocator("spark", "loaded")
        val selection = ThreadStartSelection("/work/repo", modelOverride = "model")
        val initial = DealerUiState(
            threads = mapOf(
                locator to DiscoveredThread(
                    locator = locator,
                    workingDirectory = "/work/repo",
                    workState = ThreadWorkState.READY,
                ),
            ),
            resumeThread = ResumeThreadUiState(
                locator = locator,
                observedWorkingDirectories = listOf("/work/repo"),
                workingDirectory = "/work/repo",
            ),
        )

        assertEquals(
            "Take Dealer control before applying Resume overrides",
            initial.resumeControlError(selection),
        )
        val claimed = initial.withResumeControlClaim(true)
        assertEquals(true, claimed.resumeThread?.controlClaimed)
        assertEquals(null, claimed.resumeControlError(selection))
    }

    @Test
    fun inheritedResumeAttachesAsObserverWhileVerifiedOverridesGrantControl() {
        val locator = CodexThreadLocator("u4090", "loaded")
        val initial = DealerUiState(
            threads = mapOf(
                locator to DiscoveredThread(
                    locator = locator,
                    workingDirectory = "/work/repo",
                    workState = ThreadWorkState.READY,
                ),
            ),
        )

        val observer = initial.withResumedThread(
            locator,
            ThreadStartSelection("/work/repo"),
            grantControl = false,
        )
        val controlled = initial.withResumedThread(
            locator,
            ThreadStartSelection("/work/repo", modelOverride = "model"),
            grantControl = true,
        )

        assertEquals(setOf(locator), observer.threadAttachments.attached)
        assertEquals(emptySet<CodexThreadLocator>(), observer.threadAttachments.dealerClaims)
        assertEquals(ControlSurface.NONE, observer.threads.getValue(locator).intendedControlSurface)
        assertEquals(setOf(locator), controlled.threadAttachments.dealerClaims)
        assertEquals(ControlSurface.DEALER, controlled.threads.getValue(locator).intendedControlSurface)
    }

    @Test
    fun renameWorksInEveryKnownWorkStateAndAllowsSameNames() {
        val locators = listOf(
            CodexThreadLocator("spark", "ready"),
            CodexThreadLocator("spark", "busy"),
            CodexThreadLocator("spark", "attention"),
        )
        val states = listOf(
            ThreadWorkState.READY,
            ThreadWorkState.BUSY,
            ThreadWorkState.ATTENTION_REQUIRED,
        )
        val initial = DealerUiState(
            threads = locators.zip(states).associate { (locator, workState) ->
                locator to DiscoveredThread(locator, name = locator.threadId, workState = workState)
            },
        )

        val renamed = locators.fold(initial) { state, locator ->
            state.withRenamedThread(locator, "Shared name")
        }

        assertEquals(locators.toSet(), renamed.threads.keys)
        assertEquals(setOf("Shared name"), renamed.threads.values.mapNotNull { it.name }.toSet())
    }

    @Test
    fun forkIsGatedToReadyAndCreatesAControlledSameHostLocator() {
        val source = CodexThreadLocator("spark", "source")
        val fork = CodexThreadLocator("spark", "fork")
        assertEquals(true, DiscoveredThread(source, workState = ThreadWorkState.READY).canFork())
        assertEquals(false, DiscoveredThread(source, workState = ThreadWorkState.BUSY).canFork())
        assertEquals(false, DiscoveredThread(source, workState = ThreadWorkState.ATTENTION_REQUIRED).canFork())

        val state = DealerUiState(
            threads = mapOf(
                source to DiscoveredThread(
                    locator = source,
                    workingDirectory = "/work/repo",
                    workState = ThreadWorkState.READY,
                ),
            ),
            newThread = NewThreadUiState(
                hostId = "spark",
                observedWorkingDirectories = listOf("/work/repo"),
                workingDirectory = "/work/repo",
                sourceLocator = source,
            ),
        ).withCreatedThread(
            locator = fork,
            name = null,
            preview = "Existing prompt",
            selection = ThreadStartSelection("/work/repo", reasoningEffort = "high"),
        )

        assertEquals(ThreadWorkState.READY, state.threads.getValue(source).workState)
        assertEquals(setOf(fork), state.threadAttachments.attached)
        assertEquals(setOf(fork), state.threadAttachments.dealerClaims)
        assertEquals(ControlSurface.DEALER, state.threads.getValue(fork).intendedControlSurface)
        assertEquals(fork, state.browsedThread)
    }

    @Test
    fun archiveNotificationsDetachOnlyTheThreadsTheServerReports() {
        val root = CodexThreadLocator("spark", "root")
        val child = CodexThreadLocator("spark", "child")
        val unrelated = CodexThreadLocator("spark", "unrelated")
        val initial = lifecycleState(root, child, unrelated)

        val archived = initial.withArchivedThreads(setOf(root, child))

        assertEquals(setOf(unrelated), archived.threadAttachments.attached)
        assertEquals(setOf(unrelated), archived.threadAttachments.dealerClaims)
        assertEquals(true, archived.threads.getValue(root).archived)
        assertEquals(true, archived.threads.getValue(child).archived)
        assertEquals(false, archived.threads.getValue(unrelated).archived)
        assertEquals(initial.threadActions, archived.threadActions)
        assertEquals(initial.cards, archived.cards)
    }

    @Test
    fun restoreNotificationRestoresOnlyTheSelectedThreadAndKeepsItDetached() {
        val selected = CodexThreadLocator("spark", "selected")
        val descendant = CodexThreadLocator("spark", "descendant")
        val initial = lifecycleState(selected, descendant).withArchivedThreads(setOf(selected, descendant))

        val restored = initial.withRestoredThread(selected)

        assertEquals(false, restored.threads.getValue(selected).archived)
        assertEquals(true, restored.threads.getValue(descendant).archived)
        assertEquals(emptySet<CodexThreadLocator>(), restored.threadAttachments.attached)
        assertEquals(emptySet<CodexThreadLocator>(), restored.threadAttachments.dealerClaims)
    }

    @Test
    fun deleteNotificationsPurgeOnlyExactHostQualifiedLocalState() {
        val root = CodexThreadLocator("spark", "same-id")
        val child = CodexThreadLocator("spark", "child")
        val otherHost = CodexThreadLocator("u4090", "same-id")
        val initial = lifecycleState(root, child, otherHost)

        val deleted = initial.withDeletedThreads(setOf(root, child))

        assertEquals(setOf(otherHost), deleted.threads.keys)
        assertEquals(setOf(otherHost), deleted.threadAttachments.attached)
        assertEquals(mapOf(otherHost to "draft-u4090"), deleted.threadActions.drafts)
        assertEquals(listOf("u4090/same-id"), deleted.cards.map { it.conversationId })
        assertEquals(setOf(otherHost), deleted.knownBlockingRequestThreads)
    }

    private fun lifecycleState(vararg locators: CodexThreadLocator): DealerUiState {
        val attachments = locators.fold(ThreadAttachmentState()) { state, locator ->
            state.attach(locator).claim(locator)
        }
        val actions = locators.fold(com.code2hack.pokerdealer.domain.ThreadActionState()) { state, locator ->
            state.editDraft(locator, "draft-${locator.hostId}")
        }
        return DealerUiState(
            threadAttachments = attachments,
            threadActions = actions,
            knownBlockingRequestThreads = locators.toSet(),
            threads = locators.associateWith { locator ->
                DiscoveredThread(
                    locator = locator,
                    workState = ThreadWorkState.READY,
                    ephemeral = false,
                    attached = true,
                    intendedControlSurface = ControlSurface.DEALER,
                )
            },
            cards = locators.mapIndexed { index, locator ->
                M1TurnInput("turn", locator.threadId, "client-$index")
                    .pendingUserCard("${locator.hostId}/${locator.threadId}", index.toLong())
            },
        )
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
