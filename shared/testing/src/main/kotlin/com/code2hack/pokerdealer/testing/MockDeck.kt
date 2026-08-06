package com.code2hack.pokerdealer.testing

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardRole
import com.code2hack.pokerdealer.domain.CardSource
import com.code2hack.pokerdealer.domain.CardState
import com.code2hack.pokerdealer.domain.CodexDistribution
import com.code2hack.pokerdealer.domain.CodexHost
import com.code2hack.pokerdealer.domain.CodexHostKind
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ControlSurface
import com.code2hack.pokerdealer.domain.Conversation
import com.code2hack.pokerdealer.domain.ConversationState
import com.code2hack.pokerdealer.domain.DaemonState
import com.code2hack.pokerdealer.domain.HostArchitecture
import com.code2hack.pokerdealer.domain.HostAvailabilityClass
import com.code2hack.pokerdealer.domain.HostConnectionRoute
import com.code2hack.pokerdealer.domain.HostConnectionState
import com.code2hack.pokerdealer.domain.InitialCodexHosts
import com.code2hack.pokerdealer.domain.PokerPileMetadata
import com.code2hack.pokerdealer.domain.ThreadPileReducer
import com.code2hack.pokerdealer.domain.ThreadWorkEvidence

object MockDeck {
    val sparkHost = CodexHost(
        id = "spark",
        displayName = "DGX Spark",
        kind = CodexHostKind.LINUX_WORKSTATION,
        architecture = HostArchitecture.LINUX_ARM64,
        distribution = CodexDistribution.OPENAI_UPSTREAM,
        connectionRoutes = listOf(
            HostConnectionRoute.SSH_LAN,
            HostConnectionRoute.SSH_EMBEDDED_TSNET,
            HostConnectionRoute.SSH_EXTERNAL_TAILSCALE,
        ),
        activeConnectionRoute = HostConnectionRoute.SSH_EMBEDDED_TSNET,
        availabilityClass = HostAvailabilityClass.PERSISTENT,
        connectionState = HostConnectionState.CONNECTED,
        daemonState = DaemonState.READY,
        codexVersion = "mock-current",
        appServerVersion = "mock-current",
    )

    val u4090Host = InitialCodexHosts.u4090.copy(
        activeConnectionRoute = HostConnectionRoute.SSH_LAN,
        connectionState = HostConnectionState.CONNECTED,
        daemonState = DaemonState.READY,
        codexVersion = "mock-current",
        appServerVersion = "mock-current",
    )

    val termuxHost = CodexHost(
        id = "fold6-termux",
        displayName = "Fold6 Termux",
        kind = CodexHostKind.TERMUX_ANDROID,
        architecture = HostArchitecture.ANDROID_ARM64,
        distribution = CodexDistribution.TERMUX_COMMUNITY,
        connectionRoutes = listOf(HostConnectionRoute.SSH_LOOPBACK),
        availabilityClass = HostAvailabilityClass.OPPORTUNISTIC,
        connectionState = HostConnectionState.WAITING_FOR_HOST_APP,
        daemonState = DaemonState.UNKNOWN,
    )

    val hosts = listOf(sparkHost, u4090Host, termuxHost)
    val host = u4090Host
    val sparkPileLocator = CodexThreadLocator("spark", "thr_mock_spark_busy")
    val termuxPileLocator = CodexThreadLocator("fold6-termux", "thr_mock_termux_ready")

    val conversation = Conversation(
        id = "mock-conversation",
        locator = CodexThreadLocator(
            hostId = host.id,
            threadId = "thr_mock_poker_dealer",
        ),
        alias = "Poker–Dealer implementation",
        state = ConversationState.ACTIVE,
        intendedControlSurface = ControlSurface.DEALER,
        lastSequence = 1,
        unreadCount = 1,
    )

    val pileMetadata: PokerPileMetadata = ThreadPileReducer().run {
        attach(
            sparkPileLocator,
            ThreadWorkEvidence(activeTurn = true, unresolvedRequestCount = 0),
            atMs = 1,
        )
        attach(
            conversation.locator,
            ThreadWorkEvidence(activeTurn = true, unresolvedRequestCount = 1),
            atMs = 2,
        )
        attach(
            termuxPileLocator,
            ThreadWorkEvidence(activeTurn = false, unresolvedRequestCount = 0),
            atMs = 3,
        )
        view(conversation.locator)
        metadata()
    }

    val longCard = Card(
        id = "mock-card-1",
        conversationId = conversation.id,
        sequence = 1,
        revision = 1,
        role = CardRole.AGENT,
        state = CardState.COMMITTED,
        fullText = longCardText(),
        conclusion = "The mock projection preserves and displays the complete Codex agent message.",
        createdAtMs = 1_784_600_000_000,
        updatedAtMs = 1_784_600_000_000,
        source = CardSource.CODEX_AGENT_MESSAGE,
    )

    val cardTextByLocator: Map<CodexThreadLocator, String> = mapOf(
        sparkPileLocator to "DGX Spark busy pile\n\nThe focused card remains keyed by the Spark locator.",
        conversation.locator to longCard.fullText,
        termuxPileLocator to "Fold6 Termux ready pile\n\nThe focused card remains keyed by the Termux locator.",
    )

    private fun longCardText(): String = buildString {
        appendLine("Poker–Dealer three-host Codex-thread mock vertical slice")
        appendLine()
        appendLine("This card deliberately exceeds 20,000 characters. It represents a structured agent-message projection from a daemon-backed Codex thread and preserves Unicode, indentation, and complete text.")
        appendLine()
        appendLine("Configured hosts: DGX Spark, u4090, and Fold6 Termux.")
        appendLine("Workstation route priority: trusted LAN, embedded tsnet, optional external Tailscale fallback.")
        appendLine()
        var line = 1
        while (length < 20_000) {
            append(line.toString().padStart(4, '0'))
            append("  DGX Spark / thr_mock_poker_dealer  │  full text stays intact  │  ♠ ♥ ♦ ♣  │  中文  │  ")
            appendLine("val delta$line = \"agent-message-$line\"")
            line += 1
        }
    }
}
